package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.data.Float16;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteOrder;

/**
 * A NumPy numeric dtype as numcodecs' filters name it ({@code "<i4"}, {@code ">f8"}, {@code "|u1"},
 * {@code "|b1"}), with the arithmetic those filters do in it: element access, NumPy's casts, its type
 * promotion, and its arithmetic, which rounds every operation to the type (a {@code float16} operation is
 * done in {@code float} and rounded to half, as NumPy's half loops do).
 *
 * <p>An element travels as its raw bits in a {@code long}: an integer sign- or zero-extended (a
 * {@code uint64} as its two's-complement bits), a float as its IEEE&nbsp;754 bits, a bool as 0 or 1.
 *
 * <p>Where NumPy's result is undefined (a NaN or out-of-range float cast to an integer), this converts to
 * a {@code long} as Java does (NaN to 0, saturating) and wraps that to the width.
 *
 * @param kind  {@code 'b'} (bool), {@code 'i'} (signed), {@code 'u'} (unsigned), or {@code 'f'} (float)
 * @param size  the element size in bytes
 * @param order the byte order of the stored elements
 */
record NumpyType(char kind, int size, ByteOrder order) {

    /** The bytes numcodecs sees when a filter gets a plain buffer ({@code "|u1"}). */
    static final NumpyType U1 = new NumpyType('u', 1, ByteOrder.LITTLE_ENDIAN);

    private static final NumpyType F8 = new NumpyType('f', 8, ByteOrder.LITTLE_ENDIAN);

    /**
     * Parses a NumPy dtype string: a byte order ({@code <}, {@code >}, or {@code |} for a single byte), a
     * kind ({@code b}, {@code i}, {@code u}, {@code f}), and a size (1, 2, 4, or 8; a bool 1; a float 2, 4,
     * or 8).
     *
     * @throws ZarrUnsupportedException if it is not such a string (complex, strings, times, records, ...)
     */
    static NumpyType parse(String dtype, String what) {
        if (dtype.length() < 3) {
            throw unsupported(dtype, what);
        }
        char kind = dtype.charAt(1);
        int size;
        try {
            size = Integer.parseInt(dtype.substring(2));
        } catch (NumberFormatException e) {
            throw unsupported(dtype, what);
        }
        boolean valid = switch (kind) {
            case 'b' -> size == 1;
            case 'i', 'u' -> size == 1 || size == 2 || size == 4 || size == 8;
            case 'f' -> size == 2 || size == 4 || size == 8;
            default -> false;
        };
        ByteOrder order = switch (dtype.charAt(0)) {
            case '<' -> ByteOrder.LITTLE_ENDIAN;
            case '>' -> ByteOrder.BIG_ENDIAN;
            case '|' -> size == 1 ? ByteOrder.LITTLE_ENDIAN : null;
            default -> null;
        };
        if (!valid || order == null) {
            throw unsupported(dtype, what);
        }
        return new NumpyType(kind, size, order);
    }

    private static ZarrUnsupportedException unsupported(String dtype, String what) {
        return new ZarrUnsupportedException(what + ": dtype '" + dtype
                + "' is not supported (a numeric NumPy dtype such as '<i4', '>f8', or '|u1' is)");
    }

    /**
     * The NumPy type of a Zarr data type's elements in {@code order}, or {@code null} for a type that is
     * not a NumPy bool, integer, or float (complex, strings, times, structs, raw bytes).
     */
    static NumpyType of(DataType dataType, ByteOrder order) {
        return switch (dataType.kind()) {
            case BOOL -> new NumpyType('b', 1, ByteOrder.LITTLE_ENDIAN);
            case INT -> new NumpyType('i', dataType.byteCount(), order);
            case UINT -> new NumpyType('u', dataType.byteCount(), order);
            case FLOAT -> new NumpyType('f', dataType.byteCount(), order);
            default -> null;
        };
    }

    boolean isFloat() {
        return kind == 'f';
    }

    boolean isInteger() {
        return kind == 'i' || kind == 'u';
    }

    /** The dtype string, as numcodecs writes it ({@code "<i4"}, {@code "|u1"}). */
    @Override
    public String toString() {
        char o = size == 1 ? '|' : order == ByteOrder.BIG_ENDIAN ? '>' : '<';
        return "" + o + kind + size;
    }

    /** NumPy's {@code np.promote_types} of two numeric types, in little-endian order. */
    static NumpyType promote(NumpyType a, NumpyType b) {
        if (a.kind == b.kind) {
            return new NumpyType(a.kind, Math.max(a.size, b.size), ByteOrder.LITTLE_ENDIAN);
        }
        if (a.kind == 'b') {
            return new NumpyType(b.kind, b.size, ByteOrder.LITTLE_ENDIAN);
        }
        if (b.kind == 'b') {
            return new NumpyType(a.kind, a.size, ByteOrder.LITTLE_ENDIAN);
        }
        if (a.kind == 'f' || b.kind == 'f') {
            NumpyType f = a.kind == 'f' ? a : b;
            NumpyType n = a.kind == 'f' ? b : a;
            // The smallest float holding every value of the integer: half 1-byte, float 2-byte, else double.
            int needed = n.size == 1 ? 2 : n.size == 2 ? 4 : 8;
            return new NumpyType('f', Math.max(f.size, needed), ByteOrder.LITTLE_ENDIAN);
        }
        NumpyType s = a.kind == 'i' ? a : b;
        NumpyType u = a.kind == 'u' ? a : b;
        if (s.size > u.size) {
            return new NumpyType('i', s.size, ByteOrder.LITTLE_ENDIAN);
        }
        return u.size == 8 ? F8 : new NumpyType('i', 2 * u.size, ByteOrder.LITTLE_ENDIAN);
    }

    // ---- element access ------------------------------------------------------------------------------

    /** Element {@code index} of {@code buffer}: an integer extended to 64 bits, a float's bits, a bool. */
    long get(byte[] buffer, int index) {
        int off = index * size;
        long raw = 0;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = size - 1; i >= 0; i--) {
                raw = (raw << 8) | (buffer[off + i] & 0xffL);
            }
        } else {
            for (int i = 0; i < size; i++) {
                raw = (raw << 8) | (buffer[off + i] & 0xffL);
            }
        }
        return kind == 'i' && size < 8 ? (raw << (64 - 8 * size)) >> (64 - 8 * size) : raw;
    }

    /** Stores {@code bits} (this type's, as {@link #get} gives them) as element {@code index}. */
    void put(byte[] buffer, int index, long bits) {
        int off = index * size;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int i = 0; i < size; i++) {
                buffer[off + i] = (byte) (bits >>> (8 * i));
            }
        } else {
            for (int i = 0; i < size; i++) {
                buffer[off + i] = (byte) (bits >>> (8 * (size - 1 - i)));
            }
        }
    }

    /** A float element's value. */
    double toDouble(long bits) {
        return switch (size) {
            case 2 -> halfToFloat((int) bits & 0xffff);
            case 4 -> Float.intBitsToFloat((int) bits);
            default -> Double.longBitsToDouble(bits);
        };
    }

    /** The bits of {@code value} rounded to this float type (to nearest, ties to even, once). */
    long fromDouble(double value) {
        return switch (size) {
            case 2 -> doubleToHalf(value);
            case 4 -> Float.floatToRawIntBits((float) value) & 0xffffffffL;
            default -> Double.doubleToRawLongBits(value);
        };
    }

    /**
     * A half's value as a float, as NumPy's {@code npy_halfbits_to_floatbits} gives it: exact, and a NaN keeps
     * its sign and payload.
     */
    private static float halfToFloat(int half) {
        if ((half & 0x7c00) == 0x7c00 && (half & 0x03ff) != 0) {
            return Float.intBitsToFloat((half & 0x8000) << 16 | 0x7f800000 | (half & 0x03ff) << 13);
        }
        return Float.float16ToFloat((short) half);
    }

    /**
     * The half nearest {@code value}, rounded once ({@link Float16#fromDouble}); a NaN keeps its sign and the
     * top of its payload, as NumPy's {@code npy_doublebits_to_halfbits} keeps them.
     */
    private static long doubleToHalf(double value) {
        if (Double.isNaN(value)) {
            long bits = Double.doubleToRawLongBits(value);
            int half = 0x7c00 + (int) ((bits & 0x000fffffffffffffL) >>> 42);
            if (half == 0x7c00) {
                half++;
            }
            return (bits >>> 48 & 0x8000) | half;
        }
        return Float16.fromDouble(value) & 0xffffL;
    }

    /** An integer element's value as a double, rounded once (a {@code uint64} unsigned). */
    double integerToDouble(long value) {
        if (kind == 'u' && size == 8 && value < 0) {
            double half = (double) ((value >>> 1) | (value & 1)); // keep a sticky bit so the rounding is once
            return half * 2;
        }
        return (double) value;
    }

    /** The bits of {@code value} wrapped to this integer type (sign- or zero-extended). */
    long wrap(long value) {
        if (size == 8) {
            return value;
        }
        int shift = 64 - 8 * size;
        return kind == 'i' ? (value << shift) >> shift : (value << shift) >>> shift;
    }

    /** Whether the integer {@code value} of type {@code from} is a value of this integer type. */
    boolean holds(long value, NumpyType from) {
        boolean negative = from.kind == 'i' && value < 0;
        boolean huge = from.kind == 'u' && from.size == 8 && value < 0; // 2^63 and up
        if (kind == 'u') {
            return !negative && (size == 8 || (!huge && value >>> (8 * size) == 0));
        }
        if (huge) {
            return false;
        }
        return size == 8 || (value >= -(1L << (8 * size - 1)) && value < (1L << (8 * size - 1)));
    }

    // ---- casts (ndarray.astype, unsafe) --------------------------------------------------------------

    /** {@code bits} of type {@code from} cast to this type, as {@code ndarray.astype} casts. */
    long cast(long bits, NumpyType from) {
        if (from.kind == 'f') {
            double v = from.toDouble(bits);
            return switch (kind) {
                case 'f' -> fromDouble(v);
                case 'b' -> v != 0 || Double.isNaN(v) ? 1 : 0;
                default -> wrap(floatToInteger(v));
            };
        }
        // an integer, or a bool (0 or 1)
        return switch (kind) {
            case 'f' -> fromDouble(from.integerToDouble(bits));
            case 'b' -> bits != 0 ? 1 : 0;
            default -> wrap(bits);
        };
    }

    /**
     * A float truncated toward zero as a 64-bit integer, before wrapping to this type: a {@code uint64}
     * takes 2^63 to 2^64 too. NaN and out-of-range values are undefined in NumPy (see the class comment).
     */
    private long floatToInteger(double v) {
        if (kind == 'u' && size == 8 && v >= 0x1p63 && v < 0x1p64) {
            return (long) (v - 0x1p63) ^ Long.MIN_VALUE;
        }
        return (long) v;
    }

    // ---- arithmetic in this type ---------------------------------------------------------------------

    /** {@code a + b} in this type: an integer wraps, a float rounds. */
    long add(long a, long b) {
        return switch (kind) {
            case 'f' -> floatOp(a, b, '+');
            case 'b' -> (a | b) & 1;
            default -> wrap(a + b);
        };
    }

    /** {@code a - b} in this type: an integer wraps, a float rounds. */
    long subtract(long a, long b) {
        return kind == 'f' ? floatOp(a, b, '-') : wrap(a - b);
    }

    /** {@code a * b} in this type: an integer wraps, a float rounds. */
    long multiply(long a, long b) {
        return kind == 'f' ? floatOp(a, b, '*') : wrap(a * b);
    }

    /** {@code a / b} in this float type (true division). */
    long divide(long a, long b) {
        return floatOp(a, b, '/');
    }

    /** {@code np.rint} in this float type: to the nearest integer, ties to even; exact. */
    long rint(long a) {
        double value = toDouble(a);
        return Double.isNaN(value) ? a : fromDouble(Math.rint(value)); // Math.rint drops a NaN's sign
    }

    /**
     * One float operation as NumPy does it: a double in double, a float in float, a half in float rounded
     * to half (each of these rounds the exact result once, but a half rounds twice, as NumPy's does).
     */
    private long floatOp(long a, long b, char op) {
        if (size == 8) {
            double x = toDouble(a);
            double y = toDouble(b);
            return fromDouble(switch (op) {
                case '+' -> x + y;
                case '-' -> x - y;
                case '*' -> x * y;
                default -> x / y;
            });
        }
        float x = (float) toDouble(a);
        float y = (float) toDouble(b);
        float r = switch (op) {
            case '+' -> x + y;
            case '-' -> x - y;
            case '*' -> x * y;
            default -> x / y;
        };
        return size == 4 ? Float.floatToRawIntBits(r) & 0xffffffffL : doubleToHalf(r);
    }

    /** {@code float64}, the type NumPy gives an integer array divided, or combined with a Python float. */
    static NumpyType float64() {
        return F8;
    }
}
