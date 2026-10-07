package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;

/**
 * SZ 2.1.12's point-wise relative coders for floats ({@code sz_float_pwr.c}, and the MSST19 coders of
 * {@code sz_float.c}). Two forms:
 *
 * <ul>
 *   <li><b>pre_log</b> (ratios below 1E-5): the classic coder on the values' base-2 logarithms, zeros put
 *       below the smallest, the signs kept apart (zstd-compressed);</li>
 *   <li><b>MSST19</b> (the accelerated form, ratios of at least 1E-5): each value predicted by multiplying
 *       its neighbours, the ratio to the prediction quantized against powers of {@code 1 + ratio} through
 *       {@link SzPrecisionTable}; zeros replaced by a value below the smallest magnitude, the signs again apart.</li>
 * </ul>
 */
final class SzFloatPwr {

    private SzFloatPwr() {
    }

    static byte[] compress1D(SzParams p, float[] d, double pwr, int r1, SzFloatEncoder.Range range, float min,
                             float max) {
        return p.msst19() ? msst19(p, d, pwr, new int[] {r1}, range, max) : preLog(p, d, pwr, new int[] {r1}, min, max);
    }

    static byte[] compress2D(SzParams p, float[] d, double pwr, int r1, int r2, SzFloatEncoder.Range range, float min,
                             float max) {
        return p.msst19() ? msst19(p, d, pwr, new int[] {r1, r2}, range, max)
                : preLog(p, d, pwr, new int[] {r1, r2}, min, max);
    }

    static byte[] compress3D(SzParams p, float[] d, double pwr, int r1, int r2, int r3, SzFloatEncoder.Range range,
                             float min, float max) {
        return p.msst19() ? msst19(p, d, pwr, new int[] {r1, r2, r3}, range, max)
                : preLog(p, d, pwr, new int[] {r1, r2, r3}, min, max);
    }

    /** The signs, as {@code sz_lossless_compress(ZSTD_COMPRESSOR, 3, ...)} stores them (Falcon's zstd). */
    static byte[] signs(byte[] signs) {
        return ZstdEncoder.compress(signs, 3, false);
    }

    // ------------------------------------------------------------------ pre_log

    /** {@code SZ_compress_args_float_NoCkRngeNoGzip_*D_pwr_pre_log}. */
    private static byte[] preLog(SzParams p, float[] d, double pwr, int[] dims, float min, float max) {
        int n = d.length;
        float[] log = new float[n];
        byte[] signs = new byte[n];
        float maxAbsLog;
        if (min == 0) {
            maxAbsLog = (float) Math.abs(SzMath.log2(Math.abs((double) max)));
        } else if (max == 0) {
            maxAbsLog = (float) Math.abs(SzMath.log2(Math.abs((double) min)));
        } else {
            double a = Math.abs(SzMath.log2(Math.abs((double) min)));
            double b = Math.abs(SzMath.log2(Math.abs((double) max)));
            maxAbsLog = (float) (a > b ? a : b);
        }
        float minLog = maxAbsLog;
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
                log[i] = (float) SzMath.log2(log[i]);
                if (log[i] > maxAbsLog) {
                    maxAbsLog = log[i];
                }
                if (log[i] < minLog) {
                    minLog = log[i];
                }
            }
        }
        SzFloatEncoder.Range r = SzFloatEncoder.Range.of(log);
        if (Math.abs((double) minLog) > maxAbsLog) {
            maxAbsLog = (float) Math.abs((double) minLog);
        }
        double realPrecision = SzMath.log2(1.0 + pwr) - maxAbsLog * 1.2e-7;
        for (int i = 0; i < n; i++) {
            if (d[i] == 0) {
                log[i] = (float) (minLog - 2.0001 * realPrecision);
            }
        }
        float rp = (float) realPrecision;
        SzStorageEncoder.Classic t = switch (dims.length) {
            case 1 -> SzFloatEncoder.mdq1D(log, rp, r.size, r.median);
            case 2 -> mdq2D(log, dims[0], dims[1], rp, r.size, r.median);
            default -> mdq3D(log, dims[0], dims[1], dims[2], rp, r.size, r.median);
        };
        t.minLogValue = (float) (minLog - 1.0001 * realPrecision);
        if (!positive) {
            t.signs = signs(signs);
        }
        byte[] out = SzStorageEncoder.classic(p, t);
        if (out.length > SzStorageEncoder.storedSize(4, n)) {
            out = SzFloatEncoder.stored(p, d);
        }
        return out;
    }

    /** One value of the classic 2-D and 3-D coders: {@code itvNum} in double, the decoded value in float. */
    private static float code(SzStorageEncoder.Classic t, int[] type, int index, float curData, float pred,
                              float recip, float realPrecision, int intvRadius) {
        float diff = curData - pred;
        float itvNum = (float) (Math.abs((double) diff) * recip + 1);
        if (itvNum < Integer.toUnsignedLong(t.intervals)) {
            if (diff < 0) {
                itvNum = -itvNum;
            }
            type[index] = SzQuantization.toInt(itvNum / 2) + intvRadius;
            float decoded = pred + 2 * (type[index] - intvRadius) * realPrecision;
            if (Math.abs(curData - decoded) > realPrecision) {
                type[index] = 0;
                return t.exact.addFloat(curData, (float) t.medianValue);
            }
            return decoded;
        }
        type[index] = 0;
        return t.exact.addFloat(curData, (float) t.medianValue);
    }

    /** {@code optimize_intervals_float_2D_opt}. */
    private static int optimizeIntervals2D(float[] d, int r1, int r2, double realPrecision) {
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
            float pred = d[q - 1] + d[q - r2] - d[q - r2 - 1];
            float err = Math.abs(pred - d[q]);
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
    private static SzStorageEncoder.Classic classic(int n, int intervals, float valueRangeSize, float medianValue,
                                                    float realPrecision) {
        float[] median = {medianValue};
        int radExpo = SzQuantization.exponent(valueRangeSize / 2);
        int reqLength = SzFloatEncoder.reqLength(realPrecision, radExpo, median);
        SzStorageEncoder.Classic t = new SzStorageEncoder.Classic();
        t.width = 4;
        t.dataSeriesLength = n;
        t.intervals = intervals;
        t.medianValue = median[0];
        t.reqLength = reqLength;
        t.realPrecision = realPrecision;
        t.exact = new SzExactEncoder(4, reqLength);
        t.types = new int[n];
        return t;
    }

    /** {@code SZ_compress_float_2D_MDQ}: {@code r1} rows of {@code r2}. */
    private static SzStorageEncoder.Classic mdq2D(float[] d, int r1, int r2, float realPrecision, float valueRangeSize,
                                                  float medianValue) {
        float recip = 1 / realPrecision;
        int intervals = optimizeIntervals2D(d, r1, r2, realPrecision);
        int intvRadius = intervals / 2;
        SzStorageEncoder.Classic t = classic(r1 * r2, intervals, valueRangeSize, medianValue, realPrecision);
        int[] type = t.types;
        float med = (float) t.medianValue;
        float[] p0 = new float[r2];
        float[] p1 = new float[r2];
        p1[0] = t.exact.addFloat(d[0], med);
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
            float[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }

    /** {@code optimize_intervals_float_3D_opt}. */
    private static int optimizeIntervals3D(float[] d, int r1, int r2, int r3, double realPrecision) {
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
            float pred = d[q - 1] + d[q - r3] + d[q - r23] - d[q - 1 - r23] - d[q - r3 - 1] - d[q - r3 - r23]
                    + d[q - r3 - r23 - 1];
            float err = Math.abs(pred - d[q]);
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

    /** {@code SZ_compress_float_3D_MDQ}: {@code r1} layers of {@code r2} rows of {@code r3}. */
    private static SzStorageEncoder.Classic mdq3D(float[] d, int r1, int r2, int r3, float realPrecision,
                                                  float valueRangeSize, float medianValue) {
        float recip = 1 / realPrecision;
        int intervals = optimizeIntervals3D(d, r1, r2, r3, realPrecision);
        int intvRadius = intervals / 2;
        int r23 = r2 * r3;
        SzStorageEncoder.Classic t = classic(r1 * r23, intervals, valueRangeSize, medianValue, realPrecision);
        int[] type = t.types;
        float med = (float) t.medianValue;
        float[] p0 = new float[r23];
        float[] p1 = new float[r23];
        p1[0] = t.exact.addFloat(d[0], med);
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
                    float pred = p0[i2 - 1] + p0[i2 - r3] + p1[i2] - p0[i2 - r3 - 1] - p1[i2 - r3] - p1[i2 - 1]
                            + p1[i2 - r3 - 1];
                    p0[i2] = code(t, type, index, d[index], pred, recip, realPrecision, intvRadius);
                }
            }
            float[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }

    // ------------------------------------------------------------------ MSST19

    /** {@code SZ_compress_args_float_NoCkRngeNoGzip_*D_pwr_pre_log_MSST19}. */
    private static byte[] msst19(SzParams p, float[] d, double pwr, int[] dims, SzFloatEncoder.Range range, float max) {
        int n = d.length;
        float nearZero = range.nearZero;
        float multiplier = (float) SzMath.pow(1 + pwr, -3.0001);
        for (int i = 0; i < n; i++) {
            if (d[i] == 0) {
                d[i] = nearZero * multiplier;
            }
        }
        float medianLog = (float) Math.sqrt(Math.abs((double) (nearZero * max)));
        SzStorageEncoder.Classic t = switch (dims.length) {
            case 1 -> msst19OneD(d, pwr, medianLog);
            case 2 -> msst19TwoD(d, dims[0], dims[1], pwr, medianLog);
            default -> msst19ThreeD(d, dims[0], dims[1], dims[2], pwr, medianLog);
        };
        t.minLogValue = (float) (nearZero / ((1 + pwr) * (1 + pwr)));
        if (!range.positive) {
            t.signs = signs(range.signs);
        }
        byte[] out = SzStorageEncoder.classic(p, t);
        if (out.length > SzStorageEncoder.storedSize(4, n)) {
            out = SzFloatEncoder.stored(p, d);
        }
        return out;
    }

    /** The fields an MSST19 coder records. */
    private static SzStorageEncoder.Classic msst19Classic(int n, int intervals, float median, double realPrecision,
                                                          int reqLength) {
        SzStorageEncoder.Classic t = new SzStorageEncoder.Classic();
        t.width = 4;
        t.dataSeriesLength = n;
        t.intervals = intervals;
        t.medianValue = median;
        t.reqLength = reqLength;
        t.realPrecision = realPrecision;
        t.plusBits = SzParams.PLUS_BITS;
        t.exact = new SzExactEncoder(4, reqLength);
        t.types = new int[n];
        return t;
    }

    /** {@code computeReqLength_float_MSST19}: 9 less the bound's exponent as a float. */
    private static int reqLengthFloat(double realPrecision) {
        return 9 - SzQuantization.exponent((float) realPrecision);
    }

    /** {@code computeReqLength_double_MSST19}: 12 less its exponent (the 2-D float coder's choice). */
    private static int reqLengthDouble(double realPrecision) {
        return 12 - SzQuantization.exponent(realPrecision);
    }

    /** {@code optimize_intervals_float_1D_opt_MSST19}: zeros are not sampled. */
    private static int optimizeIntervals1DMsst19(float[] d, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        long samples = 0;
        float divider = (float) (SzMath.log2(1 + realPrecision) * 2);
        for (int pos = 2; pos < d.length; pos += SzParams.SAMPLE_DISTANCE) {
            if (d[pos] == 0) {
                continue;
            }
            samples++;
            float predValue = d[pos - 1];
            double err = Math.abs((double) d[pos] / predValue);
            intervals[SzQuantization.radiusIndex(Math.abs(SzMath.log2(err) / divider + 0.5))]++;
        }
        return SzQuantization.intervals(intervals, samples);
    }

    /** {@code SZ_compress_float_1D_MDQ_MSST19}: the previous value times a power of {@code 1 + ratio}. */
    private static SzStorageEncoder.Classic msst19OneD(float[] d, double realPrecision, float median) {
        int n = d.length;
        int intervals = optimizeIntervals1DMsst19(d, realPrecision);
        SzPrecisionTable table = new SzPrecisionTable(intervals, realPrecision);
        SzStorageEncoder.Classic t = msst19Classic(n, intervals, median, realPrecision, reqLengthFloat(realPrecision));
        int[] type = t.types;
        t.exact.addFloatMsst19(d[0]);
        float pred = t.exact.addFloatMsst19(d[1]);
        for (int i = 2; i < n; i++) {
            float curData = d[i];
            int state = table.code(curData / pred);
            if (state != 0) {
                type[i] = state;
                pred = (float) (pred * table.precision[state]);
                continue;
            }
            type[i] = 0;
            pred = t.exact.addFloatMsst19(curData);
        }
        return t;
    }

    /** {@code optimize_intervals_float_2D_opt_MSST19}. */
    private static int optimizeIntervals2DMsst19(float[] d, int r1, int r2, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int sd = SzParams.SAMPLE_DISTANCE;
        long offsetCount = sd - 1;
        long pos = r2 + offsetCount;
        float divider = (float) (SzMath.log2(1 + realPrecision) * 2);
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
            float pred = d[q - 1] + d[q - r2] - d[q - r2 - 1];
            float err = Math.abs(pred / d[q]);
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
        return SzQuantization.intervals(intervals, samples);
    }

    /** A coded value of the 2-D and 3-D MSST19 coders: {@code fabs(pred) * precisionTable[state]}, or stored. */
    private static float msst19Code(SzStorageEncoder.Classic t, SzPrecisionTable table, int index, float curData,
                                    float pred, boolean abs) {
        int state = table.code(curData / pred);
        if (state != 0) {
            t.types[index] = state;
            return (float) ((abs ? Math.abs((double) pred) : pred) * table.precision[state]);
        }
        t.types[index] = 0;
        return t.exact.addFloatMsst19(curData);
    }

    /** {@code SZ_compress_float_2D_MDQ_MSST19}. */
    private static SzStorageEncoder.Classic msst19TwoD(float[] d, int r1, int r2, double realPrecision, float median) {
        int intervals = optimizeIntervals2DMsst19(d, r1, r2, realPrecision);
        SzPrecisionTable table = new SzPrecisionTable(intervals, realPrecision);
        SzStorageEncoder.Classic t = msst19Classic(r1 * r2, intervals, median, realPrecision,
                reqLengthDouble(realPrecision));
        float[] p0 = new float[r2];
        float[] p1 = new float[r2];
        p1[0] = t.exact.addFloatMsst19(d[0]);
        p1[1] = msst19Code(t, table, 1, d[1], p1[0], true);
        for (int j = 2; j < r2; j++) {
            float pred = p1[j - 1] * p1[j - 1] / p1[j - 2];
            p1[j] = msst19Code(t, table, j, d[j], pred, true);
        }
        for (int i = 1; i < r1; i++) {
            int index = i * r2;
            p0[0] = msst19Code(t, table, index, d[index], p1[0], true);
            for (int j = 1; j < r2; j++) {
                index = i * r2 + j;
                float pred = p0[j - 1] * p1[j] / p1[j - 1];
                p0[j] = msst19Code(t, table, index, d[index], pred, true);
            }
            float[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }

    /** {@code optimize_intervals_float_3D_opt_MSST19}: its ratio is the value over the prediction. */
    private static int optimizeIntervals3DMsst19(float[] d, int r1, int r2, int r3, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int sd = SzParams.SAMPLE_DISTANCE;
        int r23 = r2 * r3;
        long offsetCount = sd - 2;
        long pos = r23 + r3 + offsetCount;
        float divider = (float) (SzMath.log2(1 + realPrecision) * 2);
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
            float pred = d[q - 1] + d[q - r3] + d[q - r23] - d[q - 1 - r23] - d[q - r3 - 1] - d[q - r3 - r23]
                    + d[q - r3 - r23 - 1];
            float err = Math.abs(d[q] / pred);
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
        return SzQuantization.intervals(intervals, samples);
    }

    /** {@code SZ_compress_float_3D_MDQ_MSST19}: products and quotients in double ({@code temp}, {@code temp2}). */
    private static SzStorageEncoder.Classic msst19ThreeD(float[] d, int r1, int r2, int r3, double realPrecision,
                                                         float median) {
        int intervals = optimizeIntervals3DMsst19(d, r1, r2, r3, realPrecision);
        SzPrecisionTable table = new SzPrecisionTable(intervals, realPrecision);
        int r23 = r2 * r3;
        SzStorageEncoder.Classic t = msst19Classic(r1 * r23, intervals, median, realPrecision,
                reqLengthFloat(realPrecision));
        float[] p0 = new float[r23];
        float[] p1 = new float[r23];
        p1[0] = t.exact.addFloatMsst19(d[0]);
        p1[1] = msst19Code(t, table, 1, d[1], p1[0], true);
        for (int j = 2; j < r3; j++) {
            double temp = p1[j - 1];
            float pred = (float) (temp * temp / p1[j - 2]);
            p1[j] = msst19Code(t, table, j, d[j], pred, true);
        }
        for (int i = 1; i < r2; i++) {
            int index = i * r3;
            p1[index] = msst19Code(t, table, index, d[index], p1[index - r3], false);
            for (int j = 1; j < r3; j++) {
                index = i * r3 + j;
                double temp = p1[index - 1];
                float pred = (float) (temp * p1[index - r3] / p1[index - r3 - 1]);
                p1[index] = msst19Code(t, table, index, d[index], pred, true);
            }
        }
        for (int k = 1; k < r1; k++) {
            int index = k * r23;
            p0[0] = msst19Code(t, table, index, d[index], p1[0], true);
            for (int j = 1; j < r3; j++) {
                index++;
                double temp = p0[j - 1];
                float pred = (float) (temp * p1[j] / p1[j - 1]);
                p0[j] = msst19Code(t, table, index, d[index], pred, true);
            }
            for (int i = 1; i < r2; i++) {
                index = k * r23 + i * r3;
                int i2 = i * r3;
                double temp = p0[i2 - r3];
                float pred = (float) (temp * p1[i2] / p1[i2 - r3]);
                p0[i2] = msst19Code(t, table, index, d[index], pred, true);
                for (int j = 1; j < r3; j++) {
                    index++;
                    i2 = i * r3 + j;
                    temp = p0[i2 - 1];
                    double temp2 = p0[i2 - r3 - 1];
                    float pred3D = (float) (temp * p0[i2 - r3] * p1[i2] * p1[i2 - r3 - 1]
                            / (temp2 * p1[i2 - r3] * p1[i2 - 1]));
                    p0[i2] = msst19Code(t, table, index, d[index], pred3D, true);
                }
            }
            float[] tmp = p1;
            p1 = p0;
            p0 = tmp;
        }
        return t;
    }
}
