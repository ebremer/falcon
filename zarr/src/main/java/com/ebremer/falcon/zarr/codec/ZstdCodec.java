package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;

/**
 * The {@code zstd} bytes&rarr;bytes codec, decoded by Falcon's from-scratch Zstandard implementation
 * (see {@link ZstdDecoder}) so the module stays dependency-free. This matters for interoperability:
 * zarr-python compresses with zstd by default, so most Zarr v3 stores in the wild need it.
 *
 * <p>Both directions are implemented in pure Java: {@link ZstdDecoder} reads and {@link ZstdEncoder}
 * writes. The encoder works as libzstd's lazy strategies do (Huffman-coded literals, FSE tables fitted to
 * each block, repeat offsets); its frames are read by libzstd (zarr-python).
 *
 * <p>Writing honours the configuration: the {@code level} (libzstd's scale, 1 to 22; 0, or none, the
 * default 3; a negative level the fastest settings; above 22, 22), and the {@code checksum}, which puts the
 * content checksum in each frame for readers to verify (I9).
 */
final class ZstdCodec implements BytesBytesCodec {

    private final int level;
    private final boolean checksum;

    private ZstdCodec(int level, boolean checksum) {
        this.level = level;
        this.checksum = checksum;
    }

    static ZstdCodec parse(JsonObject configuration) {
        int level = configuration.find("level").map(v -> v.asNumber().intValue()).orElse(0); // an integer
        return new ZstdCodec(level, configuration.find("checksum").map(JsonValue::asBoolean).orElse(false));
    }

    @Override
    public String name() {
        return "zstd";
    }

    @Override
    public long encodedSize(long decodedSize) {
        throw new ZarrUnsupportedException("zstd has no fixed encoded size, so it cannot encode a shard index");
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return BytesBytesCodec.compressorBound(decodedSize);
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        try {
            return ZstdDecoder.decompress(input, 0, input.length, maxSize);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException("zstd decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        return ZstdEncoder.compress(input, level, checksum);
    }
}
