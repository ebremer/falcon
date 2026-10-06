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

    private static final int MODE_SHORT_MAX = (1 << 12) - 2;

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
