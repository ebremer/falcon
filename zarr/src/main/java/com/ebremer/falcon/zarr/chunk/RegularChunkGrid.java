package com.ebremer.falcon.zarr.chunk;

import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A regular chunk grid: the array is tiled by chunks of a fixed {@code chunk_shape}, so the grid has
 * {@code ceil(shape[i] / chunk_shape[i])} chunks along each dimension.
 *
 * <p>Every chunk decodes to a full {@code chunk_shape} block; when the array shape is not a multiple of the
 * chunk shape, the trailing <em>edge chunks</em> extend past the array bound and the out-of-range remainder
 * is fill (see {@link #validExtent}).
 */
public final class RegularChunkGrid extends ChunkGrid {

    private final long[] chunkShape;
    private final long[] gridShape;

    /**
     * @throws IllegalArgumentException if the shapes differ in rank, a chunk dimension is not positive, an
     *                                  array dimension is negative, or the array has more than
     *                                  {@code Long.MAX_VALUE} elements
     */
    public RegularChunkGrid(long[] arrayShape, long[] chunkShape) {
        super(checkRank(arrayShape, chunkShape));
        this.chunkShape = chunkShape.clone();
        this.gridShape = new long[arrayShape.length];
        for (int i = 0; i < arrayShape.length; i++) {
            if (chunkShape[i] <= 0) {
                throw new IllegalArgumentException("chunk dimension " + i + " must be positive");
            }
            gridShape[i] = Math.ceilDiv(arrayShape[i], chunkShape[i]);
        }
    }

    private static long[] checkRank(long[] arrayShape, long[] chunkShape) {
        if (arrayShape.length != chunkShape.length) {
            throw new IllegalArgumentException("array rank " + arrayShape.length
                    + " does not match chunk rank " + chunkShape.length);
        }
        return arrayShape;
    }

    /** The chunk shape (a defensive copy). */
    public long[] chunkShape() {
        return chunkShape.clone();
    }

    /**
     * The number of elements in a full chunk (the product of the chunk shape).
     *
     * @throws ArithmeticException if the product overflows {@code long}
     */
    public long elementsPerChunk() {
        return elementCount(chunkShape);
    }

    @Override
    public long chunksAlong(int dim) {
        return gridShape[dim];
    }

    @Override
    public long chunkAt(int dim, long index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("element index " + index + " is negative");
        }
        return index / chunkShape[dim];
    }

    @Override
    public long chunkStart(int dim, long chunk) {
        return chunk * chunkShape[dim];
    }

    @Override
    public long chunkLength(int dim, long chunk) {
        return chunkShape[dim];
    }

    @Override
    public boolean isRegular() {
        return true;
    }

    @Override
    public RegularChunkGrid resized(long[] newShape) {
        return new RegularChunkGrid(newShape, chunkShape);
    }

    @Override
    public JsonObject toJson() {
        List<JsonValue> shape = new ArrayList<>(chunkShape.length);
        for (long d : chunkShape) {
            shape.add(JsonNumber.of(d));
        }
        return JsonObject.builder().put("name", "regular")
                .put("configuration", JsonObject.builder().put("chunk_shape", new JsonArray(shape)).build())
                .build();
    }

    @Override
    public String toString() {
        return "RegularChunkGrid[array=" + Arrays.toString(arrayShape())
                + " chunk=" + Arrays.toString(chunkShape) + " grid=" + Arrays.toString(gridShape) + "]";
    }
}
