package com.ebremer.falcon.core.compress.sz;

/**
 * The values SZ cannot predict, written as {@code compressSingleFloatValue} (and its double and MSST19 kin),
 * {@code updateLossyCompElement_Float}/{@code _Double} and {@code addExactData} write them; {@link SzExact}
 * reads them back. Each keeps the leading {@code reqLength} bits of the value less the median, big-endian:
 * the bytes it shares with the previous one (0 to 3) as a two-bit count, the rest of its whole bytes, and its
 * last {@code reqLength % 8} bits in a bit stream.
 */
final class SzExactEncoder {

    private final int width;
    private final int reqLength;
    private final int reqBytesLength;
    private final int resiBitsLength;
    private final byte[] preBytes;
    private final byte[] curBytes;
    private byte[] leadNum = new byte[64];
    private int count;
    final SzBytes midBytes = new SzBytes(1024);
    private byte[] resiBits = new byte[64];
    private int resiCount;

    /**
     * @param width 4 (float) or 8 (double)
     */
    SzExactEncoder(int width, int reqLength) {
        this.width = width;
        this.reqLength = reqLength;
        this.reqBytesLength = reqLength / 8;
        this.resiBitsLength = reqLength % 8;
        this.preBytes = new byte[8];
        this.curBytes = new byte[8];
    }

    int reqLength() {
        return reqLength;
    }

    int resiBitsLength() {
        return resiBitsLength;
    }

    /** {@code exactDataNum}. */
    int count() {
        return count;
    }

    /** Stores {@code value - median} ({@code compressSingleFloatValue}); returns what it decodes to. */
    float addFloat(float value, float median) {
        float norm = value - median;
        int bits = Float.floatToRawIntBits(norm);
        int ign = Math.max(32 - reqLength, 0);
        add(bits & 0xFFFFFFFFL);
        return Float.intBitsToFloat((bits >> ign) << ign) + median;
    }

    /** Stores {@code value} itself ({@code compressSingleFloatValue_MSST19}). */
    float addFloatMsst19(float value) {
        int bits = Float.floatToRawIntBits(value);
        int ign = Math.max(32 - reqLength, 0);
        add(bits & 0xFFFFFFFFL);
        return Float.intBitsToFloat((bits >> ign) << ign);
    }

    /** {@code compressSingleDoubleValue}. */
    double addDouble(double value, double median) {
        double norm = value - median;
        long bits = Double.doubleToRawLongBits(norm);
        int ign = Math.max(64 - reqLength, 0);
        add(bits);
        return Double.longBitsToDouble((bits >> ign) << ign) + median;
    }

    /** {@code compressSingleDoubleValue_MSST19}. */
    double addDoubleMsst19(double value) {
        long bits = Double.doubleToRawLongBits(value);
        int ign = Math.max(64 - reqLength, 0);
        add(bits);
        return Double.longBitsToDouble((bits >> ign) << ign);
    }

    /** {@code updateLossyCompElement_*} and {@code addExactData} for a value's big-endian bytes. */
    private void add(long bits) {
        for (int i = 0; i < width; i++) {
            curBytes[i] = (byte) (bits >>> (8 * (width - 1 - i)));
        }
        int leading = 0;
        for (int i = 0; i < width && preBytes[i] == curBytes[i]; i++) {
            leading++;
        }
        leading = Math.min(leading, 3);
        if (count == leadNum.length) {
            leadNum = java.util.Arrays.copyOf(leadNum, count * 2);
        }
        leadNum[count++] = (byte) leading;
        if (leading < reqBytesLength) {
            midBytes.bytes(curBytes, leading, reqBytesLength - leading);
        }
        if (resiBitsLength != 0) {
            int resi = reqBytesLength < 8 ? (curBytes[reqBytesLength] & 0xFF) >>> (8 - resiBitsLength) : 0;
            if (resiCount == resiBits.length) {
                resiBits = java.util.Arrays.copyOf(resiBits, resiCount * 2);
            }
            resiBits[resiCount++] = (byte) resi;
        }
        System.arraycopy(curBytes, 0, preBytes, 0, width);
    }

    /** {@code convertIntArray2ByteArray_fast_2b}: the lead counts, four to a byte, high bits first. */
    byte[] leadNumBytes() {
        byte[] out = new byte[(count * 2 + 7) / 8];
        for (int i = 0; i < count; i++) {
            out[i >> 2] |= (byte) (leadNum[i] << (6 - 2 * (i & 3)));
        }
        return out;
    }

    /** {@code convertIntArray2ByteArray_fast_dynamic}: the residual bits, packed high bits first. */
    byte[] residualBytes() {
        if (resiBitsLength == 0) {
            return new byte[0];
        }
        long bits = (long) resiCount * resiBitsLength;
        byte[] out = new byte[(int) ((bits + 7) / 8)];
        long k = 0;
        for (int i = 0; i < resiCount; i++) {
            int v = resiBits[i] & 0xFF;
            for (int b = resiBitsLength - 1; b >= 0; b--, k++) {
                if (((v >>> b) & 1) != 0) {
                    out[(int) (k >>> 3)] |= (byte) (0x80 >>> (k & 7));
                }
            }
        }
        return out;
    }
}
