package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.zfp.ZfpDecoder;
import com.ebremer.falcon.core.compress.zfp.ZfpEncoder;
import com.ebremer.falcon.core.compress.zfp.ZfpHeader;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The {@code numcodecs.zfpy} array&rarr;bytes codec: numcodecs' {@code ZFPY}, which zarr-python 3 runs as an
 * array&rarr;bytes codec in Zarr v3 metadata ({@code zarr.codecs.numcodecs.ZFPY}). A chunk is one zfp stream,
 * {@code zfpy.compress_numpy(chunk, write_header=True)}: the full zfp header (the scalar type, {@code int32},
 * {@code int64}, {@code float32}, or {@code float64}, and the chunk's shape, the last dimension as zfp's
 * {@code x}), then the chunk compressed whole. Falcon Core's {@link ZfpDecoder} reads every mode bit for bit
 * as libzfp 1.0.1 does, going by the stream's header, which must give the array's data type and the chunk's
 * shape; its {@link ZfpEncoder} writes each chunk as zfpy does, byte for byte.
 *
 * <p>The configuration chooses the mode as numcodecs and zfpy choose it ({@link #header}): {@code mode} 4
 * (fixed accuracy, numcodecs' default) with {@code tolerance}, 2 (fixed rate) with {@code rate}, or 3 (fixed
 * precision) with {@code precision}; a parameter left at -1 (or out) gives zfp's reversible, lossless mode,
 * and a tolerance of 0 its expert mode. numcodecs writes no other mode, so Falcon writes none either.
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

    /** numcodecs' {@code zfpy.mode_fixed_rate}, {@code mode_fixed_precision}, and {@code mode_fixed_accuracy}. */
    private static final long MODE_FIXED_RATE = 2;
    private static final long MODE_FIXED_PRECISION = 3;
    private static final long MODE_FIXED_ACCURACY = 4;

    private final DataType dataType;
    private final JsonObject configuration;
    private final ZarrUnsupportedException unwritable; // why chunks of the pipeline's shape cannot be written

    private ZfpyCodec(DataType dataType, JsonObject configuration, int[] shape) {
        this.dataType = dataType;
        this.configuration = configuration;
        ZarrUnsupportedException why = null;
        try {
            header(configuration, zfpType(dataType), shape, "'" + dataType.name() + "'");
        } catch (ZarrUnsupportedException e) {
            why = e;
        }
        this.unwritable = why;
    }

    /**
     * Builds the codec for chunks of {@code dataType} and {@code shape}. The data type and the chunk's shape
     * are checked against each stream's header when it is decoded; whether such chunks can be written, when
     * a write asks ({@link #checkWritable}).
     *
     * @throws ZarrFormatException if the configuration is malformed, or the data type is variable-length
     */
    static ZfpyCodec parse(JsonObject configuration, DataType dataType, int[] shape) {
        checkConfiguration(configuration);
        if (dataType.isVariableLength()) {
            throw new ZarrFormatException("the '" + dataType.name() + "' data type requires the '"
                    + VlenCodec.of(dataType).codecName() + "' codec, not '" + NAME + "'");
        }
        return new ZfpyCodec(dataType, configuration, shape.clone());
    }

    /**
     * Checks that chunks can be written: zfpy compresses their data type, in 1 to 4 dimensions, in a mode
     * numcodecs writes.
     *
     * @throws ZarrUnsupportedException if they cannot be
     */
    void checkWritable() {
        if (unwritable != null) {
            throw unwritable;
        }
    }

    /**
     * The zfp header numcodecs' zfpy codec writes for an array of {@code shape} {@code type} elements: the
     * field, its last dimension zfp's {@code x}, and the mode the configuration chooses, as numcodecs hands
     * zfpy one parameter and zfpy chooses (a tolerance or rate set without the scalar type, a negative
     * parameter giving the reversible mode).
     *
     * @param elements what the elements are, for the message when {@code type} is {@code null}
     * @throws ZarrUnsupportedException if zfpy would not compress the array, or numcodecs not write the mode
     */
    static ZfpHeader header(JsonObject configuration, ZfpHeader.Type type, int[] shape, String elements) {
        if (type == null) {
            throw new ZarrUnsupportedException(NAME + ": the elements are " + elements + ", which zfpy does not"
                    + " compress (little-endian int32, int64, float32, and float64 only)");
        }
        if (shape.length < 1 || shape.length > 4) {
            throw new ZarrUnsupportedException(NAME + ": zfpy compresses arrays of 1 to 4 dimensions, not "
                    + shape.length);
        }
        long[] n = new long[4];
        for (int i = 0; i < shape.length; i++) {
            n[i] = shape[shape.length - 1 - i];
        }
        ZfpHeader field = ZfpHeader.of(type, n[0], n[1], n[2], n[3]);
        long mode = Numcodecs.integer(configuration, "mode", MODE_FIXED_ACCURACY);
        try {
            ZfpHeader header;
            if (mode == MODE_FIXED_ACCURACY) {
                double tolerance = real(configuration, "tolerance");
                header = tolerance >= 0 ? field.withAccuracy(tolerance) : field.withReversible();
            } else if (mode == MODE_FIXED_RATE) {
                double rate = real(configuration, "rate");
                header = rate >= 0 ? field.withRate(rate, false) : field.withReversible();
            } else if (mode == MODE_FIXED_PRECISION) {
                long precision = Numcodecs.integer(configuration, "precision", -1);
                header = precision >= 0 ? field.withPrecision((int) Math.min(precision, 64)) : field.withReversible();
            } else {
                throw new ZarrUnsupportedException(NAME + ": numcodecs' zfpy writes mode 2 (fixed rate), 3 (fixed"
                        + " precision), or 4 (fixed accuracy), not " + mode);
            }
            ZfpEncoder.header(header, 64); // the header can record the field's sizes
            return header;
        } catch (IllegalArgumentException e) {
            throw new ZarrUnsupportedException(NAME + ": " + e.getMessage());
        }
    }

    /** A configuration's number, as a double, or -1 (numcodecs' default) if it is absent. */
    private static double real(JsonObject configuration, String key) {
        return configuration.find(key).filter(v -> !v.isNull()).map(v -> v.asNumber().doubleValue()).orElse(-1.0);
    }

    /** Checks numcodecs' settings: numbers, where they are given. */
    static void checkConfiguration(JsonObject configuration) {
        for (String key : new String[] {"mode", "precision"}) {
            configuration.find(key).filter(v -> !v.isNull()).ifPresent(v -> v.asNumber().longValue());
        }
        for (String key : new String[] {"tolerance", "rate"}) {
            configuration.find(key).filter(v -> !v.isNull()).ifPresent(JsonValue::asNumber);
        }
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

    /** The chunk compressed whole, as {@code zfpy.compress_numpy(chunk, write_header=True)} writes it. */
    @Override
    public byte[] encode(ArrayValue array, int elementSize, byte[] fillElement, boolean writeEmptyChunks) {
        checkWritable();
        ZfpHeader header = header(configuration, zfpType(dataType), array.shape, "'" + dataType.name() + "'");
        return compress(header, array.data);
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

    /** The stream zfpy writes: the full header, then the field, in 64-bit words. */
    private static byte[] compress(ZfpHeader header, byte[] elements) {
        if (elements.length != header.elements() * header.type().size()) {
            throw new ZarrFormatException(NAME + ": " + elements.length + " bytes are not the " + header.elements()
                    + " elements of the zfp field");
        }
        return ZfpEncoder.compressWithHeader(header, elements, 0, 64);
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
     * A Zarr v2 {@code zfpy} compressor (or filter): zarr-python 3 hands numcodecs' {@code ZFPY} the chunk as an
     * array of the elements the stage before makes (the {@code bytes} codec's data type, or a filter's output)
     * and reads its decoded array back as those elements' bytes. So the stream must hold those elements,
     * little-endian {@code int32}, {@code int64}, {@code float32}, or {@code float64} (zfpy compresses nothing
     * else); its shape is not checked, as zarr-python only reshapes what it decodes, but the codecs after it
     * check the element count. A chunk is written in the shape numcodecs is handed it: the chunk's own, or
     * after a filter that flattens (all but {@code astype} and {@code bitround}), one dimension.
     */
    static final class Compressor implements BytesBytesCodec {

        private final NumpyType input;
        private final ZfpHeader.Type type; // null: zfpy would not have taken the elements
        private final JsonObject configuration;
        private final int[] shape;         // null: the elements in one dimension
        private final ZarrUnsupportedException unwritable;

        private Compressor(NumpyType input, JsonObject configuration, int[] shape) {
            this.input = input;
            this.type = zfpType(input);
            this.configuration = configuration;
            this.shape = shape;
            ZarrUnsupportedException why = null;
            try {
                header(configuration, type, shape != null ? shape : new int[] {1}, elements());
            } catch (ZarrUnsupportedException e) {
                why = e;
            }
            this.unwritable = why;
        }

        /**
         * Builds the compressor for {@code input} elements ({@link Numcodecs#elementType}). Elements zfpy does
         * not compress are refused when a chunk is decoded, so that an array whose chunks are all absent
         * still reads, as its fill value, and when a write asks ({@link #checkWritable}).
         *
         * @param shape the shape numcodecs hands zfpy a chunk in, or {@code null} for one dimension
         * @throws ZarrFormatException if the configuration is malformed
         */
        static Compressor parse(JsonObject configuration, NumpyType input, int[] shape) {
            checkConfiguration(configuration);
            return new Compressor(input, configuration, shape == null ? null : shape.clone());
        }

        /**
         * Checks that chunks can be written: zfpy compresses the elements, in 1 to 4 dimensions, in a mode
         * numcodecs writes.
         *
         * @throws ZarrUnsupportedException if they cannot be
         */
        void checkWritable() {
            if (unwritable != null) {
                throw unwritable;
            }
        }

        private String elements() {
            return input == null ? "not numbers" : "'" + input + "'";
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public byte[] decode(byte[] bytes, int maxSize) {
            if (type == null) {
                throw new ZarrFormatException(NAME + ": the elements before it are " + elements() + ", which zfpy"
                        + " does not compress (little-endian int32, int64, float32, and float64 only)");
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
            checkWritable();
            int count = bytes.length / type.size();
            int[] fieldShape = shape != null ? shape : new int[] {count};
            if (Pipelines.elementCount(fieldShape) * type.size() != bytes.length) {
                throw new ZarrFormatException(NAME + ": " + bytes.length + " bytes are not a chunk of "
                        + Arrays.toString(fieldShape) + " '" + input + "' elements");
            }
            return compress(header(configuration, type, fieldShape, elements()), bytes);
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
