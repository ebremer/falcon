package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * The implicit chunk index (version-4/5 layout, index type 2). When a chunked dataset has fixed
 * dimensions, no filters, and early allocation, the library stores every chunk &mdash; including
 * never-written ones &mdash; contiguously in row-major chunk-grid order at a single base address, with
 * no on-disk index structure to consult. Chunk number {@code i} therefore sits at {@code base +
 * i·chunkBytes}, and its element offset is recovered from its position in the chunk grid.
 */
public final class ImplicitIndex {

    private ImplicitIndex() {
    }

    /** Enumerates every chunk of the grid covering {@code datasetDims} in row-major order. */
    public static List<ChunkRecord> readChunks(long baseAddress, int chunkBytes, long[] datasetDims,
                                               int[] chunkDims) {
        int rank = datasetDims.length;
        int[] gridDims = new int[rank];
        long chunkCount = 1;
        for (int d = 0; d < rank; d++) {
            gridDims[d] = (int) ((datasetDims[d] + chunkDims[d] - 1) / chunkDims[d]);
            chunkCount *= gridDims[d];
        }
        List<ChunkRecord> chunks = new ArrayList<>((int) chunkCount);
        int[] gridCoord = new int[rank];
        for (long i = 0; i < chunkCount; i++) {
            long[] offset = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = (long) gridCoord[d] * chunkDims[d];
            }
            chunks.add(new ChunkRecord(offset, baseAddress + i * chunkBytes, chunkBytes, 0));
            for (int d = rank - 1; d >= 0; d--) { // advance the grid coordinate, last dimension fastest
                if (++gridCoord[d] < gridDims[d]) {
                    break;
                }
                gridCoord[d] = 0;
            }
        }
        return chunks;
    }
}
