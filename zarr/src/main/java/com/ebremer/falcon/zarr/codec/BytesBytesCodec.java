package com.ebremer.falcon.zarr.codec;

/** A codec that transforms bytes into bytes (for example {@code gzip} or {@code crc32c}). */
interface BytesBytesCodec {

    /** The codec name. */
    String name();

    /**
     * Decodes {@code input}, undoing this codec's transform.
     *
     * @param maxSize the most bytes the result may hold; a codec that would produce more fails as soon as
     *                it knows, before allocating it (H1: a few bytes of corrupt input must not claim gigabytes)
     * @throws com.ebremer.falcon.zarr.ZarrFormatException if the input is malformed or decodes to more than
     *                                                     {@code maxSize} bytes
     */
    byte[] decode(byte[] input, int maxSize);

    /** Encodes {@code input}, applying this codec's transform. */
    byte[] encode(byte[] input);

    /**
     * The encoded size of {@code decodedSize} bytes, for codecs whose overhead is fixed. Needed to locate
     * a shard index without reading the whole shard.
     *
     * @throws com.ebremer.falcon.zarr.ZarrUnsupportedException if this codec's encoded size is not fixed
     */
    long encodedSize(long decodedSize);

    /**
     * An upper bound on the encoded size of {@code decodedSize} bytes: the limit for decoding the codec
     * that runs before this one. A compressor's bound is generous (any sane encoder stays well inside it);
     * it only has to stop a bomb.
     */
    long maxEncodedSize(long decodedSize);

    /** The generous bound for a compressor: twice the input plus 64 KiB, saturating. */
    static long compressorBound(long decodedSize) {
        return decodedSize > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : 2 * decodedSize + 65536;
    }
}
