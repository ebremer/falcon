package com.ebremer.falcon.zarr.codec;

import java.nio.ByteOrder;

/** The single codec that serializes an array to bytes and back ({@code bytes} or {@code sharding_indexed}). */
interface ArrayBytesCodec {

    /** The codec name. */
    String name();

    /** The byte order of each primitive in a decoded element. */
    ByteOrder elementByteOrder();

    /** An upper bound on the encoded size of a chunk of {@code shape}: the limit for decoding it. */
    long maxEncodedSize(int[] shape, int elementSize);

    /**
     * Decodes the chunk read through {@code source} into an array of {@code shape}.
     *
     * <p>{@code regionOrigin}/{@code regionShape} name the sub-region the caller actually needs. A codec
     * that can address parts of a chunk (sharding) may decode only that region and leave the rest set to
     * {@code fillElement}; callers must not rely on data outside the region. Codecs that cannot decode
     * the whole chunk regardless.
     *
     * @return the decoded array, or {@code null} if the chunk is absent from the store
     */
    ArrayValue decode(ChunkBytes source, int[] shape, int elementSize, byte[] fillElement,
                      int[] regionOrigin, int[] regionShape);

    /**
     * Encodes a whole chunk to its stored bytes. {@code fillElement} lets a codec recognize empty regions
     * (a shard omits sub-chunks that hold only the fill value, unless {@code writeEmptyChunks}).
     */
    byte[] encode(ArrayValue array, int elementSize, byte[] fillElement, boolean writeEmptyChunks);
}
