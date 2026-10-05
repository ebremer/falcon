package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.io.MappedHdfFile;
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
 * lifetime of this object.
 *
 * <pre>{@code
 * try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
 *     for (String name : h5.root().childNames()) { ... }
 * }
 * }</pre>
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

    /** Opens and memory-maps an HDF5 file for reading. */
    public static Hdf5File open(Path path) throws IOException {
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
            FileContext ctx = new FileContext(data,
                    superblock.sizeOfOffsets(), superblock.sizeOfLengths(), path);
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
        long extension = superblock.superblockExtensionAddress();
        if (extension == HdfBuffer.UNDEFINED_ADDRESS) {
            return Optional.empty();
        }
        HeaderMessage message = ObjectHeader.parse(ctx, extension).find(MessageType.FILE_SPACE_INFO);
        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(FileSpaceInfoMessage.parse(ctx, message.bodyOffset(), message.bodySize()));
    }

    @Override
    public void close() {
        mapped.close();
    }
}
