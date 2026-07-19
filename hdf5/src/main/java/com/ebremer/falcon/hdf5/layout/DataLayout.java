package com.ebremer.falcon.hdf5.layout;

/**
 * A dataset's storage layout (from Data Layout message 8).
 *
 * <ul>
 *   <li>{@link Compact} &mdash; element data stored inline in the object header.</li>
 *   <li>{@link Contiguous} &mdash; one contiguous block at a file address (which may be undefined if
 *       the dataset is unallocated, in which case reads yield the fill value).</li>
 *   <li>{@link Chunked} &mdash; data split into indexed chunks (read support arrives in stage H4).</li>
 * </ul>
 */
public sealed interface DataLayout {

    /** Inline data (layout class 0). */
    record Compact(byte[] data) implements DataLayout {
    }

    /** A single contiguous block (layout class 1); {@code address} may be undefined (unallocated). */
    record Contiguous(long address, long size) implements DataLayout {
    }

    /** Chunked storage (layout class 2). A placeholder until stage H4 fills in the chunk index. */
    record Chunked() implements DataLayout {
    }
}
