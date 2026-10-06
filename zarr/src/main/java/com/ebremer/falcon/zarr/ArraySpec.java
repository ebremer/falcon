package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.codec.VlenCodec;
import com.ebremer.falcon.zarr.data.Elements;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.metadata.Metadata;
import com.ebremer.falcon.zarr.metadata.NodeMetadata;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The description of an array to create: its shape and data type, plus the chunking, fill value, codecs,
 * and attributes to record in {@code zarr.json}.
 *
 * <p>Build one with {@link #builder(long[], DataType)}; everything but the shape and data type has a
 * sensible default (one chunk covering the array, a zero fill value, and a little-endian {@code bytes}
 * codec). {@link Builder#build()} checks the whole description, as opening the array would, so a spec
 * that exists is one Falcon can create, read, and write.
 */
public final class ArraySpec {

    private final long[] shape;
    private final DataType dataType;
    private final long[] chunkShape;
    private final JsonValue fillValue;
    private final List<JsonValue> codecs;
    private final JsonObject attributes;
    private final String[] dimensionNames;
    private final String encodingName;
    private final String separator;

    private ArraySpec(Builder b) {
        this.shape = b.shape.clone();
        this.dataType = b.dataType;
        this.chunkShape = b.chunkShape != null ? b.chunkShape.clone() : defaultChunkShape(b.shape);
        this.fillValue = fillJson(b);
        this.codecs = buildCodecs(b);
        this.attributes = b.attributes;
        this.dimensionNames = b.dimensionNames == null ? null : b.dimensionNames.clone();
        this.encodingName = b.encodingName;
        this.separator = b.separator;
    }

    /** A builder for an array of {@code shape} holding {@code dataType} elements. */
    public static Builder builder(long[] shape, DataType dataType) {
        return new Builder(shape, dataType);
    }

    private static long[] defaultChunkShape(long[] shape) {
        long[] chunks = new long[shape.length];
        for (int i = 0; i < shape.length; i++) {
            chunks[i] = Math.max(shape[i], 1); // a chunk dimension must be positive
        }
        return chunks;
    }

    /**
     * The fill value's JSON. A number set with {@code fillValue(long)} or {@code fillValue(double)} is
     * converted to the data type as a write would convert it, then written in the type's own form: an
     * integer type gets an integer, bool gets {@code true}/{@code false}, and a float keeps its bits ("NaN"
     * for the canonical NaN, a hex string for any other).
     */
    private static JsonValue fillJson(Builder b) {
        DataType dt = b.dataType;
        if (b.fillValue != null) {
            return b.fillValue;
        }
        if (b.fillNumber == null) {
            return dt.isVariableLength() ? new JsonString("")
                    : dt.encodeFillValue(new byte[dt.byteCount()], ByteOrder.LITTLE_ENDIAN);
        }
        byte[] element;
        try {
            element = b.fillNumber instanceof Long l
                    ? Elements.fromLong(l, dt, ByteOrder.LITTLE_ENDIAN)
                    : Elements.fromDouble(b.fillNumber.doubleValue(), dt, ByteOrder.LITTLE_ENDIAN);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid fill value: " + e.getMessage(), e);
        } catch (ZarrException e) {
            throw new IllegalArgumentException("a numeric fill value needs a bool, integer, or float data type, not '"
                    + dt.name() + "': use fillValue(JsonValue)", e);
        }
        return dt.encodeFillValue(element, ByteOrder.LITTLE_ENDIAN);
    }

    private static List<JsonValue> buildCodecs(Builder b) {
        List<JsonValue> inner = new ArrayList<>();
        // The array->bytes codec: vlen-utf8 or vlen-bytes for variable-length elements, otherwise the
        // fixed-size bytes codec.
        inner.add(b.dataType.isVariableLength()
                ? named(VlenCodec.of(b.dataType).codecName(), JsonObject.builder().build())
                : bytesCodec(b.dataType, b.endian));
        if (b.gzipLevel != null) {
            inner.add(named("gzip", JsonObject.builder().put("level", b.gzipLevel).build()));
        }
        if (b.zstd) {
            inner.add(named("zstd", JsonObject.builder().put("level", 0).put("checksum", false).build()));
        }
        if (b.blosc) {
            int typeSize = Math.max(b.dataType.byteCount(), 1); // variable-length elements have no fixed size
            boolean shuffle = typeSize > 1;
            inner.add(named("blosc", JsonObject.builder()
                    .put("cname", "zstd").put("clevel", 5)
                    .put("shuffle", shuffle ? "shuffle" : "noshuffle")
                    .put("typesize", typeSize).put("blocksize", 0).build()));
        }
        if (b.crc32c) {
            inner.add(named("crc32c", null));
        }
        if (b.subChunkShape == null) {
            return List.copyOf(inner);
        }
        JsonObject sharding = JsonObject.builder()
                .put("chunk_shape", numbers(b.subChunkShape))
                .put("codecs", new JsonArray(inner))
                .put("index_codecs", new JsonArray(List.of(
                        bytesCodec(DataType.UINT64, ByteOrder.LITTLE_ENDIAN), named("crc32c", null))))
                .put("index_location", b.indexAtStart ? "start" : "end")
                .build();
        return List.of(named("sharding_indexed", sharding));
    }

    private static JsonValue bytesCodec(DataType dataType, ByteOrder endian) {
        if (dataType.byteCount() == 1) {
            return named("bytes", null); // byte order is meaningless for single-byte elements
        }
        String value = endian == ByteOrder.BIG_ENDIAN ? "big" : "little";
        return named("bytes", JsonObject.builder().put("endian", value).build());
    }

    private static JsonValue named(String name, JsonObject configuration) {
        JsonObject.Builder b = JsonObject.builder().put("name", name);
        if (configuration != null) {
            b.put("configuration", configuration);
        }
        return b.build();
    }

    private static JsonArray numbers(long[] values) {
        List<JsonValue> out = new ArrayList<>(values.length);
        for (long v : values) {
            out.add(JsonNumber.of(v));
        }
        return new JsonArray(out);
    }

    /** The array shape (a defensive copy). */
    public long[] shape() {
        return shape.clone();
    }

    /** The element data type. */
    public DataType dataType() {
        return dataType;
    }

    /** This spec as a {@code zarr.json} document, with fields in the order the specification lists them. */
    public JsonObject toJson() {
        JsonObject.Builder json = JsonObject.builder()
                .put("zarr_format", Zarr.ZARR_FORMAT)
                .put("node_type", "array")
                .put("shape", numbers(shape))
                .put("data_type", dataType.name())
                .put("chunk_grid", named("regular",
                        JsonObject.builder().put("chunk_shape", numbers(chunkShape)).build()))
                .put("chunk_key_encoding", named(encodingName,
                        JsonObject.builder().put("separator", separator()).build()))
                .put("fill_value", fillValue)
                .put("codecs", new JsonArray(codecs))
                .put("attributes", attributes);
        if (dimensionNames != null) {
            List<JsonValue> names = new ArrayList<>(dimensionNames.length);
            for (String name : dimensionNames) {
                names.add(name == null ? com.ebremer.falcon.zarr.json.JsonNull.INSTANCE : new JsonString(name));
            }
            json.put("dimension_names", new JsonArray(names));
        }
        return json.build();
    }

    private String separator() {
        if (separator != null) {
            return separator;
        }
        return encodingName.equals("v2") ? "." : "/";
    }

    /** Builds an {@link ArraySpec}. */
    public static final class Builder {

        private final long[] shape;
        private final DataType dataType;
        private long[] chunkShape;
        private JsonValue fillValue;   // set by fillValue(JsonValue), else
        private Number fillNumber;     // a Long or Double, converted to the data type by build()
        private JsonObject attributes = new JsonObject(Map.of());
        private String[] dimensionNames;
        private String encodingName = "default";
        private String separator;
        private ByteOrder endian = ByteOrder.LITTLE_ENDIAN;
        private Integer gzipLevel;
        private boolean zstd;
        private boolean blosc;
        private boolean crc32c;
        private long[] subChunkShape;
        private boolean indexAtStart;

        private Builder(long[] shape, DataType dataType) {
            this.shape = shape.clone();
            this.dataType = dataType;
        }

        /** Sets the chunk shape (default: a single chunk covering the array). */
        public Builder chunkShape(long... chunkShape) {
            this.chunkShape = chunkShape.clone();
            return this;
        }

        /**
         * Sets the fill value as raw JSON, written to {@code zarr.json} as given: for the {@code string} data
         * type a JSON string, and for {@code variable_length_bytes} the bytes in base64 (as zarr-python
         * writes them). Both default to empty.
         */
        public Builder fillValue(JsonValue fillValue) {
            this.fillValue = fillValue;
            this.fillNumber = null;
            return this;
        }

        /**
         * Sets the fill value from an integer, converted to the data type as {@code writeLongs} converts:
         * an integer type must hold it exactly, a float type rounds it, and bool takes nonzero as true.
         * {@link #build()} rejects a value the type cannot hold.
         */
        public Builder fillValue(long fillValue) {
            this.fillNumber = fillValue;
            this.fillValue = null;
            return this;
        }

        /**
         * Sets the fill value from a {@code double}, converted to the data type as {@code writeDoubles}
         * converts: an integer type takes only a whole number in its range (written as an integer), a float
         * type rounds it (NaN and the infinities included, a NaN keeping its bits), and bool takes nonzero
         * as true. {@link #build()} rejects a value the type cannot hold.
         */
        public Builder fillValue(double fillValue) {
            this.fillNumber = fillValue;
            this.fillValue = null;
            return this;
        }

        /** Sets the element byte order used by the {@code bytes} codec (default little-endian). */
        public Builder endian(ByteOrder endian) {
            this.endian = endian;
            return this;
        }

        /** Compresses chunks with {@code gzip} at the given level (0&ndash;9). */
        public Builder gzip(int level) {
            this.gzipLevel = level;
            return this;
        }

        /** Compresses chunks with {@code zstd} (Falcon's pure-Java encoder; libzstd/zarr-python read it). */
        public Builder zstd() {
            this.zstd = true;
            return this;
        }

        /** Compresses chunks with {@code blosc} (byte-shuffle + zstd; c-blosc/zarr-python read it). */
        public Builder blosc() {
            this.blosc = true;
            return this;
        }

        /** Appends a {@code crc32c} checksum to each stored chunk (or sub-chunk, when sharding). */
        public Builder crc32c() {
            this.crc32c = true;
            return this;
        }

        /** Stores each chunk as a shard of sub-chunks of the given shape. */
        public Builder sharding(long... subChunkShape) {
            this.subChunkShape = subChunkShape.clone();
            return this;
        }

        /** Places the shard index at the start of the shard instead of the end. */
        public Builder shardIndexAtStart() {
            this.indexAtStart = true;
            return this;
        }

        /** Sets the user attributes. */
        public Builder attributes(JsonObject attributes) {
            this.attributes = attributes;
            return this;
        }

        /** Sets the dimension names; a {@code null} entry leaves that dimension unnamed. */
        public Builder dimensionNames(String... dimensionNames) {
            this.dimensionNames = dimensionNames.clone();
            return this;
        }

        /** Selects the chunk key encoding: {@code "default"} or {@code "v2"}. */
        public Builder chunkKeyEncoding(String name) {
            this.encodingName = name;
            return this;
        }

        /** Sets the chunk key separator ({@code "/"} or {@code "."}). */
        public Builder separator(String separator) {
            this.separator = separator;
            return this;
        }

        /**
         * The finished spec, checked as opening the array would check it: the shapes, fill value, dimension
         * names, chunk key encoding, and codecs (including that a chunk fits one buffer).
         *
         * @throws IllegalArgumentException if the spec does not describe an array Falcon can create, read,
         *                                  and write
         */
        public ArraySpec build() {
            ArraySpec spec = new ArraySpec(this);
            try {
                NodeMetadata parsed = Metadata.parse(Json.writeBytes(spec.toJson()), "zarr.json");
                ((ArrayMetadata) parsed).pipeline();
            } catch (ZarrException e) {
                throw new IllegalArgumentException("invalid array spec: " + e.getMessage(), e);
            }
            return spec;
        }
    }
}
