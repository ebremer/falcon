package com.ebremer.falcon.core.compress.lz4;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;

/**
 * The LZ4 <em>block</em> format (the bare block, not the framed one), which is what Blosc stores for its
 * {@code lz4} and {@code lz4hc} compressors &mdash; both produce the same block format &mdash; and what
 * numcodecs' {@code LZ4} codec stores after a 4-byte size.
 *
 * <p>A block is a run of sequences. Each begins with a token byte whose high nibble is the literal
 * length and low nibble the match length minus 4; a nibble of 15 means the length continues in
 * following bytes, each adding its value until one is not 255. Literals are copied verbatim, then a
 * 2-byte little-endian offset selects a match earlier in the output. The final sequence carries literals
 * only.
 *
 * <p>Both of liblz4's compressors are here, ported from lz4 1.10.0 (which numcodecs 0.17 links):
 * {@link #compress(byte[], int, int, byte[], int, int, int) compress} is {@code LZ4_compress_fast}, and
 * {@link #compressHc(byte[], int, int, byte[], int, int, int) compressHc} is {@code LZ4_compress_HC} at
 * levels 1 to 9 (its optimal parser, levels 10 to 12, is not here), so a block is the one liblz4 writes for
 * the same input and settings. Every block keeps the format's end rules (the last five bytes are literals,
 * and the last match starts at least twelve bytes before the end), so any LZ4 decoder reads it.
 */
public final class Lz4 {

    private static final int MIN_MATCH = 4;

    /** The format's parsing restrictions (lz4's {@code doc/lz4_Block_format.md}). */
    static final int LASTLITERALS = 5;
    static final int MFLIMIT = 12;
    static final int MIN_LENGTH = MFLIMIT + 1;
    static final int RUN_MASK = 15;
    static final int ML_MASK = 15;
    static final int MAX_DISTANCE = 65535;
    /** {@code LZ4_MAX_INPUT_SIZE}. */
    private static final int MAX_INPUT_SIZE = 0x7E000000;

    /** {@code LZ4_HASHLOG} for the default {@code LZ4_MEMORY_USAGE} of 14. */
    private static final int HASH_LOG = 12;
    /** Inputs below this use the 16-bit table, hashed with one more bit ({@code LZ4_64Klimit}). */
    private static final int LIMIT_64K = 65536 + MFLIMIT - 1;
    private static final int SKIP_TRIGGER = 6;
    private static final int ACCELERATION_MAX = 65537;

    private Lz4() {
    }

    /**
     * The most bytes a block of {@code length} input bytes can take ({@code LZ4_compressBound}).
     *
     * @param length the input length
     * @return the bound
     * @throws IllegalArgumentException if {@code length} is negative or above LZ4's 2,113,929,216-byte limit
     */
    public static int maxCompressedLength(int length) {
        if (length < 0 || length > MAX_INPUT_SIZE) {
            throw new IllegalArgumentException("LZ4 cannot compress " + length + " bytes");
        }
        return length + length / 255 + 16;
    }

    /**
     * Compresses {@code len} bytes of {@code src} into a new LZ4 block, as {@code LZ4_compress_fast}.
     *
     * @param acceleration 1 for the best ratio; larger values search less (below 1 means 1)
     * @return the block
     */
    public static byte[] compress(byte[] src, int off, int len, int acceleration) {
        byte[] dst = new byte[maxCompressedLength(len)];
        return Arrays.copyOf(dst, compress(src, off, len, dst, 0, dst.length, acceleration));
    }

    /**
     * Compresses {@code len} bytes of {@code src} into {@code dst}, as {@code LZ4_compress_fast} with a
     * {@code dstCapacity} of {@code maxOut}. Below {@link #maxCompressedLength} the capacity is checked as
     * liblz4 checks it, so a block that would not fit gives 0, the answer liblz4 gives (Blosc then stores
     * the input raw).
     *
     * @param acceleration 1 for the best ratio; larger values search less (below 1 means 1)
     * @return the block's length, or 0 if it does not fit in {@code maxOut} bytes
     */
    public static int compress(byte[] src, int off, int len, byte[] dst, int dstOff, int maxOut, int acceleration) {
        int accel = Math.min(Math.max(acceleration, 1), ACCELERATION_MAX);
        boolean limited = maxOut < maxCompressedLength(len);
        if (len == 0) {
            if (limited && maxOut <= 0) {
                return 0;
            }
            dst[dstOff] = 0;
            return 1;
        }
        // LZ4_compress_generic_validated for one segment and no dictionary: a 16-bit table below 64 KiB,
        // else a 32-bit one hashed from 5 bytes (as on 64-bit platforms). Indexes count from off.
        boolean u16 = len < LIMIT_64K;
        int[] table = new int[u16 ? 1 << (HASH_LOG + 1) : 1 << HASH_LOG];
        int ip = off;
        int anchor = off;
        int iend = off + len;
        int mflimitPlusOne = iend - MFLIMIT + 1;
        int matchlimit = iend - LASTLITERALS;
        int op = dstOff;
        int olimit = limited ? dstOff + maxOut : -1;

        if (len >= MIN_LENGTH) {
            ip++; // the first byte's index is 0, which the table already holds
            int forwardH = hash(src, ip, u16);
            search:
            while (true) {
                int match;
                int forwardIp = ip;
                int step = 1;
                int searchMatchNb = accel << SKIP_TRIGGER;
                while (true) { // find a match, stepping further the longer none turns up
                    int h = forwardH;
                    int current = forwardIp - off;
                    int matchIndex = table[h];
                    ip = forwardIp;
                    forwardIp += step;
                    step = searchMatchNb++ >>> SKIP_TRIGGER;
                    if (forwardIp > mflimitPlusOne) {
                        break search;
                    }
                    match = off + matchIndex;
                    forwardH = hash(src, forwardIp, u16);
                    table[h] = current;
                    if (!u16 && matchIndex + MAX_DISTANCE < current) {
                        continue; // too far
                    }
                    if (read32(src, match) == read32(src, ip)) {
                        break;
                    }
                }
                while (ip > anchor && match > off && src[ip - 1] == src[match - 1]) { // catch up
                    ip--;
                    match--;
                }
                int litLength = ip - anchor;
                int token = op++;
                if (limited && (long) op + litLength + (2 + 1 + LASTLITERALS) + litLength / 255 > olimit) {
                    return 0;
                }
                op = writeLiteralLength(dst, token, op, litLength);
                System.arraycopy(src, anchor, dst, op, litLength);
                op += litLength;

                while (true) { // _next_match: the offset, then the match length
                    int offset = ip - match;
                    dst[op] = (byte) offset;
                    dst[op + 1] = (byte) (offset >>> 8);
                    op += 2;
                    int matchCode = count(src, ip + MIN_MATCH, match + MIN_MATCH, matchlimit);
                    ip += matchCode + MIN_MATCH;
                    if (limited && (long) op + (1 + LASTLITERALS) + (matchCode + 240) / 255 > olimit) {
                        return 0;
                    }
                    op = writeMatchLength(dst, token, op, matchCode);
                    anchor = ip;
                    if (ip >= mflimitPlusOne) {
                        break search;
                    }
                    table[hash(src, ip - 2, u16)] = ip - 2 - off;
                    int h = hash(src, ip, u16); // a match right here needs no literals
                    int current = ip - off;
                    int matchIndex = table[h];
                    match = off + matchIndex;
                    table[h] = current;
                    if ((u16 || matchIndex + MAX_DISTANCE >= current) && read32(src, match) == read32(src, ip)) {
                        token = op++;
                        dst[token] = 0;
                        continue;
                    }
                    forwardH = hash(src, ++ip, u16);
                    continue search;
                }
            }
        }
        op = lastLiterals(src, anchor, iend - anchor, dst, op, olimit);
        return op < 0 ? 0 : op - dstOff;
    }

    /**
     * Compresses {@code len} bytes of {@code src} into a new LZ4 block with liblz4's high-compression
     * search, as {@code LZ4_compress_HC}.
     *
     * @param level 1 to 9, searching deeper with each level (below 1 means 9, liblz4's default; above 9
     *              means 9: liblz4's optimal parser, its levels 10 to 12, is not implemented)
     * @return the block
     */
    public static byte[] compressHc(byte[] src, int off, int len, int level) {
        byte[] dst = new byte[maxCompressedLength(len)];
        return Arrays.copyOf(dst, compressHc(src, off, len, dst, 0, dst.length, level));
    }

    /**
     * Compresses {@code len} bytes of {@code src} into {@code dst} with liblz4's high-compression search,
     * as {@code LZ4_compress_HC} with a {@code dstCapacity} of {@code maxOut}; the capacity is checked as in
     * {@link #compress(byte[], int, int, byte[], int, int, int)}.
     *
     * @param level 1 to 9 (below 1 means 9; above 9 means 9)
     * @return the block's length, or 0 if it does not fit in {@code maxOut} bytes
     */
    public static int compressHc(byte[] src, int off, int len, byte[] dst, int dstOff, int maxOut, int level) {
        boolean limited = maxOut < maxCompressedLength(len);
        int cLevel = level < 1 ? 9 : Math.min(level, 9);
        return new Lz4Hc(src, off, len).compress(dst, dstOff, limited ? dstOff + maxOut : -1, cLevel);
    }

    /** Writes a literal length into the token's high nibble and any continuation bytes; returns the new op. */
    static int writeLiteralLength(byte[] dst, int token, int op, int length) {
        if (length >= RUN_MASK) {
            dst[token] = (byte) (RUN_MASK << 4);
            int rest = length - RUN_MASK;
            for (; rest >= 255; rest -= 255) {
                dst[op++] = (byte) 255;
            }
            dst[op++] = (byte) rest;
        } else {
            dst[token] = (byte) (length << 4);
        }
        return op;
    }

    /** Adds a match length less 4 to the token's low nibble and writes any continuation bytes; returns op. */
    static int writeMatchLength(byte[] dst, int token, int op, int matchCode) {
        if (matchCode >= ML_MASK) {
            dst[token] += (byte) ML_MASK;
            int rest = matchCode - ML_MASK;
            for (; rest >= 255; rest -= 255) {
                dst[op++] = (byte) 255;
            }
            dst[op++] = (byte) rest;
        } else {
            dst[token] += (byte) matchCode;
        }
        return op;
    }

    /**
     * Writes the final, literal-only sequence. {@code olimit} is the end of the capacity, or -1 for none.
     *
     * @return the new output position, or -1 if the sequence does not fit
     */
    static int lastLiterals(byte[] src, int anchor, int lastRun, byte[] dst, int op, int olimit) {
        if (olimit >= 0 && (long) op + lastRun + 1 + (lastRun + 255 - RUN_MASK) / 255 > olimit) {
            return -1;
        }
        int token = op++;
        op = writeLiteralLength(dst, token, op, lastRun);
        System.arraycopy(src, anchor, dst, op, lastRun);
        return op + lastRun;
    }

    /** The number of equal bytes from {@code in} and {@code match} onward, stopping at {@code limit}. */
    static int count(byte[] src, int in, int match, int limit) {
        int start = in;
        while (in < limit && src[in] == src[match]) {
            in++;
            match++;
        }
        return in - start;
    }

    /** A little-endian 32-bit read. */
    static int read32(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
    }

    /** {@code LZ4_hashPosition}: 4 bytes for the 16-bit table, 5 (of a 64-bit read) for the 32-bit one. */
    private static int hash(byte[] src, int p, boolean u16) {
        if (u16) {
            return (read32(src, p) * -1640531535) >>> (32 - (HASH_LOG + 1)); // 2654435761U
        }
        long sequence = (read32(src, p) & 0xffffffffL) | (long) read32(src, p + 4) << 32;
        return (int) (((sequence << 24) * 889523592379L) >>> (64 - HASH_LOG));
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
