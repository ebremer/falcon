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
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
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
     * The dataset's chunk index: its chunks looked up one at a time in the file's index, or read from it
     * all at once, as each read needs (see {@link ChunkIndex}). {@code maxDims} are the dataspace's maximum
     * dimensions (or {@code null} if it stores none): the array-style chunk indexes number their chunks
     * over the maximum chunk grid, so they are needed to locate each chunk. {@code loadedBytes} is told what
     * the index takes in memory when it is read whole.
     */
    public static ChunkIndex readIndex(FileContext ctx, DataLayout.Chunked layout, long[] datasetDims, long[] maxDims,
                                       int elementSize, java.util.function.LongConsumer loadedBytes) {
        int[] chunkDims = layout.chunkDimensions();
        int chunkBytes = chunkBytes(chunkDims, elementSize);
        int rank = datasetDims.length;
        if (chunkDims.length != rank) {
            throw new HdfFormatException("chunk rank " + chunkDims.length + " does not match dataset rank " + rank);
        }
        long address = layout.indexAddress();
        if (address == HdfBuffer.UNDEFINED_ADDRESS) {
            return ChunkIndex.of(List.of(), chunkDims); // no chunk ever written: it all reads as the fill value
        }
        ChunkLookup lookup = switch (layout.indexType()) {
            case DataLayout.INDEX_V1_BTREE -> ChunkBTreeV1.lookup(ctx, address, rank, chunkDims);
            case DataLayout.INDEX_SINGLE_CHUNK -> null; // one chunk: the index is the layout message
            case DataLayout.INDEX_IMPLICIT -> ImplicitIndex.lookup(address, chunkBytes,
                    ChunkGrid.forFixedArray(datasetDims, maxDims, chunkDims), ctx.buffer().size());
            case DataLayout.INDEX_FIXED_ARRAY -> FixedArray.lookup(ctx, address, chunkBytes,
                    ChunkGrid.forFixedArray(datasetDims, maxDims, chunkDims));
            case DataLayout.INDEX_EXTENSIBLE_ARRAY -> ExtensibleArray.lookup(ctx, address, chunkBytes,
                    ChunkGrid.forExtensibleArray(datasetDims, maxDims, chunkDims));
            case DataLayout.INDEX_V2_BTREE -> ChunkBTreeV2.lookup(ctx, address, chunkBytes, datasetDims, chunkDims);
            default -> throw new HdfUnsupportedException("unknown chunk index type " + layout.indexType());
        };
        return ChunkIndex.of(chunkDims, datasetDims, lookup,
                () -> enumerateChunks(ctx, layout, chunkBytes, datasetDims, maxDims), loadedBytes);
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
     * the points fall in, each read once however many points it holds. Elements are copied out of a chunk a
     * run at a time (P2 PF8): a block of the last dimension in one copy, and indices a stride apart in one
     * loop.
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
            int[] order = byCell(points, n, chunkDims, datasetDims);
            long[] cell = new long[rank];
            long[] next = new long[rank];
            for (int k = 0; k < n; ) {
                cellOf(points.at(order[k]), chunkDims, cell);
                int end = k + 1;
                while (end < n && java.util.Arrays.equals(cellOf(points.at(order[end]), chunkDims, next), cell)) {
                    end++;
                }
                ChunkRecord chunk = index.at(cell);
                if (chunk != null) {
                    byte[] bytes = readChunk(ctx, layout, chunk, datasetDims, pipeline, elementSize, chunkBytes);
                    long[] offset = chunk.offset();
                    // Points listed one after another, next to each other in the last dimension: one copy.
                    for (int j = k; j < end; ) {
                        int i = order[j];
                        long[] at = points.at(i);
                        int run = 1;
                        while (j + run < end && order[j + run] == i + run && follows(points.at(i + run), at, run)) {
                            run++;
                        }
                        copyRun(bytes, offset, chunkDims, at, output, i, run, 1, elementSize);
                        j += run;
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
            product.forEachRunInBox(offset, box, (position, coordinates, length, step) ->
                    copyRun(bytes, offset, chunkDims, coordinates, output, position, length, step, elementSize));
        }
        return output;
    }

    /**
     * Copies {@code length} elements of a decoded chunk, from the one at {@code coordinates} on, each
     * {@code step} after the one before in the last dimension, to {@code output} from position
     * {@code position} on: in one copy when they are next to each other.
     */
    private static void copyRun(byte[] chunk, long[] chunkOffset, int[] chunkDims, long[] coordinates,
                                byte[] output, long position, long length, long step, int elementSize) {
        long flat = 0;
        for (int d = 0; d < chunkDims.length; d++) {
            flat = flat * chunkDims[d] + (coordinates[d] - chunkOffset[d]);
        }
        int from = (int) (flat * elementSize);
        int to = (int) (position * elementSize);
        if (step == 1) {
            System.arraycopy(chunk, from, output, to, (int) (length * elementSize));
            return;
        }
        int stride = (int) (step * elementSize);
        switch (elementSize) { // one move an element, for the common sizes (the bytes' order is kept)
            case 8 -> {
                for (long k = 0; k < length; k++, from += stride, to += 8) {
                    LONGS.set(output, to, (long) LONGS.get(chunk, from));
                }
            }
            case 4 -> {
                for (long k = 0; k < length; k++, from += stride, to += 4) {
                    INTS.set(output, to, (int) INTS.get(chunk, from));
                }
            }
            case 2 -> {
                for (long k = 0; k < length; k++, from += stride, to += 2) {
                    SHORTS.set(output, to, (short) SHORTS.get(chunk, from));
                }
            }
            default -> {
                for (long k = 0; k < length; k++, from += stride, to += elementSize) {
                    System.arraycopy(chunk, from, output, to, elementSize);
                }
            }
        }
    }

    private static final java.lang.invoke.VarHandle LONGS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.nativeOrder());
    private static final java.lang.invoke.VarHandle INTS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(int[].class, java.nio.ByteOrder.nativeOrder());
    private static final java.lang.invoke.VarHandle SHORTS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(short[].class, java.nio.ByteOrder.nativeOrder());

    /** The grid cell of the chunk that holds the element at {@code at}, into {@code cell}. */
    private static long[] cellOf(long[] at, int[] chunkDims, long[] cell) {
        for (int d = 0; d < cell.length; d++) {
            cell[d] = at[d] / chunkDims[d];
        }
        return cell;
    }

    /** True if {@code at} is the element {@code distance} after {@code start} in the last dimension. */
    private static boolean follows(long[] at, long[] start, int distance) {
        int last = start.length - 1;
        if (last < 0) {
            return false;
        }
        for (int d = 0; d < last; d++) {
            if (at[d] != start[d]) {
                return false;
            }
        }
        return at[last] == start[last] + distance;
    }

    /**
     * The points' indices ordered by the chunk-grid cell each falls in (row-major), points of one cell in
     * their own order: by one sort of numbers, each a cell's row-major number and a point's index, when
     * those fit in a {@code long}, else by comparing cells.
     */
    private static int[] byCell(SelectedElements.Listed points, int n, int[] chunkDims, long[] datasetDims) {
        int rank = chunkDims.length;
        long cells = 1;
        boolean fits = true;
        long[] grid = new long[rank];
        for (int d = 0; d < rank && fits; d++) {
            grid[d] = (datasetDims[d] + chunkDims[d] - 1) / chunkDims[d];
            fits = grid[d] == 0 || cells <= Long.MAX_VALUE / Math.max(1, grid[d]) / Math.max(1, n);
            cells *= Math.max(1, grid[d]);
        }
        int[] order = new int[n];
        if (fits) {
            long[] keys = new long[n];
            long[] cell = new long[rank];
            for (int i = 0; i < n; i++) {
                cellOf(points.at(i), chunkDims, cell);
                long linear = 0;
                for (int d = 0; d < rank; d++) {
                    linear = linear * Math.max(1, grid[d]) + cell[d];
                }
                keys[i] = linear * n + i; // the cell first, then the point's own place
            }
            java.util.Arrays.sort(keys);
            for (int k = 0; k < n; k++) {
                order[k] = (int) (keys[k] % n);
            }
            return order;
        }
        long[][] cellOf = new long[n][];
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) {
            cellOf[i] = cellOf(points.at(i), chunkDims, new long[rank]);
            boxed[i] = i;
        }
        java.util.Arrays.sort(boxed, (a, b) -> java.util.Arrays.compare(cellOf[a], cellOf[b])); // stable
        for (int k = 0; k < n; k++) {
            order[k] = boxed[k];
        }
        return order;
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
