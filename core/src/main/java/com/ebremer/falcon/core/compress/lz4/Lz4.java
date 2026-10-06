package com.ebremer.falcon.core.compress.lz4;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * LZ4 <em>block</em> decompression (the bare block format, not the framed one), which is what Blosc
 * stores for its {@code lz4} and {@code lz4hc} compressors &mdash; both produce identical block data.
 *
 * <p>A block is a run of sequences. Each begins with a token byte whose high nibble is the literal
 * length and low nibble the match length minus 4; a nibble of 15 means the length continues in
 * following bytes, each adding its value until one is not 255. Literals are copied verbatim, then a
 * 2-byte little-endian offset selects a match earlier in the output. The final sequence carries literals
 * only.
 */
public final class Lz4 {

    private static final int MIN_MATCH = 4;

    private Lz4() {
    }

    /**
     * Decompresses one block into {@code dst}, which must be exactly the decompressed size.
     *
     * @throws CompressionFormatException if the block is malformed or would overrun either buffer
     */
    public static void decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstLen) {
        int in = srcOff;
        int inEnd = srcOff + srcLen;
        int out = dstOff;
        int outEnd = dstOff + dstLen;

        while (in < inEnd) {
            int token = src[in++] & 0xff;

            int literalLength = token >>> 4;
            if (literalLength == 15) {
                long continued = readLength(src, in, inEnd, outEnd - out - 15);
                literalLength += (int) continued;
                in = (int) (continued >>> 32);
            }
            if (literalLength > inEnd - in || literalLength > outEnd - out) {
                throw new CompressionFormatException("LZ4 literal run overruns the block");
            }
            System.arraycopy(src, in, dst, out, literalLength);
            in += literalLength;
            out += literalLength;

            if (in >= inEnd) {
                break; // the last sequence is literals only
            }
            if (in + 2 > inEnd) {
                throw new CompressionFormatException("LZ4 match offset is truncated");
            }
            int offset = (src[in] & 0xff) | ((src[in + 1] & 0xff) << 8);
            in += 2;
            if (offset == 0) {
                throw new CompressionFormatException("LZ4 match offset of zero");
            }

            int matchLength = token & 0xF;
            if (matchLength == 15) {
                long continued = readLength(src, in, inEnd, outEnd - out - 15 - MIN_MATCH);
                matchLength += (int) continued;
                in = (int) (continued >>> 32);
            }
            matchLength += MIN_MATCH;

            int from = out - offset;
            if (from < dstOff) {
                throw new CompressionFormatException("LZ4 match reaches back before the block");
            }
            if (matchLength > outEnd - out) {
                throw new CompressionFormatException("LZ4 match overruns the block");
            }
            if (offset >= matchLength) {
                System.arraycopy(dst, from, dst, out, matchLength);
            } else {
                // Overlapping matches are legal and must be copied byte by byte.
                for (int i = 0; i < matchLength; i++) {
                    dst[out + i] = dst[from + i];
                }
            }
            out += matchLength;
        }

        if (out != outEnd) {
            throw new CompressionFormatException(
                    "LZ4 block produced " + (out - dstOff) + " bytes, expected " + dstLen);
        }
    }

    /**
     * Reads a continued length: successive bytes are summed until one is not 255. A sum larger than
     * {@code room} (the output left, less what the token adds to the sum) is refused as it accumulates, so
     * the length can neither overrun the block nor overflow an {@code int}.
     *
     * @return the added length in the low 32 bits and the new input position in the high 32 bits
     */
    private static long readLength(byte[] src, int in, int inEnd, int room) {
        int extra = 0;
        int b;
        do {
            if (in >= inEnd) {
                throw new CompressionFormatException("LZ4 length continuation is truncated");
            }
            b = src[in++] & 0xff;
            extra += b;
            if (extra > room) {
                throw new CompressionFormatException("LZ4 length overruns the block");
            }
        } while (b == 255);
        return ((long) in << 32) | (extra & 0xffffffffL);
    }
}
