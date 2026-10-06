package com.ebremer.falcon.zarr.chunk;

import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.Arrays;

/**
 * How an array is cut into chunks: along each dimension the grid lays chunks end to end from the origin,
 * and a chunk is the box where one interval of each dimension meet. {@link RegularChunkGrid} gives every
 * chunk one shape; {@link RectilinearChunkGrid} lets each dimension's chunk lengths vary.
 *
 * <p>A chunk is addressed by its grid coordinates {@code (c0, …, cn-1)} with {@code 0 ≤ ci < gridShape[i]},
 * where {@code gridShape[i]} counts the chunks that overlap the array along dimension {@code i}. Every chunk
 * is encoded at its full declared shape ({@link #chunkShape(long[])}); a chunk that reaches past the array
 * bound (an <em>edge chunk</em>) holds fill there (see {@link #validExtent}). The grid is rank-agnostic,
 * including the rank-0 (scalar) case, which has a single chunk.
 */
public abstract sealed class ChunkGrid permits RegularChunkGrid, RectilinearChunkGrid {

    private final long[] arrayShape;
    private final long size;

    /**
     * @throws IllegalArgumentException if an array dimension is negative, or the array has more than
     *                                  {@code Long.MAX_VALUE} elements
     */
    ChunkGrid(long[] arrayShape) {
        for (int i = 0; i < arrayShape.length; i++) {
            if (arrayShape[i] < 0) {
                throw new IllegalArgumentException("array dimension " + i + " must be non-negative");
            }
        }
        this.arrayShape = arrayShape.clone();
        try {
            this.size = elementCount(arrayShape);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("array shape " + Arrays.toString(arrayShape)
                    + " has more than " + Long.MAX_VALUE + " elements");
        }
    }

    /**
     * The number of elements in a block of {@code shape}: the product of its dimensions, 1 for rank 0, and
     * 0 if any dimension is 0 (even when the others multiply past {@code long}).
     *
     * @throws ArithmeticException if the product overflows {@code long}
     */
    public static long elementCount(long[] shape) {
        for (long d : shape) {
            if (d == 0) {
                return 0;
            }
        }
        long count = 1;
        for (long d : shape) {
            count = Math.multiplyExact(count, d);
        }
        return count;
    }

    // ---- one dimension ----

    /** The number of chunks along {@code dim} that overlap the array (0 when the array is empty there). */
    public abstract long chunksAlong(int dim);

    /**
     * The chunk along {@code dim} that holds element {@code index}. Defined for every index the grid's chunks
     * reach, which may lie past the array: an edge chunk's tail, or a rectilinear grid's chunks beyond it.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or past the grid's declared chunks
     */
    public abstract long chunkAt(int dim, long index);

    /** The element offset along {@code dim} where chunk {@code chunk} starts. */
    public abstract long chunkStart(int dim, long chunk);

    /** The declared length along {@code dim} of chunk {@code chunk}: the extent it is encoded at. */
    public abstract long chunkLength(int dim, long chunk);

    /** Whether every chunk has the same shape (a {@link RegularChunkGrid}). */
    public abstract boolean isRegular();

    /**
     * The grid for the same chunking over an array of {@code newShape}, as resizing keeps it: a regular grid
     * keeps its chunk shape; a rectilinear grid keeps its chunk lengths, adding one chunk where a dimension
     * grows past the lengths it lists (zarr-python's {@code update_shape}).
     *
     * @throws IllegalArgumentException if the rank differs or an extent is negative
     */
    public abstract ChunkGrid resized(long[] newShape);

    /** This grid as the {@code chunk_grid} member of an array's {@code zarr.json}. */
    public abstract JsonObject toJson();

    // ---- the whole grid ----

    /** The number of dimensions. */
    public int rank() {
        return arrayShape.length;
    }

    /** The array shape (a defensive copy). */
    public long[] arrayShape() {
        return arrayShape.clone();
    }

    /** The number of chunks along each dimension that overlap the array (a defensive copy). */
    public long[] gridShape() {
        long[] grid = new long[rank()];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = chunksAlong(i);
        }
        return grid;
    }

    /** The number of elements in the array (1 for a scalar array; 0 if any dimension is empty). */
    public long size() {
        return size;
    }

    /**
     * The total number of chunks (1 for a scalar array; 0 if any dimension is empty). It never exceeds
     * {@link #size()}, so it cannot overflow.
     */
    public long chunkCount() {
        return elementCount(gridShape());
    }

    /** Whether {@code coords} names a chunk in this grid. */
    public boolean containsCoords(long[] coords) {
        if (coords.length != rank()) {
            return false;
        }
        for (int i = 0; i < coords.length; i++) {
            if (coords[i] < 0 || coords[i] >= chunksAlong(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @throws IllegalArgumentException  if {@code coords} has the wrong rank
     * @throws IndexOutOfBoundsException if any coordinate is outside the grid
     */
    public void checkCoords(long[] coords) {
        if (coords.length != rank()) {
            throw new IllegalArgumentException(
                    "expected " + rank() + " chunk coordinates, got " + coords.length);
        }
        for (int i = 0; i < coords.length; i++) {
            long count = chunksAlong(i);
            if (coords[i] < 0 || coords[i] >= count) {
                throw new IndexOutOfBoundsException("chunk coordinate " + i + " = " + coords[i]
                        + " out of range [0, " + count + ")");
            }
        }
    }

    /** The element offset of a chunk's first element. */
    public long[] chunkOrigin(long[] coords) {
        checkCoords(coords);
        long[] origin = new long[rank()];
        for (int i = 0; i < rank(); i++) {
            origin[i] = chunkStart(i, coords[i]);
        }
        return origin;
    }

    /** The declared shape of the chunk at {@code coords}: the shape it is encoded at. */
    public long[] chunkShape(long[] coords) {
        checkCoords(coords);
        long[] shape = new long[rank()];
        for (int i = 0; i < rank(); i++) {
            shape[i] = chunkLength(i, coords[i]);
        }
        return shape;
    }

    /**
     * The number of in-bounds elements along each axis for {@code coords}: the chunk's length for an
     * interior chunk, less for an edge chunk. The remaining positions are fill.
     */
    public long[] validExtent(long[] coords) {
        checkCoords(coords);
        long[] extent = new long[rank()];
        for (int i = 0; i < rank(); i++) {
            extent[i] = Math.min(chunkLength(i, coords[i]), arrayShape[i] - chunkStart(i, coords[i]));
        }
        return extent;
    }

    /** Whether {@code coords} is an edge chunk that the array does not completely fill. */
    public boolean isEdgeChunk(long[] coords) {
        long[] extent = validExtent(coords);
        for (int i = 0; i < rank(); i++) {
            if (extent[i] != chunkLength(i, coords[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The in-bounds length of each chunk along each dimension: {@code sizes[i][c]} is chunk {@code c}'s
     * length along dimension {@code i}, cut at the array bound (dask's {@code chunks}, zarr-python's
     * {@code write_chunk_sizes}).
     *
     * @throws IllegalStateException if a dimension has more chunks than a Java array holds
     */
    public long[][] chunkSizes() {
        long[][] sizes = new long[rank()][];
        for (int i = 0; i < rank(); i++) {
            long count = chunksAlong(i);
            if (count > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("dimension " + i + " has " + count + " chunks, too many to list");
            }
            long[] along = new long[(int) count];
            for (int c = 0; c < along.length; c++) {
                along[c] = Math.min(chunkLength(i, c), arrayShape[i] - chunkStart(i, c));
            }
            sizes[i] = along;
        }
        return sizes;
    }

    /** The chunk coordinates at linear index {@code index} in C (row-major) order. */
    public long[] chunkCoordsAt(long index) {
        long count = chunkCount();
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(
                    "chunk index " + index + " out of range [0, " + count + ")");
        }
        long[] coords = new long[rank()];
        for (int i = rank() - 1; i >= 0; i--) {
            long along = chunksAlong(i);
            coords[i] = index % along;
            index /= along;
        }
        return coords;
    }

    /** The linear index of {@code coords} in C (row-major) order. */
    public long linearIndex(long[] coords) {
        checkCoords(coords);
        long index = 0;
        for (int i = 0; i < rank(); i++) {
            index = index * chunksAlong(i) + coords[i];
        }
        return index;
    }
}
