package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * SZ 2.1.12's double decoders ({@code szd_double.c}, {@code szd_double_pwr.c}): {@link SzFloats}'s, in double
 * arithmetic throughout, with the operations in libSZ's order. Arrays are C order: the last dimension
 * varies fastest.
 */
final class SzDoubles {

    private SzDoubles() {
    }

    /** {@code SZ_decompress_args_double} after the lossless stage, for dimensions already filtered. */
    static double[] decode(SzStorage s, long r5, long r4, long r3, long r2, long r1, int n) {
        int dim = SzDecoder.dimension(r5, r4, r3, r2, r1);
        double[] data;
        if (s.lossless) {
            data = new double[n];
            int at = s.dataAt;
            for (int i = 0; i < n; i++) {
                data[i] = s.in.beDouble(at + 8L * i);
            }
        } else if (s.solId == SzDecoder.SZ_TRANSPOSE) {
            data = snapshot1D(s, n);
        } else if (s.regression) {
            data = switch (dim) {
                case 1 -> snapshot1D(s, (int) r1);
                case 2 -> regression2D(s, (int) r2, (int) r1);
                case 3 -> regression3D(s, (int) r3, (int) r2, (int) r1);
                case 4 -> regression3D(s, (int) (r4 * r3), (int) r2, (int) r1);
                default -> throw new CompressionFormatException("SZ decodes at most 4 dimensions, not " + dim);
            };
        } else {
            data = switch (dim) {
                case 1 -> snapshot1D(s, (int) r1);
                case 2 -> snapshot2D(s, (int) r2, (int) r1);
                case 3 -> snapshot3D(s, (int) r3, (int) r2, (int) r1);
                case 4 -> snapshot4D(s, (int) r4, (int) r3, (int) r2, (int) r1);
                default -> throw new CompressionFormatException("SZ decodes at most 4 dimensions, not " + dim);
            };
        }
        if (s.protectValueRange) {
            double min = s.min;
            double max = s.max;
            for (int i = 0; i < n; i++) {
                double v = data[i];
                if (v <= max && v >= min) {
                    continue;
                }
                if (v < min) {
                    data[i] = min;
                } else if (v > max) {
                    data[i] = max;
                }
            }
        }
        return data;
    }

    // ------------------------------------------------------------------ getSnapshotData_double_*D

    private static double[] same(SzStorage s, int n) {
        double[] data = new double[n];
        java.util.Arrays.fill(data, s.in.beDouble(s.dataAt));
        return data;
    }

    private static double[] snapshot1D(SzStorage s, int n) {
        if (s.allSame) {
            return same(s, n);
        }
        if (!s.pwRel) {
            return classic1D(s, n);
        }
        return s.accelerate ? preLogMsst19(s, msst19OneD(s, n)) : preLog(s, classic1D(s, n));
    }

    private static double[] snapshot2D(SzStorage s, int r1, int r2) {
        if (s.allSame) {
            return same(s, r1 * r2);
        }
        if (!s.pwRel) {
            return classic2D(s, r1, r2);
        }
        return s.accelerate ? preLogMsst19(s, msst19TwoD(s, r1, r2)) : preLog(s, classic2D(s, r1, r2));
    }

    private static double[] snapshot3D(SzStorage s, int r1, int r2, int r3) {
        if (s.allSame) {
            return same(s, r1 * r2 * r3);
        }
        if (!s.pwRel) {
            return classic3D(s, r1, r2, r3);
        }
        return s.accelerate ? preLogMsst19(s, msst19ThreeD(s, r1, r2, r3)) : preLog(s, classic3D(s, r1, r2, r3));
    }

    private static double[] snapshot4D(SzStorage s, int r1, int r2, int r3, int r4) {
        if (s.allSame) {
            return same(s, r1 * r2 * r3 * r4);
        }
        if (!s.pwRel) {
            return classic4D(s, r1, r2, r3, r4);
        }
        return s.accelerate ? preLogMsst19(s, msst19ThreeD(s, r1 * r2, r3, r4))
                : preLog(s, classic3D(s, r1 * r2, r3, r4));
    }

    // ------------------------------------------------------------------ the classic (Lorenzo) format

    /** {@code decompressDataSeries_double_1D}. */
    private static double[] classic1D(SzStorage s, int n) {
        int intvRadius = s.intervals / 2;
        double interval = s.realPrecision * 2;
        SzExact exact = s.exact();
        int[] type = s.types(n);
        double median = s.medianValue;
        double[] data = new double[n];
        for (int i = 0; i < n; i++) {
            int t = type[i];
            if (t == 0) {
                data[i] = exact.nextDouble() + median;
            } else {
                data[i] = data[i - 1] + (t - intvRadius) * interval;
            }
        }
        return data;
    }

    /** {@code decompressDataSeries_double_2D}: {@code r1} rows of {@code r2}. */
    private static double[] classic2D(SzStorage s, int r1, int r2) {
        int r = s.intervals / 2;
        double rp = s.realPrecision;
        SzExact exact = s.exact();
        int n = r1 * r2;
        int[] type = s.types(n);
        double median = s.medianValue;
        double[] d = new double[n];
        d[0] = exact.nextDouble() + median;
        int t = type[1];
        d[1] = t != 0 ? d[0] + 2 * (t - r) * rp : exact.nextDouble() + median;
        for (int jj = 2; jj < r2; jj++) {
            t = type[jj];
            d[jj] = t != 0 ? (2 * d[jj - 1] - d[jj - 2]) + 2 * (t - r) * rp : exact.nextDouble() + median;
        }
        for (int ii = 1; ii < r1; ii++) {
            int index = ii * r2;
            t = type[index];
            d[index] = t != 0 ? d[index - r2] + 2 * (t - r) * rp : exact.nextDouble() + median;
            for (int jj = 1; jj < r2; jj++) {
                index = ii * r2 + jj;
                t = type[index];
                d[index] = t != 0 ? (d[index - 1] + d[index - r2] - d[index - r2 - 1]) + 2 * (t - r) * rp
                        : exact.nextDouble() + median;
            }
        }
        return d;
    }

    /** {@code decompressDataSeries_double_3D}: {@code r1} layers of {@code r2} rows of {@code r3}. */
    private static double[] classic3D(SzStorage s, int r1, int r2, int r3) {
        int r = s.intervals / 2;
        double rp = s.realPrecision;
        SzExact exact = s.exact();
        int r23 = r2 * r3;
        int n = r1 * r23;
        int[] type = s.types(n);
        double median = s.medianValue;
        double[] d = new double[n];
        d[0] = exact.nextDouble() + median;
        int t = type[1];
        d[1] = t != 0 ? d[0] + 2 * (t - r) * rp : exact.nextDouble() + median;
        for (int jj = 2; jj < r3; jj++) {
            t = type[jj];
            d[jj] = t != 0 ? (2 * d[jj - 1] - d[jj - 2]) + 2 * (t - r) * rp : exact.nextDouble() + median;
        }
        for (int ii = 1; ii < r2; ii++) {
            int index = ii * r3;
            t = type[index];
            d[index] = t != 0 ? d[index - r3] + 2 * (t - r) * rp : exact.nextDouble() + median;
            for (int jj = 1; jj < r3; jj++) {
                index = ii * r3 + jj;
                t = type[index];
                d[index] = t != 0 ? (d[index - 1] + d[index - r3] - d[index - r3 - 1]) + 2 * (t - r) * rp
                        : exact.nextDouble() + median;
            }
        }
        for (int kk = 1; kk < r1; kk++) {
            int index = kk * r23;
            t = type[index];
            d[index] = t != 0 ? d[index - r23] + 2 * (t - r) * rp : exact.nextDouble() + median;
            for (int jj = 1; jj < r3; jj++) {
                index = kk * r23 + jj;
                t = type[index];
                d[index] = t != 0 ? (d[index - 1] + d[index - r23] - d[index - r23 - 1]) + 2 * (t - r) * rp
                        : exact.nextDouble() + median;
            }
            for (int ii = 1; ii < r2; ii++) {
                index = kk * r23 + ii * r3;
                t = type[index];
                d[index] = t != 0 ? (d[index - r3] + d[index - r23] - d[index - r23 - r3]) + 2 * (t - r) * rp
                        : exact.nextDouble() + median;
                for (int jj = 1; jj < r3; jj++) {
                    index = kk * r23 + ii * r3 + jj;
                    t = type[index];
                    d[index] = t != 0 ? (d[index - 1] + d[index - r3] + d[index - r23] - d[index - r3 - 1]
                            - d[index - r23 - r3] - d[index - r23 - 1] + d[index - r23 - r3 - 1]) + 2 * (t - r) * rp
                            : exact.nextDouble() + median;
                }
            }
        }
        return d;
    }

    /** {@code decompressDataSeries_double_4D}: the first value of each {@code r2·r3·r4} block is stored. */
    private static double[] classic4D(SzStorage s, int r1, int r2, int r3, int r4) {
        int r = s.intervals / 2;
        double rp = s.realPrecision;
        SzExact exact = s.exact();
        int r34 = r3 * r4;
        int r234 = r2 * r34;
        int n = r1 * r234;
        int[] type = s.types(n);
        double median = s.medianValue;
        double[] d = new double[n];
        for (int ll = 0; ll < r1; ll++) {
            int index = ll * r234;
            d[index] = exact.nextDouble() + median;
            index = ll * r234 + 1;
            int t = type[index];
            d[index] = t != 0 ? d[index - 1] + 2 * (t - r) * rp : exact.nextDouble() + median;
            for (int jj = 2; jj < r4; jj++) {
                index = ll * r234 + jj;
                t = type[index];
                d[index] = t != 0 ? (2 * d[index - 1] - d[index - 2]) + 2 * (t - r) * rp
                        : exact.nextDouble() + median;
            }
            for (int ii = 1; ii < r3; ii++) {
                index = ll * r234 + ii * r4;
                t = type[index];
                d[index] = t != 0 ? d[index - r4] + 2 * (t - r) * rp : exact.nextDouble() + median;
                for (int jj = 1; jj < r4; jj++) {
                    index = ll * r234 + ii * r4 + jj;
                    t = type[index];
                    d[index] = t != 0 ? (d[index - 1] + d[index - r4] - d[index - r4 - 1]) + 2 * (t - r) * rp
                            : exact.nextDouble() + median;
                }
            }
            for (int kk = 1; kk < r2; kk++) {
                index = ll * r234 + kk * r34;
                t = type[index];
                d[index] = t != 0 ? d[index - r34] + 2 * (t - r) * rp : exact.nextDouble() + median;
                for (int jj = 1; jj < r4; jj++) {
                    index = ll * r234 + kk * r34 + jj;
                    t = type[index];
                    d[index] = t != 0 ? (d[index - 1] + d[index - r34] - d[index - r34 - 1]) + 2 * (t - r) * rp
                            : exact.nextDouble() + median;
                }
                for (int ii = 1; ii < r3; ii++) {
                    index = ll * r234 + kk * r34 + ii * r4;
                    t = type[index];
                    d[index] = t != 0 ? (d[index - r4] + d[index - r34] - d[index - r34 - r4]) + 2 * (t - r) * rp
                            : exact.nextDouble() + median;
                    for (int jj = 1; jj < r4; jj++) {
                        index = ll * r234 + kk * r34 + ii * r4 + jj;
                        t = type[index];
                        d[index] = t != 0 ? (d[index - 1] + d[index - r4] + d[index - r34] - d[index - r4 - 1]
                                - d[index - r34 - r4] - d[index - r34 - 1] + d[index - r34 - r4 - 1]) + 2 * (t - r) * rp
                                : exact.nextDouble() + median;
                    }
                }
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ point-wise relative bounds

    /**
     * {@code decompressDataSeries_double_*D_pwr_pre_log}: the classic decoder ran on the values' base-2
     * logarithms; below {@code minLogValue} a value was zero, and the signs come zstd-compressed.
     */
    private static double[] preLog(SzStorage s, double[] data) {
        double threshold = s.minLogValue;
        byte[] signs = s.pwrErrBoundSize > 0 ? SzDecoder.signs(s, data.length) : null;
        for (int i = 0; i < data.length; i++) {
            if (data[i] < threshold) {
                data[i] = 0;
            } else {
                data[i] = SzDecoder.exp2(data[i]);
            }
            if (signs != null && signs[i] != 0) {
                data[i] = -data[i];
            }
        }
        return data;
    }

    /** {@code decompressDataSeries_double_*D_pwr_pre_log_MSST19}: zeros and the signs on magnitudes. */
    private static double[] preLogMsst19(SzStorage s, double[] data) {
        double threshold = s.minLogValue;
        if (s.pwrErrBoundSize > 0) {
            byte[] signs = SzDecoder.signs(s, data.length);
            for (int i = 0; i < data.length; i++) {
                if (data[i] < threshold && data[i] >= 0) {
                    data[i] = 0;
                    continue;
                }
                if (signs[i] != 0) {
                    data[i] = Double.longBitsToDouble(Double.doubleToRawLongBits(data[i]) | 0x8000000000000000L);
                }
            }
        } else {
            for (int i = 0; i < data.length; i++) {
                if (data[i] < threshold) {
                    data[i] = 0;
                }
            }
        }
        return data;
    }

    /** {@code decompressDataSeries_double_1D_MSST19}: magnitudes predicted multiplicatively. */
    private static double[] msst19OneD(SzStorage s, int n) {
        SzDecoder.PrecisionTable table = new SzDecoder.PrecisionTable(s);
        SzExact exact = s.exact();
        int[] type = s.types(n);
        double[] data = new double[n];
        double predValue = 0;
        for (int i = 0; i < n; i++) {
            int t = type[i];
            if (t == 0) {
                data[i] = exact.nextDouble();
                predValue = data[i];
            } else {
                predValue = Math.abs(predValue) * table.get(t);
                data[i] = predValue;
            }
        }
        return data;
    }

    /** {@code decompressDataSeries_double_2D_MSST19}. */
    private static double[] msst19TwoD(SzStorage s, int r1, int r2) {
        SzDecoder.PrecisionTable table = new SzDecoder.PrecisionTable(s);
        SzExact exact = s.exact();
        int n = r1 * r2;
        int[] type = s.types(n);
        double[] d = new double[n];
        d[0] = exact.nextDouble();
        int t = type[1];
        d[1] = t != 0 ? Math.abs(d[0]) * table.get(t) : exact.nextDouble();
        for (int jj = 2; jj < r2; jj++) {
            t = type[jj];
            if (t != 0) {
                double pred1D = d[jj - 1] * d[jj - 1] / d[jj - 2];
                d[jj] = Math.abs(pred1D) * table.get(t);
            } else {
                d[jj] = exact.nextDouble();
            }
        }
        for (int ii = 1; ii < r1; ii++) {
            int index = ii * r2;
            t = type[index];
            d[index] = t != 0 ? Math.abs(d[index - r2]) * table.get(t) : exact.nextDouble();
            for (int jj = 1; jj < r2; jj++) {
                index = ii * r2 + jj;
                double pred2D = d[index - 1] * d[index - r2] / d[index - r2 - 1];
                t = type[index];
                d[index] = t != 0 ? Math.abs(pred2D) * table.get(t) : exact.nextDouble();
            }
        }
        return d;
    }

    /** {@code decompressDataSeries_double_3D_MSST19}. */
    private static double[] msst19ThreeD(SzStorage s, int r1, int r2, int r3) {
        SzDecoder.PrecisionTable table = new SzDecoder.PrecisionTable(s);
        SzExact exact = s.exact();
        int r23 = r2 * r3;
        int n = r1 * r23;
        int[] type = s.types(n);
        double[] d = new double[n];
        d[0] = exact.nextDouble();
        int t = type[1];
        d[1] = t != 0 ? Math.abs(d[0]) * table.get(t) : exact.nextDouble();
        for (int jj = 2; jj < r3; jj++) {
            double temp = d[jj - 1];
            double pred1D = temp * d[jj - 1] / d[jj - 2];
            t = type[jj];
            d[jj] = t != 0 ? Math.abs(pred1D) * table.get(t) : exact.nextDouble();
        }
        for (int ii = 1; ii < r2; ii++) {
            int index = ii * r3;
            double pred1D = d[index - r3];
            t = type[index];
            d[index] = t != 0 ? Math.abs(pred1D) * table.get(t) : exact.nextDouble();
            for (int jj = 1; jj < r3; jj++) {
                index = ii * r3 + jj;
                double temp = d[index - 1];
                double pred2D = temp * d[index - r3] / d[index - r3 - 1];
                t = type[index];
                d[index] = t != 0 ? Math.abs(pred2D) * table.get(t) : exact.nextDouble();
            }
        }
        for (int kk = 1; kk < r1; kk++) {
            int index = kk * r23;
            double pred1D = d[index - r23];
            t = type[index];
            d[index] = t != 0 ? Math.abs(pred1D) * table.get(t) : exact.nextDouble();
            for (int jj = 1; jj < r3; jj++) {
                index = kk * r23 + jj;
                double temp = d[index - 1];
                double pred2D = temp * d[index - r23] / d[index - r23 - 1];
                t = type[index];
                d[index] = t != 0 ? Math.abs(pred2D) * table.get(t) : exact.nextDouble();
            }
            for (int ii = 1; ii < r2; ii++) {
                index = kk * r23 + ii * r3;
                double temp = d[index - r3];
                double pred2D = temp * d[index - r23] / d[index - r23 - r3];
                t = type[index];
                d[index] = t != 0 ? Math.abs(pred2D) * table.get(t) : exact.nextDouble();
                for (int jj = 1; jj < r3; jj++) {
                    index = kk * r23 + ii * r3 + jj;
                    temp = d[index - 1];
                    double temp2 = d[index - r3 - 1];
                    double pred3D = temp * d[index - r3] * d[index - r23] * d[index - r23 - r3 - 1]
                            / (temp2 * d[index - r23 - r3] * d[index - r23 - 1]);
                    t = type[index];
                    d[index] = t != 0 ? Math.abs(pred3D) * table.get(t) : exact.nextDouble();
                }
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ the regression format (SZ 2.1)

    /**
     * {@code decompressDataSeries_double_2D_nonblocked_with_blocked_regression}: blocks predicted by Lorenzo
     * or by a plane whose coefficients are themselves quantized.
     */
    private static double[] regression2D(SzStorage s, int r1, int r2) {
        SzBuffer in = s.in;
        long pos = s.dataAt;
        SzDecoder.Blocks bx;
        SzDecoder.Blocks by;
        long blockSize = in.be32(pos);
        pos += 4;
        bx = new SzDecoder.Blocks(r1, blockSize);
        by = new SzDecoder.Blocks(r2, blockSize);
        int numBlocks = Math.multiplyExact(bx.count, by.count);
        double realPrecision = in.beDouble(pos);
        pos += 8;
        int intervals = SzDecoder.intervals(in.be32(pos));
        pos += 4;
        long treeSize = in.be32(pos) & 0xFFFFFFFFL;
        pos += 4;
        SzHuffman root = SzHuffman.read(in, (int) pos + 4, in.be32(pos));
        pos += 4 + treeSize;
        boolean useMean = in.u8(pos) != 0;
        pos += 1;
        double mean = in.leDouble(pos);
        pos += 8;
        boolean[] indicator = in.oneBit(pos, numBlocks);
        pos += (numBlocks - 1) / 8 + 1;
        int regCount = 0;
        for (boolean sz : indicator) {
            regCount += sz ? 0 : 1;
        }
        int coefficients = 3;
        double[] precision = new double[coefficients];
        int[] coeffIntvRadius = new int[coefficients];
        int[][] coeffType = new int[coefficients][];
        long[] coeffUnpredAt = new long[coefficients];
        if (regCount > 0) {
            for (int e = 0; e < coefficients; e++) {
                precision[e] = in.beDouble(pos);
                pos += 8;
                coeffIntvRadius[e] = in.be32(pos);
                pos += 4;
                long size = in.be32(pos) & 0xFFFFFFFFL;
                pos += 4;
                SzHuffman tree = SzHuffman.read(in, (int) pos + 4, in.be32(pos));
                pos += 4 + size;
                long typeArraySize = in.size(pos, s.sizeType);
                coeffType[e] = new int[regCount];
                tree.decode(in, (int) pos + 8, regCount, coeffType[e]);
                pos += 8 + typeArraySize;
                long unpredCount = in.be32(pos);
                pos += 4;
                coeffUnpredAt[e] = pos;
                pos += unpredCount * 8;
                in.require(0, pos);
            }
        }
        long totalUnpred = in.le64(pos);
        pos += 8;
        long unpredAt = pos;
        if (totalUnpred < 0 || totalUnpred > (in.length() - pos) / 8) {
            throw new CompressionFormatException("SZ stream declares " + totalUnpred + " unpredictable values");
        }
        pos += totalUnpred * 8;
        int n = r1 * r2;
        int[] type = new int[n];
        root.decode(in, (int) pos, n, type);
        int intvRadius = intervals / 2;

        double[] data = new double[n];
        int dim0 = r2;
        double[] last = new double[coefficients];
        int[] coeffUnpredCount = new int[coefficients];
        int coeffIndex = 0;
        int typeAt = 0;
        long unpred = 0; // values of unpred_data used so far
        int block = 0;
        for (int i = 0; i < bx.count; i++) {
            for (int j = 0; j < by.count; j++) {
                int cx = bx.size(i);
                int cy = by.size(j);
                int base = bx.offset(i) * dim0 + by.offset(j);
                long used = 0;
                if (indicator[block]) {
                    for (int ii = 0; ii < cx; ii++) {
                        for (int jj = 0; jj < cy; jj++) {
                            int p = base + ii * dim0 + jj;
                            int t = type[typeAt + ii * cy + jj];
                            if (useMean && t == intvRadius) {
                                data[p] = mean;
                            } else if (t == 0) {
                                data[p] = in.leDouble(unpredAt + 8 * (unpred + used++));
                            } else {
                                boolean top = i == 0 && ii == 0;
                                boolean leftEdge = j == 0 && jj == 0;
                                double d00 = top || leftEdge ? 0 : data[p - dim0 - 1];
                                double d01 = top ? 0 : data[p - dim0];
                                double d10 = leftEdge ? 0 : data[p - 1];
                                if (useMean && t < intvRadius) {
                                    t += 1;
                                }
                                double pred = d10 + d01 - d00;
                                data[p] = pred + 2 * (t - intvRadius) * realPrecision;
                            }
                        }
                    }
                } else {
                    for (int e = 0; e < coefficients; e++) {
                        int t = coeffType[e][coeffIndex];
                        if (t != 0) {
                            last[e] = last[e] + 2 * (t - coeffIntvRadius[e]) * precision[e];
                        } else {
                            last[e] = in.leDouble(coeffUnpredAt[e] + 8L * coeffUnpredCount[e]++);
                        }
                    }
                    coeffIndex++;
                    for (int ii = 0; ii < cx; ii++) {
                        for (int jj = 0; jj < cy; jj++) {
                            int p = base + ii * dim0 + jj;
                            int t = type[typeAt + ii * cy + jj];
                            if (t != 0) {
                                double pred = last[0] * ii + last[1] * jj + last[2];
                                data[p] = pred + 2 * (t - intvRadius) * realPrecision;
                            } else {
                                data[p] = in.leDouble(unpredAt + 8 * (unpred + used++));
                            }
                        }
                    }
                }
                typeAt += cx * cy;
                unpred += used;
                block++;
            }
        }
        return data;
    }

    /**
     * {@code decompressDataSeries_double_3D_nonblocked_with_blocked_regression}. libSZ unrolls the block
     * edges; each Lorenzo prediction there sums only the neighbours inside the array, in the order of the
     * full stencil, which is what {@link #lorenzo3D} computes.
     */
    private static double[] regression3D(SzStorage s, int r1, int r2, int r3) {
        SzBuffer in = s.in;
        long pos = s.dataAt;
        long blockSize = in.be32(pos);
        pos += 4;
        SzDecoder.Blocks bx = new SzDecoder.Blocks(r1, blockSize);
        SzDecoder.Blocks by = new SzDecoder.Blocks(r2, blockSize);
        SzDecoder.Blocks bz = new SzDecoder.Blocks(r3, blockSize);
        int numBlocks = Math.multiplyExact(Math.multiplyExact(bx.count, by.count), bz.count);
        double realPrecision = in.beDouble(pos);
        pos += 8;
        int intervals = SzDecoder.intervals(in.be32(pos));
        pos += 4;
        long treeSize = in.be32(pos) & 0xFFFFFFFFL;
        pos += 4;
        SzHuffman root = SzHuffman.read(in, (int) pos + 4, in.be32(pos));
        pos += 4 + treeSize;
        boolean useMean = in.u8(pos) != 0;
        pos += 1;
        double mean = in.leDouble(pos);
        pos += 8;
        boolean[] indicator = in.oneBit(pos, numBlocks);
        pos += (numBlocks - 1) / 8 + 1;
        int regCount = 0;
        for (boolean sz : indicator) {
            regCount += sz ? 0 : 1;
        }
        int coefficients = 4;
        double[] precision = new double[coefficients];
        int[] coeffIntvRadius = new int[coefficients];
        int[][] coeffType = new int[coefficients][];
        long[] coeffUnpredAt = new long[coefficients];
        if (regCount > 0) {
            for (int e = 0; e < coefficients; e++) {
                precision[e] = in.beDouble(pos);
                pos += 8;
                coeffIntvRadius[e] = in.be32(pos);
                pos += 4;
                long size = in.be32(pos) & 0xFFFFFFFFL;
                pos += 4;
                SzHuffman tree = SzHuffman.read(in, (int) pos + 4, in.be32(pos));
                pos += 4 + size;
                long typeArraySize = in.size(pos, s.sizeType);
                coeffType[e] = new int[regCount];
                tree.decode(in, (int) pos + 8, regCount, coeffType[e]);
                pos += 8 + typeArraySize;
                long unpredCount = in.be32(pos);
                pos += 4;
                coeffUnpredAt[e] = pos;
                pos += unpredCount * 8;
                in.require(0, pos);
            }
        }
        long totalUnpred = in.le64(pos);
        pos += 8;
        long unpredAt = pos;
        if (totalUnpred < 0 || totalUnpred > (in.length() - pos) / 8) {
            throw new CompressionFormatException("SZ stream declares " + totalUnpred + " unpredictable values");
        }
        pos += totalUnpred * 8;
        int dim1 = r3;
        int dim0 = r2 * r3;
        int n = r1 * dim0;
        int[] type = new int[n];
        root.decode(in, (int) pos, n, type);
        int intvRadius = intervals / 2;

        double[] data = new double[n];
        double[] last = new double[coefficients];
        int[] coeffUnpredCount = new int[coefficients];
        int coeffIndex = 0;
        int typeAt = 0;
        long unpred = 0;
        int block = 0;
        for (int i = 0; i < bx.count; i++) {
            for (int j = 0; j < by.count; j++) {
                for (int k = 0; k < bz.count; k++) {
                    int cx = bx.size(i);
                    int cy = by.size(j);
                    int cz = bz.size(k);
                    int base = bx.offset(i) * dim0 + by.offset(j) * dim1 + bz.offset(k);
                    long used = 0;
                    int index = typeAt;
                    if (indicator[block]) {
                        for (int ii = 0; ii < cx; ii++) {
                            for (int jj = 0; jj < cy; jj++) {
                                for (int kk = 0; kk < cz; kk++) {
                                    int p = base + ii * dim0 + jj * dim1 + kk;
                                    int t = type[index++];
                                    if (useMean && t == intvRadius) {
                                        data[p] = mean;
                                    } else if (t == 0) {
                                        data[p] = in.leDouble(unpredAt + 8 * (unpred + used++));
                                    } else {
                                        if (useMean && t < intvRadius) {
                                            t += 1;
                                        }
                                        double pred = lorenzo3D(data, p, dim0, dim1, i > 0 || ii > 0, j > 0 || jj > 0,
                                                k > 0 || kk > 0);
                                        data[p] = pred + 2 * (t - intvRadius) * realPrecision;
                                    }
                                }
                            }
                        }
                    } else {
                        for (int e = 0; e < coefficients; e++) {
                            int t = coeffType[e][coeffIndex];
                            if (t != 0) {
                                last[e] = last[e] + 2 * (t - coeffIntvRadius[e]) * precision[e];
                            } else {
                                last[e] = in.leDouble(coeffUnpredAt[e] + 8L * coeffUnpredCount[e]++);
                            }
                        }
                        coeffIndex++;
                        for (int ii = 0; ii < cx; ii++) {
                            for (int jj = 0; jj < cy; jj++) {
                                for (int kk = 0; kk < cz; kk++) {
                                    int p = base + ii * dim0 + jj * dim1 + kk;
                                    int t = type[index++];
                                    if (t != 0) {
                                        double pred = last[0] * ii + last[1] * jj + last[2] * kk + last[3];
                                        data[p] = pred + 2 * (t - intvRadius) * realPrecision;
                                    } else {
                                        data[p] = in.leDouble(unpredAt + 8 * (unpred + used++));
                                    }
                                }
                            }
                        }
                    }
                    typeAt += cx * cy * cz;
                    unpred += used;
                    block++;
                }
            }
        }
        return data;
    }

    /**
     * The 3-D Lorenzo prediction of {@code data[p]} from the neighbours that exist ({@code x}: the previous
     * layer, {@code y}: row, {@code z}: value), summed in the full stencil's order
     * {@code d110 + d101 + d011 - d100 - d010 - d001 + d000}, as libSZ's unrolled edge cases do.
     */
    private static double lorenzo3D(double[] d, int p, int dim0, int dim1, boolean x, boolean y, boolean z) {
        double pred = 0;
        boolean any = false;
        if (z) {
            pred = d[p - 1];
            any = true;
        }
        if (y) {
            pred = any ? pred + d[p - dim1] : d[p - dim1];
            any = true;
        }
        if (x) {
            pred = any ? pred + d[p - dim0] : d[p - dim0];
        }
        if (y && z) {
            pred -= d[p - dim1 - 1];
        }
        if (x && z) {
            pred -= d[p - dim0 - 1];
        }
        if (x && y) {
            pred -= d[p - dim0 - dim1];
        }
        if (x && y && z) {
            pred += d[p - dim0 - dim1 - 1];
        }
        return pred;
    }
}
