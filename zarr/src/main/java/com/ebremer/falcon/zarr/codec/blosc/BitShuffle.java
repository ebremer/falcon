package com.ebremer.falcon.zarr.codec.blosc;

/**
 * Blosc's bit-shuffle filter (inverse only, for reading). Bit-shuffle groups the same bit position of
 * every element together before compression, which compresses better than the byte shuffle for some
 * data. Translated from the scalar reference in c-blosc's {@code bitshuffle-generic.c}.
 *
 * <p>c-blosc bit-shuffles a block only when its element count is a multiple of 8; otherwise the block is
 * stored unshuffled. The inverse mirrors that: whole groups of eight elements are un-transposed and any
 * trailing bytes are copied through (see {@code blosc_internal_bitunshuffle}).
 */
final class BitShuffle {

    private BitShuffle() {
    }

    /**
     * Undoes the bit shuffle on one block of {@code length} bytes holding {@code typeSize}-byte elements,
     * writing the original bytes to {@code dst}.
     */
    static void unshuffle(byte[] src, int srcOff, byte[] dst, int dstOff, int length, int typeSize) {
        int elements = length / typeSize;
        if (elements % 8 != 0) {
            System.arraycopy(src, srcOff, dst, dstOff, length); // not bit-shuffled by the encoder
            return;
        }
        int shuffled = elements * typeSize;
        byte[] tmp = new byte[shuffled];
        transByteBitRow(src, srcOff, tmp, elements, typeSize);
        shuffleBitEightElem(tmp, dst, dstOff, elements, typeSize);
        int leftover = length - shuffled;
        if (leftover > 0) {
            System.arraycopy(src, srcOff + shuffled, dst, dstOff + shuffled, leftover);
        }
    }

    /** Step 1 of the inverse: transpose byte rows back (bshuf_trans_byte_bitrow_scal). */
    private static void transByteBitRow(byte[] in, int inOff, byte[] out, int size, int elemSize) {
        int nbyteRow = size / 8;
        for (int jj = 0; jj < elemSize; jj++) {
            for (int ii = 0; ii < nbyteRow; ii++) {
                for (int kk = 0; kk < 8; kk++) {
                    out[ii * 8 * elemSize + jj * 8 + kk] = in[inOff + (jj * 8 + kk) * nbyteRow + ii];
                }
            }
        }
    }

    /** Step 2 of the inverse: shuffle bits within eight-element groups (little-endian branch). */
    private static void shuffleBitEightElem(byte[] in, byte[] out, int outOff, int size, int elemSize) {
        int nbyte = elemSize * size;
        for (int jj = 0; jj < 8 * elemSize; jj += 8) {
            for (int ii = 0; ii + 8 * elemSize - 1 < nbyte; ii += 8 * elemSize) {
                long x = readLe64(in, ii + jj);
                x = transposeBit8x8(x);
                for (int kk = 0; kk < 8; kk++) {
                    out[outOff + ii + jj / 8 + kk * elemSize] = (byte) x;
                    x >>>= 8;
                }
            }
        }
    }

    /** The 8x8 bit-matrix transpose used by both steps (TRANS_BIT_8X8). */
    private static long transposeBit8x8(long x) {
        long t;
        t = (x ^ (x >>> 7)) & 0x00AA00AA00AA00AAL;
        x = x ^ t ^ (t << 7);
        t = (x ^ (x >>> 14)) & 0x0000CCCC0000CCCCL;
        x = x ^ t ^ (t << 14);
        t = (x ^ (x >>> 28)) & 0x00000000F0F0F0F0L;
        x = x ^ t ^ (t << 28);
        return x;
    }

    private static long readLe64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (b[off + i] & 0xffL) << (8 * i);
        }
        return v;
    }
}
