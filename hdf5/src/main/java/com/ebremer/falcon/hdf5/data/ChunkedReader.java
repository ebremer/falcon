package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.btree.ChunkBTreeV1;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.util.List;

/**
 * Assembles a chunked dataset's full element data into a single contiguous, row-major byte array by
 * reading each chunk (via the chunk index), reversing its filter pipeline, and copying its in-bounds
 * region into place. Boundary chunks that extend past the dataset extent contribute only their valid
 * elements; any elements no chunk covers keep the fill value.
 */
public final class ChunkedReader {

    private ChunkedReader() {
    }

    public static byte[] assemble(FileContext ctx, DataLayout.Chunked layout, long[] datasetDims,
                                  int elementSize, FilterPipeline pipeline, byte[] fill) {
        long elements = 1;
        for (long d : datasetDims) {
            elements *= d;
        }
        byte[] output = new byte[Math.toIntExact(elements * elementSize)];
        tileFill(output, fill, elementSize);

        int rank = datasetDims.length;
        int[] chunkDims = layout.chunkDimensions();
        int chunkElements = 1;
        for (int d : chunkDims) {
            chunkElements *= d;
        }
        int chunkBytes = chunkElements * elementSize;
        List<ChunkRecord> chunks = ChunkBTreeV1.read(ctx, layout.indexAddress(), rank);
        for (ChunkRecord chunk : chunks) {
            byte[] raw = ctx.buffer().getBytes(chunk.address(), chunk.size());
            byte[] bytes = pipeline == null ? raw
                    : pipeline.decode(raw, chunk.filterMask(), elementSize, chunkBytes);
            copyChunk(output, datasetDims, chunkDims, chunk.offset(), bytes, elementSize);
        }
        return output;
    }

    /** Copies a decoded chunk into the output buffer, clamped to the dataset's extent. */
    private static void copyChunk(byte[] output, long[] datasetDims, int[] chunkDims, long[] chunkOffset,
                                  byte[] chunk, int elementSize) {
        int rank = datasetDims.length;
        if (rank == 0) {
            System.arraycopy(chunk, 0, output, 0, elementSize);
            return;
        }

        int last = rank - 1;
        long run = Math.min(chunkDims[last], datasetDims[last] - chunkOffset[last]);
        if (run <= 0) {
            return;
        }

        long[] dsStride = rowMajorStrides(datasetDims);
        int[] chunkStride = rowMajorStrides(chunkDims);

        int[] local = new int[rank]; // local coordinates within the chunk; the last stays 0
        while (true) {
            boolean inBounds = true;
            long dsFlat = 0;
            long chunkFlat = 0;
            for (int d = 0; d < rank; d++) {
                long global = chunkOffset[d] + local[d];
                if (d < last && global >= datasetDims[d]) {
                    inBounds = false;
                    break;
                }
                dsFlat += global * dsStride[d];
                chunkFlat += (long) local[d] * chunkStride[d];
            }
            if (inBounds) {
                System.arraycopy(chunk, (int) (chunkFlat * elementSize),
                        output, (int) (dsFlat * elementSize), (int) (run * elementSize));
            }
            int d = last - 1;
            while (d >= 0) {
                if (++local[d] < chunkDims[d]) {
                    break;
                }
                local[d] = 0;
                d--;
            }
            if (d < 0) {
                break;
            }
        }
    }

    private static long[] rowMajorStrides(long[] dims) {
        long[] stride = new long[dims.length];
        long s = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            stride[i] = s;
            s *= dims[i];
        }
        return stride;
    }

    private static int[] rowMajorStrides(int[] dims) {
        int[] stride = new int[dims.length];
        int s = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            stride[i] = s;
            s *= dims[i];
        }
        return stride;
    }

    private static void tileFill(byte[] output, byte[] fill, int elementSize) {
        if (fill == null || fill.length == 0) {
            return;
        }
        for (int off = 0; off + elementSize <= output.length; off += elementSize) {
            System.arraycopy(fill, 0, output, off, Math.min(elementSize, fill.length));
        }
    }
}
