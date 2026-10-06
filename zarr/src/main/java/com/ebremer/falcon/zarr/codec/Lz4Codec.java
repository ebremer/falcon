package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.Arrays;

/**
 * The {@code numcodecs.lz4} bytes&rarr;bytes codec: numcodecs' {@code LZ4}, under the name zarr-python 3
 * gives it in Zarr v3 metadata ({@code zarr.codecs.numcodecs.LZ4}), and what a Zarr v2 {@code lz4}
 * compressor becomes. A chunk is the decoded length as a little-endian {@code uint32}, then one LZ4 block
 * from {@code LZ4_compress_fast}; Falcon Core's {@link Lz4} writes the block liblz4 writes. The
 * {@code acceleration} configuration (default 1, the best ratio; larger is faster; below 1 means 1) is
 * liblz4's.
 */
final class Lz4Codec implements BytesBytesCodec {

    private static final int DEFAULT_ACCELERATION = 1; // numcodecs.LZ4's

    private final int acceleration;

    private Lz4Codec(int acceleration) {
        this.acceleration = acceleration;
    }

    static Lz4Codec parse(JsonObject configuration) {
        return new Lz4Codec(configuration.find("acceleration").map(v -> v.asNumber().intValue())
                .orElse(DEFAULT_ACCELERATION));
    }

    @Override
    public String name() {
        return "numcodecs.lz4";
    }

    @Override
    public long encodedSize(long decodedSize) {
        throw new ZarrUnsupportedException(
                "numcodecs.lz4 has no fixed encoded size, so it cannot encode a shard index");
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return BytesBytesCodec.compressorBound(decodedSize);
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        if (input.length < 4) {
            throw new ZarrFormatException("numcodecs.lz4 chunk is shorter than its 4-byte length");
        }
        long size = (input[0] & 0xffL) | (input[1] & 0xffL) << 8 | (input[2] & 0xffL) << 16 | (input[3] & 0xffL) << 24;
        if (size > maxSize) { // before anything is allocated
            throw new ZarrFormatException("numcodecs.lz4 chunk claims " + size + " bytes, more than the "
                    + maxSize + " it may hold");
        }
        byte[] out = new byte[(int) size];
        try {
            Lz4.decompress(input, 4, input.length - 4, out, 0, out.length);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException("numcodecs.lz4 decode failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public byte[] encode(byte[] input) {
        int bound;
        try {
            bound = Lz4.maxCompressedLength(input.length);
        } catch (IllegalArgumentException e) {
            throw new ZarrUnsupportedException("numcodecs.lz4 cannot compress a chunk of " + input.length
                    + " bytes: " + e.getMessage());
        }
        byte[] out = new byte[4 + bound];
        out[0] = (byte) input.length;
        out[1] = (byte) (input.length >>> 8);
        out[2] = (byte) (input.length >>> 16);
        out[3] = (byte) (input.length >>> 24);
        int length = Lz4.compress(input, 0, input.length, out, 4, bound, acceleration);
        return Arrays.copyOf(out, 4 + length);
    }
}
