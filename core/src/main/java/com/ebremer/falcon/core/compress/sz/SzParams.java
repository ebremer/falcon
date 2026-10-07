package com.ebremer.falcon.core.compress.sz;

/**
 * The part of libSZ's configuration ({@code confparams_cpr}) a compression reads and writes, as
 * {@code SZ_Init(NULL)} leaves it ({@code conf.c}) and one {@code SZ_compress_args} call then changes it, and
 * its 28- or 36-byte image in a stream ({@code convertSZParamsToBytes}).
 *
 * <p>H5Z-SZ runs {@code SZ_Init} when a dataset is created, so each dataset starts from these defaults; a
 * compression changes only what its mode needs. Two of the values a stream records are therefore not the
 * caller's: a relative bound is written as the default ratio, 1E-4 ({@code SZ_compress_args} does not store
 * it), and an integer stream's parameters carry the zero minimum and maximum of a fresh configuration.
 */
final class SzParams {

    // defines.h
    static final int ABS = 0;
    static final int REL = 1;
    static final int ABS_AND_REL = 2;
    static final int ABS_OR_REL = 3;
    static final int PW_REL = 10;

    /** {@code MetaDataByteLength}: floats and integers. */
    static final int META_FLOAT = 28;
    /** {@code MetaDataByteLength_double}. */
    static final int META_DOUBLE = 36;
    /** SZ 2.1.12's {@code versionNumber}. */
    static final byte[] VERSION = {2, 1, 12};
    /** {@code exe_params->SZ_SIZE_TYPE} on x86-64. */
    static final int SIZE_TYPE = 8;
    /** {@code max_quant_intervals}. */
    static final int MAX_QUANT_INTERVALS = 65536;
    /** {@code maxRangeRadius}. */
    static final int MAX_RANGE_RADIUS = MAX_QUANT_INTERVALS / 2;
    /** {@code sampleDistance}. */
    static final int SAMPLE_DISTANCE = 100;
    /** {@code predThreshold}, a C float. */
    static final float PRED_THRESHOLD = 0.99f;
    /** {@code segment_size}. */
    static final int SEGMENT_SIZE = 36;
    /** {@code plus_bits}. */
    static final int PLUS_BITS = 3;
    /** {@code sol_ID}: {@code SZ}. */
    static final int SOL_ID = 101;

    int dataType;
    int errorBoundMode;
    double absErrBound = 1E-4;
    double relBoundRatio = 1E-4;
    double pwRelBoundRatio = 1E-3;
    double psnr = 90;
    float fmin;
    float fmax;
    double dmin;
    double dmax;
    boolean accelerate = true;

    SzParams(int dataType, int errorBoundMode) {
        this.dataType = dataType;
        this.errorBoundMode = errorBoundMode;
    }

    /** Whether the accelerated (MSST19) point-wise relative coder is in use. */
    boolean msst19() {
        return errorBoundMode == PW_REL && accelerate;
    }

    /**
     * Writes the first {@code length} bytes of {@code convertSZParamsToBytes}'s image (28 or 36: an integer
     * stream's buffer holds 28 of the 36 it writes, the rest overwritten after).
     */
    void write(SzBytes out, int length) {
        byte[] r = new byte[META_DOUBLE];
        // optQuantMode 1, little-endian data and system, szMode SZ_BEST_COMPRESSION, gzipMode 3 (zstd's level,
        // which none of the three zlib cases matches)
        r[0] = (byte) ((((1 << 1 | 0) << 1 | 0) << 2 | 1) << 2);
        r[1] = (byte) (SAMPLE_DISTANCE >>> 8);
        r[2] = (byte) SAMPLE_DISTANCE;
        short threshold = (short) (PRED_THRESHOLD * 10000); // float arithmetic: 9900
        r[3] = (byte) (threshold >>> 8);
        r[4] = (byte) threshold;
        r[5] = (byte) (errorBoundMode << 4 | (dataType & 0x17));
        switch (errorBoundMode) {
            case ABS -> putFloat(r, 6, (float) absErrBound);
            case REL -> putFloat(r, 10, (float) relBoundRatio);
            case ABS_AND_REL, ABS_OR_REL -> {
                putFloat(r, 6, (float) absErrBound);
                putFloat(r, 10, (float) relBoundRatio);
            }
            case PW_REL -> putFloat(r, 10, (float) pwRelBoundRatio);
            default -> throw new IllegalStateException("SZ error-bound mode " + errorBoundMode);
        }
        r[14] = (byte) SOL_ID;
        putInt(r, 16, MAX_QUANT_INTERVALS);
        if (dataType == SzDecoder.FLOAT) {
            putFloat(r, 20, fmin);
            putFloat(r, 24, fmax);
        } else {
            putLong(r, 20, Double.doubleToRawLongBits(dmin));
            putLong(r, 28, Double.doubleToRawLongBits(dmax));
        }
        out.bytes(r, 0, length);
    }

    /** The version and flag byte, then the parameters ({@code length} of them) and the value count. */
    void header(SzBytes out, int flags, int length, long count) {
        out.bytes(VERSION);
        out.u8(flags);
        write(out, length);
        out.be64(count);
    }

    private static void putFloat(byte[] b, int at, float v) {
        putInt(b, at, Float.floatToRawIntBits(v));
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static void putLong(byte[] b, int at, long v) {
        putInt(b, at, (int) (v >>> 32));
        putInt(b, at + 4, (int) v);
    }
}
