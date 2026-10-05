package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Decodes raw element bytes into Java arrays for atomic datatypes, honouring each type's byte order
 * and precision. Multidimensional data is returned flattened in row-major order.
 */
public final class Elements {

    private Elements() {
    }

    /** A non-negative {@code long} that fits in an {@code int}, or {@link HdfFormatException} (corrupt input). */
    public static int checkedInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new HdfFormatException("value out of range (corrupt input?): " + value);
        }
        return (int) value;
    }

    /** {@code count * elementSize} as an {@code int}, rejecting overflow or out-of-range values (corrupt input). */
    public static int checkedByteCount(long count, int elementSize) {
        if (count < 0 || elementSize < 0) {
            throw new HdfFormatException("negative element count/size (corrupt input?): " + count + " x " + elementSize);
        }
        long bytes = count * elementSize;
        if ((elementSize != 0 && bytes / elementSize != count) || bytes > Integer.MAX_VALUE) {
            throw new HdfFormatException("dataset too large or corrupt: " + count + " x " + elementSize);
        }
        return (int) bytes;
    }

    private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort BE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /**
     * Every element as an {@code int}. Each value is the integer the element stores (its
     * {@code bitPrecision} bits at {@code bitOffset}, sign-extended if signed); a value outside the
     * {@code int} range, such as a {@code uint32} above 2<sup>31</sup>&minus;1, is an error rather than a
     * silently wrapped result.
     *
     * @throws HdfUnsupportedException if the datatype is not fixed-point or a value does not fit
     */
    public static int[] toInts(MemorySegment data, int count, Datatype type) {
        Datatype.FixedPoint fp = requireFixed(type, "readInts");
        int[] out = new int[count];
        int size = fp.size();
        if (isPlain(fp) && (size <= 2 || size == 4 && fp.signed())) {
            boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
            for (int i = 0; i < count; i++) {
                long off = (long) i * size;
                out[i] = switch (size) {
                    case 1 -> fp.signed() ? data.get(ValueLayout.JAVA_BYTE, off)
                                          : data.get(ValueLayout.JAVA_BYTE, off) & 0xff;
                    case 2 -> {
                        short s = data.get(le ? LE_SHORT : BE_SHORT, off);
                        yield fp.signed() ? s : s & 0xffff;
                    }
                    default -> data.get(le ? LE_INT : BE_INT, off);
                };
            }
            return out;
        }
        for (int i = 0; i < count; i++) {
            long value = integerAt(data, (long) i * size, fp);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE || (!fp.signed() && value < 0)) {
                throw new HdfUnsupportedException(describe(fp) + " value " + unsignedAware(value, fp)
                        + " (element " + i + ") does not fit in an int; use readLongs()");
            }
            out[i] = (int) value;
        }
        return out;
    }

    /**
     * Every element as a {@code long} (see {@link #toInts}). A {@code uint64} value of 2<sup>63</sup> or
     * more does not fit and is an error; read such data with {@link #toUnsignedBigIntegers}.
     *
     * @throws HdfUnsupportedException if the datatype is not fixed-point or a value does not fit
     */
    public static long[] toLongs(MemorySegment data, int count, Datatype type) {
        Datatype.FixedPoint fp = requireFixed(type, "readLongs");
        long[] out = new long[count];
        int size = fp.size();
        for (int i = 0; i < count; i++) {
            long value = integerAt(data, (long) i * size, fp);
            if (!fp.signed() && value < 0) {
                throw new HdfUnsupportedException(describe(fp) + " value " + Long.toUnsignedString(value)
                        + " (element " + i + ") does not fit in a long; use read() for BigInteger values");
            }
            out[i] = value;
        }
        return out;
    }

    /** Every element of a fixed-point datatype as a {@link BigInteger} (exact for {@code uint64}). */
    public static BigInteger[] toBigIntegers(MemorySegment data, int count, Datatype type) {
        Datatype.FixedPoint fp = requireFixed(type, "read");
        BigInteger[] out = new BigInteger[count];
        for (int i = 0; i < count; i++) {
            long value = integerAt(data, (long) i * fp.size(), fp);
            out[i] = value >= 0 || fp.signed() ? BigInteger.valueOf(value)
                    : BigInteger.valueOf(value).add(TWO_TO_THE_64);
        }
        return out;
    }

    /**
     * The most natural Java array for a fixed-point datatype's values: {@code int[]} for types of at
     * most 4 bytes whose every value fits in an {@code int} (so {@code uint32} is not one),
     * {@code long[]} when every value fits in a {@code long}, and {@code BigInteger[]} for {@code uint64}.
     */
    public static Object toNaturalIntegers(MemorySegment data, int count, Datatype.FixedPoint fp) {
        if (fp.size() <= 4 && fitsInt(fp)) {
            return toInts(data, count, fp);
        }
        return fitsLong(fp) ? toLongs(data, count, fp) : toBigIntegers(data, count, fp);
    }

    /** True if every value of {@code fp} fits in an {@code int}. */
    public static boolean fitsInt(Datatype.FixedPoint fp) {
        return fp.bitPrecision() <= (fp.signed() ? 32 : 31);
    }

    /** True if every value of {@code fp} fits in a {@code long}. */
    public static boolean fitsLong(Datatype.FixedPoint fp) {
        return fp.bitPrecision() <= (fp.signed() ? 64 : 63);
    }

    public static double[] toDoubles(MemorySegment data, int count, Datatype type) {
        Datatype.FloatingPoint fp = requireFloat(type, "readDoubles");
        int size = fp.size();
        double[] out = new double[count];
        boolean ieee = isIeee(fp);
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < count; i++) {
            long off = (long) i * size;
            out[i] = ieee ? readIeee(data, off, size, le) : decodeFloat(data, off, fp);
        }
        return out;
    }

    public static float[] toFloats(MemorySegment data, int count, Datatype type) {
        Datatype.FloatingPoint fp = requireFloat(type, "readFloats");
        int size = fp.size();
        float[] out = new float[count];
        boolean ieee = isIeee(fp);
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        for (int i = 0; i < count; i++) {
            long off = (long) i * size;
            out[i] = (float) (ieee ? readIeee(data, off, size, le) : decodeFloat(data, off, fp));
        }
        return out;
    }

    public static String[] toStrings(MemorySegment data, int count, Datatype type) {
        if (!(type instanceof Datatype.StringType st)) {
            throw new HdfUnsupportedException("readStrings requires a string datatype, not " + type.typeClass());
        }
        int size = st.size();
        var charset = st.characterSet() == Datatype.CharacterSet.UTF8
                ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII;
        boolean spacePad = st.padding() == Datatype.StringPadding.SPACE_PAD;
        String[] out = new String[count];
        byte[] element = new byte[size];
        for (int i = 0; i < count; i++) {
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, (long) i * size, element, 0, size);
            int length;
            if (spacePad) {
                length = size;
                while (length > 0 && element[length - 1] == ' ') {
                    length--;
                }
            } else {
                length = 0;
                while (length < size && element[length] != 0) {
                    length++;
                }
            }
            out[i] = new String(element, 0, length, charset);
        }
        return out;
    }

    public static byte[] toRawBytes(MemorySegment data, long byteCount) {
        byte[] out = new byte[checkedInt(byteCount)];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    private static final BigInteger TWO_TO_THE_64 = BigInteger.ONE.shiftLeft(64);

    /** A full-width integer: no padding bits, in a 1/2/4/8-byte container. */
    private static boolean isPlain(Datatype.FixedPoint fp) {
        int size = fp.size();
        return fp.bitOffset() == 0 && fp.bitPrecision() == 8 * size
                && (size == 1 || size == 2 || size == 4 || size == 8);
    }

    /**
     * The integer stored in the element at {@code off}: its {@code bitPrecision} bits from
     * {@code bitOffset}, sign-extended if signed, as libhdf5's integer conversion reads it (padding bits
     * are ignored). An unsigned 64-bit value is returned as its bit pattern.
     */
    private static long integerAt(MemorySegment data, long off, Datatype.FixedPoint fp) {
        int size = fp.size();
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        if (isPlain(fp)) {
            return switch (size) {
                case 1 -> fp.signed() ? data.get(ValueLayout.JAVA_BYTE, off) : data.get(ValueLayout.JAVA_BYTE, off) & 0xffL;
                case 2 -> {
                    short s = data.get(le ? LE_SHORT : BE_SHORT, off);
                    yield fp.signed() ? s : s & 0xffffL;
                }
                case 4 -> {
                    int v = data.get(le ? LE_INT : BE_INT, off);
                    yield fp.signed() ? v : v & 0xffff_ffffL;
                }
                default -> data.get(le ? LE_LONG : BE_LONG, off);
            };
        }
        int precision = fp.bitPrecision();
        if (precision < 1 || (long) fp.bitOffset() + precision > 8L * size) {
            throw new HdfFormatException("fixed-point bit offset " + fp.bitOffset() + " and precision "
                    + precision + " do not fit a " + size + "-byte element");
        }
        if (precision > 64) {
            throw new HdfUnsupportedException("integers wider than 64 bits are not supported (" + precision + " bits)");
        }
        long value = bits(data, off, size, le, fp.bitOffset(), precision);
        return fp.signed() && precision < 64 ? value << (64 - precision) >> (64 - precision) : value;
    }

    /**
     * {@code length} (1&ndash;64) bits of the element at {@code off}, starting {@code position} bits above
     * its least significant bit.
     */
    private static long bits(MemorySegment data, long off, int size, boolean le, int position, int length) {
        long value = 0;
        int taken = 0;
        while (taken < length) {
            int bit = position + taken;
            int byteIndex = bit >>> 3; // counted from the least significant byte
            int shift = bit & 7;
            int take = Math.min(8 - shift, length - taken);
            int b = data.get(ValueLayout.JAVA_BYTE, off + (le ? byteIndex : size - 1 - byteIndex)) & 0xff;
            value |= (long) ((b >>> shift) & ((1 << take) - 1)) << taken;
            taken += take;
        }
        return value;
    }

    /** True if {@code fp} is exactly IEEE 754 binary16, binary32, or binary64. */
    private static boolean isIeee(Datatype.FloatingPoint fp) {
        if (fp.normalization() != Datatype.MantissaNormalization.IMPLIED || fp.mantissaLocation() != 0) {
            return false;
        }
        return switch (fp.size()) {
            case 2 -> fp.signLocation() == 15 && fp.exponentLocation() == 10 && fp.exponentSize() == 5
                    && fp.mantissaSize() == 10 && fp.exponentBias() == 15;
            case 4 -> fp.signLocation() == 31 && fp.exponentLocation() == 23 && fp.exponentSize() == 8
                    && fp.mantissaSize() == 23 && fp.exponentBias() == 127;
            case 8 -> fp.signLocation() == 63 && fp.exponentLocation() == 52 && fp.exponentSize() == 11
                    && fp.mantissaSize() == 52 && fp.exponentBias() == 1023;
            default -> false;
        };
    }

    private static double readIeee(MemorySegment data, long off, int size, boolean le) {
        return switch (size) {
            case 2 -> Float.float16ToFloat(data.get(le ? LE_SHORT : BE_SHORT, off));
            case 4 -> Float.intBitsToFloat(data.get(le ? LE_INT : BE_INT, off));
            default -> Double.longBitsToDouble(data.get(le ? LE_LONG : BE_LONG, off));
        };
    }

    /**
     * Decodes a floating-point element from its sign, exponent, and mantissa fields, as libhdf5's
     * float conversion ({@code H5T__conv_f_f}) does: field locations count from the element's least
     * significant bit (the bit offset and precision are not used), an all-ones exponent is infinity or
     * NaN, and a mantissa without an implied leading bit (x87 extended precision) is read as written.
     */
    private static double decodeFloat(MemorySegment data, long off, Datatype.FloatingPoint fp) {
        int size = fp.size();
        int esize = fp.exponentSize();
        int msize = fp.mantissaSize();
        long totalBits = 8L * size;
        if (esize < 1 || msize < 1 || fp.signLocation() >= totalBits
                || (long) fp.exponentLocation() + esize > totalBits || (long) fp.mantissaLocation() + msize > totalBits) {
            throw new HdfFormatException("floating-point fields do not fit a " + size + "-byte element");
        }
        if (esize > 62 || msize > 64 || fp.normalization() == Datatype.MantissaNormalization.RESERVED) {
            throw new HdfUnsupportedException("floating-point layout not supported: " + esize
                    + "-bit exponent, " + msize + "-bit mantissa, " + fp.normalization() + " normalization");
        }
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        boolean negative = bits(data, off, size, le, fp.signLocation(), 1) != 0;
        long exponent = bits(data, off, size, le, fp.exponentLocation(), esize);
        long mantissa = bits(data, off, size, le, fp.mantissaLocation(), msize);
        long maxExponent = (1L << esize) - 1;
        boolean implied = fp.normalization() == Datatype.MantissaNormalization.IMPLIED;
        double magnitude;
        if (mantissa == 0 && exponent == 0) {
            magnitude = 0;
        } else if (exponent == maxExponent && (mantissa == 0 || !implied && mantissa == 1L << (msize - 1))) {
            magnitude = Double.POSITIVE_INFINITY; // an explicit leading bit alone is also infinity (x87)
        } else if (exponent == maxExponent) {
            return Double.NaN;
        } else {
            double m = unsignedToDouble(mantissa);
            long scale;
            if (implied && exponent != 0) {
                m += Math.scalb(1.0, msize);                        // 1.mantissa x 2^(e - bias)
                scale = exponent - fp.exponentBias() - msize;
            } else if (implied) {
                scale = 1 - fp.exponentBias() - msize;              // subnormal: 0.mantissa x 2^(1 - bias)
            } else {
                scale = exponent - fp.exponentBias() + 1 - msize;   // explicit leading bit
            }
            magnitude = Math.scalb(m, (int) Math.max(-4000, Math.min(4000, scale)));
        }
        return negative ? -magnitude : magnitude;
    }

    private static double unsignedToDouble(long value) {
        return value >= 0 ? value : ((value >>> 1) | (value & 1)) * 2.0;
    }

    private static String describe(Datatype.FixedPoint fp) {
        return (fp.signed() ? "int" : "uint") + fp.bitPrecision();
    }

    private static String unsignedAware(long value, Datatype.FixedPoint fp) {
        return fp.signed() ? Long.toString(value) : Long.toUnsignedString(value);
    }

    private static Datatype.FixedPoint requireFixed(Datatype type, String op) {
        if (type instanceof Datatype.FixedPoint fp) {
            return fp;
        }
        throw new HdfUnsupportedException(op + " requires a fixed-point datatype, not " + type.typeClass());
    }

    private static Datatype.FloatingPoint requireFloat(Datatype type, String op) {
        if (type instanceof Datatype.FloatingPoint fp) {
            return fp;
        }
        throw new HdfUnsupportedException(op + " requires a floating-point datatype, not " + type.typeClass());
    }
}
