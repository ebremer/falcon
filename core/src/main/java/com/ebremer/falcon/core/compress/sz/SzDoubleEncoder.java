package com.ebremer.falcon.core.compress.sz;

/**
 * SZ 2.1.12's double compressor ({@code sz_double.c}, {@code sz_double_pwr.c}): {@link SzFloatEncoder}'s paths
 * in double arithmetic, with the double coder's own differences kept (the bits an unpredictable value keeps,
 * 12 to 64; its 1-D coder's rounding, and no second check of a quantized value).
 */
final class SzDoubleEncoder {

    private SzDoubleEncoder() {
    }

    /** {@code SZ_compress_args_double}, the lossless stage aside, for dimensions already filtered. */
    static SzEncoder.Encoded compress(double[] data, long r5, long r4, long r3, long r2, long r1, int mode,
                                      double absErrBound, double relBoundRatio, double pwRelBoundRatio) {
        SzParams p = new SzParams(SzDecoder.DOUBLE, mode);
        if (mode == SzParams.PW_REL) {
            p.pwRelBoundRatio = pwRelBoundRatio;
        }
        int n = data.length;
        if (n <= SzEncoder.MIN_NUM_OF_ELEMENTS) {
            byte[] out = new byte[8 * n]; // SZ_skip_compress_double
            for (int i = 0; i < n; i++) {
                long bits = Double.doubleToRawLongBits(data[i]);
                for (int b = 0; b < 8; b++) {
                    out[8 * i + b] = (byte) (bits >>> (8 * b));
                }
            }
            return new SzEncoder.Encoded(out, false);
        }
        if (pwRelBoundRatio < 0.000009999) {
            p.accelerate = false;
        }
        Range range = p.msst19() ? Range.msst19(data) : Range.of(data);
        double min = range.min;
        double max = min + range.size;
        p.dmin = min;
        p.dmax = max;
        double realPrecision = realPrecision(range.size, mode, absErrBound, relBoundRatio);
        p.absErrBound = realPrecision;
        if (range.size <= realPrecision) {
            return new SzEncoder.Encoded(SzStorageEncoder.constant(p, 8, n, Double.doubleToRawLongBits(data[0])), false);
        }
        int dim = SzDecoder.dimension(r5, r4, r3, r2, r1);
        byte[] out;
        if (mode >= SzParams.PW_REL) {
            out = switch (dim) {
                case 1 -> SzDoublePwr.compress1D(p, data, pwRelBoundRatio, (int) r1, range, min, max);
                case 2 -> SzDoublePwr.compress2D(p, data, pwRelBoundRatio, (int) r2, (int) r1, range, min, max);
                case 3 -> SzDoublePwr.compress3D(p, data, pwRelBoundRatio, (int) r3, (int) r2, (int) r1, range, min, max);
                default -> SzDoublePwr.compress3D(p, data, pwRelBoundRatio, (int) (r4 * r3), (int) r2, (int) r1, range,
                        min, max);
            };
        } else {
            out = switch (dim) {
                case 1 -> SzStorageEncoder.classic(p, mdq1D(data, realPrecision, range.size, range.median));
                case 2 -> SzDoubleRegression.compress2D(p, data, (int) r2, (int) r1, realPrecision);
                case 3 -> SzDoubleRegression.compress3D(p, data, (int) r3, (int) r2, (int) r1, realPrecision);
                default -> SzDoubleRegression.compress3D(p, data, (int) (r4 * r3), (int) r2, (int) r1, realPrecision);
            };
            if (out.length >= SzStorageEncoder.storedSize(8, n)) {
                out = stored(p, data);
            }
        }
        return new SzEncoder.Encoded(out, true);
    }

    /** {@code getRealPrecision_double}. */
    static double realPrecision(double valueRangeSize, int mode, double absErrBound, double relBoundRatio) {
        return switch (mode) {
            case SzParams.ABS -> absErrBound;
            case SzParams.REL -> relBoundRatio * valueRangeSize;
            case SzParams.ABS_AND_REL -> {
                double b = relBoundRatio * valueRangeSize;
                yield absErrBound < b ? absErrBound : b;
            }
            case SzParams.ABS_OR_REL -> {
                double b = relBoundRatio * valueRangeSize;
                yield absErrBound > b ? absErrBound : b;
            }
            default -> 0;
        };
    }

    /** {@code SZ_compress_args_double_StoreOriData}. */
    static byte[] stored(SzParams p, double[] data) {
        long[] bits = new long[data.length];
        for (int i = 0; i < data.length; i++) {
            bits[i] = Double.doubleToRawLongBits(data[i]);
        }
        return SzStorageEncoder.stored(p, 8, bits);
    }

    /** {@code computeRangeSize_double} and its MSST19 form. */
    static final class Range {
        double min;
        double size;
        double median;
        byte[] signs;
        boolean positive = true;
        double nearZero;

        static Range of(double[] d) {
            Range r = new Range();
            double min = d[0];
            double max = min;
            for (int i = 1; i < d.length; i++) {
                double v = d[i];
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

        /** {@code computeRangeSize_double_MSST19}. */
        static Range msst19(double[] d) {
            Range r = new Range();
            r.signs = new byte[d.length];
            double min = d[0];
            double max = min;
            r.nearZero = min;
            for (int i = 1; i < d.length; i++) {
                double v = d[i];
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

    /** {@code optimize_intervals_double_1D_opt}. */
    static int optimizeIntervals1D(double[] d, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        long samples = 0;
        for (int pos = 2; pos < d.length; pos += SzParams.SAMPLE_DISTANCE) {
            samples++;
            double err = Math.abs(d[pos - 1] - d[pos]);
            intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
        }
        return SzQuantization.intervals(intervals, samples);
    }

    /** {@code computeReqLength_double}: 12 to 64 bits; at 64, no median. */
    static int reqLength(double realPrecision, int radExpo, double[] median) {
        int reqExpo = SzQuantization.exponent(realPrecision);
        int reqLength = 12 + radExpo - reqExpo;
        if (reqLength < 12) {
            reqLength = 12;
        }
        if (reqLength > 64) {
            reqLength = 64;
            median[0] = 0;
        }
        return reqLength;
    }

    /** {@code SZ_compress_double_1D_MDQ}. */
    static SzStorageEncoder.Classic mdq1D(double[] d, double realPrecision, double valueRangeSize, double medianValue) {
        int n = d.length;
        int quantizationIntervals = optimizeIntervals1D(d, realPrecision);
        int intvRadius = quantizationIntervals / 2;
        double[] median = {medianValue};
        int radExpo = SzQuantization.exponent(valueRangeSize / 2);
        int reqLength = reqLength(realPrecision, radExpo, median);
        double med = median[0];
        int[] type = new int[n];
        SzExactEncoder exact = new SzExactEncoder(8, reqLength);
        exact.addDouble(d[0], med);
        double pred = exact.addDouble(d[1], med);
        double checkRadius = Integer.toUnsignedLong(quantizationIntervals - 1) * realPrecision;
        double interval = 2 * realPrecision;
        double recip = 1 / realPrecision;
        for (int i = 2; i < n; i++) {
            double curData = d[i];
            double predAbsErr = Math.abs(curData - pred);
            if (predAbsErr < checkRadius) {
                int state = SzQuantization.toInt((predAbsErr * recip + 1) * 0.5);
                if (curData >= pred) {
                    type[i] = intvRadius + state;
                    pred = pred + state * interval;
                } else {
                    type[i] = intvRadius - state;
                    pred = pred - state * interval;
                }
                continue;
            }
            type[i] = 0;
            pred = exact.addDouble(curData, med);
        }
        SzStorageEncoder.Classic t = new SzStorageEncoder.Classic();
        t.width = 8;
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
