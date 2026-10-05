package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.btree.ChunkBTreeV1;
import com.ebremer.falcon.hdf5.filter.FilterPipeline;
import com.ebremer.falcon.hdf5.index.ChunkBTreeV2;
import com.ebremer.falcon.hdf5.index.ChunkGrid;
import com.ebremer.falcon.hdf5.index.ExtensibleArray;
import com.ebremer.falcon.hdf5.index.FixedArray;
import com.ebremer.falcon.hdf5.index.ImplicitIndex;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.util.List;

/**
 * Assembles a chunked dataset's element data, whole or a hyperslab of it, into a contiguous row-major
 * byte array: each chunk the {@link ChunkIndex} finds overlapping is read, its filter pipeline reversed,
 * and its in-bounds part copied into place. Boundary chunks that extend past the dataset extent
 * contribute only their valid elements; any elements no chunk covers keep the fill value.
 */
public final class ChunkedReader {

    private ChunkedReader() {
    }

    /**
     * Reads the dataset's chunk index, once: every chunk stored. {@code maxDims} are the dataspace's
     * maximum dimensions (or {@code null} if it stores none): the array-style chunk indexes number their
     * chunks over the maximum chunk grid, so they are needed to locate each chunk.
     */
    public static ChunkIndex readIndex(FileContext ctx, DataLayout.Chunked layout, long[] datasetDims, long[] maxDims,
                                       int elementSize) {
        int chunkBytes = chunkBytes(layout.chunkDimensions(), elementSize);
        return ChunkIndex.of(enumerateChunks(ctx, layout, chunkBytes, datasetDims, maxDims), layout.chunkDimensions());
    }

    /** Assembles the whole dataset from the chunks within its extent. */
    public static byte[] assemble(FileContext ctx, DataLayout.Chunked layout, ChunkIndex index, long[] datasetDims,
                                  int elementSize, FilterPipeline pipeline, byte[] fill) {
        byte[] output = new byte[Elements.checkedByteCount(product(datasetDims), elementSize)];
        tileFill(output, fill, elementSize);

        int[] chunkDims = layout.chunkDimensions();
        int chunkBytes = chunkBytes(chunkDims, elementSize);
        for (ChunkRecord chunk : index.overlapping(new long[datasetDims.length], datasetDims, datasetDims)) {
            copyChunk(output, datasetDims, chunkDims, chunk.offset(),
                    readChunk(ctx, layout, chunk, datasetDims, pipeline, elementSize, chunkBytes), elementSize);
        }
        return output;
    }

    /**
     * Assembles just the hyperslab {@code [selOffset, selOffset+selCount)}: only the chunks that overlap
     * the selection are looked up, read, and de-filtered, so reading a small window of a large chunked
     * dataset does not touch the whole dataset.
     */
    public static byte[] assembleSelection(FileContext ctx, DataLayout.Chunked layout, ChunkIndex index,
                                           long[] datasetDims, int elementSize, FilterPipeline pipeline, byte[] fill,
                                           long[] selOffset, long[] selCount) {
        byte[] output = new byte[Elements.checkedByteCount(product(selCount), elementSize)];
        tileFill(output, fill, elementSize);

        int[] chunkDims = layout.chunkDimensions();
        int chunkBytes = chunkBytes(chunkDims, elementSize);
        for (ChunkRecord chunk : index.overlapping(selOffset, selCount, datasetDims)) {
            byte[] bytes = readChunk(ctx, layout, chunk, datasetDims, pipeline, elementSize, chunkBytes);
            copyIntersection(output, selOffset, selCount, chunk.offset(), chunkDims, datasetDims, bytes, elementSize);
        }
        return output;
    }

    /**
     * Gathers the selected elements, in the selection's order: only the chunks that hold a selected element
     * are read. For a regular hyperslab those are the chunks in the grid cells its indices fall in, in
     * every dimension, so a strided selection skips the chunks between its blocks; for points, the chunks
     * the points fall in, each read once however many points it holds.
     */
    public static byte[] gather(FileContext ctx, DataLayout.Chunked layout, ChunkIndex index, long[] datasetDims,
                                int elementSize, FilterPipeline pipeline, byte[] fill, SelectedElements selection) {
        byte[] output = new byte[Elements.checkedByteCount(selection.count(), elementSize)];
        tileFill(output, fill, elementSize);
        if (selection.count() == 0) {
            return output;
        }
        int[] chunkDims = layout.chunkDimensions();
        int chunkBytes = chunkBytes(chunkDims, elementSize);
        int rank = datasetDims.length;
        if (selection instanceof SelectedElements.Listed points) {
            // Order the points by the chunk they fall in, then read each chunk once for all of its points.
            int n = (int) points.count();
            long[][] cellOf = new long[n][rank];
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                long[] c = points.at(i);
                for (int d = 0; d < rank; d++) {
                    cellOf[i][d] = c[d] / chunkDims[d];
                }
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> java.util.Arrays.compare(cellOf[a], cellOf[b]));
            for (int k = 0; k < n; ) {
                long[] cell = cellOf[order[k]];
                int end = k;
                while (end < n && java.util.Arrays.equals(cellOf[order[end]], cell)) {
                    end++;
                }
                ChunkRecord chunk = index.at(cell);
                if (chunk != null) {
                    byte[] bytes = readChunk(ctx, layout, chunk, datasetDims, pipeline, elementSize, chunkBytes);
                    for (int j = k; j < end; j++) {
                        int i = order[j];
                        copyElement(bytes, chunk.offset(), chunkDims, points.at(i), output, i, elementSize);
                    }
                }
                k = end;
            }
            return output;
        }
        SelectedElements.Product product = (SelectedElements.Product) selection;
        long[][] cells = new long[rank][];
        for (int d = 0; d < rank; d++) {
            cells[d] = product.cells(d, chunkDims[d]);
        }
        long[] box = new long[rank];
        for (ChunkRecord chunk : index.inGrid(cells)) {
            long[] offset = chunk.offset();
            for (int d = 0; d < rank; d++) {
                box[d] = Math.max(0, Math.min(chunkDims[d], datasetDims[d] - offset[d]));
            }
            byte[] bytes = readChunk(ctx, layout, chunk, datasetDims, pipeline, elementSize, chunkBytes);
            product.forEachInBox(offset, box, (position, coordinates) ->
                    copyElement(bytes, offset, chunkDims, coordinates, output, position, elementSize));
        }
        return output;
    }

    /** Copies the element at {@code coordinates} of a decoded chunk to position {@code position} of {@code output}. */
    private static void copyElement(byte[] chunk, long[] chunkOffset, int[] chunkDims, long[] coordinates,
                                    byte[] output, long position, int elementSize) {
        long flat = 0;
        for (int d = 0; d < chunkDims.length; d++) {
            flat = flat * chunkDims[d] + (coordinates[d] - chunkOffset[d]);
        }
        System.arraycopy(chunk, (int) (flat * elementSize), output, (int) (position * elementSize), elementSize);
    }

    private static List<ChunkRecord> enumerateChunks(FileContext ctx, DataLayout.Chunked layout, int chunkBytes,
                                                     long[] datasetDims, long[] maxDims) {
        int rank = datasetDims.length;
        int[] chunkDims = layout.chunkDimensions();
        if (chunkDims.length != rank) {
            throw new HdfFormatException("chunk rank " + chunkDims.length + " does not match dataset rank " + rank);
        }
        if (layout.indexAddress() == HdfBuffer.UNDEFINED_ADDRESS) {
            return List.of(); // no chunk has ever been written: the whole dataset reads as the fill value
        }
        return switch (layout.indexType()) {
            case DataLayout.INDEX_V1_BTREE -> ChunkBTreeV1.read(ctx, layout.indexAddress(), rank);
            case DataLayout.INDEX_SINGLE_CHUNK -> {
                // A filtered single chunk records its stored size and filter mask in the layout message.
                long size = layout.singleChunkSize() >= 0 ? layout.singleChunkSize() : chunkBytes;
                if (size > Integer.MAX_VALUE) {
                    throw new HdfFormatException("single chunk is too large: " + size + " bytes");
                }
                yield List.of(new ChunkRecord(new long[rank], layout.indexAddress(), (int) size,
                        layout.singleChunkFilterMask()));
            }
            case DataLayout.INDEX_IMPLICIT -> ImplicitIndex.readChunks(layout.indexAddress(), chunkBytes,
                    ChunkGrid.forFixedArray(datasetDims, maxDims, chunkDims), ctx.buffer().size());
            case DataLayout.INDEX_FIXED_ARRAY -> FixedArray.readChunks(ctx, layout.indexAddress(), chunkBytes,
                    ChunkGrid.forFixedArray(datasetDims, maxDims, chunkDims));
            case DataLayout.INDEX_EXTENSIBLE_ARRAY -> ExtensibleArray.readChunks(ctx, layout.indexAddress(), chunkBytes,
                    ChunkGrid.forExtensibleArray(datasetDims, maxDims, chunkDims));
            case DataLayout.INDEX_V2_BTREE ->
                    ChunkBTreeV2.readChunks(ctx, layout.indexAddress(), chunkBytes, datasetDims, chunkDims);
            default -> throw new HdfUnsupportedException("unknown chunk index type " + layout.indexType());
        };
    }

    private static byte[] readChunk(FileContext ctx, DataLayout.Chunked layout, ChunkRecord chunk,
                                    long[] datasetDims, FilterPipeline pipeline, int elementSize, int chunkBytes) {
        boolean filtered = pipeline != null
                && !(layout.dontFilterPartialBoundChunks() && isPartialEdgeChunk(chunk.offset(), layout, datasetDims));
        if (!filtered) {
            // Unfiltered chunks are a cheap copy straight from the memory mapping; no need to cache.
            if (chunk.size() < chunkBytes) {
                throw new HdfFormatException("unfiltered chunk at " + chunk.address() + " is " + chunk.size()
                        + " bytes, expected " + chunkBytes);
            }
            return ctx.buffer().getBytes(chunk.address(), chunkBytes);
        }
        byte[] cached = ctx.chunkCache().get(chunk.address());
        if (cached != null) {
            return cached;
        }
        byte[] raw = ctx.buffer().getBytes(chunk.address(), chunk.size());
        byte[] bytes = pipeline.decode(raw, chunk.filterMask(), elementSize, chunkBytes);
        if (bytes.length < chunkBytes) {
            throw new HdfFormatException("decoded chunk is " + bytes.length + " bytes, expected " + chunkBytes);
        }
        ctx.chunkCache().put(chunk.address(), bytes);
        return bytes;
    }

    /**
     * True if the chunk extends past the dataset's current extent in any dimension
     * ({@code H5D__chunk_is_partial_edge_chunk}). With the layout's "don't filter partial bound chunks"
     * flag, such chunks are stored without passing through the filter pipeline.
     */
    private static boolean isPartialEdgeChunk(long[] chunkOffset, DataLayout.Chunked layout, long[] datasetDims) {
        int[] chunkDims = layout.chunkDimensions();
        for (int d = 0; d < chunkOffset.length; d++) {
            if (chunkOffset[d] + chunkDims[d] > datasetDims[d]) {
                return true;
            }
        }
        return false;
    }

    private static int chunkBytes(int[] chunkDims, int elementSize) {
        long chunkElements = 1;
        for (int d : chunkDims) {
            chunkElements = multiply(chunkElements, d);
        }
        return Elements.checkedByteCount(chunkElements, elementSize);
    }

    private static long product(long[] dims) {
        long n = 1;
        for (long d : dims) {
            n = multiply(n, d);
        }
        return n;
    }

    private static long multiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new HdfFormatException("dataset extent overflows: " + a + " x " + b);
        }
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
