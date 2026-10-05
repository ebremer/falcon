package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * The {@code zstd} bytes&rarr;bytes codec, decoded by Falcon's from-scratch Zstandard implementation
 * (see {@link ZstdDecoder}) so the module stays dependency-free. This matters for interoperability:
 * zarr-python compresses with zstd by default, so most Zarr v3 stores in the wild need it.
 *
 * <p>Both directions are implemented in pure Java: {@link ZstdDecoder} reads and {@link ZstdEncoder}
 * writes. The encoder is a real LZ77 + FSE compressor using the format's predefined tables and raw
 * literals; its frames are read by libzstd (zarr-python).
 */
final class ZstdCodec implements BytesBytesCodec {

    static ZstdCodec parse(JsonObject configuration) {
        return new ZstdCodec();
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
    public byte[] decode(byte[] input) {
        try {
            return ZstdDecoder.decompress(input);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException("zstd decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        return ZstdEncoder.compress(input);
    }
}
