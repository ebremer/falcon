package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * A chunked dataset's stored chunks, found by their position in the chunk grid: looked up one at a time in
 * the file's chunk index, or read from it all at once and kept compactly, sorted by grid position.
 *
 * <p><b>Which (P2 PF5).</b> A read that covers few chunk-grid cells looks each one up in the file's index,
 * as libhdf5 does: an array index's entry, or a B-tree's path to it. So a small read of a dataset of
 * millions of chunks, or of a remote one, reads a few index entries, not the whole index. A read covering
 * an eighth of the grid or more reads the whole index instead (one pass, cheaper than as many lookups), as
 * does every read once the lookups made add up to the cells in the grid; from then on the index is kept,
 * and every read finds its chunks there by coordinate. An index whose chunks cannot be looked up one at a
 * time (a single chunk) is read whole.
 *
 * <p>Once kept, a selection is answered one of two ways, whichever touches less: by looking up each
 * chunk-grid cell the selection covers (a binary search apiece), or, when the selection covers more cells
 * than there are stored chunks, by one pass over the chunks. Both give the chunks in row-major grid order.
 *
 * <p>Thread-safe: a dataset shared between threads can share it. Read whole, a chunk whose offset is not a
 * multiple of the chunk shape, or two chunks at the same offset, can only come from a corrupt index and
 * fail with {@link HdfFormatException}, as libhdf5 would not find them either.
 */
public final class ChunkIndex {

    /** A read covering more than this fraction of the grid's cells reads the whole index. */
    private static final int WHOLE_INDEX_FRACTION = 8;

    private final int rank;
    private final int[] chunkDims;
    private final ChunkLookup lookup;                   // finds one chunk; null: always read the index whole
    private final Supplier<List<ChunkRecord>> enumerate; // reads every chunk
    private final long gridCells;                       // the cells of the grid over the extent (saturating)
    private final LongConsumer loadedBytes;             // told the bytes the whole index takes, once read
    private final AtomicLong lookups = new AtomicLong(); // the cells looked up so far
    private volatile Loaded loaded;                     // the whole index, once read

    private ChunkIndex(int[] chunkDims, ChunkLookup lookup, Supplier<List<ChunkRecord>> enumerate, long gridCells,
                       LongConsumer loadedBytes, Loaded loaded) {
        this.rank = chunkDims.length;
        this.chunkDims = chunkDims.clone();
        this.lookup = lookup;
        this.enumerate = enumerate;
        this.gridCells = gridCells;
        this.loadedBytes = loadedBytes;
        this.loaded = loaded;
    }

    /** The index of {@code chunks}, which a chunk index enumerated for chunks of shape {@code chunkDims}. */
    public static ChunkIndex of(List<ChunkRecord> chunks, int[] chunkDims) {
        checkChunkDims(chunkDims);
        return new ChunkIndex(chunkDims, null, null, 0, bytes -> { }, Loaded.of(chunks, chunkDims));
    }

    /**
     * The index of a dataset of extent {@code datasetDims} whose chunks {@code lookup} finds one at a time
     * (null if it cannot) and {@code enumerate} reads all at once, neither called until a read needs them.
     * {@code loadedBytes} is told what the index takes in memory when it is read whole.
     */
    public static ChunkIndex of(int[] chunkDims, long[] datasetDims, ChunkLookup lookup,
                                Supplier<List<ChunkRecord>> enumerate, LongConsumer loadedBytes) {
        checkChunkDims(chunkDims);
        long cells = 1;
        for (int d = 0; d < chunkDims.length; d++) {
            long span = datasetDims[d] == 0 ? 0 : (datasetDims[d] - 1) / chunkDims[d] + 1;
            cells = span != 0 && cells > Long.MAX_VALUE / span ? Long.MAX_VALUE : cells * span; // saturating
        }
        return new ChunkIndex(chunkDims, lookup, enumerate, cells, loadedBytes, null);
    }

    private static void checkChunkDims(int[] chunkDims) {
        for (int d : chunkDims) {
            if (d <= 0) {
                throw new HdfFormatException("chunk dimension " + d + " is not positive");
            }
        }
    }

    /** The number of stored chunks (reading the whole index). */
    public int size() {
        return loaded().size();
    }

    /** The bytes the stored chunks take, as filtered (reading the whole index). */
    public long storedBytes() {
        return loaded().storedBytes();
    }

    /** Every stored chunk, in row-major grid order (reading the whole index). */
    public List<ChunkRecord> all() {
        Loaded whole = loaded();
        List<ChunkRecord> out = new ArrayList<>(whole.size());
        for (int i = 0; i < whole.size(); i++) {
            out.add(whole.record(i));
        }
        return out;
    }

    /**
     * The stored chunks that overlap the box {@code [offset, offset + count)} of a dataset of extent
     * {@code datasetDims}, in row-major grid order.
     */
    public List<ChunkRecord> overlapping(long[] offset, long[] count, long[] datasetDims) {
        long[] first = new long[rank];
        long[] last = new long[rank];
        long cells = 1;
        for (int d = 0; d < rank; d++) {
            long end = Math.min(offset[d] + count[d], datasetDims[d]); // exclusive
            if (count[d] == 0 || end <= offset[d]) {
                return new ArrayList<>();
            }
            first[d] = offset[d] / chunkDims[d];
            last[d] = (end - 1) / chunkDims[d];
            long span = last[d] - first[d] + 1;
            cells = cells > Long.MAX_VALUE / span ? Long.MAX_VALUE : cells * span; // saturating
        }
        if (!direct(cells)) {
            return loaded().overlapping(first, last, cells);
        }
        List<ChunkRecord> out = new ArrayList<>();
        long[] cell = first.clone();
        while (true) {
            ChunkRecord chunk = lookup.find(cell.clone());
            if (chunk != null) {
                out.add(chunk);
            }
            int d = rank - 1;
            while (d >= 0) {
                if (++cell[d] <= last[d]) {
                    break;
                }
                cell[d] = first[d];
                d--;
            }
            if (d < 0) {
                return out;
            }
        }
    }

    /** The stored chunk at grid coordinates {@code cell}, or null if none is stored there. */
    public ChunkRecord at(long[] cell) {
        return direct(1) ? lookup.find(cell.clone()) : loaded().at(cell);
    }

    /**
     * The stored chunks whose grid coordinate in every dimension <i>d</i> is one of {@code cells[d]}
     * (each sorted increasing), in row-major grid order: by looking up each combination, or, when there
     * are more combinations than stored chunks, by one pass over the chunks.
     */
    public List<ChunkRecord> inGrid(long[][] cells) {
        long combinations = 1;
        for (long[] axis : cells) {
            if (axis.length == 0) {
                return new ArrayList<>();
            }
            combinations = combinations > Long.MAX_VALUE / axis.length ? Long.MAX_VALUE : combinations * axis.length;
        }
        if (!direct(combinations)) {
            return loaded().inGrid(cells, combinations);
        }
        List<ChunkRecord> out = new ArrayList<>();
        int[] at = new int[rank];
        while (true) {
            long[] cell = new long[rank];
            for (int d = 0; d < rank; d++) {
                cell[d] = cells[d][at[d]];
            }
            ChunkRecord chunk = lookup.find(cell);
            if (chunk != null) {
                out.add(chunk);
            }
            int d = rank - 1;
            while (d >= 0) {
                if (++at[d] < cells[d].length) {
                    break;
                }
                at[d] = 0;
                d--;
            }
            if (d < 0) {
                return out;
            }
        }
    }

    /**
     * True if a read of {@code cells} grid cells looks them up one at a time: the index is not kept yet, it
     * can be looked up, the read covers less than an eighth of the grid, and the lookups made so far, with
     * these, do not pass the grid's cells.
     */
    private boolean direct(long cells) {
        if (lookup == null || loaded != null || cells > gridCells / WHOLE_INDEX_FRACTION) {
            return false;
        }
        return lookups.addAndGet(cells) <= gridCells;
    }

    /** The whole index, read on first use and then kept. */
    private Loaded loaded() {
        Loaded result = loaded;
        if (result == null) {
            result = Loaded.of(enumerate.get(), chunkDims);
            loaded = result;
            loadedBytes.accept(64 + (16 + 8L * rank) * result.size());
        }
        return result;
    }

    /** Every stored chunk, kept compactly and sorted by grid position. Immutable. */
    private static final class Loaded {
        private final int rank;
        private final int[] chunkDims;
        private final long[] scaled;     // rank grid coordinates per chunk, chunks sorted row-major
        private final long[] addresses;
        private final int[] sizes;
        private final int[] filterMasks;

        private Loaded(int rank, int[] chunkDims, long[] scaled, long[] addresses, int[] sizes, int[] filterMasks) {
            this.rank = rank;
            this.chunkDims = chunkDims;
            this.scaled = scaled;
            this.addresses = addresses;
            this.sizes = sizes;
            this.filterMasks = filterMasks;
        }

        static Loaded of(List<ChunkRecord> chunks, int[] chunkDims) {
            int rank = chunkDims.length;
            int n = chunks.size();
            long[] grid = new long[Math.multiplyExact(n, rank)];
            for (int i = 0; i < n; i++) {
                long[] offset = chunks.get(i).offset();
                if (offset.length != rank) {
                    throw new HdfFormatException("chunk offset of rank " + offset.length + " in a dataset of rank " + rank);
                }
                for (int d = 0; d < rank; d++) {
                    if (offset[d] < 0 || offset[d] % chunkDims[d] != 0) {
                        throw new HdfFormatException("chunk at offset " + Arrays.toString(offset)
                                + " is not aligned to the chunk shape " + Arrays.toString(chunkDims));
                    }
                    grid[i * rank + d] = offset[d] / chunkDims[d];
                }
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> compare(grid, a * rank, grid, b * rank, rank));
            long[] scaled = new long[n * rank];
            long[] addresses = new long[n];
            int[] sizes = new int[n];
            int[] masks = new int[n];
            for (int k = 0; k < n; k++) {
                int i = order[k];
                System.arraycopy(grid, i * rank, scaled, k * rank, rank);
                ChunkRecord chunk = chunks.get(i);
                addresses[k] = chunk.address();
                sizes[k] = chunk.size();
                masks[k] = chunk.filterMask();
                if (k > 0 && compare(scaled, (k - 1) * rank, scaled, k * rank, rank) == 0) {
                    throw new HdfFormatException("two chunks are stored at offset " + Arrays.toString(chunk.offset()));
                }
            }
            return new Loaded(rank, chunkDims.clone(), scaled, addresses, sizes, masks);
        }

        int size() {
            return addresses.length;
        }

        long storedBytes() {
            long total = 0;
            for (int size : sizes) {
                total += size;
            }
            return total;
        }

        /** The chunks in the grid cells from {@code first} to {@code last}, {@code cells} of them. */
        List<ChunkRecord> overlapping(long[] first, long[] last, long cells) {
            List<ChunkRecord> out = new ArrayList<>();
            if (cells > size()) {
                for (int i = 0; i < size(); i++) { // fewer chunks than covered cells: one pass over them all
                    if (within(i, first, last)) {
                        out.add(record(i));
                    }
                }
                return out;
            }
            long[] cell = first.clone();
            while (true) {
                int i = find(cell);
                if (i >= 0) {
                    out.add(record(i));
                }
                int d = rank - 1;
                while (d >= 0) {
                    if (++cell[d] <= last[d]) {
                        break;
                    }
                    cell[d] = first[d];
                    d--;
                }
                if (d < 0) {
                    return out;
                }
            }
        }

        ChunkRecord at(long[] cell) {
            int i = find(cell);
            return i < 0 ? null : record(i);
        }

        List<ChunkRecord> inGrid(long[][] cells, long combinations) {
            List<ChunkRecord> out = new ArrayList<>();
            if (combinations > size()) {
                next:
                for (int i = 0; i < size(); i++) {
                    for (int d = 0; d < rank; d++) {
                        if (Arrays.binarySearch(cells[d], scaled[i * rank + d]) < 0) {
                            continue next;
                        }
                    }
                    out.add(record(i));
                }
                return out;
            }
            int[] at = new int[rank];
            long[] cell = new long[rank];
            while (true) {
                for (int d = 0; d < rank; d++) {
                    cell[d] = cells[d][at[d]];
                }
                int i = find(cell);
                if (i >= 0) {
                    out.add(record(i));
                }
                int d = rank - 1;
                while (d >= 0) {
                    if (++at[d] < cells[d].length) {
                        break;
                    }
                    at[d] = 0;
                    d--;
                }
                if (d < 0) {
                    return out;
                }
            }
        }

        private boolean within(int i, long[] first, long[] last) {
            for (int d = 0; d < rank; d++) {
                long c = scaled[i * rank + d];
                if (c < first[d] || c > last[d]) {
                    return false;
                }
            }
            return true;
        }

        /** The position of the chunk at grid coordinates {@code cell}, or -1. */
        private int find(long[] cell) {
            int lo = 0;
            int hi = size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = compare(scaled, mid * rank, cell, 0, rank);
                if (c < 0) {
                    lo = mid + 1;
                } else if (c > 0) {
                    hi = mid - 1;
                } else {
                    return mid;
                }
            }
            return -1;
        }

        ChunkRecord record(int i) {
            long[] offset = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = scaled[i * rank + d] * chunkDims[d];
            }
            return new ChunkRecord(offset, addresses[i], sizes[i], filterMasks[i]);
        }

        private static int compare(long[] a, int aFrom, long[] b, int bFrom, int length) {
            for (int d = 0; d < length; d++) {
                int c = Long.compare(a[aFrom + d], b[bFrom + d]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
    }
}
