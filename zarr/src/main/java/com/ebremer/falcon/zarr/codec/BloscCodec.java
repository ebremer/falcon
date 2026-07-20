package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.codec.blosc.BloscDecoder;
import com.ebremer.falcon.zarr.codec.blosc.BloscEncoder;
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
 * <p>Reading supports the {@code blosclz}/{@code lz4}/{@code lz4hc}/{@code zlib}/{@code zstd} internal
 * compressors with byte- or bit-shuffle. Writing uses byte-shuffle plus zstd (see
 * {@link BloscEncoder}); the element size for the shuffle comes from the array's data type, supplied
 * when the pipeline is built.
 */
final class BloscCodec implements BytesBytesCodec {

    private final int elementSize;

    private BloscCodec(int elementSize) {
        this.elementSize = elementSize;
    }

    static BloscCodec parse(JsonObject configuration, int elementSize) {
        return new BloscCodec(elementSize);
    }

    @Override
    public String name() {
        return "blosc";
    }

    @Override
    public long encodedSize(long decodedSize) {
        throw new com.ebremer.falcon.zarr.ZarrUnsupportedException(
                "blosc has no fixed encoded size, so it cannot encode a shard index");
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
        return BloscEncoder.compress(input, elementSize);
    }
}
