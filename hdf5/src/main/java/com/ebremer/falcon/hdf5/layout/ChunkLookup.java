package com.ebremer.falcon.hdf5.layout;

/**
 * Finds one stored chunk in a dataset's chunk index, reading only the part of the index that holds it
 * (P2 PF5): an array index's entry (and the block or page it is in, verified once), or a B-tree's path
 * down to it, as libhdf5's {@code H5D__chunk_lookup} finds a chunk.
 */
@FunctionalInterface
public interface ChunkLookup {

    /**
     * The chunk stored at chunk-grid coordinates {@code cell} (a cell of the grid covering the dataset's
     * current extent), or null if none is stored there.
     */
    ChunkRecord find(long[] cell);
}
