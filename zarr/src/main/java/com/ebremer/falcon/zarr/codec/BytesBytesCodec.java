package com.ebremer.falcon.zarr.codec;

/** A codec that transforms bytes into bytes (for example {@code gzip} or {@code crc32c}). */
interface BytesBytesCodec {

    /** The codec name. */
    String name();

    /** Decodes {@code input}, undoing this codec's transform. */
    byte[] decode(byte[] input);
}
