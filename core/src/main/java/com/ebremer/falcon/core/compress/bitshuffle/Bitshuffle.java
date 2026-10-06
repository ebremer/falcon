package com.ebremer.falcon.core.compress.bitshuffle;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;

/**
 * Kiyoshi Masui's bitshuffle, translated from the scalar reference in {@code bitshuffle_core.c}: the
 * inverse for reading, and the forward transpose ({@link #transpose}) for Blosc's bit-shuffle filter on
 * write. Bitshuffle transposes a block of elements as a bit matrix, so that bit <i>k</i> of every element
 * is stored together; this compresses better than a byte shuffle for many numeric arrays. Blosc uses the
 * transpose on its blocks, and the bitshuffle library (HDF5 filter 32008) applies it to blocks of
 * elements on its own or followed by LZ4 or zstd:
 *
 * <ul>
 *   <li>{@link #unshuffle}: whole blocks of {@code blockSize} elements, then the remaining elements
 *       rounded down to a multiple of 8, then the last {@code n % 8} elements copied through
 *       ({@code bshuf_bitunshuffle});</li>
 *   <li>{@link #decompress}: the same blocks, each stored as {@code compressed size (4, big-endian) ·
 *       LZ4 block or zstd frame}, then the last {@code n % 8} elements raw ({@code bshuf_decompress_lz4},
 *       {@code bshuf_decompress_zstd}).</li>
 * </ul>
 */
public final class Bitshuffle {

    /** The compressor applied to each bit-shuffled block. */
    public enum BlockCodec {
        LZ4, ZSTD
    }

    private static final int BLOCKED_MULT = 8;        // BSHUF_BLOCKED_MULT
    private static final int TARGET_BLOCK_BYTES = 8192; // BSHUF_TARGET_BLOCK_SIZE_B
    private static final int MIN_RECOMMEND_BLOCK = 128; // BSHUF_MIN_RECOMMEND_BLOCK

    private Bitshuffle() {
    }

    /** The block size (in elements) bitshuffle picks when none is given ({@code bshuf_default_block_size}). */
    public static int defaultBlockSize(int elementSize) {
        int blockSize = TARGET_BLOCK_BYTES / elementSize / BLOCKED_MULT * BLOCKED_MULT;
        return Math.max(blockSize, MIN_RECOMMEND_BLOCK);
    }

    /**
     * Undoes {@code bshuf_bitshuffle} on {@code elements} elements of {@code elementSize} bytes.
     *
     * @param blockSize elements per block, a multiple of 8 (0 for {@link #defaultBlockSize})
     */
    public static byte[] unshuffle(byte[] src, int offset, int elements, int elementSize, int blockSize) {
        int block = checkedBlockSize(elementSize, blockSize);
        long bytes = (long) elements * elementSize;
        if (elements < 0 || offset < 0 || offset + bytes > src.length) {
            throw new CompressionFormatException("bitshuffled data of " + elements + " elements of " + elementSize
                    + " bytes overruns its " + (src.length - offset) + " bytes");
        }
        byte[] out = new byte[(int) bytes];
        int done = 0;
        while (done < elements) {
            int n = blockElements(elements - done, block);
            if (n == 0) {
                break;
            }
            untranspose(src, offset + done * elementSize, out, done * elementSize, n, elementSize);
            done += n;
        }
        System.arraycopy(src, offset + done * elementSize, out, done * elementSize, (elements - done) * elementSize);
        return out;
    }

    /**
     * Undoes {@code bshuf_compress_lz4} or {@code bshuf_compress_zstd}: decompresses each block, then
     * un-transposes it.
     *
     * @param blockSize elements per block, a multiple of 8 (0 for {@link #defaultBlockSize})
     */
    public static byte[] decompress(byte[] src, int offset, int length, int elements, int elementSize, int blockSize,
                                    BlockCodec codec) {
        int block = checkedBlockSize(elementSize, blockSize);
        long bytes = (long) elements * elementSize;
        if (elements < 0 || bytes > Integer.MAX_VALUE - 8) {
            throw new CompressionFormatException("bitshuffled data of " + elements + " elements is too large");
        }
        byte[] out = new byte[(int) bytes];
        byte[] decoded = new byte[block * elementSize];
        int in = offset;
        int end = offset + length;
        int done = 0;
        while (done < elements) {
            int n = blockElements(elements - done, block);
            if (n == 0) {
                break;
            }
            if (in + 4 > end) {
                throw new CompressionFormatException("bitshuffle block size is truncated");
            }
            long compressed = readBe32(src, in);
            in += 4;
            if (compressed > end - in) {
                throw new CompressionFormatException("bitshuffle block of " + compressed + " bytes overruns the input");
            }
            int blockBytes = n * elementSize;
            switch (codec) {
                case LZ4 -> Lz4.decompress(src, in, (int) compressed, decoded, 0, blockBytes);
                case ZSTD -> {
                    byte[] frame = ZstdDecoder.decompress(src, in, (int) compressed);
                    if (frame.length != blockBytes) {
                        throw new CompressionFormatException("bitshuffle zstd block holds " + frame.length
                                + " bytes, expected " + blockBytes);
                    }
                    System.arraycopy(frame, 0, decoded, 0, blockBytes);
                }
            }
            untranspose(decoded, 0, out, done * elementSize, n, elementSize);
            in += (int) compressed;
            done += n;
        }
        int leftover = (elements - done) * elementSize;
        if (leftover > end - in) {
            throw new CompressionFormatException("bitshuffle data is missing its last " + leftover + " bytes");
        }
        System.arraycopy(src, in, out, done * elementSize, leftover);
        return out;
    }

    /**
     * Un-transposes one block: {@code elements} (a multiple of 8) elements of {@code elementSize} bytes
     * ({@code bshuf_untrans_bit_elem}).
     */
    public static void untranspose(byte[] src, int srcOff, byte[] dst, int dstOff, int elements, int elementSize) {
        untranspose(src, srcOff, dst, dstOff, elements, elementSize, new byte[elements * elementSize]);
    }

    /**
     * Un-transposes one block as {@link #untranspose(byte[], int, byte[], int, int, int)} does, using
     * {@code tmp} (at least {@code elements * elementSize} bytes) as its scratch space, so a caller decoding
     * many blocks allocates it once.
     */
    public static void untranspose(byte[] src, int srcOff, byte[] dst, int dstOff, int elements, int elementSize,
                                   byte[] tmp) {
        if (elements % BLOCKED_MULT != 0) {
            throw new IllegalArgumentException("bitshuffle blocks hold a multiple of 8 elements, not " + elements);
        }
        transByteBitRow(src, srcOff, tmp, elements, elementSize);
        shuffleBitEightElem(tmp, dst, dstOff, elements, elementSize);
    }

    /**
     * Transposes one block, the inverse of {@link #untranspose}: {@code elements} (a multiple of 8) elements
     * of {@code elementSize} bytes ({@code bshuf_trans_bit_elem}). {@code dst} must not overlap {@code src};
     * {@code tmp} holds at least {@code elements * elementSize} bytes.
     */
    public static void transpose(byte[] src, int srcOff, byte[] dst, int dstOff, int elements, int elementSize,
                                 byte[] tmp) {
        if (elements % BLOCKED_MULT != 0) {
            throw new IllegalArgumentException("bitshuffle blocks hold a multiple of 8 elements, not " + elements);
        }
        int nbyte = elements * elementSize;
        // bshuf_trans_byte_elem: group byte k of every element (a byte shuffle), into dst for now.
        for (int ii = 0; ii < elements; ii++) {
            for (int jj = 0; jj < elementSize; jj++) {
                dst[dstOff + jj * elements + ii] = src[srcOff + ii * elementSize + jj];
            }
        }
        // bshuf_trans_bit_byte: transpose the bits of each run of 8 bytes, writing bit row k of every run
        // together.
        int bitRow = nbyte / 8;
        for (int ii = 0; ii < bitRow; ii++) {
            long x = transposeBit8x8(readLe64(dst, dstOff + ii * 8));
            for (int kk = 0; kk < 8; kk++) {
                tmp[kk * bitRow + ii] = (byte) x;
                x >>>= 8;
            }
        }
        // bshuf_trans_bitrow_eight: reorder the rows so the 8 bit rows of each byte position sit together.
        int row = elements / 8;
        for (int ii = 0; ii < 8; ii++) {
            for (int jj = 0; jj < elementSize; jj++) {
                System.arraycopy(tmp, (ii * elementSize + jj) * row, dst, dstOff + (jj * 8 + ii) * row, row);
            }
        }
    }

    /** The elements the next block holds: a whole block, else what remains rounded down to a multiple of 8. */
    private static int blockElements(int remaining, int blockSize) {
        return remaining >= blockSize ? blockSize : remaining - remaining % BLOCKED_MULT;
    }

    private static int checkedBlockSize(int elementSize, int blockSize) {
        if (elementSize < 1) {
            throw new CompressionFormatException("bitshuffle element size " + elementSize);
        }
        int block = blockSize == 0 ? defaultBlockSize(elementSize) : blockSize;
        if (block < 0 || block % BLOCKED_MULT != 0 || (long) block * elementSize > Integer.MAX_VALUE - 8) {
            throw new CompressionFormatException("bitshuffle block size " + blockSize + " is not a multiple of 8");
        }
        return block;
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

    private static long readBe32(byte[] b, int off) {
        return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16) | ((b[off + 2] & 0xffL) << 8) | (b[off + 3] & 0xffL);
    }
}
