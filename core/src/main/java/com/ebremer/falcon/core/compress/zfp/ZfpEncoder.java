package com.ebremer.falcon.core.compress.zfp;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * zfp compression: a port of zfp 1.0.1's encoder (Peter Lindstrom, LLNL; {@code src/template/encode*.c},
 * {@code revencode*.c}, {@code compress.c}, and {@code zfp_write_header} in {@code zfp.c}), every stream bit
 * for bit libzfp's; {@link ZfpDecoder} is its inverse. The field is coded in blocks of 4<sup>d</sup> values as
 * {@link ZfpDecoder} describes, a partial block at a far edge padded as libzfp pads it (each missing value a
 * copy of one inside), in the mode and parameters the {@link ZfpHeader} holds.
 *
 * <p>Where C leaves the result undefined, this follows libzfp built for x86-64 Windows (zfpy's and
 * hdf5plugin's wheels), the builds its streams are checked against: a floating-point value too large (or NaN)
 * for the block's integer becomes the integer's minimum, as the processor's conversion makes it, and so does
 * every value of a block whose largest value is subnormal, whose scale factor overflows (zfp's issue 119); a
 * block whose largest magnitude is infinite takes the exponent -1, as the Microsoft C runtime's {@code frexp}
 * gives it (glibc's gives 0, so a Linux libzfp codes such a block differently). zfp is meant for finite
 * values; the reversible mode keeps any value, NaN and the infinities included, bit for bit.
 *
 * <p>The input is the field's values, little-endian, {@code x} varying fastest, as {@link ZfpDecoder}
 * returns them. A stream ends padded to a whole word of zfp's bit stream: H5Z-ZFP, as hdf5plugin builds it,
 * uses 8-bit words; zfpy, and so numcodecs, 64-bit words.
 */
public final class ZfpEncoder {

    private static final long UINT_MASK = 0xffffffffL;
    private static final int NBMASK32 = 0xaaaaaaaa;
    private static final long NBMASK64 = 0xaaaaaaaaaaaaaaaaL;
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private ZfpEncoder() {
    }

    /**
     * Compresses a field into a bare zfp stream ({@code zfp_compress}), as H5Z-ZFP stores each chunk, its
     * header kept elsewhere.
     *
     * @param header   the field and parameters
     * @param src      the field's values, little-endian, {@code x} varying fastest
     * @param offset   where they start
     * @param wordBits the bit stream's word size, 8, 16, 32, or 64: the stream is padded to a whole word
     * @return the stream
     * @throws IllegalArgumentException if {@code src} holds fewer values than the field, or the word size is
     *                                  none of those
     */
    public static byte[] compress(ZfpHeader header, byte[] src, int offset, int wordBits) {
        ZfpBitWriter out = writer(header, src, offset, wordBits);
        new Field(header, src, offset, out).encode();
        return out.toByteArray(wordBits);
    }

    /**
     * Compresses a field into a zfp stream that starts with its full header ({@code zfp_write_header} with
     * {@code ZFP_HEADER_FULL}, then {@code zfp_compress} into the same stream), as zfpy writes it for numcodecs'
     * zfpy codec.
     *
     * @param header   the field and parameters, written as the stream's header
     * @param src      the field's values, little-endian, {@code x} varying fastest
     * @param offset   where they start
     * @param wordBits the bit stream's word size, 8, 16, 32, or 64 (zfpy's is 64)
     * @return the stream
     * @throws IllegalArgumentException if {@code src} holds fewer values than the field, the word size is none
     *                                  of those, or the header cannot record the field's sizes
     */
    public static byte[] compressWithHeader(ZfpHeader header, byte[] src, int offset, int wordBits) {
        ZfpBitWriter out = writer(header, src, offset, wordBits);
        writeHeader(header, out);
        new Field(header, src, offset, out).encode();
        return out.toByteArray(wordBits);
    }

    /**
     * The full header alone ({@code zfp_write_header} with {@code ZFP_HEADER_FULL}, then
     * {@code zfp_stream_flush}): 96 bits with a short mode, 148 with a long one, padded to a whole word. H5Z-ZFP
     * keeps it in the filter's client data.
     *
     * @param header   the field and parameters
     * @param wordBits the bit stream's word size, 8, 16, 32, or 64
     * @return the header's bytes
     * @throws IllegalArgumentException if the header cannot record the field's sizes, or the word size is none
     *                                  of those
     */
    public static byte[] header(ZfpHeader header, int wordBits) {
        checkWordBits(wordBits);
        ZfpBitWriter out = new ZfpBitWriter();
        writeHeader(header, out);
        return out.toByteArray(wordBits);
    }

    private static ZfpBitWriter writer(ZfpHeader header, byte[] src, int offset, int wordBits) {
        java.util.Objects.requireNonNull(header, "header");
        checkWordBits(wordBits);
        long bytes = header.elements() * header.type().size();
        if (offset < 0 || offset > src.length || src.length - offset < bytes) {
            throw new IllegalArgumentException("the field needs " + bytes + " bytes from offset " + offset + ", but "
                    + src.length + " are given");
        }
        return new ZfpBitWriter();
    }

    private static void checkWordBits(int wordBits) {
        if (wordBits != 8 && wordBits != 16 && wordBits != 32 && wordBits != 64) {
            throw new IllegalArgumentException("a zfp bit stream word is 8, 16, 32, or 64 bits, not " + wordBits);
        }
    }

    /** {@code zfp_write_header} with {@code ZFP_HEADER_FULL}: the magic, the field's metadata, and the mode. */
    private static void writeHeader(ZfpHeader h, ZfpBitWriter out) {
        long meta = metadata(h);
        out.writeBits('z', 8);
        out.writeBits('f', 8);
        out.writeBits('p', 8);
        out.writeBits(ZfpHeader.CODEC, 8);
        out.writeBits(meta, 52);
        long mode = h.encodedMode();
        out.writeBits(mode, Long.compareUnsigned(mode, ZfpHeader.MODE_SHORT_MAX) > 0 ? 64 : 12);
    }

    /** {@code zfp_field_metadata}: each size less one, {@code x} first, then the dimensionality and type. */
    private static long metadata(ZfpHeader h) {
        int dims = h.dimensions();
        int bits = switch (dims) {
            case 1 -> 48;
            case 2 -> 24;
            case 3 -> 16;
            default -> 12;
        };
        long[] sizes = {h.nx(), h.ny(), h.nz(), h.nw()};
        long meta = 0;
        for (int i = dims - 1; i >= 0; i--) {
            if ((sizes[i] - 1) >>> bits != 0) {
                throw new IllegalArgumentException("a zfp header cannot record a size of " + sizes[i] + " in " + dims
                        + " dimensions (at most 2^" + bits + ")");
            }
            meta = (meta << bits) + sizes[i] - 1;
        }
        meta = (meta << 2) + dims - 1;
        return (meta << 2) + h.type().ordinal();
    }

    /** One field's encoding state: the values, the stream, the parameters, and scratch blocks. */
    private static final class Field {

        private final byte[] src;
        private final int offset;
        private final ZfpBitWriter out;
        private final ZfpHeader.Type type;
        private final int dims;
        private final int size;
        private final int[] perm;
        private final long minbits;
        private final long maxbits;
        private final int maxprec;
        private final int minexp;
        private final boolean reversible;
        private final int nx;
        private final int ny;
        private final int nz;
        private final int nw;
        private final long[] raw;     // the gathered block's values, as their bits
        private final int[] iblock32;
        private final int[] ublock32;
        private final long[] iblock64;
        private final long[] ublock64;

        Field(ZfpHeader header, byte[] src, int offset, ZfpBitWriter out) {
            this.src = src;
            this.offset = offset;
            this.out = out;
            this.type = header.type();
            this.dims = header.dimensions();
            this.size = 1 << (2 * dims);
            this.perm = ZfpDecoder.perm(dims);
            this.minbits = header.minbits() & UINT_MASK;
            this.maxbits = header.maxbits() & UINT_MASK;
            this.maxprec = header.maxprec();
            this.minexp = header.minexp();
            this.reversible = header.reversible();
            // the field fits in an array, so each size fits in an int
            this.nx = (int) header.nx();
            this.ny = (int) Math.max(header.ny(), 1);
            this.nz = (int) Math.max(header.nz(), 1);
            this.nw = (int) Math.max(header.nw(), 1);
            this.raw = new long[size];
            boolean wide = type.size() == 8;
            this.iblock32 = wide ? null : new int[size];
            this.ublock32 = wide ? null : new int[size];
            this.iblock64 = wide ? new long[size] : null;
            this.ublock64 = wide ? new long[size] : null;
        }

        /** Encodes every block in order (w, z, y, x outermost to innermost), as {@code compress.c} does. */
        void encode() {
            int stepY = dims >= 2 ? 4 : 1;
            int stepZ = dims >= 3 ? 4 : 1;
            int stepW = dims >= 4 ? 4 : 1;
            for (int w = 0; w < nw; w += stepW) {
                for (int z = 0; z < nz; z += stepZ) {
                    for (int y = 0; y < ny; y += stepY) {
                        for (int x = 0; x < nx; x += 4) {
                            gather(x, y, z, w, Math.min(4, nx - x), Math.min(stepY, ny - y), Math.min(stepZ, nz - z),
                                    Math.min(stepW, nw - w));
                            encodeBlock();
                        }
                    }
                }
            }
        }

        private void encodeBlock() {
            switch (type) {
                case INT32 -> {
                    for (int i = 0; i < size; i++) {
                        iblock32[i] = (int) raw[i];
                    }
                    if (reversible) {
                        revEncodeInts32(minbits, maxbits, maxprec);
                    } else {
                        encodeInts32(minbits, maxbits, maxprec);
                    }
                }
                case INT64 -> {
                    System.arraycopy(raw, 0, iblock64, 0, size);
                    if (reversible) {
                        revEncodeInts64(minbits, maxbits, maxprec);
                    } else {
                        encodeInts64(minbits, maxbits, maxprec);
                    }
                }
                case FLOAT -> {
                    if (reversible) {
                        revEncodeFloat();
                    } else {
                        encodeFloat();
                    }
                }
                case DOUBLE -> {
                    if (reversible) {
                        revEncodeDouble();
                    } else {
                        encodeDouble();
                    }
                }
            }
        }

        // ------------------------------------------------------------------ gathering (encode1.c to encode4.c)

        /**
         * Copies the block at ({@code x0}, {@code y0}, {@code z0}, {@code w0}) of {@code ex} by {@code ey} by
         * {@code ez} by {@code ew} values into {@code raw}, padding a partial block along x, then y, z, and w, as
         * {@code gather_partial} does (a full block is a partial one that needs no padding).
         */
        private void gather(int x0, int y0, int z0, int w0, int ex, int ey, int ez, int ew) {
            for (int w = 0; w < ew; w++) {
                for (int z = 0; z < ez; z++) {
                    for (int y = 0; y < ey; y++) {
                        long row = x0 + (long) nx * ((y0 + y) + (long) ny * ((z0 + z) + (long) nz * (w0 + w)));
                        int q = 4 * y + 16 * z + 64 * w;
                        for (int x = 0; x < ex; x++) {
                            raw[q + x] = value((int) (row + x));
                        }
                        pad(q, ex, 1);
                    }
                    if (dims >= 2) {
                        for (int x = 0; x < 4; x++) {
                            pad(16 * z + 64 * w + x, ey, 4);
                        }
                    }
                }
                if (dims >= 3) {
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            pad(64 * w + 4 * y + x, ez, 16);
                        }
                    }
                }
            }
            if (dims >= 4) {
                for (int z = 0; z < 4; z++) {
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            pad(16 * z + 4 * y + x, ew, 64);
                        }
                    }
                }
            }
        }

        /** The bits of the field's value at index {@code at}. */
        private long value(int at) {
            return type.size() == 8 ? (long) LONG_LE.get(src, offset + at * 8)
                    : (int) INT_LE.get(src, offset + at * 4) & UINT_MASK;
        }

        /** {@code pad_block}: fills a row of {@code n} values (of 4, stride {@code s}) with copies of them. */
        private void pad(int p, int n, int s) {
            switch (n) {
                case 0:
                    raw[p] = 0;
                    // fall through
                case 1:
                    raw[p + s] = raw[p];
                    // fall through
                case 2:
                    raw[p + 2 * s] = raw[p + s];
                    // fall through
                case 3:
                    raw[p + 3 * s] = raw[p];
                    break;
                default:
                    break;
            }
        }

        // ------------------------------------------------------------------ floating point (encodef.c)

        /** The bit planes to encode for a block of largest exponent {@code emax} ({@code precision}, codecf.c). */
        private int precision(int emax) {
            return (int) Math.min(maxprec, Math.max(0L, (long) emax - minexp + 2L * dims + 2));
        }

        /** {@code exponent_block} for floats: the largest magnitude's {@code frexpf} exponent, at least -126. */
        private int emaxFloat() {
            float max = 0;
            for (int i = 0; i < size; i++) {
                float f = Math.abs(Float.intBitsToFloat((int) raw[i]));
                if (max < f) {
                    max = f;
                }
            }
            return max > 0 ? Math.max(frexp(max), -126) : -127;
        }

        /** {@code exponent_block} for doubles, at least -1022. */
        private int emaxDouble() {
            double max = 0;
            for (int i = 0; i < size; i++) {
                double f = Math.abs(Double.longBitsToDouble(raw[i]));
                if (max < f) {
                    max = f;
                }
            }
            return max > 0 ? Math.max(frexp(max), -1022) : -1023;
        }

        /** {@code fwd_cast}: each value times {@code ldexpf(1, 30 - emax)}, in single precision, truncated. */
        private void fwdCastFloat(int emax) {
            float s = (float) Math.scalb(1.0, 30 - emax); // exact in double, rounded once, as ldexpf rounds
            for (int i = 0; i < size; i++) {
                iblock32[i] = toInt(s * Float.intBitsToFloat((int) raw[i]));
            }
        }

        /** {@code fwd_cast} for doubles: each value times {@code ldexp(1, 62 - emax)}, truncated. */
        private void fwdCastDouble(int emax) {
            double s = Math.scalb(1.0, 62 - emax);
            for (int i = 0; i < size; i++) {
                iblock64[i] = toLong(s * Double.longBitsToDouble(raw[i]));
            }
        }

        /** {@code encode_block} for floats: the largest exponent, then the block's integers. */
        private void encodeFloat() {
            long bits = 1;
            int emax = emaxFloat();
            int prec = precision(emax);
            long e = prec != 0 ? emax + 127 : 0;
            if (e != 0) {
                bits += 8;
                out.writeBits(2 * e + 1, (int) bits);
                fwdCastFloat(emax);
                encodeInts32(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, prec);
            } else {
                out.writeBit(0);
                if (minbits > bits) {
                    out.pad(minbits - bits);
                }
            }
        }

        /** {@code encode_block} for doubles. */
        private void encodeDouble() {
            long bits = 1;
            int emax = emaxDouble();
            int prec = precision(emax);
            long e = prec != 0 ? emax + 1023 : 0;
            if (e != 0) {
                bits += 11;
                out.writeBits(2 * e + 1, (int) bits);
                fwdCastDouble(emax);
                encodeInts64(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, prec);
            } else {
                out.writeBit(0);
                if (minbits > bits) {
                    out.pad(minbits - bits);
                }
            }
        }

        /**
         * {@code rev_encode_block} for floats (revencodef.c): block-floating-point if it gives the values back
         * bit for bit, else the values' bits as sign-magnitude integers.
         */
        private void revEncodeFloat() {
            long bits = 0;
            int emax = emaxFloat();
            if (emax != -127) {
                fwdCastFloat(emax);
            } else {
                java.util.Arrays.fill(iblock32, 0);
            }
            if (reversibleFloat(emax)) {
                long e = emax + 127;
                if (e == 0) {
                    out.writeBit(0); // an all-zero block: one bit, not padded to minbits, as libzfp writes it
                    return;
                }
                bits += 2;
                out.writeBits(1, 2);
                bits += 8;
                out.writeBits(e, 8);
            } else {
                for (int i = 0; i < size; i++) {
                    int x = (int) raw[i];
                    iblock32[i] = x < 0 ? x ^ 0x7fffffff : x;
                }
                bits += 2;
                out.writeBits(3, 2);
            }
            revEncodeInts32(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, maxprec);
        }

        /** {@code rev_fwd_reversible} for floats: whether {@code inv_cast} gives every value back exactly. */
        private boolean reversibleFloat(int emax) {
            float s = emax != -127 ? (float) Math.scalb(1.0, emax - 30) : 0;
            for (int i = 0; i < size; i++) {
                float g = emax != -127 ? s * (float) iblock32[i] : 0;
                if (Float.floatToRawIntBits(g) != (int) raw[i]) {
                    return false;
                }
            }
            return true;
        }

        /** {@code rev_encode_block} for doubles. */
        private void revEncodeDouble() {
            long bits = 0;
            int emax = emaxDouble();
            if (emax != -1023) {
                fwdCastDouble(emax);
            } else {
                java.util.Arrays.fill(iblock64, 0);
            }
            if (reversibleDouble(emax)) {
                long e = emax + 1023;
                if (e == 0) {
                    out.writeBit(0);
                    return;
                }
                bits += 2;
                out.writeBits(1, 2);
                bits += 11;
                out.writeBits(e, 11);
            } else {
                for (int i = 0; i < size; i++) {
                    long x = raw[i];
                    iblock64[i] = x < 0 ? x ^ 0x7fffffffffffffffL : x;
                }
                bits += 2;
                out.writeBits(3, 2);
            }
            revEncodeInts64(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, maxprec);
        }

        private boolean reversibleDouble(int emax) {
            double s = emax != -1023 ? Math.scalb(1.0, emax - 62) : 0;
            for (int i = 0; i < size; i++) {
                double g = emax != -1023 ? s * (double) iblock64[i] : 0;
                if (Double.doubleToRawLongBits(g) != raw[i]) {
                    return false;
                }
            }
            return true;
        }

        // ------------------------------------------------------------------ 32-bit integers (encode.c)

        /** {@code encode_block} for 32-bit integers in {@code iblock32}: transform, reorder, bit planes. */
        private void encodeInts32(long minbits, long maxbits, int maxprec) {
            fwdTransform32(iblock32);
            for (int i = 0; i < size; i++) {
                ublock32[i] = (iblock32[perm[i]] + NBMASK32) ^ NBMASK32;
            }
            long bits = encodeUnsigned32(maxbits, maxprec);
            if (bits < minbits) {
                out.pad(minbits - bits);
            }
        }

        /** {@code rev_encode_block} for 32-bit integers (revencode.c): the precision, then the planes. */
        private void revEncodeInts32(long minbits, long maxbits, int maxprec) {
            long bits = 5;
            revFwdTransform32(iblock32);
            int m = 0;
            for (int i = 0; i < size; i++) {
                ublock32[i] = (iblock32[perm[i]] + NBMASK32) ^ NBMASK32;
                m |= ublock32[i];
            }
            int prec = m == 0 ? 0 : 32 - Integer.numberOfTrailingZeros(m); // rev_precision
            prec = Math.max(Math.min(prec, maxprec), 1);
            out.writeBits(prec - 1, 5);
            bits += encodeUnsigned32((maxbits - bits) & UINT_MASK, prec);
            if (bits < minbits) {
                out.pad(minbits - bits);
            }
        }

        /** {@code encode_ints}: the coefficients in {@code ublock32} as bit planes; returns the bits written. */
        private long encodeUnsigned32(long maxbits, int maxprec) {
            int[] data = ublock32;
            int kmin = 32 > maxprec ? 32 - maxprec : 0;
            if (withMaxbits(maxbits, maxprec)) {
                // rate constrained: partial bit planes (encode_few_ints, encode_many_ints)
                long bits = maxbits;
                int n = 0;
                for (int k = 32; bits != 0 && k-- > kmin;) {
                    int m = (int) Math.min(n, bits);
                    bits -= m;
                    if (size <= 64) {
                        long x = 0;
                        for (int i = 0; i < size; i++) {
                            x += (long) ((data[i] >>> k) & 1) << i;
                        }
                        x = out.writeBits(x, m);
                        for (; bits != 0 && n < size; x >>>= 1, n++) {
                            bits--;
                            if (out.writeBit(x != 0 ? 1 : 0) != 0) {
                                for (; bits != 0 && n < size - 1; x >>>= 1, n++) {
                                    bits--;
                                    if (out.writeBit((int) (x & 1)) != 0) {
                                        break;
                                    }
                                }
                            } else {
                                break;
                            }
                        }
                    } else {
                        for (int i = 0; i < m; i++) {
                            out.writeBit((data[i] >>> k) & 1);
                        }
                        long c = 0;
                        for (int i = m; i < size; i++) {
                            c += (data[i] >>> k) & 1;
                        }
                        for (; bits != 0 && n < size; n++) {
                            bits--;
                            if (out.writeBit(c != 0 ? 1 : 0) != 0) {
                                for (c--; bits != 0 && n < size - 1; n++) {
                                    bits--;
                                    if (out.writeBit((data[n] >>> k) & 1) != 0) {
                                        break;
                                    }
                                }
                            } else {
                                break;
                            }
                        }
                    }
                }
                return maxbits - bits;
            }
            // variable rate: whole bit planes (encode_few_ints_prec, encode_many_ints_prec)
            long start = out.position();
            int n = 0;
            for (int k = 32; k-- > kmin;) {
                if (size <= 64) {
                    long x = 0;
                    for (int i = 0; i < size; i++) {
                        x += (long) ((data[i] >>> k) & 1) << i;
                    }
                    x = out.writeBits(x, n);
                    for (; n < size && out.writeBit(x != 0 ? 1 : 0) != 0; x >>>= 1, n++) {
                        for (; n < size - 1 && out.writeBit((int) (x & 1)) == 0; x >>>= 1, n++) {
                            // scan for the one-bit
                        }
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        out.writeBit((data[i] >>> k) & 1);
                    }
                    long c = 0;
                    for (int i = n; i < size; i++) {
                        c += (data[i] >>> k) & 1;
                    }
                    for (; n < size && out.writeBit(c != 0 ? 1 : 0) != 0; n++) {
                        for (c--; n < size - 1 && out.writeBit((data[n] >>> k) & 1) == 0; n++) {
                            // scan for the one-bit
                        }
                    }
                }
            }
            return out.position() - start;
        }

        /** {@code fwd_xform}: the decorrelating transform, along x, y, z, then w. */
        private void fwdTransform32(int[] p) {
            for (int axis = 0; axis < dims; axis++) {
                int s = 1 << (2 * axis);
                for (int o = 0; o < size; o++) {
                    if ((o & (3 * s)) == 0) { // the first value of each 4-vector along this axis
                        fwdLift32(p, o, s);
                    }
                }
            }
        }

        /** {@code rev_fwd_xform}: the reversible transform, in the same axis order. */
        private void revFwdTransform32(int[] p) {
            for (int axis = 0; axis < dims; axis++) {
                int s = 1 << (2 * axis);
                for (int o = 0; o < size; o++) {
                    if ((o & (3 * s)) == 0) {
                        revFwdLift32(p, o, s);
                    }
                }
            }
        }

        // ------------------------------------------------------------------ 64-bit integers

        private void encodeInts64(long minbits, long maxbits, int maxprec) {
            fwdTransform64(iblock64);
            for (int i = 0; i < size; i++) {
                ublock64[i] = (iblock64[perm[i]] + NBMASK64) ^ NBMASK64;
            }
            long bits = encodeUnsigned64(maxbits, maxprec);
            if (bits < minbits) {
                out.pad(minbits - bits);
            }
        }

        private void revEncodeInts64(long minbits, long maxbits, int maxprec) {
            long bits = 6;
            revFwdTransform64(iblock64);
            long m = 0;
            for (int i = 0; i < size; i++) {
                ublock64[i] = (iblock64[perm[i]] + NBMASK64) ^ NBMASK64;
                m |= ublock64[i];
            }
            int prec = m == 0 ? 0 : 64 - Long.numberOfTrailingZeros(m);
            prec = Math.max(Math.min(prec, maxprec), 1);
            out.writeBits(prec - 1, 6);
            bits += encodeUnsigned64((maxbits - bits) & UINT_MASK, prec);
            if (bits < minbits) {
                out.pad(minbits - bits);
            }
        }

        /** {@code encode_ints} for 64-bit coefficients in {@code ublock64}. */
        private long encodeUnsigned64(long maxbits, int maxprec) {
            long[] data = ublock64;
            int kmin = 64 > maxprec ? 64 - maxprec : 0;
            if (withMaxbits(maxbits, maxprec)) {
                long bits = maxbits;
                int n = 0;
                for (int k = 64; bits != 0 && k-- > kmin;) {
                    int m = (int) Math.min(n, bits);
                    bits -= m;
                    if (size <= 64) {
                        long x = 0;
                        for (int i = 0; i < size; i++) {
                            x += ((data[i] >>> k) & 1) << i;
                        }
                        x = out.writeBits(x, m);
                        for (; bits != 0 && n < size; x >>>= 1, n++) {
                            bits--;
                            if (out.writeBit(x != 0 ? 1 : 0) != 0) {
                                for (; bits != 0 && n < size - 1; x >>>= 1, n++) {
                                    bits--;
                                    if (out.writeBit((int) (x & 1)) != 0) {
                                        break;
                                    }
                                }
                            } else {
                                break;
                            }
                        }
                    } else {
                        for (int i = 0; i < m; i++) {
                            out.writeBit((int) ((data[i] >>> k) & 1));
                        }
                        long c = 0;
                        for (int i = m; i < size; i++) {
                            c += (data[i] >>> k) & 1;
                        }
                        for (; bits != 0 && n < size; n++) {
                            bits--;
                            if (out.writeBit(c != 0 ? 1 : 0) != 0) {
                                for (c--; bits != 0 && n < size - 1; n++) {
                                    bits--;
                                    if (out.writeBit((int) ((data[n] >>> k) & 1)) != 0) {
                                        break;
                                    }
                                }
                            } else {
                                break;
                            }
                        }
                    }
                }
                return maxbits - bits;
            }
            long start = out.position();
            int n = 0;
            for (int k = 64; k-- > kmin;) {
                if (size <= 64) {
                    long x = 0;
                    for (int i = 0; i < size; i++) {
                        x += ((data[i] >>> k) & 1) << i;
                    }
                    x = out.writeBits(x, n);
                    for (; n < size && out.writeBit(x != 0 ? 1 : 0) != 0; x >>>= 1, n++) {
                        for (; n < size - 1 && out.writeBit((int) (x & 1)) == 0; x >>>= 1, n++) {
                            // scan for the one-bit
                        }
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        out.writeBit((int) ((data[i] >>> k) & 1));
                    }
                    long c = 0;
                    for (int i = n; i < size; i++) {
                        c += (data[i] >>> k) & 1;
                    }
                    for (; n < size && out.writeBit(c != 0 ? 1 : 0) != 0; n++) {
                        for (c--; n < size - 1 && out.writeBit((int) ((data[n] >>> k) & 1)) == 0; n++) {
                            // scan for the one-bit
                        }
                    }
                }
            }
            return out.position() - start;
        }

        private void fwdTransform64(long[] p) {
            for (int axis = 0; axis < dims; axis++) {
                int s = 1 << (2 * axis);
                for (int o = 0; o < size; o++) {
                    if ((o & (3 * s)) == 0) {
                        fwdLift64(p, o, s);
                    }
                }
            }
        }

        private void revFwdTransform64(long[] p) {
            for (int axis = 0; axis < dims; axis++) {
                int s = 1 << (2 * axis);
                for (int o = 0; o < size; o++) {
                    if ((o & (3 * s)) == 0) {
                        revFwdLift64(p, o, s);
                    }
                }
            }
        }

        /** True if a block's largest coding exceeds {@code maxbits} ({@code with_maxbits}, codec.c). */
        private boolean withMaxbits(long maxbits, int maxprec) {
            return (long) (maxprec + 1) * size - 1 > maxbits;
        }
    }

    /** {@code frexp}'s exponent of a positive finite value ({@code x = m * 2^e}, {@code 0.5 <= m < 1}). */
    static int frexp(double x) {
        if (x == Double.POSITIVE_INFINITY) {
            return -1; // left unspecified by C: the Microsoft C runtime gives -1 (glibc 0)
        }
        if (x < Double.MIN_NORMAL) {
            return Math.getExponent(x * 0x1p54) + 1 - 54; // subnormal: scaled into the normal range first
        }
        return Math.getExponent(x) + 1;
    }

    /** A float truncated to an int as x86-64 converts it: NaN or a value out of range is the minimum. */
    private static int toInt(float v) {
        return v >= -0x1p31f && v < 0x1p31f ? (int) v : Integer.MIN_VALUE;
    }

    /** A double truncated to a long as x86-64 converts it: NaN or a value out of range is the minimum. */
    private static long toLong(double v) {
        return v >= -0x1p63 && v < 0x1p63 ? (long) v : Long.MIN_VALUE;
    }

    /**
     * {@code fwd_lift}: zfp's non-orthogonal transform of a 4-vector, in two's complement arithmetic with
     * arithmetic shifts, as zfp computes it.
     */
    private static void fwdLift32(int[] p, int o, int s) {
        int x = p[o];
        int y = p[o + s];
        int z = p[o + 2 * s];
        int w = p[o + 3 * s];
        x += w;
        x >>= 1;
        w -= x;
        z += y;
        z >>= 1;
        y -= z;
        x += z;
        x >>= 1;
        z -= x;
        w += y;
        w >>= 1;
        y -= w;
        w += y >> 1;
        y -= w >> 1;
        p[o] = x;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }

    private static void fwdLift64(long[] p, int o, int s) {
        long x = p[o];
        long y = p[o + s];
        long z = p[o + 2 * s];
        long w = p[o + 3 * s];
        x += w;
        x >>= 1;
        w -= x;
        z += y;
        z >>= 1;
        y -= z;
        x += z;
        x >>= 1;
        z -= x;
        w += y;
        w >>= 1;
        y -= w;
        w += y >> 1;
        y -= w >> 1;
        p[o] = x;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }

    /** {@code rev_fwd_lift}: the high-order Lorenzo transform (a Pascal matrix). */
    private static void revFwdLift32(int[] p, int o, int s) {
        int x = p[o];
        int y = p[o + s];
        int z = p[o + 2 * s];
        int w = p[o + 3 * s];
        w -= z;
        z -= y;
        y -= x;
        w -= z;
        z -= y;
        w -= z;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }

    private static void revFwdLift64(long[] p, int o, int s) {
        long x = p[o];
        long y = p[o + s];
        long z = p[o + 2 * s];
        long w = p[o + 3 * s];
        w -= z;
        z -= y;
        y -= x;
        w -= z;
        z -= y;
        w -= z;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }
}
