package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parsed array metadata: shape, data type, chunk grid, chunk key encoding, fill value, and codecs, plus
 * optional attributes and dimension names.
 *
 * <p>Z1 parses and validates the <em>structure</em> of every field, but defers semantic interpretation
 * to later stages: the {@code data_type} is kept as its spec name (the element layout lands in Z2), the
 * {@code fill_value} is kept as raw JSON (decoded in Z2), and the {@code codecs} are kept as
 * {@link NamedConfig}s (the pipeline is built in Z4). Only the {@code regular} chunk grid and the
 * {@code default}/{@code v2} chunk key encodings are recognized; anything else is reported as
 * {@link ZarrUnsupportedException}.
 */
public final class ArrayMetadata implements NodeMetadata {

    private static final Set<String> KNOWN = Set.of(
            "zarr_format", "node_type", "shape", "data_type", "chunk_grid", "chunk_key_encoding",
            "fill_value", "codecs", "attributes", "dimension_names", "storage_transformers");

    private final long[] shape;
    private final String dataType;
    private final NamedConfig chunkGrid;
    private final long[] chunkShape;
    private final NamedConfig chunkKeyEncoding;
    private final String separator;
    private final JsonValue fillValue;
    private final List<NamedConfig> codecs;
    private final JsonObject attributes;
    private final String[] dimensionNames; // null if absent; individual entries may be null (unnamed)

    ArrayMetadata(long[] shape, String dataType, NamedConfig chunkGrid, long[] chunkShape,
                  NamedConfig chunkKeyEncoding, String separator, JsonValue fillValue,
                  List<NamedConfig> codecs, JsonObject attributes, String[] dimensionNames) {
        this.shape = shape;
        this.dataType = dataType;
        this.chunkGrid = chunkGrid;
        this.chunkShape = chunkShape;
        this.chunkKeyEncoding = chunkKeyEncoding;
        this.separator = separator;
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

        JsonValue dataTypeValue = Fields.require(o, "data_type", ctx);
        if (!(dataTypeValue instanceof JsonString)) {
            throw new ZarrUnsupportedException(
                    ctx + ".data_type: extension (object) data types are not yet supported");
        }
        String dataType = Fields.string(dataTypeValue, ctx + ".data_type");

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

        NamedConfig chunkKeyEncoding =
                NamedConfig.parse(Fields.require(o, "chunk_key_encoding", ctx), ctx + ".chunk_key_encoding");
        String separator = parseSeparator(chunkKeyEncoding, ctx);

        JsonValue fillValue = Fields.require(o, "fill_value", ctx);

        JsonArray codecArray = Fields.array(Fields.require(o, "codecs", ctx), ctx + ".codecs");
        List<NamedConfig> codecs = new ArrayList<>(codecArray.size());
        for (int i = 0; i < codecArray.size(); i++) {
            codecs.add(NamedConfig.parse(codecArray.get(i), ctx + ".codecs[" + i + "]"));
        }

        JsonObject attributes = o.find("attributes")
                .map(v -> Fields.object(v, ctx + ".attributes"))
                .orElse(Fields.EMPTY_OBJECT);

        String[] dimensionNames = parseDimensionNames(o, rank, ctx);

        rejectStorageTransformers(o, ctx);
        Fields.checkUnknownFields(o, KNOWN, ctx);

        return new ArrayMetadata(shape, dataType, chunkGrid, chunkShape, chunkKeyEncoding, separator,
                fillValue, List.copyOf(codecs), attributes, dimensionNames);
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
        if (!o.has("dimension_names")) {
            return null;
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
        return shape.clone();
    }

    /** The number of dimensions. */
    public int rank() {
        return shape.length;
    }

    /** The data-type name as written in {@code zarr.json} (for example {@code "float64"}). */
    public String dataType() {
        return dataType;
    }

    /** The chunk shape (a defensive copy); same rank as {@link #shape()}, all entries positive. */
    public long[] chunkShape() {
        return chunkShape.clone();
    }

    /** The chunk grid extension ({@code name} is always {@code "regular"} in Z1). */
    public NamedConfig chunkGrid() {
        return chunkGrid;
    }

    /** The chunk key encoding extension ({@code "default"} or {@code "v2"}). */
    public NamedConfig chunkKeyEncoding() {
        return chunkKeyEncoding;
    }

    /** The chunk key separator ({@code "/"} or {@code "."}). */
    public String separator() {
        return separator;
    }

    /** The raw fill value, decoded to the element type in Z2. */
    public JsonValue fillValue() {
        return fillValue;
    }

    /** The codec chain as named extensions, assembled into a pipeline in Z4. */
    public List<NamedConfig> codecs() {
        return codecs;
    }

    /** The codec names, in pipeline order. */
    public List<String> codecNames() {
        return codecs.stream().map(NamedConfig::name).toList();
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
