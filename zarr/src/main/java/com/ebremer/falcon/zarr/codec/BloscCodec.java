package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.blosc.BloscDecoder;
import com.ebremer.falcon.core.compress.blosc.BloscEncoder;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code blosc} bytes&rarr;bytes codec, decoded by Falcon's from-scratch Blosc implementation in
 * Falcon Core (see {@link BloscDecoder}), shared with the HDF5 module's blosc filter.
 *
 * <p>Everything the decoder needs &mdash; the internal compressor, shuffle filter, type size, and block
 * layout &mdash; is recorded in the buffer's own header, so the codec's {@code configuration} (which
 * describes how the <em>encoder</em> was set up) is not consulted when reading.
 *
 * <p>Reading supports the {@code blosclz}/{@code lz4}/{@code lz4hc}/{@code zlib}/{@code zstd}/{@code snappy}
 * internal compressors with byte- or bit-shuffle. Writing honours the configuration's {@code shuffle}
 * ({@code noshuffle}, {@code shuffle}, {@code bitshuffle}), {@code typesize}, {@code blocksize}, and
 * {@code clevel} (I9), through {@link BloscEncoder}. The internal compressor is always zstd, whatever
 * {@code cname} says: the output is still valid Blosc, which every reader decodes from its own header,
 * but Falcon has no encoder for the others yet. A configuration without a field (v2 metadata records
 * none) gets zstd at level 5, the byte shuffle for multi-byte elements, and automatic block sizes.
 */
final class BloscCodec implements BytesBytesCodec {

    private static final Set<String> CNAMES = Set.of("blosclz", "lz4", "lz4hc", "zlib", "zstd", "snappy");

    private final int typeSize;
    private final int shuffle;
    private final int blockSize;
    private final int clevel;

    private BloscCodec(int typeSize, int shuffle, int blockSize, int clevel) {
        this.typeSize = typeSize;
        this.shuffle = shuffle;
        this.blockSize = blockSize;
        this.clevel = clevel;
    }

    static BloscCodec parse(JsonObject configuration, int elementSize) {
        String cname = configuration.find("cname").map(JsonValue::asString).orElse("zstd");
        if (!CNAMES.contains(cname)) {
            throw new ZarrFormatException("blosc cname must be one of " + new TreeSet<>(CNAMES) + ", was '" + cname + "'");
        }
        int clevel = configuration.find("clevel").map(v -> v.asNumber().intValue()).orElse(5);
        if (clevel < 0 || clevel > 9) {
            throw new ZarrFormatException("blosc clevel must be 0..9, was " + clevel);
        }
        int typeSize = configuration.find("typesize").map(v -> v.asNumber().intValue()).orElse(Math.max(elementSize, 1));
        if (typeSize < 1) {
            throw new ZarrFormatException("blosc typesize must be positive, was " + typeSize);
        }
        int shuffle = configuration.find("shuffle").map(v -> switch (v.asString()) {
            case "noshuffle" -> BloscEncoder.NOSHUFFLE;
            case "shuffle" -> BloscEncoder.SHUFFLE;
            case "bitshuffle" -> BloscEncoder.BITSHUFFLE;
            default -> throw new ZarrFormatException(
                    "blosc shuffle must be noshuffle, shuffle, or bitshuffle, was '" + v.asString() + "'");
        }).orElse(typeSize > 1 ? BloscEncoder.SHUFFLE : BloscEncoder.NOSHUFFLE);
        int blockSize = configuration.find("blocksize").map(v -> v.asNumber().intValue()).orElse(0);
        if (blockSize < 0) {
            throw new ZarrFormatException("blosc blocksize must not be negative, was " + blockSize);
        }
        return new BloscCodec(typeSize, shuffle, blockSize, clevel);
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
    public long maxEncodedSize(long decodedSize) {
        return BytesBytesCodec.compressorBound(decodedSize);
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        try {
            int claimed = BloscDecoder.decompressedSize(input); // the header's nbytes, before any allocation
            if (claimed < 0 || claimed > maxSize) {
                throw new ZarrFormatException("blosc chunk claims " + Integer.toUnsignedString(claimed)
                        + " bytes, more than the " + maxSize + " it may hold");
            }
            return BloscDecoder.decompress(input, maxSize);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException("blosc decode failed: " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new ZarrUnsupportedException(e.getMessage());
        }
    }

    @Override
    public byte[] encode(byte[] input) {
        return BloscEncoder.compress(input, typeSize, shuffle, blockSize, clevel);
    }
}
