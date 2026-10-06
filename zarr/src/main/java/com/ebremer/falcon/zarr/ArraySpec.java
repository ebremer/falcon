package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.chunk.RectilinearChunkGrid;
import com.ebremer.falcon.zarr.codec.VlenCodec;
import com.ebremer.falcon.zarr.data.Elements;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The description of an array to create: its shape and data type, plus the chunking, fill value, codecs,
 * and attributes to record in {@code zarr.json}.
 *
 * <p>Build one with {@link #builder(long[], DataType)}; everything but the shape and data type has a
 * sensible default (one chunk covering the array, zarr-python's default fill value for the type &mdash;
 * zero, or NaT for a time type; see {@link DataType#defaultFillValue()} &mdash; and a little-endian
 * {@code bytes} codec). {@link Builder#build()} checks the whole description, as opening the array would,
 * so a spec that exists is one Falcon can create, read, and write.
 */
public final class ArraySpec {

    private final long[] shape;
    private final DataType dataType;
    private final JsonObject chunkGrid;
    private final JsonValue fillValue;
    private final List<JsonValue> codecs;
    private final JsonObject attributes;
    private final String[] dimensionNames;
    private final String encodingName;
    private final String separator;

    private ArraySpec(Builder b) {
        this.shape = b.shape.clone();
        this.dataType = b.dataType;
        this.chunkGrid = chunkGrid(b);
        this.fillValue = fillJson(b);
        this.codecs = buildCodecs(b);
        this.attributes = b.attributes;
        this.dimensionNames = b.dimensionNames == null ? null : b.dimensionNames.clone();
        this.encodingName = b.encodingName;
        this.separator = b.separator;
    }

    /**
     * A builder for an array of {@code shape} holding {@code dataType} elements.
     *
     * @param shape    the array's extent in each dimension (copied; zero-dimensional when empty)
     * @param dataType the element data type
     * @return a builder with every other setting at its default
     */
    public static Builder builder(long[] shape, DataType dataType) {
        return new Builder(shape, dataType);
    }

    /**
     * The {@code chunk_grid}: regular, unless a dimension lists its chunk lengths; then rectilinear, every
     * other dimension repeating its {@code chunkShape} entry (or one chunk covering it).
     */
    private static JsonObject chunkGrid(Builder b) {
        long[] chunkShape = b.chunkShape != null ? b.chunkShape : defaultChunkShape(b.shape);
        if (b.chunkLengths.isEmpty()) {
            return chunkGridJson(chunkShape);
        }
        if (chunkShape.length != b.shape.length) {
            throw new IllegalArgumentException("invalid array spec: chunk shape rank " + chunkShape.length
                    + " does not match array rank " + b.shape.length);
        }
        RectilinearChunkGrid.Axis[] axes = new RectilinearChunkGrid.Axis[b.shape.length];
        try {
            for (int i = 0; i < axes.length; i++) {
                long[] lengths = b.chunkLengths.get(i);
                axes[i] = lengths != null ? RectilinearChunkGrid.Axis.listed(lengths)
                        : RectilinearChunkGrid.Axis.repeating(chunkShape[i]);
            }
            return new RectilinearChunkGrid(b.shape, axes).toJson();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid array spec: " + e.getMessage(), e);
        }
    }

    /** A regular grid's JSON, written as given so that opening the array checks it. */
    private static JsonObject chunkGridJson(long[] chunkShape) {
        return JsonObject.builder().put("name", "regular")
                .put("configuration", JsonObject.builder().put("chunk_shape", numbers(chunkShape)).build())
                .build();
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
            return dt.defaultFillValue();
        }
        byte[] element;
        try {
            element = b.fillNumber instanceof Long l
                    ? Elements.fromLong(l, dt, ByteOrder.LITTLE_ENDIAN)
                    : Elements.fromDouble(b.fillNumber.doubleValue(), dt, ByteOrder.LITTLE_ENDIAN);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid fill value: " + e.getMessage(), e);
        } catch (ZarrException e) {
            throw new IllegalArgumentException("a numeric fill value needs a bool, integer, float, or (as an integer)"
                    + " time data type, not '" + dt.name() + "': use fillValue(JsonValue)", e);
        }
        return dt.encodeFillValue(element, ByteOrder.LITTLE_ENDIAN);
    }

    private static List<JsonValue> buildCodecs(Builder b) {
        List<JsonValue> inner = new ArrayList<>();
        // cast_value first, as zarr-python puts its filters (inside the shard, when sharding); the codecs after
        // it see the type it casts to.
        DataType stored = b.dataType;
        if (b.castType != null) {
            JsonObject.Builder config = JsonObject.builder().put("data_type", b.castType.toJson());
            if (!b.castRounding.equals("nearest-even")) {
                config.put("rounding", b.castRounding); // the default is left out, as zarr-python leaves it
            }
            if (b.castOutOfRange != null) {
                config.put("out_of_range", b.castOutOfRange);
            }
            if (b.castScalarMap != null) {
                config.put("scalar_map", b.castScalarMap);
            }
            inner.add(named("cast_value", config.build()));
            stored = b.castType;
        }
        // The array->bytes codec: vlen-utf8 or vlen-bytes for variable-length elements, otherwise the
        // fixed-size bytes codec.
        inner.add(b.dataType.isVariableLength()
                ? named(VlenCodec.of(b.dataType).codecName(), JsonObject.builder().build())
                : bytesCodec(stored, b.endian));
        if (b.gzipLevel != null) {
            inner.add(named("gzip", JsonObject.builder().put("level", b.gzipLevel).build()));
        }
        if (b.zstd) {
            inner.add(named("zstd", JsonObject.builder().put("level", b.zstdLevel).put("checksum", false).build()));
        }
        if (b.blosc) {
            int typeSize = Math.max(stored.byteCount(), 1); // variable-length elements have no fixed size
            String shuffle = b.bloscShuffle != null ? b.bloscShuffle : typeSize > 1 ? "shuffle" : "noshuffle";
            inner.add(named("blosc", JsonObject.builder()
                    .put("cname", b.bloscCname).put("clevel", b.bloscClevel)
                    .put("shuffle", shuffle)
                    .put("typesize", typeSize).put("blocksize", 0).build()));
        }
        if (b.bz2Level != null) {
            inner.add(named("numcodecs.bz2", JsonObject.builder().put("level", b.bz2Level).build()));
        }
        if (b.crc32c) {
            inner.add(named("crc32c", null));
        }
        // reshape, an array->array codec, comes first: a shard holds the reshaped chunk
        List<JsonValue> outer = new ArrayList<>();
        if (b.reshape != null) {
            outer.add(named("reshape", JsonObject.builder().put("shape", b.reshape).build()));
        }
        if (b.subChunkShape == null) {
            outer.addAll(inner);
            return List.copyOf(outer);
        }
        JsonObject sharding = JsonObject.builder()
                .put("chunk_shape", numbers(b.subChunkShape))
                .put("codecs", new JsonArray(inner))
                .put("index_codecs", new JsonArray(List.of(
                        bytesCodec(DataType.UINT64, ByteOrder.LITTLE_ENDIAN), named("crc32c", null))))
                .put("index_location", b.indexAtStart ? "start" : "end")
                .build();
        outer.add(named("sharding_indexed", sharding));
        return List.copyOf(outer);
    }

    private static JsonValue bytesCodec(DataType dataType, ByteOrder endian) {
        // Byte order is meaningless for single-byte elements and byte strings; r* keeps its endian, as before.
        if (dataType.kind() == DataTypeKind.RAW ? dataType.byteCount() == 1 : !dataType.hasByteOrder()) {
            return named("bytes", null);
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

    /** {@return the array shape (a defensive copy)} */
    public long[] shape() {
        return shape.clone();
    }

    /** {@return the element data type} */
    public DataType dataType() {
        return dataType;
    }

    /**
     * {@return this spec as a {@code zarr.json} document, with fields in the order the specification lists
     * them} An extension data type is written as zarr-python writes it ({@link DataType#toJson()}).
     */
    public JsonObject toJson() {
        JsonObject.Builder json = JsonObject.builder()
                .put("zarr_format", Zarr.ZARR_FORMAT)
                .put("node_type", "array")
                .put("shape", numbers(shape))
                .put("data_type", dataType.toJson())
                .put("chunk_grid", chunkGrid)
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
        private final Map<Integer, long[]> chunkLengths = new HashMap<>(); // listed lengths, by dimension
        private JsonValue fillValue;   // set by fillValue(JsonValue), else
        private Number fillNumber;     // a Long or Double, converted to the data type by build()
        private JsonObject attributes = new JsonObject(Map.of());
        private String[] dimensionNames;
        private String encodingName = "default";
        private String separator;
        private ByteOrder endian = ByteOrder.LITTLE_ENDIAN;
        private Integer gzipLevel;
        private boolean zstd;
        private int zstdLevel;
        private boolean blosc;
        private String bloscCname = "zstd";
        private int bloscClevel = 5;
        private String bloscShuffle; // null: the byte shuffle for multi-byte elements, else none
        private Integer bz2Level;
        private JsonArray reshape;
        private boolean crc32c;
        private long[] subChunkShape;
        private boolean indexAtStart;
        private DataType castType;     // null: no cast_value
        private String castRounding;
        private String castOutOfRange; // null: an element out of range fails the write
        private JsonObject castScalarMap;

        private Builder(long[] shape, DataType dataType) {
            this.shape = shape.clone();
            this.dataType = dataType;
        }

        /**
         * Sets the chunk shape (default: a single chunk covering the array).
         *
         * @param chunkShape the extent of every chunk along each dimension, of the array's rank, each positive
         * @return this builder
         */
        public Builder chunkShape(long... chunkShape) {
            this.chunkShape = chunkShape.clone();
            return this;
        }

        /**
         * Lists the lengths of the chunks along {@code dimension}, in order from the origin, which makes the
         * array's chunk grid {@code rectilinear} (zarr-extensions {@code chunk-grids/rectilinear}): its chunks
         * may then differ in shape. A dimension without listed lengths repeats its {@link #chunkShape} entry
         * (or has one chunk covering it). The lengths must reach the dimension's extent and may run past it.
         * zarr-python 3.4 reads such an array with {@code zarr.config.set({"array.rectilinear_chunks": True})}
         * (it creates one only with lengths summing to the extent exactly).
         *
         * <p>Sharding a rectilinear grid shards each chunk: every length must then be a multiple of the
         * sub-chunk shape's extent along its dimension, which {@link #build()} checks.
         *
         * @param dimension the dimension, from 0 to the array's rank minus one
         * @param lengths   the chunk lengths along it, each positive
         * @return this builder
         * @throws IndexOutOfBoundsException if {@code dimension} is not a dimension of the array
         * @throws IllegalArgumentException  if no length is given
         */
        public Builder chunkLengths(int dimension, long... lengths) {
            if (dimension < 0 || dimension >= shape.length) {
                throw new IndexOutOfBoundsException("dimension " + dimension + " of an array of rank " + shape.length);
            }
            if (lengths.length == 0) {
                throw new IllegalArgumentException("dimension " + dimension + " needs at least one chunk length");
            }
            chunkLengths.put(dimension, lengths.clone());
            return this;
        }

        /**
         * Sets the fill value as raw JSON, written to {@code zarr.json} as given, in the form
         * {@link DataType} describes: a JSON string for {@code string} and {@code fixed_length_utf32}; the
         * bytes in base64 for {@code variable_length_bytes}, {@code null_terminated_bytes}, and
         * {@code raw_bytes}; an integer or {@code "NaT"} for a time type; and for a {@code struct} an object
         * holding each field's fill value.
         *
         * @param fillValue the fill value's JSON
         * @return this builder
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
         *
         * @param fillValue the fill value, replacing any fill value set before
         * @return this builder
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
         *
         * @param fillValue the fill value, replacing any fill value set before
         * @return this builder
         */
        public Builder fillValue(double fillValue) {
            this.fillNumber = fillValue;
            this.fillValue = null;
            return this;
        }

        /**
         * Sets the element byte order used by the {@code bytes} codec (default little-endian). Single-byte
         * and variable-length data types record none.
         *
         * @param endian {@link ByteOrder#LITTLE_ENDIAN} or {@link ByteOrder#BIG_ENDIAN}
         * @return this builder
         */
        public Builder endian(ByteOrder endian) {
            this.endian = endian;
            return this;
        }

        /**
         * Compresses chunks with {@code gzip} at the given level (0&ndash;9).
         *
         * @param level the deflate level, 0 (stored) to 9 (smallest); {@link #build()} rejects any other
         * @return this builder
         */
        public Builder gzip(int level) {
            this.gzipLevel = level;
            return this;
        }

        /**
         * Compresses chunks with {@code zstd} at the default level (Falcon's pure-Java encoder;
         * libzstd/zarr-python read it).
         *
         * @return this builder
         */
        public Builder zstd() {
            return zstd(0);
        }

        /**
         * Compresses chunks with {@code zstd} at {@code level}, on libzstd's scale: 1 (fastest) to 22
         * (smallest), 0 for the default (3), a negative level for the fastest settings.
         *
         * @param level the compression level, -131072 to 22
         * @return this builder
         * @throws IllegalArgumentException if {@code level} is outside libzstd's range, -131072 to 22
         */
        public Builder zstd(int level) {
            if (level < -131072 || level > 22) {
                throw new IllegalArgumentException("zstd level must be -131072 to 22, not " + level);
            }
            this.zstd = true;
            this.zstdLevel = level;
            return this;
        }

        /**
         * Compresses chunks with {@code blosc} (byte-shuffle + zstd; c-blosc/zarr-python read it): zstd at
         * clevel 5, and the byte shuffle for multi-byte elements, replacing any earlier
         * {@link #blosc(String, int, String)} settings.
         *
         * @return this builder
         */
        public Builder blosc() {
            this.blosc = true;
            this.bloscCname = "zstd";
            this.bloscClevel = 5;
            this.bloscShuffle = null;
            return this;
        }

        /**
         * Compresses chunks with {@code blosc} with the given internal compressor, level, and filter, in blocks
         * c-blosc sizes, the type size the element's. Falcon writes each buffer as c-blosc 1.21 writes it, byte
         * for byte, but for zstd, whose encoder is Falcon's own.
         *
         * @param cname   the internal compressor: {@code blosclz}, {@code lz4}, {@code lz4hc}, {@code zlib},
         *                {@code zstd}, or {@code snappy} (which numcodecs' c-blosc, and so zarr-python, cannot
         *                read)
         * @param clevel  the level, 0 (stored as it is) to 9
         * @param shuffle the filter: {@code noshuffle}, {@code shuffle} (bytes), or {@code bitshuffle}
         * @return this builder
         * @throws IllegalArgumentException if a setting is none of those
         */
        public Builder blosc(String cname, int clevel, String shuffle) {
            if (!List.of("blosclz", "lz4", "lz4hc", "zlib", "zstd", "snappy").contains(cname)) {
                throw new IllegalArgumentException("blosc cname must be blosclz, lz4, lz4hc, zlib, zstd, or snappy, not "
                        + cname);
            }
            if (clevel < 0 || clevel > 9) {
                throw new IllegalArgumentException("blosc clevel must be 0 to 9, not " + clevel);
            }
            if (!List.of("noshuffle", "shuffle", "bitshuffle").contains(shuffle)) {
                throw new IllegalArgumentException("blosc shuffle must be noshuffle, shuffle, or bitshuffle, not "
                        + shuffle);
            }
            this.blosc = true;
            this.bloscCname = cname;
            this.bloscClevel = clevel;
            this.bloscShuffle = shuffle;
            return this;
        }

        /**
         * Compresses chunks with numcodecs' bzip2 codec, {@code numcodecs.bz2}, as zarr-python 3 names it: each
         * chunk one bzip2 stream, written byte for byte as numcodecs writes it (libbzip2 1.0.8, through Python's
         * {@code bz2} module), which zarr-python reads.
         *
         * @param level the block size in units of 100&nbsp;kB, 1 (numcodecs' default) to 9 (bzip2's default)
         * @return this builder
         * @throws IllegalArgumentException if {@code level} is not 1 to 9
         */
        public Builder bz2(int level) {
            if (level < 1 || level > 9) {
                throw new IllegalArgumentException("bz2 level must be 1 to 9, not " + level);
            }
            this.bz2Level = level;
            return this;
        }

        /**
         * Reshapes each chunk before it is stored, with the {@code reshape} codec (zarr-extensions
         * {@code codecs/reshape}): the elements keep their C order, only the shape the codecs after it see
         * changes, so a {@link #sharding} sub-chunk shape is of the reshaped rank. {@code shape} has one entry
         * per reshaped dimension: a positive size; an array of chunk dimensions whose sizes multiply to it,
         * which fits chunks of any shape (a rectilinear grid's); or {@code -1}, at most once, for the size that
         * makes the element counts agree. The chunk dimensions named must strictly increase across
         * {@code shape}, each entry naming a run of them that it spans exactly ({@code [[0, 1], [2]]} merges a
         * chunk's first two dimensions; {@code [[0], 2, -1]} splits its second in two, of size 2 and the rest);
         * {@link #build()} checks every chunk shape against them. zarr-python 3.4 does not read the codec.
         *
         * @param shape the codec's {@code shape} configuration
         * @return this builder
         */
        public Builder reshape(JsonArray shape) {
            this.reshape = shape;
            return this;
        }

        /**
         * Stores the elements converted by value to {@code dataType}, with the {@code cast_value} codec
         * (zarr-extensions {@code codecs/cast_value}; zarr-python 3.4 reads it with the cast-value-rs package),
         * rounded to nearest even, an element outside {@code dataType}'s range failing the write. See
         * {@link #castValue(DataType, String, String, JsonObject)}.
         *
         * @param dataType the stored data type: {@code int8} to {@code uint64}, {@code float16},
         *                 {@code float32}, or {@code float64}
         * @return this builder
         * @throws IllegalArgumentException if {@code dataType} is not an integer or float type
         */
        public Builder castValue(DataType dataType) {
            return castValue(dataType, "nearest-even", null, null);
        }

        /**
         * Stores the elements converted by value to {@code dataType}, with the {@code cast_value} codec
         * (zarr-extensions {@code codecs/cast_value}), placed before the {@code bytes} codec (inside the shard,
         * when {@link #sharding}), where zarr-python places it; the codecs after it, Blosc's type size among
         * them, see {@code dataType}. A write casts each element as cast-value-rs (zarr-python's backend)
         * does, bit for bit, and a read casts it back to the array's data type the same way. An element that
         * cannot be cast (NaN or an infinity to an integer, or a value out of range with no
         * {@code outOfRange}) fails the write or read with {@link ZarrFormatException}. The fill value must
         * survive the cast both ways, which {@link #build()} checks. Both data types must be integer or float
         * types; the array's must not be variable-length.
         *
         * @param dataType   the stored data type: {@code int8} to {@code uint64}, {@code float16},
         *                   {@code float32}, or {@code float64}
         * @param rounding   how a value between two of the target's is rounded: {@code nearest-even},
         *                   {@code towards-zero}, {@code towards-positive}, {@code towards-negative}, or
         *                   {@code nearest-away}
         * @param outOfRange what becomes of a value outside the target's range: {@code null} (it is an error),
         *                   {@code clamp} (the nearest bound; for a float target, the infinity), or {@code wrap}
         *                   (modulo 2<sup>N</sup>; an integer {@code dataType} only)
         * @param scalarMap  the {@code scalar_map}, applied before any other rule, or {@code null}: an object of
         *                   {@code "encode"} (array type to {@code dataType}) and {@code "decode"} (back), each
         *                   an array of {@code [input, output]} pairs in the Zarr v3 fill value encoding of
         *                   their types, such as {@code {"encode": [["NaN", 0]], "decode": [[0, "NaN"]]}}
         * @return this builder
         * @throws IllegalArgumentException if {@code dataType} is not an integer or float type, or
         *                                  {@code rounding} or {@code outOfRange} is none of those
         */
        public Builder castValue(DataType dataType, String rounding, String outOfRange, JsonObject scalarMap) {
            DataTypeKind kind = dataType.kind();
            if (kind != DataTypeKind.INT && kind != DataTypeKind.UINT && kind != DataTypeKind.FLOAT) {
                throw new IllegalArgumentException("cast_value casts to an integer or float data type, not '"
                        + dataType.name() + "'");
            }
            if (!List.of("nearest-even", "towards-zero", "towards-positive", "towards-negative", "nearest-away")
                    .contains(rounding)) {
                throw new IllegalArgumentException("cast_value rounding must be nearest-even, towards-zero,"
                        + " towards-positive, towards-negative, or nearest-away, not " + rounding);
            }
            if (outOfRange != null && !outOfRange.equals("clamp") && !outOfRange.equals("wrap")) {
                throw new IllegalArgumentException("cast_value out_of_range must be null, clamp, or wrap, not "
                        + outOfRange);
            }
            this.castType = dataType;
            this.castRounding = rounding;
            this.castOutOfRange = outOfRange;
            this.castScalarMap = scalarMap;
            return this;
        }

        /**
         * Appends a {@code crc32c} checksum to each stored chunk (or sub-chunk, when sharding).
         *
         * @return this builder
         */
        public Builder crc32c() {
            this.crc32c = true;
            return this;
        }

        /**
         * Stores each chunk as a shard of sub-chunks of the given shape. The codecs chosen on this builder
         * then apply to each sub-chunk, and the shard index is checked with {@code crc32c}.
         *
         * @param subChunkShape the sub-chunk's extent in each dimension; each must divide the chunk shape
         * @return this builder
         */
        public Builder sharding(long... subChunkShape) {
            this.subChunkShape = subChunkShape.clone();
            return this;
        }

        /**
         * Places the shard index at the start of the shard instead of the end. It has an effect only with
         * {@link #sharding}.
         *
         * @return this builder
         */
        public Builder shardIndexAtStart() {
            this.indexAtStart = true;
            return this;
        }

        /**
         * Sets the user attributes (default: none).
         *
         * @param attributes the {@code attributes} object
         * @return this builder
         */
        public Builder attributes(JsonObject attributes) {
            this.attributes = attributes;
            return this;
        }

        /**
         * Sets the dimension names; a {@code null} entry leaves that dimension unnamed.
         *
         * @param dimensionNames one name per array dimension (copied)
         * @return this builder
         */
        public Builder dimensionNames(String... dimensionNames) {
            this.dimensionNames = dimensionNames.clone();
            return this;
        }

        /**
         * Selects the chunk key encoding: {@code "default"} (the default) or {@code "v2"}.
         *
         * @param name the encoding's name
         * @return this builder
         */
        public Builder chunkKeyEncoding(String name) {
            this.encodingName = name;
            return this;
        }

        /**
         * Sets the chunk key separator ({@code "/"} or {@code "."}). Without one, the encoding's own default
         * applies: {@code "/"} for {@code "default"}, {@code "."} for {@code "v2"}.
         *
         * @param separator the separator
         * @return this builder
         */
        public Builder separator(String separator) {
            this.separator = separator;
            return this;
        }

        /**
         * The finished spec, checked as opening the array would check it: the shapes, fill value, dimension
         * names, chunk key encoding, and codecs (including that a chunk fits one buffer).
         *
         * @return the spec
         * @throws IllegalArgumentException if the spec does not describe an array Falcon can create, read,
         *                                  and write
         */
        public ArraySpec build() {
            ArraySpec spec = new ArraySpec(this);
            try {
                NodeMetadata parsed = Metadata.parse(Json.writeBytes(spec.toJson()), "zarr.json");
                ((ArrayMetadata) parsed).checkPipelines();
            } catch (ZarrException e) {
                throw new IllegalArgumentException("invalid array spec: " + e.getMessage(), e);
            }
            return spec;
        }
    }
}
