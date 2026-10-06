package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * One direction of a {@code cast_value} codec: elements of one number type converted by value to another, with
 * a rounding mode, an out-of-range mode, and a scalar map, exactly as cast-value-rs 0.4.2 (the Rust backend
 * zarr-python's {@code CastValue} calls) converts them, bit for bit.
 *
 * <p>Each element goes through the scalar map first (the first entry whose key equals it wins, a NaN key
 * matching any NaN), then one of four paths:
 * <ul>
 *   <li><b>integer &rarr; integer</b>: in range, the value; out of range, the bound ({@code clamp}), the low
 *       bits ({@code wrap}, modulo 2<sup>N</sup>), or an error;</li>
 *   <li><b>float &rarr; integer</b>: a NaN is an error; the value is rounded in the source precision, then
 *       compared with the target's bounds <em>as the source type holds them</em> (so {@code 2^31} as a float32
 *       is no more than int32's maximum, and saturates to it), and clamped, wrapped by IEEE remainder in the
 *       source precision ({@code wrap} refuses an infinity), or refused;</li>
 *   <li><b>float &rarr; float</b>: NaN propagates (quieted, as x86 converts it) and signed zero is kept; the
 *       value is rounded to nearest even and, under another rounding mode, moved one step when that was the
 *       wrong side. A finite value that becomes infinite is an error unless {@code clamp}. float64 reaches
 *       float16 through float32, rounding twice, as cast-value-rs does;</li>
 *   <li><b>integer &rarr; float</b>: rounded to nearest even and adjusted the same way, the comparison made
 *       against the integer as a float64 (so int64 and uint64 are rounded to nearest even whatever the mode
 *       when the target is float64); never out of range (a float16 overflows to infinity).</li>
 * </ul>
 *
 * <p>Elements are flat buffers in one byte order; a value is carried as a {@code long}: an integer
 * sign-extended (or zero-extended, uint64 as its bits), a float as its bits.
 */
final class ValueCast {

    /** A number type {@code cast_value} converts. */
    enum Num {
        INT8(1, false, true), INT16(2, false, true), INT32(4, false, true), INT64(8, false, true),
        UINT8(1, false, false), UINT16(2, false, false), UINT32(4, false, false), UINT64(8, false, false),
        FLOAT16(2, true, true), FLOAT32(4, true, true), FLOAT64(8, true, true);

        final int size;
        final boolean isFloat;
        final boolean signed;

        Num(int size, boolean isFloat, boolean signed) {
            this.size = size;
            this.isFloat = isFloat;
            this.signed = signed;
        }

        /** The {@code Num} of a data type, or {@code null} if it is not an integer or float type. */
        static Num of(DataType dataType) {
            return switch (dataType.kind()) {
                case INT -> switch (dataType.byteCount()) {
                    case 1 -> INT8;
                    case 2 -> INT16;
                    case 4 -> INT32;
                    default -> INT64;
                };
                case UINT -> switch (dataType.byteCount()) {
                    case 1 -> UINT8;
                    case 2 -> UINT16;
                    case 4 -> UINT32;
                    default -> UINT64;
                };
                case FLOAT -> switch (dataType.byteCount()) {
                    case 2 -> FLOAT16;
                    case 4 -> FLOAT32;
                    default -> FLOAT64;
                };
                default -> null;
            };
        }

        /** An integer type's smallest value. */
        long min() {
            return signed ? -1L << (8 * size - 1) : 0;
        }

        /** An integer type's largest value; uint64's, 2<sup>64</sup> - 1, as its bits (-1). */
        long max() {
            return this == UINT64 ? -1L : signed ? ~(-1L << (8 * size - 1)) : ~(-1L << (8 * size));
        }

        /** The name, as a Zarr data type. */
        String typeName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The {@code rounding} modes, by their configuration names. */
    enum Rounding {
        NEAREST_EVEN("nearest-even"), TOWARDS_ZERO("towards-zero"), TOWARDS_POSITIVE("towards-positive"),
        TOWARDS_NEGATIVE("towards-negative"), NEAREST_AWAY("nearest-away");

        final String json;

        Rounding(String json) {
            this.json = json;
        }

        /** The mode a configuration names, or {@code null} for an unknown name. */
        static Rounding of(String name) {
            for (Rounding r : values()) {
                if (r.json.equals(name)) {
                    return r;
                }
            }
            return null;
        }
    }

    /** The {@code out_of_range} modes; {@code null} stands for none (an out-of-range value is an error). */
    enum OutOfRange {
        CLAMP("clamp"), WRAP("wrap");

        final String json;

        OutOfRange(String json) {
            this.json = json;
        }

        /** The mode a configuration names, or {@code null} for an unknown name. */
        static OutOfRange of(String name) {
            for (OutOfRange o : values()) {
                if (o.json.equals(name)) {
                    return o;
                }
            }
            return null;
        }
    }

    /**
     * A value that cannot be cast. {@link #nanOrInfinity} tells cast-value-rs' two errors apart: a NaN or an
     * infinity an integer cannot hold, or a value out of range with no {@code out_of_range} mode.
     */
    static final class CastException extends ZarrFormatException {

        private static final long serialVersionUID = 1L;

        /** Whether the value was a NaN or an infinity (else it was out of range). */
        final boolean nanOrInfinity;

        CastException(String message, boolean nanOrInfinity) {
            super(message);
            this.nanOrInfinity = nanOrInfinity;
        }
    }

    /** One scalar map entry: a key in the source type and its value in the target type, both as carried. */
    record Entry(long key, long value) {
    }

    private static final double TWO_63 = 0x1p63;
    private static final double TWO_64 = 0x1p64;

    private final Num src;
    private final Num dst;
    private final Rounding rounding;
    private final OutOfRange outOfRange;
    private final long[] keys;
    private final long[] values;
    // float -> integer: the target's bounds as the source type holds them (cast-value-rs' dst_min/dst_max)
    private final double lo;
    private final double hi;

    ValueCast(Num src, Num dst, Rounding rounding, OutOfRange outOfRange, List<Entry> entries) {
        this.src = src;
        this.dst = dst;
        this.rounding = rounding;
        this.outOfRange = outOfRange;
        this.keys = new long[entries.size()];
        this.values = new long[entries.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = entries.get(i).key();
            values[i] = entries.get(i).value();
        }
        double low = 0;
        double high = 0;
        if (src.isFloat && !dst.isFloat) {
            double min = dst.min(); // exact: every integer type's minimum is 0 or a power of two
            // the maximum as Rust's `as` makes it, rounded to nearest: int32's 2^31 - 1 is 2^31 as a float32
            double max = dst == Num.UINT64 ? TWO_64 : (double) dst.max();
            float maxFloat = dst == Num.UINT64 ? (float) TWO_64 : (float) dst.max();
            switch (src) {
                case FLOAT64 -> {
                    low = min;
                    high = max;
                }
                case FLOAT32 -> {
                    low = (float) min;
                    high = maxFloat;
                }
                default -> { // FLOAT16: f16::from_f32(<dst>::MIN as f32), which may be an infinity
                    low = f16((float) min);
                    high = f16(maxFloat);
                }
            }
        }
        this.lo = low;
        this.hi = high;
    }

    /** The target type. */
    Num target() {
        return dst;
    }

    /**
     * Converts {@code count} elements of {@code input} (the source type, in {@code order}) into a new buffer of
     * the target type in the same order.
     *
     * @throws CastException at the first element that cannot be cast
     */
    byte[] convert(byte[] input, int count, ByteOrder order) {
        byte[] out = new byte[count * dst.size];
        ByteBuffer in = ByteBuffer.wrap(input).order(order);
        ByteBuffer o = ByteBuffer.wrap(out).order(order);
        for (int i = 0; i < count; i++) {
            put(o, i, cast(get(in, i, src)));
        }
        return out;
    }

    private static long get(ByteBuffer b, int i, Num type) {
        return switch (type) {
            case INT8 -> b.get(i);
            case UINT8 -> b.get(i) & 0xffL;
            case INT16 -> b.getShort(2 * i);
            case UINT16, FLOAT16 -> b.getShort(2 * i) & 0xffffL;
            case INT32 -> b.getInt(4 * i);
            case UINT32, FLOAT32 -> b.getInt(4 * i) & 0xffffffffL;
            default -> b.getLong(8 * i);
        };
    }

    private void put(ByteBuffer b, int i, long v) {
        switch (dst.size) {
            case 1 -> b.put(i, (byte) v);
            case 2 -> b.putShort(2 * i, (short) v);
            case 4 -> b.putInt(4 * i, (int) v);
            default -> b.putLong(8 * i, v);
        }
    }

    /**
     * Casts one value (carried as described in the class comment) and returns the result, carried the same way
     * for the target type.
     *
     * @throws CastException if the value cannot be cast
     */
    long cast(long v) {
        for (int i = 0; i < keys.length; i++) {
            if (matches(v, keys[i])) {
                return values[i];
            }
        }
        if (src.isFloat) {
            return dst.isFloat ? floatToFloat(v) : floatToInt(v);
        }
        return dst.isFloat ? intToFloat(v) : intToInt(v);
    }

    /** Whether {@code v} equals the map key {@code key}: by value, a NaN key matching any NaN. */
    private boolean matches(long v, long key) {
        if (!src.isFloat) {
            return v == key;
        }
        double k = toDouble(src, key);
        double x = toDouble(src, v);
        return Double.isNaN(k) ? Double.isNaN(x) : x == k;
    }

    // ---- integer -> integer --------------------------------------------------------------------------

    private long intToInt(long v) {
        boolean srcU64 = src == Num.UINT64;
        boolean below = !srcU64 && v < dst.min();
        boolean above = dst != Num.UINT64 && (srcU64 ? Long.compareUnsigned(v, dst.max()) > 0 : v > dst.max());
        if (outOfRange == OutOfRange.CLAMP) {
            return below ? dst.min() : above ? dst.max() : v;
        }
        if ((below || above) && outOfRange == null) {
            throw outOfRange(intText(v, src), dst);
        }
        return v; // in range, or wrapped: the target keeps the low bits
    }

    // ---- integer -> float ----------------------------------------------------------------------------

    private long intToFloat(long v) {
        boolean u64 = src == Num.UINT64;
        double asDouble = u64 ? unsignedToDouble(v) : (double) v; // cast-value-rs compares against this
        switch (dst) {
            case FLOAT64 -> {
                return Double.doubleToRawLongBits(asDouble); // the same rounding: never adjusted
            }
            case FLOAT32 -> {
                float r = u64 ? unsignedToFloat(v) : (float) v;
                if (rounding != Rounding.NEAREST_EVEN && asDouble != r) {
                    r = adjust32(r, asDouble);
                }
                return Float.floatToRawIntBits(r) & 0xffffffffL;
            }
            default -> {
                // f16::from_f32(v as f32): rounded twice; beyond float16's range only an infinity results
                short r = Float.floatToFloat16(u64 ? unsignedToFloat(v) : (float) v);
                if (rounding != Rounding.NEAREST_EVEN && asDouble != f16ToDouble(r)) {
                    r = adjust16(r, asDouble);
                }
                return r & 0xffffL;
            }
        }
    }

    // ---- float -> float ------------------------------------------------------------------------------

    private long floatToFloat(long bits) {
        double value = toDouble(src, bits);
        long result = narrowOrWiden(bits);
        if (!Double.isNaN(value) && rounding != Rounding.NEAREST_EVEN) {
            double r = toDouble(dst, result);
            if (r != value) {
                result = switch (dst) {
                    case FLOAT32 -> Float.floatToRawIntBits(adjust32(Float.intBitsToFloat((int) result), value))
                            & 0xffffffffL;
                    case FLOAT16 -> adjust16((short) result, value) & 0xffffL;
                    default -> result; // a float64 target holds every source value exactly
                };
            }
        }
        if (Double.isFinite(value) && Double.isInfinite(toDouble(dst, result)) && outOfRange != OutOfRange.CLAMP) {
            // clamp maps a value beyond the finite range to the infinity it became; wrap is no mode for a float
            throw new CastException("cast_value: " + floatText(src, bits) + " is out of range for " + dst.typeName()
                    + " (set out_of_range to 'clamp' to map it to infinity)", false);
        }
        return result;
    }

    /** The source float's bits as the target float, rounded to nearest even, NaN quieted as x86 converts it. */
    private long narrowOrWiden(long bits) {
        if (src == dst) {
            return bits;
        }
        boolean nan = Double.isNaN(toDouble(src, bits));
        return switch (src) {
            case FLOAT64 -> {
                double d = Double.longBitsToDouble(bits);
                int f = nan ? nan64to32(bits) : Float.floatToRawIntBits((float) d);
                yield dst == Num.FLOAT32 ? f & 0xffffffffL
                        : (nan ? nan32to16(f) : Float.floatToFloat16(Float.intBitsToFloat(f))) & 0xffffL;
            }
            case FLOAT32 -> {
                int f = (int) bits;
                if (dst == Num.FLOAT64) {
                    yield nan ? nan32to64(f) : Double.doubleToRawLongBits(Float.intBitsToFloat(f));
                }
                yield (nan ? nan32to16(f) : Float.floatToFloat16(Float.intBitsToFloat(f))) & 0xffffL;
            }
            default -> {
                int f = nan ? nan16to32((short) bits) : Float.floatToRawIntBits(Float.float16ToFloat((short) bits));
                yield dst == Num.FLOAT32 ? f & 0xffffffffL
                        : nan ? nan32to64(f) : Double.doubleToRawLongBits(Float.intBitsToFloat(f));
            }
        };
    }

    // ---- float -> integer ----------------------------------------------------------------------------

    private long floatToInt(long bits) {
        double value = toDouble(src, bits);
        if (Double.isNaN(value)) {
            throw nanOrInfinity(floatText(src, bits));
        }
        double v = round(value);
        if (outOfRange == OutOfRange.CLAMP) {
            return saturate(v < lo ? lo : v > hi ? hi : v);
        }
        if (outOfRange == OutOfRange.WRAP) {
            if (Double.isInfinite(v)) {
                throw nanOrInfinity(floatText(src, bits));
            }
            return saturate(v < lo || v > hi ? wrap(v) : v);
        }
        if (v < lo || v > hi) {
            throw outOfRange(floatText(src, bits), dst);
        }
        return saturate(v);
    }

    /**
     * Rounds to an integer in the source precision. Only nearest-away computes anything inexact: Rust's
     * {@code copysign(floor(|x| + 0.5), x)}, whose sum rounds in the source type (float32 for a float16, where it
     * is exact), so {@code 0.49999999999999994} rounds to 1 and a float32 {@code 2^23 + 1} to {@code 2^23 + 2}.
     * The other modes give an integer the source type holds, whatever precision computes it.
     */
    private double round(double v) {
        return switch (rounding) {
            case NEAREST_EVEN -> Math.rint(v);
            case TOWARDS_ZERO -> v < 0 ? Math.ceil(v) : Math.floor(v);
            case TOWARDS_POSITIVE -> Math.ceil(v);
            case TOWARDS_NEGATIVE -> Math.floor(v);
            case NEAREST_AWAY -> src == Num.FLOAT64 ? Math.copySign(Math.floor(Math.abs(v) + 0.5), v)
                    : Math.copySign((float) Math.floor(Math.abs((float) v) + 0.5f), (float) v);
        };
    }

    /**
     * cast-value-rs' wrap of a value beyond the bounds: {@code ((v - lo) rem_euclid (hi - lo + 1)) + lo}, every
     * operation rounded to the source type (a float16's through float32).
     */
    private double wrap(double v) {
        switch (src) {
            case FLOAT64 -> {
                double range = hi - lo + 1.0;
                return remEuclid(v - lo, range) + lo;
            }
            case FLOAT32 -> {
                float l = (float) lo;
                float range = (float) hi - l + 1f;
                float r = (float) v - l;
                float m = r % range;
                if (m < 0) {
                    m += Math.abs(range);
                }
                return m + l;
            }
            default -> {
                float l = (float) lo;
                float range = f16(f16((float) hi - l) + 1f);
                float r = f16((float) v - l);
                float m = r % range;
                if (m < 0) {
                    m += Math.abs(range);
                }
                return f16(f16(m) + l);
            }
        }
    }

    private static double remEuclid(double a, double b) {
        double r = a % b;
        return r < 0 ? r + Math.abs(b) : r;
    }

    /** Rust's saturating {@code as} from a float to the integer target: toward zero, NaN to 0. */
    private long saturate(double d) {
        if (Double.isNaN(d)) {
            return 0;
        }
        return switch (dst) {
            case INT64 -> (long) d;
            case UINT64 -> d >= TWO_64 ? -1L : d < 1.0 ? 0 : d >= TWO_63 ? (long) (d - TWO_63) | Long.MIN_VALUE
                    : (long) d;
            default -> Math.max(dst.min(), Math.min(dst.max(), (long) d));
        };
    }

    // ---- rounding adjustment (integer -> float, float -> float) ---------------------------------------

    /** cast-value-rs' step from the nearest-even result {@code r} toward the requested rounding of {@code v}. */
    private float adjust32(float r, double v) {
        double rd = r;
        return switch (rounding) {
            case NEAREST_EVEN -> r;
            case TOWARDS_ZERO -> Math.abs(rd) > Math.abs(v) ? (v >= 0 ? Math.nextDown(r) : Math.nextUp(r)) : r;
            case TOWARDS_POSITIVE -> rd < v ? Math.nextUp(r) : r;
            case TOWARDS_NEGATIVE -> rd > v ? Math.nextDown(r) : r;
            case NEAREST_AWAY -> {
                float candidate = v > rd ? Math.nextUp(r) : Math.nextDown(r);
                double mid = (rd + candidate) / 2.0;
                yield mid == v && (double) Math.abs(candidate) > Math.abs(rd) ? candidate : r;
            }
        };
    }

    /** {@link #adjust32} for a float16 result, stepping through cast-value-rs' own float16 nextUp/nextDown. */
    private short adjust16(short r, double v) {
        double rd = f16ToDouble(r);
        return switch (rounding) {
            case NEAREST_EVEN -> r;
            case TOWARDS_ZERO -> Math.abs(rd) > Math.abs(v) ? (v >= 0 ? nextDown16(r) : nextUp16(r)) : r;
            case TOWARDS_POSITIVE -> rd < v ? nextUp16(r) : r;
            case TOWARDS_NEGATIVE -> rd > v ? nextDown16(r) : r;
            case NEAREST_AWAY -> {
                short candidate = v > rd ? nextUp16(r) : nextDown16(r);
                double mid = (rd + f16ToDouble(candidate)) / 2.0;
                yield mid == v && f16ToDouble((short) (candidate & 0x7fff)) > f16ToDouble((short) (r & 0x7fff))
                        ? candidate : r;
            }
        };
    }

    /** cast-value-rs' float16 nextUp, on the bits (NaN unchanged, -0 to the smallest subnormal). */
    private static short nextUp16(short h) {
        int bits = h & 0xffff;
        if ((bits & 0x7c00) == 0x7c00 && (bits & 0x03ff) != 0) {
            return h;
        }
        if (bits == 0x8000) {
            return 0x0001;
        }
        return (short) ((bits & 0x8000) == 0 ? bits + 1 : bits - 1);
    }

    /** nextDown(x) = -nextUp(-x). */
    private static short nextDown16(short h) {
        return (short) (nextUp16((short) (h ^ 0x8000)) ^ 0x8000);
    }

    // ---- floats and their bits -----------------------------------------------------------------------

    /** A float's value as a double (exact), from its bits. */
    static double toDouble(Num type, long bits) {
        return switch (type) {
            case FLOAT64 -> Double.longBitsToDouble(bits);
            case FLOAT32 -> Float.intBitsToFloat((int) bits);
            default -> f16ToDouble((short) bits);
        };
    }

    private static double f16ToDouble(short h) {
        return Float.float16ToFloat(h);
    }

    /** {@code x} rounded to float16 and back. */
    private static float f16(float x) {
        return Float.float16ToFloat(Float.floatToFloat16(x));
    }

    /** A float64 NaN as float32, as x86's conversion makes it: quieted, the top of the payload kept. */
    static int nan64to32(long bits) {
        return (int) ((bits >>> 32) & 0x80000000L) | 0x7fc00000 | (int) ((bits >>> 29) & 0x3fffff);
    }

    /** A float32 NaN as float64: quieted, the payload kept. */
    static long nan32to64(int bits) {
        return ((bits & 0x80000000L) << 32) | 0x7ff8000000000000L | ((bits & 0x7fffffL) << 29);
    }

    /** A float32 NaN as float16 (F16C and the half crate's fallback): quieted, the top of the payload kept. */
    static short nan32to16(int bits) {
        return (short) (((bits >>> 16) & 0x8000) | 0x7e00 | ((bits & 0x7fffff) >>> 13));
    }

    /** A float16 NaN as float32: quieted, the payload kept. */
    static int nan16to32(short h) {
        return ((h & 0x8000) << 16) | 0x7fc00000 | ((h & 0x3ff) << 13);
    }

    /** A uint64's bits as the nearest double (ties to even). */
    static double unsignedToDouble(long v) {
        return v >= 0 ? (double) v : ((double) ((v >>> 1) | (v & 1))) * 2.0;
    }

    /** A uint64's bits as the nearest float (ties to even). */
    static float unsignedToFloat(long v) {
        return v >= 0 ? (float) v : ((float) ((v >>> 1) | (v & 1))) * 2f;
    }

    // ---- errors --------------------------------------------------------------------------------------

    private CastException nanOrInfinity(String value) {
        return new CastException("cast_value: " + value + " cannot be cast to " + dst.typeName()
                + (outOfRange == OutOfRange.WRAP ? " (wrap needs a finite value)" : "")
                + "; map it with scalar_map", true);
    }

    private CastException outOfRange(String value, Num target) {
        return new CastException("cast_value: " + value + " is out of range for " + target.typeName() + " ["
                + intText(target.min(), target) + ", " + intText(target.max(), target)
                + "] (set out_of_range to 'clamp' or 'wrap', or map it with scalar_map)", false);
    }

    /** An integer value carried for {@code type}, as text. */
    static String intText(long v, Num type) {
        return type == Num.UINT64 ? Long.toUnsignedString(v) : Long.toString(v);
    }

    /** A float value carried for {@code type}, as text. */
    static String floatText(Num type, long bits) {
        double d = toDouble(type, bits);
        return type == Num.FLOAT64 ? Double.toString(d) : Float.toString((float) d);
    }
}
