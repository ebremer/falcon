package com.ebremer.falcon.hdf5.io;

import java.nio.file.Path;

/**
 * Per-file addressing context threaded through the format parsers: the mapped bytes plus the
 * superblock's "size of offsets" (file address width) and "size of lengths" (object size width). The
 * file's own path is carried too, so a virtual dataset can resolve relative source-file names.
 *
 * <p>Internal type &mdash; lives in a non-exported package.
 */
public final class FileContext {

    private final HdfBuffer buffer;
    private final int sizeOfOffsets;
    private final int sizeOfLengths;
    private final Path path;
    private ChunkCache chunkCache; // per-file decoded-chunk cache, created on first use

    public FileContext(HdfBuffer buffer, int sizeOfOffsets, int sizeOfLengths) {
        this(buffer, sizeOfOffsets, sizeOfLengths, null);
    }

    public FileContext(HdfBuffer buffer, int sizeOfOffsets, int sizeOfLengths, Path path) {
        this.buffer = buffer;
        this.sizeOfOffsets = sizeOfOffsets;
        this.sizeOfLengths = sizeOfLengths;
        this.path = path;
    }

    public HdfBuffer buffer() {
        return buffer;
    }

    /** This file's decoded-chunk cache (created lazily), shared across reads of the file. */
    public ChunkCache chunkCache() {
        if (chunkCache == null) {
            chunkCache = new ChunkCache();
        }
        return chunkCache;
    }

    /** The file's path, or {@code null} if unknown (e.g. a buffer not backed by a file). */
    public Path path() {
        return path;
    }

    public int sizeOfOffsets() {
        return sizeOfOffsets;
    }

    public int sizeOfLengths() {
        return sizeOfLengths;
    }

    /** Reads a file address ("size of offsets" wide) at {@code off}, mapping all-ones to undefined. */
    public long readAddress(long off) {
        return buffer.getAddress(off, sizeOfOffsets);
    }

    /** Reads an object length ("size of lengths" wide) at {@code off}. */
    public long readLength(long off) {
        return buffer.getUnsignedValue(off, sizeOfLengths);
    }
}
