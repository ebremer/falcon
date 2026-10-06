package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Zarr <b>v2</b> metadata and translates it into the v3 model, so the whole v3 read path (chunk
 * grid, codec pipeline, selections) serves v2 stores unchanged.
 *
 * <p>A v2 array is described by a {@code .zarray} document with a NumPy dtype ({@code "<i4"},
 * {@code ">f8"}, {@code "<U8"}, a structured list), an {@code order}, a list of {@code filters}, a
 * {@code compressor}, a {@code dimension_separator}, and its user attributes in a <em>separate</em>
 * {@code .zattrs} file. The translation, checked against how zarr-python 3.4 reads and writes v2, maps:
 *
 * <ul>
 *   <li>the dtype to a v3 {@link DataType} plus the {@code bytes} codec's endian: the numeric types,
 *       {@code U<n>} to {@code fixed_length_utf32}, {@code S<n>} to {@code null_terminated_bytes},
 *       {@code V<n>} to {@code raw_bytes}, {@code M8[...]}/{@code m8[...]} to {@code numpy.datetime64}/
 *       {@code numpy.timedelta64}, a structured list to {@code struct}, and {@code |O} to {@code string} or
 *       {@code variable_length_bytes}, as its object codec ({@code vlen-utf8} or {@code vlen-bytes}, the
 *       first filter) says, that codec becoming the array&rarr;bytes codec;</li>
 *   <li>the fill value to the v3 form of that type ({@code null}: the type's zero, NaT for times, the empty
 *       string), as zarr-python reads it;</li>
 *   <li>{@code "F"} order to a {@code transpose} codec reversing the axes, ahead of the array&rarr;bytes
 *       codec: a chunk's elements are stored in Fortran order;</li>
 *   <li>each other filter, in order, and then the compressor, to bytes&rarr;bytes codecs after the
 *       array&rarr;bytes codec: {@code gzip}, {@code zstd}, and {@code blosc} to the v3 codecs of those names,
 *       their configurations translated, and every other numcodecs codec Falcon implements to
 *       {@code numcodecs.<id>}, the name zarr-python 3 gives it, configured as numcodecs is (less the
 *       {@code id}): {@code zfpy} among them, though in Zarr v3 metadata it is an array&rarr;bytes codec,
 *       for zarr-python 3 hands a v2 compressor the chunk's elements after its filters, as here;</li>
 *   <li>the chunk grid to a {@code regular} grid, and the {@code dimension_separator} to the {@code v2}
 *       chunk key encoding (no {@code "c"} prefix).</li>
 * </ul>
 *
 * <p>Writing into a translated v2 array goes through the same pipeline, so the chunks written are those
 * zarr-python writes for that metadata (but for {@code zfpy}, which Falcon only reads). Creating v2 arrays is
 * out of scope.
 */
public final class V2Metadata {

    /** The key of a v2 array's metadata document. */
    public static final String ZARRAY = ".zarray";
    /** The key of a v2 group's metadata document. */
    public static final String ZGROUP = ".zgroup";
    /** The key of a v2 node's user attributes. */
    public static final String ZATTRS = ".zattrs";
    /** The key of a v2 hierarchy's consolidated metadata, which zarr-python writes beside the root group's. */
    public static final String ZMETADATA = ".zmetadata";

    /** The numcodecs codecs read as {@code numcodecs.<id>}: the filters, checksums, and four compressors. */
    private static final Set<String> NUMCODECS = Set.of(
            "delta", "fixedscaleoffset", "quantize", "bitround", "astype", "packbits", "shuffle",
            "crc32", "crc32c", "adler32", "fletcher32", "jenkins_lookup3", "zlib", "lz4", "bz2", "zfpy");
    /** The numcodecs codecs that turn objects into bytes; a {@code |O} array's first filter is one. */
    private static final Set<String> OBJECT_CODECS = Set.of(
            "vlen-utf8", "vlen-bytes", "vlen-array", "json2", "msgpack2", "pickle", "json", "msgpack");
    /** A time dtype's unit, with its optional multiplier: {@code [ns]}, {@code [10s]}. */
    private static final Pattern TIME_UNIT = Pattern.compile("\\[(\\d*)([^\\]\\d]+)]");

    private V2Metadata() {
    }

    /**
     * Translates a v2 group ({@code .zgroup} + optional {@code .zattrs}) into v3 group metadata.
     *
     * @param zgroup the {@code .zgroup} bytes
     * @param zattrs the {@code .zattrs} bytes, or {@code null} if none is stored
     * @param key    the store key of the {@code .zgroup}, for diagnostics
     * @return the group metadata
     */
    public static GroupMetadata parseGroup(byte[] zgroup, byte[] zattrs, String key) {
        return Metadata.wrapJson(key, () -> parseGroup(object(zgroup, key),
                zattrs == null ? null : object(zattrs, key + " (.zattrs)"), key));
    }

    /**
     * Translates already-parsed v2 group documents, such as entries of consolidated metadata.
     *
     * @param zgroup the {@code .zgroup} document
     * @param zattrs the {@code .zattrs} document, or {@code null} if no attributes are stored
     * @param key    where the documents came from, for diagnostics
     * @return the group metadata
     */
    public static GroupMetadata parseGroup(JsonValue zgroup, JsonValue zattrs, String key) {
        return Metadata.wrapJson(key, () -> {
            checkFormat(Fields.object(zgroup, key), key);
            JsonObject attributes = zattrs == null ? Fields.EMPTY_OBJECT : Fields.object(zattrs, key + " (.zattrs)");
            return GroupMetadata.parse(JsonObject.builder()
                    .put("zarr_format", 3).put("node_type", "group").put("attributes", attributes)
                    .build(), key);
        });
    }

    /**
     * Translates a v2 array ({@code .zarray} + optional {@code .zattrs}) into v3 array metadata.
     *
     * @param zarray the {@code .zarray} bytes
     * @param zattrs the {@code .zattrs} bytes, or {@code null} if none is stored
     * @param key    the store key of the {@code .zarray}, for diagnostics
     * @return the array metadata
     * @throws ZarrFormatException      if the metadata is malformed
     * @throws ZarrUnsupportedException if it uses a dtype, filter, or compressor Falcon does not read
     */
    public static ArrayMetadata parseArray(byte[] zarray, byte[] zattrs, String key) {
        return Metadata.wrapJson(key, () -> parseArray(object(zarray, key),
                zattrs == null ? null : object(zattrs, key + " (.zattrs)"), key));
    }

    /**
     * Translates already-parsed v2 array documents, such as entries of consolidated metadata.
     *
     * @param zarray the {@code .zarray} document
     * @param zattrs the {@code .zattrs} document, or {@code null} if no attributes are stored
     * @param key    where the documents came from, for diagnostics
     * @return the array metadata
     * @throws ZarrFormatException      if the metadata is malformed
     * @throws ZarrUnsupportedException if it uses a dtype, filter, or compressor Falcon does not read
     */
    public static ArrayMetadata parseArray(JsonValue zarray, JsonValue zattrs, String key) {
        return Metadata.wrapJson(key, () -> ArrayMetadata.parse(translate(Fields.object(zarray, key),
                zattrs == null ? null : Fields.object(zattrs, key + " (.zattrs)"), key), key));
    }

    /**
     * The v3 array document a v2 array's {@code .zarray} and {@code .zattrs} ({@code null} if none) stand
     * for. {@code key} names the source for diagnostics.
     */
    static JsonObject translate(JsonObject meta, JsonObject zattrs, String key) {
        checkFormat(meta, key);

        JsonArray shape = Fields.array(Fields.require(meta, "shape", key), key + ".shape");
        JsonArray chunks = Fields.array(Fields.require(meta, "chunks", key), key + ".chunks");
        int rank = shape.size();
        if (chunks.size() != rank) {
            throw new ZarrFormatException(key + ": chunks rank does not match shape rank");
        }

        String order = meta.find("order").map(v -> Fields.string(v, key + ".order")).orElse("C");
        if (!order.equals("C") && !order.equals("F")) {
            throw new ZarrFormatException(key + ".order must be \"C\" or \"F\", was \"" + order + "\"");
        }

        List<JsonObject> filters = filters(meta, key);
        DType dtype = parseDtype(Fields.require(meta, "dtype", key), key);

        List<JsonValue> codecs = new ArrayList<>();
        if (order.equals("F") && rank > 1) {
            // A chunk's elements are stored in Fortran order: C order of the chunk with its axes reversed.
            List<JsonValue> axes = new ArrayList<>(rank);
            for (int i = rank - 1; i >= 0; i--) {
                axes.add(JsonNumber.of(i));
            }
            codecs.add(named("transpose", JsonObject.builder().put("order", new JsonArray(axes)).build()));
        }

        DataType type = dtype.type;
        int first = 0; // the first filter that becomes a bytes->bytes codec
        int itemSize;  // the element size of the buffer the next codec is handed, as numcodecs sees it (-1: unknown)
        if (type == null) { // |O: the first filter says what the objects are
            String id = filters.isEmpty() ? null : id(filters.get(0), key + ".filters[0]");
            type = objectType(id, key);
            codecs.add(named(id, null));
            first = 1;
            itemSize = 1;
        } else {
            codecs.add(bytesCodec(dtype));
            itemSize = type.byteCount();
        }

        for (int i = first; i < filters.size(); i++) {
            String ctx = key + ".filters[" + i + "]";
            JsonObject filter = filters.get(i);
            String id = id(filter, ctx);
            if (OBJECT_CODECS.contains(id)) {
                throw new ZarrFormatException(ctx + ": the object codec '" + id
                        + "' belongs only first in the filters of a \"|O\" array");
            }
            if (!isSupported(id)) {
                throw new ZarrUnsupportedException(key + ": the v2 filter '" + id + "' is not supported");
            }
            codecs.add(codec(filter, id, itemSize, ctx));
            itemSize = encodedItemSize(filter, id, itemSize);
        }

        JsonValue compressor = meta.find("compressor").orElse(JsonNull.INSTANCE);
        if (!(compressor instanceof JsonNull)) {
            String ctx = key + ".compressor";
            JsonObject c = Fields.object(compressor, ctx);
            String id = id(c, ctx);
            if (OBJECT_CODECS.contains(id) || !isSupported(id)) {
                throw new ZarrUnsupportedException(key + ": the v2 compressor '" + id + "' is not supported");
            }
            codecs.add(codec(c, id, itemSize, ctx));
        }

        // null, as zarr-python 2 writes when none was chosen, means the default "."
        String separator = meta.find("dimension_separator").filter(v -> !v.isNull())
                .map(v -> Fields.string(v, key + ".dimension_separator")).orElse(".");

        JsonValue fillValue = translateFill(meta.find("fill_value").orElse(JsonNull.INSTANCE), type, dtype, key);
        JsonObject attributes = zattrs == null ? Fields.EMPTY_OBJECT : zattrs;

        return JsonObject.builder()
                .put("zarr_format", 3)
                .put("node_type", "array")
                .put("shape", shape)
                .put("data_type", type.toJson())
                .put("chunk_grid", JsonObject.builder().put("name", "regular")
                        .put("configuration", JsonObject.builder().put("chunk_shape", chunks).build()).build())
                .put("chunk_key_encoding", JsonObject.builder().put("name", "v2")
                        .put("configuration", JsonObject.builder().put("separator", separator).build()).build())
                .put("fill_value", fillValue)
                .put("codecs", new JsonArray(codecs))
                .put("attributes", attributes)
                .build();
    }

    private static List<JsonObject> filters(JsonObject meta, String key) {
        JsonValue v = meta.find("filters").orElse(JsonNull.INSTANCE);
        if (v instanceof JsonNull) {
            return List.of();
        }
        JsonArray array = Fields.array(v, key + ".filters");
        List<JsonObject> filters = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            filters.add(Fields.object(array.get(i), key + ".filters[" + i + "]"));
        }
        return filters;
    }

    private static String id(JsonObject codec, String ctx) {
        return Fields.string(Fields.require(codec, "id", ctx), ctx + ".id");
    }

    private static boolean isSupported(String id) {
        return switch (id) {
            case "gzip", "zstd", "blosc" -> true;
            default -> NUMCODECS.contains(id);
        };
    }

    // ---- dtypes ----------------------------------------------------------------------------------------

    /**
     * A v2 dtype: its data type ({@code null} for {@code |O}, whose object codec says what it is) and the
     * byte order of its multi-byte numbers ({@code null} if the dtype gives none).
     */
    private record DType(DataType type, ByteOrder order) {
    }

    private static DType parseDtype(JsonValue dtype, String key) {
        if (dtype instanceof JsonArray fields) {
            return parseStructured(fields, key, key + ".dtype");
        }
        return parseDtype(Fields.string(dtype, key + ".dtype"), key);
    }

    private static DType parseDtype(String dtype, String key) {
        if (dtype.length() < 2) {
            throw new ZarrFormatException(key + ": unrecognized v2 dtype \"" + dtype + "\"");
        }
        ByteOrder order = switch (dtype.charAt(0)) {
            case '<' -> ByteOrder.LITTLE_ENDIAN;
            case '>' -> ByteOrder.BIG_ENDIAN;
            case '|', '=' -> null; // not applicable (single byte) or native
            default -> throw new ZarrFormatException(key + ": unknown byte order in dtype \"" + dtype + "\"");
        };
        String rest = dtype.substring(1);
        DataType type = switch (rest) {
            case "b1" -> DataType.BOOL;
            case "i1" -> DataType.INT8;
            case "i2" -> DataType.INT16;
            case "i4" -> DataType.INT32;
            case "i8" -> DataType.INT64;
            case "u1" -> DataType.UINT8;
            case "u2" -> DataType.UINT16;
            case "u4" -> DataType.UINT32;
            case "u8" -> DataType.UINT64;
            case "f2" -> DataType.FLOAT16;
            case "f4" -> DataType.FLOAT32;
            case "f8" -> DataType.FLOAT64;
            case "c8" -> DataType.COMPLEX64;
            case "c16" -> DataType.COMPLEX128;
            case "O" -> null;
            default -> flexibleType(rest, dtype, key);
        };
        return new DType(type, order);
    }

    /** The dtypes with a length or a unit: {@code U<n>}, {@code S<n>}, {@code V<n>}, {@code M8[..]}, {@code m8[..]}. */
    private static DataType flexibleType(String rest, String dtype, String key) {
        char kind = rest.charAt(0);
        try {
            if ((kind == 'M' || kind == 'm') && rest.startsWith("8", 1)) {
                String unit = "generic"; // a bare M8 or m8 has numpy's generic unit
                int scale = 1;
                if (rest.length() > 2) {
                    Matcher m = TIME_UNIT.matcher(rest.substring(2));
                    if (!m.matches()) {
                        throw new ZarrFormatException(key + ": malformed time unit in dtype \"" + dtype + "\"");
                    }
                    unit = m.group(2);
                    scale = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
                }
                return kind == 'M' ? DataType.datetime64(unit, scale) : DataType.timedelta64(unit, scale);
            }
            if ((kind == 'U' || kind == 'S' || kind == 'V') && rest.length() > 1
                    && rest.substring(1).chars().allMatch(c -> c >= '0' && c <= '9')) {
                int length = Integer.parseInt(rest.substring(1));
                if (length == 0) {
                    throw new ZarrUnsupportedException(key + ": the zero-length dtype \"" + dtype
                            + "\" is not supported");
                }
                return switch (kind) {
                    case 'U' -> DataType.fixedLengthUtf32(length);
                    case 'S' -> DataType.nullTerminatedBytes(length);
                    default -> DataType.rawBytes(length);
                };
            }
        } catch (IllegalArgumentException e) { // NumberFormatException included
            throw new ZarrFormatException(key + ": invalid v2 dtype \"" + dtype + "\": " + e.getMessage(), e);
        }
        throw new ZarrUnsupportedException(key + ": unsupported v2 dtype \"" + dtype + "\"");
    }

    /**
     * A structured dtype: a list of {@code [name, dtype]} fields, a field's dtype a string or a structured
     * list itself. Its multi-byte fields must share one byte order, which becomes the {@code bytes} codec's.
     */
    private static DType parseStructured(JsonArray list, String key, String ctx) {
        if (list.size() == 0) {
            throw new ZarrFormatException(ctx + ": a structured dtype needs at least one field");
        }
        List<DataType.Field> fields = new ArrayList<>(list.size());
        ByteOrder order = null;
        for (int i = 0; i < list.size(); i++) {
            String what = ctx + "[" + i + "]";
            JsonArray field = Fields.array(list.get(i), what);
            if (field.size() == 3) {
                throw new ZarrUnsupportedException(key + ": structured dtype field " + i
                        + " has a shape (a subarray field), which is not supported");
            }
            if (field.size() != 2) {
                throw new ZarrFormatException(what + ": a structured dtype field must be [name, dtype]");
            }
            String name = Fields.string(field.get(0), what + "[0]");
            DType sub = field.get(1) instanceof JsonArray nested
                    ? parseStructured(nested, key, what + "[1]")
                    : parseDtype(Fields.string(field.get(1), what + "[1]"), key);
            if (sub.type == null) {
                throw new ZarrUnsupportedException(key + ": structured dtype field '" + name
                        + "' is an object (\"|O\"), which is not supported");
            }
            if (sub.order != null && sub.type.hasByteOrder()) {
                if (order != null && order != sub.order) {
                    throw new ZarrUnsupportedException(key + ": the structured dtype mixes little- and big-endian "
                            + "fields, which is not supported (a bytes codec has one byte order)");
                }
                order = sub.order;
            }
            fields.add(new DataType.Field(name, sub.type));
        }
        try {
            return new DType(DataType.struct(fields), order);
        } catch (IllegalArgumentException e) {
            throw new ZarrFormatException(ctx + ": invalid structured dtype: " + e.getMessage(), e);
        }
    }

    /** The data type of a {@code |O} array whose first filter is {@code id} ({@code null}: none). */
    private static DataType objectType(String id, String key) {
        if (id == null) {
            throw new ZarrFormatException(key + ": a \"|O\" array needs an object codec as its first filter");
        }
        return switch (id) {
            case "vlen-utf8" -> DataType.STRING;
            case "vlen-bytes" -> DataType.BYTES;
            default -> throw new ZarrUnsupportedException(OBJECT_CODECS.contains(id)
                    ? key + ": the v2 object codec '" + id + "' is not supported (Falcon reads vlen-utf8 and vlen-bytes)"
                    : key + ": a \"|O\" array's first filter is '" + id
                            + "', not an object codec Falcon reads (vlen-utf8 or vlen-bytes)");
        };
    }

    private static JsonValue bytesCodec(DType dtype) {
        if (dtype.order == null || !dtype.type.hasByteOrder()) {
            return named("bytes", null); // no multi-byte numbers, or no order given: little-endian
        }
        return named("bytes", JsonObject.builder()
                .put("endian", dtype.order == ByteOrder.LITTLE_ENDIAN ? "little" : "big").build());
    }

    // ---- filters and the compressor --------------------------------------------------------------------

    /**
     * The v3 codec for the numcodecs codec {@code c} (a filter or the compressor), whose input numcodecs
     * sees as elements of {@code itemSize} bytes ({@code -1} if not known). A missing setting takes
     * numcodecs' default.
     */
    private static JsonValue codec(JsonObject c, String id, int itemSize, String ctx) {
        return switch (id) {
            case "gzip" -> named("gzip", JsonObject.builder().put("level", integer(c, "level", 1, ctx)).build());
            case "zstd" -> named("zstd", JsonObject.builder()
                    .put("level", integer(c, "level", 0, ctx)) // 0: libzstd's default level
                    .put("checksum", bool(c, "checksum", false, ctx))
                    .build());
            case "blosc" -> blosc(c, itemSize, ctx);
            default -> {
                JsonObject.Builder config = JsonObject.builder();
                for (Map.Entry<String, JsonValue> e : c.members().entrySet()) {
                    if (!e.getKey().equals("id")) {
                        config.put(e.getKey(), e.getValue());
                    }
                }
                yield named("numcodecs." + id, config.build());
            }
        };
    }

    /**
     * numcodecs' {@code blosc}: c-blosc is handed the buffer's element size as its type size, and the
     * automatic shuffle ({@code -1}) is the bit shuffle for single bytes and the byte shuffle otherwise.
     */
    private static JsonValue blosc(JsonObject c, int itemSize, String ctx) {
        long typeSize = integer(c, "typesize", itemSize, ctx);
        if (typeSize < 1) {
            throw new ZarrFormatException(ctx + ": the element size blosc compresses is not known: a filter's "
                    + "dtype is not a NumPy dtype Falcon reads");
        }
        long shuffle = integer(c, "shuffle", 1, ctx);
        String shuffleName;
        if (shuffle == 0) {
            shuffleName = "noshuffle";
        } else if (shuffle == 1) {
            shuffleName = "shuffle";
        } else if (shuffle == 2) {
            shuffleName = "bitshuffle";
        } else if (shuffle == -1) {
            shuffleName = typeSize == 1 ? "bitshuffle" : "shuffle"; // AUTOSHUFFLE
        } else {
            throw new ZarrFormatException(ctx + ".shuffle must be -1, 0, 1, or 2, was " + shuffle);
        }
        String cname = c.find("cname").filter(v -> !v.isNull())
                .map(v -> Fields.string(v, ctx + ".cname")).orElse("lz4");
        return named("blosc", JsonObject.builder()
                .put("cname", cname)
                .put("clevel", integer(c, "clevel", 5, ctx))
                .put("shuffle", shuffleName)
                .put("typesize", typeSize)
                .put("blocksize", integer(c, "blocksize", 0, ctx))
                .build());
    }

    /**
     * The element size of what the numcodecs codec {@code c} hands on, when it is handed elements of
     * {@code itemSize} bytes: a filter with an {@code astype} (or {@code encode_dtype}) makes elements of that
     * dtype, {@code bitround} keeps the size, and every other codec makes bytes. {@code -1} if not known.
     */
    private static int encodedItemSize(JsonObject c, String id, int itemSize) {
        return switch (id) {
            case "delta", "fixedscaleoffset", "quantize" ->
                    numpyItemSize(c.find("astype").filter(v -> !v.isNull()).orElse(c.find("dtype").orElse(null)));
            case "astype" -> numpyItemSize(c.find("encode_dtype").orElse(null));
            case "bitround" -> itemSize;
            default -> 1;
        };
    }

    /** The element size of a NumPy dtype string ({@code "<i2"}: 2, {@code "<U3"}: 12), or {@code -1}. */
    private static int numpyItemSize(JsonValue dtype) {
        if (!(dtype instanceof JsonString s)) {
            return -1;
        }
        String text = s.value();
        if (!text.isEmpty() && "<>|=".indexOf(text.charAt(0)) >= 0) {
            text = text.substring(1);
        }
        if (text.length() < 2) {
            return -1;
        }
        char kind = text.charAt(0);
        if ((kind == 'M' || kind == 'm') && text.charAt(1) == '8') {
            return 8;
        }
        String digits = text.substring(1);
        if (digits.length() > 9 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        long size = Long.parseLong(digits) * (kind == 'U' ? 4 : 1);
        return size >= 1 && size <= Integer.MAX_VALUE ? (int) size : -1;
    }

    private static long integer(JsonObject c, String member, long fallback, String ctx) {
        return c.find(member).filter(v -> !v.isNull())
                .map(v -> Fields.integer(v, ctx + "." + member)).orElse(fallback);
    }

    private static boolean bool(JsonObject c, String member, boolean fallback, String ctx) {
        JsonValue v = c.find(member).filter(x -> !x.isNull()).orElse(null);
        if (v == null) {
            return fallback;
        }
        if (v instanceof JsonBool b) {
            return b.value();
        }
        throw new ZarrFormatException(ctx + "." + member + " must be true or false, was " + v.typeName());
    }

    private static JsonValue named(String name, JsonObject configuration) {
        JsonObject.Builder b = JsonObject.builder().put("name", name);
        if (configuration != null) {
            b.put("configuration", configuration);
        }
        return b.build();
    }

    // ---- fill values -----------------------------------------------------------------------------------

    /**
     * Maps a v2 fill value onto the v3 form of {@code type}, reading what zarr-python 3 reads. A v2
     * {@code null} means no fill value was set, and unwritten chunks read as the type's default: zeros
     * ({@code false} for bool, {@code [0.0, 0.0]} for a complex type, an all-zero struct), NaT for a time,
     * and empty text or bytes. A bool accepts 0/1. A structured fill is base64 of the element as stored, in
     * the dtype's byte order. A {@code vlen-utf8} array's fill may be a number or bool, as zarr-python 2
     * wrote, and reads as its text (zarr-python 3 reads {@code 0} as {@code "0"}); a {@code vlen-bytes}
     * array's {@code 0}, zarr-python 2's default, reads as no bytes (zarr-python 3 refuses it).
     */
    private static JsonValue translateFill(JsonValue fill, DataType type, DType dtype, String key) {
        DataTypeKind kind = type.kind();
        if (fill instanceof JsonNull) {
            return switch (kind) {
                case BOOL -> JsonBool.FALSE;
                case COMPLEX -> JsonArray.of(JsonNumber.of(0.0), JsonNumber.of(0.0));
                case INT, UINT, FLOAT -> JsonNumber.of(0);
                case STRUCT -> type.encodeFillValue(new byte[type.byteCount()], ByteOrder.LITTLE_ENDIAN);
                default -> type.defaultFillValue(); // NaT for a time, empty text or bytes, zero raw bytes
            };
        }
        return switch (kind) {
            case BOOL -> fill instanceof JsonNumber n ? JsonBool.of(n.doubleValue() != 0) : fill;
            case STRING -> switch (fill) {
                case JsonNumber n -> new JsonString(n.literal());
                case JsonBool b -> new JsonString(b.value() ? "True" : "False"); // Python's str()
                default -> fill;
            };
            case BYTES -> fill instanceof JsonNumber n && n.literal().equals("0") ? new JsonString("") : fill;
            case STRUCT -> {
                if (!(fill instanceof JsonString s)) {
                    yield fill; // the v3 object form, which zarr-python 3 accepts too
                }
                byte[] element;
                try {
                    element = Base64.getDecoder().decode(s.value());
                } catch (IllegalArgumentException e) {
                    throw new ZarrFormatException(key + ".fill_value is not base64: " + e.getMessage(), e);
                }
                if (element.length != type.byteCount()) {
                    throw new ZarrFormatException(key + ".fill_value holds " + element.length
                            + " bytes, but a structured element is " + type.byteCount());
                }
                yield type.encodeFillValue(element, dtype.order == null ? ByteOrder.LITTLE_ENDIAN : dtype.order);
            }
            default -> fill;
        };
    }

    private static void checkFormat(JsonObject meta, String key) {
        long format = Fields.integer(Fields.require(meta, "zarr_format", key), key + ".zarr_format");
        if (format != 2) {
            throw new ZarrFormatException(key + ": expected zarr_format 2, was " + format);
        }
    }

    private static JsonObject object(byte[] json, String key) {
        try {
            return Fields.object(Json.parse(json), key);
        } catch (JsonException e) {
            throw new ZarrFormatException("malformed JSON in '" + key + "': " + e.getMessage(), e);
        }
    }
}
