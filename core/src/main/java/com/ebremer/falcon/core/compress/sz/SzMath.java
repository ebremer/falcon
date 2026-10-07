package com.ebremer.falcon.core.compress.sz;

/**
 * The C library functions SZ's point-wise relative coders call where Java has no exact equivalent. libSZ's
 * results there are its C runtime's (hdf5plugin's: the Windows UCRT), whose last bit no Java method is
 * guaranteed to reproduce: {@link #log2} is computed in double-double arithmetic and rounded once, so it is
 * the correctly rounded value but in the rarest of cases, which is what a C library's accurate
 * {@code log2} returns; {@code pow} is {@link Math#pow} (see {@link SzDecoder}).
 */
final class SzMath {

    private static final double SQRT2 = 1.4142135623730951;
    /** 1/ln 2 as a double-double. */
    private static final double INV_LN2_HI = 1.4426950408889634;
    private static final double INV_LN2_LO = 2.0355273740931033E-17;

    private SzMath() {
    }

    /** {@code log2}: {@code -inf} for zero, NaN for negatives and NaN, {@code +inf} for {@code +inf}. */
    static double log2(double x) {
        if (!(x > 0) || x == Double.POSITIVE_INFINITY) {
            return x == 0 ? Double.NEGATIVE_INFINITY : x > 0 ? x : Double.NaN;
        }
        int e = Math.getExponent(x);
        if (e < Double.MIN_EXPONENT) { // subnormal
            x *= 0x1p64;
            e = Math.getExponent(x) - 64;
            x = Math.scalb(x, -Math.getExponent(x));
        } else {
            x = Math.scalb(x, -e);
        }
        if (x > SQRT2) {
            x /= 2;
            e++;
        }
        if (x == 1) {
            return e;
        }
        // ln(x) = 2 atanh(s), s = (x - 1) / (x + 1), |s| < 0.172; all in double-double
        double num = x - 1; // exact (Sterbenz)
        double denHi = x + 1;
        double denLo = (x - (denHi - 1)) + (1 - (denHi - (denHi - 1)));
        // s = num / den
        double sHi = num / denHi;
        double rem = Math.fma(-sHi, denHi, num) - sHi * denLo;
        double sLo = rem / denHi;
        double t = sHi + sLo;
        sLo = sLo - (t - sHi);
        sHi = t;
        // s^2
        double s2Hi = sHi * sHi;
        double s2Lo = Math.fma(sHi, sHi, -s2Hi) + 2 * sHi * sLo;
        t = s2Hi + s2Lo;
        s2Lo = s2Lo - (t - s2Hi);
        s2Hi = t;
        // Horner: p = 1/1 + s2/3 + s2^2/5 + ... (to 1/49)
        double pHi = 1.0 / 49;
        double pLo = 0;
        for (int k = 23; k >= 0; k--) {
            // p = p * s2 + 1/(2k+1)
            double mHi = pHi * s2Hi;
            double mLo = Math.fma(pHi, s2Hi, -mHi) + (pHi * s2Lo + pLo * s2Hi);
            double c = 1.0 / (2 * k + 1);
            double cLo = Math.fma(-c, 2 * k + 1, 1) / (2 * k + 1);
            double sum = mHi + c;
            double err = (mHi - (sum - c)) + (c - (sum - (sum - c)));
            pHi = sum;
            pLo = err + mLo + cLo;
            t = pHi + pLo;
            pLo = pLo - (t - pHi);
            pHi = t;
        }
        // ln x = 2 s p
        double lHi = sHi * pHi;
        double lLo = Math.fma(sHi, pHi, -lHi) + (sHi * pLo + sLo * pHi);
        lHi *= 2;
        lLo *= 2;
        // log2 x = e + ln x / ln 2
        double qHi = lHi * INV_LN2_HI;
        double qLo = Math.fma(lHi, INV_LN2_HI, -qHi) + (lHi * INV_LN2_LO + lLo * INV_LN2_HI);
        double r = e + qHi;
        double rLo = (e - (r - qHi)) + (qHi - (r - (r - qHi))) + qLo;
        return r + rLo;
    }

    /** {@code pow}. */
    static double pow(double x, double y) {
        return Math.pow(x, y);
    }
}
