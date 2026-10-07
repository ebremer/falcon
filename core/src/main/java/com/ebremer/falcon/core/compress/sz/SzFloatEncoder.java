package com.ebremer.falcon.core.compress.sz;

/**
 * SZ 2.1.12's float compressor ({@code sz_float.c}, {@code sz_float_pwr.c}), path by path as
 * {@code SZ_compress_args_float} reaches them with {@code SZ_Init(NULL)}'s configuration, each value computed
 * as libSZ computes it: in float where it uses floats, in double where it uses doubles, in the same order.
 * Arrays are C order: the last dimension varies fastest.
 */
final class SzFloatEncoder {

    private SzFloatEncoder() {
    }

    /** {@code SZ_compress_args_float}, the lossless stage aside, for dimensions already filtered. */
    static SzEncoder.Encoded compress(float[] data, long r5, long r4, long r3, long r2, long r1, int mode,
                                      double absErrBound, double relBoundRatio, double pwRelBoundRatio) {
        SzParams p = new SzParams(SzDecoder.FLOAT, mode);
        if (mode == SzParams.PW_REL) {
            p.pwRelBoundRatio = pwRelBoundRatio;
        }
        int n = data.length;
        if (n <= SzEncoder.MIN_NUM_OF_ELEMENTS) {
            byte[] out = new byte[4 * n]; // SZ_skip_compress_float
            for (int i = 0; i < n; i++) {
                int bits = Float.floatToRawIntBits(data[i]);
                for (int b = 0; b < 4; b++) {
                    out[4 * i + b] = (byte) (bits >>> (8 * b));
                }
            }
            return new SzEncoder.Encoded(out, false);
        }
        if (pwRelBoundRatio < 0.000009999) {
            p.accelerate = false;
        }
        Range range = p.msst19() ? Range.msst19(data) : Range.of(data);
        float min = range.min;
        float max = min + range.size;
        p.fmin = min;
        p.fmax = max;
        double realPrecision = realPrecision(range.size, mode, absErrBound, relBoundRatio);
        p.absErrBound = realPrecision;
        if (range.size <= realPrecision) {
            return new SzEncoder.Encoded(SzStorageEncoder.constant(p, 4, n, Float.floatToRawIntBits(data[0]) & 0xFFFFFFFFL),
                    false);
        }
        int dim = SzDecoder.dimension(r5, r4, r3, r2, r1);
        byte[] out;
        if (mode >= SzParams.PW_REL) {
            out = switch (dim) {
                case 1 -> SzFloatPwr.compress1D(p, data, pwRelBoundRatio, (int) r1, range, min, max);
                case 2 -> SzFloatPwr.compress2D(p, data, pwRelBoundRatio, (int) r2, (int) r1, range, min, max);
                case 3 -> SzFloatPwr.compress3D(p, data, pwRelBoundRatio, (int) r3, (int) r2, (int) r1, range, min, max);
                default -> SzFloatPwr.compress3D(p, data, pwRelBoundRatio, (int) (r4 * r3), (int) r2, (int) r1, range,
                        min, max);
            };
        } else {
            out = switch (dim) {
                case 1 -> classic1D(p, data, realPrecision, range.size, range.median);
                case 2 -> SzFloatRegression.compress2D(p, data, (int) r2, (int) r1, (float) realPrecision);
                case 3 -> SzFloatRegression.compress3D(p, data, (int) r3, (int) r2, (int) r1, (float) realPrecision);
                default -> SzFloatRegression.compress3D(p, data, (int) (r4 * r3), (int) r2, (int) r1,
                        (float) realPrecision);
            };
            if (out.length >= SzStorageEncoder.storedSize(4, n)) {
                out = stored(p, data);
            }
        }
        return new SzEncoder.Encoded(out, true);
    }

    /** {@code getRealPrecision_float}: the combined modes compare in float ({@code min_f}, {@code max_f}). */
    static double realPrecision(float valueRangeSize, int mode, double absErrBound, double relBoundRatio) {
        return switch (mode) {
            case SzParams.ABS -> absErrBound;
            case SzParams.REL -> relBoundRatio * valueRangeSize;
            case SzParams.ABS_AND_REL -> {
                float a = (float) absErrBound;
                float b = (float) (relBoundRatio * valueRangeSize);
                yield a < b ? a : b;
            }
            case SzParams.ABS_OR_REL -> {
                float a = (float) absErrBound;
                float b = (float) (relBoundRatio * valueRangeSize);
                yield a > b ? a : b;
            }
            default -> 0;
        };
    }

    /** {@code SZ_compress_args_float_StoreOriData}. */
    static byte[] stored(SzParams p, float[] data) {
        long[] bits = new long[data.length];
        for (int i = 0; i < data.length; i++) {
            bits[i] = Float.floatToRawIntBits(data[i]) & 0xFFFFFFFFL;
        }
        return SzStorageEncoder.stored(p, 4, bits);
    }

    /** {@code computeRangeSize_float} and its MSST19 form. */
    static final class Range {
        float min;
        float size;
        float median;
        byte[] signs;
        boolean positive = true;
        float nearZero;

        static Range of(float[] d) {
            Range r = new Range();
            float min = d[0];
            float max = min;
            for (int i = 1; i < d.length; i++) {
                float v = d[i];
                if (min > v) {
                    min = v;
                } else if (max < v) {
                    max = v;
                }
            }
            r.min = min;
            r.size = max - min;
            r.median = min + r.size / 2;
            return r;
        }

        /** {@code computeRangeSize_float_MSST19}: also the signs (from the second value on) and the value nearest 0. */
        static Range msst19(float[] d) {
            Range r = new Range();
            r.signs = new byte[d.length];
            float min = d[0];
            float max = min;
            r.nearZero = min;
            for (int i = 1; i < d.length; i++) {
                float v = d[i];
                if (v < 0) {
                    r.signs[i] = 1;
                    r.positive = false;
                }
                if (v != 0 && Math.abs(v) < Math.abs(r.nearZero)) {
                    r.nearZero = v;
                }
                if (min > v) {
                    min = v;
                } else if (max < v) {
                    max = v;
                }
            }
            r.min = min;
            r.size = max - min;
            r.median = min + r.size / 2;
            return r;
        }
    }

    // ------------------------------------------------------------------ the classic 1-D format

    /** {@code SZ_compress_args_float_NoCkRngeNoGzip_1D}. */
    static byte[] classic1D(SzParams p, float[] data, double realPrecision, float valueRangeSize, float median) {
        return SzStorageEncoder.classic(p, mdq1D(data, (float) realPrecision, valueRangeSize, median));
    }

    /** {@code optimize_intervals_float_1D_opt}: every 100th prediction error from the third value on. */
    static int optimizeIntervals1D(float[] d, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        long samples = 0;
        for (int pos = 2; pos < d.length; pos += SzParams.SAMPLE_DISTANCE) {
            samples++;
            float err = Math.abs(d[pos - 1] - d[pos]);
            intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
        }
        return SzQuantization.intervals(intervals, samples);
    }

    /** {@code computeReqLength_float}: the bits kept of an unpredictable value; above 32, all and no median. */
    static int reqLength(double realPrecision, int radExpo, float[] median) {
        int reqExpo = SzQuantization.exponent(realPrecision);
        int reqLength = 9 + radExpo - reqExpo + 1;
        if (reqLength < 9) {
            reqLength = 9;
        }
        if (reqLength > 32) {
            reqLength = 32;
            median[0] = 0;
        }
        return reqLength;
    }

    /** {@code SZ_compress_float_1D_MDQ}: each value predicted by the one before. */
    static SzStorageEncoder.Classic mdq1D(float[] d, float realPrecision, float valueRangeSize, float medianValue) {
        int n = d.length;
        int quantizationIntervals = optimizeIntervals1D(d, realPrecision);
        int intvRadius = quantizationIntervals / 2;
        float[] median = {medianValue};
        int radExpo = SzQuantization.exponent(valueRangeSize / 2);
        int reqLength = reqLength(realPrecision, radExpo, median);
        float med = median[0];
        int[] type = new int[n];
        SzExactEncoder exact = new SzExactEncoder(4, reqLength);
        type[0] = 0;
        exact.addFloat(d[0], med);
        type[1] = 0;
        float pred = exact.addFloat(d[1], med);
        float checkRadius = (float) Integer.toUnsignedLong(quantizationIntervals - 1) * realPrecision;
        float interval = 2 * realPrecision;
        float recipPrecision = 1 / realPrecision;
        for (int i = 2; i < n; i++) {
            float curData = d[i];
            float predAbsErr = Math.abs(curData - pred);
            if (predAbsErr < checkRadius) {
                int state = SzQuantization.toInt(predAbsErr * recipPrecision + 1) >> 1;
                if (curData >= pred) {
                    type[i] = intvRadius + state;
                    pred = pred + state * interval;
                } else {
                    type[i] = intvRadius - state;
                    pred = pred - state * interval;
                }
                if (Math.abs(curData - pred) > realPrecision) {
                    type[i] = 0;
                    pred = exact.addFloat(curData, med);
                }
                continue;
            }
            type[i] = 0;
            pred = exact.addFloat(curData, med);
        }
        SzStorageEncoder.Classic t = new SzStorageEncoder.Classic();
        t.width = 4;
        t.dataSeriesLength = n;
        t.intervals = quantizationIntervals;
        t.medianValue = med;
        t.reqLength = reqLength;
        t.realPrecision = realPrecision;
        t.exact = exact;
        t.types = type;
        return t;
    }
}
