package com.ebremer.falcon.hdf5.layout;

/**
 * Finds one stored chunk in a dataset's chunk index, reading only the part of the index that holds it
 * (P2 PF5): an array index's entry (and the block or page it is in, verified once), or a B-tree's path
 * down to it, as libhdf5's {@code H5D__chunk_lookup} finds a chunk.
 */
@FunctionalInterface
public interface ChunkLookup {

    /** The most cells a hint ({@link #ahead}) names: as many as a small read covers. */
    int AHEAD_CELLS = 4096;

    /**
     * The chunk stored at chunk-grid coordinates {@code cell} (a cell of the grid covering the dataset's
     * current extent), or null if none is stored there.
     */
    ChunkRecord find(long[] cell);

    /**
     * A hint that the cells whose coordinate in every dimension <i>d</i> is one of {@code cells[d]} (each
     * sorted increasing) are about to be looked up, in row-major order (P2 PF12). An index whose parts lie
     * apart in a file that is read on demand (the nodes of a version 1 B-tree) reads those the lookups need
     * together, several requests at once, where the lookups would read them one after another. It changes
     * nothing {@link #find} returns or fails with, and never fails itself. By default it does nothing.
     */
    default void ahead(long[][] cells) {
    }
}
