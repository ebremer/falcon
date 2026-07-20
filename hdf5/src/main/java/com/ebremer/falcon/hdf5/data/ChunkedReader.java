package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.btree.ChunkBTreeV1;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.index.ChunkBTreeV2;
import com.ebremer.falcon.hdf5.index.ExtensibleArray;
import com.ebremer.falcon.hdf5.index.FixedArray;
import com.ebremer.falcon.hdf5.index.ImplicitIndex;
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
        byte[] output = new byte[Elements.checkedByteCount(elements, elementSize)];
        tileFill(output, fill, elementSize);

        int rank = datasetDims.length;
        int[] chunkDims = layout.chunkDimensions();
        long chunkElements = 1;
        for (int d : chunkDims) {
            chunkElements *= d;
        }
        int chunkBytes = Elements.checkedByteCount(chunkElements, elementSize);
        for (ChunkRecord chunk : enumerateChunks(ctx, layout, chunkBytes, datasetDims, chunkDims, rank)) {
            copyChunk(output, datasetDims, chunkDims, chunk.offset(),
                    readChunk(ctx, chunk, pipeline, elementSize, chunkBytes), elementSize);
        }
        return output;
    }

    /**
     * Assembles just the hyperslab {@code [selOffset, selOffset+selCount)}: only the chunks that overlap
     * the selection are read and de-filtered (the rest are skipped), so reading a small window of a
     * large chunked dataset does not touch the whole dataset.
     */
    public static byte[] assembleSelection(FileContext ctx, DataLayout.Chunked layout, long[] datasetDims,
                                           int elementSize, FilterPipeline pipeline, byte[] fill,
                                           long[] selOffset, long[] selCount) {
        long selElements = 1;
        for (long c : selCount) {
            selElements *= c;
        }
        byte[] output = new byte[Elements.checkedByteCount(selElements, elementSize)];
        tileFill(output, fill, elementSize);

        int rank = datasetDims.length;
        int[] chunkDims = layout.chunkDimensions();
        long chunkElements = 1;
        for (int d : chunkDims) {
            chunkElements *= d;
        }
        int chunkBytes = Elements.checkedByteCount(chunkElements, elementSize);
        for (ChunkRecord chunk : enumerateChunks(ctx, layout, chunkBytes, datasetDims, chunkDims, rank)) {
            if (!overlaps(chunk.offset(), chunkDims, datasetDims, selOffset, selCount)) {
                continue;
            }
            byte[] bytes = readChunk(ctx, chunk, pipeline, elementSize, chunkBytes);
            copyIntersection(output, selOffset, selCount, chunk.offset(), chunkDims, datasetDims, bytes, elementSize);
        }
        return output;
    }

    private static List<ChunkRecord> enumerateChunks(FileContext ctx, DataLayout.Chunked layout, int chunkBytes,
                                                     long[] datasetDims, int[] chunkDims, int rank) {
        return switch (layout.indexType()) {
            case DataLayout.INDEX_V1_BTREE -> ChunkBTreeV1.read(ctx, layout.indexAddress(), rank);
            case DataLayout.INDEX_SINGLE_CHUNK ->
                    List.of(new ChunkRecord(new long[rank], layout.indexAddress(), chunkBytes, 0));
            case DataLayout.INDEX_IMPLICIT ->
                    ImplicitIndex.readChunks(layout.indexAddress(), chunkBytes, datasetDims, chunkDims);
            case DataLayout.INDEX_FIXED_ARRAY ->
                    FixedArray.readChunks(ctx, layout.indexAddress(), chunkBytes, datasetDims, chunkDims);
            case DataLayout.INDEX_EXTENSIBLE_ARRAY ->
                    ExtensibleArray.readChunks(ctx, layout.indexAddress(), chunkBytes, datasetDims, chunkDims);
            case DataLayout.INDEX_V2_BTREE ->
                    ChunkBTreeV2.readChunks(ctx, layout.indexAddress(), chunkBytes, datasetDims, chunkDims);
            default -> throw new HdfUnsupportedException(
                    "chunk index type " + layout.indexType() + " is implemented in a later increment");
        };
    }

    private static byte[] readChunk(FileContext ctx, ChunkRecord chunk, FilterPipeline pipeline,
                                    int elementSize, int chunkBytes) {
        byte[] raw = ctx.buffer().getBytes(chunk.address(), chunk.size());
        byte[] bytes = pipeline == null ? raw
                : pipeline.decode(raw, chunk.filterMask(), elementSize, chunkBytes);
        if (bytes.length < chunkBytes) {
            throw new HdfFormatException("decoded chunk is " + bytes.length + " bytes, expected " + chunkBytes);
        }
        return bytes;
    }

    /** True if a chunk (clamped to the dataset extent) intersects the selection in every dimension. */
    private static boolean overlaps(long[] chunkOffset, int[] chunkDims, long[] datasetDims,
                                    long[] selOffset, long[] selCount) {
        for (int d = 0; d < chunkOffset.length; d++) {
            long lo = Math.max(chunkOffset[d], selOffset[d]);
            long hi = Math.min(Math.min(chunkOffset[d] + chunkDims[d], datasetDims[d]), selOffset[d] + selCount[d]);
            if (lo >= hi) {
                return false;
            }
        }
        return true;
    }

    /** Copies the chunk&cap;selection intersection into {@code output} (shaped like the selection). */
    private static void copyIntersection(byte[] output, long[] selOffset, long[] selCount, long[] chunkOffset,
                                         int[] chunkDims, long[] datasetDims, byte[] chunk, int elementSize) {
        int rank = datasetDims.length;
        if (rank == 0) {
            System.arraycopy(chunk, 0, output, 0, elementSize);
            return;
        }
        long[] lo = new long[rank];
        long[] hi = new long[rank];
        for (int d = 0; d < rank; d++) {
            lo[d] = Math.max(chunkOffset[d], selOffset[d]);
            hi[d] = Math.min(Math.min(chunkOffset[d] + chunkDims[d], datasetDims[d]), selOffset[d] + selCount[d]);
        }
        int last = rank - 1;
        long run = hi[last] - lo[last];
        long[] outStride = rowMajorStrides(selCount);
        int[] chunkStride = rowMajorStrides(chunkDims);
        long[] g = lo.clone(); // current global coordinate; the last dimension stays at lo[last]
        while (true) {
            long chunkFlat = 0;
            long outFlat = 0;
            for (int d = 0; d < rank; d++) {
                chunkFlat += (g[d] - chunkOffset[d]) * chunkStride[d];
                outFlat += (g[d] - selOffset[d]) * outStride[d];
            }
            System.arraycopy(chunk, (int) (chunkFlat * elementSize),
                    output, (int) (outFlat * elementSize), (int) (run * elementSize));
            int d = last - 1;
            while (d >= 0) {
                if (++g[d] < hi[d]) {
                    break;
                }
                g[d] = lo[d];
                d--;
            }
            if (d < 0) {
                break;
            }
        }
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
