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
 * writes. The encoder is a real LZ77 + FSE compressor using the format's predefined tables and raw
 * literals; its frames are read by libzstd (zarr-python).
 *
 * <p>Writing honours the configuration's {@code checksum}: each frame then carries its content checksum,
 * which readers verify (I9). The {@code level} is read but has no effect, since the encoder has one level.
 */
final class ZstdCodec implements BytesBytesCodec {

    private final boolean checksum;

    private ZstdCodec(boolean checksum) {
        this.checksum = checksum;
    }

    static ZstdCodec parse(JsonObject configuration) {
        configuration.find("level").ifPresent(v -> v.asNumber().intValue()); // must be an integer
        return new ZstdCodec(configuration.find("checksum").map(JsonValue::asBoolean).orElse(false));
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
        return ZstdEncoder.compress(input, checksum);
    }
}
