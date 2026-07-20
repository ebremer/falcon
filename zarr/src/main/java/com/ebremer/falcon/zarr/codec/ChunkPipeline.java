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
    private final boolean vlen;        // array->bytes codec is vlen-utf8 (a String[] chunk, not a byte buffer)

    private ChunkPipeline(DataType dataType, int[] chunkShape, List<ArrayArrayCodec> arrayCodecs,
                          ArrayBytesCodec bytesCodec, List<BytesBytesCodec> byteCodecs, int[] boundaryShape,
                          boolean vlen) {
        this.dataType = dataType;
        this.chunkShape = chunkShape;
        this.arrayCodecs = arrayCodecs;
        this.bytesCodec = bytesCodec;
        this.byteCodecs = byteCodecs;
        this.boundaryShape = boundaryShape;
        this.vlen = vlen;
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
        boolean vlen = false;
        List<BytesBytesCodec> byteCodecs = new ArrayList<>();
        int[] boundaryShape = shape;

        try {
            for (JsonObject spec : codecSpecs) {
                String name = spec.get("name").asString();
                JsonObject config = spec.find("configuration")
                        .map(JsonValue::asObject).orElse(EMPTY_CONFIG);
                boolean arrayBytesSet = bytesCodec != null || vlen;
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
                        if (arrayBytesSet) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        if (dataType.isVariableLength()) {
                            throw new ZarrFormatException(
                                    "the '" + dataType.name() + "' data type requires the 'vlen-utf8' codec, not 'bytes'");
                        }
                        bytesCodec = BytesCodec.parse(config, dataType);
                    }
                    case "vlen-utf8" -> {
                        if (arrayBytesSet) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        if (!dataType.isVariableLength()) {
                            throw new ZarrFormatException(
                                    "the 'vlen-utf8' codec requires a variable-length data type, not '"
                                            + dataType.name() + "'");
                        }
                        if (!arrayCodecs.isEmpty()) {
                            throw new ZarrFormatException(
                                    "array->array codecs are not supported before 'vlen-utf8'");
                        }
                        vlen = true;
                    }
                    case "gzip" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(GzipCodec.parse(config));
                    }
                    case "crc32c" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(new Crc32cCodec());
                    }
                    case "sharding_indexed" -> {
                        if (arrayBytesSet) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        bytesCodec = ShardingCodec.parse(config, dataType, boundaryShape);
                    }
                    case "zstd" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(ZstdCodec.parse(config));
                    }
                    case "blosc" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(BloscCodec.parse(config, dataType.byteCount()));
                    }
                    default -> throw new ZarrUnsupportedException("unknown codec: '" + name + "'");
                }
            }
        } catch (JsonException e) {
            throw new ZarrFormatException("invalid codec configuration: " + e.getMessage(), e);
        }

        if (bytesCodec == null && !vlen) {
            throw new ZarrFormatException("codec pipeline has no array->bytes codec");
        }
        return new ChunkPipeline(dataType, shape, List.copyOf(arrayCodecs), bytesCodec,
                List.copyOf(byteCodecs), boundaryShape, vlen);
    }

    private static void requireBytesCodec(boolean arrayBytesSet, String name) {
        if (!arrayBytesSet) {
            throw new ZarrFormatException(
                    "bytes->bytes codec '" + name + "' appears before the array->bytes codec");
        }
    }

    /** The byte order of each decoded primitive (the {@code bytes} codec's endian, or LE for vlen strings). */
    public ByteOrder elementOrder() {
        return vlen ? ByteOrder.LITTLE_ENDIAN : bytesCodec.elementByteOrder();
    }

    /** Whether the array&rarr;bytes codec is {@code vlen-utf8} (a {@code String[]} chunk). */
    public boolean isVlen() {
        return vlen;
    }

    /**
     * Decodes a variable-length string chunk's stored bytes into its {@code elementCount} elements in C
     * order. Reverses the bytes&rarr;bytes codecs, then the {@code vlen-utf8} codec.
     *
     * @throws ZarrFormatException if the pipeline is not a string pipeline, or the bytes are malformed
     */
    public String[] decodeStrings(byte[] stored, int elementCount) {
        if (!vlen) {
            throw new IllegalStateException("decodeStrings requires a vlen-utf8 pipeline");
        }
        byte[] bytes = stored;
        for (int i = byteCodecs.size() - 1; i >= 0; i--) {
            bytes = byteCodecs.get(i).decode(bytes);
        }
        String[] elements = VlenUtf8.decode(bytes);
        if (elements.length != elementCount) {
            throw new ZarrFormatException("decoded string chunk has " + elements.length
                    + " elements, expected " + elementCount);
        }
        return elements;
    }

    /**
     * Encodes a variable-length string chunk (a flat C-order {@code String[]}) into the bytes to store:
     * the {@code vlen-utf8} codec, then the bytes&rarr;bytes codecs in order.
     */
    public byte[] encodeStrings(String[] elements) {
        if (!vlen) {
            throw new IllegalStateException("encodeStrings requires a vlen-utf8 pipeline");
        }
        int expected = Pipelines.elementCount(chunkShape);
        if (elements.length != expected) {
            throw new ZarrFormatException(
                    "string chunk has " + elements.length + " elements, expected " + expected);
        }
        byte[] bytes = VlenUtf8.encode(elements);
        for (BytesBytesCodec codec : byteCodecs) {
            bytes = codec.encode(bytes);
        }
        return bytes;
    }

    /** The element data type. */
    public DataType dataType() {
        return dataType;
    }

    /**
     * Encodes a chunk's elements (a flat C-order buffer, each primitive in {@link #elementOrder()}) into
     * the bytes to store: array&rarr;array codecs in order, then the array&rarr;bytes codec, then the
     * bytes&rarr;bytes codecs in order. {@code fillElement} lets a shard omit all-fill sub-chunks.
     */
    public byte[] encode(byte[] elements, byte[] fillElement) {
        int elementSize = dataType.byteCount();
        long expected = (long) Pipelines.elementCount(chunkShape) * elementSize;
        if (elements.length != expected) {
            throw new ZarrFormatException(
                    "chunk is " + elements.length + " bytes, expected " + expected);
        }
        ArrayValue array = new ArrayValue(elements, chunkShape);
        for (ArrayArrayCodec codec : arrayCodecs) {
            array = codec.encode(array, elementSize);
        }
        byte[] bytes = bytesCodec.encode(array, elementSize, fillElement);
        for (BytesBytesCodec codec : byteCodecs) {
            bytes = codec.encode(bytes);
        }
        return bytes;
    }

    /** Whether the array&rarr;bytes codec is {@code sharding_indexed}. */
    public boolean isSharded() {
        return bytesCodec instanceof ShardingCodec;
    }

    /** The encoded size of {@code rawLength} bytes after this pipeline's bytes&rarr;bytes codecs. */
    long encodedLength(long rawLength) {
        long length = rawLength;
        for (BytesBytesCodec codec : byteCodecs) {
            length = codec.encodedSize(length);
        }
        return length;
    }

    /**
     * Decodes a chunk's stored bytes into its elements: a flat buffer of {@code elementsPerChunk} elements
     * in C order, each primitive in {@link #elementOrder()}. Empty sub-chunks of a shard decode as zeros;
     * use {@link #decodeChunk} to supply the array's fill value.
     *
     * @throws ZarrFormatException if the bytes are truncated, fail a checksum, or otherwise malformed
     */
    public byte[] decode(byte[] stored) {
        byte[] decoded = decodeChunk(ChunkBytes.of(stored), new byte[dataType.byteCount()],
                new int[chunkShape.length], chunkShape);
        if (decoded == null) {
            throw new ZarrFormatException("chunk bytes were unexpectedly absent");
        }
        return decoded;
    }

    /**
     * Decodes the chunk read through {@code source}, returning its elements in C order.
     *
     * <p>{@code regionOrigin}/{@code regionShape} name the part of the chunk the caller needs: with a
     * sharding codec and no other array-stage codecs, only the sub-chunks overlapping that region are
     * fetched and decoded, and the rest of the returned buffer holds {@code fillElement}. Callers must
     * not read outside the region they asked for.
     *
     * @return the chunk's elements, or {@code null} if the chunk is absent from the store
     */
    public byte[] decodeChunk(ChunkBytes source, byte[] fillElement, int[] regionOrigin, int[] regionShape) {
        ChunkBytes effective = source;
        if (!byteCodecs.isEmpty()) {
            byte[] bytes = source.readAll().orElse(null);
            if (bytes == null) {
                return null;
            }
            for (int i = byteCodecs.size() - 1; i >= 0; i--) {
                bytes = byteCodecs.get(i).decode(bytes);
            }
            effective = ChunkBytes.of(bytes);
        }
        // A transpose between the chunk and the bytes permutes axes, so a region expressed in logical
        // coordinates does not map onto the encoded layout: decode the whole chunk in that case.
        boolean wholeChunk = !arrayCodecs.isEmpty();
        int[] origin = wholeChunk ? new int[boundaryShape.length] : regionOrigin;
        int[] extent = wholeChunk ? boundaryShape : regionShape;

        int elementSize = dataType.byteCount();
        ArrayValue array = bytesCodec.decode(effective, boundaryShape, elementSize, fillElement, origin, extent);
        if (array == null) {
            return null;
        }
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
