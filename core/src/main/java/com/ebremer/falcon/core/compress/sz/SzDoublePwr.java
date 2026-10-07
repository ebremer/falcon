package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;

/**
 * SZ 2.1.12's point-wise relative coders for doubles ({@code sz_double_pwr.c}, and the MSST19 coders of
 * {@code sz_double.c}). Two forms:
 *
 * <ul>
 *   <li><b>pre_log</b> (ratios below 1E-5): the classic coder on the values' base-2 logarithms, zeros put
 *       below the smallest, the signs kept apart (zstd-compressed);</li>
 *   <li><b>MSST19</b> (the accelerated form, ratios of at least 1E-5): each value predicted by multiplying
 *       its neighbours, the ratio to the prediction quantized against powers of {@code 1 + ratio} through
 *       {@link SzPrecisionTable}; zeros replaced by a value below the smallest magnitude, the signs again apart.</li>
 * </ul>
 */
final class SzDoublePwr {

    private SzDoublePwr() {
    }

    static byte[] compress1D(SzParams p, double[] d, double pwr, int r1, SzDoubleEncoder.Range range, double min,
                             double max) {
        return p.msst19() ? msst19(p, d, pwr, new int[] {r1}, range, max) : preLog(p, d, pwr, new int[] {r1}, min, max);
    }

    static byte[] compress2D(SzParams p, double[] d, double pwr, int r1, int r2, SzDoubleEncoder.Range range, double min,
                             double max) {
        return p.msst19() ? msst19(p, d, pwr, new int[] {r1, r2}, range, max)
                : preLog(p, d, pwr, new int[] {r1, r2}, min, max);
    }

    static byte[] compress3D(SzParams p, double[] d, double pwr, int r1, int r2, int r3, SzDoubleEncoder.Range range,
                             double min, double max) {
        return p.msst19() ? msst19(p, d, pwr, new int[] {r1, r2, r3}, range, max)
                : preLog(p, d, pwr, new int[] {r1, r2, r3}, min, max);
    }

    /** The signs, as {@code sz_lossless_compress(ZSTD_COMPRESSOR, 3, ...)} stores them (Falcon's zstd). */
    static byte[] signs(byte[] signs) {
        return ZstdEncoder.compress(signs, 3, false);
    }

    // ------------------------------------------------------------------ pre_log

    /** {@code SZ_compress_args_double_NoCkRngeNoGzip_*D_pwr_pre_log}. */
    private static byte[] preLog(SzParams p, double[] d, double pwr, int[] dims, double min, double max) {
        int n = d.length;
        double[] log = new double[n];
        byte[] signs = new byte[n];
        double maxAbsLog;
        if (min == 0) {
            maxAbsLog = Math.abs(SzMath.log2(Math.abs(max)));
        } else if (max == 0) {
            maxAbsLog = Math.abs(SzMath.log2(Math.abs(min)));
        } else {
            double a = Math.abs(SzMath.log2(Math.abs(min)));
            double b = Math.abs(SzMath.log2(Math.abs(max)));
            maxAbsLog = (a > b ? a : b);
        }
        double minLog = maxAbsLog;
        boolean positive = true;
        for (int i = 0; i < n; i++) {
            if (d[i] < 0) {
                signs[i] = 1;
                log[i] = -d[i];
                positive = false;
            } else {
                log[i] = d[i];
            }
            if (log[i] > 0) {
                log[i] = SzMath.log2(log[i]);
                if (log[i] > maxAbsLog) {
                    maxAbsLog = log[i];
                }
                if (log[i] < minLog) {
                    minLog = log[i];
                }
            }
        }
        SzDoubleEncoder.Range r = SzDoubleEncoder.Range.of(log);
        if (Math.abs(minLog) > maxAbsLog) {
            maxAbsLog = Math.abs(minLog);
        }
        double realPrecision = SzMath.log2(1.0 + pwr) - maxAbsLog * 2.23e-16;
        for (int i = 0; i < n; i++) {
            if (d[i] == 0) {
                log[i] = (minLog - 2.0001 * realPrecision);
            }
        }
        SzStorageEncoder.Classic t = switch (dims.length) {
            case 1 -> SzDoubleEncoder.mdq1D(log, realPrecision, r.size, r.median);
            case 2 -> mdq2D(log, dims[0], dims[1], realPrecision, r.size, r.median);
            default -> mdq3D(log, dims[0], dims[1], dims[2], realPrecision, r.size, r.median);
        };
        t.minLogValue = (minLog - 1.0001 * realPrecision);
        if (!positive) {
            t.signs = signs(signs);
        }
        byte[] out = SzStorageEncoder.classic(p, t);
        if (out.length > storedBound(n)) {
            out = SzDoubleEncoder.stored(p, d);
        }
        return out;
    }

    /** One value of the classic 2-D and 3-D double coders, which, unlike the float ones, check no decoded value. */
    private static double code(SzStorageEncoder.Classic t, int[] type, int index, double curData, double pred,
                               double recip, double realPrecision, int intvRadius) {
        double diff = curData - pred;
        double itvNum = Math.abs(diff) * recip + 1;
        if (itvNum < Integer.toUnsignedLong(t.intervals)) {
            if (diff < 0) {
                itvNum = -itvNum;
            }
            type[index] = SzQuantization.toInt(itvNum / 2) + intvRadius;
            return pred + 2 * (type[index] - intvRadius) * realPrecision;
        }
        type[index] = 0;
        return t.exact.addDouble(curData, t.medianValue);
    }

    /** {@code sz_double_pwr.c} compares a stream with {@code MetaDataByteLength}, the float header's 28 bytes. */
    private static long storedBound(int n) {
        return 3 + SzParams.META_FLOAT + SzParams.SIZE_TYPE + 1 + 8L * n;
    }

    /** {@code optimize_intervals_double_2D_opt}. */
    private static int optimizeIntervals2D(double[] d, int r1, int r2, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int sd = SzParams.SAMPLE_DISTANCE;
        long len = (long) r1 * r2;
        long offsetCount = sd - 1;
        long n1Count = 1;
        long samples = 0;
        long pos = r2 + offsetCount;
        while (pos < len) {
            samples++;
            int q = (int) pos;
            double pred = d[q - 1] + d[q - r2] - d[q - r2 - 1];
            double err = Math.abs(pred - d[q]);
            intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
            offsetCount += sd;
            if (offsetCount >= r2) {
                n1Count++;
                long offsetCount2 = n1Count % sd;
                pos += (r2 + sd - offsetCount) + (sd - offsetCount2);
                offsetCount = sd - offsetCount2;
                if (offsetCount == 0) {
                    offsetCount++;
                }
            } else {
                pos += sd;
            }
        }
        return SzQuantization.intervals(intervals, samples);
    }

    /** The classic coder's common fields, for a coder whose quantization codes are {@code type}. */
    private static SzStorageEncoder.Classic classic(int n, int intervals, double valueRangeSize, double medianValue,
                                                    double realPrecision) {
        double[] median = {medianValue};
        int radExpo = SzQuantization.exponent(valueRangeSize / 2);
        int reqLength = SzDoubleEncoder.reqLength(realPrecision, radExpo, median);
        SzStorageEncoder.Classic t = new SzStorageEncoder.Classic();
        t.width = 8;
        t.dataSeriesLength = n;
        t.intervals = intervals;
        t.medianValue = median[0];
        t.reqLength = reqLength;
        t.realPrecision = realPrecision;
        t.exact = new SzExactEncoder(8, reqLength);
        t.types = new int[n];
        return t;
    }

    /** {@code SZ_compress_double_2D_MDQ}: {@code r1} rows of {@code r2}. */
    private static SzStorageEncoder.Classic mdq2D(double[] d, int r1, int r2, double realPrecision, double valueRangeSize,
                                                  double medianValue) {
        double recip = 1 / realPrecision;
        int intervals = optimizeIntervals2D(d, r1, r2, realPrecision);
        int intvRadius = intervals / 2;
        SzStorageEncoder.Classic t = classic(r1 * r2, intervals, valueRangeSize, medianValue, realPrecision);
        int[] type = t.types;
        double med = t.medianValue;
        double[] p0 = new double[r2];
        double[] p1 = new double[r2];
        p1[0] = t.exact.addDouble(d[0], med);
        p1[1] = code(t, type, 1, d[1], p1[0], recip, realPrecision, intvRadius);
        for (int j = 2; j < r2; j++) {
            p1[j] = code(t, type, j, d[j], 2 * p1[j - 1] - p1[j - 2], recip, realPrecision, intvRadius);
        }
        for (int i = 1; i < r1; i++) {
            int index = i * r2;
            p0[0] = code(t, type, index, d[index], p1[0], recip, realPrecision, intvRadius);
            for (int j = 1; j < r2; j++) {
                index = i * r2 + j;
                p0[j] = code(t, type, index, d[index], p0[j - 1] + p1[j] - p1[j - 1], recip, realPrecision, intvRadius);
            }
            double[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }

    /** {@code optimize_intervals_double_3D_opt}. */
    private static int optimizeIntervals3D(double[] d, int r1, int r2, int r3, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int sd = SzParams.SAMPLE_DISTANCE;
        int r23 = r2 * r3;
        long len = (long) r1 * r23;
        long offsetCount = sd - 2;
        long pos = r23 + r3 + offsetCount;
        long n1Count = 1;
        long n2Count = 1;
        long samples = 0;
        while (pos < len) {
            samples++;
            int q = (int) pos;
            double pred = d[q - 1] + d[q - r3] + d[q - r23] - d[q - 1 - r23] - d[q - r3 - 1] - d[q - r3 - r23]
                    + d[q - r3 - r23 - 1];
            double err = Math.abs(pred - d[q]);
            intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
            offsetCount += sd;
            if (offsetCount >= r3) {
                n2Count++;
                if (n2Count == r2) {
                    n1Count++;
                    n2Count = 1;
                    pos += r3;
                }
                long offsetCount2 = (n1Count + n2Count) % sd;
                pos += (r3 + sd - offsetCount) + (sd - offsetCount2);
                offsetCount = sd - offsetCount2;
                if (offsetCount == 0) {
                    offsetCount++;
                }
            } else {
                pos += sd;
            }
        }
        return SzQuantization.intervals(intervals, samples);
    }

    /** {@code SZ_compress_double_3D_MDQ}: {@code r1} layers of {@code r2} rows of {@code r3}. */
    private static SzStorageEncoder.Classic mdq3D(double[] d, int r1, int r2, int r3, double realPrecision,
                                                  double valueRangeSize, double medianValue) {
        double recip = 1 / realPrecision;
        int intervals = optimizeIntervals3D(d, r1, r2, r3, realPrecision);
        int intvRadius = intervals / 2;
        int r23 = r2 * r3;
        SzStorageEncoder.Classic t = classic(r1 * r23, intervals, valueRangeSize, medianValue, realPrecision);
        int[] type = t.types;
        double med = t.medianValue;
        double[] p0 = new double[r23];
        double[] p1 = new double[r23];
        p1[0] = t.exact.addDouble(d[0], med);
        p1[1] = code(t, type, 1, d[1], p1[0], recip, realPrecision, intvRadius);
        for (int j = 2; j < r3; j++) {
            p1[j] = code(t, type, j, d[j], 2 * p1[j - 1] - p1[j - 2], recip, realPrecision, intvRadius);
        }
        for (int i = 1; i < r2; i++) {
            int index = i * r3;
            p1[index] = code(t, type, index, d[index], p1[index - r3], recip, realPrecision, intvRadius);
            for (int j = 1; j < r3; j++) {
                index = i * r3 + j;
                p1[index] = code(t, type, index, d[index], p1[index - 1] + p1[index - r3] - p1[index - r3 - 1], recip,
                        realPrecision, intvRadius);
            }
        }
        for (int k = 1; k < r1; k++) {
            int index = k * r23;
            p0[0] = code(t, type, index, d[index], p1[0], recip, realPrecision, intvRadius);
            for (int j = 1; j < r3; j++) {
                index++;
                p0[j] = code(t, type, index, d[index], p0[j - 1] + p1[j] - p1[j - 1], recip, realPrecision, intvRadius);
            }
            for (int i = 1; i < r2; i++) {
                index = k * r23 + i * r3;
                int i2 = i * r3;
                p0[i2] = code(t, type, index, d[index], p0[i2 - r3] + p1[i2] - p1[i2 - r3], recip, realPrecision,
                        intvRadius);
                for (int j = 1; j < r3; j++) {
                    index++;
                    i2 = i * r3 + j;
                    double pred = p0[i2 - 1] + p0[i2 - r3] + p1[i2] - p0[i2 - r3 - 1] - p1[i2 - r3] - p1[i2 - 1]
                            + p1[i2 - r3 - 1];
                    p0[i2] = code(t, type, index, d[index], pred, recip, realPrecision, intvRadius);
                }
            }
            double[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }

    // ------------------------------------------------------------------ MSST19

    /** {@code SZ_compress_args_double_NoCkRngeNoGzip_*D_pwr_pre_log_MSST19}. */
    private static byte[] msst19(SzParams p, double[] d, double pwr, int[] dims, SzDoubleEncoder.Range range, double max) {
        int n = d.length;
        double nearZero = range.nearZero;
        double multiplier = SzMath.pow(1 + pwr, -3.0001);
        for (int i = 0; i < n; i++) {
            if (d[i] == 0) {
                d[i] = nearZero * multiplier;
            }
        }
        double medianLog = Math.sqrt(Math.abs((nearZero * max)));
        SzStorageEncoder.Classic t = switch (dims.length) {
            case 1 -> msst19OneD(d, pwr, medianLog);
            case 2 -> msst19TwoD(d, dims[0], dims[1], pwr, medianLog);
            default -> msst19ThreeD(d, dims[0], dims[1], dims[2], pwr, medianLog);
        };
        t.minLogValue = (nearZero / ((1 + pwr) * (1 + pwr)));
        if (!range.positive) {
            t.signs = signs(range.signs);
        }
        byte[] out = SzStorageEncoder.classic(p, t);
        if (out.length > storedBound(n)) {
            out = SzDoubleEncoder.stored(p, d);
        }
        return out;
    }

    /** The fields an MSST19 coder records. */
    private static SzStorageEncoder.Classic msst19Classic(int n, int intervals, double median, double realPrecision,
                                                          int reqLength) {
        SzStorageEncoder.Classic t = new SzStorageEncoder.Classic();
        t.width = 8;
        t.dataSeriesLength = n;
        t.intervals = intervals;
        t.medianValue = median;
        t.reqLength = reqLength;
        t.realPrecision = realPrecision;
        t.plusBits = SzParams.PLUS_BITS;
        t.exact = new SzExactEncoder(8, reqLength);
        t.types = new int[n];
        return t;
    }

    /** {@code computeReqLength_double_MSST19}: 12 less the bound's exponent (every double MSST19 coder's). */
    private static int reqLengthDouble(double realPrecision) {
        return 12 - SzQuantization.exponent(realPrecision);
    }

    /** {@code optimize_intervals_double_1D_opt_MSST19}: zeros are not sampled. */
    private static int optimizeIntervals1DMsst19(double[] d, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        long samples = 0;
        double divider = (SzMath.log2(1 + realPrecision) * 2);
        for (int pos = 2; pos < d.length; pos += SzParams.SAMPLE_DISTANCE) {
            if (d[pos] == 0) {
                continue;
            }
            samples++;
            double predValue = d[pos - 1];
            double err = Math.abs(d[pos] / predValue);
            intervals[SzQuantization.radiusIndex(Math.abs(SzMath.log2(err) / divider + 0.5))]++;
        }
        return SzQuantization.intervals(intervals, samples, 64);
    }

    /** {@code SZ_compress_double_1D_MDQ_MSST19}: the previous value times a power of {@code 1 + ratio}. */
    private static SzStorageEncoder.Classic msst19OneD(double[] d, double realPrecision, double median) {
        int n = d.length;
        int intervals = optimizeIntervals1DMsst19(d, realPrecision);
        SzPrecisionTable table = new SzPrecisionTable(intervals, realPrecision);
        SzStorageEncoder.Classic t = msst19Classic(n, intervals, median, realPrecision, reqLengthDouble(realPrecision));
        int[] type = t.types;
        t.exact.addDoubleMsst19(d[0]);
        double pred = t.exact.addDoubleMsst19(d[1]);
        for (int i = 2; i < n; i++) {
            double curData = d[i];
            int state = table.code(curData / pred);
            if (state != 0) {
                type[i] = state;
                pred = (pred * table.precision[state]);
                continue;
            }
            type[i] = 0;
            pred = t.exact.addDoubleMsst19(curData);
        }
        return t;
    }

    /** {@code optimize_intervals_double_2D_opt_MSST19}. */
    private static int optimizeIntervals2DMsst19(double[] d, int r1, int r2, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int sd = SzParams.SAMPLE_DISTANCE;
        long offsetCount = sd - 1;
        long pos = r2 + offsetCount;
        double divider = (SzMath.log2(1 + realPrecision) * 2);
        long n1Count = 1;
        long len = (long) r1 * r2;
        long samples = 0;
        while (pos < len) {
            int q = (int) pos;
            if (d[q] == 0) {
                pos += sd;
                continue;
            }
            samples++;
            double pred = d[q - 1] + d[q - r2] - d[q - r2 - 1];
            double err = Math.abs(pred / d[q]);
            intervals[SzQuantization.radiusIndex(Math.abs(SzMath.log2(err) / divider + 0.5))]++;
            offsetCount += sd;
            if (offsetCount >= r2) {
                n1Count++;
                long offsetCount2 = n1Count % sd;
                pos += (r2 + sd - offsetCount) + (sd - offsetCount2);
                offsetCount = sd - offsetCount2;
                if (offsetCount == 0) {
                    offsetCount++;
                }
            } else {
                pos += sd;
            }
        }
        return SzQuantization.intervals(intervals, samples, 64);
    }

    /** A coded value of the 2-D and 3-D MSST19 coders: {@code fabs(pred) * precisionTable[state]}, or stored. */
    private static double msst19Code(SzStorageEncoder.Classic t, SzPrecisionTable table, int index, double curData,
                                    double pred, boolean abs) {
        int state = table.code(curData / pred);
        if (state != 0) {
            t.types[index] = state;
            return ((abs ? Math.abs(pred) : pred) * table.precision[state]);
        }
        t.types[index] = 0;
        return t.exact.addDoubleMsst19(curData);
    }

    /** {@code SZ_compress_double_2D_MDQ_MSST19}. */
    private static SzStorageEncoder.Classic msst19TwoD(double[] d, int r1, int r2, double realPrecision, double median) {
        int intervals = optimizeIntervals2DMsst19(d, r1, r2, realPrecision);
        SzPrecisionTable table = new SzPrecisionTable(intervals, realPrecision);
        SzStorageEncoder.Classic t = msst19Classic(r1 * r2, intervals, median, realPrecision,
                reqLengthDouble(realPrecision));
        double[] p0 = new double[r2];
        double[] p1 = new double[r2];
        p1[0] = t.exact.addDoubleMsst19(d[0]);
        p1[1] = msst19Code(t, table, 1, d[1], p1[0], true);
        for (int j = 2; j < r2; j++) {
            double pred = p1[j - 1] * p1[j - 1] / p1[j - 2];
            p1[j] = msst19Code(t, table, j, d[j], pred, true);
        }
        for (int i = 1; i < r1; i++) {
            int index = i * r2;
            p0[0] = msst19Code(t, table, index, d[index], p1[0], true);
            for (int j = 1; j < r2; j++) {
                index = i * r2 + j;
                double pred = p0[j - 1] * p1[j] / p1[j - 1];
                p0[j] = msst19Code(t, table, index, d[index], pred, true);
            }
            double[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }

    /** {@code optimize_intervals_double_3D_opt_MSST19}: its ratio is the value over the prediction. */
    private static int optimizeIntervals3DMsst19(double[] d, int r1, int r2, int r3, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int sd = SzParams.SAMPLE_DISTANCE;
        int r23 = r2 * r3;
        long offsetCount = sd - 2;
        long pos = r23 + r3 + offsetCount;
        double divider = (SzMath.log2(1 + realPrecision) * 2);
        long n1Count = 1;
        long n2Count = 1;
        long len = (long) r1 * r23;
        long samples = 0;
        while (pos < len) {
            int q = (int) pos;
            if (d[q] == 0) {
                pos += sd;
                continue;
            }
            samples++;
            double pred = d[q - 1] + d[q - r3] + d[q - r23] - d[q - 1 - r23] - d[q - r3 - 1] - d[q - r3 - r23]
                    + d[q - r3 - r23 - 1];
            double err = Math.abs(d[q] / pred);
            intervals[SzQuantization.radiusIndex(Math.abs(SzMath.log2(err) / divider + 0.5))]++;
            offsetCount += sd;
            if (offsetCount >= r3) {
                n2Count++;
                if (n2Count == r2) {
                    n1Count++;
                    n2Count = 1;
                    pos += r3;
                }
                long offsetCount2 = (n1Count + n2Count) % sd;
                pos += (r3 + sd - offsetCount) + (sd - offsetCount2);
                offsetCount = sd - offsetCount2;
                if (offsetCount == 0) {
                    offsetCount++;
                }
            } else {
                pos += sd;
            }
        }
        return SzQuantization.intervals(intervals, samples, 64);
    }

    /** {@code SZ_compress_double_3D_MDQ_MSST19}: products and quotients in double ({@code temp}, {@code temp2}). */
    private static SzStorageEncoder.Classic msst19ThreeD(double[] d, int r1, int r2, int r3, double realPrecision,
                                                         double median) {
        int intervals = optimizeIntervals3DMsst19(d, r1, r2, r3, realPrecision);
        SzPrecisionTable table = new SzPrecisionTable(intervals, realPrecision);
        int r23 = r2 * r3;
        SzStorageEncoder.Classic t = msst19Classic(r1 * r23, intervals, median, realPrecision,
                reqLengthDouble(realPrecision));
        double[] p0 = new double[r23];
        double[] p1 = new double[r23];
        p1[0] = t.exact.addDoubleMsst19(d[0]);
        p1[1] = msst19Code(t, table, 1, d[1], p1[0], true);
        for (int j = 2; j < r3; j++) {
            double temp = p1[j - 1];
            double pred = (temp * temp / p1[j - 2]);
            p1[j] = msst19Code(t, table, j, d[j], pred, true);
        }
        for (int i = 1; i < r2; i++) {
            int index = i * r3;
            p1[index] = msst19Code(t, table, index, d[index], p1[index - r3], false);
            for (int j = 1; j < r3; j++) {
                index = i * r3 + j;
                double temp = p1[index - 1];
                double pred = (temp * p1[index - r3] / p1[index - r3 - 1]);
                p1[index] = msst19Code(t, table, index, d[index], pred, true);
            }
        }
        for (int k = 1; k < r1; k++) {
            int index = k * r23;
            p0[0] = msst19Code(t, table, index, d[index], p1[0], true);
            for (int j = 1; j < r3; j++) {
                index++;
                double temp = p0[j - 1];
                double pred = (temp * p1[j] / p1[j - 1]);
                p0[j] = msst19Code(t, table, index, d[index], pred, true);
            }
            for (int i = 1; i < r2; i++) {
                index = k * r23 + i * r3;
                int i2 = i * r3;
                double temp = p0[i2 - r3];
                double pred = (temp * p1[i2] / p1[i2 - r3]);
                p0[i2] = msst19Code(t, table, index, d[index], pred, true);
                for (int j = 1; j < r3; j++) {
                    index++;
                    i2 = i * r3 + j;
                    temp = p0[i2 - 1];
                    double temp2 = p0[i2 - r3 - 1];
                    double pred3D = (temp * p0[i2 - r3] * p1[i2] * p1[i2 - r3 - 1]
                            / (temp2 * p1[i2 - r3] * p1[i2 - 1]));
                    p0[i2] = msst19Code(t, table, index, d[index], pred3D, true);
                }
            }
            double[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }
}
