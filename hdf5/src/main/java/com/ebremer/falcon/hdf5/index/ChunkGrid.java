package com.ebremer.falcon.hdf5.index;

import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.HdfFormatException;

/**
 * The mapping between a chunk's position in the chunk grid and the linear index under which the
 * array-style chunk indexes (fixed array, extensible array, implicit) store it.
 *
 * <p>libhdf5 linearizes row-major over the grid of the dataset's <em>maximum</em> dimensions, not its
 * current ones ({@code H5VM_array_offset_pre} over {@code layout.max_down_chunks} in
 * {@code H5Dfarray.c} / {@code H5Dnone.c}). Only the slowest-varying dimension's extent never enters
 * the strides, so a dataset whose fixed maximum exceeds its current size in any other dimension has
 * gaps in the linear numbering. The extensible-array index additionally "swizzles" its single
 * unlimited dimension to the slowest position before linearizing ({@code H5VM_swizzle_coords} with
 * {@code swizzled_max_down_chunks} in {@code H5Dearray.c}), so appending along it extends the array.
 */
public final class ChunkGrid {

    private final int[] chunkDims;
    private final long[] currentChunks; // chunks per dimension covering the current extent
    private final long[] down;          // strides over the (possibly swizzled) max chunk grid
    private final int swizzleDim;       // the dimension moved to the slowest position, or 0 (no-op)

    private ChunkGrid(int[] chunkDims, long[] currentChunks, long[] down, int swizzleDim) {
        this.chunkDims = chunkDims;
        this.currentChunks = currentChunks;
        this.down = down;
        this.swizzleDim = swizzleDim;
    }

    /** The grid for a fixed-array or implicit index: linear over the maximum dimensions. */
    public static ChunkGrid forFixedArray(long[] dims, long[] maxDims, int[] chunkDims) {
        return build(dims, maxDims, chunkDims, 0);
    }

    /** The grid for an extensible-array index: its unlimited dimension is swizzled to the slowest. */
    public static ChunkGrid forExtensibleArray(long[] dims, long[] maxDims, int[] chunkDims) {
        int unlimited = 0;
        if (maxDims != null) {
            for (int d = 0; d < maxDims.length; d++) {
                if (maxDims[d] == Dataspace.UNLIMITED) {
                    unlimited = d;
                    break;
                }
            }
        }
        return build(dims, maxDims, chunkDims, unlimited);
    }

    private static ChunkGrid build(long[] dims, long[] maxDims, int[] chunkDims, int swizzleDim) {
        int rank = dims.length;
        if (chunkDims.length != rank) {
            throw new HdfFormatException("chunk rank " + chunkDims.length + " does not match dataset rank " + rank);
        }
        long[] current = new long[rank];
        long[] max = new long[rank];
        for (int d = 0; d < rank; d++) {
            current[d] = ceilDiv(dims[d], chunkDims[d]);
            long m = maxDims == null || maxDims[d] == Dataspace.UNLIMITED ? dims[d] : maxDims[d];
            max[d] = Math.max(current[d], ceilDiv(m, chunkDims[d]));
        }
        long[] swizzled = swizzle(max, swizzleDim);
        long[] down = new long[rank];
        long stride = 1;
        for (int d = rank - 1; d >= 0; d--) {
            down[d] = stride;
            if (d > 0) {
                try {
                    stride = Math.multiplyExact(stride, Math.max(1, swizzled[d]));
                } catch (ArithmeticException e) {
                    throw new HdfFormatException("chunk grid too large to index");
                }
            }
        }
        return new ChunkGrid(chunkDims.clone(), current, down, swizzleDim);
    }

    /** The number of chunks per dimension covering the dataset's current extent. */
    public long[] currentChunks() {
        return currentChunks.clone();
    }

    /** The linear index of the chunk at grid coordinates {@code scaled} (chunk units). */
    public long linearIndex(long[] scaled) {
        long[] s = swizzle(scaled, swizzleDim);
        long index = 0;
        for (int d = 0; d < s.length; d++) {
            index += s[d] * down[d];
        }
        return index;
    }

    /** The element offset of the chunk stored under linear index {@code linear}. */
    public long[] chunkOffset(long linear) {
        int rank = down.length;
        long[] s = new long[rank];
        long remaining = linear;
        for (int d = 0; d < rank; d++) {
            s[d] = remaining / down[d];
            remaining %= down[d];
        }
        long[] scaled = unswizzle(s, swizzleDim);
        long[] offset = new long[rank];
        for (int d = 0; d < rank; d++) {
            offset[d] = scaled[d] * chunkDims[d];
        }
        return offset;
    }

    /** True if the chunk at element {@code offset} lies at least partly inside the current extent. */
    public boolean isWithinCurrentExtent(long[] offset) {
        for (int d = 0; d < offset.length; d++) {
            if (offset[d] / chunkDims[d] >= currentChunks[d]) {
                return false;
            }
        }
        return true;
    }

    /** Moves dimension {@code dim} to position 0, shifting the earlier dimensions up by one. */
    private static long[] swizzle(long[] coords, int dim) {
        long[] out = coords.clone();
        if (dim > 0) {
            long moved = coords[dim];
            System.arraycopy(coords, 0, out, 1, dim);
            out[0] = moved;
        }
        return out;
    }

    /** Inverse of {@link #swizzle}. */
    private static long[] unswizzle(long[] coords, int dim) {
        long[] out = coords.clone();
        if (dim > 0) {
            long moved = coords[0];
            System.arraycopy(coords, 1, out, 0, dim);
            out[dim] = moved;
        }
        return out;
    }

    private static long ceilDiv(long value, long divisor) {
        return value == 0 ? 0 : (value - 1) / divisor + 1;
    }
}
