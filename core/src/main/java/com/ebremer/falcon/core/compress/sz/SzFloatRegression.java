package com.ebremer.falcon.core.compress.sz;

/**
 * SZ 2.1's blocked-regression coder for floats ({@code SZ_compress_float_2D_MDQ_nonblocked_with_blocked_regression}
 * and its 3-D twin): the data cut into blocks (16 by 16, or 6 by 6 by 6), each predicted either by a plane
 * fitted to it, whose coefficients are themselves quantized, or by the Lorenzo predictor on the values already
 * decoded, whichever a few sampled points favour. libSZ's arithmetic is kept as it is written (floats,
 * evaluated left to right), and so are its two prediction buffers, which hold a block row's decoded surfaces.
 * The 2-D coder computes a mean predictor and then turns it off; the 3-D one uses it when the sampled values
 * cluster around one value.
 */
final class SzFloatRegression {

    private static final int COEFF_INTV_CAPACITY = 65536;
    private static final int COEFF_INTV_RADIUS = COEFF_INTV_CAPACITY / 2;

    private SzFloatRegression() {
    }

    /** One dimension's blocks ({@code SZ_COMPUTE_*_NUMBER_OF_BLOCKS}, {@code SZ_COMPUTE_BLOCKCOUNT}). */
    private record Blocks(int count, int split, int early, int late) {
        static Blocks of(int length, int blockSize) {
            int count = length <= blockSize ? 1 : length / blockSize;
            int late = length / count;
            int split = length % count;
            return new Blocks(count, split, split != 0 ? late + 1 : late, late);
        }

        int size(int i) {
            return i < split ? early : late;
        }

        int offset(int i) {
            return i < split ? i * early : i * late + split;
        }
    }

    /** The coefficient coder's state: the codes, the unpredictable coefficients, and the last decoded ones. */
    private static final class Coefficients {
        final float[] precision;
        final float[] recip;
        final float[] last;
        final int[][] type;
        final float[][] unpred;
        final int[] unpredCount;
        int index;

        Coefficients(float[] precision, int numBlocks) {
            int n = precision.length;
            this.precision = precision;
            this.recip = new float[n];
            for (int e = 0; e < n; e++) {
                recip[e] = 1 / precision[e];
            }
            this.last = new float[n];
            this.type = new int[n][numBlocks];
            this.unpred = new float[n][numBlocks];
            this.unpredCount = new int[n];
        }

        /** Quantizes a block's coefficients against the last ones decoded (by division, or by reciprocal). */
        void add(float[] params, int block, int numBlocks, boolean byReciprocal) {
            for (int e = 0; e < precision.length; e++) {
                float cur = params[e * numBlocks + block];
                float diff = cur - last[e];
                float itvNum = byReciprocal ? Math.abs(diff) * recip[e] + 1 : Math.abs(diff) / precision[e] + 1;
                if (itvNum < COEFF_INTV_CAPACITY) {
                    if (diff < 0) {
                        itvNum = -itvNum;
                    }
                    int t = SzQuantization.toInt(itvNum / 2) + COEFF_INTV_RADIUS;
                    type[e][index] = t;
                    last[e] = last[e] + 2 * (t - COEFF_INTV_RADIUS) * precision[e];
                    if (Math.abs(cur - last[e]) > precision[e]) {
                        type[e][index] = 0;
                        last[e] = cur;
                        unpred[e][unpredCount[e]++] = cur;
                    }
                } else {
                    type[e][index] = 0;
                    last[e] = cur;
                    unpred[e][unpredCount[e]++] = cur;
                }
            }
            index++;
        }

        /** Each coefficient's precision, radius, tree, codes, and unpredictable values. */
        void write(SzBytes out) {
            for (int e = 0; e < precision.length; e++) {
                SzHuffmanEncoder tree = new SzHuffmanEncoder(2 * COEFF_INTV_CAPACITY, type[e], 0, index);
                byte[] treeBytes = tree.treeBytes();
                out.beFloat(precision[e]);
                out.be32(COEFF_INTV_RADIUS);
                out.be32(treeBytes.length);
                out.be32(tree.nodeCount());
                out.bytes(treeBytes);
                SzBytes codes = new SzBytes(index + 16);
                tree.encode(type[e], 0, index, codes);
                out.be64(codes.size());
                out.bytes(codes.toArray());
                out.be32(unpredCount[e]);
                for (int i = 0; i < unpredCount[e]; i++) {
                    out.leFloat(unpred[e][i]);
                }
            }
        }
    }

    /** The values predicted so far, and the types, in block order. */
    private static final class Output {
        final int[] type;
        final float[] unpred;
        int unpredCount;

        Output(int n) {
            type = new int[n];
            unpred = new float[n];
        }
    }

    /**
     * Quantizes {@code curData} against {@code pred} ({@code itvNum = fabsf(diff) * recip + 1}, within
     * {@code capacity}); stores the code at {@code index}; returns the decoded value.
     */
    private static float quantize(Output o, int index, float curData, float pred, float recip, float realPrecision,
                                  int capacity, int intvRadius) {
        float diff = curData - pred;
        float itvNum = Math.abs(diff) * recip + 1;
        if (itvNum < capacity) {
            if (diff < 0) {
                itvNum = -itvNum;
            }
            int t = SzQuantization.toInt(itvNum / 2) + intvRadius;
            o.type[index] = t;
            float decoded = pred + 2 * (t - intvRadius) * realPrecision;
            if (Math.abs(curData - decoded) > realPrecision) {
                o.type[index] = 0;
                o.unpred[o.unpredCount++] = curData;
                return curData;
            }
            return decoded;
        }
        o.type[index] = 0;
        o.unpred[o.unpredCount++] = curData;
        return curData;
    }

    /** The stream: {@code initRandomAccessBytes}, then the regression format's fields. */
    private static byte[] write(SzParams p, int numElements, int blockSize, float realPrecision, int intervals,
                                Output o, boolean useMean, float mean, byte[] indicator, int numBlocks, int regCount,
                                Coefficients coeffs) {
        SzHuffmanEncoder tree = new SzHuffmanEncoder(2 * intervals, o.type, 0, numElements);
        byte[] treeBytes = tree.treeBytes();
        SzBytes out = new SzBytes(numElements + 4 * o.unpredCount + treeBytes.length + 256);
        out.bytes(SzParams.VERSION);
        out.u8(0x80 | 0x40); // regression format, 8-byte sizes
        p.write(out, SzParams.META_FLOAT);
        out.be64(numElements);
        out.be32(blockSize);
        out.beFloat(realPrecision);
        out.be32(intervals);
        out.be32(treeBytes.length);
        out.be32(tree.nodeCount());
        out.bytes(treeBytes);
        out.u8(useMean ? 1 : 0);
        out.leFloat(mean);
        byte[] bits = new byte[(numBlocks + 7) / 8];
        for (int i = 0; i < numBlocks; i++) {
            if (indicator[i] == 1) {
                bits[i >> 3] |= (byte) (0x80 >>> (i & 7));
            }
        }
        out.bytes(bits);
        if (regCount > 0) {
            coeffs.write(out);
        }
        out.le64(o.unpredCount);
        for (int i = 0; i < o.unpredCount; i++) {
            out.leFloat(o.unpred[i]);
        }
        tree.encode(o.type, 0, numElements, out);
        return out.toArray();
    }

    // ------------------------------------------------------------------ 2-D

    /** {@code optimize_intervals_float_2D_with_freq_and_dense_pos}, of which the 2-D coder keeps the count. */
    private static int optimizeIntervals2D(float[] d, int r1, int r2, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        long len = (long) r1 * r2;
        int sd = SzParams.SAMPLE_DISTANCE;
        long offsetCount = sd - 1;
        long n1Count = 1;
        long samples = 0;
        long pos = r2 + offsetCount;
        while (pos < len) {
            int p = (int) pos;
            float pred = d[p - 1] + d[p - r2] - d[p - r2 - 1];
            float err = Math.abs(pred - d[p]);
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
            samples++;
        }
        return SzQuantization.intervals(intervals, samples);
    }

    /** {@code SZ_compress_float_2D_MDQ_nonblocked_with_blocked_regression}: {@code r1} rows of {@code r2}. */
    static byte[] compress2D(SzParams p, float[] d, int r1, int r2, float realPrecision) {
        float recip = 1 / realPrecision;
        int intervals = optimizeIntervals2D(d, r1, r2, realPrecision);
        int blockSize = 16;
        Blocks bx = Blocks.of(r1, blockSize);
        Blocks by = Blocks.of(r2, blockSize);
        int numBlocks = bx.count * by.count;
        int numElements = r1 * r2;
        int dim0 = r2;

        float[] reg = new float[numBlocks * 4];
        int nb = numBlocks;
        int block = 0;
        for (int i = 0; i < bx.count; i++) {
            for (int j = 0; j < by.count; j++) {
                int cx = bx.size(i);
                int cy = by.size(j);
                int at = bx.offset(i) * dim0 + by.offset(j);
                float fx = 0;
                float fy = 0;
                float f = 0;
                for (int ii = 0; ii < cx; ii++) {
                    float sumX = 0;
                    for (int jj = 0; jj < cy; jj++) {
                        float cur = d[at + ii * dim0 + jj];
                        sumX += cur;
                        fy += cur * jj;
                    }
                    fx += sumX * ii;
                    f += sumX;
                }
                float coeff = (float) (1.0 / ((long) cx * cy));
                reg[block] = (2 * fx / (cx - 1) - f) * 6 * coeff / (cx + 1);
                reg[nb + block] = (2 * fy / (cy - 1) - f) * 6 * coeff / (cy + 1);
                reg[2 * nb + block] = f * coeff - ((cx - 1) * reg[block] / 2 + (cy - 1) * reg[nb + block] / 2);
                block++;
            }
        }

        float relParamErr = (float) (0.15 / 3);
        float[] precision = {relParamErr * realPrecision / bx.late, relParamErr * realPrecision / by.late,
            relParamErr * realPrecision};
        Coefficients coeffs = new Coefficients(precision, numBlocks);
        float noise = (float) (realPrecision * 0.81);
        int intvCapacity = intervals;
        int intvRadius = intvCapacity / 2;
        int capacitySz = intvCapacity - 2;

        int strip = r2 + 1; // strip_dim0_offset
        int bufSize = (bx.early + 1) * strip;
        float[] cur = new float[bufSize];
        float[] next = new float[bufSize];
        byte[] indicator = new byte[numBlocks];
        Output o = new Output(numElements);
        int typeAt = 0;
        int regCount = 0;
        block = 0;
        for (int i = 0; i < bx.count; i++) {
            int cx = bx.size(i);
            int dataRow = bx.offset(i) * dim0;
            int pb = strip + 1;  // cur_pb_buf_pos
            int npb = 1;         // next_pb_buf_pos
            int dataPos = dataRow;
            for (int j = 0; j < by.count; j++) {
                int cy = by.size(j);
                // sampling: Lorenzo on the original values against the fitted plane
                float errSz = 0;
                float errReg = 0;
                int bs = Math.min(cx, cy);
                for (int k = 1; k < bs; k++) {
                    int c = dataPos + k * dim0 + k;
                    float curData = d[c];
                    float predSz = d[c - 1] + d[c - dim0] - d[c - dim0 - 1];
                    float predReg = reg[block] * k + reg[nb + block] * k + reg[2 * nb + block];
                    errSz += Math.abs(predSz - curData) + noise;
                    errReg += Math.abs(predReg - curData);
                    int bmi = bs - k;
                    c = dataPos + k * dim0 + bmi;
                    curData = d[c];
                    predSz = d[c - 1] + d[c - dim0] - d[c - dim0 - 1];
                    predReg = reg[block] * (k - 1) + reg[nb + block] * bmi + reg[2 * nb + block];
                    errSz += Math.abs(predSz - curData) + noise;
                    errReg += Math.abs(predReg - curData);
                }
                if (errReg < errSz) {
                    coeffs.add(reg, block, nb, false);
                    float[] last = coeffs.last;
                    int index = typeAt;
                    for (int ii = 0; ii < cx; ii++) {
                        for (int jj = 0; jj < cy; jj++) {
                            float curData = d[dataPos + ii * dim0 + jj];
                            float pred = last[0] * ii + last[1] * jj + last[2];
                            pred = quantize(o, index++, curData, pred, recip, realPrecision, intvCapacity, intvRadius);
                            if (jj == cy - 1) {
                                cur[pb + ii * strip + jj] = pred;
                            }
                            if (ii == cx - 1) {
                                next[npb + jj] = pred;
                            }
                        }
                    }
                    regCount++;
                } else {
                    int index = typeAt;
                    for (int ii = 0; ii < cx; ii++) {
                        for (int jj = 0; jj < cy; jj++) {
                            int q = pb + ii * strip + jj;
                            float curData = d[dataPos + ii * dim0 + jj];
                            float pred2D = cur[q - 1] + cur[q - strip] - cur[q - strip - 1];
                            cur[q] = quantize(o, index++, curData, pred2D, recip, realPrecision, capacitySz, intvRadius);
                            if (ii == cx - 1) {
                                next[npb + jj] = cur[q];
                            }
                        }
                    }
                    indicator[block] = 1;
                }
                block++;
                dataPos += cy;
                pb += cy;
                npb += cy;
                typeAt += cx * cy;
            }
            float[] t = cur;
            cur = next;
            next = t;
        }
        return write(p, numElements, blockSize, realPrecision, intervals, o, false, 0, indicator, numBlocks, regCount,
                coeffs);
    }

    // ------------------------------------------------------------------ 3-D

    /** What {@code optimize_intervals_float_3D_with_freq_and_dense_pos} finds. */
    private record Optimized3D(int intervals, float densePos, float maxFreq, float meanFreq) {
    }

    /** {@code optimize_intervals_float_3D_with_freq_and_dense_pos}. */
    private static Optimized3D optimizeIntervals3D(float[] d, int r1, int r2, int r3, double realPrecision) {
        long len = (long) r1 * r2 * r3;
        long meanDistance = SzQuantization.toInt(Math.sqrt((double) len));
        float mean = 0;
        long pos = 0;
        long offsetCount = 0;
        long offsetCount2 = 0;
        long meanCount = 0;
        long r23 = (long) r2 * r3;
        while (pos < len) {
            mean += d[(int) pos];
            meanCount++;
            pos += meanDistance;
            offsetCount += meanDistance;
            offsetCount2 += meanDistance;
            if (offsetCount >= r3) {
                offsetCount = 0;
                pos -= 1;
            }
            if (offsetCount2 >= r23) {
                offsetCount2 = 0;
                pos -= 1;
            }
        }
        if (meanCount > 0) {
            mean /= SzQuantization.unsignedToFloat(meanCount);
        }
        int range = 8192;
        int radius = 4096;
        long[] freqIntervals = new long[range];
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int sd = SzParams.SAMPLE_DISTANCE;
        long freqCount = 0;
        long samples = 0;
        offsetCount = sd - 2;
        pos = r23 + r3 + offsetCount;
        long n1Count = 1;
        long n2Count = 1;
        while (pos < len) {
            int p = (int) pos;
            float pred = d[p - 1] + d[p - r3] + d[p - (int) r23] - d[p - 1 - (int) r23] - d[p - r3 - 1]
                    - d[p - r3 - (int) r23] + d[p - r3 - (int) r23 - 1];
            float err = Math.abs(pred - d[p]);
            if (err < realPrecision) {
                freqCount++;
            }
            intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
            float meanDiff = d[p] - mean;
            long freqIndex = meanDiff > 0 ? SzQuantization.toLong(meanDiff / realPrecision) + radius
                    : SzQuantization.toLong(meanDiff / realPrecision) - 1 + radius;
            if (freqIndex <= 0) {
                freqIntervals[0]++;
            } else if (Long.compareUnsigned(freqIndex, range) >= 0) {
                freqIntervals[range - 1]++;
            } else {
                freqIntervals[(int) freqIndex]++;
            }
            offsetCount += sd;
            if (offsetCount >= r3) {
                n2Count++;
                if (n2Count == r2) {
                    n1Count++;
                    n2Count = 1;
                    pos += r3;
                }
                offsetCount2 = (n1Count + n2Count) % sd;
                pos += (r3 + sd - offsetCount) + (sd - offsetCount2);
                offsetCount = sd - offsetCount2;
                if (offsetCount == 0) {
                    offsetCount++;
                }
            } else {
                pos += sd;
            }
            samples++;
        }
        float maxFreq = (float) (freqCount * 1.0 / samples);
        int count = SzQuantization.intervals(intervals, samples);
        long maxSum = 0;
        long maxIndex = 0;
        for (int i = 1; i < range - 2; i++) {
            long tmpSum = freqIntervals[i] + freqIntervals[i + 1];
            if (tmpSum > maxSum) {
                maxSum = tmpSum;
                maxIndex = i;
            }
        }
        float densePos = (float) (mean + realPrecision * (double) (maxIndex + 1 - radius));
        float meanFreq = (float) (maxSum * 1.0 / samples);
        return new Optimized3D(count, densePos, maxFreq, meanFreq);
    }

    /**
     * {@code SZ_compress_float_3D_MDQ_nonblocked_with_blocked_regression}: {@code r1} layers of {@code r2} rows of
     * {@code r3}.
     */
    static byte[] compress3D(SzParams p, float[] d, int r1, int r2, int r3, float realPrecision) {
        float recip = 1 / realPrecision;
        int blockSize = 6;
        Blocks bx = Blocks.of(r1, blockSize);
        Blocks by = Blocks.of(r2, blockSize);
        Blocks bz = Blocks.of(r3, blockSize);
        int numBlocks = bx.count * by.count * bz.count;
        int numElements = r1 * r2 * r3;
        int dim0 = r2 * r3;
        int dim1 = r3;

        float[] reg = new float[numBlocks * 4];
        int nb = numBlocks;
        int block = 0;
        for (int i = 0; i < bx.count; i++) {
            for (int j = 0; j < by.count; j++) {
                for (int k = 0; k < bz.count; k++) {
                    int cx = bx.size(i);
                    int cy = by.size(j);
                    int cz = bz.size(k);
                    int at = bx.offset(i) * dim0 + by.offset(j) * dim1 + bz.offset(k);
                    float fx = 0;
                    float fy = 0;
                    float fz = 0;
                    float f = 0;
                    for (int ii = 0; ii < cx; ii++) {
                        float sumX = 0;
                        for (int jj = 0; jj < cy; jj++) {
                            float sumY = 0;
                            for (int kk = 0; kk < cz; kk++) {
                                float cur = d[at + ii * dim0 + jj * dim1 + kk];
                                sumY += cur;
                                fz += cur * kk;
                            }
                            fy += sumY * jj;
                            sumX += sumY;
                        }
                        fx += sumX * ii;
                        f += sumX;
                    }
                    float coeff = (float) (1.0 / ((long) cx * cy * cz));
                    reg[block] = (2 * fx / (cx - 1) - f) * 6 * coeff / (cx + 1);
                    reg[nb + block] = (2 * fy / (cy - 1) - f) * 6 * coeff / (cy + 1);
                    reg[2 * nb + block] = (2 * fz / (cz - 1) - f) * 6 * coeff / (cz + 1);
                    reg[3 * nb + block] = f * coeff - ((cx - 1) * reg[block] / 2 + (cy - 1) * reg[nb + block] / 2
                            + (cz - 1) * reg[2 * nb + block] / 2);
                    block++;
                }
            }
        }

        float relParamErr = 0.025f;
        float[] precision = {relParamErr * realPrecision / bx.late, relParamErr * realPrecision / by.late,
            relParamErr * realPrecision / bz.late, relParamErr * realPrecision};

        Optimized3D opt = optimizeIntervals3D(d, r1, r2, r3, realPrecision);
        int intervals = opt.intervals;
        boolean useMean = opt.meanFreq > 0.5 || opt.meanFreq > opt.maxFreq;
        float mean = 0;
        if (useMean) {
            float sum = 0;
            long meanCount = 0;
            for (int i = 0; i < numElements; i++) {
                if (Math.abs(d[i] - opt.densePos) < realPrecision) {
                    sum += d[i];
                    meanCount++;
                }
            }
            if (meanCount > 0) {
                mean = sum / meanCount;
            }
        }

        Coefficients coeffs = new Coefficients(precision, numBlocks);
        float noise = (float) (realPrecision * 1.22);
        int intvCapacity = intervals;
        int intvRadius = intvCapacity / 2;
        int capacitySz = intvCapacity - 2;

        int s2 = r3 + 1;                // strip_dim1_offset
        int s1 = (r2 + 1) * s2;         // strip_dim0_offset
        int bufSize = (bx.early + 1) * s1;
        float[] cur = new float[bufSize];
        float[] next = new float[bufSize];
        byte[] indicator = new byte[numBlocks];
        Output o = new Output(numElements);
        int regCount = 0;
        block = 0;
        for (int i = 0; i < bx.count; i++) {
            int cx = bx.size(i);
            int offsetX = bx.offset(i);
            for (int j = 0; j < by.count; j++) {
                int cy = by.size(j);
                int offsetY = by.offset(j);
                int dataPos = offsetX * dim0 + offsetY * dim1;
                int typeAt = offsetX * dim0 + offsetY * cx * dim1;
                int pb = offsetY * s2 + s1 + s2 + 1;
                int npb = offsetY * s2 + s2 + 1;
                for (int k = 0; k < bz.count; k++) {
                    int cz = bz.size(k);
                    float errSz = 0;
                    float errReg = 0;
                    int bs = Math.min(cx, Math.min(cy, cz));
                    for (int q = 1; q < bs; q++) {
                        int bmi = bs - q;
                        int[][] points = {{q, q, q}, {q, q, bmi}, {q, bmi, q}, {q, bmi, bmi}};
                        for (int[] pt : points) {
                            int c = dataPos + pt[0] * dim0 + pt[1] * dim1 + pt[2];
                            float curData = d[c];
                            float predSz = d[c - 1] + d[c - dim1] + d[c - dim0] - d[c - dim1 - 1] - d[c - dim0 - 1]
                                    - d[c - dim0 - dim1] + d[c - dim0 - dim1 - 1];
                            float predReg = reg[block] * pt[0] + reg[nb + block] * pt[1] + reg[2 * nb + block] * pt[2]
                                    + reg[3 * nb + block];
                            if (useMean) {
                                float a = Math.abs(predSz - curData) + noise;
                                float b = Math.abs(mean - curData);
                                errSz += a < b ? a : b;
                            } else {
                                errSz += Math.abs(predSz - curData) + noise;
                            }
                            errReg += Math.abs(predReg - curData);
                        }
                    }
                    if (errReg < errSz) {
                        coeffs.add(reg, block, nb, useMean);
                        float[] last = coeffs.last;
                        int index = typeAt;
                        for (int ii = 0; ii < cx; ii++) {
                            for (int jj = 0; jj < cy; jj++) {
                                for (int kk = 0; kk < cz; kk++) {
                                    float curData = d[dataPos + ii * dim0 + jj * dim1 + kk];
                                    float pred = last[0] * ii + last[1] * jj + last[2] * kk + last[3];
                                    pred = quantize(o, index++, curData, pred, recip, realPrecision, intvCapacity,
                                            intvRadius);
                                    if (jj == cy - 1 || kk == cz - 1) {
                                        cur[pb + ii * s1 + jj * s2 + kk] = pred;
                                    }
                                    if (ii == cx - 1) {
                                        next[npb + jj * s2 + kk] = pred;
                                    }
                                }
                            }
                        }
                        regCount++;
                    } else {
                        int index = typeAt;
                        for (int ii = 0; ii < cx; ii++) {
                            for (int jj = 0; jj < cy; jj++) {
                                for (int kk = 0; kk < cz; kk++) {
                                    int q = pb + ii * s1 + jj * s2 + kk;
                                    float curData = d[dataPos + ii * dim0 + jj * dim1 + kk];
                                    if (useMean && Math.abs(curData - mean) <= realPrecision) {
                                        o.type[index++] = intvRadius;
                                        cur[q] = mean;
                                    } else {
                                        float pred3D = cur[q - 1] + cur[q - s2] + cur[q - s1] - cur[q - s2 - 1]
                                                - cur[q - s1 - 1] - cur[q - s1 - s2] + cur[q - s1 - s2 - 1];
                                        cur[q] = quantize(o, index, curData, pred3D, recip, realPrecision, capacitySz,
                                                intvRadius);
                                        if (useMean && o.type[index] != 0 && o.type[index] <= intvRadius) {
                                            o.type[index] -= 1;
                                        }
                                        index++;
                                    }
                                    if (ii == cx - 1) {
                                        next[npb + jj * s2 + kk] = cur[q];
                                    }
                                }
                            }
                        }
                        indicator[block] = 1;
                    }
                    block++;
                    dataPos += cz;
                    pb += cz;
                    npb += cz;
                    typeAt += cx * cy * cz;
                }
            }
            float[] t = cur;
            cur = next;
            next = t;
        }
        return write(p, numElements, blockSize, realPrecision, intervals, o, useMean, mean, indicator, numBlocks,
                regCount, coeffs);
    }
}
