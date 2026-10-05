package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * BloscLZ block decompression &mdash; c-blosc's built-in LZ codec (a FastLZ variant) and its default
 * compressor. Translated directly from {@code blosclz_decompress} in c-blosc's {@code blosclz.c}.
 *
 * <p>The stream is a sequence of opcodes. A control byte below 32 introduces a literal run of
 * {@code ctrl + 1} bytes; a control byte of 32 or more introduces a match: its top three bits carry the
 * length (extended by trailing bytes when 7) and its low five bits the high part of the back-reference
 * distance, with the low part in the following byte and a 16-bit "far" distance signaled by an all-ones
 * low byte.
 */
final class BloscLz {

    private static final int MAX_DISTANCE = 8191;

    private BloscLz() {
    }

    /** Decompresses one block, which must produce exactly {@code outLength} bytes. */
    static void decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int outLength) {
        int ip = srcOff;
        int ipLimit = srcOff + srcLen;
        int op = dstOff;
        int opLimit = dstOff + outLength;
        if (srcLen == 0) {
            throw new CompressionFormatException("empty BloscLZ block");
        }
        int ctrl = (src[ip++] & 0xff) & 31; // the first opcode is always a literal run

        while (true) {
            if (ctrl >= 32) {
                int len = (ctrl >>> 5) - 1;
                int ofs = (ctrl & 31) << 8;
                int ref = op - ofs;
                int code;

                if (len == 7 - 1) {
                    do {
                        if (ip + 1 >= ipLimit) {
                            throw truncated();
                        }
                        code = src[ip++] & 0xff;
                        len += code;
                        if (len > opLimit) { // cannot fit, and must not overflow
                            throw new CompressionFormatException("BloscLZ match overruns the block");
                        }
                    } while (code == 255);
                } else if (ip + 1 >= ipLimit) {
                    throw truncated();
                }
                code = src[ip++] & 0xff;
                len += 3;
                ref -= code;

                if (code == 255 && ofs == (31 << 8)) { // 16-bit "far" distance
                    if (ip + 1 >= ipLimit) {
                        throw truncated();
                    }
                    ofs = (src[ip++] & 0xff) << 8;
                    ofs += src[ip++] & 0xff;
                    ref = op - ofs - MAX_DISTANCE;
                }

                if (len > opLimit - op) {
                    throw new CompressionFormatException("BloscLZ match overruns the block");
                }
                if (ref - 1 < dstOff) {
                    throw new CompressionFormatException("BloscLZ match reaches before the block");
                }
                if (ip >= ipLimit) {
                    break;
                }
                ctrl = src[ip++] & 0xff;
                ref--;
                // Overlapping matches are legal and must be copied byte by byte.
                for (int i = 0; i < len; i++) {
                    dst[op + i] = dst[ref + i];
                }
                op += len;
            } else {
                int literals = ctrl + 1;
                if (literals > opLimit - op) {
                    throw new CompressionFormatException("BloscLZ literal run overruns the block");
                }
                if (literals > ipLimit - ip) {
                    throw truncated();
                }
                System.arraycopy(src, ip, dst, op, literals);
                op += literals;
                ip += literals;
                if (ip >= ipLimit) {
                    break;
                }
                ctrl = src[ip++] & 0xff;
            }
        }

        if (op != opLimit) {
            throw new CompressionFormatException(
                    "BloscLZ block produced " + (op - dstOff) + " bytes, expected " + outLength);
        }
    }

    private static CompressionFormatException truncated() {
        return new CompressionFormatException("BloscLZ block is truncated");
    }
}
