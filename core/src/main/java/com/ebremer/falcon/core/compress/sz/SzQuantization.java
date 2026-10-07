package com.ebremer.falcon.core.compress.sz;

/**
 * What SZ 2.1.12's {@code optimize_intervals_*} functions share: turning a histogram of sampled prediction
 * errors into a quantization interval count, and C's conversions where libSZ relies on how the MSVC x86-64
 * build performs them.
 */
final class SzQuantization {

    private SzQuantization() {
    }

    /**
     * The tail of every {@code optimize_intervals_*}: the smallest radius covering more than
     * {@code predThreshold} of the samples, doubled, rounded up to a power of two, and at least 32.
     *
     * @param intervals the histogram of {@code maxRangeRadius} radii
     * @param samples   {@code totalSampleSize} (a {@code size_t} multiplied by the float threshold)
     */
    static int intervals(long[] intervals, long samples) {
        return intervals(intervals, samples, 32);
    }

    /** {@link #intervals(long[], long)} with another least count (the double MSST19 coders keep 64). */
    static int intervals(long[] intervals, long samples, int least) {
        long targetCount = (long) ((float) samples * SzParams.PRED_THRESHOLD);
        long sum = 0;
        int i;
        for (i = 0; i < SzParams.MAX_RANGE_RADIUS; i++) {
            sum += intervals[i];
            if (Long.compareUnsigned(sum, targetCount) > 0) {
                break;
            }
        }
        if (i >= SzParams.MAX_RANGE_RADIUS) {
            i = SzParams.MAX_RANGE_RADIUS - 1;
        }
        int accIntervals = 2 * (i + 1);
        int powerOf2 = roundUpToPowerOf2(accIntervals);
        return Math.max(powerOf2, least);
    }

    /** {@code roundUpToPowerOf2} on an unsigned int. */
    static int roundUpToPowerOf2(int base) {
        base -= 1;
        base |= base >>> 1;
        base |= base >>> 2;
        base |= base >>> 4;
        base |= base >>> 8;
        base |= base >>> 16;
        return base + 1;
    }

    /**
     * A histogram slot: {@code (uint64_t)x} capped at {@code maxRangeRadius - 1}, MSVC's conversion: a negative
     * value wraps to a huge one (a negative bound makes them), a NaN or a value past 2^64 becomes 2^63; the
     * cap catches all three.
     */
    static int radiusIndex(double x) {
        long v = toUnsignedLong(x);
        return Long.compareUnsigned(v, SzParams.MAX_RANGE_RADIUS) >= 0 ? SzParams.MAX_RANGE_RADIUS - 1 : (int) v;
    }

    /** C's {@code (int)} of a float, as {@code cvttss2si} does it: out of range or NaN gives INT_MIN. */
    static int toInt(float x) {
        return x >= -2147483648f && x < 2147483648f ? (int) x : Integer.MIN_VALUE;
    }

    /** C's {@code (int)} of a double ({@code cvttsd2si}). */
    static int toInt(double x) {
        return x > -2147483649.0 && x < 2147483648.0 ? (int) x : Integer.MIN_VALUE;
    }

    /** C's {@code (int64_t)} of a double ({@code cvttsd2si} with a 64-bit destination). */
    static long toLong(double x) {
        return x >= -9.223372036854775808E18 && x < 9.223372036854775808E18 ? (long) x : Long.MIN_VALUE;
    }

    /**
     * C's {@code (uint64_t)} of a double as hdf5plugin's MSVC build performs it: below 2^63 as a signed
     * conversion (a negative value wraps), from 2^63 to 2^64 offset by 2^63, and 2^63 for anything else.
     */
    static long toUnsignedLong(double x) {
        if (x >= 9.223372036854775808E18) {
            return x < 1.8446744073709551616E19 ? (long) (x - 9.223372036854775808E18) ^ Long.MIN_VALUE : Long.MIN_VALUE;
        }
        return toLong(x);
    }

    /** {@code (double)} of an unsigned 64-bit value. */
    static double unsignedToDouble(long v) {
        if (v >= 0) {
            return (double) v;
        }
        double d = (double) ((v >>> 1) | (v & 1));
        return d * 2;
    }

    /** {@code (float)} of a {@code size_t}. */
    static float unsignedToFloat(long v) {
        if (v >= 0) {
            return (float) v;
        }
        float f = (float) ((v >>> 1) | (v & 1));
        return f * 2;
    }

    /** {@code getExponent_float}: the unbiased exponent field. */
    static int exponent(float v) {
        return (short) (((Float.floatToRawIntBits(v) & 0x7F800000) >> 23) - 127);
    }

    /** {@code getExponent_double} and {@code getPrecisionReqLength_double}. */
    static int exponent(double v) {
        return (short) ((int) ((Double.doubleToRawLongBits(v) & 0x7FF0000000000000L) >>> 52) - 1023);
    }
}
