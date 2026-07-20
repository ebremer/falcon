package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.codec.zstd.ZstdDecoder;
import com.ebremer.falcon.zarr.codec.zstd.ZstdFormatException;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * The {@code zstd} bytes&rarr;bytes codec, decoded by Falcon's from-scratch Zstandard implementation
 * (see {@link ZstdDecoder}) so the module stays dependency-free. This matters for interoperability:
 * zarr-python compresses with zstd by default, so most Zarr v3 stores in the wild need it.
 *
 * <p>Only decoding is implemented. Writing zstd would need a full compressor; Falcon writes with
 * {@code gzip} or no compression instead, both of which every Zarr implementation reads.
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
        } catch (ZstdFormatException e) {
            throw new ZarrFormatException("zstd decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        throw new ZarrUnsupportedException(
                "writing zstd is not supported; create the array with gzip or no compression");
    }
}
