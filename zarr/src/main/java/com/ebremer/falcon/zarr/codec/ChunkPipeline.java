package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A chunk codec pipeline: the ordered codec chain that turns a chunk's stored bytes into its decoded
 * elements. A valid Zarr v3 pipeline is {@code (array→array)* (array→bytes) (bytes→bytes)*} with exactly
 * one array&rarr;bytes codec; {@link #of} validates that structure.
 *
 * <p>{@link #decode} inverts the chain: the bytes&rarr;bytes codecs are undone in reverse order, the
 * array&rarr;bytes codec reshapes the flat buffer, and the array&rarr;array codecs are undone in reverse
 * order. The result is a flat buffer of the chunk's elements in C order, each primitive in
 * {@link #elementOrder()}.
 */
public final class ChunkPipeline {

    private final DataType dataType;
    private final int[] chunkShape;
    private final List<ArrayArrayCodec> arrayCodecs;
    private final ArrayBytesCodec bytesCodec;
    private final List<BytesBytesCodec> byteCodecs;
    private final int[] boundaryShape; // chunk shape at the array->bytes boundary (after array->array encode)

    private ChunkPipeline(DataType dataType, int[] chunkShape, List<ArrayArrayCodec> arrayCodecs,
                          ArrayBytesCodec bytesCodec, List<BytesBytesCodec> byteCodecs, int[] boundaryShape) {
        this.dataType = dataType;
        this.chunkShape = chunkShape;
        this.arrayCodecs = arrayCodecs;
        this.bytesCodec = bytesCodec;
        this.byteCodecs = byteCodecs;
        this.boundaryShape = boundaryShape;
    }

    /**
     * Builds the pipeline for {@code chunkShape} elements of {@code dataType} from the array metadata's
     * codec specs (each a {@code {"name", "configuration"?}} object, in pipeline order).
     *
     * @throws ZarrFormatException      if the codec order is invalid or a configuration is malformed
     * @throws ZarrUnsupportedException if a codec is not yet implemented
     */
    public static ChunkPipeline of(DataType dataType, long[] chunkShape, List<JsonObject> codecSpecs) {
        int[] shape = Pipelines.toIntShape(chunkShape);
        List<ArrayArrayCodec> arrayCodecs = new ArrayList<>();
        ArrayBytesCodec bytesCodec = null;
        List<BytesBytesCodec> byteCodecs = new ArrayList<>();
        int[] boundaryShape = shape;

        try {
            for (JsonObject spec : codecSpecs) {
                String name = spec.get("name").asString();
                JsonObject config = spec.find("configuration")
                        .map(JsonValue::asObject).orElse(EMPTY_CONFIG);
                switch (name) {
                    case "transpose" -> {
                        if (bytesCodec != null) {
                            throw new ZarrFormatException(
                                    "array->array codec 'transpose' appears after the array->bytes codec");
                        }
                        TransposeCodec codec = TransposeCodec.parse(config, boundaryShape.length);
                        arrayCodecs.add(codec);
                        boundaryShape = codec.encodedShape(boundaryShape);
                    }
                    case "bytes" -> {
                        if (bytesCodec != null) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        bytesCodec = BytesCodec.parse(config, dataType);
                    }
                    case "gzip" -> {
                        requireBytesCodec(bytesCodec, name);
                        byteCodecs.add(GzipCodec.parse(config));
                    }
                    case "crc32c" -> {
                        requireBytesCodec(bytesCodec, name);
                        byteCodecs.add(new Crc32cCodec());
                    }
                    case "sharding_indexed" -> throw new ZarrUnsupportedException(
                            "the sharding_indexed codec is not yet supported (planned; see PLAN.md, stage Z6)");
                    case "blosc", "zstd" -> throw new ZarrUnsupportedException(
                            "the " + name + " codec is not yet supported (planned; see PLAN.md, stage Z8)");
                    default -> throw new ZarrUnsupportedException("unknown codec: '" + name + "'");
                }
            }
        } catch (JsonException e) {
            throw new ZarrFormatException("invalid codec configuration: " + e.getMessage(), e);
        }

        if (bytesCodec == null) {
            throw new ZarrFormatException("codec pipeline has no array->bytes codec");
        }
        return new ChunkPipeline(dataType, shape, List.copyOf(arrayCodecs), bytesCodec,
                List.copyOf(byteCodecs), boundaryShape);
    }

    private static void requireBytesCodec(ArrayBytesCodec bytesCodec, String name) {
        if (bytesCodec == null) {
            throw new ZarrFormatException(
                    "bytes->bytes codec '" + name + "' appears before the array->bytes codec");
        }
    }

    /** The byte order of each decoded primitive (the {@code bytes} codec's endian). */
    public ByteOrder elementOrder() {
        return bytesCodec.elementByteOrder();
    }

    /** The element data type. */
    public DataType dataType() {
        return dataType;
    }

    /**
     * Decodes a chunk's stored bytes into its elements: a flat buffer of {@code elementsPerChunk} elements
     * in C order, each primitive in {@link #elementOrder()}.
     *
     * @throws ZarrFormatException if the bytes are truncated, fail a checksum, or otherwise malformed
     */
    public byte[] decode(byte[] stored) {
        byte[] bytes = stored;
        for (int i = byteCodecs.size() - 1; i >= 0; i--) {
            bytes = byteCodecs.get(i).decode(bytes);
        }
        int elementSize = dataType.byteCount();
        ArrayValue array = bytesCodec.decode(bytes, boundaryShape, elementSize);
        for (int i = arrayCodecs.size() - 1; i >= 0; i--) {
            array = arrayCodecs.get(i).decode(array, elementSize);
        }
        if (!Arrays.equals(array.shape, chunkShape)) {
            throw new ZarrFormatException("decoded chunk shape " + Arrays.toString(array.shape)
                    + " does not match " + Arrays.toString(chunkShape));
        }
        return array.data;
    }

    private static final JsonObject EMPTY_CONFIG = new JsonObject(java.util.Map.of());
}
