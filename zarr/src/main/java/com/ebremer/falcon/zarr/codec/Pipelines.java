package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;

/** Small shared helpers for chunk shapes within the codec package. */
final class Pipelines {

    private Pipelines() {
    }

    /** Narrows a chunk shape to {@code int} dimensions, rejecting a chunk too large for a Java array. */
    static int[] toIntShape(long[] shape) {
        int[] out = new int[shape.length];
        long count = 1;
        for (int i = 0; i < shape.length; i++) {
            if (shape[i] < 0 || shape[i] > Integer.MAX_VALUE) {
                throw new ZarrFormatException("chunk dimension " + i + " = " + shape[i] + " is too large");
            }
            out[i] = (int) shape[i];
            count *= out[i];
            if (count > Integer.MAX_VALUE) {
                throw new ZarrFormatException("chunk has too many elements for a single buffer");
            }
        }
        return out;
    }

    /** The number of elements in a chunk of {@code shape} (1 for a scalar). */
    static int elementCount(int[] shape) {
        int count = 1;
        for (int d : shape) {
            count *= d;
        }
        return count;
    }
}
