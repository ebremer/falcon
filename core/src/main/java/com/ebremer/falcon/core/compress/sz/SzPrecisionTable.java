package com.ebremer.falcon.core.compress.sz;

/**
 * The MSST19 coders' quantization lookup ({@code MultiLevelCacheTableWideInterval.c}): the ratios
 * {@code pow(1 + realPrecision, (2 - 2^-plus_bits) * (i - radius))} and, for each binary exponent between the
 * smallest and the largest, a table from the leading {@code bits} bits of a ratio's mantissa to the code of the
 * interval holding it (0: none), built as {@code MultiLevelCacheTableWideIntervalBuild} builds it.
 */
final class SzPrecisionTable {

    final double[] precision;
    private final int bits;
    private final int shift;
    private final long base;
    private final long range;
    private final char[][] tables;

    SzPrecisionTable(int intervals, double realPrecision) {
        int radius = intervals / 2;
        precision = new double[intervals];
        double inv = 2.0 - SzMath.pow(2, -SzParams.PLUS_BITS);
        for (int i = 0; i < intervals; i++) {
            precision[i] = SzMath.pow(1 + realPrecision, inv * (i - radius));
        }
        // MLCTWI_GetRequiredBits: -(exponent field - 1023), as a uint16_t
        bits = (char) (-(int) ((Double.doubleToRawLongBits(realPrecision) >>> 52) - 1023)) + SzParams.PLUS_BITS;
        shift = 52 - bits;
        double bottomBoundary = precision[1] / (1 + realPrecision);
        double topBoundary = precision[intervals - 1] / (1 - realPrecision);
        base = (char) (Double.doubleToRawLongBits(bottomBoundary) >>> 52);
        long top = (char) (Double.doubleToRawLongBits(topBoundary) >>> 52);
        range = top - base;
        int subTables = (int) range + 1;
        int length = (int) ((1L << bits) - 1) + 1;
        tables = new char[subTables][length];
        int index = 0;
        boolean flag = false;
        for (int i = 0; i < subTables; i++) {
            long expo = (char) (i + base);
            char[] table = tables[i];
            for (int j = 0; j < length; j++) {
                double sampleBottom = rebuild(expo, j, bits);
                double sampleTop = rebuild(expo, j + 1L, bits);
                double bottom = precision[index] / (1 + realPrecision);
                double topB = precision[index] / (1 - realPrecision);
                if (sampleTop < topB && sampleBottom > bottom) {
                    table[j] = (char) index;
                    flag = true;
                } else if (flag && index < intervals - 1) {
                    index++;
                    table[j] = (char) index;
                } else {
                    table[j] = 0;
                }
            }
        }
    }

    /** {@code MLTCWI_RebuildDouble}. */
    private static double rebuild(long expo, long manti, int bits) {
        return Double.longBitsToDouble((expo << 52) + (manti << (52 - bits)));
    }

    /** The code of a ratio ({@code predRelErrRatio}), or 0: the sign bit is ignored. */
    int code(double ratio) {
        long b = Double.doubleToRawLongBits(ratio);
        long expoIndex = ((b & 0x7fffffffffffffffL) >>> 52) - base;
        if (Long.compareUnsigned(expoIndex, range) <= 0) {
            return tables[(int) expoIndex][(int) ((b & 0x000fffffffffffffL) >>> shift)];
        }
        return 0;
    }
}
