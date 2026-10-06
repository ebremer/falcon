package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.io.MappedHdfFile;
import com.ebremer.falcon.hdf5.io.PagedSource;
import com.ebremer.falcon.hdf5.message.BTreeKValuesMessage;
import com.ebremer.falcon.hdf5.message.DriverInfoMessage;
import com.ebremer.falcon.hdf5.message.FileSpaceInfoMessage;
import com.ebremer.falcon.hdf5.superblock.Superblock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * An open, read-only HDF5 file &mdash; the entry point of Falcon's read API.
 *
 * <p>Open a file, walk its hierarchy from {@link #root()}, and {@link #close()} to unmap it (or use
 * try-with-resources). A file that begins with a user block (as MATLAB v7.3 {@code .mat} files do) is
 * read like any other.
 *
 * <pre>{@code
 * try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
 *     for (String name : h5.root().childNames()) { ... }
 * }
 * }</pre>
 *
 * <p><b>Sources.</b> A file opened from a {@link Path} is memory-mapped, so it must remain available
 * while this object is open; on a file system whose files cannot be mapped (a zip file system, an
 * in-memory one), it is read on demand through a channel instead. One already in memory opens from its
 * bytes ({@link #open(byte[])}), and one elsewhere (an object store, an HTTP server, a channel) from a
 * {@link RangeReader}, which Falcon reads on demand: the metadata it parses and the data it is asked for.
 * A read that the reader fails throws {@link java.io.UncheckedIOException}.
 *
 * <p><b>Other files.</b> External raw data and virtual-dataset sources are opened only as the
 * {@link ExternalFileAccess} policy allows: by default, files in this file's own directory tree; a
 * {@linkplain ExternalFileAccess#resolvedBy resolver} can open them from anywhere, such as next to a file
 * read through a {@link RangeReader}. {@link OpenOptions} set it, how virtual datasets with unlimited
 * mappings are read, and the cache sizes.
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

    private final Runnable release; // unmaps the file, or drops a paged source's cache
    private final Superblock superblock;
    private final FileContext ctx;
    private final Group root;

    private Hdf5File(Runnable release, Superblock superblock, FileContext ctx, Group root) {
        this.release = release;
        this.superblock = superblock;
        this.ctx = ctx;
        this.root = root;
    }

    /**
     * Opens and memory-maps an HDF5 file for reading, with the {@linkplain OpenOptions#defaults() default options}.
     *
     * @param path the file
     * @return the open file, to be closed by the caller
     * @throws IOException if the file cannot be opened, mapped, or read
     * @throws HdfFormatException if it holds no HDF5 superblock, or its first metadata is corrupt
     */
    public static Hdf5File open(Path path) throws IOException {
        return open(path, OpenOptions.defaults());
    }

    /**
     * Opens and memory-maps an HDF5 file for reading; {@code externalFileAccess} decides which other
     * files (external raw data, virtual-dataset sources) it may make Falcon open.
     *
     * @param path               the file
     * @param externalFileAccess the policy for the other files it names
     * @return the open file, to be closed by the caller
     * @throws IOException if the file cannot be opened, mapped, or read
     * @throws HdfFormatException if it holds no HDF5 superblock, or its first metadata is corrupt
     */
    public static Hdf5File open(Path path, ExternalFileAccess externalFileAccess) throws IOException {
        return open(path, OpenOptions.defaults().externalFileAccess(externalFileAccess));
    }

    /**
     * Opens and memory-maps an HDF5 file for reading, as {@code options} say. A file whose file system
     * cannot map it is read on demand through a channel instead, as through a {@link RangeReader}
     * ({@link OpenOptions#readerPageSize} and {@link OpenOptions#readerCacheSize} apply); the channel is
     * closed with the file.
     *
     * @param path    the file
     * @param options the policy for other files, and the cache sizes
     * @return the open file, to be closed by the caller
     * @throws IOException if the file cannot be opened, mapped, or read
     * @throws HdfFormatException if it holds no HDF5 superblock, or its first metadata is corrupt
     */
    public static Hdf5File open(Path path, OpenOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        MappedHdfFile mapped;
        try {
            mapped = MappedHdfFile.openReadOnly(path);
        } catch (UnsupportedOperationException e) {
            return openUnmapped(path, options); // a file system without memory-mapped channels
        }
        return open(mapped.buffer(), path, mapped::close, options);
    }

    /** Opens {@code path} read on demand through a channel of its file system, which closes with the file. */
    private static Hdf5File openUnmapped(Path path, OpenOptions options) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        PagedSource source;
        try {
            source = PagedSource.open(RangeReader.of(channel), options.readerPageSize(), options.readerCacheSize());
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return open(new HdfBuffer(source), path, () -> {
            source.close();
            try {
                channel.close();
            } catch (IOException e) {
                // nothing more is read through it
            }
        }, options);
    }

    /**
     * Opens an HDF5 file held in memory, with the {@linkplain OpenOptions#defaults() default options}. The
     * array is read in place, not copied, so it must not change while the file is open.
     *
     * @param bytes the whole file
     * @return the open file
     * @throws HdfFormatException if it holds no HDF5 superblock, or its first metadata is corrupt
     */
    public static Hdf5File open(byte[] bytes) {
        return open(bytes, OpenOptions.defaults());
    }

    /**
     * Opens an HDF5 file held in memory, as {@code options} say. The array is read in place, not copied,
     * so it must not change while the file is open. The file has no directory of its own, so the default
     * {@link ExternalFileAccess} policy opens no other file (see {@link ExternalFileAccess}).
     *
     * @param bytes   the whole file
     * @param options the policy for other files, and the cache sizes
     * @return the open file
     * @throws HdfFormatException if it holds no HDF5 superblock, or its first metadata is corrupt
     */
    public static Hdf5File open(byte[] bytes, OpenOptions options) {
        Objects.requireNonNull(options, "options");
        try {
            return open(new HdfBuffer(MemorySegment.ofArray(bytes)), null, () -> { }, options);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // not reached: bytes in memory are never read through a reader
        }
    }

    /**
     * Opens an HDF5 file read through {@code reader} on demand, with the
     * {@linkplain OpenOptions#defaults() default options}.
     *
     * @param reader the file's bytes, read a range at a time
     * @return the open file, to be closed by the caller (which does not close the reader)
     * @throws IOException if the reader cannot report the size or read the file's first metadata
     */
    public static Hdf5File open(RangeReader reader) throws IOException {
        return open(reader, OpenOptions.defaults());
    }

    /**
     * Opens an HDF5 file read through {@code reader} on demand, as {@code options} say. The reader must
     * stay usable while the file is open; Falcon does not close it. The file has no directory of its own,
     * so the default {@link ExternalFileAccess} policy opens no other file (see {@link ExternalFileAccess}).
     *
     * @param reader  the file's bytes, read a range at a time
     * @param options the policy for other files, the reader's page and cache sizes, and the other caches
     * @return the open file, to be closed by the caller (which does not close the reader)
     * @throws IOException if the reader cannot report the size or read the file's first metadata
     */
    public static Hdf5File open(RangeReader reader, OpenOptions options) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(options, "options");
        PagedSource source = PagedSource.open(reader, options.readerPageSize(), options.readerCacheSize());
        return open(new HdfBuffer(source), null, source::close, options);
    }

    /** Opens the file whose bytes {@code data} reads; {@code release} runs on close, or if opening fails. */
    private static Hdf5File open(HdfBuffer data, Path path, Runnable release, OpenOptions options) throws IOException {
        try {
            Superblock superblock = Superblock.parse(data);
            // Every file address is relative to the base address, which is the superblock's own offset:
            // non-zero when the file starts with a user block (e.g. MATLAB v7.3 .mat files, h5py
            // userblock_size=). Like libhdf5 (H5F__super_read), use where the superblock actually sits
            // rather than the stored base address, then read through a view that starts there.
            long base = superblock.location();
            if (base != 0) {
                data = data.slice(base, data.size() - base);
            }
            FileContext ctx = new FileContext(data, superblock.sizeOfOffsets(), superblock.sizeOfLengths(),
                    path, superblock.rootObjectHeaderAddress(), options, superblock.superblockExtensionAddress());
            Group root = Group.root(ctx, superblock.rootObjectHeaderAddress());
            return new Hdf5File(release, superblock, ctx, root);
        } catch (UncheckedIOException e) {
            release.run();
            throw e.getCause(); // a reader failure while opening is the open's own I/O error
        } catch (RuntimeException e) {
            release.run();
            throw e;
        }
    }

    /**
     * The root group ({@code "/"}).
     *
     * @return the root group, from which every path is found
     */
    public Group root() {
        return root;
    }

    /** This file's context, which every object read from it shares. */
    FileContext context() {
        return ctx;
    }

    /**
     * The file's path, or {@code null} if it was opened from bytes or a {@link RangeReader}.
     *
     * @return the path given to {@link #open(Path)}, or null
     */
    public Path path() {
        return ctx.path();
    }

    /**
     * The HDF5 superblock format version (0–3) of this file.
     *
     * @return 0 or 1 for the original format, 2 or 3 for the checksummed one of HDF5 1.8 and later
     */
    public int superblockVersion() {
        return superblock.version();
    }

    /**
     * This file's free-space management settings, if it records them (a File Space Info message in the
     * superblock extension). Empty for the common case of a file written with the default strategy and
     * no superblock extension.
     *
     * @return the settings, or empty if the file records none
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
     *
     * @return the K values, never null
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
     *
     * @return the driver's identifier and its stored settings, or empty for the default driver
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

    /**
     * True until {@link #close()} is called.
     *
     * @return whether the file is still open
     */
    public boolean isOpen() {
        return !ctx.isClosed();
    }

    /**
     * Unmaps the file (or closes the channel it was read through), or releases what was cached from a
     * {@link RangeReader} (which stays open). Closes the files its virtual datasets opened. Calling it again
     * does nothing.
     */
    @Override
    public void close() {
        ctx.markClosed();
        release.run();
    }
}
