package com.ebremer.falcon.core.compress.zfp;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;

/**
 * A zfp stream's full header ({@code zfp_read_header} with {@code ZFP_HEADER_FULL}, zfp 1.0.1's
 * {@code zfp.c}): the field it encodes and the compression parameters. In bit-stream order:
 * <ul>
 *   <li>32 bits of magic: {@code 'z' 'f' 'p'} and the codec version, 5 since zfp 0.5.0;</li>
 *   <li>52 bits of field metadata: the scalar type (2 bits), the dimensionality less one (2 bits), then each
 *       size less one, {@code x} first: 48 bits in one dimension (of which zfp reads 32), 24 in two, 16 in
 *       three, 12 in four;</li>
 *   <li>the mode: 12 bits for the common modes ({@code zfp_stream_mode}'s short form: fixed rate, precision,
 *       accuracy, reversible), or, when those 12 bits are all ones, 52 more for each parameter.</li>
 * </ul>
 *
 * <p>Sizes are as zfp counts them: {@code nx} varies fastest, and a missing dimension is 0.
 *
 * <p>To compress, start from {@link #of} and choose the mode as zfp's {@code zfp_stream_set_*} functions do:
 * {@link #withRate}, {@link #withPrecision}, {@link #withAccuracy}, {@link #withReversible}, or
 * {@link #withParameters} (expert mode); {@link ZfpEncoder} then codes a field with it.
 *
 * @param type    the scalar type
 * @param nx      the size along x (fastest varying), at least 1
 * @param ny      the size along y, or 0 in one dimension
 * @param nz      the size along z, or 0 in fewer than three dimensions
 * @param nw      the size along w, or 0 in fewer than four dimensions
 * @param minbits the fewest bits each block takes
 * @param maxbits the most bits each block takes
 * @param maxprec the most bit planes coded, 1 to 64
 * @param minexp  the smallest bit plane coded (as a power of two); below {@link #MIN_EXP} for reversible mode
 */
public record ZfpHeader(Type type, long nx, long ny, long nz, long nw, int minbits, int maxbits, int maxprec,
                        int minexp) {

    /** The zfp codec version this decodes ({@code ZFP_CODEC}). */
    public static final int CODEC = 5;
    /** The smallest base-2 exponent of a double ({@code ZFP_MIN_EXP}); a lower {@code minexp} means reversible. */
    public static final int MIN_EXP = -1074;
    /** The most bits a block may take ({@code ZFP_MAX_BITS}). */
    public static final int MAX_BITS = 16658;
    /** A header's length in bits with a 12-bit mode: magic, field metadata, and mode. */
    public static final int SHORT_HEADER_BITS = 96;
    /** A header's length in bits with a 64-bit mode. */
    public static final int LONG_HEADER_BITS = 148;

    /** The largest mode written in 12 bits ({@code ZFP_MODE_SHORT_MAX}); any other takes 64. */
    static final int MODE_SHORT_MAX = (1 << 12) - 2;

    private static final int MODE_FIXED_RATE = 0;
    private static final int MODE_FIXED_PRECISION = 1;
    private static final int MODE_FIXED_ACCURACY = 2;
    private static final int MODE_REVERSIBLE = 3;
    private static final int MODE_EXPERT = 4;

    /**
     * A header, checked as {@code zfp_stream_set_params} checks the parameters.
     *
     * @throws IllegalArgumentException if a size or parameter is out of range
     */
    public ZfpHeader {
        java.util.Objects.requireNonNull(type, "type");
        if (nx < 1 || ny < 0 || nz < 0 || nw < 0 || (ny == 0 && nz != 0) || (nz == 0 && nw != 0)) {
            throw new IllegalArgumentException("invalid zfp field size " + nx + " x " + ny + " x " + nz + " x " + nw);
        }
        if (minbits < 0 || minbits > maxbits || maxprec < 1 || maxprec > 64) {
            throw new IllegalArgumentException("invalid zfp parameters: minbits " + minbits + ", maxbits " + maxbits
                    + ", maxprec " + maxprec);
        }
    }

    /** zfp's scalar types ({@code zfp_type}), in the order the header numbers them. */
    public enum Type {
        /** 32-bit two's complement integers. */
        INT32(4),
        /** 64-bit two's complement integers. */
        INT64(8),
        /** IEEE single precision. */
        FLOAT(4),
        /** IEEE double precision. */
        DOUBLE(8);

        private final int size;

        Type(int size) {
            this.size = size;
        }

        /**
         * The scalar's size.
         *
         * @return its size in bytes
         */
        public int size() {
            return size;
        }
    }

    /**
     * The dimensionality.
     *
     * @return 1 to 4
     */
    public int dimensions() {
        return ny == 0 ? 1 : nz == 0 ? 2 : nw == 0 ? 3 : 4;
    }

    /**
     * The number of scalars the field holds.
     *
     * @return the product of its sizes
     */
    public long elements() {
        return nx * Math.max(ny, 1) * Math.max(nz, 1) * Math.max(nw, 1);
    }

    /**
     * Whether the stream is lossless ({@code REVERSIBLE}: {@code minexp} below {@link #MIN_EXP}).
     *
     * @return true in reversible mode
     */
    public boolean reversible() {
        return minexp < MIN_EXP;
    }

    /**
     * A header for a field, with the parameters a new zfp stream has ({@code zfp_stream_open}): every bit
     * plane coded, no rate limit (expert mode, as near lossless as the non-reversible coding comes).
     *
     * @param type the scalar type
     * @param nx   the size along x (fastest varying), at least 1
     * @param ny   the size along y, or 0 in one dimension
     * @param nz   the size along z, or 0 in fewer than three dimensions
     * @param nw   the size along w, or 0 in fewer than four dimensions
     * @return the header
     * @throws IllegalArgumentException if a size is out of range
     */
    public static ZfpHeader of(Type type, long nx, long ny, long nz, long nw) {
        return new ZfpHeader(type, nx, ny, nz, nw, 1, MAX_BITS, 64, MIN_EXP);
    }

    /**
     * This field in fixed-rate mode ({@code zfp_stream_set_rate}, not aligned for random access): every block
     * takes {@code floor(4^d * rate + 0.5)} bits. zfp raises a float's block to 9 bits and a double's to 12
     * when it is told the scalar type, as H5Z-ZFP tells it ({@code forType}), but not when it is not, as zfpy,
     * and so numcodecs, calls it.
     *
     * @param rate    the bits per value, at least 0
     * @param forType whether the scalar type's floor applies
     * @return the header with that mode
     * @throws IllegalArgumentException if a block would take no bits, or more than a header records (32768)
     */
    public ZfpHeader withRate(double rate, boolean forType) {
        if (!(rate >= 0) || Double.isInfinite(rate)) {
            throw new IllegalArgumentException("invalid zfp rate " + rate);
        }
        double n = Math.floor((1 << (2 * dimensions())) * rate + 0.5);
        int bits = n > 0x8000 ? Integer.MAX_VALUE : (int) n;
        if (forType && type == Type.FLOAT) {
            bits = Math.max(bits, 1 + 8);
        } else if (forType && type == Type.DOUBLE) {
            bits = Math.max(bits, 1 + 11);
        }
        if (bits < 1 || bits > 0x8000) {
            throw new IllegalArgumentException("zfp rate " + rate + " gives blocks of " + (long) n
                    + " bits; a header records 1 to 32768");
        }
        return new ZfpHeader(type, nx, ny, nz, nw, bits, bits, 64, MIN_EXP);
    }

    /**
     * This field in fixed-precision mode ({@code zfp_stream_set_precision}): {@code precision} bit planes of
     * each block, 0 meaning all 64 (which zfp counts as expert mode).
     *
     * @param precision the bit planes, 0 to 64 (more is 64)
     * @return the header with that mode
     * @throws IllegalArgumentException if {@code precision} is negative
     */
    public ZfpHeader withPrecision(int precision) {
        if (precision < 0) {
            throw new IllegalArgumentException("invalid zfp precision " + precision);
        }
        return new ZfpHeader(type, nx, ny, nz, nw, 1, MAX_BITS, precision == 0 ? 64 : Math.min(precision, 64),
                MIN_EXP);
    }

    /**
     * This field in fixed-accuracy mode ({@code zfp_stream_set_accuracy}): bit planes are coded down to
     * 2<sup>e</sup>, the largest power of two not above {@code tolerance}; a tolerance of 0 or less codes every
     * plane (which zfp counts as expert mode).
     *
     * @param tolerance the absolute error tolerated
     * @return the header with that mode
     * @throws IllegalArgumentException if {@code tolerance} is NaN or infinite
     */
    public ZfpHeader withAccuracy(double tolerance) {
        if (!Double.isFinite(tolerance)) {
            throw new IllegalArgumentException("invalid zfp tolerance " + tolerance);
        }
        int emin = tolerance > 0 ? ZfpEncoder.frexp(tolerance) - 1 : MIN_EXP;
        return new ZfpHeader(type, nx, ny, nz, nw, 1, MAX_BITS, 64, emin);
    }

    /**
     * This field in reversible (lossless) mode ({@code zfp_stream_set_reversible}).
     *
     * @return the header with that mode
     */
    public ZfpHeader withReversible() {
        return new ZfpHeader(type, nx, ny, nz, nw, 1, MAX_BITS, 64, MIN_EXP - 1);
    }

    /**
     * This field with each parameter given ({@code zfp_stream_set_params}, expert mode); a {@code minexp}
     * below {@link #MIN_EXP} is the reversible mode.
     *
     * @param minbits the fewest bits each block takes
     * @param maxbits the most bits each block takes
     * @param maxprec the most bit planes coded, 1 to 64
     * @param minexp  the smallest bit plane coded, as a power of two
     * @return the header with those parameters
     * @throws IllegalArgumentException if {@code minbits} exceeds {@code maxbits}, or {@code maxprec} is out of
     *                                  range
     */
    public ZfpHeader withParameters(int minbits, int maxbits, int maxprec, int minexp) {
        return new ZfpHeader(type, nx, ny, nz, nw, minbits, maxbits, maxprec, minexp);
    }

    /**
     * The mode as a header records it ({@code zfp_stream_mode}): 12 bits for fixed rate (up to 2048 bits a
     * block), fixed precision, fixed accuracy (down to 2<sup>843</sup>), and reversible, as zfp tells them
     * apart; otherwise 64 bits, the low 12 all ones, then each parameter.
     *
     * @return the mode; above 4094 it takes 64 bits
     * @throws IllegalArgumentException if a parameter is beyond what the 64-bit form records ({@code maxbits}
     *                                  to 32768, {@code minexp} from -16495 to 16272)
     */
    public long encodedMode() {
        switch (compressionMode()) {
            case MODE_FIXED_RATE -> {
                if (maxbits <= 2048) {
                    return maxbits - 1;
                }
            }
            case MODE_FIXED_PRECISION -> {
                return maxprec - 1 + 2048; // maxprec is at most 64, under the short form's 128
            }
            case MODE_FIXED_ACCURACY -> {
                if (minexp <= 843) {
                    return (long) minexp - MIN_EXP + (2048 + 128 + 1);
                }
            }
            case MODE_REVERSIBLE -> {
                return 2048 + 128;
            }
            default -> {
                // each parameter, below
            }
        }
        if (maxbits > 0x8000 || minexp < -16495 || minexp > 0x7fff - 16495) {
            throw new IllegalArgumentException("a zfp header cannot record maxbits " + maxbits + " or minexp "
                    + minexp);
        }
        long mode = minexp + 16495;
        mode = (mode << 7) + maxprec - 1;
        mode = (mode << 15) + Math.max(1, maxbits) - 1;
        mode = (mode << 15) + Math.max(1, minbits) - 1;
        return (mode << 12) + 0xfff;
    }

    /** {@code zfp_stream_compression_mode}: which of zfp's modes the parameters make. */
    private int compressionMode() {
        if (minbits == 1 && maxbits == MAX_BITS && maxprec == 64 && minexp == MIN_EXP) {
            return MODE_EXPERT; // zfp's defaults
        }
        if (minbits == maxbits && maxbits >= 1 && maxbits <= MAX_BITS && maxprec >= 64 && minexp == MIN_EXP) {
            return MODE_FIXED_RATE;
        }
        boolean unlimited = minbits <= 1 && maxbits >= MAX_BITS;
        if (unlimited && minexp == MIN_EXP) {
            return MODE_FIXED_PRECISION;
        }
        if (unlimited && maxprec >= 64) {
            return minexp >= MIN_EXP ? MODE_FIXED_ACCURACY : MODE_REVERSIBLE;
        }
        return MODE_EXPERT;
    }

    /**
     * Reads a full header from the start of {@code length} bytes of {@code src} at {@code offset}.
     *
     * @param src    the bytes holding the header
     * @param offset where it starts
     * @param length the bytes available
     * @return the header
     * @throws CompressionFormatException      if the bytes are not a zfp header, or it is truncated or invalid
     * @throws UnsupportedCompressionException if it is of another codec version
     */
    public static ZfpHeader read(byte[] src, int offset, int length) {
        ZfpDecoder.checkRange(src, offset, length);
        return read(new ZfpBitReader(src, offset, length));
    }

    /** Reads a full header from {@code in}, leaving it at the first bit after the header. */
    static ZfpHeader read(ZfpBitReader in) {
        long z = in.readBits(8);
        long f = in.readBits(8);
        long p = in.readBits(8);
        long codec = in.readBits(8);
        if (z != 'z' || f != 'f' || p != 'p') {
            throw new CompressionFormatException("not a zfp header: no 'zfp' magic");
        }
        if (codec != CODEC) {
            throw new UnsupportedCompressionException("zfp codec version " + codec + " is not supported, only "
                    + CODEC + " (zfp 0.5 to 1.0)");
        }
        // zfp_field_set_metadata
        long meta = in.readBits(52);
        Type type = Type.values()[(int) (meta & 3)];
        meta >>>= 2;
        int dims = (int) (meta & 3) + 1;
        meta >>>= 2;
        long nx;
        long ny = 0;
        long nz = 0;
        long nw = 0;
        switch (dims) {
            case 1 -> nx = (meta & 0xffffffffL) + 1; // "currently dimensions are limited to 2^32 - 1"
            case 2 -> {
                nx = (meta & 0xffffff) + 1;
                ny = ((meta >>> 24) & 0xffffff) + 1;
            }
            case 3 -> {
                nx = (meta & 0xffff) + 1;
                ny = ((meta >>> 16) & 0xffff) + 1;
                nz = ((meta >>> 32) & 0xffff) + 1;
            }
            default -> {
                nx = (meta & 0xfff) + 1;
                ny = ((meta >>> 12) & 0xfff) + 1;
                nz = ((meta >>> 24) & 0xfff) + 1;
                nw = ((meta >>> 36) & 0xfff) + 1;
            }
        }
        long mode = in.readBits(12);
        if (mode > MODE_SHORT_MAX) {
            mode += in.readBits(52) << 12;
        }
        return withMode(type, nx, ny, nz, nw, mode);
    }

    /** The header for a field and an encoded mode ({@code zfp_stream_set_mode}). */
    private static ZfpHeader withMode(Type type, long nx, long ny, long nz, long nw, long mode) {
        int minbits;
        int maxbits;
        int maxprec;
        int minexp;
        if (Long.compareUnsigned(mode, MODE_SHORT_MAX) <= 0) { // the mode is a uint64
            if (mode < 2048) { // fixed rate
                minbits = maxbits = (int) mode + 1;
                maxprec = 64;
                minexp = MIN_EXP;
            } else if (mode < 2048 + 128) { // fixed precision
                minbits = 1;
                maxbits = MAX_BITS;
                maxprec = (int) mode + 1 - 2048;
                minexp = MIN_EXP;
            } else if (mode == 2048 + 128) { // reversible
                minbits = 1;
                maxbits = MAX_BITS;
                maxprec = 64;
                minexp = MIN_EXP - 1;
            } else { // fixed accuracy
                minbits = 1;
                maxbits = MAX_BITS;
                maxprec = 64;
                minexp = (int) mode + MIN_EXP - (2048 + 128 + 1);
            }
        } else {
            mode >>>= 12;
            minbits = (int) (mode & 0x7fff) + 1;
            mode >>>= 15;
            maxbits = (int) (mode & 0x7fff) + 1;
            mode >>>= 15;
            maxprec = (int) (mode & 0x7f) + 1;
            mode >>>= 7;
            minexp = (int) (mode & 0x7fff) - 16495;
        }
        // zfp_stream_set_params
        if (minbits > maxbits || maxprec < 1 || maxprec > 64) {
            throw new CompressionFormatException("zfp header has invalid parameters: minbits " + minbits + ", maxbits "
                    + maxbits + ", maxprec " + maxprec);
        }
        return new ZfpHeader(type, nx, ny, nz, nw, minbits, maxbits, maxprec, minexp);
    }
}
