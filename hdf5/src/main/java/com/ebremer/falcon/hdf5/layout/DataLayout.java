package com.ebremer.falcon.hdf5.layout;

/**
 * A dataset's storage layout (from Data Layout message 8).
 *
 * <ul>
 *   <li>{@link Compact} &mdash; element data stored inline in the object header.</li>
 *   <li>{@link Contiguous} &mdash; one contiguous block at a file address (which may be undefined if
 *       the dataset is unallocated, in which case reads yield the fill value).</li>
 *   <li>{@link Chunked} &mdash; data split into fixed-size chunks located through an index. Stage H4
 *       reads the version-1 B-tree (type 1) index used by earliest-libver files; the newer index
 *       types arrive with new-style navigation in stage H5.</li>
 * </ul>
 */
public sealed interface DataLayout {

    /** Inline data (layout class 0). */
    record Compact(byte[] data) implements DataLayout {
    }

    /** A single contiguous block (layout class 1); {@code address} may be undefined (unallocated). */
    record Contiguous(long address, long size) implements DataLayout {
    }

    /**
     * Chunked storage (layout class 2). {@code indexAddress} points at the chunk index (a version-1
     * B-tree for the formats read in stage H4); {@code chunkDimensions} is the chunk shape in
     * elements; {@code elementSize} is the size of one element in bytes.
     */
    record Chunked(long indexAddress, int[] chunkDimensions, int elementSize) implements DataLayout {
    }
}
