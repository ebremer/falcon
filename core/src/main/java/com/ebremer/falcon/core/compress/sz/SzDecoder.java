package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A pure-Java decoder for SZ 2 (SZ 2.1.12, the version hdf5plugin builds), the error-bounded lossy
 * compressor behind HDF5 filter 32017. It decodes what {@code SZ_decompress} does, value for value:
 *
 * <ul>
 *   <li>floats and doubles: the classic (Lorenzo-predicted) format in 1 to 4 dimensions, SZ 2.1's
 *       regression format (2-D, and 3-D also for 4-D data), point-wise relative bounds (the logarithmic
 *       "pre_log" transform, and its accelerated "MSST19" form), all-equal data, and a stored copy;</li>
 *   <li>8-, 16-, 32- and 64-bit integers, signed or not: the classic format in 1 to 4 dimensions;</li>
 *   <li>the lossless stage: zstd (SZ's default), zlib, or none.</li>
 * </ul>
 *
 * Arrays are C order: the dimension passed last ({@code r1}) varies fastest. Decoded values come out
 * little-endian, bit for bit libSZ's, with one exception: the point-wise relative formats exponentiate
 * ({@code pow}, {@code exp2}) in double, and libSZ's last bit there is its C library's. Falcon uses
 * {@link Math#pow}, which on x86-64 agrees with the Windows C runtime (that hdf5plugin's libSZ uses) in all
 * but about 1 in 1,700 cases; a double decoded through such a case, and the predictions that follow from
 * it, can differ by a few units in the last place. Floats are rounded from those doubles: all tested match.
 *
 * <p>libSZ itself cannot read what it stores for exactly 20 floats or doubles: {@code SZ_compress} keeps
 * such short data as it is, with no header (its version check then fails). This decoder returns that data
 * as stored.
 */
public final class SzDecoder {

    /** {@code SZ_FLOAT}. */
    public static final int FLOAT = 0;
    /** {@code SZ_DOUBLE}. */
    public static final int DOUBLE = 1;
    /** {@code SZ_UINT8}. */
    public static final int UINT8 = 2;
    /** {@code SZ_INT8}. */
    public static final int INT8 = 3;
    /** {@code SZ_UINT16}. */
    public static final int UINT16 = 4;
    /** {@code SZ_INT16}. */
    public static final int INT16 = 5;
    /** {@code SZ_UINT32}. */
    public static final int UINT32 = 6;
    /** {@code SZ_INT32}. */
    public static final int INT32 = 7;
    /** {@code SZ_UINT64}. */
    public static final int UINT64 = 8;
    /** {@code SZ_INT64}. */
    public static final int INT64 = 9;

    /** {@code sol_ID} of the transposed 1-D layout ({@code SZ_Transpose}), decoded as one row. */
    static final int SZ_TRANSPOSE = 104;
    /** {@code MIN_NUM_OF_ELEMENTS}: data no longer than this is stored as it is. */
    private static final int MIN_NUM_OF_ELEMENTS = 20;
    /** {@code MIN_ZLIB_DEC_ALLOMEM_BYTES}: the least libSZ makes room for when it undoes its lossless stage. */
    private static final int MIN_ZLIB_DEC_ALLOMEM_BYTES = 1000000;

    private SzDecoder() {
    }

    /**
     * The size in bytes of one value of an SZ data type.
     *
     * @param dataType {@link #FLOAT} to {@link #INT64}
     * @return 1, 2, 4, or 8
     * @throws UnsupportedCompressionException for an unknown type
     */
    public static int elementSize(int dataType) {
        return switch (dataType) {
            case FLOAT, INT32, UINT32 -> 4;
            case DOUBLE, INT64, UINT64 -> 8;
            case INT8, UINT8 -> 1;
            case INT16, UINT16 -> 2;
            default -> throw new UnsupportedCompressionException("SZ data type " + dataType + " is not supported");
        };
    }

    /**
     * Decodes an SZ stream as {@code SZ_decompress(dataType, bytes, byteLength, r5, r4, r3, r2, r1)} does: the
     * dimensions (0 for an unused one, those of length 1 dropped as SZ drops them) give the value count.
     *
     * @param dataType {@link #FLOAT} to {@link #INT64}
     * @param stream   the compressed bytes
     * @param offset   where they start
     * @param length   their length
     * @param r5       the slowest-varying dimension of five, or 0
     * @param r4       the next, or 0
     * @param r3       the next, or 0
     * @param r2       the next, or 0
     * @param r1       the fastest-varying dimension
     * @param maxBytes the most decoded bytes the caller accepts
     * @return the decoded values, little-endian
     * @throws CompressionFormatException      if the stream is corrupt or does not fit the dimensions
     * @throws UnsupportedCompressionException for a form SZ 2.1.12 does not read either
     */
    public static byte[] decompress(int dataType, byte[] stream, int offset, int length, long r5, long r4, long r3,
                                    long r2, long r1, int maxBytes) {
        if (offset < 0 || length < 0 || length > stream.length - offset) {
            throw new IllegalArgumentException("invalid range " + offset + "+" + length + " of " + stream.length);
        }
        int size = elementSize(dataType);
        long[] r = filterDimension(r5, r4, r3, r2, r1);
        long count = dataLength(r[4], r[3], r[2], r[1], r[0]);
        if (count < 0 || count * size > maxBytes || count > Integer.MAX_VALUE - 8) {
            throw new CompressionFormatException("SZ data of " + count + " values is larger than " + maxBytes + " bytes");
        }
        int n = (int) count;
        try {
            return switch (dataType) {
                case FLOAT -> floats(stream, offset, length, r, n);
                case DOUBLE -> doubles(stream, offset, length, r, n);
                default -> SzIntegers.decode(dataType, stream, offset, length, r, n);
            };
        } catch (IndexOutOfBoundsException | ArithmeticException | NegativeArraySizeException e) {
            // A corrupt stream sends a prediction outside the array, or a count past what it holds.
            throw new CompressionFormatException("corrupt SZ stream: " + e.getMessage());
        }
    }

    private static byte[] floats(byte[] stream, int offset, int length, long[] r, int n) {
        if (n <= MIN_NUM_OF_ELEMENTS && length == 4 * n) {
            return java.util.Arrays.copyOfRange(stream, offset, offset + length); // SZ_skip_compress_float
        }
        // version, flags, parameters, length, and one value: a constant stream is not compressed again
        SzBuffer in = lossless(stream, offset, length, length == 8 + 4 + 28 || length == 8 + 8 + 28,
                (long) Math.max(4L * n, MIN_ZLIB_DEC_ALLOMEM_BYTES) + 4 + 28 + 8);
        SzStorage storage = new SzStorage(in, 4);
        float[] values = SzFloats.decode(storage, r[4], r[3], r[2], r[1], r[0], n);
        byte[] out = new byte[4 * n];
        for (int i = 0; i < n; i++) {
            int bits = Float.floatToRawIntBits(values[i]);
            out[4 * i] = (byte) bits;
            out[4 * i + 1] = (byte) (bits >>> 8);
            out[4 * i + 2] = (byte) (bits >>> 16);
            out[4 * i + 3] = (byte) (bits >>> 24);
        }
        return out;
    }

    private static byte[] doubles(byte[] stream, int offset, int length, long[] r, int n) {
        if (n <= MIN_NUM_OF_ELEMENTS && length == 8 * n) {
            return java.util.Arrays.copyOfRange(stream, offset, offset + length); // SZ_skip_compress_double
        }
        SzBuffer in = lossless(stream, offset, length, length == 12 + 4 + 36 || length == 12 + 8 + 36,
                (long) Math.max(8L * n, MIN_ZLIB_DEC_ALLOMEM_BYTES) + 4 + 36 + 8);
        SzStorage storage = new SzStorage(in, 8);
        double[] values = SzDoubles.decode(storage, r[4], r[3], r[2], r[1], r[0], n);
        byte[] out = new byte[8 * n];
        for (int i = 0; i < n; i++) {
            long bits = Double.doubleToRawLongBits(values[i]);
            for (int b = 0; b < 8; b++) {
                out[8 * i + b] = (byte) (bits >>> (8 * b));
            }
        }
        return out;
    }

    /**
     * Undoes SZ's lossless stage, as {@code SZ_decompress_args_float} and its kin do: a stream of the
     * constant-data size is taken as it is; otherwise one that starts as a zstd frame or a zlib stream is
     * decompressed (to at most {@code target} bytes), and anything else was not compressed further.
     */
    static SzBuffer lossless(byte[] stream, int offset, int length, boolean constantSize, long target) {
        if (constantSize) {
            return new SzBuffer(stream, offset, length);
        }
        int max = (int) Math.min(target, Integer.MAX_VALUE - 8);
        if (isZstd(stream, offset, length)) {
            byte[] bytes = ZstdDecoder.decompress(stream, offset, length, max);
            return new SzBuffer(bytes, 0, bytes.length);
        }
        if (length >= 2 && isZlib(stream[offset] & 0xFF, stream[offset + 1] & 0xFF)) {
            byte[] bytes = inflate(stream, offset, length, max);
            return new SzBuffer(bytes, 0, bytes.length);
        }
        return new SzBuffer(stream, offset, length);
    }

    /**
     * Whether {@code ZSTD_getFrameContentSize} finds a frame header: the zstd magic number with a valid frame
     * header descriptor, or a skippable frame's magic.
     */
    private static boolean isZstd(byte[] b, int off, int len) {
        if (len < 4) {
            return false;
        }
        int magic = (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
        if ((magic & 0xFFFFFFF0) == 0x184D2A50) {
            return len >= 8;
        }
        return magic == 0xFD2FB528 && len >= 5 && (b[off + 4] & 0x08) == 0;
    }

    /** {@code isZlibFormat}: the zlib headers libSZ recognises. */
    private static boolean isZlib(int b0, int b1) {
        return b0 == 104 && (b1 == 5 || b1 == 129 || b1 == 222)
                || b0 == 120 && (b1 == 1 || b1 == 94 || b1 == 156 || b1 == 218);
    }

    private static byte[] inflate(byte[] stream, int offset, int length, int max) {
        Inflater inflater = new Inflater();
        inflater.setInput(stream, offset, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished() && out.size() < max) {
                int count = inflater.inflate(buffer, 0, Math.min(buffer.length, max - out.size()));
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new CompressionFormatException("SZ zlib stage: " + e.getMessage());
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** The point-wise relative format's signs: zstd, one byte per value (missing ones read as positive). */
    static byte[] signs(SzStorage s, int n) {
        s.in.require(s.pwrErrBoundAt, s.pwrErrBoundSize);
        byte[] copy = new byte[s.pwrErrBoundSize];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = (byte) s.in.u8(s.pwrErrBoundAt + (long) i);
        }
        byte[] signs = ZstdDecoder.decompress(copy, 0, copy.length, n);
        return signs.length == n ? signs : java.util.Arrays.copyOf(signs, n);
    }

    /** {@code exp2}, through {@link Math#pow} (see the class description on its last bit). */
    static double exp2(double x) {
        return Math.pow(2.0, x);
    }

    /** The quantization interval count of the regression format, bounded as {@link SzStorage} bounds it. */
    static int intervals(int value) {
        if (value < 0 || value > SzStorage.MAX_INTERVALS) {
            throw new CompressionFormatException("SZ stream of " + Integer.toUnsignedString(value)
                    + " quantization intervals");
        }
        return value;
    }

    /**
     * The point-wise relative ratios of the MSST19 decoders, {@code pow(1 + realPrecision, inv * (i - radius))}
     * with {@code inv = 2 - 2^-plus_bits}, computed as they are used.
     */
    static final class PrecisionTable {
        private final double base;
        private final double inv;
        private final int radius;
        private final double[] values;
        private final boolean[] known;

        PrecisionTable(SzStorage s) {
            this.base = 1 + s.realPrecision;
            this.inv = 2.0 - Math.pow(2, -s.plusBits);
            this.radius = s.intervals / 2;
            this.values = new double[s.intervals];
            this.known = new boolean[s.intervals];
        }

        double get(int i) {
            if (!known[i]) {
                values[i] = Math.pow(base, inv * (i - radius));
                known[i] = true;
            }
            return values[i];
        }
    }

    /**
     * One dimension's blocks in the regression format ({@code SZ_COMPUTE_3D_NUMBER_OF_BLOCKS},
     * {@code SZ_COMPUTE_BLOCKCOUNT}): {@code count} blocks, the first {@code split} one longer.
     */
    static final class Blocks {
        final int count;
        private final int split;
        private final int early;
        private final int late;

        /**
         * @param blockSize the stream's block size (a C {@code int} widened to {@code size_t})
         */
        Blocks(long length, long blockSize) {
            long size = blockSize < 0 ? Long.MAX_VALUE : blockSize;
            if (size == 0) {
                throw new CompressionFormatException("SZ regression block size of 0");
            }
            count = length <= size ? 1 : (int) (length / size);
            late = (int) (length / count);
            split = (int) (length % count);
            early = split != 0 ? late + 1 : late;
        }

        int size(int i) {
            return i < split ? early : late;
        }

        int offset(int i) {
            return i < split ? i * early : i * late + split;
        }
    }

    /** {@code filterDimension}: drops the dimensions of length 1, as SZ does before (de)compressing. */
    static long[] filterDimension(long r5, long r4, long r3, long r2, long r1) {
        int dim = dimension(r5, r4, r3, r2, r1);
        long[] c = {r1, r2, r3, r4, r5};
        if (dim == 2) {
            if (r2 == 1) {
                c[1] = 0;
            }
            if (r1 == 1) {
                c[0] = c[1];
                c[1] = c[2];
            }
        } else if (dim == 3) {
            if (r3 == 1) {
                c[2] = 0;
            }
            if (r2 == 1) {
                c[1] = c[2];
                c[2] = c[3];
            }
            if (r1 == 1) {
                c[0] = c[1];
                c[1] = c[2];
                c[2] = c[3];
            }
        } else if (dim == 4) {
            if (r4 == 1) {
                c[3] = 0;
            }
            if (r3 == 1) {
                c[2] = c[3];
                c[3] = c[4];
            }
            if (r2 == 1) {
                c[1] = c[2];
                c[2] = c[3];
                c[3] = c[4];
            }
            if (r1 == 1) {
                c[0] = c[1];
                c[1] = c[2];
                c[2] = c[3];
                c[3] = c[4];
            }
        } else if (dim == 5) {
            if (r5 == 1) {
                c[4] = 0;
            }
            if (r4 == 1) {
                c[3] = c[4];
                c[4] = 0;
            }
            if (r3 == 1) {
                c[2] = c[3];
                c[3] = c[4];
                c[4] = 0;
            }
            if (r2 == 1) {
                c[1] = c[2];
                c[2] = c[3];
                c[3] = c[4];
                c[4] = 0;
            }
            if (r1 == 1) {
                c[0] = c[1];
                c[1] = c[2];
                c[2] = c[3];
                c[3] = c[4];
                c[4] = 0;
            }
        }
        return c;
    }

    /** {@code computeDimension}. */
    static int dimension(long r5, long r4, long r3, long r2, long r1) {
        return r1 == 0 ? 0 : r2 == 0 ? 1 : r3 == 0 ? 2 : r4 == 0 ? 3 : r5 == 0 ? 4 : 5;
    }

    /** {@code computeDataLength}, refusing negative or overflowing lengths. */
    static long dataLength(long r5, long r4, long r3, long r2, long r1) {
        long[] dims = {r1, r2, r3, r4, r5};
        int dim = dimension(r5, r4, r3, r2, r1);
        long n = dim == 0 ? 0 : 1;
        for (int i = 0; i < dim; i++) {
            if (dims[i] < 0) {
                throw new CompressionFormatException("SZ dimension of " + dims[i]);
            }
            n = Math.multiplyExact(n, dims[i]);
        }
        return n;
    }
}
