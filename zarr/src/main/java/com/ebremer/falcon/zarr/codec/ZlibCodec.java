package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.zlib.Zlib;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * The {@code numcodecs.zlib} bytes&rarr;bytes codec: numcodecs' {@code Zlib}, under the name zarr-python 3
 * gives it in Zarr v3 metadata ({@code zarr.codecs.numcodecs.Zlib}), and what a Zarr v2 {@code zlib}
 * compressor becomes. A chunk is one zlib stream (RFC&nbsp;1950), as Python's {@code zlib.compress}
 * writes it, handled by {@code java.util.zip}; the {@code level} configuration (-1, zlib's default, or 0
 * to 9; numcodecs' default 1) sets the deflate level when encoding. Like numcodecs, decoding ignores bytes
 * after the stream's end and refuses a truncated stream.
 */
final class ZlibCodec implements BytesBytesCodec {

    private static final int DEFAULT_LEVEL = 1; // numcodecs.Zlib's

    private final int level;

    private ZlibCodec(int level) {
        this.level = level;
    }

    static ZlibCodec parse(JsonObject configuration) {
        int level = configuration.find("level").map(v -> v.asNumber().intValue()).orElse(DEFAULT_LEVEL);
        if (level < -1 || level > 9) {
            throw new ZarrFormatException("numcodecs.zlib level must be -1..9, was " + level);
        }
        return new ZlibCodec(level);
    }

    @Override
    public String name() {
        return "numcodecs.zlib";
    }

    @Override
    public long encodedSize(long decodedSize) {
        throw new ZarrUnsupportedException(
                "numcodecs.zlib has no fixed encoded size, so it cannot encode a shard index");
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return BytesBytesCodec.compressorBound(decodedSize);
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        try {
            return Zlib.decompress(input, 0, input.length, maxSize);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException("numcodecs.zlib decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        return Zlib.compress(input, level);
    }
}
