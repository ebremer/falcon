package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;

/**
 * A pure-Java encoder for SZ 2 (SZ 2.1.12, the version hdf5plugin builds), the error-bounded lossy compressor
 * behind HDF5 filter 32017: it compresses as {@code SZ_compress_args} does with the configuration
 * {@code SZ_Init(NULL)} gives (H5Z-SZ's), path for path, and {@link SzDecoder} (or libSZ) reads the result.
 *
 * <ul>
 *   <li>floats and doubles in 1 to 4 dimensions: the classic (Lorenzo) format in 1-D, SZ 2.1's
 *       blocked-regression format in 2-D and 3-D (4-D data goes through 3-D), point-wise relative bounds
 *       (the accelerated "MSST19" form for ratios of at least 1E-5, the logarithmic "pre_log" form below),
 *       data within the bound of each other as one value, data SZ cannot shrink as a stored copy, and 20
 *       values or fewer as they are;</li>
 *   <li>8-, 16-, 32- and 64-bit integers, signed or not: the classic format in 1 to 4 dimensions, with
 *       libSZ's own quirks kept (its 4-D integer coder, for one, stores the chunk's first value for every value
 *       it cannot predict);</li>
 *   <li>bounds: absolute, relative to the value range, both (the smaller, {@link #ABS_AND_REL}, or the larger,
 *       {@link #ABS_OR_REL}), and point-wise relative ({@link #PW_REL}, floats and doubles only).</li>
 * </ul>
 *
 * <p>SZ's own bytes, those before its lossless stage, are libSZ's byte for byte (hdf5plugin's x86-64 Windows
 * build), with two exceptions. The lossless stage, zstd at level 3, is Falcon's own encoder, whose frames are
 * valid zstd but not libzstd's bytes; so are the zstd-compressed signs a point-wise relative stream holds.
 * And the point-wise relative coder computes logarithms and powers ({@code log2}, {@code pow}) whose last bit
 * is the C runtime's: Falcon uses {@link Math}'s, which can differ from it in rare cases (see
 * {@link SzDecoder} for the measure), and then a value may be coded one step differently, always within its
 * bound.
 */
public final class SzEncoder {

    /** An absolute bound ({@code ABS}). */
    public static final int ABS = SzParams.ABS;
    /** A bound relative to the data's value range ({@code REL}). */
    public static final int REL = SzParams.REL;
    /** The smaller of an absolute and a relative bound ({@code ABS_AND_REL}). */
    public static final int ABS_AND_REL = SzParams.ABS_AND_REL;
    /** The larger of an absolute and a relative bound ({@code ABS_OR_REL}). */
    public static final int ABS_OR_REL = SzParams.ABS_OR_REL;
    /** A bound relative to each value ({@code PW_REL}): floats and doubles only. */
    public static final int PW_REL = SzParams.PW_REL;

    /** {@code MIN_NUM_OF_ELEMENTS}: 20 values or fewer are stored as they are. */
    static final int MIN_NUM_OF_ELEMENTS = 20;
    /** {@code gzipMode} for zstd: its level. */
    private static final int ZSTD_LEVEL = 3;

    private SzEncoder() {
    }

    /** An SZ stream before its lossless stage, and whether that stage applies to it. */
    record Encoded(byte[] bytes, boolean lossless) {
    }

    /**
     * Compresses as {@code SZ_compress_args(dataType, data, &outSize, errorBoundMode, absErrBound,
     * relBoundRatio, pwRelBoundRatio, r5, r4, r3, r2, r1)} does: the dimensions (0 for an unused one, those of
     * length 1 dropped as SZ drops them) give the value count and shape.
     *
     * @param dataType        {@link SzDecoder#FLOAT} to {@link SzDecoder#INT64}
     * @param data            the values, little-endian, C order (the dimension passed last varies fastest)
     * @param r5              the slowest-varying dimension of five, which must be 0 or 1
     * @param r4              the next, or 0
     * @param r3              the next, or 0
     * @param r2              the next, or 0
     * @param r1              the fastest-varying dimension
     * @param errorBoundMode  {@link #ABS}, {@link #REL}, {@link #ABS_AND_REL}, {@link #ABS_OR_REL}, or
     *                        {@link #PW_REL}
     * @param absErrBound     the absolute bound (its modes), positive
     * @param relBoundRatio   the bound relative to the value range (its modes), positive
     * @param pwRelBoundRatio the point-wise relative bound ({@link #PW_REL}), positive
     * @return the stream, as H5Z-SZ stores it
     * @throws IllegalArgumentException if the type, mode, bounds, or dimensions are not ones SZ compresses, or
     *                                  the data's length does not match the dimensions
     */
    public static byte[] compress(int dataType, byte[] data, long r5, long r4, long r3, long r2, long r1,
                                  int errorBoundMode, double absErrBound, double relBoundRatio, double pwRelBoundRatio) {
        Encoded e = encode(dataType, data, r5, r4, r3, r2, r1, errorBoundMode, absErrBound, relBoundRatio,
                pwRelBoundRatio);
        return e.lossless() ? ZstdEncoder.compress(e.bytes(), ZSTD_LEVEL, false) : e.bytes();
    }

    /** {@link #compress} before the lossless stage. */
    static Encoded encode(int dataType, byte[] data, long r5, long r4, long r3, long r2, long r1, int mode,
                          double absErrBound, double relBoundRatio, double pwRelBoundRatio) {
        return encode(dataType, data, r5, r4, r3, r2, r1, mode, absErrBound, relBoundRatio, pwRelBoundRatio, false);
    }

    /**
     * {@link #encode}, optionally with int64 ranges computed as hdf5plugin's MSVC build of libSZ computes them
     * (see {@link SzIntegerEncoder}): for tests against that build's bytes only.
     */
    static Encoded encode(int dataType, byte[] data, long r5, long r4, long r3, long r2, long r1, int mode,
                          double absErrBound, double relBoundRatio, double pwRelBoundRatio, boolean msvcInt64Range) {
        int size;
        try {
            size = SzDecoder.elementSize(dataType);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("SZ data type " + dataType + " is not one SZ compresses");
        }
        checkBounds(dataType, mode, absErrBound, relBoundRatio, pwRelBoundRatio);
        if (r5 < 0 || r4 < 0 || r3 < 0 || r2 < 0 || r1 < 0) {
            throw new IllegalArgumentException("negative SZ dimension");
        }
        long[] r = SzDecoder.filterDimension(r5, r4, r3, r2, r1);
        int dim = SzDecoder.dimension(r[4], r[3], r[2], r[1], r[0]);
        if (dim == 0 || dim > 4) {
            throw new IllegalArgumentException("SZ compresses 1 to 4 dimensions, not " + dim);
        }
        long count = 1;
        for (int i = 0; i < dim; i++) {
            count = Math.multiplyExact(count, r[i]);
        }
        if (count * size != data.length) {
            throw new IllegalArgumentException("SZ data of " + data.length + " bytes does not hold " + count
                    + " values of " + size + " bytes");
        }
        int n = (int) count;
        return switch (dataType) {
            case SzDecoder.FLOAT -> {
                float[] v = new float[n];
                for (int i = 0; i < n; i++) {
                    v[i] = Float.intBitsToFloat(le32(data, 4 * i));
                }
                yield SzFloatEncoder.compress(v, r[4], r[3], r[2], r[1], r[0], mode, absErrBound, relBoundRatio,
                        pwRelBoundRatio);
            }
            case SzDecoder.DOUBLE -> {
                double[] v = new double[n];
                for (int i = 0; i < n; i++) {
                    v[i] = Double.longBitsToDouble(le64(data, 8 * i));
                }
                yield SzDoubleEncoder.compress(v, r[4], r[3], r[2], r[1], r[0], mode, absErrBound, relBoundRatio,
                        pwRelBoundRatio);
            }
            default -> SzIntegerEncoder.compress(dataType, data, n, r[4], r[3], r[2], r[1], r[0], mode, absErrBound,
                    relBoundRatio, msvcInt64Range);
        };
    }

    /**
     * SZ's {@code filterDimension}: the dimensions with those of length 1 dropped, as SZ drops them before it
     * compresses (and as H5Z-SZ's {@code set_local} drops them from its client data).
     *
     * @param r5 the slowest-varying dimension of five, or 0
     * @param r4 the next, or 0
     * @param r3 the next, or 0
     * @param r2 the next, or 0
     * @param r1 the fastest-varying dimension
     * @return the dimensions left, fastest-varying first: {@code {r1, r2, r3, r4, r5}}, 0 for none
     */
    public static long[] filterDimensions(long r5, long r4, long r3, long r2, long r1) {
        return SzDecoder.filterDimension(r5, r4, r3, r2, r1);
    }

    /**
     * Checks that {@link #compress} takes a data type and error bound, without data.
     *
     * @param dataType        {@link SzDecoder#FLOAT} to {@link SzDecoder#INT64}
     * @param errorBoundMode  the mode
     * @param absErrBound     the absolute bound
     * @param relBoundRatio   the relative bound
     * @param pwRelBoundRatio the point-wise relative bound
     * @throws IllegalArgumentException as {@link #compress} would
     */
    public static void check(int dataType, int errorBoundMode, double absErrBound, double relBoundRatio,
                             double pwRelBoundRatio) {
        try {
            SzDecoder.elementSize(dataType);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("SZ data type " + dataType + " is not one SZ compresses");
        }
        checkBounds(dataType, errorBoundMode, absErrBound, relBoundRatio, pwRelBoundRatio);
    }

    private static void checkBounds(int dataType, int mode, double abs, double rel, double pwr) {
        boolean floating = dataType == SzDecoder.FLOAT || dataType == SzDecoder.DOUBLE;
        switch (mode) {
            case ABS -> positive("absolute bound", abs);
            case REL -> positive("relative bound", rel);
            case ABS_AND_REL, ABS_OR_REL -> {
                positive("absolute bound", abs);
                positive("relative bound", rel);
            }
            case PW_REL -> {
                if (!floating) {
                    throw new IllegalArgumentException("SZ takes no point-wise relative bound for integers");
                }
                positive("point-wise relative bound", pwr);
            }
            default -> throw new IllegalArgumentException("SZ error-bound mode " + mode + " is not supported");
        }
    }

    private static void positive(String what, double v) {
        if (!(v > 0) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("SZ " + what + " must be positive and finite, not " + v);
        }
    }

    static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    static long le64(byte[] b, int at) {
        return (le32(b, at) & 0xFFFFFFFFL) | (long) le32(b, at + 4) << 32;
    }
}
