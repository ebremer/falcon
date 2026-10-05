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
     * Chunked storage (layout class 2). {@code indexType} selects the chunk index
     * ({@link #INDEX_V1_BTREE} for version-3 layouts, or one of the version-4/5 index types);
     * {@code indexAddress} points at that index (or, for a single-chunk index, at the chunk itself) and
     * is undefined when no chunk has been written; {@code chunkDimensions} is the chunk shape in
     * elements; {@code elementSize} is one element's size in bytes.
     *
     * <p>{@code flags} are the version-4/5 layout flags ({@link #FLAG_DONT_FILTER_PARTIAL_BOUND_CHUNKS},
     * {@link #FLAG_SINGLE_INDEX_WITH_FILTER}). A filtered single-chunk index stores the chunk's
     * filtered size and filter mask in the layout message itself ({@code singleChunkSize},
     * {@code singleChunkFilterMask}); both are {@code -1}/{@code 0} otherwise.
     */
    record Chunked(int indexType, long indexAddress, int[] chunkDimensions, int elementSize,
                   int flags, long singleChunkSize, int singleChunkFilterMask)
            implements DataLayout {

        /** A chunked layout with no version-4/5 flags (a version-3 v1-B-tree layout). */
        public Chunked(int indexType, long indexAddress, int[] chunkDimensions, int elementSize) {
            this(indexType, indexAddress, chunkDimensions, elementSize, 0, -1, 0);
        }

        /** True if partial edge chunks are stored without passing through the filter pipeline. */
        public boolean dontFilterPartialBoundChunks() {
            return (flags & FLAG_DONT_FILTER_PARTIAL_BOUND_CHUNKS) != 0;
        }
    }

    /**
     * Virtual storage (layout class 3): the dataset's data is assembled from selections of other
     * datasets. The mapping list lives in the global-heap object at {@code globalHeapAddress}/{@code
     * index}.
     */
    record Virtual(long globalHeapAddress, int index) implements DataLayout {
    }

    /** Version-3 layout: a version-1 B-tree index. */
    int INDEX_V1_BTREE = 0;
    /** Version-4/5 index types. */
    int INDEX_SINGLE_CHUNK = 1;
    int INDEX_IMPLICIT = 2;
    int INDEX_FIXED_ARRAY = 3;
    int INDEX_EXTENSIBLE_ARRAY = 4;
    int INDEX_V2_BTREE = 5;

    /** Layout flag (v4/5): partial edge chunks are written unfiltered ({@code H5Pset_chunk_opts}). */
    int FLAG_DONT_FILTER_PARTIAL_BOUND_CHUNKS = 0x01;
    /** Layout flag (v4/5): a single-chunk index carries the filtered chunk size and filter mask. */
    int FLAG_SINGLE_INDEX_WITH_FILTER = 0x02;
}
