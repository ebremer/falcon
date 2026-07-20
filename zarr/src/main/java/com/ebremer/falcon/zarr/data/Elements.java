package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Interprets a flat buffer of decoded elements (C order, {@code order} byte order) as a typed Java array.
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

    public static double[] toDoubles(byte[] buf, DataType dt, ByteOrder order, int count) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        int es = dt.byteCount();
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            int off = i * es;
            out[i] = switch (dt.kind()) {
                case BOOL -> buf[off] != 0 ? 1.0 : 0.0;
                case INT -> (double) signed(bb, off, es);
                case UINT -> es == 8 ? unsignedToDouble(bb.getLong(off)) : (double) unsigned(bb, off, es);
                case FLOAT -> floatValue(bb, off, es);
                case COMPLEX, RAW -> throw cannotRead(dt, "double");
            };
        }
        return out;
    }

    public static float[] toFloats(byte[] buf, DataType dt, ByteOrder order, int count) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        int es = dt.byteCount();
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            int off = i * es;
            out[i] = switch (dt.kind()) {
                case FLOAT -> (float) floatValue(bb, off, es);
                default -> throw cannotRead(dt, "float");
            };
        }
        return out;
    }

    public static long[] toLongs(byte[] buf, DataType dt, ByteOrder order, int count) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        int es = dt.byteCount();
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            int off = i * es;
            out[i] = switch (dt.kind()) {
                case BOOL -> buf[off] != 0 ? 1L : 0L;
                case INT -> signed(bb, off, es);
                case UINT -> {
                    if (es == 8) {
                        throw new ZarrException("uint64 values may exceed long; read as double or raw bytes");
                    }
                    yield unsigned(bb, off, es);
                }
                case FLOAT, COMPLEX, RAW -> throw cannotRead(dt, "long");
            };
        }
        return out;
    }

    public static int[] toInts(byte[] buf, DataType dt, ByteOrder order, int count) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
        int es = dt.byteCount();
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int off = i * es;
            out[i] = switch (dt.kind()) {
                case BOOL -> buf[off] != 0 ? 1 : 0;
                case INT -> {
                    if (es > 4) {
                        throw new ZarrException("int64 exceeds int; read as long");
                    }
                    yield (int) signed(bb, off, es);
                }
                case UINT -> {
                    if (es > 2) {
                        throw new ZarrException("uint32/uint64 may exceed int; read as long or double");
                    }
                    yield (int) unsigned(bb, off, es);
                }
                case FLOAT, COMPLEX, RAW -> throw cannotRead(dt, "int");
            };
        }
        return out;
    }

    // ---- encoding (typed array -> element bytes) --------------------------------------------------

    public static byte[] fromDoubles(double[] values, DataType dt, ByteOrder order) {
        int es = dt.byteCount();
        ByteBuffer bb = ByteBuffer.allocate(values.length * es).order(order);
        for (int i = 0; i < values.length; i++) {
            putNumber(bb, i * es, dt, es, values[i], (long) values[i]);
        }
        return bb.array();
    }

    public static byte[] fromFloats(float[] values, DataType dt, ByteOrder order) {
        int es = dt.byteCount();
        ByteBuffer bb = ByteBuffer.allocate(values.length * es).order(order);
        for (int i = 0; i < values.length; i++) {
            putNumber(bb, i * es, dt, es, values[i], (long) values[i]);
        }
        return bb.array();
    }

    public static byte[] fromLongs(long[] values, DataType dt, ByteOrder order) {
        int es = dt.byteCount();
        ByteBuffer bb = ByteBuffer.allocate(values.length * es).order(order);
        for (int i = 0; i < values.length; i++) {
            putNumber(bb, i * es, dt, es, values[i], values[i]);
        }
        return bb.array();
    }

    public static byte[] fromInts(int[] values, DataType dt, ByteOrder order) {
        int es = dt.byteCount();
        ByteBuffer bb = ByteBuffer.allocate(values.length * es).order(order);
        for (int i = 0; i < values.length; i++) {
            putNumber(bb, i * es, dt, es, values[i], values[i]);
        }
        return bb.array();
    }

    /** Writes one element, narrowing as the data type requires. */
    private static void putNumber(ByteBuffer bb, int off, DataType dt, int es, double asDouble, long asLong) {
        switch (dt.kind()) {
            case BOOL -> bb.put(off, (byte) (asLong != 0 ? 1 : 0));
            case INT, UINT -> {
                switch (es) {
                    case 1 -> bb.put(off, (byte) asLong);
                    case 2 -> bb.putShort(off, (short) asLong);
                    case 4 -> bb.putInt(off, (int) asLong);
                    case 8 -> bb.putLong(off, asLong);
                    default -> throw new IllegalStateException("integer size " + es);
                }
            }
            case FLOAT -> {
                switch (es) {
                    case 2 -> bb.putShort(off, Float.floatToFloat16((float) asDouble));
                    case 4 -> bb.putFloat(off, (float) asDouble);
                    case 8 -> bb.putDouble(off, asDouble);
                    default -> throw new IllegalStateException("float size " + es);
                }
            }
            case COMPLEX, RAW -> throw new ZarrException(
                    dt.name() + " cannot be written from a primitive array; use the raw element bytes");
        }
    }

    private static long signed(ByteBuffer bb, int off, int es) {
        return switch (es) {
            case 1 -> bb.get(off);
            case 2 -> bb.getShort(off);
            case 4 -> bb.getInt(off);
            case 8 -> bb.getLong(off);
            default -> throw new IllegalStateException("integer size " + es);
        };
    }

    private static long unsigned(ByteBuffer bb, int off, int es) {
        return switch (es) {
            case 1 -> bb.get(off) & 0xffL;
            case 2 -> bb.getShort(off) & 0xffffL;
            case 4 -> bb.getInt(off) & 0xffffffffL;
            default -> throw new IllegalStateException("unsigned size " + es);
        };
    }

    private static double floatValue(ByteBuffer bb, int off, int es) {
        return switch (es) {
            case 2 -> Float.float16ToFloat(bb.getShort(off));
            case 4 -> bb.getFloat(off);
            case 8 -> bb.getDouble(off);
            default -> throw new IllegalStateException("float size " + es);
        };
    }

    /** Converts an unsigned 64-bit value (held in a {@code long}) to the nearest double. */
    private static double unsignedToDouble(long bits) {
        return bits >= 0 ? (double) bits : ((double) (bits >>> 1)) * 2.0 + (bits & 1);
    }

    private static ZarrException cannotRead(DataType dt, String as) {
        return new ZarrException(dt.name() + " cannot be read as " + as + "; use the raw element bytes");
    }
}
