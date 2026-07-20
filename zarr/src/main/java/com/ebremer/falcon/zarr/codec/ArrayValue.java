package com.ebremer.falcon.zarr.codec;

/**
 * The array representation that flows between the array-stage codecs of a pipeline: a flat buffer of
 * fixed-size elements laid out in C (row-major) order for {@link #shape}. The element size and byte
 * order are carried by the pipeline, not here, so an array&rarr;array codec such as {@code transpose}
 * only ever moves whole elements around.
 */
final class ArrayValue {

    final byte[] data;
    final int[] shape;

    ArrayValue(byte[] data, int[] shape) {
        this.data = data;
        this.shape = shape;
    }
}
