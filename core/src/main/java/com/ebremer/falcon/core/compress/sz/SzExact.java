package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * The values SZ could not predict, as {@code decompressDataSeries_float_1D} and its kin recover them
 * ({@code szd_float.c}, {@code szd_double.c}): each is the leading {@code reqLength} bits of a float or
 * double (big-endian) minus the median. Its first {@code leadNum} (0 to 3) bytes repeat the previous
 * value's; the rest of its whole bytes come from {@code exactMidBytes}, and its last
 * {@code reqLength % 8} bits from the bit stream {@code residualMidBits}.
 */
final class SzExact {

    private final SzBuffer in;
    private final byte[] leadNum;
    private final int width;
    private final int reqBytesLength;
    private final int resiBitsLength;
    private long midAt;
    private final long residualAt;
    private long k; // bits taken from residualMidBits
    private int l;  // values taken
    private final byte[] preBytes;
    private final byte[] curBytes;

    /**
     * @param width the value's size: 4 (float) or 8 (double)
     */
    SzExact(SzBuffer in, byte[] leadNum, long midAt, long residualAt, int reqLength, int width) {
        if (reqLength < 0 || reqLength > 8 * width) {
            throw new CompressionFormatException("SZ required length " + reqLength + " for a " + width + "-byte value");
        }
        this.in = in;
        this.leadNum = leadNum;
        this.width = width;
        this.reqBytesLength = reqLength / 8;
        this.resiBitsLength = reqLength % 8;
        this.midAt = midAt;
        this.residualAt = residualAt;
        this.preBytes = new byte[width];
        this.curBytes = new byte[width];
    }

    /** The next value's bits (a float's in the low 32). */
    long next() {
        int resiBits = 0;
        if (resiBitsLength != 0) {
            // the next resiBitsLength bits of residualMidBits, high bit first (at most two bytes)
            int at = (int) (k & 7);
            int pair = in.u8(residualAt + (k >>> 3)) << 8;
            if (at + resiBitsLength > 8) {
                pair |= in.u8(residualAt + (k >>> 3) + 1);
            }
            resiBits = (pair >>> (16 - at - resiBitsLength)) & ((1 << resiBitsLength) - 1);
            k += resiBitsLength;
        }
        if (l >= leadNum.length) {
            throw new CompressionFormatException("SZ stream has fewer unpredictable values than it uses");
        }
        int leadingNum = leadNum[l++];
        java.util.Arrays.fill(curBytes, (byte) 0);
        System.arraycopy(preBytes, 0, curBytes, 0, leadingNum);
        for (int j = leadingNum; j < reqBytesLength; j++) {
            curBytes[j] = (byte) in.u8(midAt++);
        }
        if (resiBitsLength != 0) {
            curBytes[reqBytesLength] = (byte) (resiBits << (8 - resiBitsLength));
        }
        System.arraycopy(curBytes, 0, preBytes, 0, width);
        long v = 0;
        for (int j = 0; j < width; j++) {
            v = v << 8 | curBytes[j] & 0xFF;
        }
        return v;
    }

    float nextFloat() {
        return Float.intBitsToFloat((int) next());
    }

    double nextDouble() {
        return Double.longBitsToDouble(next());
    }
}
