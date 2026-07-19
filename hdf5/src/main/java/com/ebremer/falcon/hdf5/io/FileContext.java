package com.ebremer.falcon.hdf5.io;

/**
 * Per-file addressing context threaded through the format parsers: the mapped bytes plus the
 * superblock's "size of offsets" (file address width) and "size of lengths" (object size width).
 *
 * <p>Internal type &mdash; lives in a non-exported package.
 */
public final class FileContext {

    private final HdfBuffer buffer;
    private final int sizeOfOffsets;
    private final int sizeOfLengths;

    public FileContext(HdfBuffer buffer, int sizeOfOffsets, int sizeOfLengths) {
        this.buffer = buffer;
        this.sizeOfOffsets = sizeOfOffsets;
        this.sizeOfLengths = sizeOfLengths;
    }

    public HdfBuffer buffer() {
        return buffer;
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
