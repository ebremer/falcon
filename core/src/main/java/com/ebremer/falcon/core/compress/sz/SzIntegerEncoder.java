package com.ebremer.falcon.core.compress.sz;

/**
 * SZ 2.1.12's integer compressors ({@code sz_int8.c} to {@code sz_uint64.c}): the classic (Lorenzo) format in 1
 * to 4 dimensions, each type with its own C arithmetic. Values are carried as {@code long}s, each type's
 * value as C widens it to {@code int64_t} (unsigned 64-bit values as their bits), and every operation is
 * done at the width, signedness, and with the conversions the type's own source uses: the 8- and 16-bit
 * coders clamp their predictions to the type's range, the 32-bit ones wrap, and conversions from double
 * follow the MSVC x86-64 build. libSZ's quirks are kept: its 4-D coder stores the chunk's first value for
 * every value it cannot predict (and predicts each layer's second value from its first, unchanged), and its
 * 1-D coder stores a copy of two values more than it has when it gives up (those two are written as zeros
 * here; libSZ reads them from past its input).
 */
final class SzIntegerEncoder {

    private SzIntegerEncoder() {
    }

    /** One integer type's C arithmetic. */
    private static final class Kind {
        final int dataType;
        final int size;
        final boolean signed;

        Kind(int dataType) {
            this.dataType = dataType;
            this.size = SzDecoder.elementSize(dataType);
            this.signed = switch (dataType) {
                case SzDecoder.INT8, SzDecoder.INT16, SzDecoder.INT32, SzDecoder.INT64 -> true;
                default -> false;
            };
        }

        /** The element as C widens it to {@code int64_t}. */
        long value(byte[] b, int i) {
            int at = i * size;
            return switch (size) {
                case 1 -> signed ? b[at] : b[at] & 0xFF;
                case 2 -> {
                    int v = (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
                    yield signed ? (short) v : v;
                }
                case 4 -> {
                    int v = SzEncoder.le32(b, at);
                    yield signed ? v : v & 0xFFFFFFFFL;
                }
                default -> SzEncoder.le64(b, at);
            };
        }

        /** A value narrowed to the type and widened back (C's assignment to the type, then to int64_t). */
        long narrow(long v) {
            return switch (size) {
                case 1 -> signed ? (byte) v : v & 0xFF;
                case 2 -> signed ? (short) v : v & 0xFFFF;
                case 4 -> signed ? (int) v : v & 0xFFFFFFFFL;
                default -> v;
            };
        }

        /**
         * A prediction plus its quantized step, stored in the type ({@code P1[j] = pred + 2*(...)*realPrecision}):
         * the 8- and 16-bit coders convert to int64_t and clamp to the type ({@code tmp}); the 32-bit ones convert
         * directly (MSVC: through a 64-bit conversion for unsigned), the 64-bit ones as MSVC does.
         */
        long store(double x) {
            return switch (size) {
                case 1, 2 -> {
                    long tmp = SzQuantization.toLong(x);
                    long min = signed ? (size == 1 ? -128 : -32768) : 0;
                    long max = signed ? (size == 1 ? 127 : 32767) : (size == 1 ? 255 : 65535);
                    yield tmp >= min && tmp < max ? tmp : tmp < min ? min : max;
                }
                case 4 -> signed ? SzQuantization.toInt(x) : SzQuantization.toLong(x) & 0xFFFFFFFFL;
                default -> signed ? SzQuantization.toLong(x) : SzQuantization.toUnsignedLong(x);
            };
        }

        /**
         * A Lorenzo prediction from stored values, computed in the type's C arithmetic and widened to int64_t:
         * {@code int} for 8 and 16 bits (no overflow), 32-bit wrap (signed or unsigned), 64-bit wrap.
         */
        long predict(long expr) {
            return size == 4 ? narrow(expr) : expr;
        }

        /** The difference to a prediction as the 2-D to 4-D coders hold it ({@code diff}). */
        long diff(long value, long pred) {
            long d = value - pred;
            return size <= 4 && !(size == 4 && !signed) ? (int) d : d;
        }

        /** The 1-D coder's prediction variable: int64_t, but int32_t and uint32_t for the 32-bit types. */
        long pred1D(long v) {
            return size == 4 ? narrow(v) : v;
        }

        /**
         * The 1-D coder's {@code pred + state*interval} stored back in its prediction variable; the 8- and 16-bit
         * coders then clamp it to the type's range.
         */
        long store1D(double x) {
            if (size == 4) {
                return signed ? SzQuantization.toInt(x) : SzQuantization.toLong(x) & 0xFFFFFFFFL;
            }
            long pred = SzQuantization.toLong(x);
            if (size <= 2) {
                long min = signed ? (size == 1 ? -128 : -32768) : 0;
                long max = signed ? (size == 1 ? 127 : 32767) : (size == 1 ? 255 : 65535);
                if (pred > max) {
                    pred = max;
                }
                if (pred < min) {
                    pred = min;
                }
            }
            return pred;
        }

        /** {@code compressInt*Value}: the value less the minimum, in the type, big-endian, its last bytes. */
        void putValue(SzBytes out, long value, long minValue, int byteSize) {
            long data = value - minValue;
            for (int b = byteSize - 1; b >= 0; b--) {
                out.u8((int) (data >>> (8 * b)));
            }
        }

        /** {@code convertDataTypeSize}. */
        static int sizeCode(int size) {
            return switch (size) {
                case 1 -> 0;
                case 2 -> 4;
                case 4 -> 8;
                case 8 -> 12;
                default -> 0;
            };
        }

        /**
         * The size code of a constant stream: the coders that set {@code dataTypeSize} there set it to the code,
         * which is then coded again (16-bit types: 8; int32: 12); the others leave it unset, and libSZ's heap
         * memory gives no size it codes, so 0.
         */
        int constantSizeCode() {
            return switch (dataType) {
                case SzDecoder.INT16, SzDecoder.UINT16, SzDecoder.INT32 -> sizeCode(sizeCode(size));
                default -> 0;
            };
        }
    }

    /** The coded values: their quantization codes and the bytes of those stored. */
    private static final class Coded {
        int[] type;
        SzBytes exact = new SzBytes(1024);
        int intervals;
        long exactDataNum;
        int byteSize;
    }

    /**
     * {@code SZ_compress_args_int*}, the lossless stage aside, for dimensions already filtered.
     *
     * @param msvcInt64Range compute an int64 range as hdf5plugin's MSVC build does, over the data's first
     *                       {@code n} 32-bit words ({@code computeRangeSize_int} reads int64 data through a
     *                       {@code long *}, 32 bits there): its streams then hold values outside their bound
     *                       wherever the data needs more than 32 bits, so Falcon computes it over the values, as
     *                       SZ means to and an LP64 build does. Only the tests that check the rest of the int64
     *                       coder against hdf5plugin's bytes ask for libSZ's.
     */
    static SzEncoder.Encoded compress(int dataType, byte[] data, int n, long r5, long r4, long r3, long r2, long r1,
                                      int mode, double absErrBound, double relBoundRatio, boolean msvcInt64Range) {
        Kind k = new Kind(dataType);
        SzParams p = new SzParams(dataType, mode);
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            v[i] = k.value(data, i);
        }
        // computeRangeSize_int: unsigned 64-bit values compare unsigned (int64_t against uint64_t)
        boolean words = msvcInt64Range && dataType == SzDecoder.INT64;
        long min = words ? SzEncoder.le32(data, 0) : v[0];
        long max = min;
        for (int i = 1; i < n; i++) {
            long x = words ? SzEncoder.le32(data, 4 * i) : v[i];
            boolean below = k.size == 8 && !k.signed ? Long.compareUnsigned(min, x) > 0 : min > x;
            boolean above = k.size == 8 && !k.signed ? Long.compareUnsigned(max, x) < 0 : max < x;
            if (below) {
                min = x;
            } else if (above) {
                max = x;
            }
        }
        long valueRangeSize = max - min;
        long minValue = k.narrow(min);
        double realPrecision = realPrecision(valueRangeSize, mode, absErrBound, relBoundRatio);
        if ((double) valueRangeSize <= realPrecision) {
            SzBytes out = new SzBytes(64);
            p.header(out, 0x01 | 0x02 | k.constantSizeCode() | 0x40, SzParams.META_FLOAT, n);
            k.putValue(out, v[0], 0, k.size);
            return new SzEncoder.Encoded(out.toArray(), false);
        }
        int dim = SzDecoder.dimension(r5, r4, r3, r2, r1);
        int byteSize = byteSize(valueRangeSize);
        Coded c = switch (dim) {
            case 1 -> mdq1D(k, v, realPrecision, minValue, byteSize);
            case 2 -> mdq2D(k, v, (int) r2, (int) r1, realPrecision, minValue, byteSize);
            case 3 -> mdq3D(k, v, (int) r3, (int) r2, (int) r1, realPrecision, minValue, byteSize);
            default -> mdq4D(k, v, (int) r4, (int) r3, (int) r2, (int) r1, realPrecision, minValue, byteSize);
        };
        byte[] out = classic(p, k, c, n, minValue, realPrecision);
        if (out.length > (long) n * k.size) {
            out = stored(p, k, v, dim == 1 ? n + 2 : n);
        }
        return new SzEncoder.Encoded(out, true);
    }

    /** {@code getRealPrecision_int}: the combined modes compare in float. */
    private static double realPrecision(long valueRangeSize, int mode, double absErrBound, double relBoundRatio) {
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
            default -> -1;
        };
    }

    /** {@code computeByteSizePerIntValue}. */
    private static int byteSize(long valueRangeSize) {
        if (valueRangeSize <= 256) {
            return 1;
        } else if (valueRangeSize <= 65536) {
            return 2;
        } else if (valueRangeSize <= 4294967296L) {
            return 4;
        }
        return 8;
    }

    /** {@code convertTDPStoFlatBytes_int}. */
    private static byte[] classic(SzParams p, Kind k, Coded c, int n, long minValue, double realPrecision) {
        SzBytes types = new SzBytes(n + 64);
        SzHuffmanEncoder.encodeWithTree(2 * c.intervals, c.type, n, types);
        byte[] typeArray = types.toArray();
        byte[] exact = c.exact.toArray();
        SzBytes out = new SzBytes(typeArray.length + exact.length + 128);
        out.bytes(SzParams.VERSION);
        out.u8(0x02 | Kind.sizeCode(k.size) | 0x40);
        p.write(out, SzParams.META_FLOAT);
        out.u8(c.byteSize);
        out.be64(n);
        out.be32(SzParams.MAX_QUANT_INTERVALS);
        out.be32(c.intervals);
        out.be64(minValue);
        out.beDouble(realPrecision);
        out.be64(typeArray.length);
        out.be64(c.exactDataNum);
        out.be64(exact.length);
        out.bytes(typeArray);
        out.bytes(exact);
        return out.toArray();
    }

    /** {@code SZ_compress_args_int*_StoreOriData}: the values big-endian, {@code count} of them. */
    private static byte[] stored(SzParams p, Kind k, long[] v, int count) {
        SzBytes out = new SzBytes(64 + k.size * count);
        p.header(out, 0x50, SzParams.META_FLOAT, count);
        for (int i = 0; i < count; i++) {
            k.putValue(out, i < v.length ? v[i] : 0, 0, k.size);
        }
        return out.toArray();
    }

    // ------------------------------------------------------------------ choosing the interval count

    private static int optimizeIntervals1D(Kind k, long[] d, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        long samples = d.length / SzParams.SAMPLE_DISTANCE;
        for (int i = 2; i < d.length; i++) {
            if (i % SzParams.SAMPLE_DISTANCE == 0) {
                long err = Math.abs(d[i - 1] - d[i]);
                intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
            }
        }
        return SzQuantization.intervals(intervals, samples);
    }

    private static int optimizeIntervals2D(Kind k, long[] d, int r1, int r2, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        long samples = (k.size == 4 ? (long) r1 * r2 : (long) (r1 - 1) * (r2 - 1)) / SzParams.SAMPLE_DISTANCE;
        for (int i = 1; i < r1; i++) {
            for (int j = 1; j < r2; j++) {
                if ((i + j) % SzParams.SAMPLE_DISTANCE == 0) {
                    int index = i * r2 + j;
                    long pred = k.predict(d[index - 1] + d[index - r2] - d[index - r2 - 1]);
                    long err = Math.abs(pred - d[index]);
                    intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
                }
            }
        }
        return SzQuantization.intervals(intervals, samples);
    }

    private static int optimizeIntervals3D(Kind k, long[] d, int r1, int r2, int r3, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int r23 = r2 * r3;
        long samples = (long) (r1 - 1) * (r2 - 1) * (r3 - 1) / SzParams.SAMPLE_DISTANCE;
        for (int i = 1; i < r1; i++) {
            for (int j = 1; j < r2; j++) {
                for (int l = 1; l < r3; l++) {
                    if ((i + j + l) % SzParams.SAMPLE_DISTANCE == 0) {
                        int index = i * r23 + j * r3 + l;
                        long pred = k.predict(d[index - 1] + d[index - r3] + d[index - r23] - d[index - 1 - r23]
                                - d[index - r3 - 1] - d[index - r3 - r23] + d[index - r3 - r23 - 1]);
                        long err = Math.abs(pred - d[index]);
                        intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
                    }
                }
            }
        }
        return SzQuantization.intervals(intervals, samples);
    }

    /** {@code optimize_intervals_*_4D}, with its stencil's slip ({@code index-r3} for {@code index-r4}) kept. */
    private static int optimizeIntervals4D(Kind k, long[] d, int r1, int r2, int r3, int r4, double realPrecision) {
        long[] intervals = new long[SzParams.MAX_RANGE_RADIUS];
        int r34 = r3 * r4;
        int r234 = r2 * r34;
        long samples = (long) (r1 - 1) * (r2 - 1) * (r3 - 1) * (r4 - 1) / SzParams.SAMPLE_DISTANCE;
        for (int i = 1; i < r1; i++) {
            for (int j = 1; j < r2; j++) {
                for (int l = 1; l < r3; l++) {
                    for (int m = 1; m < r4; m++) {
                        if ((i + j + l + m) % SzParams.SAMPLE_DISTANCE == 0) {
                            int index = i * r234 + j * r34 + l * r4 + m;
                            long pred = k.predict(d[index - 1] + d[index - r3] + d[index - r34] - d[index - 1 - r34]
                                    - d[index - r4 - 1] - d[index - r4 - r34] + d[index - r4 - r34 - 1]);
                            long err = Math.abs(pred - d[index]);
                            intervals[SzQuantization.radiusIndex((err / realPrecision + 1) / 2)]++;
                        }
                    }
                }
            }
        }
        return SzQuantization.intervals(intervals, samples);
    }

    // ------------------------------------------------------------------ the classic format

    /** {@code SZ_compress_*_1D_MDQ}. */
    private static Coded mdq1D(Kind k, long[] d, double realPrecision, long minValue, int byteSize) {
        int n = d.length;
        Coded c = new Coded();
        c.byteSize = byteSize;
        c.intervals = optimizeIntervals1D(k, d, realPrecision);
        int intvRadius = c.intervals / 2;
        c.type = new int[n];
        k.putValue(c.exact, d[0], minValue, byteSize);
        k.putValue(c.exact, d[1], minValue, byteSize);
        double checkRadius = (c.intervals - 1) * realPrecision;
        double interval = 2 * realPrecision;
        long pred = k.pred1D(d[1]);
        for (int i = 2; i < n; i++) {
            long curData = d[i];
            long predAbsErr = k.pred1D(Math.abs(curData - pred));
            if (predAbsErr < checkRadius) {
                int state = SzQuantization.toInt((predAbsErr / realPrecision + 1) / 2);
                if (curData >= pred) {
                    c.type[i] = intvRadius + state;
                    pred = k.store1D(pred + state * interval);
                } else {
                    c.type[i] = intvRadius - state;
                    pred = k.store1D(pred - state * interval);
                }
                continue;
            }
            c.type[i] = 0;
            k.putValue(c.exact, curData, minValue, byteSize);
            pred = k.pred1D(curData);
        }
        c.exactDataNum = c.exact.size() / byteSize;
        return c;
    }

    /** Quantizes value {@code index} against {@code pred}; returns what the type stores for it. */
    private static long code(Kind k, Coded c, long[] d, int index, long value, long pred, double realPrecision,
                             long minValue, long unpredictable) {
        int intvRadius = c.intervals / 2;
        long diff = k.diff(value, pred);
        double itvNum = Math.abs(diff) / realPrecision + 1;
        if (itvNum < c.intervals) {
            if (diff < 0) {
                itvNum = -itvNum;
            }
            c.type[index] = SzQuantization.toInt(itvNum / 2) + intvRadius;
            return k.store(pred + 2 * (c.type[index] - intvRadius) * realPrecision);
        }
        c.type[index] = 0;
        k.putValue(c.exact, unpredictable, minValue, c.byteSize);
        return unpredictable;
    }

    /** {@code SZ_compress_*_2D_MDQ}: {@code r1} rows of {@code r2}. */
    private static Coded mdq2D(Kind k, long[] d, int r1, int r2, double realPrecision, long minValue, int byteSize) {
        Coded c = new Coded();
        c.byteSize = byteSize;
        c.intervals = optimizeIntervals2D(k, d, r1, r2, realPrecision);
        c.type = new int[r1 * r2];
        long[] p0 = new long[r2];
        long[] p1 = new long[r2];
        p1[0] = d[0];
        k.putValue(c.exact, d[0], minValue, byteSize);
        p1[1] = code(k, c, d, 1, d[1], p1[0], realPrecision, minValue, d[1]);
        for (int j = 2; j < r2; j++) {
            p1[j] = code(k, c, d, j, d[j], k.predict(2 * p1[j - 1] - p1[j - 2]), realPrecision, minValue, d[j]);
        }
        for (int i = 1; i < r1; i++) {
            int index = i * r2;
            p0[0] = code(k, c, d, index, d[index], p1[0], realPrecision, minValue, d[index]);
            for (int j = 1; j < r2; j++) {
                index = i * r2 + j;
                p0[j] = code(k, c, d, index, d[index], k.predict(p0[j - 1] + p1[j] - p1[j - 1]), realPrecision,
                        minValue, d[index]);
            }
            long[] t = p1;
            p1 = p0;
            p0 = t;
        }
        c.exactDataNum = c.exact.size();
        return c;
    }

    /** {@code SZ_compress_*_3D_MDQ}: {@code r1} layers of {@code r2} rows of {@code r3}. */
    private static Coded mdq3D(Kind k, long[] d, int r1, int r2, int r3, double realPrecision, long minValue,
                               int byteSize) {
        Coded c = new Coded();
        c.byteSize = byteSize;
        c.intervals = optimizeIntervals3D(k, d, r1, r2, r3, realPrecision);
        int r23 = r2 * r3;
        c.type = new int[r1 * r23];
        long[] p0 = new long[r23];
        long[] p1 = new long[r23];
        p1[0] = d[0];
        k.putValue(c.exact, d[0], minValue, byteSize);
        p1[1] = code(k, c, d, 1, d[1], p1[0], realPrecision, minValue, d[1]);
        for (int j = 2; j < r3; j++) {
            p1[j] = code(k, c, d, j, d[j], k.predict(2 * p1[j - 1] - p1[j - 2]), realPrecision, minValue, d[j]);
        }
        for (int i = 1; i < r2; i++) {
            int index = i * r3;
            p1[index] = code(k, c, d, index, d[index], p1[index - r3], realPrecision, minValue, d[index]);
            for (int j = 1; j < r3; j++) {
                index = i * r3 + j;
                p1[index] = code(k, c, d, index, d[index], k.predict(p1[index - 1] + p1[index - r3] - p1[index - r3 - 1]),
                        realPrecision, minValue, d[index]);
            }
        }
        for (int l = 1; l < r1; l++) {
            int index = l * r23;
            p0[0] = code(k, c, d, index, d[index], p1[0], realPrecision, minValue, d[index]);
            for (int j = 1; j < r3; j++) {
                index++;
                p0[j] = code(k, c, d, index, d[index], k.predict(p0[j - 1] + p1[j] - p1[j - 1]), realPrecision,
                        minValue, d[index]);
            }
            for (int i = 1; i < r2; i++) {
                index = l * r23 + i * r3;
                int i2 = i * r3;
                p0[i2] = code(k, c, d, index, d[index], k.predict(p0[i2 - r3] + p1[i2] - p1[i2 - r3]), realPrecision,
                        minValue, d[index]);
                for (int j = 1; j < r3; j++) {
                    index++;
                    i2 = i * r3 + j;
                    long pred = k.predict(p0[i2 - 1] + p0[i2 - r3] + p1[i2] - p0[i2 - r3 - 1] - p1[i2 - r3] - p1[i2 - 1]
                            + p1[i2 - r3 - 1]);
                    p0[i2] = code(k, c, d, index, d[index], pred, realPrecision, minValue, d[index]);
                }
            }
            long[] t = p1;
            p1 = p0;
            p0 = t;
        }
        c.exactDataNum = c.exact.size();
        return c;
    }

    /**
     * {@code SZ_compress_*_4D_MDQ}: {@code r1} blocks of {@code r2} layers of {@code r3} rows of {@code r4}, with
     * libSZ's slips kept: an unpredictable value is stored as the chunk's first, and each block's second value
     * is predicted from its first ({@code diff = curValue - pred1D}).
     */
    private static Coded mdq4D(Kind k, long[] d, int r1, int r2, int r3, int r4, double realPrecision, long minValue,
                               int byteSize) {
        Coded c = new Coded();
        c.byteSize = byteSize;
        c.intervals = optimizeIntervals4D(k, d, r1, r2, r3, r4, realPrecision);
        int r34 = r3 * r4;
        int r234 = r2 * r34;
        c.type = new int[r1 * r234];
        long[] p0 = new long[r34];
        long[] p1 = new long[r34];
        long first = d[0];
        for (int l = 0; l < r1; l++) {
            int index = l * r234;
            long curValue = d[index];
            p1[0] = curValue;
            c.type[index] = 0;
            k.putValue(c.exact, curValue, minValue, byteSize);
            index = l * r234 + 1;
            p1[1] = code(k, c, d, index, curValue, p1[0], realPrecision, minValue, first);
            for (int j = 2; j < r4; j++) {
                index = l * r234 + j;
                p1[j] = code(k, c, d, index, d[index], k.predict(2 * p1[j - 1] - p1[j - 2]), realPrecision, minValue,
                        first);
            }
            for (int i = 1; i < r3; i++) {
                index = l * r234 + i * r4;
                int i2 = i * r4;
                p1[i2] = code(k, c, d, index, d[index], p1[i2 - r4], realPrecision, minValue, first);
                for (int j = 1; j < r4; j++) {
                    index = l * r234 + i * r4 + j;
                    i2 = i * r4 + j;
                    p1[i2] = code(k, c, d, index, d[index], k.predict(p1[i2 - 1] + p1[i2 - r4] - p1[i2 - r4 - 1]),
                            realPrecision, minValue, first);
                }
            }
            for (int m = 1; m < r2; m++) {
                index = l * r234 + m * r34;
                p0[0] = code(k, c, d, index, d[index], p1[0], realPrecision, minValue, first);
                for (int j = 1; j < r4; j++) {
                    index = l * r234 + m * r34 + j;
                    p0[j] = code(k, c, d, index, d[index], k.predict(p0[j - 1] + p1[j] - p1[j - 1]), realPrecision,
                            minValue, first);
                }
                for (int i = 1; i < r3; i++) {
                    index = l * r234 + m * r34 + i * r4;
                    int i2 = i * r4;
                    p0[i2] = code(k, c, d, index, d[index], k.predict(p0[i2 - r4] + p1[i2] - p1[i2 - r4]), realPrecision,
                            minValue, first);
                    for (int j = 1; j < r4; j++) {
                        index = l * r234 + m * r34 + i * r4 + j;
                        i2 = i * r4 + j;
                        long pred = k.predict(p0[i2 - 1] + p0[i2 - r4] + p1[i2] - p0[i2 - r4 - 1] - p1[i2 - r4]
                                - p1[i2 - 1] + p1[i2 - r4 - 1]);
                        p0[i2] = code(k, c, d, index, d[index], pred, realPrecision, minValue, first);
                    }
                }
                long[] t = p1;
                p1 = p0;
                p0 = t;
            }
        }
        c.exactDataNum = c.exact.size();
        return c;
    }
}
