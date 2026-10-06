package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * The implicit chunk index (version-4/5 layout, index type 2). When a chunked dataset has fixed
 * dimensions, no filters, and early allocation, the library stores every chunk of the <em>maximum</em>
 * chunk grid &mdash; including never-written ones &mdash; contiguously at a single base address, with
 * no on-disk index structure to consult. The chunk with linear index {@code i} (see {@link ChunkGrid})
 * therefore sits at {@code base + i·chunkBytes} ({@code H5D__none_idx_get_addr}).
 */
public final class ImplicitIndex {

    private ImplicitIndex() {
    }

    /** Enumerates every chunk of the grid covering the dataset's current extent. */
    public static List<ChunkRecord> readChunks(long baseAddress, int chunkBytes, ChunkGrid grid, long fileSize) {
        long[] gridDims = grid.currentChunks();
        int rank = gridDims.length;
        long chunkCount = 1;
        long limit = fileSize / Math.max(1, chunkBytes) + 1; // no more chunks than fit in the file
        for (long g : gridDims) {
            if (g == 0) {
                return List.of();
            }
            if (g > limit || chunkCount > limit / g) {
                throw new HdfFormatException("implicit chunk index covers more chunks than fit in the file");
            }
            chunkCount *= g;
        }
        List<ChunkRecord> chunks = new ArrayList<>((int) chunkCount);
        long[] scaled = new long[rank];
        for (long i = 0; i < chunkCount; i++) {
            long linear = grid.linearIndex(scaled);
            chunks.add(new ChunkRecord(grid.chunkOffset(linear), baseAddress + linear * chunkBytes, chunkBytes, 0));
            for (int d = rank - 1; d >= 0; d--) { // advance the grid coordinate, last dimension fastest
                if (++scaled[d] < gridDims[d]) {
                    break;
                }
                scaled[d] = 0;
            }
        }
        return chunks;
    }

    /** Finds a chunk by arithmetic on its linear index: every chunk of the grid is stored. */
    public static ChunkLookup lookup(long baseAddress, int chunkBytes, ChunkGrid grid, long fileSize) {
        return cell -> {
            long linear = grid.linearIndex(cell);
            long address;
            try {
                address = Math.addExact(baseAddress, Math.multiplyExact(linear, (long) chunkBytes));
            } catch (ArithmeticException e) {
                throw new HdfFormatException("implicit chunk index addresses a chunk past the file");
            }
            if (address > fileSize) {
                throw new HdfFormatException("implicit chunk index addresses a chunk past the file");
            }
            return new ChunkRecord(grid.offsetOf(cell), address, chunkBytes, 0);
        };
    }
}
