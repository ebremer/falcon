package com.ebremer.falcon.ome;

import com.ebremer.falcon.zarr.datatype.DataType;
import java.math.BigInteger;

/**
 * The pyramid's kernel: makes one level's box of pixels from the larger level's, block by block, on
 * little-endian element bytes.
 */
final class Downsampler {

    /** How the bytes of an element are read as a number. */
    private enum Kind { BOOL, SIGNED, UNSIGNED, SIGNED64, UNSIGNED64, F16, F32, F64 }

    private Downsampler() {
    }

    /**
     * Downsamples a box. Output element {@code j} (in each dimension) is made from the input elements
     * {@code [j * f, min((j + 1) * f, inShape))}; the input box starts at output element 0's first input
     * element, and may be longer than the output needs (its trailing elements are then unused).
     *
     * @param in       the input box's elements, little-endian, in C order
     * @param inShape  the input box's shape
     * @param outShape the output box's shape
     * @param factors  the block's extent in each dimension
     * @param type     the elements' data type
     * @param method   how a block becomes a pixel
     * @return the output box's elements, little-endian, in C order
     */
    static byte[] downsample(byte[] in, long[] inShape, long[] outShape, int[] factors, DataType type,
                             Downsampling method) {
        int rank = inShape.length;
        int size = type.byteCount();
        Kind kind = kind(type);
        long outCount = 1;
        for (long n : outShape) {
            outCount *= n;
        }
        byte[] out = new byte[Math.toIntExact(Math.multiplyExact(outCount, (long) size))];
        if (outCount == 0) {
            return out;
        }
        long[] inStride = new long[rank];
        long s = 1;
        for (int d = rank - 1; d >= 0; d--) {
            inStride[d] = s;
            s *= inShape[d];
        }
        int kernel = 1;
        for (int f : factors) {
            kernel = Math.multiplyExact(kernel, f);
        }
        long[] block = new long[kernel]; // the current block's elements, as raw bits
        int[] j = new int[rank];
        int[] k = new int[rank];
        int[] len = new int[rank];
        for (long o = 0; o < outCount; o++) {
            long base = 0;
            int n = 1;
            for (int d = 0; d < rank; d++) {
                long start = (long) j[d] * factors[d];
                len[d] = (int) Math.max(0, Math.min(factors[d], inShape[d] - start));
                base += start * inStride[d];
                n *= len[d];
            }
            if (n > 0) {
                java.util.Arrays.fill(k, 0);
                for (int e = 0; e < n; e++) {
                    long index = base;
                    for (int d = 0; d < rank; d++) {
                        index += k[d] * inStride[d];
                    }
                    block[e] = raw(in, (int) index * size, size);
                    for (int d = rank - 1; d >= 0; d--) {
                        if (++k[d] < len[d]) {
                            break;
                        }
                        k[d] = 0;
                    }
                }
                long value = switch (method) {
                    case NEAREST -> block[0];
                    case MODE -> mode(block, n);
                    case MEAN -> mean(block, n, kind, size);
                };
                put(out, (int) o * size, size, value);
            }
            for (int d = rank - 1; d >= 0; d--) {
                if (++j[d] < outShape[d]) {
                    break;
                }
                j[d] = 0;
            }
        }
        return out;
    }

    private static Kind kind(DataType type) {
        return switch (type.kind()) {
            case BOOL -> Kind.BOOL;
            case INT -> type.byteCount() == 8 ? Kind.SIGNED64 : Kind.SIGNED;
            case UINT -> type.byteCount() == 8 ? Kind.UNSIGNED64 : Kind.UNSIGNED;
            case FLOAT -> switch (type.byteCount()) {
                case 2 -> Kind.F16;
                case 4 -> Kind.F32;
                default -> Kind.F64;
            };
            default -> throw new IllegalArgumentException("cannot downsample " + type);
        };
    }

    /** The most frequent of the block's first {@code n} values; of equally frequent ones, the first. */
    private static long mode(long[] block, int n) {
        long best = block[0];
        int bestCount = 0;
        for (int a = 0; a < n; a++) {
            int count = 0;
            for (int b = 0; b < n; b++) {
                if (block[b] == block[a]) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = block[a];
                bestCount = count;
            }
        }
        return best;
    }

    private static long mean(long[] block, int n, Kind kind, int size) {
        switch (kind) {
            case BOOL -> {
                int set = 0;
                for (int i = 0; i < n; i++) {
                    set += block[i] != 0 ? 1 : 0;
                }
                return 2 * set >= n ? 1 : 0;
            }
            case SIGNED, UNSIGNED -> {
                int shift = 64 - 8 * size;
                long sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += kind == Kind.SIGNED ? block[i] << shift >> shift : block[i];
                }
                return Math.floorDiv(2 * sum + n, 2L * n); // rounded half up
            }
            case SIGNED64, UNSIGNED64 -> {
                BigInteger sum = BigInteger.ZERO;
                for (int i = 0; i < n; i++) {
                    sum = sum.add(kind == Kind.SIGNED64 ? BigInteger.valueOf(block[i])
                            : new BigInteger(Long.toUnsignedString(block[i])));
                }
                BigInteger twice = sum.shiftLeft(1).add(BigInteger.valueOf(n));
                BigInteger divisor = BigInteger.valueOf(2L * n);
                BigInteger[] qr = twice.divideAndRemainder(divisor);
                BigInteger q = qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
                return q.longValue();
            }
            case F16 -> {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += Float.float16ToFloat((short) block[i]);
                }
                return Float.floatToFloat16((float) (sum / n)) & 0xFFFFL;
            }
            case F32 -> {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += Float.intBitsToFloat((int) block[i]);
                }
                return Float.floatToRawIntBits((float) (sum / n)) & 0xFFFFFFFFL;
            }
            default -> {
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += Double.longBitsToDouble(block[i]);
                }
                return Double.doubleToRawLongBits(sum / n);
            }
        }
    }

    /** An element's bytes as a long, zero-extended: its raw bits, which {@link #mean} reads by type. */
    private static long raw(byte[] b, int at, int size) {
        long v = 0;
        for (int i = size - 1; i >= 0; i--) {
            v = (v << 8) | (b[at + i] & 0xFF);
        }
        return v;
    }

    private static void put(byte[] b, int at, int size, long v) {
        for (int i = 0; i < size; i++) {
            b[at + i] = (byte) (v >>> (8 * i));
        }
    }
}
