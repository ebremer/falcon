package com.ebremer.falcon.zarr.data;

/**
 * IEEE 754 binary16 ({@code float16}) from a {@code double}, rounded once.
 *
 * <p>The JDK converts only from {@code float} ({@link Float#floatToFloat16}), and going through a
 * {@code float} first rounds twice: {@code 1 + 2^-11 + 2^-40} rounds to {@code 1 + 2^-11} as a
 * {@code float}, a tie, which then rounds to even, {@code 1.0}, while the nearest half is
 * {@code 1 + 2^-10}. This rounds the {@code double} directly, to nearest with ties to even, as numpy does.
 */
public final class Float16 {

    private Float16() {
    }

    /**
     * The binary16 nearest {@code d}: ties to even, infinity from {@code 65520} up (the midpoint above the
     * largest half, {@code 65504}), and a NaN stays a quiet NaN with the top of its payload.
     */
    public static short fromDouble(double d) {
        long bits = Double.doubleToRawLongBits(d);
        int sign = (int) (bits >>> 48) & 0x8000;
        if (Double.isNaN(d)) {
            return (short) (sign | 0x7e00 | (int) ((bits >>> 42) & 0x1ff));
        }
        double a = Math.abs(d);
        if (a >= 65520.0) {
            return (short) (sign | 0x7c00);
        }
        if (a < 0x1p-14) {
            // Subnormal (or zero): a count of 2^-24. Scaling by a power of two is exact, and rint rounds
            // half to even; 1024 is the smallest normal, which the bit layout gives for free.
            return (short) (sign | (int) Math.rint(a * 0x1p24));
        }
        int exponent = Math.getExponent(a); // -14 .. 15
        double fraction = Math.scalb(a, -exponent) - 1.0; // [0, 1), exact
        int mantissa = (int) Math.rint(fraction * 1024); // 1024 carries into the exponent, as it should
        return (short) (sign | (((exponent + 15) << 10) + mantissa));
    }
}
