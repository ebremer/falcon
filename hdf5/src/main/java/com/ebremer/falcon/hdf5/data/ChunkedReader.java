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
import com.ebremer.falcon.hdf5.io.ChunkWorkers;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.io.RangeBatch;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.util.List;

/**
 * Assembles a chunked dataset's element data, whole or a hyperslab of it, into a contiguous row-major
 * byte array: each chunk the {@link ChunkIndex} finds overlapping is read, its filter pipeline reversed,
 * and its in-bounds part copied into place. Boundary chunks that extend past the dataset extent
 * contribute only their valid elements; any elements no chunk covers keep the fill value. The filters of
 * several chunks are reversed on several threads at once (P2 PF9), and the chunks of a file read on demand
 * are read in few requests, several of those at once (P2 PF11).
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
        Chunks chunks = new Chunks(ctx, layout, datasetDims, pipeline, elementSize, chunkBytes(chunkDims, elementSize));
        List<ChunkRecord> overlapping = index.overlapping(new long[datasetDims.length], datasetDims, datasetDims);
        chunks.forEach(overlapping, (bytes, i) ->
                copyChunk(output, datasetDims, chunkDims, overlapping.get(i).offset(), bytes, elementSize));
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
        Chunks chunks = new Chunks(ctx, layout, datasetDims, pipeline, elementSize, chunkBytes(chunkDims, elementSize));
        List<ChunkRecord> overlapping = index.overlapping(selOffset, selCount, datasetDims);
        chunks.forEach(overlapping, (bytes, i) -> copyIntersection(output, selOffset, selCount,
                overlapping.get(i).offset(), chunkDims, datasetDims, bytes, elementSize));
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
        Chunks chunks = new Chunks(ctx, layout, datasetDims, pipeline, elementSize, chunkBytes(chunkDims, elementSize));
        int rank = datasetDims.length;
        if (selection instanceof SelectedElements.Listed points) {
            // Order the points by the chunk they fall in, then read each chunk once for all of its points:
            // the chunks are looked up first, so that those of a file read on demand can be read together.
            int n = (int) points.count();
            int[] order = byCell(points, n, chunkDims, datasetDims);
            List<ChunkRecord> holding = new java.util.ArrayList<>();
            int[] ends = new int[16]; // chunk i holds the points order[ends[2 * i]] to order[ends[2 * i + 1]]
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
                    if (2 * holding.size() == ends.length) {
                        ends = java.util.Arrays.copyOf(ends, 2 * ends.length);
                    }
                    ends[2 * holding.size()] = k;
                    ends[2 * holding.size() + 1] = end;
                    holding.add(chunk);
                }
                k = end;
            }
            int[] bounds = ends;
            chunks.forEach(holding, (bytes, i) -> copyPoints(bytes, holding.get(i).offset(), chunkDims, points, order,
                    bounds[2 * i], bounds[2 * i + 1], output, elementSize));
            return output;
        }
        SelectedElements.Product product = (SelectedElements.Product) selection;
        long[][] cells = new long[rank][];
        for (int d = 0; d < rank; d++) {
            cells[d] = product.cells(d, chunkDims[d]);
        }
        List<ChunkRecord> inGrid = index.inGrid(cells);
        chunks.forEach(inGrid, (bytes, i) -> {
            long[] offset = inGrid.get(i).offset();
            long[] box = new long[rank];
            for (int d = 0; d < rank; d++) {
                box[d] = Math.max(0, Math.min(chunkDims[d], datasetDims[d] - offset[d]));
            }
            product.forEachRunInBox(offset, box, (position, coordinates, length, step) ->
                    copyRun(bytes, offset, chunkDims, coordinates, output, position, length, step, elementSize));
        });
        return output;
    }

    /**
     * Copies out of a decoded chunk the points {@code order[first..past)} that fall in it, each to its own
     * place in {@code output}: points listed one after another, next to each other in the last dimension,
     * in one copy.
     */
    private static void copyPoints(byte[] chunk, long[] chunkOffset, int[] chunkDims, SelectedElements.Listed points,
                                   int[] order, int first, int past, byte[] output, int elementSize) {
        for (int j = first; j < past; ) {
            int i = order[j];
            long[] at = points.at(i);
            int run = 1;
            while (j + run < past && order[j + run] == i + run && follows(points.at(i + run), at, run)) {
                run++;
            }
            copyRun(chunk, chunkOffset, chunkDims, at, output, i, run, 1, elementSize);
            j += run;
        }
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

    /**
     * The chunks of one read: where they are stored, and how each is read and its filters reversed. A read
     * of several filtered chunks decodes them on several threads (P2 PF9; {@code OpenOptions.decodeThreads}):
     * the reading thread reads each chunk's stored bytes from the file, in turn as on one thread, and hands
     * them to {@link ChunkWorkers}, where the filters are reversed and the decoded elements copied to their
     * places in the read's output. Each chunk's elements have places of their own there, so the copies need no
     * ordering.
     *
     * <p>The chunks of a file read on demand, through a {@code RangeReader}, are not read one request at a
     * time (P2 PF11; {@code OpenOptions.readerRequests}): the reading thread reads the stored bytes of the
     * chunks ahead a {@link RangeBatch} at a time, those next to each other in the file in one request and
     * several requests at once, and then hands each chunk on in turn as before.
     */
    private record Chunks(FileContext ctx, DataLayout.Chunked layout, long[] datasetDims, FilterPipeline pipeline,
                          int elementSize, int chunkBytes) {

        /** Reads chunks: each one's decoded bytes go to {@code copy}, on this thread or another, once. */
        private interface Reads {
            /** Reads the chunk from the file, or takes it from the chunk cache. */
            void read(ChunkRecord chunk, java.util.function.Consumer<byte[]> copy);

            /** Decodes a filtered chunk whose stored bytes, {@code raw}, have been read. */
            void decode(ChunkRecord chunk, byte[] raw, java.util.function.Consumer<byte[]> copy);
        }

        /**
         * Hands the decoded bytes of each of {@code chunks}, with its place in the list, to {@code copy},
         * which may run on several threads at once.
         */
        void forEach(List<ChunkRecord> chunks, java.util.function.ObjIntConsumer<byte[]> copy) {
            decoding(chunks.size(), reads -> {
                // Each chunk of a file that is mapped or in memory is read when its turn comes: a copy.
                RangeBatch batch = chunks.isEmpty() ? null : ctx.buffer().batch();
                if (batch == null) {
                    for (int i = 0; i < chunks.size(); i++) {
                        int at = i;
                        reads.read(chunks.get(i), bytes -> copy.accept(bytes, at));
                    }
                    return;
                }
                Ahead ahead = new Ahead(this, batch);
                for (int from = 0; from < chunks.size(); ) {
                    int past = ahead.read(chunks, from);
                    for (int i = from; i < past; i++) {
                        int at = i;
                        ahead.take(i - from, chunks.get(i), reads, bytes -> copy.accept(bytes, at));
                    }
                    from = past;
                }
            });
        }

        /**
         * Runs {@code body}, which reads at most {@code atMost} chunks: on this thread alone when there is
         * nothing to decode, or to decode beside, else with their filters reversed on several.
         */
        void decoding(long atMost, java.util.function.Consumer<Reads> body) {
            int threads = pipeline == null || !ChunkWorkers.worthwhile(atMost, chunkBytes) ? 1
                    : ChunkWorkers.threads(ctx.options().decodeThreads());
            if (threads < 2) {
                body.accept(new Reads() {
                    @Override
                    public void read(ChunkRecord chunk, java.util.function.Consumer<byte[]> copy) {
                        copy.accept(Chunks.this.read(chunk));
                    }

                    @Override
                    public void decode(ChunkRecord chunk, byte[] raw, java.util.function.Consumer<byte[]> copy) {
                        copy.accept(Chunks.this.decode(chunk, raw));
                    }
                });
                return;
            }
            ChunkWorkers.<Void>run(threads, null, workers -> {
                Stored stored = new Stored(this, workers);
                try {
                    body.accept(stored);
                } finally {
                    // The chunks still waiting are decoded too: after a failure, since one of them may have
                    // failed before it, as it would have on one thread.
                    stored.decode();
                }
            });
        }

        /** The chunk's decoded bytes, read and decoded on this thread. */
        byte[] read(ChunkRecord chunk) {
            if (!filtered(chunk)) {
                return unfiltered(chunk);
            }
            byte[] cached = ctx.chunkCache().get(chunk.address());
            if (cached != null) {
                return cached;
            }
            return decode(chunk, ctx.buffer().getBytes(chunk.address(), chunk.size()));
        }

        /** Whether the chunk went through the filters: not without any, and not a partial edge chunk kept from them. */
        private boolean filtered(ChunkRecord chunk) {
            return pipeline != null
                    && !(layout.dontFilterPartialBoundChunks() && isPartialEdgeChunk(chunk.offset(), layout, datasetDims));
        }

        /** An unfiltered chunk's bytes: a cheap copy straight from the memory mapping, so not cached. */
        private byte[] unfiltered(ChunkRecord chunk) {
            if (chunk.size() < chunkBytes) {
                throw new HdfFormatException("unfiltered chunk at " + chunk.address() + " is " + chunk.size()
                        + " bytes, expected " + chunkBytes);
            }
            return ctx.buffer().getBytes(chunk.address(), chunkBytes);
        }

        /** The chunk's stored bytes {@code raw} with its filters reversed, and kept in the chunk cache. */
        private byte[] decode(ChunkRecord chunk, byte[] raw) {
            byte[] bytes = pipeline.decode(raw, chunk.filterMask(), elementSize, chunkBytes);
            if (bytes.length < chunkBytes) {
                throw new HdfFormatException("decoded chunk is " + bytes.length + " bytes, expected " + chunkBytes);
            }
            ctx.chunkCache().put(chunk.address(), bytes);
            return bytes;
        }
    }

    /**
     * A read's chunks as the reading thread reads their stored bytes, handed to {@link ChunkWorkers} to be
     * decoded: each a piece of work of its own, or, of chunks smaller than {@link ChunkWorkers#PIECE_BYTES},
     * several to a piece, decoded one after another. An unfiltered chunk, and one found in the chunk cache,
     * has nothing to decode, and is copied by the reading thread at once.
     */
    private static final class Stored implements Chunks.Reads {
        private final Chunks chunks;
        private final ChunkWorkers<Void> workers;
        // The chunks waiting to make up a piece: each one's record, stored bytes, and copy.
        private List<ChunkRecord> records = new java.util.ArrayList<>();
        private List<byte[]> stored = new java.util.ArrayList<>();
        private List<java.util.function.Consumer<byte[]>> copies = new java.util.ArrayList<>();
        private long cost;

        Stored(Chunks chunks, ChunkWorkers<Void> workers) {
            this.chunks = chunks;
            this.workers = workers;
        }

        @Override
        public void read(ChunkRecord chunk, java.util.function.Consumer<byte[]> copy) {
            if (!chunks.filtered(chunk)) {
                copy.accept(chunks.unfiltered(chunk));
                return;
            }
            byte[] cached = chunks.ctx().chunkCache().get(chunk.address());
            if (cached != null) {
                copy.accept(cached);
                return;
            }
            decode(chunk, chunks.ctx().buffer().getBytes(chunk.address(), chunk.size()), copy);
        }

        @Override
        public void decode(ChunkRecord chunk, byte[] raw, java.util.function.Consumer<byte[]> copy) {
            // In flight a chunk holds its stored bytes, its decoded ones, and a filter's output between them.
            long chunkCost = raw.length + 2L * chunks.chunkBytes();
            if (chunks.chunkBytes() >= ChunkWorkers.PIECE_BYTES) {
                workers.add(chunkCost, () -> {
                    copy.accept(chunks.decode(chunk, raw));
                    return null;
                });
                return;
            }
            records.add(chunk);
            stored.add(raw);
            copies.add(copy);
            cost += chunkCost;
            if ((long) records.size() * chunks.chunkBytes() >= ChunkWorkers.PIECE_BYTES) {
                decode();
            }
        }

        /** Hands over the chunks waiting, as one piece of work. */
        void decode() {
            if (records.isEmpty()) {
                return;
            }
            List<ChunkRecord> pieceRecords = records;
            List<byte[]> pieceStored = stored;
            List<java.util.function.Consumer<byte[]>> pieceCopies = copies;
            long pieceCost = cost;
            records = new java.util.ArrayList<>();
            stored = new java.util.ArrayList<>();
            copies = new java.util.ArrayList<>();
            cost = 0;
            workers.add(pieceCost, () -> {
                for (int i = 0; i < pieceRecords.size(); i++) {
                    pieceCopies.get(i).accept(chunks.decode(pieceRecords.get(i), pieceStored.set(i, null)));
                }
                return null;
            });
        }
    }

    /**
     * The chunks of a read of a file read on demand, their stored bytes read ahead a {@link RangeBatch} at a
     * time. A chunk the chunk cache holds needs no reading. One whose stored bytes cannot be read as they
     * are recorded (they lie outside the file, or are too few for an unfiltered chunk) is left out of the
     * batch and read when its turn comes, where it fails as it always has.
     */
    private static final class Ahead {
        private final Chunks chunks;
        private final RangeBatch batch;
        // Of each chunk of the batch: its decoded bytes, if the chunk cache had them; else its range in the
        // batch, or -1 if it is read in its turn.
        private final List<byte[]> cached = new java.util.ArrayList<>();
        private int[] ranges = new int[16];

        Ahead(Chunks chunks, RangeBatch batch) {
            this.chunks = chunks;
            this.batch = batch;
        }

        /**
         * Reads the stored bytes of the chunks from {@code list.get(from)} on, as many as the batch takes.
         *
         * @return the place in the list after the last of them
         */
        int read(List<ChunkRecord> list, int from) {
            batch.clear();
            cached.clear();
            HdfBuffer buffer = chunks.ctx().buffer();
            int past = from;
            for (; past < list.size(); past++) {
                ChunkRecord chunk = list.get(past);
                boolean filtered = chunks.filtered(chunk);
                byte[] decoded = filtered ? chunks.ctx().chunkCache().get(chunk.address()) : null;
                int range = -1;
                if (decoded == null) {
                    int length = filtered ? chunk.size() : chunks.chunkBytes();
                    if ((filtered || chunk.size() >= length) && buffer.holds(chunk.address(), length)) {
                        if (!batch.add(chunk.address(), length)) {
                            break; // the batch is full: this chunk is the next one's first
                        }
                        range = batch.size() - 1;
                    }
                }
                if (cached.size() == ranges.length) {
                    ranges = java.util.Arrays.copyOf(ranges, 2 * ranges.length);
                }
                ranges[cached.size()] = range;
                cached.add(decoded);
            }
            batch.read();
            return past;
        }

        /** Hands on chunk {@code i} of those read, {@code chunk}: decoded, to be decoded, or to be read. */
        void take(int i, ChunkRecord chunk, Chunks.Reads reads, java.util.function.Consumer<byte[]> copy) {
            byte[] decoded = cached.set(i, null);
            if (decoded != null) {
                copy.accept(decoded);
            } else if (ranges[i] < 0) {
                reads.read(chunk, copy);
            } else if (chunks.filtered(chunk)) {
                reads.decode(chunk, batch.bytes(ranges[i]), copy);
            } else {
                copy.accept(batch.bytes(ranges[i]));
            }
        }
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
