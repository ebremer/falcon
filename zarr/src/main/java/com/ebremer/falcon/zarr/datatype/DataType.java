package com.ebremer.falcon.zarr.datatype;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Zarr v3 core data type: its name, {@linkplain DataTypeKind kind}, and fixed element size in bytes.
 *
 * <p>Byte order is <em>not</em> part of a data type in Zarr v3 &mdash; it is carried by the {@code bytes}
 * codec &mdash; so the endianness-dependent operations here ({@link #decodeFillValue} /
 * {@link #encodeFillValue}) take a {@link ByteOrder} argument.
 *
 * <p>A fill value is stored in {@code zarr.json} as JSON; this class converts it to and from the element's
 * on-disk bytes:
 * <ul>
 *   <li><b>bool</b> &mdash; JSON {@code true}/{@code false} &harr; one byte;</li>
 *   <li><b>integers</b> &mdash; a JSON integer, range-checked against the type ({@code uint64} uses
 *       {@link BigInteger});</li>
 *   <li><b>floats</b> &mdash; a JSON number, one of {@code "NaN"}/{@code "Infinity"}/{@code "-Infinity"},
 *       or a {@code "0x…"} hex string giving the exact big-endian bit pattern;</li>
 *   <li><b>complex</b> &mdash; a two-element JSON array {@code [real, imag]} of the component float
 *       encoding;</li>
 *   <li><b>raw</b> &mdash; a JSON array of byte values, or a {@code "0x…"} hex string (no byte order).</li>
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

    private static final Map<String, DataType> BUILTINS = Map.ofEntries(
            Map.entry(BOOL.name, BOOL),
            Map.entry(INT8.name, INT8), Map.entry(INT16.name, INT16),
            Map.entry(INT32.name, INT32), Map.entry(INT64.name, INT64),
            Map.entry(UINT8.name, UINT8), Map.entry(UINT16.name, UINT16),
            Map.entry(UINT32.name, UINT32), Map.entry(UINT64.name, UINT64),
            Map.entry(FLOAT16.name, FLOAT16), Map.entry(FLOAT32.name, FLOAT32),
            Map.entry(FLOAT64.name, FLOAT64),
            Map.entry(COMPLEX64.name, COMPLEX64), Map.entry(COMPLEX128.name, COMPLEX128),
            Map.entry(STRING.name, STRING));

    private final String name;
    private final DataTypeKind kind;
    private final int byteCount;

    private DataType(String name, DataTypeKind kind, int byteCount) {
        this.name = name;
        this.kind = kind;
        this.byteCount = byteCount;
    }

    /**
     * Resolves a Zarr v3 data-type name.
     *
     * @throws ZarrFormatException      if {@code name} is a raw type {@code r<N>} with {@code N} not a
     *                                  positive multiple of 8
     * @throws ZarrUnsupportedException if {@code name} is not a recognized core data type
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
        throw new ZarrUnsupportedException("unknown data type: '" + name + "'");
    }

    /** The data-type name as written in {@code zarr.json} (for example {@code "float64"}, {@code "r24"}). */
    public String name() {
        return name;
    }

    /** The kind of this data type. */
    public DataTypeKind kind() {
        return kind;
    }

    /** The size of one element in bytes (undefined, {@code -1}, for a variable-length type). */
    public int byteCount() {
        return byteCount;
    }

    /** Whether elements have no fixed byte size (the {@code string} type). */
    public boolean isVariableLength() {
        return kind == DataTypeKind.STRING;
    }

    /**
     * Encodes a JSON fill value into one element's bytes.
     *
     * @throws ZarrFormatException if {@code fill} is not a valid fill value for this data type
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
            case STRING -> throw new UnsupportedOperationException(
                    "the '" + name + "' data type has no fixed-size fill encoding; its fill value is a string");
        };
    }

    /** Decodes one element's bytes back into a JSON fill value (the inverse of {@link #decodeFillValue}). */
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
            case STRING -> throw new UnsupportedOperationException(
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
            case 2 -> Float.floatToFloat16((float) value) & 0xffffL;
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
            return new JsonString("NaN");
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
        return o instanceof DataType other && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "DataType[" + name + "]";
    }
}
