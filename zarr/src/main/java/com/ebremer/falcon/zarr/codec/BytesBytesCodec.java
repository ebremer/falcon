package com.ebremer.falcon.zarr.codec;

/** A codec that transforms bytes into bytes (for example {@code gzip} or {@code crc32c}). */
interface BytesBytesCodec {

    /** The codec name. */
    String name();

    /** Decodes {@code input}, undoing this codec's transform. */
    byte[] decode(byte[] input);

    /** Encodes {@code input}, applying this codec's transform. */
    byte[] encode(byte[] input);

    /**
     * The encoded size of {@code decodedSize} bytes, for codecs whose overhead is fixed. Needed to locate
     * a shard index without reading the whole shard.
     *
     * @throws com.ebremer.falcon.zarr.ZarrUnsupportedException if this codec's encoded size is not fixed
     */
    long encodedSize(long decodedSize);
}
