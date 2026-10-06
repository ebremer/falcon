package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Interprets a flat buffer of decoded elements (C order, {@code order} byte order) as a typed Java array,
 * and encodes a typed Java array into one.
 *
 * <p>Each reader widens where it can and refuses where it cannot: {@code readDoubles} accepts any numeric
 * type (integers and floats), {@code readLongs}/{@code readInts} accept the integer types that fit, and
 * {@code readFloats} accepts the float types. Types that do not fit raise {@link ZarrException}: whether a
 * type fits is decided by the type, not by the values stored. Two readers cover the rest exactly (F8):
 * {@code readUnsignedLongs} gives any unsigned type's values, {@code uint64}'s as its 64 bits, and
 * {@code readComplex} gives a complex type's parts as {@code double}s. The raw element bytes cover
 * {@code r*}.
 *
 * <p>The extension types (F14) have their own: a {@code numpy.datetime64} or {@code numpy.timedelta64}
 * reads as {@code long}s (its counts of time units, {@link Long#MIN_VALUE} for NaT), a
 * {@code fixed_length_utf32} as {@code String}s ({@link #toFixedStrings}), and a
 * {@code null_terminated_bytes}, {@code raw_bytes}, {@code struct}, or {@code r*} as one {@code byte[]} per
 * element ({@link #toFixedByteArrays}).
 */
public final class Elements {

    private Elements() {
    }

    // Each reader picks its conversion once, by kind and size, and then runs a plain loop over a typed
    // view of the buffer (PF7: the kind used to be switched on for every element).

    public static double[] toDoubles(byte[] buf, DataType dt, ByteOrder order, int count) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        double[] out = new double[count];
        boolean unsigned = dt.kind() == DataTypeKind.UINT;
        switch (dt.kind()) {
            case BOOL -> {
                for (int i = 0; i < count; i++) {
                    out[i] = buf[i] != 0 ? 1.0 : 0.0;
                }
            }
            case INT, UINT -> {
                switch (dt.byteCount()) {
                    case 1 -> {
                        for (int i = 0; i < count; i++) {
                            out[i] = unsigned ? buf[i] & 0xff : buf[i];
                        }
                    }
                    case 2 -> {
                        ShortBuffer v = bb.asShortBuffer();
                        for (int i = 0; i < count; i++) {
                            out[i] = unsigned ? v.get(i) & 0xffff : v.get(i);
                        }
                    }
                    case 4 -> {
                        IntBuffer v = bb.asIntBuffer();
                        for (int i = 0; i < count; i++) {
                            out[i] = unsigned ? v.get(i) & 0xffffffffL : v.get(i);
                        }
                    }
                    default -> {
                        LongBuffer v = bb.asLongBuffer();
                        for (int i = 0; i < count; i++) {
                            out[i] = unsigned ? unsignedToDouble(v.get(i)) : v.get(i);
                        }
                    }
                }
            }
            case FLOAT -> {
                switch (dt.byteCount()) {
                    case 2 -> {
                        ShortBuffer v = bb.asShortBuffer();
                        for (int i = 0; i < count; i++) {
                            out[i] = Float.float16ToFloat(v.get(i));
                        }
                    }
                    case 4 -> {
                        FloatBuffer v = bb.asFloatBuffer();
                        for (int i = 0; i < count; i++) {
                            out[i] = v.get(i);
                        }
                    }
                    default -> bb.asDoubleBuffer().get(out);
                }
            }
            case COMPLEX, RAW, STRING, BYTES, DATETIME, TIMEDELTA, FIXED_STRING, FIXED_BYTES, RAW_BYTES, STRUCT ->
                    throw cannotRead(dt, "double");
        }
        return out;
    }

    public static float[] toFloats(byte[] buf, DataType dt, ByteOrder order, int count) {
        if (dt.kind() != DataTypeKind.FLOAT) {
            throw cannotRead(dt, "float");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        float[] out = new float[count];
        switch (dt.byteCount()) {
            case 2 -> {
                ShortBuffer v = bb.asShortBuffer();
                for (int i = 0; i < count; i++) {
                    out[i] = Float.float16ToFloat(v.get(i));
                }
            }
            case 4 -> bb.asFloatBuffer().get(out);
            default -> {
                DoubleBuffer v = bb.asDoubleBuffer();
                for (int i = 0; i < count; i++) {
                    out[i] = (float) v.get(i);
                }
            }
        }
        return out;
    }

    public static long[] toLongs(byte[] buf, DataType dt, ByteOrder order, int count) {
        boolean unsigned = dt.kind() == DataTypeKind.UINT;
        switch (dt.kind()) {
            case BOOL, INT, UINT -> {
                if (unsigned && dt.byteCount() == 8) {
                    throw new ZarrException("uint64 values may exceed long; use readUnsignedLongs() for exact"
                            + " values, or readDoubles()");
                }
            }
            case DATETIME, TIMEDELTA -> {
                // 8-byte signed counts of time units, NaT as Long.MIN_VALUE: the int64 path below
            }
            case FLOAT, COMPLEX, RAW, STRING, BYTES, FIXED_STRING, FIXED_BYTES, RAW_BYTES, STRUCT ->
                    throw cannotRead(dt, "long");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        long[] out = new long[count];
        switch (dt.byteCount()) {
            case 1 -> {
                boolean bool = dt.kind() == DataTypeKind.BOOL;
                for (int i = 0; i < count; i++) {
                    out[i] = bool ? (buf[i] != 0 ? 1 : 0) : unsigned ? buf[i] & 0xff : buf[i];
                }
            }
            case 2 -> {
                ShortBuffer v = bb.asShortBuffer();
                for (int i = 0; i < count; i++) {
                    out[i] = unsigned ? v.get(i) & 0xffff : v.get(i);
                }
            }
            case 4 -> {
                IntBuffer v = bb.asIntBuffer();
                for (int i = 0; i < count; i++) {
                    out[i] = unsigned ? v.get(i) & 0xffffffffL : v.get(i);
                }
            }
            default -> bb.asLongBuffer().get(out);
        }
        return out;
    }

    public static int[] toInts(byte[] buf, DataType dt, ByteOrder order, int count) {
        boolean unsigned = dt.kind() == DataTypeKind.UINT;
        switch (dt.kind()) {
            case BOOL -> {
            }
            case INT -> {
                if (dt.byteCount() > 4) {
                    throw new ZarrException("int64 exceeds int; read as long");
                }
            }
            case UINT -> {
                if (dt.byteCount() > 2) {
                    throw new ZarrException("uint32/uint64 may exceed int; read as long or double");
                }
            }
            case FLOAT, COMPLEX, RAW, STRING, BYTES, DATETIME, TIMEDELTA, FIXED_STRING, FIXED_BYTES, RAW_BYTES,
                 STRUCT -> throw cannotRead(dt, "int");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        int[] out = new int[count];
        switch (dt.byteCount()) {
            case 1 -> {
                boolean bool = dt.kind() == DataTypeKind.BOOL;
                for (int i = 0; i < count; i++) {
                    out[i] = bool ? (buf[i] != 0 ? 1 : 0) : unsigned ? buf[i] & 0xff : buf[i];
                }
            }
            case 2 -> {
                ShortBuffer v = bb.asShortBuffer();
                for (int i = 0; i < count; i++) {
                    out[i] = unsigned ? v.get(i) & 0xffff : v.get(i);
                }
            }
            default -> bb.asIntBuffer().get(out);
        }
        return out;
    }

    /**
     * The elements of an unsigned integer type as {@code long}s holding their values exactly: uint8, uint16,
     * and uint32 zero-extended, and uint64 as its 64 bits, so that a value of 2<sup>63</sup> or more is a
     * negative {@code long} which {@link Long#toUnsignedString(long)}, {@link Long#compareUnsigned}, and
     * {@link Long#divideUnsigned} read correctly.
     */
    public static long[] toUnsignedLongs(byte[] buf, DataType dt, ByteOrder order, int count) {
        if (dt.kind() != DataTypeKind.UINT) {
            throw cannotRead(dt, "unsigned long");
        }
        if (dt.byteCount() < 8) {
            return toLongs(buf, dt, order, count); // zero-extended
        }
        long[] out = new long[count];
        ByteBuffer.wrap(buf).order(order).asLongBuffer().get(out);
        return out;
    }

    /**
     * The elements of a complex type as {@code double}s, two per element: the real part, then the imaginary,
     * as numpy lays complex numbers out. A complex64's float parts widen exactly.
     */
    public static double[] toComplex(byte[] buf, DataType dt, ByteOrder order, int count) {
        if (dt.kind() != DataTypeKind.COMPLEX) {
            throw cannotRead(dt, "complex");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        double[] out = new double[2 * count]; // an element is at least 8 bytes, so this fits
        if (dt.byteCount() == 8) {
            FloatBuffer v = bb.asFloatBuffer();
            for (int i = 0; i < out.length; i++) {
                out[i] = v.get(i);
            }
        } else {
            bb.asDoubleBuffer().get(out);
        }
        return out;
    }

    // ---- encoding (typed array -> element bytes) --------------------------------------------------
    //
    // A value is stored exactly or not at all. An integer type takes a whole number in its range; a float
    // type rounds to nearest, ties to even, and refuses a finite value beyond its range; bool stores any
    // nonzero value, NaN included, as true, as numpy does. Anything else is an IllegalArgumentException
    // naming the value and its index.

    public static byte[] fromDoubles(double[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt, false);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putDouble(bb, i * es, dt, es, values[i], i);
        }
        return bb.array();
    }

    public static byte[] fromFloats(float[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt, false);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putDouble(bb, i * es, dt, es, values[i], i); // float -> double is exact, so this rounds once
        }
        return bb.array();
    }

    public static byte[] fromLongs(long[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt, true);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putLong(bb, i * es, dt, es, values[i], i);
        }
        return bb.array();
    }

    public static byte[] fromInts(int[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt, true);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putLong(bb, i * es, dt, es, values[i], i);
        }
        return bb.array();
    }

    /**
     * Encodes {@code values}, each taken as an unsigned 64-bit value (as {@link #toUnsignedLongs} gives
     * them), into an unsigned integer type: uint64 stores the bits as they are, and a narrower type takes a
     * value up to its maximum and refuses a larger one.
     *
     * @throws IllegalArgumentException if a value is too large for the type
     * @throws ZarrException            if the data type is not an unsigned integer type
     */
    public static byte[] fromUnsignedLongs(long[] values, DataType dt, ByteOrder order) {
        if (dt.kind() != DataTypeKind.UINT) {
            throw new ZarrException("writeUnsignedLongs needs an unsigned integer data type, not '" + dt.name()
                    + "'; use writeLongs()");
        }
        int es = dt.byteCount();
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            if (es < 8 && Long.compareUnsigned(values[i], max(dt)) > 0) {
                throw cannotStore(Long.toUnsignedString(values[i]), i, dt, range(dt));
            }
            putInteger(bb, i * es, es, values[i]);
        }
        return bb.array();
    }

    /**
     * Encodes complex values given as {@code double}s, two per element (the real part, then the imaginary),
     * into a complex type. A complex64 rounds each part to the nearest float, as {@link #fromDoubles} does
     * for float32, and refuses a finite part beyond float's range.
     *
     * @throws IllegalArgumentException if the count is odd, or a part is out of range
     * @throws ZarrException            if the data type is not a complex type
     */
    public static byte[] fromComplex(double[] values, DataType dt, ByteOrder order) {
        if (dt.kind() != DataTypeKind.COMPLEX) {
            throw new ZarrException("writeComplex needs a complex data type, not '" + dt.name() + "'");
        }
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("complex values come in pairs (real, imaginary), but got "
                    + values.length + " doubles");
        }
        int part = dt.byteCount() / 2;
        ByteBuffer bb = allocate(values.length / 2, dt.byteCount(), order);
        for (int i = 0; i < values.length; i++) {
            putFloat(bb, i * part, dt, part, values[i], i / 2);
        }
        return bb.array();
    }

    /**
     * One element's bytes holding {@code value}, under the same rules as {@link #fromDoubles}.
     *
     * @throws IllegalArgumentException if the data type cannot hold {@code value}
     * @throws ZarrException            if the data type is not bool, an integer, or a float
     */
    public static byte[] fromDouble(double value, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt, false);
        ByteBuffer bb = ByteBuffer.allocate(es).order(order);
        putDouble(bb, 0, dt, es, value, -1);
        return bb.array();
    }

    /**
     * One element's bytes holding {@code value}, under the same rules as {@link #fromLongs}.
     *
     * @throws IllegalArgumentException if the data type cannot hold {@code value}
     * @throws ZarrException            if the data type is not bool, an integer, a float, or a time type
     */
    public static byte[] fromLong(long value, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt, true);
        ByteBuffer bb = ByteBuffer.allocate(es).order(order);
        putLong(bb, 0, dt, es, value, -1);
        return bb.array();
    }

    /** The element size of a type a primitive array writes; a time type takes only integers. */
    private static int requireNumeric(DataType dt, boolean integers) {
        return switch (dt.kind()) {
            case BOOL, INT, UINT, FLOAT -> dt.byteCount();
            case DATETIME, TIMEDELTA -> {
                if (!integers) {
                    throw new ZarrException(dt.name() + " is written as counts of its time unit (Long.MIN_VALUE"
                            + " for NaT); use writeLongs()");
                }
                yield 8;
            }
            case FIXED_STRING -> throw new ZarrException(
                    "the '" + dt.name() + "' data type holds text; use writeStrings()");
            case FIXED_BYTES, RAW_BYTES, STRUCT -> throw new ZarrException(
                    "the '" + dt.name() + "' data type holds byte strings; use writeByteArrays()");
            case COMPLEX -> throw new ZarrException(dt.name()
                    + " cannot be written from a primitive array; use writeComplex() or the raw element bytes");
            case RAW -> throw new ZarrException(dt.name()
                    + " cannot be written from a primitive array; use writeByteArrays() or the raw element bytes");
            case STRING -> throw new ZarrException(
                    "the '" + dt.name() + "' data type is variable-length; use writeStrings()");
            case BYTES -> throw new ZarrException(
                    "the '" + dt.name() + "' data type is variable-length; use writeByteArrays()");
        };
    }

    // ---- fixed-size text and byte strings (F14) ---------------------------------------------------

    /**
     * The elements of a {@code fixed_length_utf32} type as strings: each element's code points less its
     * trailing NULs, as numpy reads a {@code U} value.
     *
     * @throws ZarrException       if the data type is not {@code fixed_length_utf32}
     * @throws ZarrFormatException if an element holds a code unit that is not a Unicode code point
     */
    public static String[] toFixedStrings(byte[] buf, DataType dt, ByteOrder order, int count) {
        if (dt.kind() != DataTypeKind.FIXED_STRING) {
            throw cannotRead(dt, "strings");
        }
        int es = dt.byteCount();
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            out[i] = getUtf32(buf, i * es, es, order);
        }
        return out;
    }

    /**
     * Encodes strings into a {@code fixed_length_utf32} type, each as its code points padded with NULs; a
     * {@code null} element is the empty string. A string's own trailing NULs do not survive reading, as in
     * numpy.
     *
     * @throws IllegalArgumentException if a string has more code points than the type holds
     * @throws ZarrException            if the data type is not {@code fixed_length_utf32}
     */
    public static byte[] fromFixedStrings(String[] values, DataType dt, ByteOrder order) {
        if (dt.kind() != DataTypeKind.FIXED_STRING) {
            throw new ZarrException("writeStrings needs the 'string' or 'fixed_length_utf32' data type, not '"
                    + dt.name() + "'");
        }
        int es = dt.byteCount();
        byte[] out = allocate(values.length, es, order).array();
        for (int i = 0; i < values.length; i++) {
            String v = values[i] == null ? "" : values[i];
            if (!putUtf32(out, i * es, es, order, v, false)) {
                throw cannotStore("a string of " + v.codePointCount(0, v.length()) + " code points", i, dt,
                        "it holds " + es / 4);
            }
        }
        return out;
    }

    /**
     * The elements of a fixed-size byte type, one {@code byte[]} each: a {@code null_terminated_bytes}
     * element less its trailing NULs (as numpy reads an {@code S} value), and a {@code raw_bytes},
     * {@code r*}, or {@code struct} element whole. A struct's numbers come little-endian whatever the array's
     * byte order, so an element reads the same from any array of its type.
     *
     * @throws ZarrException if the data type is not one of these
     */
    public static byte[][] toFixedByteArrays(byte[] buf, DataType dt, ByteOrder order, int count) {
        requireFixedBytes(dt, "readByteArrays");
        int es = dt.byteCount();
        boolean strip = dt.kind() == DataTypeKind.FIXED_BYTES;
        boolean swap = dt.kind() == DataTypeKind.STRUCT && order != ByteOrder.LITTLE_ENDIAN;
        byte[][] out = new byte[count][];
        for (int i = 0; i < count; i++) {
            int off = i * es;
            int n = es;
            while (strip && n > 0 && buf[off + n - 1] == 0) {
                n--;
            }
            out[i] = Arrays.copyOfRange(buf, off, off + n);
            if (swap) {
                reverseByteOrder(out[i], 0, dt, 1);
            }
        }
        return out;
    }

    /**
     * Encodes byte strings into a fixed-size byte type: a {@code null_terminated_bytes} element takes up to
     * its length, padded with NULs, and a {@code raw_bytes}, {@code r*}, or {@code struct} element exactly its
     * length (a struct's numbers little-endian, as {@link #toFixedByteArrays} gives them). A {@code null}
     * element is all zero bytes.
     *
     * @throws IllegalArgumentException if an element has the wrong length
     * @throws ZarrException            if the data type is not one of these
     */
    public static byte[] fromFixedByteArrays(byte[][] values, DataType dt, ByteOrder order) {
        requireFixedBytes(dt, "writeByteArrays");
        int es = dt.byteCount();
        boolean upTo = dt.kind() == DataTypeKind.FIXED_BYTES;
        byte[] out = allocate(values.length, es, order).array();
        for (int i = 0; i < values.length; i++) {
            byte[] v = values[i];
            if (v == null) {
                continue;
            }
            if (upTo ? v.length > es : v.length != es) {
                throw cannotStore("a byte string of " + v.length + " bytes", i, dt,
                        upTo ? "it holds " + es : "an element is exactly " + es);
            }
            System.arraycopy(v, 0, out, i * es, v.length);
        }
        if (dt.kind() == DataTypeKind.STRUCT && order != ByteOrder.LITTLE_ENDIAN) {
            reverseByteOrder(out, 0, dt, values.length);
        }
        return out;
    }

    private static void requireFixedBytes(DataType dt, String method) {
        switch (dt.kind()) {
            case FIXED_BYTES, RAW_BYTES, RAW, STRUCT -> {
            }
            default -> throw new ZarrException(method + " needs a byte-string data type (variable_length_bytes,"
                    + " null_terminated_bytes, raw_bytes, struct, or r*), not '" + dt.name() + "'");
        }
    }

    /**
     * The text of one {@code fixed_length_utf32} element of {@code size} bytes at {@code off}: its code
     * points, less trailing NULs.
     *
     * @throws ZarrFormatException if a code unit is not a Unicode code point
     */
    public static String getUtf32(byte[] buf, int off, int size, ByteOrder order) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        int n = size / 4;
        while (n > 0 && bb.getInt(off + 4 * (n - 1)) == 0) {
            n--;
        }
        StringBuilder text = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int cp = bb.getInt(off + 4 * i);
            if (cp < 0 || cp > Character.MAX_CODE_POINT) {
                throw new ZarrFormatException(String.format("invalid UTF-32 code unit 0x%08x", cp));
            }
            text.appendCodePoint(cp);
        }
        return text.toString();
    }

    /**
     * Writes {@code text}'s code points into one {@code fixed_length_utf32} element of {@code size} bytes at
     * {@code off}, padded with NULs. Text with more code points than fit is cut to fit when {@code cut}, as
     * numpy cuts a fill value; otherwise nothing is written.
     *
     * @return false if the text did not fit and was not cut
     */
    public static boolean putUtf32(byte[] buf, int off, int size, ByteOrder order, String text, boolean cut) {
        int units = size / 4;
        if (!cut && text.codePointCount(0, text.length()) > units) {
            return false;
        }
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        int i = 0;
        for (int at = 0; at < text.length() && i < units; i++) {
            int cp = text.codePointAt(at);
            bb.putInt(off + 4 * i, cp);
            at += Character.charCount(cp);
        }
        for (; i < units; i++) {
            bb.putInt(off + 4 * i, 0);
        }
        return true;
    }

    /**
     * Reverses the byte order of every number in {@code count} elements of {@code dt} at {@code off}: each
     * integer, float, and time, each half of a complex number, each UTF-32 code unit, and each such field of a
     * struct, at any depth, as numpy's {@code newbyteorder} does. Bools, byte strings, and raw types have no
     * byte order and stay as they are. Reversing twice restores the bytes, so this converts either way
     * between little- and big-endian.
     */
    public static void reverseByteOrder(byte[] buf, int off, DataType dt, int count) {
        int es = dt.byteCount();
        for (int e = 0; e < count; e++) {
            int base = off + e * es;
            switch (dt.kind()) {
                case INT, UINT, FLOAT, DATETIME, TIMEDELTA -> reverse(buf, base, es);
                case COMPLEX -> {
                    reverse(buf, base, es / 2);
                    reverse(buf, base + es / 2, es / 2);
                }
                case FIXED_STRING -> {
                    for (int u = 0; u < es; u += 4) {
                        reverse(buf, base + u, 4);
                    }
                }
                case STRUCT -> {
                    int at = base; // packed: each field starts where the one before it ends
                    for (DataType.Field f : dt.fields()) {
                        reverseByteOrder(buf, at, f.type(), 1);
                        at += f.type().byteCount();
                    }
                }
                default -> {
                    return; // no byte order
                }
            }
        }
    }

    private static void reverse(byte[] buf, int off, int n) {
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            byte t = buf[off + i];
            buf[off + i] = buf[off + j];
            buf[off + j] = t;
        }
    }


    private static ByteBuffer allocate(int count, int es, ByteOrder order) {
        return ByteBuffer.allocate(ChunkAssembler.bufferSize(count, es, "selection")).order(order);
    }

    /** Writes one element from a {@code double}; {@code index} names it in errors ({@code -1}: no index). */
    private static void putDouble(ByteBuffer bb, int off, DataType dt, int es, double v, int index) {
        switch (dt.kind()) {
            case BOOL -> bb.put(off, (byte) (v != 0 ? 1 : 0)); // NaN != 0, so NaN is true, as in numpy
            case INT, UINT -> {
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw cannotStore(Double.toString(v), index, dt, null);
                }
                if (v != Math.rint(v)) {
                    throw cannotStore(Double.toString(v), index, dt, "not a whole number");
                }
                long bits;
                if (es == 8) {
                    // -2^63 and 2^63 (2^64 unsigned) are exact doubles, so these bounds are exact.
                    boolean inRange = dt.kind() == DataTypeKind.INT
                            ? v >= -0x1p63 && v < 0x1p63
                            : v >= 0 && v < 0x1p64;
                    if (!inRange) {
                        throw cannotStore(Double.toString(v), index, dt, range(dt));
                    }
                    // A uint64 at or above 2^63 is cast from v - 2^63 (exact) with the top bit put back.
                    bits = v < 0x1p63 ? (long) v : (long) (v - 0x1p63) ^ Long.MIN_VALUE;
                } else {
                    if (v < min(dt) || v > max(dt)) {
                        throw cannotStore(Double.toString(v), index, dt, range(dt));
                    }
                    bits = (long) v;
                }
                putInteger(bb, off, es, bits);
            }
            case FLOAT -> putFloat(bb, off, dt, es, v, index);
            default -> throw new IllegalStateException(dt.name());
        }
    }

    /** Writes one element from a {@code long}; {@code index} names it in errors ({@code -1}: no index). */
    private static void putLong(ByteBuffer bb, int off, DataType dt, int es, long v, int index) {
        switch (dt.kind()) {
            case BOOL -> bb.put(off, (byte) (v != 0 ? 1 : 0));
            case INT, UINT -> {
                boolean inRange = dt.kind() == DataTypeKind.INT
                        ? es == 8 || (v >= min(dt) && v <= max(dt))
                        : v >= 0 && (es == 8 || v <= max(dt));
                if (!inRange) {
                    throw cannotStore(Long.toString(v), index, dt, range(dt));
                }
                putInteger(bb, off, es, v);
            }
            case DATETIME, TIMEDELTA -> bb.putLong(off, v); // every long is a count, Long.MIN_VALUE NaT
            case FLOAT -> {
                if (es == 4) {
                    bb.putFloat(off, (float) v); // long -> float rounds once; via double it could round twice
                } else {
                    // A double holds every long within half's range exactly; beyond it, the value overflows.
                    putFloat(bb, off, dt, es, (double) v, index);
                }
            }
            default -> throw new IllegalStateException(dt.name());
        }
    }

    private static void putFloat(ByteBuffer bb, int off, DataType dt, int es, double v, int index) {
        switch (es) {
            case 2 -> {
                short half = Float16.fromDouble(v);
                if ((half & 0x7fff) == 0x7c00 && !Double.isInfinite(v)) {
                    throw cannotStore(Double.toString(v), index, dt, "out of range");
                }
                bb.putShort(off, half);
            }
            case 4 -> {
                float f = (float) v;
                if (Float.isInfinite(f) && !Double.isInfinite(v)) {
                    throw cannotStore(Double.toString(v), index, dt, "out of range");
                }
                bb.putFloat(off, f);
            }
            case 8 -> bb.putDouble(off, v);
            default -> throw new IllegalStateException("float size " + es);
        }
    }

    private static void putInteger(ByteBuffer bb, int off, int es, long bits) {
        switch (es) {
            case 1 -> bb.put(off, (byte) bits);
            case 2 -> bb.putShort(off, (short) bits);
            case 4 -> bb.putInt(off, (int) bits);
            case 8 -> bb.putLong(off, bits);
            default -> throw new IllegalStateException("integer size " + es);
        }
    }

    /** The smallest value of an integer type of fewer than 8 bytes. */
    private static long min(DataType dt) {
        return dt.kind() == DataTypeKind.UINT ? 0 : -(1L << (8 * dt.byteCount() - 1));
    }

    /** The largest value of an integer type of fewer than 8 bytes. */
    private static long max(DataType dt) {
        int bits = 8 * dt.byteCount();
        return dt.kind() == DataTypeKind.UINT ? (1L << bits) - 1 : (1L << (bits - 1)) - 1;
    }

    private static String range(DataType dt) {
        if (dt.byteCount() == 8) {
            return dt.kind() == DataTypeKind.UINT
                    ? "out of range [0, 18446744073709551615]"
                    : "out of range [" + Long.MIN_VALUE + ", " + Long.MAX_VALUE + "]";
        }
        return "out of range [" + min(dt) + ", " + max(dt) + "]";
    }

    private static IllegalArgumentException cannotStore(String value, int index, DataType dt, String reason) {
        return new IllegalArgumentException("cannot store " + value + (index < 0 ? "" : " (index " + index + ")")
                + " as " + dt.name() + (reason == null ? "" : ": " + reason));
    }

    /**
     * Converts an unsigned 64-bit value (held in a {@code long}) to the nearest double. Halving keeps the
     * dropped bit as a sticky bit (round to odd), so the one rounding is still to nearest.
     */
    private static double unsignedToDouble(long bits) {
        return bits >= 0 ? (double) bits : (double) ((bits >>> 1) | (bits & 1)) * 2.0;
    }

    private static ZarrException cannotRead(DataType dt, String as) {
        String instead = switch (dt.kind()) {
            case COMPLEX -> "readComplex() or the raw element bytes";
            case STRING, FIXED_STRING -> "readStrings()";
            case BYTES, FIXED_BYTES, RAW_BYTES, STRUCT -> "readByteArrays()";
            case DATETIME, TIMEDELTA -> "readLongs() (counts of the time unit, Long.MIN_VALUE for NaT)";
            case RAW -> "readByteArrays() or the raw element bytes";
            default -> "the raw element bytes";
        };
        return new ZarrException(dt.name() + " cannot be read as " + as + "; use " + instead);
    }
}
