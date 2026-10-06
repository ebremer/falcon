package com.ebremer.falcon.zarr.datatype;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.data.Elements;
import com.ebremer.falcon.zarr.data.Float16;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A Zarr v3 data type: its name, {@linkplain DataTypeKind kind}, and fixed element size in bytes, plus the
 * configuration of an extension data type (a time unit, a length, a struct's fields).
 *
 * <p>The core types are the constants here ({@link #FLOAT64}, {@link #INT32}, ...) and the raw types
 * {@code r<N>} ({@link #of(String)}). The extension types zarr-python writes are made by
 * {@link #datetime64}, {@link #timedelta64}, {@link #fixedLengthUtf32}, {@link #nullTerminatedBytes},
 * {@link #rawBytes}, and {@link #struct}. {@link #fromJson} reads any of them from a {@code zarr.json}
 * {@code data_type}, and {@link #toJson()} writes it back.
 *
 * <p>Byte order is <em>not</em> part of a data type in Zarr v3 &mdash; it is carried by the {@code bytes}
 * codec &mdash; so the endianness-dependent operations here ({@link #decodeFillValue} /
 * {@link #encodeFillValue}) take a {@link ByteOrder} argument.
 *
 * <p>A fill value is stored in {@code zarr.json} as JSON; this class converts it to and from the element's
 * on-disk bytes, as zarr-python 3.4 does:
 * <ul>
 *   <li><b>bool</b> &mdash; JSON {@code true}/{@code false} &harr; one byte;</li>
 *   <li><b>integers</b> &mdash; a JSON integer, range-checked against the type ({@code uint64} uses
 *       {@link BigInteger});</li>
 *   <li><b>floats</b> &mdash; a JSON number, one of {@code "NaN"}/{@code "Infinity"}/{@code "-Infinity"},
 *       or a {@code "0x…"} hex string giving the exact big-endian bit pattern;</li>
 *   <li><b>complex</b> &mdash; a two-element JSON array {@code [real, imag]} of the component float
 *       encoding;</li>
 *   <li><b>raw</b> &mdash; a JSON array of byte values, or a {@code "0x…"} hex string (no byte order);</li>
 *   <li><b>numpy.datetime64</b>, <b>numpy.timedelta64</b> &mdash; a JSON integer, or {@code "NaT"}; NaT is
 *       written as the integer {@code -9223372036854775808}, as zarr-python writes it;</li>
 *   <li><b>fixed_length_utf32</b> &mdash; a JSON string, cut to the type's length as numpy cuts it;</li>
 *   <li><b>null_terminated_bytes</b>, <b>raw_bytes</b> &mdash; base64 text, padded with zero bytes or cut
 *       to the type's length;</li>
 *   <li><b>struct</b> &mdash; a JSON object holding each field's fill value in its own type's form (a field
 *       left out takes its type's {@linkplain #defaultFillValue() default}), or base64 text of the whole
 *       element, packed little-endian.</li>
 * </ul>
 */
public final class DataType {

    /** {@code bool}. */
    public static final DataType BOOL = new DataType("bool", DataTypeKind.BOOL, 1);
    /** {@code int8}. */
    public static final DataType INT8 = new DataType("int8", DataTypeKind.INT, 1);
    /** {@code int16}. */
    public static final DataType INT16 = new DataType("int16", DataTypeKind.INT, 2);
    /** {@code int32}. */
    public static final DataType INT32 = new DataType("int32", DataTypeKind.INT, 4);
    /** {@code int64}. */
    public static final DataType INT64 = new DataType("int64", DataTypeKind.INT, 8);
    /** {@code uint8}. */
    public static final DataType UINT8 = new DataType("uint8", DataTypeKind.UINT, 1);
    /** {@code uint16}. */
    public static final DataType UINT16 = new DataType("uint16", DataTypeKind.UINT, 2);
    /** {@code uint32}. */
    public static final DataType UINT32 = new DataType("uint32", DataTypeKind.UINT, 4);
    /** {@code uint64}. */
    public static final DataType UINT64 = new DataType("uint64", DataTypeKind.UINT, 8);
    /** {@code float16}. */
    public static final DataType FLOAT16 = new DataType("float16", DataTypeKind.FLOAT, 2);
    /** {@code float32}. */
    public static final DataType FLOAT32 = new DataType("float32", DataTypeKind.FLOAT, 4);
    /** {@code float64}. */
    public static final DataType FLOAT64 = new DataType("float64", DataTypeKind.FLOAT, 8);
    /** {@code complex64} (two {@code float32}). */
    public static final DataType COMPLEX64 = new DataType("complex64", DataTypeKind.COMPLEX, 8);
    /** {@code complex128} (two {@code float64}). */
    public static final DataType COMPLEX128 = new DataType("complex128", DataTypeKind.COMPLEX, 16);
    /** {@code string}: variable-length UTF-8 (elements have no fixed byte size). */
    public static final DataType STRING = new DataType("string", DataTypeKind.STRING, -1);
    /**
     * {@code variable_length_bytes}: variable-length byte strings, as zarr-python names them (it reads the
     * shorter {@code "bytes"} too, and so does {@link #of}). Elements have no fixed byte size, and the fill
     * value is base64 text.
     */
    public static final DataType BYTES = new DataType("variable_length_bytes", DataTypeKind.BYTES, -1);

    private static final Map<String, DataType> BUILTINS = Map.ofEntries(
            Map.entry(BOOL.name, BOOL),
            Map.entry(INT8.name, INT8), Map.entry(INT16.name, INT16),
            Map.entry(INT32.name, INT32), Map.entry(INT64.name, INT64),
            Map.entry(UINT8.name, UINT8), Map.entry(UINT16.name, UINT16),
            Map.entry(UINT32.name, UINT32), Map.entry(UINT64.name, UINT64),
            Map.entry(FLOAT16.name, FLOAT16), Map.entry(FLOAT32.name, FLOAT32),
            Map.entry(FLOAT64.name, FLOAT64),
            Map.entry(COMPLEX64.name, COMPLEX64), Map.entry(COMPLEX128.name, COMPLEX128),
            Map.entry(STRING.name, STRING),
            Map.entry(BYTES.name, BYTES), Map.entry("bytes", BYTES));

    private static final String DATETIME64 = "numpy.datetime64";
    private static final String TIMEDELTA64 = "numpy.timedelta64";
    private static final String FIXED_LENGTH_UTF32 = "fixed_length_utf32";
    private static final String NULL_TERMINATED_BYTES = "null_terminated_bytes";
    private static final String RAW_BYTES = "raw_bytes";
    private static final String STRUCT = "struct";

    /** numpy's time units, as zarr-python accepts them ({@code "μs"} is numpy's other spelling of us). */
    private static final Set<String> TIME_UNITS = Set.of(
            "Y", "M", "W", "D", "h", "m", "s", "ms", "us", "μs", "ns", "ps", "fs", "as", "generic");

    /**
     * A field of a {@link #struct} data type.
     *
     * @param name the field's name: not empty, and unique within its struct
     * @param type the field's data type, which must have a fixed size (not {@code string} or
     *             {@code variable_length_bytes})
     */
    public record Field(String name, DataType type) {
    }

    private final String name;
    private final DataTypeKind kind;
    private final int byteCount;
    private final String unit;        // a time type's unit, else null
    private final int scaleFactor;    // a time type's scale factor, else 0
    private final List<Field> fields; // a struct's fields, else empty
    private final int[] offsets;      // a struct's field offsets, else empty

    private DataType(String name, DataTypeKind kind, int byteCount) {
        this(name, kind, byteCount, null, 0, List.of(), new int[0]);
    }

    private DataType(String name, DataTypeKind kind, int byteCount, String unit, int scaleFactor,
                     List<Field> fields, int[] offsets) {
        this.name = name;
        this.kind = kind;
        this.byteCount = byteCount;
        this.unit = unit;
        this.scaleFactor = scaleFactor;
        this.fields = fields;
        this.offsets = offsets;
    }

    /**
     * Resolves a data-type name: a core type, {@code r<N>}, or zarr-python's {@code "bytes"} for
     * {@code variable_length_bytes}. An extension type with a configuration is resolved by
     * {@link #fromJson} instead.
     *
     * @param name the data-type name as written in {@code zarr.json}
     * @return the data type
     * @throws ZarrFormatException      if {@code name} is a raw type {@code r<N>} with {@code N} not a
     *                                  positive multiple of 8, or an extension type that needs a configuration
     * @throws ZarrUnsupportedException if {@code name} is not a recognized data type
     */
    public static DataType of(String name) {
        DataType builtin = BUILTINS.get(name);
        if (builtin != null) {
            return builtin;
        }
        if (name.length() > 1 && name.charAt(0) == 'r') {
            int bits;
            try {
                bits = Integer.parseInt(name.substring(1));
            } catch (NumberFormatException e) {
                throw new ZarrUnsupportedException("unknown data type: '" + name + "'");
            }
            if (bits <= 0 || bits % 8 != 0) {
                throw new ZarrFormatException(
                        "invalid raw data type '" + name + "': bit count must be a positive multiple of 8");
            }
            return new DataType(name, DataTypeKind.RAW, bits / 8);
        }
        if (isExtensionName(name)) {
            throw new ZarrFormatException("the '" + name + "' data type needs a configuration");
        }
        throw new ZarrUnsupportedException("unknown data type: '" + name + "'");
    }

    private static boolean isExtensionName(String name) {
        return switch (name) {
            case DATETIME64, TIMEDELTA64, FIXED_LENGTH_UTF32, NULL_TERMINATED_BYTES, RAW_BYTES, STRUCT,
                 "structured" -> true;
            default -> false;
        };
    }

    // ---- the extension types ------------------------------------------------------------------------

    /**
     * The {@code numpy.datetime64} data type: instants as a signed 64-bit count of {@code scaleFactor}
     * {@code unit}s since 1970-01-01T00:00:00, {@link Long#MIN_VALUE} meaning NaT. numpy's
     * {@code datetime64[10ms]} is {@code datetime64("ms", 10)}.
     *
     * @param unit        the time unit: {@code "Y"}, {@code "M"}, {@code "W"}, {@code "D"}, {@code "h"},
     *                    {@code "m"}, {@code "s"}, {@code "ms"}, {@code "us"} (or {@code "μs"}),
     *                    {@code "ns"}, {@code "ps"}, {@code "fs"}, {@code "as"}, or {@code "generic"}
     * @param scaleFactor how many units one count is, from 1 to 2<sup>31</sup>&nbsp;&minus;&nbsp;1
     * @return the data type
     * @throws IllegalArgumentException if the unit is not one of these, or the scale factor is out of range
     */
    public static DataType datetime64(String unit, int scaleFactor) {
        return time(DATETIME64, DataTypeKind.DATETIME, unit, scaleFactor);
    }

    /**
     * The {@code numpy.timedelta64} data type: durations as a signed 64-bit count of {@code scaleFactor}
     * {@code unit}s, {@link Long#MIN_VALUE} meaning NaT.
     *
     * @param unit        the time unit, as for {@link #datetime64}
     * @param scaleFactor how many units one count is, from 1 to 2<sup>31</sup>&nbsp;&minus;&nbsp;1
     * @return the data type
     * @throws IllegalArgumentException if the unit is not a numpy time unit, or the scale factor is out of
     *                                  range
     */
    public static DataType timedelta64(String unit, int scaleFactor) {
        return time(TIMEDELTA64, DataTypeKind.TIMEDELTA, unit, scaleFactor);
    }

    private static DataType time(String name, DataTypeKind kind, String unit, int scaleFactor) {
        if (!TIME_UNITS.contains(unit)) {
            throw new IllegalArgumentException("'" + unit + "' is not a numpy time unit; expected one of "
                    + "Y, M, W, D, h, m, s, ms, us, μs, ns, ps, fs, as, generic");
        }
        if (scaleFactor < 1) {
            throw new IllegalArgumentException("a time scale factor must be at least 1, was " + scaleFactor);
        }
        return new DataType(name, kind, 8, unit, scaleFactor, List.of(), new int[0]);
    }

    /**
     * The {@code fixed_length_utf32} data type (numpy's {@code U<length>}): text of up to {@code length}
     * code points, each stored as a 4-byte UTF-32 code unit, padded with NULs.
     *
     * @param length the most code points an element holds, at least 1
     * @return the data type, of {@code 4 × length} bytes
     * @throws IllegalArgumentException if {@code length} is not positive, or the element would exceed 2 GB
     */
    public static DataType fixedLengthUtf32(int length) {
        if (length < 1 || length > Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("a fixed_length_utf32 length must be 1 to "
                    + Integer.MAX_VALUE / 4 + " code points, was " + length);
        }
        return new DataType(FIXED_LENGTH_UTF32, DataTypeKind.FIXED_STRING, 4 * length);
    }

    /**
     * The {@code null_terminated_bytes} data type (numpy's {@code S<length>}): byte strings of up to
     * {@code length} bytes, padded with NULs. A string's trailing NULs are not part of it, as in numpy.
     *
     * @param length the element size in bytes, at least 1
     * @return the data type
     * @throws IllegalArgumentException if {@code length} is not positive
     */
    public static DataType nullTerminatedBytes(int length) {
        return new DataType(NULL_TERMINATED_BYTES, DataTypeKind.FIXED_BYTES, positiveLength(length));
    }

    /**
     * The {@code raw_bytes} data type (numpy's {@code V<length>}): opaque elements of exactly {@code length}
     * bytes.
     *
     * @param length the element size in bytes, at least 1
     * @return the data type
     * @throws IllegalArgumentException if {@code length} is not positive
     */
    public static DataType rawBytes(int length) {
        return new DataType(RAW_BYTES, DataTypeKind.RAW_BYTES, positiveLength(length));
    }

    private static int positiveLength(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("a length in bytes must be at least 1, was " + length);
        }
        return length;
    }

    /**
     * The {@code struct} data type (numpy's structured dtype): a record of named fields, each of a
     * fixed-size data type (a struct too), packed in order without padding.
     *
     * @param fields the fields, in storage order; at least one
     * @return the data type, whose size is the sum of the fields' sizes
     * @throws IllegalArgumentException if there are no fields, a name is empty or repeated, a field's type is
     *                                  variable-length, or the element would exceed 2 GB
     */
    public static DataType struct(Field... fields) {
        return struct(List.of(fields));
    }

    /**
     * The {@code struct} data type made of {@code fields}; see {@link #struct(Field...)}.
     *
     * @param fields the fields, in storage order; at least one
     * @return the data type, whose size is the sum of the fields' sizes
     * @throws IllegalArgumentException if there are no fields, a name is empty or repeated, a field's type is
     *                                  variable-length, or the element would exceed 2 GB
     */
    public static DataType struct(List<Field> fields) {
        List<Field> copy = List.copyOf(fields);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("a struct needs at least one field");
        }
        int[] offsets = new int[copy.size()];
        Set<String> names = new HashSet<>();
        long size = 0;
        for (int i = 0; i < copy.size(); i++) {
            Field f = copy.get(i);
            if (f.name() == null || f.name().isEmpty()) {
                throw new IllegalArgumentException("struct field " + i + " has no name");
            }
            if (!names.add(f.name())) {
                throw new IllegalArgumentException("struct field name '" + f.name() + "' is repeated");
            }
            if (f.type() == null || f.type().isVariableLength()) {
                throw new IllegalArgumentException("struct field '" + f.name() + "' must have a fixed-size data type"
                        + (f.type() == null ? "" : ", not '" + f.type().name() + "'"));
            }
            offsets[i] = (int) size;
            size += f.type().byteCount();
            if (size > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("a struct element larger than 2 GB is not supported");
            }
        }
        return new DataType(STRUCT, DataTypeKind.STRUCT, (int) size, null, 0, copy, offsets);
    }

    /**
     * Reads a {@code data_type} from {@code zarr.json}: a name ({@code "float64"}), or an object with a
     * {@code "name"} and, for an extension type, a {@code "configuration"}. zarr-python's legacy
     * {@code "structured"} struct form, whose fields are {@code [name, data_type]} pairs, is read as
     * {@code struct}.
     *
     * @param json the {@code data_type} value
     * @return the data type
     * @throws ZarrFormatException      if the value is malformed, or a configuration is missing or invalid
     * @throws ZarrUnsupportedException if the data type is not one Falcon implements
     */
    public static DataType fromJson(JsonValue json) {
        if (json instanceof JsonString s) {
            return of(s.value());
        }
        if (!(json instanceof JsonObject o)) {
            throw new ZarrFormatException("a data type must be a name or an object, was " + json.typeName());
        }
        String name = text(o.find("name").orElseThrow(
                () -> new ZarrFormatException("a data type object needs a \"name\"")), "the data type's name");
        JsonValue configValue = o.find("configuration").orElse(null);
        if (configValue != null && !(configValue instanceof JsonObject)) {
            throw new ZarrFormatException("the configuration of data type '" + name + "' must be an object, was "
                    + configValue.typeName());
        }
        JsonObject config = (JsonObject) configValue;
        if (!isExtensionName(name)) {
            if (config != null && !config.members().isEmpty()) {
                throw new ZarrUnsupportedException("data type '" + name
                        + "' with a configuration is an extension data type Falcon does not implement");
            }
            return of(name);
        }
        if (config == null) {
            throw new ZarrFormatException("the '" + name + "' data type needs a configuration");
        }
        try {
            return switch (name) {
                case DATETIME64, TIMEDELTA64 -> {
                    onlyKeys(config, name, "unit", "scale_factor");
                    String unit = text(require(config, "unit", name), name + " unit");
                    long scale = integer(require(config, "scale_factor", name), name + " scale_factor");
                    if (scale < 1 || scale > Integer.MAX_VALUE) {
                        throw new ZarrFormatException(name + " scale_factor must be 1 to 2147483647, was " + scale);
                    }
                    yield name.equals(DATETIME64) ? datetime64(unit, (int) scale) : timedelta64(unit, (int) scale);
                }
                case FIXED_LENGTH_UTF32 -> {
                    onlyKeys(config, name, "length_bytes");
                    int length = lengthBytes(config, name);
                    if (length % 4 != 0) {
                        throw new ZarrFormatException(name + " length_bytes must be a multiple of 4, was " + length);
                    }
                    yield fixedLengthUtf32(length / 4);
                }
                case NULL_TERMINATED_BYTES -> nullTerminatedBytes(lengthBytes(config, name)); // more keys: ignored
                case RAW_BYTES -> {
                    onlyKeys(config, name, "length_bytes");
                    yield rawBytes(lengthBytes(config, name));
                }
                default -> { // struct, structured
                    onlyKeys(config, name, "fields");
                    yield structFromJson(require(config, "fields", name), name);
                }
            };
        } catch (IllegalArgumentException e) {
            throw new ZarrFormatException("invalid '" + name + "' data type: " + e.getMessage(), e);
        }
    }

    private static DataType structFromJson(JsonValue value, String name) {
        if (!(value instanceof JsonArray array)) {
            throw new ZarrFormatException(name + " fields must be an array, was " + value.typeName());
        }
        List<Field> fields = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonValue f = array.get(i);
            String what = name + " field " + i;
            if (f instanceof JsonObject o) { // {"name": ..., "data_type": ...}; other members are ignored
                fields.add(new Field(text(require(o, "name", what), what + " name"),
                        fromJson(require(o, "data_type", what))));
            } else if (f instanceof JsonArray pair && pair.size() == 2) { // the legacy [name, data_type]
                fields.add(new Field(text(pair.get(0), what + " name"), fromJson(pair.get(1))));
            } else {
                throw new ZarrFormatException(what + " must be an object or a [name, data_type] pair");
            }
        }
        return struct(fields);
    }

    private static JsonValue require(JsonObject o, String key, String what) {
        return o.find(key).orElseThrow(() -> new ZarrFormatException(what + " needs \"" + key + "\""));
    }

    private static void onlyKeys(JsonObject config, String name, String... keys) {
        Set<String> known = Set.of(keys);
        for (String key : config.members().keySet()) {
            if (!known.contains(key)) {
                throw new ZarrFormatException("unexpected \"" + key + "\" in the configuration of data type '"
                        + name + "'");
            }
        }
    }

    private static int lengthBytes(JsonObject config, String name) {
        long length = integer(require(config, "length_bytes", name), name + " length_bytes");
        if (length < 1 || length > Integer.MAX_VALUE) {
            throw new ZarrFormatException(name + " length_bytes must be 1 to 2147483647, was " + length);
        }
        return (int) length;
    }

    private static String text(JsonValue v, String what) {
        if (v instanceof JsonString s) {
            return s.value();
        }
        throw new ZarrFormatException(what + " must be a string, was " + v.typeName());
    }

    private static long integer(JsonValue v, String what) {
        if (v instanceof JsonNumber n) {
            try {
                return n.longValue();
            } catch (JsonException e) {
                throw new ZarrFormatException(what + " must be an integer, was " + n.literal());
            }
        }
        throw new ZarrFormatException(what + " must be an integer, was " + v.typeName());
    }

    /**
     * This data type as a {@code zarr.json} {@code data_type}: its name for a core type, and for an
     * extension type an object with its name and configuration, as zarr-python 3.4 writes them.
     *
     * @return the {@code data_type} JSON
     */
    public JsonValue toJson() {
        JsonObject.Builder config = JsonObject.builder();
        switch (kind) {
            case DATETIME, TIMEDELTA -> config.put("unit", unit).put("scale_factor", scaleFactor);
            case FIXED_STRING, FIXED_BYTES, RAW_BYTES -> config.put("length_bytes", byteCount);
            case STRUCT -> {
                List<JsonValue> list = new ArrayList<>(fields.size());
                for (Field f : fields) {
                    list.add(JsonObject.builder().put("name", f.name()).put("data_type", f.type().toJson()).build());
                }
                config.put("fields", new JsonArray(list));
            }
            default -> {
                return new JsonString(name);
            }
        }
        return JsonObject.builder().put("name", name).put("configuration", config.build()).build();
    }

    /**
     * The data-type name as written in {@code zarr.json} (for example {@code "float64"}, {@code "r24"},
     * {@code "numpy.datetime64"}, {@code "struct"}). An extension type's configuration is not part of it:
     * see {@link #toJson()}.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The kind of this data type.
     *
     * @return the kind
     */
    public DataTypeKind kind() {
        return kind;
    }

    /**
     * The size of one element in bytes ({@code -1}, undefined, for a variable-length type). A
     * {@code fixed_length_utf32} element of {@code n} code points is {@code 4n} bytes.
     *
     * @return the element size
     */
    public int byteCount() {
        return byteCount;
    }

    /**
     * Whether elements have no fixed byte size (the {@code string} and {@code variable_length_bytes} types).
     *
     * @return true for a variable-length type
     */
    public boolean isVariableLength() {
        return kind == DataTypeKind.STRING || kind == DataTypeKind.BYTES;
    }

    /**
     * Whether an element's stored bytes depend on the byte order the {@code bytes} codec names: true for
     * multi-byte numbers, times, and UTF-32 text, and for a struct with such a field; false for single-byte
     * types, byte strings, raw types, and variable-length types.
     *
     * @return true if the {@code bytes} codec's {@code endian} applies to this type
     */
    public boolean hasByteOrder() {
        return switch (kind) {
            case INT, UINT, FLOAT, COMPLEX, DATETIME, TIMEDELTA, FIXED_STRING -> byteCount > 1;
            case STRUCT -> fields.stream().anyMatch(f -> f.type().hasByteOrder());
            case BOOL, RAW, FIXED_BYTES, RAW_BYTES, STRING, BYTES -> false;
        };
    }

    /**
     * The time unit of a {@code numpy.datetime64} or {@code numpy.timedelta64} type (for example
     * {@code "s"}, {@code "ns"}, {@code "generic"}).
     *
     * @return the unit
     * @throws IllegalStateException if this is not a time type
     */
    public String unit() {
        requireTime();
        return unit;
    }

    /**
     * The scale factor of a {@code numpy.datetime64} or {@code numpy.timedelta64} type: how many
     * {@linkplain #unit() units} one count is (10 for numpy's {@code datetime64[10s]}).
     *
     * @return the scale factor, at least 1
     * @throws IllegalStateException if this is not a time type
     */
    public int scaleFactor() {
        requireTime();
        return scaleFactor;
    }

    private void requireTime() {
        if (kind != DataTypeKind.DATETIME && kind != DataTypeKind.TIMEDELTA) {
            throw new IllegalStateException("'" + name + "' is not a numpy.datetime64 or numpy.timedelta64 type");
        }
    }

    /**
     * A struct's fields, in storage order; empty for any other type.
     *
     * @return the fields (immutable)
     */
    public List<Field> fields() {
        return fields;
    }

    /**
     * Where a struct's field starts within an element, in bytes: the sum of the sizes of the fields before
     * it, as the struct is packed without padding.
     *
     * @param fieldName the field's name
     * @return the field's byte offset
     * @throws IllegalArgumentException if this type has no field of that name
     */
    public int fieldOffset(String fieldName) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(fieldName)) {
                return offsets[i];
            }
        }
        throw new IllegalArgumentException("'" + name + "' has no field named '" + fieldName + "'");
    }

    /**
     * The fill value zarr-python gives an array of this type when none is chosen, as JSON: zero (or
     * {@code false}) for numbers, NaT for times, the empty string or byte string for text and
     * {@code null_terminated_bytes}, zero bytes for {@code raw_bytes} and {@code r<N>}, and for a struct an
     * object holding each field's default.
     *
     * @return the default fill value's JSON
     */
    public JsonValue defaultFillValue() {
        return switch (kind) {
            case STRING, BYTES -> new JsonString("");
            case DATETIME, TIMEDELTA -> JsonNumber.of(Long.MIN_VALUE);
            case STRUCT -> {
                JsonObject.Builder b = JsonObject.builder();
                for (Field f : fields) {
                    b.put(f.name(), f.type().defaultFillValue());
                }
                yield b.build();
            }
            default -> encodeFillValue(new byte[byteCount], ByteOrder.LITTLE_ENDIAN);
        };
    }

    /**
     * Decodes a JSON fill value into one element's bytes.
     *
     * @param fill  the fill value's JSON
     * @param order the byte order of the element's numbers
     * @return the element's bytes
     * @throws ZarrFormatException           if {@code fill} is not a valid fill value for this data type
     * @throws UnsupportedOperationException for a variable-length type, which has no fixed-size encoding
     */
    public byte[] decodeFillValue(JsonValue fill, ByteOrder order) {
        return switch (kind) {
            case BOOL -> new byte[] {(byte) (requireBool(fill) ? 1 : 0)};
            case INT, UINT -> {
                BigInteger v = requireInteger(fill);
                if (v.compareTo(min()) < 0 || v.compareTo(max()) > 0) {
                    throw new ZarrFormatException(
                            "fill value " + v + " out of range for " + name);
                }
                byte[] out = new byte[byteCount];
                writeLowBytes(v.longValue(), out, 0, byteCount, order);
                yield out;
            }
            case FLOAT -> decodeFloat(fill, byteCount, order);
            case COMPLEX -> {
                JsonArray a = requireArray(fill, 2);
                int half = byteCount / 2;
                byte[] out = new byte[byteCount];
                System.arraycopy(decodeFloat(a.get(0), half, order), 0, out, 0, half);
                System.arraycopy(decodeFloat(a.get(1), half, order), 0, out, half, half);
                yield out;
            }
            case RAW -> decodeRaw(fill);
            case DATETIME, TIMEDELTA -> {
                long v;
                if (fill instanceof JsonString s && s.value().equals("NaT")) {
                    v = Long.MIN_VALUE;
                } else {
                    BigInteger i = requireInteger(fill);
                    if (i.bitLength() > 63) {
                        throw new ZarrFormatException("fill value " + i + " out of range for " + name);
                    }
                    v = i.longValue();
                }
                byte[] out = new byte[8];
                writeLowBytes(v, out, 0, 8, order);
                yield out;
            }
            case FIXED_STRING -> {
                if (!(fill instanceof JsonString s)) {
                    throw new ZarrFormatException("expected a string fill value for " + name + ", was "
                            + fill.typeName());
                }
                byte[] out = new byte[byteCount];
                Elements.putUtf32(out, 0, byteCount, order, s.value(), true); // cut to fit, as numpy does
                yield out;
            }
            case FIXED_BYTES, RAW_BYTES -> Arrays.copyOf(base64(fill), byteCount); // zero-padded or cut
            case STRUCT -> decodeStructFill(fill, order);
            case STRING, BYTES -> throw new UnsupportedOperationException(
                    "the '" + name + "' data type has no fixed-size fill encoding; its fill value is a string");
        };
    }

    private byte[] decodeStructFill(JsonValue fill, ByteOrder order) {
        if (fill instanceof JsonString) { // the whole element, packed little-endian
            byte[] le = base64(fill);
            if (le.length != byteCount) {
                throw new ZarrFormatException("a base64 struct fill value must hold " + byteCount + " bytes, held "
                        + le.length);
            }
            if (order != ByteOrder.LITTLE_ENDIAN) {
                Elements.reverseByteOrder(le, 0, this, 1);
            }
            return le;
        }
        if (!(fill instanceof JsonObject o)) {
            throw new ZarrFormatException("expected an object fill value for a struct, was " + fill.typeName());
        }
        byte[] out = new byte[byteCount];
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            JsonValue value = o.find(f.name()).orElse(null); // a field left out takes its default, as in zarr-python
            byte[] element;
            try {
                element = f.type().decodeFillValue(value != null ? value : f.type().defaultFillValue(), order);
            } catch (ZarrFormatException e) {
                throw new ZarrFormatException("struct field '" + f.name() + "': " + e.getMessage(), e);
            }
            System.arraycopy(element, 0, out, offsets[i], element.length);
        }
        return out;
    }

    private static byte[] base64(JsonValue fill) {
        if (!(fill instanceof JsonString s)) {
            throw new ZarrFormatException("expected a base64 string fill value, was " + fill.typeName());
        }
        try {
            return Base64.getDecoder().decode(s.value());
        } catch (IllegalArgumentException e) {
            throw new ZarrFormatException("fill value is not base64: " + e.getMessage(), e);
        }
    }

    /**
     * Encodes one element's bytes as a JSON fill value (the inverse of {@link #decodeFillValue}).
     *
     * @param element the element's bytes, exactly {@link #byteCount()} of them
     * @param order   the byte order of the element's numbers
     * @return the fill value's JSON, in the form zarr-python writes
     * @throws IllegalArgumentException      if {@code element} is not one element long
     * @throws UnsupportedOperationException for a variable-length type, which has no fixed-size encoding
     */
    public JsonValue encodeFillValue(byte[] element, ByteOrder order) {
        if (element.length != byteCount) {
            throw new IllegalArgumentException(
                    "element is " + element.length + " bytes, expected " + byteCount + " for " + name);
        }
        return switch (kind) {
            case BOOL -> JsonBool.of(element[0] != 0);
            case INT -> {
                long v = signExtend(readLowBytes(element, 0, byteCount, order), byteCount);
                yield JsonNumber.of(v);
            }
            case UINT -> {
                BigInteger v = unsigned(element, 0, byteCount, order);
                yield v.bitLength() < 64 ? JsonNumber.of(v.longValueExact()) : JsonNumber.of(v);
            }
            case FLOAT -> encodeFloat(element, 0, byteCount, order);
            case COMPLEX -> {
                int half = byteCount / 2;
                yield JsonArray.of(encodeFloat(element, 0, half, order),
                        encodeFloat(element, half, half, order));
            }
            case RAW -> {
                List<JsonValue> bytes = new ArrayList<>(byteCount);
                for (byte b : element) {
                    bytes.add(JsonNumber.of(b & 0xff));
                }
                yield new JsonArray(bytes);
            }
            case DATETIME, TIMEDELTA -> JsonNumber.of(readLowBytes(element, 0, 8, order)); // NaT as -2^63
            case FIXED_STRING -> new JsonString(Elements.getUtf32(element, 0, byteCount, order));
            case FIXED_BYTES -> {
                int n = byteCount;
                while (n > 0 && element[n - 1] == 0) {
                    n--; // numpy's S value ends before its trailing NULs
                }
                yield new JsonString(Base64.getEncoder().encodeToString(Arrays.copyOf(element, n)));
            }
            case RAW_BYTES -> new JsonString(Base64.getEncoder().encodeToString(element));
            case STRUCT -> {
                JsonObject.Builder b = JsonObject.builder();
                for (int i = 0; i < fields.size(); i++) {
                    Field f = fields.get(i);
                    b.put(f.name(), f.type().encodeFillValue(
                            Arrays.copyOfRange(element, offsets[i], offsets[i] + f.type().byteCount()), order));
                }
                yield b.build();
            }
            case STRING, BYTES -> throw new UnsupportedOperationException(
                    "the '" + name + "' data type has no fixed-size fill encoding; its fill value is a string");
        };
    }

    // ---- floats -----------------------------------------------------------------------------------

    private static byte[] decodeFloat(JsonValue v, int size, ByteOrder order) {
        if (v instanceof JsonString s) {
            String text = s.value();
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return orient(parseHex(text, size), order);
            }
            double special = switch (text) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> throw new ZarrFormatException("invalid float fill value: \"" + text + "\"");
            };
            return floatBits(special, size, order);
        }
        if (v instanceof JsonNumber n) {
            return floatBits(n.doubleValue(), size, order);
        }
        throw new ZarrFormatException("invalid float fill value: " + v.typeName());
    }

    private static byte[] floatBits(double value, int size, ByteOrder order) {
        byte[] out = new byte[size];
        long bits = switch (size) {
            case 2 -> Float16.fromDouble(value) & 0xffffL; // not via float, which would round twice
            case 4 -> Float.floatToRawIntBits((float) value) & 0xffffffffL;
            case 8 -> Double.doubleToRawLongBits(value);
            default -> throw new IllegalStateException("float size " + size);
        };
        writeLowBytes(bits, out, 0, size, order);
        return out;
    }

    private static JsonValue encodeFloat(byte[] element, int off, int size, ByteOrder order) {
        long bits = readLowBytes(element, off, size, order);
        double value;
        boolean single = size <= 4;
        float f = 0f;
        switch (size) {
            case 2 -> {
                f = Float.float16ToFloat((short) bits);
                value = f;
            }
            case 4 -> {
                f = Float.intBitsToFloat((int) bits);
                value = f;
            }
            case 8 -> value = Double.longBitsToDouble(bits);
            default -> throw new IllegalStateException("float size " + size);
        }
        if (Double.isNaN(value)) {
            // "NaN" decodes to the canonical quiet NaN; any other NaN keeps its bits as a hex string.
            long canonical = switch (size) {
                case 2 -> 0x7e00L;
                case 4 -> 0x7fc00000L;
                default -> 0x7ff8000000000000L;
            };
            if (bits == canonical) {
                return new JsonString("NaN");
            }
            return new JsonString("0x" + String.format("%0" + (2 * size) + "x", bits));
        }
        if (value == Double.POSITIVE_INFINITY) {
            return new JsonString("Infinity");
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return new JsonString("-Infinity");
        }
        return single ? new JsonNumber(Float.toString(f)) : JsonNumber.of(value);
    }

    // ---- raw --------------------------------------------------------------------------------------

    private byte[] decodeRaw(JsonValue v) {
        if (v instanceof JsonString s) {
            return parseHex(s.value(), byteCount);
        }
        if (v instanceof JsonArray a) {
            if (a.size() != byteCount) {
                throw new ZarrFormatException("raw fill value has " + a.size()
                        + " bytes, expected " + byteCount + " for " + name);
            }
            byte[] out = new byte[byteCount];
            for (int i = 0; i < byteCount; i++) {
                BigInteger b = requireInteger(a.get(i));
                if (b.signum() < 0 || b.compareTo(BigInteger.valueOf(255)) > 0) {
                    throw new ZarrFormatException("raw fill value byte " + i + " out of range [0,255]");
                }
                out[i] = (byte) b.intValueExact();
            }
            return out;
        }
        throw new ZarrFormatException("invalid raw fill value: " + v.typeName());
    }

    // ---- byte helpers ---------------------------------------------------------------------------

    private static void writeLowBytes(long bits, byte[] dst, int off, int n, ByteOrder order) {
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = 0; i < n; i++) {
                dst[off + i] = (byte) (bits >>> (8 * i));
            }
        } else {
            for (int i = 0; i < n; i++) {
                dst[off + (n - 1 - i)] = (byte) (bits >>> (8 * i));
            }
        }
    }

    private static long readLowBytes(byte[] src, int off, int n, ByteOrder order) {
        long bits = 0;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = n - 1; i >= 0; i--) {
                bits = (bits << 8) | (src[off + i] & 0xffL);
            }
        } else {
            for (int i = 0; i < n; i++) {
                bits = (bits << 8) | (src[off + i] & 0xffL);
            }
        }
        return bits;
    }

    private static long signExtend(long bits, int n) {
        int shift = 64 - 8 * n;
        return (bits << shift) >> shift;
    }

    private static BigInteger unsigned(byte[] element, int off, int n, ByteOrder order) {
        byte[] be = new byte[n];
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = 0; i < n; i++) {
                be[i] = element[off + (n - 1 - i)];
            }
        } else {
            System.arraycopy(element, off, be, 0, n);
        }
        return new BigInteger(1, be);
    }

    /** Reorders big-endian {@code be} to the requested order (raw hex is written most-significant first). */
    private static byte[] orient(byte[] be, ByteOrder order) {
        if (order == ByteOrder.BIG_ENDIAN) {
            return be;
        }
        byte[] le = new byte[be.length];
        for (int i = 0; i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    private static byte[] parseHex(String text, int size) {
        if (!(text.startsWith("0x") || text.startsWith("0X"))) {
            throw new ZarrFormatException("expected a \"0x…\" hex string, was \"" + text + "\"");
        }
        String hex = text.substring(2);
        if (hex.length() != 2 * size) {
            throw new ZarrFormatException("hex fill value \"" + text + "\" must have " + (2 * size)
                    + " digits for a " + size + "-byte value");
        }
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new ZarrFormatException("invalid hex digit in fill value \"" + text + "\"");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // ---- range and JSON coercion ----------------------------------------------------------------

    private BigInteger min() {
        if (kind == DataTypeKind.UINT) {
            return BigInteger.ZERO;
        }
        return BigInteger.ONE.shiftLeft(8 * byteCount - 1).negate();
    }

    private BigInteger max() {
        if (kind == DataTypeKind.UINT) {
            return BigInteger.ONE.shiftLeft(8 * byteCount).subtract(BigInteger.ONE);
        }
        return BigInteger.ONE.shiftLeft(8 * byteCount - 1).subtract(BigInteger.ONE);
    }

    private static boolean requireBool(JsonValue v) {
        if (v instanceof JsonBool b) {
            return b.value();
        }
        throw new ZarrFormatException("expected a boolean fill value, was " + v.typeName());
    }

    private static BigInteger requireInteger(JsonValue v) {
        if (v instanceof JsonNumber n) {
            try {
                return n.bigIntegerValue();
            } catch (JsonException e) {
                throw new ZarrFormatException("fill value " + n.literal() + " is not an integer");
            }
        }
        throw new ZarrFormatException("expected an integer fill value, was " + v.typeName());
    }

    private static JsonArray requireArray(JsonValue v, int size) {
        if (v instanceof JsonArray a && a.size() == size) {
            return a;
        }
        throw new ZarrFormatException("expected a " + size + "-element array fill value");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DataType other && name.equals(other.name) && byteCount == other.byteCount
                && Objects.equals(unit, other.unit) && scaleFactor == other.scaleFactor
                && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, byteCount, unit, scaleFactor, fields);
    }

    @Override
    public String toString() {
        JsonValue json = toJson();
        return "DataType[" + (json instanceof JsonString ? name : json.toJson()) + "]";
    }
}
