package com.ebremer.falcon.zarr.codec;

import java.nio.ByteOrder;

/** The single codec that serializes an array to bytes and back (for example {@code bytes}). */
interface ArrayBytesCodec {

    /** The codec name. */
    String name();

    /** The byte order of each primitive in a decoded element. */
    ByteOrder elementByteOrder();

    /** Decodes {@code input} into the array of the given {@code shape}, each element {@code elementSize} bytes. */
    ArrayValue decode(byte[] input, int[] shape, int elementSize);
}
