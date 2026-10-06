package com.ebremer.falcon.core.compress.zfp;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * zfp decompression: a port of zfp 1.0.1's decoder (Peter Lindstrom, LLNL; {@code src/template/decode*.c},
 * {@code revdecode*.c}, {@code decompress.c}), every value bit for bit libzfp's.
 *
 * <p>A field of 1 to 4 dimensions is coded in blocks of 4<sup>d</sup> values, {@code x} varying fastest,
 * partial blocks at the far edges (only their values inside the field are kept). Each block is coded as:
 * <ul>
 *   <li>floating point: a bit for "not all zero", then the largest exponent ({@code emax}, 8 or 11 bits), the
 *       values being integers scaled by 2<sup>emax &minus; 30</sup> (or &minus; 62);</li>
 *   <li>integers whose decorrelating transform (a non-orthogonal lifting of each 4-vector along each axis) is
 *       inverted, after they are reordered by sequency and mapped back from negabinary;</li>
 *   <li>those coefficients as bit planes, most significant first, each plane's first bits verbatim, then
 *       group tests: a 1 announces another one-bit further on, its position coded in unary.</li>
 * </ul>
 * A rate-limited mode stops after {@code maxbits} bits; a block of fewer than {@code minbits} is padded. The
 * reversible (lossless) mode instead codes the bit planes' count, uses a Pascal-matrix (Lorenzo) transform,
 * and either reinterprets floating-point values as sign-magnitude integers or, if exact, scales them.
 *
 * <p>libzfp is built here as with its default {@code ZFP_ROUNDING_MODE} (never round), as hdf5plugin builds
 * it. The decoded field is returned as little-endian bytes, {@code x} fastest.
 */
public final class ZfpDecoder {

    private static final long UINT_MASK = 0xffffffffL;
    private static final int NBMASK32 = 0xaaaaaaaa;
    private static final long NBMASK64 = 0xaaaaaaaaaaaaaaaaL;
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    // Coefficient order by sequency (codec1.c to codec4.c): (i, j, ...) by i + j + ..., then i^2 + j^2 + ...
    private static final int[] PERM_1 = {0, 1, 2, 3};
    private static final int[] PERM_2 = {0, 1, 4, 5, 2, 8, 6, 9, 3, 12, 10, 7, 13, 11, 14, 15};
    private static final int[] PERM_3 = {
        0, 1, 4, 16, 20, 17, 5, 2, 8, 32, 21, 6, 18, 24, 9, 33,
        36, 3, 12, 48, 22, 25, 37, 40, 34, 10, 7, 19, 28, 13, 49, 52,
        41, 38, 26, 23, 29, 53, 11, 35, 44, 14, 50, 56, 42, 27, 39, 45,
        30, 54, 57, 60, 51, 15, 43, 46, 58, 61, 55, 31, 62, 59, 47, 63,
    };
    private static final int[] PERM_4 = {
        0, 1, 4, 16, 64, 5, 80, 17, 68, 65, 20, 2, 8, 32, 128, 84,
        81, 69, 21, 6, 18, 66, 24, 72, 9, 96, 33, 36, 129, 132, 144, 3,
        12, 48, 192, 85, 82, 70, 22, 73, 25, 88, 37, 100, 97, 148, 145, 133,
        10, 160, 34, 136, 130, 40, 7, 19, 67, 28, 76, 13, 112, 49, 52, 193,
        196, 208, 86, 89, 101, 149, 161, 137, 41, 134, 38, 164, 26, 152, 146, 104,
        98, 74, 83, 71, 23, 77, 29, 92, 53, 116, 113, 212, 209, 197, 11, 35,
        131, 44, 140, 14, 176, 50, 56, 194, 200, 224, 90, 165, 102, 153, 150, 105,
        168, 162, 138, 42, 87, 93, 117, 213, 27, 75, 99, 39, 135, 147, 108, 45,
        141, 156, 30, 78, 177, 180, 54, 114, 120, 57, 198, 210, 216, 201, 225, 228,
        15, 240, 51, 204, 195, 60, 169, 166, 154, 106, 91, 103, 151, 109, 157, 94,
        181, 118, 121, 214, 217, 229, 163, 139, 43, 142, 46, 172, 58, 184, 178, 232,
        226, 202, 241, 205, 61, 199, 55, 244, 31, 220, 211, 124, 115, 79, 170, 167,
        155, 107, 158, 110, 173, 122, 185, 182, 233, 230, 218, 95, 245, 119, 221, 215,
        125, 242, 206, 62, 203, 59, 248, 47, 236, 227, 188, 179, 143, 171, 174, 186,
        234, 246, 222, 126, 219, 123, 249, 111, 237, 231, 189, 183, 159, 252, 243, 207,
        63, 175, 250, 187, 238, 235, 190, 253, 247, 223, 127, 254, 251, 239, 191, 255,
    };

    private ZfpDecoder() {
    }

    /**
     * Decompresses a bare zfp stream (as H5Z-ZFP stores each chunk) of the field and mode {@code header}
     * describes.
     *
     * @param header   the field and parameters, from the stream's header kept elsewhere
     * @param src      the compressed bytes
     * @param offset   where the stream starts
     * @param length   its length in bytes
     * @param maxBytes the most bytes the caller accepts
     * @return the field's values, little-endian, {@code x} varying fastest
     * @throws CompressionFormatException if the stream is truncated, or the field is larger than {@code maxBytes}
     */
    public static byte[] decompress(ZfpHeader header, byte[] src, int offset, int length, long maxBytes) {
        java.util.Objects.requireNonNull(header, "header");
        checkRange(src, offset, length);
        return decode(header, new ZfpBitReader(src, offset, length), maxBytes);
    }

    /**
     * Decompresses a zfp stream that starts with its full header, the compressed field following it bit for bit
     * (as {@code zfp_write_header} then {@code zfp_compress} write it into one stream, numcodecs' zfpy codec
     * among them).
     *
     * @param src      the compressed bytes
     * @param offset   where the stream starts
     * @param length   its length in bytes
     * @param maxBytes the most bytes the caller accepts
     * @return the field's values, little-endian, {@code x} varying fastest
     * @throws CompressionFormatException      if the header or stream is malformed or truncated, or the field is
     *                                         larger than {@code maxBytes}
     * @throws UnsupportedCompressionException if the stream is of another zfp codec version
     */
    public static byte[] decompress(byte[] src, int offset, int length, long maxBytes) {
        checkRange(src, offset, length);
        ZfpBitReader in = new ZfpBitReader(src, offset, length);
        return decode(ZfpHeader.read(in), in, maxBytes);
    }

    static void checkRange(byte[] src, int offset, int length) {
        if (offset < 0 || length < 0 || length > src.length - offset) {
            throw new IllegalArgumentException("invalid range " + offset + "+" + length + " of " + src.length);
        }
    }

    private static byte[] decode(ZfpHeader header, ZfpBitReader in, long maxBytes) {
        long limit = Math.min(maxBytes, Integer.MAX_VALUE - 8);
        long bytes = header.elements() * header.type().size();
        if (bytes > limit) {
            throw new CompressionFormatException("zfp field of " + bytes + " bytes is larger than its " + limit);
        }
        byte[] out = new byte[(int) bytes];
        new Field(header, in, out).decode();
        return out;
    }

    /** One field's decoding state: the stream, its parameters, and scratch blocks. */
    private static final class Field {

        private final ZfpBitReader in;
        private final ZfpHeader.Type type;
        private final int dims;
        private final int size;
        private final int[] perm;
        private final long minbits;
        private final long maxbits;
        private final int maxprec;
        private final int minexp;
        private final boolean reversible;
        private final byte[] out;
        private final int nx;
        private final int ny;
        private final int nz;
        private final int nw;
        private final int[] ublock32;
        private final int[] iblock32;
        private final long[] ublock64;
        private final long[] iblock64;

        Field(ZfpHeader header, ZfpBitReader in, byte[] out) {
            this.in = in;
            this.type = header.type();
            this.dims = header.dimensions();
            this.size = 1 << (2 * dims);
            this.perm = switch (dims) {
                case 1 -> PERM_1;
                case 2 -> PERM_2;
                case 3 -> PERM_3;
                default -> PERM_4;
            };
            this.minbits = header.minbits() & UINT_MASK;
            this.maxbits = header.maxbits() & UINT_MASK;
            this.maxprec = header.maxprec();
            this.minexp = header.minexp();
            this.reversible = header.reversible();
            this.out = out;
            // the field fits in an array, so each size fits in an int
            this.nx = (int) header.nx();
            this.ny = (int) Math.max(header.ny(), 1);
            this.nz = (int) Math.max(header.nz(), 1);
            this.nw = (int) Math.max(header.nw(), 1);
            boolean wide = type.size() == 8;
            this.ublock32 = wide ? null : new int[size];
            this.iblock32 = wide ? null : new int[size];
            this.ublock64 = wide ? new long[size] : null;
            this.iblock64 = wide ? new long[size] : null;
        }

        /** Decodes every block in order (w, z, y, x outermost to innermost) and scatters it ({@code decompress.c}). */
        void decode() {
            int step = 4;
            int stepY = dims >= 2 ? 4 : 1;
            int stepZ = dims >= 3 ? 4 : 1;
            int stepW = dims >= 4 ? 4 : 1;
            for (int w = 0; w < nw; w += stepW) {
                for (int z = 0; z < nz; z += stepZ) {
                    for (int y = 0; y < ny; y += stepY) {
                        for (int x = 0; x < nx; x += step) {
                            decodeBlock();
                            scatter(x, y, z, w, Math.min(4, nx - x), Math.min(stepY, ny - y), Math.min(stepZ, nz - z),
                                    Math.min(stepW, nw - w));
                        }
                    }
                }
            }
        }

        private void decodeBlock() {
            switch (type) {
                case INT32 -> {
                    if (reversible) {
                        revDecodeInts32(minbits, maxbits, iblock32);
                    } else {
                        decodeInts32(minbits, maxbits, maxprec, iblock32);
                    }
                }
                case INT64 -> {
                    if (reversible) {
                        revDecodeInts64(minbits, maxbits, iblock64);
                    } else {
                        decodeInts64(minbits, maxbits, maxprec, iblock64);
                    }
                }
                case FLOAT -> {
                    if (reversible) {
                        revDecodeFloat();
                    } else {
                        decodeFloat();
                    }
                }
                case DOUBLE -> {
                    if (reversible) {
                        revDecodeDouble();
                    } else {
                        decodeDouble();
                    }
                }
            }
        }

        /** Copies the block's values inside the field to the output ({@code scatter_partial}). */
        private void scatter(int x0, int y0, int z0, int w0, int ex, int ey, int ez, int ew) {
            boolean wide = type.size() == 8;
            for (int w = 0; w < ew; w++) {
                for (int z = 0; z < ez; z++) {
                    for (int y = 0; y < ey; y++) {
                        long row = x0 + (long) nx * ((y0 + y) + (long) ny * ((z0 + z) + (long) nz * (w0 + w)));
                        int q = 4 * y + 16 * z + 64 * w;
                        for (int x = 0; x < ex; x++) {
                            int at = (int) (row + x);
                            if (wide) {
                                LONG_LE.set(out, at * 8, iblock64[q + x]);
                            } else {
                                INT_LE.set(out, at * 4, iblock32[q + x]);
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------ floating point (decodef.c)

        /** The bit planes to decode for a block of largest exponent {@code emax} ({@code precision}, codecf.c). */
        private int precision(int emax) {
            return (int) Math.min(maxprec, Math.max(0L, (long) emax - minexp + 2L * dims + 2));
        }

        /** {@code decode_block} for floats: block-floating-point, values left in {@code iblock32} as raw bits. */
        private void decodeFloat() {
            long bits = 1;
            if (in.readBit() != 0) {
                bits += 8;
                int emax = (int) in.readBits(8) - 127;
                decodeInts32(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, precision(emax), iblock32);
                invCastFloat(emax);
            } else {
                Arrays.fill(iblock32, 0);
                padTo(bits);
            }
        }

        /** {@code decode_block} for doubles, values left in {@code iblock64} as raw bits. */
        private void decodeDouble() {
            long bits = 1;
            if (in.readBit() != 0) {
                bits += 11;
                int emax = (int) in.readBits(11) - 1023;
                decodeInts64(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, precision(emax), iblock64);
                invCastDouble(emax);
            } else {
                Arrays.fill(iblock64, 0);
                padTo(bits);
            }
        }

        /** {@code rev_decode_block} for floats (revdecodef.c). */
        private void revDecodeFloat() {
            long bits = 1;
            if (in.readBit() != 0) {
                bits++;
                if (in.readBit() != 0) {
                    revDecodeInts32(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, iblock32);
                    // two's complement back to sign-magnitude, which is the float's bits
                    for (int i = 0; i < size; i++) {
                        if (iblock32[i] < 0) {
                            iblock32[i] ^= 0x7fffffff;
                        }
                    }
                } else {
                    bits += 8;
                    int emax = (int) in.readBits(8) - 127;
                    revDecodeInts32(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, iblock32);
                    if (emax != -127) {
                        invCastFloat(emax);
                    } else {
                        Arrays.fill(iblock32, 0);
                    }
                }
            } else {
                Arrays.fill(iblock32, 0);
                padTo(bits);
            }
        }

        /** {@code rev_decode_block} for doubles. */
        private void revDecodeDouble() {
            long bits = 1;
            if (in.readBit() != 0) {
                bits++;
                if (in.readBit() != 0) {
                    revDecodeInts64(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, iblock64);
                    for (int i = 0; i < size; i++) {
                        if (iblock64[i] < 0) {
                            iblock64[i] ^= 0x7fffffffffffffffL;
                        }
                    }
                } else {
                    bits += 11;
                    int emax = (int) in.readBits(11) - 1023;
                    revDecodeInts64(minbits - Math.min(bits, minbits), (maxbits - bits) & UINT_MASK, iblock64);
                    if (emax != -1023) {
                        invCastDouble(emax);
                    } else {
                        Arrays.fill(iblock64, 0);
                    }
                }
            } else {
                Arrays.fill(iblock64, 0);
                padTo(bits);
            }
        }

        /** An all-zero block takes at least {@code minbits} bits. */
        private void padTo(long bits) {
            if (minbits > bits) {
                in.skip(minbits - bits);
            }
        }

        /**
         * {@code inv_cast}: each value is {@code s * (float) i}, {@code s = ldexpf(1, emax - 30)}, in single
         * precision as C evaluates it (the integer rounded to a float, then the product rounded).
         */
        private void invCastFloat(int emax) {
            float s = (float) Math.scalb(1.0, emax - 30); // exact in double, rounded once, as ldexpf rounds
            for (int i = 0; i < size; i++) {
                iblock32[i] = Float.floatToRawIntBits(s * (float) iblock32[i]);
            }
        }

        /** {@code inv_cast} for doubles: {@code ldexp(1, emax - 62) * (double) i}. */
        private void invCastDouble(int emax) {
            double s = Math.scalb(1.0, emax - 62);
            for (int i = 0; i < size; i++) {
                iblock64[i] = Double.doubleToRawLongBits(s * (double) iblock64[i]);
            }
        }

        // ------------------------------------------------------------------ 32-bit integers (decode.c)

        /** {@code decode_block} for 32-bit integers into {@code iblock}. */
        private long decodeInts32(long minbits, long maxbits, int maxprec, int[] iblock) {
            long bits = decodeUnsigned32(maxbits, maxprec);
            if (bits < minbits) {
                in.skip(minbits - bits);
                bits = minbits;
            }
            for (int i = 0; i < size; i++) {
                iblock[perm[i]] = (ublock32[i] ^ NBMASK32) - NBMASK32;
            }
            invTransform32(iblock);
            return bits;
        }

        /** {@code rev_decode_block} for 32-bit integers (revdecode.c): the precision, then the planes. */
        private long revDecodeInts32(long minbits, long maxbits, int[] iblock) {
            long bits = 5;
            int prec = (int) in.readBits(5) + 1;
            bits += decodeUnsigned32((maxbits - bits) & UINT_MASK, prec);
            if (bits < minbits) {
                in.skip(minbits - bits);
                bits = minbits;
            }
            for (int i = 0; i < size; i++) {
                iblock[perm[i]] = (ublock32[i] ^ NBMASK32) - NBMASK32;
            }
            revInvTransform32(iblock);
            return bits;
        }

        /** {@code decode_ints}: the coefficients' bit planes into {@code ublock32}; returns the bits read. */
        private long decodeUnsigned32(long maxbits, int maxprec) {
            int[] data = ublock32;
            Arrays.fill(data, 0);
            int kmin = 32 > maxprec ? 32 - maxprec : 0;
            if (withMaxbits(maxbits, maxprec)) {
                // rate constrained: partial bit planes (decode_few_ints, decode_many_ints)
                long bits = maxbits;
                int k = 32;
                int n = 0;
                while (bits != 0 && k-- > kmin) {
                    int m = (int) Math.min(n, bits);
                    bits -= m;
                    if (size <= 64) {
                        long x = in.readBits(m);
                        for (; bits != 0 && n < size; n++) {
                            bits--;
                            if (in.readBit() != 0) {
                                for (; bits != 0 && n < size - 1; n++) {
                                    bits--;
                                    if (in.readBit() != 0) {
                                        break;
                                    }
                                }
                                x += 1L << n;
                            } else {
                                break;
                            }
                        }
                        for (int i = 0; x != 0; i++, x >>>= 1) {
                            data[i] += (int) (x & 1) << k;
                        }
                    } else {
                        for (int i = 0; i < m; i++) {
                            if (in.readBit() != 0) {
                                data[i] += 1 << k;
                            }
                        }
                        for (; bits != 0 && n < size; n++) {
                            bits--;
                            if (in.readBit() != 0) {
                                for (; bits != 0 && n < size - 1; n++) {
                                    bits--;
                                    if (in.readBit() != 0) {
                                        break;
                                    }
                                }
                                data[n] += 1 << k;
                            } else {
                                break;
                            }
                        }
                    }
                }
                return maxbits - bits;
            }
            // variable rate: whole bit planes (decode_few_ints_prec, decode_many_ints_prec)
            long start = in.position();
            int n = 0;
            for (int k = 32; k-- > kmin;) {
                if (size <= 64) {
                    long x = in.readBits(n);
                    while (n < size && in.readBit() != 0) {
                        while (n < size - 1 && in.readBit() == 0) {
                            n++;
                        }
                        x += 1L << n;
                        n++;
                    }
                    for (int i = 0; x != 0; i++, x >>>= 1) {
                        data[i] += (int) (x & 1) << k;
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        if (in.readBit() != 0) {
                            data[i] += 1 << k;
                        }
                    }
                    while (n < size && in.readBit() != 0) {
                        while (n < size - 1 && in.readBit() == 0) {
                            n++;
                        }
                        data[n] += 1 << k;
                        n++;
                    }
                }
            }
            return in.position() - start;
        }

        /** {@code inv_xform}: the inverse decorrelating transform, along w, z, y, then x. */
        private void invTransform32(int[] p) {
            switch (dims) {
                case 1 -> invLift32(p, 0, 1);
                case 2 -> {
                    for (int x = 0; x < 4; x++) {
                        invLift32(p, x, 4);
                    }
                    for (int y = 0; y < 4; y++) {
                        invLift32(p, 4 * y, 1);
                    }
                }
                case 3 -> {
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            invLift32(p, x + 4 * y, 16);
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            invLift32(p, 16 * z + x, 4);
                        }
                    }
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            invLift32(p, 4 * y + 16 * z, 1);
                        }
                    }
                }
                default -> {
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            for (int x = 0; x < 4; x++) {
                                invLift32(p, x + 4 * y + 16 * z, 64);
                            }
                        }
                    }
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            for (int w = 0; w < 4; w++) {
                                invLift32(p, 64 * w + x + 4 * y, 16);
                            }
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int w = 0; w < 4; w++) {
                            for (int z = 0; z < 4; z++) {
                                invLift32(p, 16 * z + 64 * w + x, 4);
                            }
                        }
                    }
                    for (int w = 0; w < 4; w++) {
                        for (int z = 0; z < 4; z++) {
                            for (int y = 0; y < 4; y++) {
                                invLift32(p, 4 * y + 16 * z + 64 * w, 1);
                            }
                        }
                    }
                }
            }
        }

        /** {@code rev_inv_xform}: the reversible transform, in the same axis order. */
        private void revInvTransform32(int[] p) {
            switch (dims) {
                case 1 -> revInvLift32(p, 0, 1);
                case 2 -> {
                    for (int x = 0; x < 4; x++) {
                        revInvLift32(p, x, 4);
                    }
                    for (int y = 0; y < 4; y++) {
                        revInvLift32(p, 4 * y, 1);
                    }
                }
                case 3 -> {
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            revInvLift32(p, x + 4 * y, 16);
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            revInvLift32(p, 16 * z + x, 4);
                        }
                    }
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            revInvLift32(p, 4 * y + 16 * z, 1);
                        }
                    }
                }
                default -> {
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            for (int x = 0; x < 4; x++) {
                                revInvLift32(p, x + 4 * y + 16 * z, 64);
                            }
                        }
                    }
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            for (int w = 0; w < 4; w++) {
                                revInvLift32(p, 64 * w + x + 4 * y, 16);
                            }
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int w = 0; w < 4; w++) {
                            for (int z = 0; z < 4; z++) {
                                revInvLift32(p, 16 * z + 64 * w + x, 4);
                            }
                        }
                    }
                    for (int w = 0; w < 4; w++) {
                        for (int z = 0; z < 4; z++) {
                            for (int y = 0; y < 4; y++) {
                                revInvLift32(p, 4 * y + 16 * z + 64 * w, 1);
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------------ 64-bit integers

        /** {@code decode_block} for 64-bit integers into {@code iblock}. */
        private long decodeInts64(long minbits, long maxbits, int maxprec, long[] iblock) {
            long bits = decodeUnsigned64(maxbits, maxprec);
            if (bits < minbits) {
                in.skip(minbits - bits);
                bits = minbits;
            }
            for (int i = 0; i < size; i++) {
                iblock[perm[i]] = (ublock64[i] ^ NBMASK64) - NBMASK64;
            }
            invTransform64(iblock);
            return bits;
        }

        /** {@code rev_decode_block} for 64-bit integers. */
        private long revDecodeInts64(long minbits, long maxbits, long[] iblock) {
            long bits = 6;
            int prec = (int) in.readBits(6) + 1;
            bits += decodeUnsigned64((maxbits - bits) & UINT_MASK, prec);
            if (bits < minbits) {
                in.skip(minbits - bits);
                bits = minbits;
            }
            for (int i = 0; i < size; i++) {
                iblock[perm[i]] = (ublock64[i] ^ NBMASK64) - NBMASK64;
            }
            revInvTransform64(iblock);
            return bits;
        }

        /** {@code decode_ints} for 64-bit coefficients into {@code ublock64}. */
        private long decodeUnsigned64(long maxbits, int maxprec) {
            long[] data = ublock64;
            Arrays.fill(data, 0);
            int kmin = 64 > maxprec ? 64 - maxprec : 0;
            if (withMaxbits(maxbits, maxprec)) {
                long bits = maxbits;
                int k = 64;
                int n = 0;
                while (bits != 0 && k-- > kmin) {
                    int m = (int) Math.min(n, bits);
                    bits -= m;
                    if (size <= 64) {
                        long x = in.readBits(m);
                        for (; bits != 0 && n < size; n++) {
                            bits--;
                            if (in.readBit() != 0) {
                                for (; bits != 0 && n < size - 1; n++) {
                                    bits--;
                                    if (in.readBit() != 0) {
                                        break;
                                    }
                                }
                                x += 1L << n;
                            } else {
                                break;
                            }
                        }
                        for (int i = 0; x != 0; i++, x >>>= 1) {
                            data[i] += (x & 1) << k;
                        }
                    } else {
                        for (int i = 0; i < m; i++) {
                            if (in.readBit() != 0) {
                                data[i] += 1L << k;
                            }
                        }
                        for (; bits != 0 && n < size; n++) {
                            bits--;
                            if (in.readBit() != 0) {
                                for (; bits != 0 && n < size - 1; n++) {
                                    bits--;
                                    if (in.readBit() != 0) {
                                        break;
                                    }
                                }
                                data[n] += 1L << k;
                            } else {
                                break;
                            }
                        }
                    }
                }
                return maxbits - bits;
            }
            long start = in.position();
            int n = 0;
            for (int k = 64; k-- > kmin;) {
                if (size <= 64) {
                    long x = in.readBits(n);
                    while (n < size && in.readBit() != 0) {
                        while (n < size - 1 && in.readBit() == 0) {
                            n++;
                        }
                        x += 1L << n;
                        n++;
                    }
                    for (int i = 0; x != 0; i++, x >>>= 1) {
                        data[i] += (x & 1) << k;
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        if (in.readBit() != 0) {
                            data[i] += 1L << k;
                        }
                    }
                    while (n < size && in.readBit() != 0) {
                        while (n < size - 1 && in.readBit() == 0) {
                            n++;
                        }
                        data[n] += 1L << k;
                        n++;
                    }
                }
            }
            return in.position() - start;
        }

        private void invTransform64(long[] p) {
            switch (dims) {
                case 1 -> invLift64(p, 0, 1);
                case 2 -> {
                    for (int x = 0; x < 4; x++) {
                        invLift64(p, x, 4);
                    }
                    for (int y = 0; y < 4; y++) {
                        invLift64(p, 4 * y, 1);
                    }
                }
                case 3 -> {
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            invLift64(p, x + 4 * y, 16);
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            invLift64(p, 16 * z + x, 4);
                        }
                    }
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            invLift64(p, 4 * y + 16 * z, 1);
                        }
                    }
                }
                default -> {
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            for (int x = 0; x < 4; x++) {
                                invLift64(p, x + 4 * y + 16 * z, 64);
                            }
                        }
                    }
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            for (int w = 0; w < 4; w++) {
                                invLift64(p, 64 * w + x + 4 * y, 16);
                            }
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int w = 0; w < 4; w++) {
                            for (int z = 0; z < 4; z++) {
                                invLift64(p, 16 * z + 64 * w + x, 4);
                            }
                        }
                    }
                    for (int w = 0; w < 4; w++) {
                        for (int z = 0; z < 4; z++) {
                            for (int y = 0; y < 4; y++) {
                                invLift64(p, 4 * y + 16 * z + 64 * w, 1);
                            }
                        }
                    }
                }
            }
        }

        private void revInvTransform64(long[] p) {
            switch (dims) {
                case 1 -> revInvLift64(p, 0, 1);
                case 2 -> {
                    for (int x = 0; x < 4; x++) {
                        revInvLift64(p, x, 4);
                    }
                    for (int y = 0; y < 4; y++) {
                        revInvLift64(p, 4 * y, 1);
                    }
                }
                case 3 -> {
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            revInvLift64(p, x + 4 * y, 16);
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            revInvLift64(p, 16 * z + x, 4);
                        }
                    }
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            revInvLift64(p, 4 * y + 16 * z, 1);
                        }
                    }
                }
                default -> {
                    for (int z = 0; z < 4; z++) {
                        for (int y = 0; y < 4; y++) {
                            for (int x = 0; x < 4; x++) {
                                revInvLift64(p, x + 4 * y + 16 * z, 64);
                            }
                        }
                    }
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 4; x++) {
                            for (int w = 0; w < 4; w++) {
                                revInvLift64(p, 64 * w + x + 4 * y, 16);
                            }
                        }
                    }
                    for (int x = 0; x < 4; x++) {
                        for (int w = 0; w < 4; w++) {
                            for (int z = 0; z < 4; z++) {
                                revInvLift64(p, 16 * z + 64 * w + x, 4);
                            }
                        }
                    }
                    for (int w = 0; w < 4; w++) {
                        for (int z = 0; z < 4; z++) {
                            for (int y = 0; y < 4; y++) {
                                revInvLift64(p, 4 * y + 16 * z + 64 * w, 1);
                            }
                        }
                    }
                }
            }
        }

        /** True if a block's largest coding exceeds {@code maxbits} ({@code with_maxbits}, codec.c). */
        private boolean withMaxbits(long maxbits, int maxprec) {
            return (long) (maxprec + 1) * size - 1 > maxbits;
        }
    }

    /**
     * {@code inv_lift}: the inverse of zfp's non-orthogonal transform of a 4-vector, in two's complement
     * arithmetic with arithmetic shifts, as zfp computes it.
     */
    private static void invLift32(int[] p, int o, int s) {
        int x = p[o];
        int y = p[o + s];
        int z = p[o + 2 * s];
        int w = p[o + 3 * s];
        y += w >> 1;
        w -= y >> 1;
        y += w;
        w <<= 1;
        w -= y;
        z += x;
        x <<= 1;
        x -= z;
        y += z;
        z <<= 1;
        z -= y;
        w += x;
        x <<= 1;
        x -= w;
        p[o] = x;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }

    private static void invLift64(long[] p, int o, int s) {
        long x = p[o];
        long y = p[o + s];
        long z = p[o + 2 * s];
        long w = p[o + 3 * s];
        y += w >> 1;
        w -= y >> 1;
        y += w;
        w <<= 1;
        w -= y;
        z += x;
        x <<= 1;
        x -= z;
        y += z;
        z <<= 1;
        z -= y;
        w += x;
        x <<= 1;
        x -= w;
        p[o] = x;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }

    /** {@code rev_inv_lift}: the inverse of the high-order Lorenzo transform (a Pascal matrix). */
    private static void revInvLift32(int[] p, int o, int s) {
        int x = p[o];
        int y = p[o + s];
        int z = p[o + 2 * s];
        int w = p[o + 3 * s];
        w += z;
        z += y;
        w += z;
        y += x;
        z += y;
        w += z;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }

    private static void revInvLift64(long[] p, int o, int s) {
        long x = p[o];
        long y = p[o + s];
        long z = p[o + 2 * s];
        long w = p[o + 3 * s];
        w += z;
        z += y;
        w += z;
        y += x;
        z += y;
        w += z;
        p[o + s] = y;
        p[o + 2 * s] = z;
        p[o + 3 * s] = w;
    }
}
