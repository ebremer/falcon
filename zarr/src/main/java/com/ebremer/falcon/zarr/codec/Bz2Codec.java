package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.bzip2.Bzip2Decoder;
import com.ebremer.falcon.core.compress.bzip2.Bzip2Encoder;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * The {@code numcodecs.bz2} bytes&rarr;bytes codec: numcodecs' {@code BZ2}, under the name zarr-python 3
 * gives it in Zarr v3 metadata ({@code zarr.codecs.numcodecs.BZ2}), and what a Zarr v2 {@code bz2}
 * compressor (or filter) becomes. numcodecs calls Python's {@code bz2} module: a chunk is written as one bzip2
 * stream, {@code bz2.compress(chunk, level)}, which Falcon Core's {@link Bzip2Encoder} writes byte for byte
 * (libbzip2 1.0.8's); the {@code level} configuration (1 to 9, numcodecs' default 1) is the block size in
 * units of 100&nbsp;kB. Decoding is {@code bz2.decompress}'s ({@link Bzip2Decoder#decompressConcatenated}):
 * streams that follow one another are joined, bytes after them that are not a stream are ignored, and a
 * stream cut short is refused.
 */
final class Bz2Codec implements BytesBytesCodec {

    private static final int DEFAULT_LEVEL = 1; // numcodecs.BZ2's

    private final int level;

    private Bz2Codec(int level) {
        this.level = level;
    }

    static Bz2Codec parse(JsonObject configuration) {
        int level = configuration.find("level").map(v -> v.asNumber().intValue()).orElse(DEFAULT_LEVEL);
        if (level < 1 || level > 9) {
            throw new ZarrFormatException("numcodecs.bz2 level must be 1..9, was " + level);
        }
        return new Bz2Codec(level);
    }

    @Override
    public String name() {
        return "numcodecs.bz2";
    }

    @Override
    public long encodedSize(long decodedSize) {
        throw new ZarrUnsupportedException(
                "numcodecs.bz2 has no fixed encoded size, so it cannot encode a shard index");
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return BytesBytesCodec.compressorBound(decodedSize); // bzip2 grows a chunk by about 1% and 600 bytes at most
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        try {
            return Bzip2Decoder.decompressConcatenated(input, 0, input.length, maxSize);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException("numcodecs.bz2 decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        return Bzip2Encoder.compress(input, level);
    }
}
