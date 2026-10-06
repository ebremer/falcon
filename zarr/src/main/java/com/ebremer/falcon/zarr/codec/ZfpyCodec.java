package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.zfp.ZfpDecoder;
import com.ebremer.falcon.core.compress.zfp.ZfpHeader;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The {@code numcodecs.zfpy} array&rarr;bytes codec, read only: numcodecs' {@code ZFPY}, which zarr-python 3
 * runs as an array&rarr;bytes codec in Zarr v3 metadata ({@code zarr.codecs.numcodecs.ZFPY}). A chunk is one
 * zfp stream, {@code zfpy.compress_numpy(chunk, write_header=True)}: the full zfp header (the scalar type,
 * {@code int32}, {@code int64}, {@code float32}, or {@code float64}, and the chunk's shape, the last dimension
 * as zfp's {@code x}), then the chunk compressed whole in whichever mode the configuration chose (fixed rate,
 * precision, or accuracy; numcodecs' defaults leave zfp in its reversible, lossless mode, and a tolerance of 0
 * gives zfp's expert mode). Falcon Core's {@link ZfpDecoder} reads every mode bit for bit as libzfp 1.0.1
 * does. The configuration ({@code mode}, {@code tolerance}, {@code rate}, {@code precision}) only steers the
 * encoder, so decoding goes by the stream's header, which must give the array's data type and the chunk's
 * shape. Falcon has no zfp encoder: writing such an array is refused ({@link #readOnly()}).
 *
 * <p>A Zarr v2 {@code zfpy} compressor (or filter) is {@link Compressor}, a bytes&rarr;bytes codec after the
 * {@code bytes} codec, as zarr-python 3 reads v2: the stream's elements are handed on as bytes, which must be
 * the elements numcodecs gave zfpy when encoding.
 */
final class ZfpyCodec implements ArrayBytesCodec {

    /** The codec's name, in Zarr v3 metadata and in the pipeline a Zarr v2 array is translated to. */
    static final String NAME = "numcodecs.zfpy";

    /** The most bits zfp spends on one block: the header's mode field holds a {@code maxbits} up to 2^15. */
    private static final long MAX_BLOCK_BITS = 1L << 15;

    private final DataType dataType;

    private ZfpyCodec(DataType dataType) {
        this.dataType = dataType;
    }

    /**
     * Builds the codec for chunks of {@code dataType}. The data type and the chunk's shape are checked
     * against each stream's header when it is decoded.
     *
     * @throws ZarrFormatException if the configuration is malformed, or the data type is variable-length
     */
    static ZfpyCodec parse(JsonObject configuration, DataType dataType) {
        checkConfiguration(configuration);
        if (dataType.isVariableLength()) {
            throw new ZarrFormatException("the '" + dataType.name() + "' data type requires the '"
                    + VlenCodec.of(dataType).codecName() + "' codec, not '" + NAME + "'");
        }
        return new ZfpyCodec(dataType);
    }

    /** Checks numcodecs' settings, which only the encoder uses: numbers, where they are given. */
    static void checkConfiguration(JsonObject configuration) {
        for (String key : new String[] {"mode", "precision"}) {
            configuration.find(key).filter(v -> !v.isNull()).ifPresent(v -> v.asNumber().longValue());
        }
        for (String key : new String[] {"tolerance", "rate"}) {
            configuration.find(key).filter(v -> !v.isNull()).ifPresent(JsonValue::asNumber);
        }
    }

    /** The exception for writing through zfpy, which Falcon decodes but cannot encode. */
    static ZarrUnsupportedException readOnly() {
        return new ZarrUnsupportedException(NAME + ": Falcon decodes zfp but has no zfp encoder, so an array"
                + " compressed with zfpy is read-only");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ByteOrder elementByteOrder() {
        return ByteOrder.LITTLE_ENDIAN; // zfpy's arrays are native, and Falcon Core decodes little-endian
    }

    /**
     * zfp's {@code zfp_stream_maximum_size}, generously: the longest header, every block of 4<sup>d</sup>
     * values (a partial block whole) at the most bits a header allows, rounded up to zfp's 64-bit words.
     */
    @Override
    public long maxEncodedSize(int[] shape, int elementSize) {
        long blocks = 1;
        for (int n : shape) {
            blocks *= (n + 3L) / 4; // at most 2^31 in all: a chunk has fewer elements
        }
        long bits = ZfpHeader.LONG_HEADER_BITS + blocks * MAX_BLOCK_BITS;
        return (bits + 63) / 64 * 8;
    }

    @Override
    public ArrayValue decode(ChunkBytes source, int[] shape, int elementSize, byte[] fillElement,
                             int[] regionOrigin, int[] regionShape) {
        byte[] input = source.readAll().orElse(null);
        if (input == null) {
            return null;
        }
        ZfpHeader header = header(input);
        ZfpHeader.Type expected = zfpType(dataType);
        if (header.type() != expected) {
            throw new ZarrFormatException(NAME + ": the chunk holds zfp " + typeName(header.type()) + " values, but the"
                    + " array's data type is '" + dataType.name() + "'" + (expected == null
                            ? ", which zfpy does not compress (int32, int64, float32, and float64 only)" : ""));
        }
        int[] streamShape = shapeOf(header);
        if (!Arrays.equals(streamShape, shape)) {
            throw new ZarrFormatException(NAME + ": the chunk's zfp stream is of shape " + Arrays.toString(streamShape)
                    + ", but the chunk is " + Arrays.toString(shape));
        }
        return new ArrayValue(decompress(input, Pipelines.elementCount(shape) * (long) elementSize), shape);
    }

    @Override
    public byte[] encode(ArrayValue array, int elementSize, byte[] fillElement, boolean writeEmptyChunks) {
        throw readOnly();
    }

    // ---- shared with the v2 compressor --------------------------------------------------------------

    /** The zfp scalar type of {@code dataType}'s elements, or {@code null} if zfpy does not compress them. */
    private static ZfpHeader.Type zfpType(DataType dataType) {
        if (dataType.equals(DataType.INT32)) {
            return ZfpHeader.Type.INT32;
        }
        if (dataType.equals(DataType.INT64)) {
            return ZfpHeader.Type.INT64;
        }
        if (dataType.equals(DataType.FLOAT32)) {
            return ZfpHeader.Type.FLOAT;
        }
        return dataType.equals(DataType.FLOAT64) ? ZfpHeader.Type.DOUBLE : null;
    }

    /** The zfp scalar type of NumPy elements, or {@code null} if zfpy does not compress them (big-endian ones too). */
    private static ZfpHeader.Type zfpType(NumpyType type) {
        if (type == null || type.order() != ByteOrder.LITTLE_ENDIAN) {
            return null;
        }
        return switch (type.kind() + "" + type.size()) {
            case "i4" -> ZfpHeader.Type.INT32;
            case "i8" -> ZfpHeader.Type.INT64;
            case "f4" -> ZfpHeader.Type.FLOAT;
            case "f8" -> ZfpHeader.Type.DOUBLE;
            default -> null;
        };
    }

    /** The NumPy name of a zfp scalar type, as zfpy decodes it. */
    private static String typeName(ZfpHeader.Type type) {
        return switch (type) {
            case INT32 -> "int32";
            case INT64 -> "int64";
            case FLOAT -> "float32";
            case DOUBLE -> "float64";
        };
    }

    /** The NumPy shape zfpy gives a stream: zfp's sizes, {@code x} last. */
    private static int[] shapeOf(ZfpHeader header) {
        long[] sizes = {header.nw(), header.nz(), header.ny(), header.nx()};
        int dims = header.dimensions();
        int[] shape = new int[dims];
        for (int i = 0; i < dims; i++) {
            long n = sizes[4 - dims + i];
            shape[i] = n > Integer.MAX_VALUE ? -1 : (int) n; // a size no chunk has
        }
        return shape;
    }

    private static ZfpHeader header(byte[] input) {
        try {
            return ZfpHeader.read(input, 0, input.length);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException(NAME + ": " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new ZarrUnsupportedException(NAME + ": " + e.getMessage());
        }
    }

    private static byte[] decompress(byte[] input, long maxBytes) {
        try {
            return ZfpDecoder.decompress(input, 0, input.length, maxBytes);
        } catch (CompressionFormatException e) {
            throw new ZarrFormatException(NAME + " decode failed: " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new ZarrUnsupportedException(NAME + ": " + e.getMessage());
        }
    }

    /**
     * A Zarr v2 {@code zfpy} compressor (or filter), read only: zarr-python 3 hands numcodecs' {@code ZFPY} the
     * chunk as an array of the elements the stage before makes (the {@code bytes} codec's data type, or a
     * filter's output) and reads its decoded array back as those elements' bytes. So the stream must hold
     * those elements, little-endian {@code int32}, {@code int64}, {@code float32}, or {@code float64} (zfpy
     * compresses nothing else); its shape is not checked, as zarr-python only reshapes what it decodes, but
     * the codecs after it check the element count.
     */
    static final class Compressor implements BytesBytesCodec {

        private final NumpyType input;
        private final ZfpHeader.Type type; // null: zfpy would not have taken the elements

        private Compressor(NumpyType input) {
            this.input = input;
            this.type = zfpType(input);
        }

        /**
         * Builds the compressor for {@code input} elements ({@link Numcodecs#elementType}). Elements zfpy does
         * not compress are refused when a chunk is decoded, so that an array whose chunks are all absent
         * still reads, as its fill value.
         *
         * @throws ZarrFormatException if the configuration is malformed
         */
        static Compressor parse(JsonObject configuration, NumpyType input) {
            checkConfiguration(configuration);
            return new Compressor(input);
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public byte[] decode(byte[] bytes, int maxSize) {
            if (type == null) {
                throw new ZarrFormatException(NAME + ": the elements before it are " + (input == null ? "not numbers"
                        : "'" + input + "'") + ", which zfpy does not compress (little-endian int32, int64,"
                        + " float32, and float64 only)");
            }
            ZfpHeader header = header(bytes);
            if (header.type() != type) {
                throw new ZarrFormatException(NAME + ": the chunk holds zfp " + typeName(header.type())
                        + " values, but the elements are '" + input + "'");
            }
            return decompress(bytes, maxSize);
        }

        @Override
        public byte[] encode(byte[] bytes) {
            throw readOnly();
        }

        @Override
        public long encodedSize(long decodedSize) {
            throw new ZarrUnsupportedException(NAME + " has no fixed encoded size, so it cannot encode a shard index");
        }

        /** Every element in a block of its own, at the most bits a block may take: no shape is known here. */
        @Override
        public long maxEncodedSize(long decodedSize) {
            long elements = decodedSize / Math.max(1, type == null ? 1 : type.size());
            if (elements > (Long.MAX_VALUE - 256) / MAX_BLOCK_BITS) {
                return Long.MAX_VALUE;
            }
            return (ZfpHeader.LONG_HEADER_BITS + Math.max(1, elements) * MAX_BLOCK_BITS + 63) / 64 * 8;
        }
    }
}
