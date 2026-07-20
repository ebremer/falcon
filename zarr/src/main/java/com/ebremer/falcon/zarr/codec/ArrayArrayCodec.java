package com.ebremer.falcon.zarr.codec;

/** A codec that transforms one array into another (for example {@code transpose}). */
interface ArrayArrayCodec {

    /** The codec name. */
    String name();

    /** The shape this codec produces on the encode side from {@code inputShape}. */
    int[] encodedShape(int[] inputShape);

    /** Decodes {@code input} (the encoded-side array) back toward the logical array. */
    ArrayValue decode(ArrayValue input, int elementSize);
}
