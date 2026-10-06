package com.ebremer.falcon.zarr.codec;

/** A codec that transforms one array into another (for example {@code transpose}). */
interface ArrayArrayCodec {

    /** The codec name. */
    String name();

    /** The shape this codec produces on the encode side from {@code inputShape}. */
    int[] encodedShape(int[] inputShape);

    /** The shape decoding produces from an encoded array of {@code encodedShape}. */
    int[] decodedShape(int[] encodedShape);

    /** Decodes {@code input} (the encoded-side array) back toward the logical array. */
    ArrayValue decode(ArrayValue input, int elementSize);

    /** Encodes {@code input} (the logical-side array) toward the stored form. */
    ArrayValue encode(ArrayValue input, int elementSize);

    /** {@link #decode} for a variable-length string chunk of {@code encodedShape}, in C order. */
    String[] decodeStrings(String[] input, int[] encodedShape);

    /** {@link #encode} for a variable-length string chunk of {@code shape}, in C order. */
    String[] encodeStrings(String[] input, int[] shape);
}
