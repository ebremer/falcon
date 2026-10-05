package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A chunked dataset's stored chunks, read from its chunk index once and kept compactly, sorted by their
 * position in the chunk grid, so that each read finds the chunks it overlaps by coordinate rather than
 * walking the index again.
 *
 * <p>A selection is answered one of two ways, whichever touches less: by looking up each chunk-grid cell
 * the selection covers (a binary search apiece), or, when the selection covers more cells than there are
 * stored chunks, by one pass over the chunks. Both give the chunks in row-major grid order.
 *
 * <p>Immutable, so a dataset shared between threads can share it. A chunk whose offset is not a multiple
 * of the chunk shape, or two chunks at the same offset, can only come from a corrupt index and fail with
 * {@link HdfFormatException}, as libhdf5 would not find them either.
 */
public final class ChunkIndex {

    private final int rank;
    private final int[] chunkDims;
    private final long[] scaled;     // rank grid coordinates per chunk, chunks sorted row-major
    private final long[] addresses;
    private final int[] sizes;
    private final int[] filterMasks;

    private ChunkIndex(int rank, int[] chunkDims, long[] scaled, long[] addresses, int[] sizes, int[] filterMasks) {
        this.rank = rank;
        this.chunkDims = chunkDims;
        this.scaled = scaled;
        this.addresses = addresses;
        this.sizes = sizes;
        this.filterMasks = filterMasks;
    }

    /** The index of {@code chunks}, which a chunk index enumerated for chunks of shape {@code chunkDims}. */
    public static ChunkIndex of(List<ChunkRecord> chunks, int[] chunkDims) {
        int rank = chunkDims.length;
        for (int d : chunkDims) {
            if (d <= 0) {
                throw new HdfFormatException("chunk dimension " + d + " is not positive");
            }
        }
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
        return new ChunkIndex(rank, chunkDims.clone(), scaled, addresses, sizes, masks);
    }

    /** The number of stored chunks. */
    public int size() {
        return addresses.length;
    }

    /** The bytes the stored chunks take, as filtered. */
    public long storedBytes() {
        long total = 0;
        for (int size : sizes) {
            total += size;
        }
        return total;
    }

    /** Every stored chunk, in row-major grid order. */
    public List<ChunkRecord> all() {
        List<ChunkRecord> out = new ArrayList<>(size());
        for (int i = 0; i < size(); i++) {
            out.add(record(i));
        }
        return out;
    }

    /**
     * The stored chunks that overlap the box {@code [offset, offset + count)} of a dataset of extent
     * {@code datasetDims}, in row-major grid order.
     */
    public List<ChunkRecord> overlapping(long[] offset, long[] count, long[] datasetDims) {
        List<ChunkRecord> out = new ArrayList<>();
        long[] first = new long[rank];
        long[] last = new long[rank];
        long cells = 1;
        for (int d = 0; d < rank; d++) {
            long end = Math.min(offset[d] + count[d], datasetDims[d]); // exclusive
            if (count[d] == 0 || end <= offset[d]) {
                return out;
            }
            first[d] = offset[d] / chunkDims[d];
            last[d] = (end - 1) / chunkDims[d];
            long span = last[d] - first[d] + 1;
            cells = cells > Long.MAX_VALUE / span ? Long.MAX_VALUE : cells * span; // saturating
        }
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

    private ChunkRecord record(int i) {
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
