package com.ebremer.falcon.hdf5.io;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A file's bytes with some ranges of addresses read from elsewhere in the file: the entries of a metadata
 * cache image, each of which stands for the bytes at its own address but is stored in the image block (see
 * {@code MetadataCacheImage}). A read within one range, or outside them all, is a view of the file's bytes
 * as before; only a read that crosses the edge of a range is copied together.
 *
 * <p>Immutable, and as thread-safe as the buffer it reads.
 */
final class RedirectedSource extends ByteSource {

    /** The largest read that is put together in a heap array; larger ones are allocated off-heap. */
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;

    private final HdfBuffer file;
    private final long[] starts;  // each range's first address, ascending; the ranges do not overlap
    private final long[] ends;    // the address after each range's last
    private final long[] sources; // where in the file each range's bytes are

    /**
     * @param file      the file's bytes as stored
     * @param addresses each range's first address, ascending
     * @param lengths   each range's length, positive; a range ends at or before the next one's address
     * @param sources   where in {@code file} each range's bytes are
     * @throws IllegalArgumentException if the ranges are not ascending and apart, or reach outside the file
     */
    RedirectedSource(HdfBuffer file, long[] addresses, long[] lengths, long[] sources) {
        int count = addresses.length;
        if (lengths.length != count || sources.length != count) {
            throw new IllegalArgumentException("the ranges' addresses, lengths, and sources differ in number");
        }
        long size = file.size();
        long[] ends = new long[count];
        for (int i = 0; i < count; i++) {
            long length = lengths[i];
            if (length <= 0 || addresses[i] < (i == 0 ? 0 : ends[i - 1]) || length > size - addresses[i]
                    || sources[i] < 0 || length > size - sources[i]) {
                throw new IllegalArgumentException("range " + i + " (" + length + " bytes at " + addresses[i]
                        + ", read from " + sources[i] + ") overlaps the one before it or lies outside the "
                        + size + "-byte file");
            }
            ends[i] = addresses[i] + length;
        }
        this.file = file;
        this.starts = addresses.clone();
        this.ends = ends;
        this.sources = sources.clone();
    }

    @Override
    long size() {
        return file.size();
    }

    @Override
    PagedSource paged() {
        return file.paged();
    }

    @Override
    byte get(long position) {
        int i = rangeAt(position);
        return file.getByte(i < starts.length && starts[i] <= position ? sources[i] + (position - starts[i]) : position);
    }

    @Override
    MemorySegment read(long position, long length) {
        int i = rangeAt(position);
        long end = position + length;
        if (i == starts.length || starts[i] >= end) {
            return file.segmentSlice(position, length); // none of it is redirected
        }
        if (starts[i] <= position && end <= ends[i]) {
            return file.segmentSlice(sources[i] + (position - starts[i]), length); // all of it, in one range
        }
        MemorySegment out = length > MAX_ARRAY ? Arena.ofAuto().allocate(length) : MemorySegment.ofArray(new byte[(int) length]);
        fill(position, out, i);
        return out;
    }

    @Override
    void copy(long position, byte[] destination, int offset, int length) {
        int i = rangeAt(position);
        long end = position + length;
        if (i == starts.length || starts[i] >= end) {
            file.copyTo(position, destination, offset, length);
        } else if (starts[i] <= position && end <= ends[i]) {
            file.copyTo(sources[i] + (position - starts[i]), destination, offset, length);
        } else {
            fill(position, MemorySegment.ofArray(destination).asSlice(offset, length), i);
        }
    }

    @Override
    void copyRequest(long position, byte[] destination, int offset, int length) {
        int i = rangeAt(position);
        long end = position + length;
        if (i == starts.length || starts[i] >= end) {
            file.copyRequest(position, destination, offset, length);
        } else if (starts[i] <= position && end <= ends[i]) {
            file.copyRequest(sources[i] + (position - starts[i]), destination, offset, length);
        } else {
            fill(position, MemorySegment.ofArray(destination).asSlice(offset, length), i);
        }
    }

    /**
     * Fills {@code target} with the bytes from {@code position} on, a piece at a time: each piece lies in one
     * range, or between two. {@code range} is {@link #rangeAt} of {@code position}.
     */
    private void fill(long position, MemorySegment target, int range) {
        long end = position + target.byteSize();
        int i = range;
        for (long at = position; at < end; ) {
            long from;
            long n;
            if (i < starts.length && starts[i] <= at) {
                n = Math.min(end, ends[i]) - at;
                from = sources[i] + (at - starts[i]);
                i++;
            } else {
                n = (i < starts.length ? Math.min(end, starts[i]) : end) - at;
                from = at;
            }
            MemorySegment.copy(file.segmentSlice(from, n), 0, target, at - position, n);
            at += n;
        }
    }

    /** The first range that ends after {@code position}: the one that holds it, or the next one; their number if none. */
    private int rangeAt(long position) {
        int lo = 0;
        int hi = ends.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ends[mid] > position) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}
