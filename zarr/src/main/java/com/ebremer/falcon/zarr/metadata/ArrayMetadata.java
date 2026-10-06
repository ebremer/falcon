package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.chunk.ChunkGrid;
import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.chunk.RectilinearChunkGrid;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.codec.VlenCodec;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed array metadata: shape, data type, chunk grid, chunk key encoding, fill value, and codecs, plus
 * optional attributes and dimension names.
 *
 * <p>The {@code data_type} is resolved to a {@link DataType} and the {@code fill_value} is validated
 * against it at parse time (though kept as raw JSON; {@link #fillValueBytes} decodes it on demand). The
 * {@code codecs} are kept as raw specs and assembled into a {@link ChunkPipeline} on first use (see
 * {@link #pipeline()}). The {@code regular} chunk grid and the {@code rectilinear} extension, and the
 * {@code default}/{@code v2} chunk key encodings, are recognized; anything else is reported as
 * {@link ZarrUnsupportedException}. A storage transformer is read past only if it says
 * {@code "must_understand": false}.
 */
public final class ArrayMetadata implements NodeMetadata {

    private static final Set<String> KNOWN = Set.of(
            "zarr_format", "node_type", "shape", "data_type", "chunk_grid", "chunk_key_encoding",
            "fill_value", "codecs", "attributes", "dimension_names", "storage_transformers");

    /** The largest element whose fill value is decoded when the document is parsed. */
    private static final int MAX_EAGER_FILL = 1 << 20;

    private final ChunkGrid grid;
    private final DataType dataType;
    private final ChunkKeyEncoding chunkKeyEncoding;
    private final JsonValue fillValue;
    private final List<JsonObject> codecs; // raw codec specs, in pipeline order
    private final JsonObject attributes;
    private final String[] dimensionNames; // null if absent; individual entries may be null (unnamed)
    private final int zarrFormat;          // 3, or 2 for a v2 array translated by V2Metadata

    private ChunkPipeline pipeline; // built lazily from the codec specs
    // A rectilinear grid's chunks differ in shape, and a pipeline is built for one shape: one per shape met.
    private final Map<List<Long>, ChunkPipeline> pipelines = new ConcurrentHashMap<>();

    ArrayMetadata(ChunkGrid grid, DataType dataType, ChunkKeyEncoding chunkKeyEncoding,
                  JsonValue fillValue, List<JsonObject> codecs, JsonObject attributes,
                  String[] dimensionNames, int zarrFormat) {
        this.grid = grid;
        this.dataType = dataType;
        this.chunkKeyEncoding = chunkKeyEncoding;
        this.fillValue = fillValue;
        this.codecs = codecs;
        this.attributes = attributes;
        this.dimensionNames = dimensionNames;
        this.zarrFormat = zarrFormat;
    }

    /** Parses a validated array document. {@code ctx} names the source key for diagnostics. */
    static ArrayMetadata parse(JsonObject o, String ctx) {
        return parse(o, ctx, 3);
    }

    /**
     * Parses a validated array document, recording {@code zarrFormat} as the format it was stored in: 2 for
     * the v3 document {@link V2Metadata} translates a {@code .zarray} into.
     */
    static ArrayMetadata parse(JsonObject o, String ctx, int zarrFormat) {
        Fields.requireZarrFormat3(o, ctx);

        long[] shape = Fields.intArray(Fields.require(o, "shape", ctx), ctx + ".shape", false);
        int rank = shape.length;

        DataType dataType = parseDataType(Fields.require(o, "data_type", ctx), ctx + ".data_type");

        ChunkGrid grid = ChunkGrids.parse(Fields.require(o, "chunk_grid", ctx), shape, ctx + ".chunk_grid");

        NamedConfig encodingConfig =
                NamedConfig.parse(Fields.require(o, "chunk_key_encoding", ctx), ctx + ".chunk_key_encoding");
        ChunkKeyEncoding chunkKeyEncoding =
                ChunkKeyEncoding.of(encodingConfig.name(), parseSeparator(encodingConfig, ctx));

        JsonValue fillValue = Fields.require(o, "fill_value", ctx);
        if (dataType.isVariableLength()) {
            // A variable-length fill value is a JSON string (typically ""): the string itself, or base64 for
            // variable_length_bytes. There is no fixed-size byte encoding to validate against.
            try {
                VlenCodec.of(dataType).fill(fillValue);
            } catch (ZarrFormatException e) {
                throw new ZarrFormatException(ctx + ".fill_value: " + e.getMessage(), e);
            }
        } else if (dataType.byteCount() <= MAX_EAGER_FILL) {
            // An element larger than this (only an extension type's, claiming megabytes) has its fill value
            // checked when it is first decoded, so opening a document allocates nothing in proportion to it.
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

        checkStorageTransformers(o, ctx);
        Fields.checkUnknownFields(o, KNOWN, ctx);

        return new ArrayMetadata(grid, dataType, chunkKeyEncoding, fillValue, List.copyOf(codecs),
                attributes, dimensionNames, zarrFormat);
    }

    /**
     * The {@code data_type}: a core type's name, the object form of one ({@code {"name": "int32"}}), or an
     * extension type zarr-python writes, with its configuration (F14; {@link DataType#fromJson}).
     */
    private static DataType parseDataType(JsonValue v, String ctx) {
        if (v instanceof JsonString s) {
            return DataType.of(s.value());
        }
        NamedConfig.parse(v, ctx); // an object with a string name and an object configuration, if any
        try {
            return DataType.fromJson(v);
        } catch (ZarrUnsupportedException e) {
            throw new ZarrUnsupportedException(ctx + ": " + e.getMessage());
        } catch (ZarrFormatException e) {
            throw new ZarrFormatException(ctx + ": " + e.getMessage(), e);
        }
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

    /**
     * Falcon implements no storage transformer (none is registered), so it reads past one only when the
     * transformer says {@code "must_understand": false}, as the v3 specification allows for an extension
     * object; any other is refused, by name. A transformer named by a bare string must be understood.
     */
    private static void checkStorageTransformers(JsonObject o, String ctx) {
        if (!o.has("storage_transformers") || o.get("storage_transformers").isNull()) {
            return;
        }
        String sctx = ctx + ".storage_transformers";
        JsonArray transformers = Fields.array(o.get("storage_transformers"), sctx);
        for (int i = 0; i < transformers.size(); i++) {
            JsonValue t = transformers.get(i);
            String tctx = sctx + "[" + i + "]";
            String name = t instanceof JsonString s ? s.value()
                    : Fields.string(Fields.require(Fields.object(t, tctx), "name", tctx), tctx + ".name");
            if (t instanceof JsonObject ext && ext.members().get("must_understand") instanceof JsonBool flag
                    && !flag.value()) {
                continue;
            }
            throw new ZarrUnsupportedException(tctx + ": storage transformer '" + name + "' is not supported"
                    + " (only one with \"must_understand\": false may be ignored)");
        }
    }

    @Override
    public NodeType nodeType() {
        return NodeType.ARRAY;
    }

    @Override
    public int zarrFormat() {
        return zarrFormat;
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

    /**
     * The chunk shape (a defensive copy) of a regular grid; same rank as {@link #shape()}, all entries
     * positive.
     *
     * @throws UnsupportedOperationException if the grid is rectilinear, whose chunks differ in shape
     */
    public long[] chunkShape() {
        if (grid instanceof RegularChunkGrid regular) {
            return regular.chunkShape();
        }
        throw new UnsupportedOperationException("the array has a rectilinear chunk grid, whose chunks differ in"
                + " shape: use chunkSizes()");
    }

    /** The chunk grid (grid arithmetic and edge-chunk handling). */
    public ChunkGrid grid() {
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
     * The chunk codec pipeline, built on first use from the codec specs and cached. In a rectilinear grid it
     * is the first chunk's, for what does not depend on a chunk's shape (the element order, the
     * variable-length codec, the sub-chunk shape); {@link #chunkPipeline} gives each chunk's own.
     *
     * @throws ZarrFormatException      if the codec order or a configuration is invalid
     * @throws ZarrUnsupportedException if a codec is not yet implemented
     */
    public ChunkPipeline pipeline() {
        ChunkPipeline p = pipeline;
        if (p == null) {
            p = build(firstChunkShape());
            pipeline = p;
        }
        return p;
    }

    /**
     * The pipeline for chunks of {@code shape}, with the fill value checked against it: a {@code cast_value}
     * codec must be able to cast the fill value both ways ({@link ChunkPipeline#checkFillValue}).
     */
    private ChunkPipeline build(long[] shape) {
        ChunkPipeline p = ChunkPipeline.of(dataType, shape, codecs);
        if (!dataType.isVariableLength() && dataType.byteCount() <= MAX_EAGER_FILL) {
            p.checkFillValue(fillValueBytes(p.elementOrder()));
        }
        return p;
    }

    /**
     * The pipeline for the chunk at {@code coords}, built for its declared shape: {@link #pipeline()} in a
     * regular grid, and in a rectilinear one a pipeline per chunk shape, built on first use and cached.
     *
     * @throws ZarrFormatException      if the codec order or a configuration is invalid, or does not fit
     *                                  this chunk's shape (a shard not divisible into sub-chunks, say)
     * @throws ZarrUnsupportedException if a codec is not yet implemented
     */
    public ChunkPipeline chunkPipeline(long[] coords) {
        if (grid.isRegular()) {
            return pipeline();
        }
        long[] shape = new long[coords.length];
        for (int i = 0; i < shape.length; i++) {
            shape[i] = grid.chunkLength(i, coords[i]);
        }
        return pipelineFor(shape);
    }

    private ChunkPipeline pipelineFor(long[] shape) {
        List<Long> key = Arrays.stream(shape).boxed().toList();
        ChunkPipeline p = pipelines.get(key);
        if (p == null) {
            p = build(shape); // may throw: then nothing is cached
            pipelines.putIfAbsent(key, p);
        }
        return p;
    }

    /** The declared shape of chunk {@code (0, ..., 0)}, which every grid has, even over an empty array. */
    private long[] firstChunkShape() {
        long[] shape = new long[grid.rank()];
        for (int i = 0; i < shape.length; i++) {
            shape[i] = grid.chunkLength(i, 0);
        }
        return shape;
    }

    /**
     * Builds the pipeline for every chunk shape the grid lists, as a writer checks before creating an array.
     * In a rectilinear grid that is each dimension's distinct lengths in turn, the other dimensions at their
     * first length, and the largest length of every dimension at once: a codec's checks of a chunk shape
     * (a shard's division into sub-chunks, the size of one buffer) look at each dimension alone or at the
     * total size.
     *
     * @throws ZarrFormatException      if a chunk shape does not suit the codecs
     * @throws ZarrUnsupportedException if a codec is not yet implemented
     */
    public void checkPipelines() {
        pipeline();
        if (!(grid instanceof RectilinearChunkGrid rectilinear)) {
            return;
        }
        long[] first = firstChunkShape();
        long[] largest = first.clone();
        for (int i = 0; i < first.length; i++) {
            for (long length : rectilinear.distinctLengths(i)) {
                long[] shape = first.clone();
                shape[i] = length;
                pipelineFor(shape);
                largest[i] = Math.max(largest[i], length);
            }
        }
        pipelineFor(largest);
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
