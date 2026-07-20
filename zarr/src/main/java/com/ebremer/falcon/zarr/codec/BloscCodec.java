package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.codec.blosc.BloscDecoder;
import com.ebremer.falcon.zarr.codec.blosc.BloscFormatException;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * The {@code blosc} bytes&rarr;bytes codec, decoded by Falcon's from-scratch Blosc implementation
 * (see {@link BloscDecoder}) so the module stays dependency-free.
 *
 * <p>Everything the decoder needs &mdash; the internal compressor, shuffle filter, type size, and block
 * layout &mdash; is recorded in the buffer's own header, so the codec's {@code configuration} (which
 * describes how the <em>encoder</em> was set up) is not consulted when reading.
 *
 * <p>Only decoding is implemented, and only for the {@code lz4}, {@code lz4hc}, {@code zlib}, and
 * {@code zstd} internal compressors with byte-shuffle or no shuffle; {@code blosclz}, {@code snappy},
 * and bit-shuffle are reported as unsupported rather than mis-decoded.
 */
final class BloscCodec implements BytesBytesCodec {

    static BloscCodec parse(JsonObject configuration) {
        return new BloscCodec();
    }

    @Override
    public String name() {
        return "blosc";
    }

    @Override
    public long encodedSize(long decodedSize) {
        throw new ZarrUnsupportedException("blosc has no fixed encoded size, so it cannot encode a shard index");
    }

    @Override
    public byte[] decode(byte[] input) {
        try {
            return BloscDecoder.decompress(input);
        } catch (BloscFormatException e) {
            throw new ZarrFormatException("blosc decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        throw new ZarrUnsupportedException(
                "writing blosc is not supported; create the array with gzip or no compression");
    }
}
