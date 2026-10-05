package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.io.MappedHdfFile;
import com.ebremer.falcon.hdf5.message.BTreeKValuesMessage;
import com.ebremer.falcon.hdf5.message.DriverInfoMessage;
import com.ebremer.falcon.hdf5.message.FileSpaceInfoMessage;
import com.ebremer.falcon.hdf5.superblock.Superblock;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * An open, read-only HDF5 file &mdash; the entry point of Falcon's read API.
 *
 * <p>Open a file, walk its hierarchy from {@link #root()}, and {@link #close()} to unmap it (or use
 * try-with-resources). Reads are backed by a memory mapping, so the file must remain available for the
 * lifetime of this object. A file that begins with a user block (as MATLAB v7.3 {@code .mat} files do)
 * is read like any other.
 *
 * <pre>{@code
 * try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
 *     for (String name : h5.root().childNames()) { ... }
 * }
 * }</pre>
 *
 * <p><b>Other files.</b> External raw data and virtual-dataset sources are opened only as the
 * {@link ExternalFileAccess} policy allows: by default, files in this file's own directory tree.
 * {@link OpenOptions} set it, and how virtual datasets with unlimited mappings are read.
 *
 * <p><b>Lifecycle.</b> {@link #close()} is idempotent. Once closed, reading anything obtained from the
 * file throws {@link HdfClosedException}; {@link #isOpen()} tells whether it is still open.
 *
 * <p><b>Thread safety.</b> An open file, and every object, attribute and selection obtained from it, may
 * be read from any number of threads at once (for example through {@code dataset.blocks(n).parallel()}):
 * the mapping is read without shared cursors, the decoded-chunk cache is synchronized, and lazily parsed
 * metadata is safely published. Close the file only once those reads have finished; a read already in
 * progress when another thread closes the file may fail with {@link IllegalStateException}.
 */
public final class Hdf5File implements AutoCloseable {

    private final MappedHdfFile mapped;
    private final Superblock superblock;
    private final FileContext ctx;
    private final Group root;

    private Hdf5File(MappedHdfFile mapped, Superblock superblock, FileContext ctx, Group root) {
        this.mapped = mapped;
        this.superblock = superblock;
        this.ctx = ctx;
        this.root = root;
    }

    /** Opens and memory-maps an HDF5 file for reading, with the {@linkplain OpenOptions#defaults() default options}. */
    public static Hdf5File open(Path path) throws IOException {
        return open(path, OpenOptions.defaults());
    }

    /**
     * Opens and memory-maps an HDF5 file for reading; {@code externalFileAccess} decides which other
     * files (external raw data, virtual-dataset sources) it may make Falcon open.
     */
    public static Hdf5File open(Path path, ExternalFileAccess externalFileAccess) throws IOException {
        return open(path, OpenOptions.defaults().externalFileAccess(externalFileAccess));
    }

    /** Opens and memory-maps an HDF5 file for reading, as {@code options} say. */
    public static Hdf5File open(Path path, OpenOptions options) throws IOException {
        java.util.Objects.requireNonNull(options, "options");
        MappedHdfFile mapped = MappedHdfFile.openReadOnly(path);
        try {
            Superblock superblock = Superblock.parse(mapped.buffer());
            // Every file address is relative to the base address, which is the superblock's own offset:
            // non-zero when the file starts with a user block (e.g. MATLAB v7.3 .mat files, h5py
            // userblock_size=). Like libhdf5 (H5F__super_read), use where the superblock actually sits
            // rather than the stored base address, then read through a view that starts there.
            HdfBuffer data = mapped.buffer();
            long base = superblock.location();
            if (base != 0) {
                data = data.slice(base, data.size() - base);
            }
            FileContext ctx = new FileContext(data, superblock.sizeOfOffsets(), superblock.sizeOfLengths(),
                    path, superblock.rootObjectHeaderAddress(), options, superblock.superblockExtensionAddress());
            Group root = Group.root(ctx, superblock.rootObjectHeaderAddress());
            return new Hdf5File(mapped, superblock, ctx, root);
        } catch (RuntimeException e) {
            mapped.close();
            throw e;
        }
    }

    /** The root group ({@code "/"}). */
    public Group root() {
        return root;
    }

    /** The file's path. */
    public Path path() {
        return mapped.path();
    }

    /** The HDF5 superblock format version (0–3) of this file. */
    public int superblockVersion() {
        return superblock.version();
    }

    /**
     * This file's free-space management settings, if it records them (a File Space Info message in the
     * superblock extension). Empty for the common case of a file written with the default strategy and
     * no superblock extension.
     */
    public Optional<FileSpaceInfo> fileSpaceInfo() {
        ctx.checkOpen();
        long extension = ctx.superblockExtensionAddress();
        if (extension == HdfBuffer.UNDEFINED_ADDRESS) {
            return Optional.empty();
        }
        HeaderMessage message = ObjectHeader.parse(ctx, extension).find(MessageType.FILE_SPACE_INFO);
        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(FileSpaceInfoMessage.parse(ctx, message.bodyOffset(), message.bodySize()));
    }

    /**
     * The 'K' values of this file's version-1 B-trees: those a version 0&ndash;1 superblock stores, or a
     * B-tree K Values message in the superblock extension; otherwise libhdf5's
     * {@linkplain BTreeKValues#DEFAULTS defaults}.
     */
    public BTreeKValues btreeKValues() {
        ctx.checkOpen();
        int[] k = superblock.btreeKValues();
        if (k != null) {
            return new BTreeKValues(k[0], k[1], k[2]);
        }
        HeaderMessage message = extensionMessage(MessageType.BTREE_K_VALUES);
        return message == null ? BTreeKValues.DEFAULTS : BTreeKValuesMessage.parse(message.body());
    }

    /**
     * The file driver this file records, if it was written with one other than the default (such as the
     * family driver, whose files are each one member of a set): from a version 0&ndash;1 superblock's
     * driver information block, or a Driver Info message in the superblock extension. Falcon reads the
     * file it opened only, not the other members.
     */
    public Optional<DriverInfo> driverInfo() {
        ctx.checkOpen();
        long block = superblock.driverInfoAddress();
        if (block != HdfBuffer.UNDEFINED_ADDRESS) {
            return Optional.of(DriverInfoMessage.parseBlock(ctx.buffer(), block));
        }
        HeaderMessage message = extensionMessage(MessageType.DRIVER_INFO);
        return message == null ? Optional.empty() : Optional.of(DriverInfoMessage.parse(message.body()));
    }

    /** The superblock extension's message of {@code type}, or null. */
    private HeaderMessage extensionMessage(int type) {
        long extension = ctx.superblockExtensionAddress();
        return extension == HdfBuffer.UNDEFINED_ADDRESS ? null : ObjectHeader.parse(ctx, extension).find(type);
    }

    /** True until {@link #close()} is called. */
    public boolean isOpen() {
        return !ctx.isClosed();
    }

    /** Unmaps the file. Calling it again does nothing. */
    @Override
    public void close() {
        ctx.markClosed();
        mapped.close();
    }
}
