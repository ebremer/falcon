package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

/**
 * Interprets a flat buffer of decoded elements (C order, {@code order} byte order) as a typed Java array,
 * and encodes a typed Java array into one.
 *
 * <p>Each reader widens where it can and refuses where it cannot: {@code readDoubles} accepts any numeric
 * type (integers and floats), {@code readLongs}/{@code readInts} accept the integer types that fit, and
 * {@code readFloats} accepts the float types. Types that do not fit (for example {@code uint64} as
 * {@code long}, or {@code complex}/{@code r*} as any primitive) raise {@link ZarrException}; use the raw
 * element bytes for those.
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
            case COMPLEX, RAW, STRING -> throw cannotRead(dt, "double");
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
                    throw new ZarrException("uint64 values may exceed long; read as double or raw bytes");
                }
            }
            case FLOAT, COMPLEX, RAW, STRING -> throw cannotRead(dt, "long");
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
            case FLOAT, COMPLEX, RAW, STRING -> throw cannotRead(dt, "int");
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

    // ---- encoding (typed array -> element bytes) --------------------------------------------------
    //
    // A value is stored exactly or not at all. An integer type takes a whole number in its range; a float
    // type rounds to nearest, ties to even, and refuses a finite value beyond its range; bool stores any
    // nonzero value, NaN included, as true, as numpy does. Anything else is an IllegalArgumentException
    // naming the value and its index.

    public static byte[] fromDoubles(double[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putDouble(bb, i * es, dt, es, values[i], i);
        }
        return bb.array();
    }

    public static byte[] fromFloats(float[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putDouble(bb, i * es, dt, es, values[i], i); // float -> double is exact, so this rounds once
        }
        return bb.array();
    }

    public static byte[] fromLongs(long[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putLong(bb, i * es, dt, es, values[i], i);
        }
        return bb.array();
    }

    public static byte[] fromInts(int[] values, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt);
        ByteBuffer bb = allocate(values.length, es, order);
        for (int i = 0; i < values.length; i++) {
            putLong(bb, i * es, dt, es, values[i], i);
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
        int es = requireNumeric(dt);
        ByteBuffer bb = ByteBuffer.allocate(es).order(order);
        putDouble(bb, 0, dt, es, value, -1);
        return bb.array();
    }

    /**
     * One element's bytes holding {@code value}, under the same rules as {@link #fromLongs}.
     *
     * @throws IllegalArgumentException if the data type cannot hold {@code value}
     * @throws ZarrException            if the data type is not bool, an integer, or a float
     */
    public static byte[] fromLong(long value, DataType dt, ByteOrder order) {
        int es = requireNumeric(dt);
        ByteBuffer bb = ByteBuffer.allocate(es).order(order);
        putLong(bb, 0, dt, es, value, -1);
        return bb.array();
    }

    private static int requireNumeric(DataType dt) {
        return switch (dt.kind()) {
            case BOOL, INT, UINT, FLOAT -> dt.byteCount();
            case COMPLEX, RAW -> throw new ZarrException(
                    dt.name() + " cannot be written from a primitive array; use the raw element bytes");
            case STRING -> throw new ZarrException(
                    "the '" + dt.name() + "' data type is variable-length; use writeStrings()");
        };
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
        return new ZarrException(dt.name() + " cannot be read as " + as + "; use the raw element bytes");
    }
}
