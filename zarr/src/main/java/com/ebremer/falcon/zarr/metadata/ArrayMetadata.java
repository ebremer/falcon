package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parsed array metadata: shape, data type, chunk grid, chunk key encoding, fill value, and codecs, plus
 * optional attributes and dimension names.
 *
 * <p>The {@code data_type} is resolved to a {@link DataType} and the {@code fill_value} is validated
 * against it at parse time (though kept as raw JSON; {@link #fillValueBytes} decodes it on demand). The
 * {@code codecs} are kept as raw specs and assembled into a {@link ChunkPipeline} on first use (see
 * {@link #pipeline()}). Only the {@code regular} chunk grid and the {@code default}/{@code v2} chunk key
 * encodings are recognized; anything else is reported as {@link ZarrUnsupportedException}.
 */
public final class ArrayMetadata implements NodeMetadata {

    private static final Set<String> KNOWN = Set.of(
            "zarr_format", "node_type", "shape", "data_type", "chunk_grid", "chunk_key_encoding",
            "fill_value", "codecs", "attributes", "dimension_names", "storage_transformers");

    private final RegularChunkGrid grid;
    private final DataType dataType;
    private final ChunkKeyEncoding chunkKeyEncoding;
    private final JsonValue fillValue;
    private final List<JsonObject> codecs; // raw codec specs, in pipeline order
    private final JsonObject attributes;
    private final String[] dimensionNames; // null if absent; individual entries may be null (unnamed)

    private ChunkPipeline pipeline; // built lazily from the codec specs

    ArrayMetadata(RegularChunkGrid grid, DataType dataType, ChunkKeyEncoding chunkKeyEncoding,
                  JsonValue fillValue, List<JsonObject> codecs, JsonObject attributes,
                  String[] dimensionNames) {
        this.grid = grid;
        this.dataType = dataType;
        this.chunkKeyEncoding = chunkKeyEncoding;
        this.fillValue = fillValue;
        this.codecs = codecs;
        this.attributes = attributes;
        this.dimensionNames = dimensionNames;
    }

    /** Parses a validated array document. {@code ctx} names the source key for diagnostics. */
    static ArrayMetadata parse(JsonObject o, String ctx) {
        Fields.requireZarrFormat3(o, ctx);

        long[] shape = Fields.intArray(Fields.require(o, "shape", ctx), ctx + ".shape", false);
        int rank = shape.length;

        DataType dataType = parseDataType(Fields.require(o, "data_type", ctx), ctx + ".data_type");

        NamedConfig chunkGrid = NamedConfig.parse(Fields.require(o, "chunk_grid", ctx), ctx + ".chunk_grid");
        if (!chunkGrid.name().equals("regular")) {
            throw new ZarrUnsupportedException(ctx + ".chunk_grid: only the 'regular' grid is supported, was '"
                    + chunkGrid.name() + "'");
        }
        long[] chunkShape = Fields.intArray(
                Fields.require(chunkGrid.configuration(), "chunk_shape", ctx + ".chunk_grid.configuration"),
                ctx + ".chunk_grid.configuration.chunk_shape", true);
        if (chunkShape.length != rank) {
            throw new ZarrFormatException(ctx + ": chunk_shape rank " + chunkShape.length
                    + " does not match array rank " + rank);
        }
        RegularChunkGrid grid;
        try {
            grid = new RegularChunkGrid(shape, chunkShape);
        } catch (IllegalArgumentException e) {
            // Ranks, signs, and positivity are checked above, so the array has more elements than a long counts.
            throw new ZarrUnsupportedException(ctx + ".shape: " + e.getMessage());
        }

        NamedConfig encodingConfig =
                NamedConfig.parse(Fields.require(o, "chunk_key_encoding", ctx), ctx + ".chunk_key_encoding");
        ChunkKeyEncoding chunkKeyEncoding =
                ChunkKeyEncoding.of(encodingConfig.name(), parseSeparator(encodingConfig, ctx));

        JsonValue fillValue = Fields.require(o, "fill_value", ctx);
        if (dataType.isVariableLength()) {
            // A variable-length string's fill value is a JSON string (typically ""); there is no fixed-size
            // byte encoding to validate against.
            if (!(fillValue instanceof JsonString)) {
                throw new ZarrFormatException(ctx + ".fill_value: the '" + dataType.name()
                        + "' data type requires a string fill value");
            }
        } else {
            try {
                dataType.decodeFillValue(fillValue, ByteOrder.LITTLE_ENDIAN);
            } catch (ZarrFormatException e) {
                throw new ZarrFormatException(ctx + ".fill_value: " + e.getMessage(), e);
            }
        }

        List<JsonObject> codecs = codecSpecs(Fields.require(o, "codecs", ctx), ctx + ".codecs");

        JsonObject attributes = o.find("attributes")
                .map(v -> Fields.object(v, ctx + ".attributes"))
                .orElse(Fields.EMPTY_OBJECT);

        String[] dimensionNames = parseDimensionNames(o, rank, ctx);

        rejectStorageTransformers(o, ctx);
        Fields.checkUnknownFields(o, KNOWN, ctx);

        return new ArrayMetadata(grid, dataType, chunkKeyEncoding, fillValue, List.copyOf(codecs),
                attributes, dimensionNames);
    }

    /**
     * The {@code data_type}: a core type's name, or the object form of one ({@code {"name": "int32"}},
     * without a configuration). A data type with a configuration is an extension Falcon does not
     * implement.
     */
    private static DataType parseDataType(JsonValue v, String ctx) {
        if (v instanceof JsonString s) {
            return DataType.of(s.value());
        }
        NamedConfig named = NamedConfig.parse(v, ctx);
        if (!named.configuration().members().isEmpty()) {
            throw new ZarrUnsupportedException(ctx + ": data type '" + named.name()
                    + "' with a configuration is an extension data type, which is not supported");
        }
        return DataType.of(named.name());
    }

    /**
     * The codec specs, each as an object: a codec named by a bare string ({@code "bytes"}) becomes
     * {@code {"name": "bytes"}}, inside a {@code sharding_indexed} codec's own {@code codecs} and
     * {@code index_codecs} lists too, so the codec layer reads one form.
     */
    private static List<JsonObject> codecSpecs(JsonValue v, String ctx) {
        JsonArray array = Fields.array(v, ctx);
        List<JsonObject> codecs = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            codecs.add(codecSpec(array.get(i), ctx + "[" + i + "]"));
        }
        return codecs;
    }

    private static JsonObject codecSpec(JsonValue v, String ctx) {
        if (v instanceof JsonString s) {
            return JsonObject.builder().put("name", s.value()).build();
        }
        JsonObject codec = Fields.object(v, ctx);
        String name = Fields.string(Fields.require(codec, "name", ctx), ctx + ".name");
        if (!name.equals("sharding_indexed")
                || !(codec.find("configuration").orElse(null) instanceof JsonObject config)) {
            return codec;
        }
        JsonObject.Builder newConfig = JsonObject.builder();
        for (Map.Entry<String, JsonValue> e : config.members().entrySet()) {
            boolean list = e.getKey().equals("codecs") || e.getKey().equals("index_codecs");
            newConfig.put(e.getKey(), list && e.getValue() instanceof JsonArray
                    ? new JsonArray(List.copyOf(codecSpecs(e.getValue(), ctx + ".configuration." + e.getKey())))
                    : e.getValue());
        }
        JsonObject.Builder newCodec = JsonObject.builder();
        for (Map.Entry<String, JsonValue> e : codec.members().entrySet()) {
            newCodec.put(e.getKey(), e.getKey().equals("configuration") ? newConfig.build() : e.getValue());
        }
        return newCodec.build();
    }

    private static String parseSeparator(NamedConfig encoding, String ctx) {
        String defaultSeparator = switch (encoding.name()) {
            case "default" -> "/";
            case "v2" -> ".";
            default -> throw new ZarrUnsupportedException(ctx + ".chunk_key_encoding: unknown encoding '"
                    + encoding.name() + "' (expected 'default' or 'v2')");
        };
        String separator = encoding.configuration().find("separator")
                .map(v -> Fields.string(v, ctx + ".chunk_key_encoding.configuration.separator"))
                .orElse(defaultSeparator);
        if (!separator.equals("/") && !separator.equals(".")) {
            throw new ZarrFormatException(
                    ctx + ".chunk_key_encoding: separator must be '/' or '.', was '" + separator + "'");
        }
        return separator;
    }

    private static String[] parseDimensionNames(JsonObject o, int rank, String ctx) {
        if (!o.has("dimension_names") || o.get("dimension_names").isNull()) {
            return null; // null, as zarr-python reads it, means no names
        }
        JsonArray names = Fields.array(o.get("dimension_names"), ctx + ".dimension_names");
        if (names.size() != rank) {
            throw new ZarrFormatException(ctx + ".dimension_names: length " + names.size()
                    + " does not match array rank " + rank);
        }
        String[] out = new String[rank];
        for (int i = 0; i < rank; i++) {
            JsonValue entry = names.get(i);
            out[i] = entry.isNull() ? null : Fields.string(entry, ctx + ".dimension_names[" + i + "]");
        }
        return out;
    }

    private static void rejectStorageTransformers(JsonObject o, String ctx) {
        if (o.has("storage_transformers")
                && Fields.array(o.get("storage_transformers"), ctx + ".storage_transformers").size() > 0) {
            throw new ZarrUnsupportedException(ctx + ": storage_transformers are not supported");
        }
    }

    @Override
    public NodeType nodeType() {
        return NodeType.ARRAY;
    }

    /** The array shape (a defensive copy). */
    public long[] shape() {
        return grid.arrayShape();
    }

    /** The number of dimensions. */
    public int rank() {
        return grid.rank();
    }

    /** The element data type. */
    public DataType dataType() {
        return dataType;
    }

    /** The fill value decoded to one element's bytes in the given order. */
    public byte[] fillValueBytes(ByteOrder order) {
        return dataType.decodeFillValue(fillValue, order);
    }

    /** The chunk shape (a defensive copy); same rank as {@link #shape()}, all entries positive. */
    public long[] chunkShape() {
        return grid.chunkShape();
    }

    /** The regular chunk grid (grid arithmetic and edge-chunk handling). */
    public RegularChunkGrid grid() {
        return grid;
    }

    /** The chunk key encoding ({@code default} or {@code v2}). */
    public ChunkKeyEncoding chunkKeyEncoding() {
        return chunkKeyEncoding;
    }

    /** The chunk key separator ({@code "/"} or {@code "."}). */
    public String separator() {
        return chunkKeyEncoding.separator();
    }

    /** The raw fill value; {@link #fillValueBytes} decodes it to element bytes. */
    public JsonValue fillValue() {
        return fillValue;
    }

    /** The codec names, in pipeline order. */
    public List<String> codecNames() {
        return codecs.stream().map(c -> c.get("name").asString()).toList();
    }

    /**
     * The chunk codec pipeline, built on first use from the codec specs and cached.
     *
     * @throws ZarrFormatException      if the codec order or a configuration is invalid
     * @throws ZarrUnsupportedException if a codec is not yet implemented
     */
    public ChunkPipeline pipeline() {
        ChunkPipeline p = pipeline;
        if (p == null) {
            p = ChunkPipeline.of(dataType, grid.chunkShape(), codecs);
            pipeline = p;
        }
        return p;
    }

    @Override
    public JsonObject attributes() {
        return attributes;
    }

    /**
     * The dimension names, if the {@code dimension_names} field is present. The returned list has one
     * entry per dimension; an entry is {@code null} for an unnamed dimension.
     */
    public Optional<List<String>> dimensionNames() {
        if (dimensionNames == null) {
            return Optional.empty();
        }
        return Optional.of(Collections.unmodifiableList(Arrays.asList(dimensionNames.clone())));
    }
}
