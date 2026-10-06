package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * SZ 2.1.12's integer decoders ({@code szd_int8.c} to {@code szd_uint64.c}): the classic format, whose
 * predictions are integers and whose corrections are doubles. Each type keeps its C arithmetic: 8- and
 * 16-bit predictions in 64 bits, their results clamped to the type; 32- and 64-bit ones wrapping, their
 * results converted as x86-64 converts a double ({@code cvttsd2si}).
 */
final class SzIntegers {

    private SzIntegers() {
    }

    /** The integer type being decoded: its width, signedness, and C arithmetic. */
    private record Type(int code, int width, boolean signed) {

        boolean small() {
            return width <= 2;
        }

        /** A sum of the type's values, as C leaves it: in {@code int} for 8 and 16 bits, else wrapping. */
        long wrapPredicted(long v) {
            return small() ? v : wrap(v);
        }

        /** The value {@code v} reduced to the type (two's complement wrap). */
        long wrap(long v) {
            return switch (width) {
                case 1 -> signed ? (byte) v : v & 0xFF;
                case 2 -> signed ? (short) v : v & 0xFFFF;
                case 4 -> signed ? (int) v : v & 0xFFFFFFFFL;
                default -> v;
            };
        }

        /** A prediction converted to double, as C converts the type (unsigned 64 bits correctly rounded). */
        double toDouble(long pred) {
            if (width == 8 && !signed && pred < 0) {
                return (double) ((pred >>> 1) | (pred & 1)) * 2.0;
            }
            return pred;
        }

        /**
         * A double result stored into the type: clamped (8, 16 bits), or converted as hdf5plugin's MSVC build
         * of libSZ converts it, which out of range differs from GCC's for unsigned 64 bits.
         */
        long fromDouble(double x) {
            return switch (width) {
                case 1, 2 -> {
                    long tmp = cvttsd2si64(x);
                    long min = signed ? (width == 1 ? -128 : -32768) : 0;
                    long max = signed ? (width == 1 ? 127 : 32767) : (width == 1 ? 255 : 65535);
                    yield tmp >= min && tmp < max ? tmp : tmp < min ? min : max;
                }
                case 4 -> signed ? cvttsd2si32(x) : cvttsd2si64(x) & 0xFFFFFFFFL;
                // to uint64, as MSVC converts (its /fpcvt:BC): below 2^63 signed, up to 2^64 offset by 2^63,
                // and past that 0x8000000000000000
                default -> signed || !(x >= 9.223372036854775807E18 && x < 1.8446744073709552E19) ? cvttsd2si64(x)
                        : cvttsd2si64(x - 9.223372036854775807E18) ^ Long.MIN_VALUE;
            };
        }
    }

    /** {@code cvttsd2si} into 64 bits: truncation, and {@code 0x8000000000000000} when out of range or NaN. */
    private static long cvttsd2si64(double x) {
        return x >= -9.223372036854775808E18 && x < 9.223372036854775807E18 ? (long) x : Long.MIN_VALUE;
    }

    /** {@code cvttsd2si} into 32 bits: truncation, and {@code 0x80000000} when out of range or NaN. */
    private static long cvttsd2si32(double x) {
        return x > -2147483649.0 && x < 2147483648.0 ? (int) x : Integer.MIN_VALUE;
    }

    /** {@code SZ_decompress_args_int*} and their snapshots: the decoded values, little-endian. */
    static byte[] decode(int code, byte[] stream, int offset, int length, long[] r, int n) {
        int width = SzDecoder.elementSize(code);
        Type type = new Type(code, width, code == SzDecoder.INT8 || code == SzDecoder.INT16
                || code == SzDecoder.INT32 || code == SzDecoder.INT64);
        // version, flags, parameters, length, and the value: a constant stream is not compressed again
        boolean constant = length == 4 + width + 4 + 28 || length == 4 + width + 8 + 28;
        SzBuffer in = SzDecoder.lossless(stream, offset, length, constant,
                (long) Math.max(4L * n, 1000000) + 4 + 28 + 8);
        long[] values = decode(type, in, r, n);
        byte[] out = new byte[width * n];
        for (int i = 0; i < n; i++) {
            long v = values[i];
            for (int b = 0; b < width; b++) {
                out[width * i + b] = (byte) (v >>> (8 * b));
            }
        }
        return out;
    }

    /** {@code new_TightDataPointStorageI_fromFlatBytes} and the decoders. */
    private static long[] decode(Type type, SzBuffer in, long[] r, int n) {
        SzStorage.checkVersion(in);
        int flags = in.u8(3);
        boolean same = (flags & 0x01) != 0;
        boolean lossless = (flags & 0x10) != 0;
        int sizeType = (flags & 0x40) != 0 ? 8 : 4;
        int solId = in.u8(4 + 14);
        int width = type.width();
        long[] data = new long[n];
        if (lossless) {
            long at = 4 + 28 + sizeType;
            for (int i = 0; i < n; i++) {
                data[i] = type.wrap(bigEndian(in, at + (long) width * i, width));
            }
            return data;
        }
        long index = 4 + 28;
        int exactByteSize = 0;
        if (!same) {
            exactByteSize = in.u8(index++);
            if (exactByteSize > width) {
                throw new CompressionFormatException("SZ stores " + exactByteSize + " bytes of a " + width + "-byte value");
            }
        }
        in.size(index, sizeType); // dataSeriesLength
        index += sizeType;
        if (same) {
            java.util.Arrays.fill(data, type.wrap(bigEndian(in, index, width)));
            return data;
        }
        index += 4; // max_quant_intervals
        int intervals = in.be32(index);
        if (intervals < 0 || intervals > SzStorage.MAX_INTERVALS) {
            throw new CompressionFormatException("SZ stream of " + Integer.toUnsignedString(intervals)
                    + " quantization intervals");
        }
        index += 4;
        long minValue = type.wrap(in.be64(index));
        index += 8;
        double realPrecision = in.beDouble(index);
        index += 8;
        long typeArraySize = in.size(index, sizeType);
        index += sizeType;
        in.size(index, sizeType); // exactDataNum
        index += sizeType;
        long exactSize = in.size(index, sizeType);
        index += sizeType;
        int typeAt = (int) index;
        long exactAt = index + typeArraySize;
        in.require(0, exactAt + exactSize);

        Decoder d = new Decoder(type, in, exactAt, exactByteSize, minValue, realPrecision, intervals / 2);
        int dim = SzDecoder.dimension(r[4], r[3], r[2], r[1], r[0]);
        if ((type.code() == SzDecoder.INT16 || type.code() == SzDecoder.UINT16) && solId == SzDecoder.SZ_TRANSPOSE) {
            dim = 1;
            r = new long[] {n, 0, 0, 0, 0};
        }
        int[] codes = SzHuffman.decodeWithTree(in, typeAt, n);
        switch (dim) {
            case 1 -> d.oneD(codes, data, n);
            case 2 -> d.twoD(codes, data, (int) r[1], (int) r[0]);
            case 3 -> d.threeD(codes, data, (int) r[2], (int) r[1], (int) r[0]);
            case 4 -> d.fourD(codes, data, (int) r[3], (int) r[2], (int) r[1], (int) r[0]);
            default -> throw new CompressionFormatException("SZ decodes at most 4 dimensions, not " + dim);
        }
        return data;
    }

    private static long bigEndian(SzBuffer in, long at, int width) {
        long v = 0;
        for (int i = 0; i < width; i++) {
            v = v << 8 | in.u8(at + i);
        }
        return v;
    }

    /** The decoders of {@code szd_<type>.c}, sharing the type's arithmetic. */
    private static final class Decoder {
        private final Type type;
        private final SzBuffer in;
        private long exactAt;
        private final int exactByteSize;
        private final int rightShiftBits;
        private final long minValue;
        private final double realPrecision;
        private final int radius;

        Decoder(Type type, SzBuffer in, long exactAt, int exactByteSize, long minValue, double realPrecision, int radius) {
            this.type = type;
            this.in = in;
            this.exactAt = exactAt;
            this.exactByteSize = exactByteSize;
            this.rightShiftBits = 8 * type.width() - 8 * exactByteSize;
            this.minValue = minValue;
            this.realPrecision = realPrecision;
            this.radius = radius;
        }

        /** An unpredictable value: its high {@code exactByteSize} bytes, shifted down, plus the minimum. */
        long exact() {
            long v = 0;
            for (int i = 0; i < exactByteSize; i++) {
                v |= (long) in.u8(exactAt + i) << (8 * (type.width() - 1 - i));
            }
            exactAt += exactByteSize;
            int bits = 8 * type.width();
            long unsigned = bits == 64 ? v : v & ((1L << bits) - 1);
            // x86 masks the shift count, so a shift by the width leaves the value as it is
            int shift = rightShiftBits & (bits == 64 ? 63 : 31);
            if (bits < 32) {
                shift = rightShiftBits; // promoted to int: shifts below 32 are exact
            }
            return type.wrap((unsigned >>> shift) + minValue);
        }

        /** {@code pred + 2 * (type - radius) * realPrecision}, stored into the type. */
        long predicted(long pred, int t) {
            return type.fromDouble(type.toDouble(type.wrapPredicted(pred)) + 2 * (t - radius) * realPrecision);
        }

        void oneD(int[] codes, long[] d, int n) {
            double interval = realPrecision * 2;
            for (int i = 0; i < n; i++) {
                int t = codes[i];
                if (t == 0) {
                    d[i] = exact();
                } else {
                    d[i] = type.fromDouble(type.toDouble(d[i - 1]) + (t - radius) * interval);
                }
            }
        }

        void twoD(int[] codes, long[] d, int r1, int r2) {
            d[0] = exact();
            int t = codes[1];
            d[1] = t != 0 ? predicted(d[0], t) : exact();
            for (int jj = 2; jj < r2; jj++) {
                t = codes[jj];
                d[jj] = t != 0 ? predicted(2 * d[jj - 1] - d[jj - 2], t) : exact();
            }
            for (int ii = 1; ii < r1; ii++) {
                int index = ii * r2;
                t = codes[index];
                d[index] = t != 0 ? predicted(d[index - r2], t) : exact();
                for (int jj = 1; jj < r2; jj++) {
                    index = ii * r2 + jj;
                    t = codes[index];
                    d[index] = t != 0 ? predicted(d[index - 1] + d[index - r2] - d[index - r2 - 1], t) : exact();
                }
            }
        }

        void threeD(int[] codes, long[] d, int r1, int r2, int r3) {
            int r23 = r2 * r3;
            d[0] = exact();
            int t = codes[1];
            d[1] = t != 0 ? predicted(d[0], t) : exact();
            for (int jj = 2; jj < r3; jj++) {
                t = codes[jj];
                d[jj] = t != 0 ? predicted(2 * d[jj - 1] - d[jj - 2], t) : exact();
            }
            for (int ii = 1; ii < r2; ii++) {
                int index = ii * r3;
                t = codes[index];
                d[index] = t != 0 ? predicted(d[index - r3], t) : exact();
                for (int jj = 1; jj < r3; jj++) {
                    index = ii * r3 + jj;
                    t = codes[index];
                    d[index] = t != 0 ? predicted(d[index - 1] + d[index - r3] - d[index - r3 - 1], t) : exact();
                }
            }
            for (int kk = 1; kk < r1; kk++) {
                int index = kk * r23;
                t = codes[index];
                d[index] = t != 0 ? predicted(d[index - r23], t) : exact();
                for (int jj = 1; jj < r3; jj++) {
                    index = kk * r23 + jj;
                    t = codes[index];
                    d[index] = t != 0 ? predicted(d[index - 1] + d[index - r23] - d[index - r23 - 1], t) : exact();
                }
                for (int ii = 1; ii < r2; ii++) {
                    index = kk * r23 + ii * r3;
                    t = codes[index];
                    d[index] = t != 0 ? predicted(d[index - r3] + d[index - r23] - d[index - r23 - r3], t) : exact();
                    for (int jj = 1; jj < r3; jj++) {
                        index = kk * r23 + ii * r3 + jj;
                        t = codes[index];
                        d[index] = t != 0 ? predicted(d[index - 1] + d[index - r3] + d[index - r23] - d[index - r3 - 1]
                                - d[index - r23 - r3] - d[index - r23 - 1] + d[index - r23 - r3 - 1], t) : exact();
                    }
                }
            }
        }

        void fourD(int[] codes, long[] d, int r1, int r2, int r3, int r4) {
            int r34 = r3 * r4;
            int r234 = r2 * r34;
            for (int ll = 0; ll < r1; ll++) {
                int index = ll * r234;
                d[index] = exact();
                index = ll * r234 + 1;
                int t = codes[index];
                d[index] = t != 0 ? predicted(d[index - 1], t) : exact();
                for (int jj = 2; jj < r4; jj++) {
                    index = ll * r234 + jj;
                    t = codes[index];
                    d[index] = t != 0 ? predicted(2 * d[index - 1] - d[index - 2], t) : exact();
                }
                for (int ii = 1; ii < r3; ii++) {
                    index = ll * r234 + ii * r4;
                    t = codes[index];
                    d[index] = t != 0 ? predicted(d[index - r4], t) : exact();
                    for (int jj = 1; jj < r4; jj++) {
                        index = ll * r234 + ii * r4 + jj;
                        t = codes[index];
                        d[index] = t != 0 ? predicted(d[index - 1] + d[index - r4] - d[index - r4 - 1], t) : exact();
                    }
                }
                for (int kk = 1; kk < r2; kk++) {
                    index = ll * r234 + kk * r34;
                    t = codes[index];
                    d[index] = t != 0 ? predicted(d[index - r34], t) : exact();
                    for (int jj = 1; jj < r4; jj++) {
                        index = ll * r234 + kk * r34 + jj;
                        t = codes[index];
                        d[index] = t != 0 ? predicted(d[index - 1] + d[index - r34] - d[index - r34 - 1], t) : exact();
                    }
                    for (int ii = 1; ii < r3; ii++) {
                        index = ll * r234 + kk * r34 + ii * r4;
                        t = codes[index];
                        d[index] = t != 0 ? predicted(d[index - r4] + d[index - r34] - d[index - r34 - r4], t) : exact();
                        for (int jj = 1; jj < r4; jj++) {
                            index = ll * r234 + kk * r34 + ii * r4 + jj;
                            t = codes[index];
                            d[index] = t != 0 ? predicted(d[index - 1] + d[index - r4] + d[index - r34] - d[index - r4 - 1]
                                    - d[index - r34 - r4] - d[index - r34 - 1] + d[index - r34 - r4 - 1], t) : exact();
                        }
                    }
                }
            }
        }
    }
}
