package com.ebremer.falcon.zarr.chunk;

import java.util.Arrays;

/**
 * A regular chunk grid: the array is tiled by chunks of a fixed {@code chunk_shape}, so the grid has
 * {@code ceil(shape[i] / chunk_shape[i])} chunks along each dimension.
 *
 * <p>A chunk is addressed by its grid coordinates {@code (c0, …, cn-1)} with {@code 0 ≤ ci < gridShape[i]}.
 * Every chunk decodes to a full {@code chunk_shape} block; when the array shape is not a multiple of the
 * chunk shape, the trailing <em>edge chunks</em> extend past the array bound and the out-of-range remainder
 * is fill (see {@link #validExtent}). The grid is rank-agnostic, including the rank-0 (scalar) case, which
 * has a single chunk.
 */
public final class RegularChunkGrid {

    private final long[] arrayShape;
    private final long[] chunkShape;
    private final long[] gridShape;
    private final long size;

    /**
     * @throws IllegalArgumentException if the shapes differ in rank, a chunk dimension is not positive, an
     *                                  array dimension is negative, or the array has more than
     *                                  {@code Long.MAX_VALUE} elements
     */
    public RegularChunkGrid(long[] arrayShape, long[] chunkShape) {
        if (arrayShape.length != chunkShape.length) {
            throw new IllegalArgumentException("array rank " + arrayShape.length
                    + " does not match chunk rank " + chunkShape.length);
        }
        this.arrayShape = arrayShape.clone();
        this.chunkShape = chunkShape.clone();
        this.gridShape = new long[arrayShape.length];
        for (int i = 0; i < arrayShape.length; i++) {
            if (chunkShape[i] <= 0) {
                throw new IllegalArgumentException("chunk dimension " + i + " must be positive");
            }
            if (arrayShape[i] < 0) {
                throw new IllegalArgumentException("array dimension " + i + " must be non-negative");
            }
            gridShape[i] = Math.ceilDiv(arrayShape[i], chunkShape[i]);
        }
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

    /** The number of dimensions. */
    public int rank() {
        return arrayShape.length;
    }

    /** The array shape (a defensive copy). */
    public long[] arrayShape() {
        return arrayShape.clone();
    }

    /** The chunk shape (a defensive copy). */
    public long[] chunkShape() {
        return chunkShape.clone();
    }

    /** The number of chunks along each dimension (a defensive copy). */
    public long[] gridShape() {
        return gridShape.clone();
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
        return elementCount(gridShape);
    }

    /**
     * The number of elements in a full chunk (the product of the chunk shape).
     *
     * @throws ArithmeticException if the product overflows {@code long}
     */
    public long elementsPerChunk() {
        return elementCount(chunkShape);
    }

    /** Whether {@code coords} names a chunk in this grid. */
    public boolean containsCoords(long[] coords) {
        if (coords.length != rank()) {
            return false;
        }
        for (int i = 0; i < coords.length; i++) {
            if (coords[i] < 0 || coords[i] >= gridShape[i]) {
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
            if (coords[i] < 0 || coords[i] >= gridShape[i]) {
                throw new IndexOutOfBoundsException("chunk coordinate " + i + " = " + coords[i]
                        + " out of range [0, " + gridShape[i] + ")");
            }
        }
    }

    /** The element offset of a chunk's first element: {@code coords[i] * chunkShape[i]}. */
    public long[] chunkOrigin(long[] coords) {
        checkCoords(coords);
        long[] origin = new long[rank()];
        for (int i = 0; i < rank(); i++) {
            origin[i] = coords[i] * chunkShape[i];
        }
        return origin;
    }

    /**
     * The number of in-bounds elements along each axis for {@code coords}: {@code chunkShape[i]} for an
     * interior chunk, less for a trailing edge chunk. The remaining {@code chunkShape[i] - extent[i]}
     * positions are fill.
     */
    public long[] validExtent(long[] coords) {
        checkCoords(coords);
        long[] extent = new long[rank()];
        for (int i = 0; i < rank(); i++) {
            long origin = coords[i] * chunkShape[i];
            extent[i] = Math.min(chunkShape[i], arrayShape[i] - origin);
        }
        return extent;
    }

    /** Whether {@code coords} is an edge chunk that the array does not completely fill. */
    public boolean isEdgeChunk(long[] coords) {
        long[] extent = validExtent(coords);
        for (int i = 0; i < rank(); i++) {
            if (extent[i] != chunkShape[i]) {
                return true;
            }
        }
        return false;
    }

    /** The chunk coordinates at linear index {@code index} in C (row-major) order. */
    public long[] chunkCoordsAt(long index) {
        if (index < 0 || index >= chunkCount()) {
            throw new IndexOutOfBoundsException(
                    "chunk index " + index + " out of range [0, " + chunkCount() + ")");
        }
        long[] coords = new long[rank()];
        for (int i = rank() - 1; i >= 0; i--) {
            coords[i] = index % gridShape[i];
            index /= gridShape[i];
        }
        return coords;
    }

    /** The linear index of {@code coords} in C (row-major) order. */
    public long linearIndex(long[] coords) {
        checkCoords(coords);
        long index = 0;
        for (int i = 0; i < rank(); i++) {
            index = index * gridShape[i] + coords[i];
        }
        return index;
    }

    @Override
    public String toString() {
        return "RegularChunkGrid[array=" + Arrays.toString(arrayShape)
                + " chunk=" + Arrays.toString(chunkShape) + " grid=" + Arrays.toString(gridShape) + "]";
    }
}
