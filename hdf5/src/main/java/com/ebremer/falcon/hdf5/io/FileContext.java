package com.ebremer.falcon.hdf5.io;

import com.ebremer.falcon.hdf5.ExternalFileAccess;
import com.ebremer.falcon.hdf5.HdfClosedException;
import com.ebremer.falcon.hdf5.OpenOptions;
import com.ebremer.falcon.hdf5.header.SharedMessageTable;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-file addressing context threaded through the format parsers: the mapped bytes plus the
 * superblock's "size of offsets" (file address width) and "size of lengths" (object size width). The
 * file's own path, root group, and {@link OpenOptions} are carried too, so soft links can resolve
 * absolute paths and external raw data and virtual-dataset sources can be located and read as asked, and
 * so are the superblock extension, which locates the shared-message table, the root group's symbol
 * table as a version 0&ndash;1 superblock caches it, and where in the file its addresses start.
 *
 * <p>Shared by every object of one open file, including across threads: it is immutable apart from the
 * (thread-safe) decoded-chunk cache, the lazily read shared-message table, the per-file resources (such as
 * the source files virtual datasets keep open), and the closed flag.
 *
 * <p>Internal type &mdash; lives in a non-exported package.
 */
public final class FileContext {

    private final HdfBuffer buffer;
    private final int sizeOfOffsets;
    private final int sizeOfLengths;
    private final Path path;
    private final long rootAddress;
    private final OpenOptions options;
    private final long superblockExtensionAddress;
    private final long rootBTreeAddress; // the root group's symbol table, as the superblock caches it
    private final long rootHeapAddress;
    private final long baseAddress; // where in the file address 0 is: the size of its user block
    private final ChunkCache chunkCache; // per-file decoded-chunk cache
    private volatile SharedMessageTable sharedMessageTable; // read on first use, then cached
    private final ConcurrentHashMap<Class<?>, AutoCloseable> resources = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public FileContext(HdfBuffer buffer, int sizeOfOffsets, int sizeOfLengths) {
        this(buffer, sizeOfOffsets, sizeOfLengths, null, HdfBuffer.UNDEFINED_ADDRESS, OpenOptions.defaults(),
                HdfBuffer.UNDEFINED_ADDRESS, HdfBuffer.UNDEFINED_ADDRESS, HdfBuffer.UNDEFINED_ADDRESS, 0);
    }

    /**
     * @param buffer      the file's bytes from its base address on: address 0 is the buffer's first byte
     * @param baseAddress where in the file that is, in bytes from its start: 0 unless it has a user block
     */
    public FileContext(HdfBuffer buffer, int sizeOfOffsets, int sizeOfLengths, Path path, long rootAddress,
                       OpenOptions options, long superblockExtensionAddress, long rootBTreeAddress,
                       long rootHeapAddress, long baseAddress) {
        this.buffer = buffer;
        this.sizeOfOffsets = sizeOfOffsets;
        this.sizeOfLengths = sizeOfLengths;
        this.path = path;
        this.rootAddress = rootAddress;
        this.options = options;
        this.superblockExtensionAddress = superblockExtensionAddress;
        this.rootBTreeAddress = rootBTreeAddress;
        this.rootHeapAddress = rootHeapAddress;
        this.baseAddress = baseAddress;
        this.chunkCache = new ChunkCache(options.chunkCacheSize());
    }

    /** The file's bytes; fails with {@link HdfClosedException} once the file is closed. */
    public HdfBuffer buffer() {
        checkOpen();
        return buffer;
    }

    /** This file's decoded-chunk cache, shared across reads (and reading threads) of the file. */
    public ChunkCache chunkCache() {
        return chunkCache;
    }

    /** The file's path, or {@code null} if unknown (e.g. a buffer not backed by a file). */
    public Path path() {
        return path;
    }

    /**
     * The directory the file is in, as an absolute path, against which the names of external raw data
     * and virtual-dataset source files are resolved; {@code null} if the file was not opened from a path.
     */
    public Path directory() {
        return path == null ? null : path.toAbsolutePath().getParent();
    }

    /** The root group's object-header address. */
    public long rootAddress() {
        return rootAddress;
    }

    /** How the file was opened. */
    public OpenOptions options() {
        return options;
    }

    /** Which other files this file may make Falcon open. */
    public ExternalFileAccess externalFileAccess() {
        return options.externalFileAccess();
    }

    /** The superblock extension's object-header address, or undefined if the file has none. */
    public long superblockExtensionAddress() {
        return superblockExtensionAddress;
    }

    /**
     * The address of the root group's B-tree as a version 0&ndash;1 superblock caches it in its root symbol
     * table entry, or undefined: what stands in for the root group's Symbol Table message where that is
     * corrupt.
     */
    public long rootBTreeAddress() {
        return rootBTreeAddress;
    }

    /** The address of the root group's local heap, cached as {@link #rootBTreeAddress()} is, or undefined. */
    public long rootHeapAddress() {
        return rootHeapAddress;
    }

    /**
     * Where in the file its addresses start, in bytes from the start of the file: every address the file
     * records, and every offset into {@link #buffer()}, is that far from the file's first byte. It is where
     * the superblock lies, so 0 unless the file starts with a user block, and libhdf5's {@code H5F_BASE_ADDR}.
     */
    public long baseAddress() {
        return baseAddress;
    }

    /** The file's shared-message (SOHM) table, read on first use. */
    public SharedMessageTable sharedMessageTable() {
        SharedMessageTable result = sharedMessageTable;
        if (result == null) {
            result = SharedMessageTable.parse(this);
            sharedMessageTable = result;
        }
        return result;
    }

    public int sizeOfOffsets() {
        return sizeOfOffsets;
    }

    public int sizeOfLengths() {
        return sizeOfLengths;
    }

    /**
     * This file's resource of type {@code type}, made by {@code create} on first use and closed when the
     * file is: the files its virtual datasets read, for one.
     */
    public <T extends AutoCloseable> T resource(Class<T> type, Supplier<T> create) {
        checkOpen();
        return type.cast(resources.computeIfAbsent(type, key -> create.get()));
    }

    /**
     * Marks the file closed, so every later read of it fails with {@link HdfClosedException}, and closes
     * its resources.
     */
    public void markClosed() {
        closed = true;
        for (AutoCloseable resource : resources.values()) {
            try {
                resource.close();
            } catch (Exception e) {
                // closing what this file opened cannot fail the close of the file itself
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Throws {@link HdfClosedException} if the file has been closed. */
    public void checkOpen() {
        if (closed) {
            throw new HdfClosedException(path == null ? "the HDF5 file is closed" : "the HDF5 file is closed: " + path);
        }
    }

    /** Reads a file address ("size of offsets" wide) at {@code off}, mapping all-ones to undefined. */
    public long readAddress(long off) {
        return buffer().getAddress(off, sizeOfOffsets);
    }

    /** Reads an object length ("size of lengths" wide) at {@code off}. */
    public long readLength(long off) {
        return buffer().getUnsignedValue(off, sizeOfLengths);
    }
}
