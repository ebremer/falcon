package com.ebremer.falcon.hdf5.io;

import com.ebremer.falcon.hdf5.ExternalFileAccess;
import com.ebremer.falcon.hdf5.HdfClosedException;
import java.nio.file.Path;

/**
 * Per-file addressing context threaded through the format parsers: the mapped bytes plus the
 * superblock's "size of offsets" (file address width) and "size of lengths" (object size width). The
 * file's own path, root group, and {@link ExternalFileAccess} policy are carried too, so soft links can
 * resolve absolute paths and external raw data and virtual-dataset sources can be located.
 *
 * <p>Shared by every object of one open file, including across threads: it is immutable apart from the
 * (thread-safe) decoded-chunk cache and the closed flag.
 *
 * <p>Internal type &mdash; lives in a non-exported package.
 */
public final class FileContext {

    private final HdfBuffer buffer;
    private final int sizeOfOffsets;
    private final int sizeOfLengths;
    private final Path path;
    private final long rootAddress;
    private final ExternalFileAccess externalFileAccess;
    private final ChunkCache chunkCache = new ChunkCache(); // per-file decoded-chunk cache
    private volatile boolean closed;

    public FileContext(HdfBuffer buffer, int sizeOfOffsets, int sizeOfLengths) {
        this(buffer, sizeOfOffsets, sizeOfLengths, null, HdfBuffer.UNDEFINED_ADDRESS, ExternalFileAccess.sameDirectory());
    }

    public FileContext(HdfBuffer buffer, int sizeOfOffsets, int sizeOfLengths, Path path, long rootAddress,
                       ExternalFileAccess externalFileAccess) {
        this.buffer = buffer;
        this.sizeOfOffsets = sizeOfOffsets;
        this.sizeOfLengths = sizeOfLengths;
        this.path = path;
        this.rootAddress = rootAddress;
        this.externalFileAccess = externalFileAccess;
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

    /** The root group's object-header address. */
    public long rootAddress() {
        return rootAddress;
    }

    /** Which other files this file may make Falcon open. */
    public ExternalFileAccess externalFileAccess() {
        return externalFileAccess;
    }

    public int sizeOfOffsets() {
        return sizeOfOffsets;
    }

    public int sizeOfLengths() {
        return sizeOfLengths;
    }

    /** Marks the file closed: every later read of it fails with {@link HdfClosedException}. */
    public void markClosed() {
        closed = true;
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
