package com.ebremer.falcon.core.compress.bzip2;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;

/**
 * bzip2 decompression, as libbzip2 1.0.8's {@code BZ2_bzDecompress} reads one stream ({@code decompress.c},
 * {@code bzlib.c}). A stream is the header {@code "BZh"} and a block-size digit, then blocks, each:
 *
 * <ol>
 *   <li>the magic {@code 0x314159265359}, the block's CRC, a "randomised" bit, and the 24-bit position of
 *       the original string among the sorted rotations;</li>
 *   <li>the byte values used (a 16-bit map of 16-value ranges, then each range's 16 bits);</li>
 *   <li>2 to 6 Huffman tables, the table each group of 50 symbols uses (move-to-front coded, in unary), and
 *       each table's code lengths (delta coded);</li>
 *   <li>the symbols: move-to-front indices, with runs of the front value in bijective base 2
 *       ({@code RUNA}, {@code RUNB}), up to an end-of-block symbol.</li>
 * </ol>
 *
 * Undoing the move-to-front coding and then the Burrows&ndash;Wheeler transform gives the block, in which a
 * run of 4 equal bytes is followed by a count of 0 to 251 more. The stream ends with the magic
 * {@code 0x177245385090} and a CRC combining the blocks'. Every CRC is verified, and libbzip2's checks on a
 * block's structure are made, so a corrupt stream fails with {@link CompressionFormatException}; bytes after
 * the end of the stream are ignored, as libbzip2 does.
 */
public final class Bzip2Decoder {

    private static final int MAX_GROUPS = 6;
    private static final int GROUP_SIZE = 50;
    private static final int MAX_ALPHA_SIZE = 258;
    private static final int MAX_CODE_LEN = 20;
    private static final int MAX_SELECTORS = 2 + 900000 / GROUP_SIZE;
    private static final int RUNA = 0;
    private static final int RUNB = 1;

    private Bzip2Decoder() {
    }

    /**
     * Decompresses the bzip2 stream at the start of {@code length} bytes of {@code src} from {@code offset},
     * ignoring any bytes after it.
     *
     * @param src     the compressed bytes
     * @param offset  where the stream starts
     * @param length  the bytes available
     * @param maxSize the most bytes the stream may decode to
     * @return the decompressed bytes
     * @throws CompressionFormatException if the stream is malformed, truncated, fails a CRC, or decodes to
     *                                    more than {@code maxSize} bytes
     */
    public static byte[] decompress(byte[] src, int offset, int length, int maxSize) {
        if (offset < 0 || length < 0 || length > src.length - offset) {
            throw new IllegalArgumentException("invalid range " + offset + "+" + length + " of " + src.length);
        }
        if (maxSize < 0) {
            throw new IllegalArgumentException("negative maximum size " + maxSize);
        }
        return new Bzip2Decoder.Stream(src, offset, offset + length, maxSize).decode();
    }

    /** The state of one stream's decoding. */
    private static final class Stream {

        private final byte[] src;
        private final int end;
        private final int maxSize;
        private int pos;
        private long bitBuffer;
        private int bitCount;

        private byte[] out;
        private int outSize;
        private int[] tt = new int[0];

        // Per block: the symbol map, tables, and selectors.
        private final int[] seqToUnseq = new int[256];
        private final byte[] selector = new byte[MAX_SELECTORS];
        private final int[][] length = new int[MAX_GROUPS][MAX_ALPHA_SIZE];
        private final int[][] limit = new int[MAX_GROUPS][MAX_CODE_LEN + 3];
        private final int[][] base = new int[MAX_GROUPS][MAX_CODE_LEN + 3];
        private final int[][] perm = new int[MAX_GROUPS][MAX_ALPHA_SIZE];
        private final int[] minLength = new int[MAX_GROUPS];

        Stream(byte[] src, int start, int end, int maxSize) {
            this.src = src;
            this.pos = start;
            this.end = end;
            this.maxSize = maxSize;
            // Start small: a stream rarely says how much it holds, and a bound alone is no reason to allocate.
            this.out = new byte[(int) Math.min(maxSize, Math.max(64L, 4L * (end - start)))];
        }

        byte[] decode() {
            if (bits(8) != 'B' || bits(8) != 'Z' || bits(8) != 'h') {
                throw new CompressionFormatException("bzip2 stream does not start with \"BZh\"");
            }
            int level = bits(8) - '0';
            if (level < 1 || level > 9) {
                throw new CompressionFormatException("bzip2 block size digit " + level + " is not 1 to 9");
            }
            int blockMax = 100000 * level;
            int combined = 0;
            while (true) {
                long magic = ((long) bits(24) << 24) | bits(24);
                if (magic == 0x177245385090L) {
                    int stored = bits32();
                    if (stored != combined) {
                        throw new CompressionFormatException(String.format(
                                "bzip2 stream CRC mismatch: stored 0x%08x, computed 0x%08x", stored, combined));
                    }
                    return outSize == out.length ? out : Arrays.copyOf(out, outSize);
                }
                if (magic != 0x314159265359L) {
                    throw new CompressionFormatException(String.format("bad bzip2 block magic 0x%012x", magic));
                }
                int storedCrc = bits32();
                int computed = block(blockMax);
                if (computed != storedCrc) {
                    throw new CompressionFormatException(String.format(
                            "bzip2 block CRC mismatch: stored 0x%08x, computed 0x%08x", storedCrc, computed));
                }
                combined = Bzip2Tables.combine(combined, computed);
            }
        }

        /** Decodes one block after its CRC, appending it to the output; returns the CRC of its bytes. */
        private int block(int blockMax) {
            boolean randomised = bits(1) == 1;
            int origPtr = bits(24);

            // The symbol map: which byte values occur, in order.
            int ranges = bits(16);
            int inUse = 0;
            for (int i = 0; i < 16; i++) {
                if ((ranges & (0x8000 >>> i)) != 0) {
                    int used = bits(16);
                    for (int j = 0; j < 16; j++) {
                        if ((used & (0x8000 >>> j)) != 0) {
                            seqToUnseq[inUse++] = i * 16 + j;
                        }
                    }
                }
            }
            if (inUse == 0) {
                throw new CompressionFormatException("bzip2 block uses no byte values");
            }
            int alphaSize = inUse + 2;

            // The selectors, move-to-front coded in unary; libbzip2 1.0.8 ignores any past its maximum.
            int groups = bits(3);
            if (groups < 2 || groups > MAX_GROUPS) {
                throw new CompressionFormatException("bzip2 block has " + groups + " Huffman tables");
            }
            int selectors = bits(15);
            if (selectors < 1) {
                throw new CompressionFormatException("bzip2 block has no selectors");
            }
            byte[] mtf = {0, 1, 2, 3, 4, 5};
            for (int i = 0; i < selectors; i++) {
                int j = 0;
                while (bits(1) == 1) {
                    if (++j >= groups) {
                        throw new CompressionFormatException("bzip2 selector names table " + j + " of " + groups);
                    }
                }
                if (i < MAX_SELECTORS) {
                    byte table = mtf[j];
                    System.arraycopy(mtf, 0, mtf, 1, j);
                    mtf[0] = table;
                    selector[i] = table;
                }
            }
            selectors = Math.min(selectors, MAX_SELECTORS);

            // The code lengths, delta coded, and libbzip2's decoding tables (BZ2_hbCreateDecodeTables).
            for (int t = 0; t < groups; t++) {
                int curr = bits(5);
                int min = 32;
                int max = 0;
                for (int i = 0; i < alphaSize; i++) {
                    while (true) {
                        if (curr < 1 || curr > MAX_CODE_LEN) {
                            throw new CompressionFormatException("bzip2 code length " + curr + " is not 1 to 20");
                        }
                        if (bits(1) == 0) {
                            break;
                        }
                        curr += bits(1) == 0 ? 1 : -1;
                    }
                    length[t][i] = curr;
                    min = Math.min(min, curr);
                    max = Math.max(max, curr);
                }
                decodeTables(t, alphaSize, min, max);
            }

            // The symbols: move-to-front indices, runs of the front value, and the end of the block.
            int eob = inUse + 1;
            int[] counts = new int[256];
            byte[] front = new byte[256];
            for (int i = 0; i < 256; i++) {
                front[i] = (byte) i;
            }
            // A block of n symbols decodes to at least 4n/5 bytes (each run of 4 has a count after it).
            long room = maxSize - outSize;
            long cap = Math.min(blockMax, (5 * room) / 4 + 4);
            int nblock = 0;
            int group = -1;
            int groupLeft = 0;
            int table = 0;
            int runLength = -1;
            int runWeight = 1;
            while (true) {
                if (groupLeft == 0) {
                    if (++group >= selectors) {
                        throw new CompressionFormatException("bzip2 block runs past its " + selectors + " selectors");
                    }
                    groupLeft = GROUP_SIZE;
                    table = selector[group];
                }
                groupLeft--;
                int sym = symbol(table, alphaSize);
                if (sym == RUNA || sym == RUNB) {
                    if (runWeight >= 2 * 1024 * 1024) {
                        throw new CompressionFormatException("bzip2 run is too long");
                    }
                    runLength += (sym + 1) * runWeight;
                    runWeight <<= 1;
                    continue;
                }
                if (runLength >= 0) { // a run of the front value ends
                    int run = runLength + 1;
                    int value = seqToUnseq[front[0] & 0xff];
                    if (nblock + (long) run > cap) {
                        throw tooBig(nblock + (long) run, blockMax, cap);
                    }
                    ensureTt(nblock + run, (int) cap);
                    Arrays.fill(tt, nblock, nblock + run, value);
                    counts[value] += run;
                    nblock += run;
                    runLength = -1;
                    runWeight = 1;
                }
                if (sym == eob) {
                    break;
                }
                if (nblock >= cap) {
                    throw tooBig(nblock + 1L, blockMax, cap);
                }
                int index = sym - 1;
                byte moved = front[index];
                System.arraycopy(front, 0, front, 1, index);
                front[0] = moved;
                int value = seqToUnseq[moved & 0xff];
                ensureTt(nblock + 1, (int) cap);
                tt[nblock++] = value;
                counts[value]++;
            }
            if (origPtr >= nblock) {
                throw new CompressionFormatException("bzip2 block's origin " + origPtr + " is past its " + nblock + " bytes");
            }

            // Undo the Burrows-Wheeler transform: tt[i] gains, above its byte, the position that follows i.
            int[] cftab = new int[257];
            for (int i = 0; i < 256; i++) {
                cftab[i + 1] = cftab[i] + counts[i];
            }
            for (int i = 0; i < nblock; i++) {
                int b = tt[i] & 0xff;
                tt[cftab[b]++] |= i << 8;
            }
            return output(tt[origPtr] >>> 8, nblock, randomised);
        }

        /**
         * Follows the transform's chain from {@code tPos} through {@code nblock} bytes, undoing the runs of
         * 4 (and, for a randomised block, the flipped bytes), into the output; returns the block CRC.
         */
        private int output(int tPos, int nblock, boolean randomised) {
            int crc = 0xffffffff;
            int rNToGo = 0;
            int rTPos = 0;
            int previous = -1;
            int same = 0;
            for (int n = 0; n < nblock; n++) {
                if (tPos >= nblock) {
                    throw new CompressionFormatException("bzip2 block's transform is corrupt");
                }
                tPos = tt[tPos];
                int b = tPos & 0xff;
                tPos >>>= 8;
                if (randomised) { // BZ_RAND_UPD_MASK
                    if (rNToGo == 0) {
                        rNToGo = Bzip2Tables.R_NUMS[rTPos];
                        rTPos = (rTPos + 1) & 511;
                    }
                    rNToGo--;
                    if (rNToGo == 1) {
                        b ^= 1;
                    }
                }
                if (same == 4) { // after 4 equal bytes, a count of more
                    room(b);
                    Arrays.fill(out, outSize, outSize + b, (byte) previous);
                    outSize += b;
                    for (int i = 0; i < b; i++) {
                        crc = Bzip2Tables.updateCrc(crc, previous);
                    }
                    same = 0;
                    previous = -1;
                    continue;
                }
                same = b == previous ? same + 1 : 1;
                previous = b;
                room(1);
                out[outSize++] = (byte) b;
                crc = Bzip2Tables.updateCrc(crc, b);
            }
            if (same == 4) {
                throw new CompressionFormatException("bzip2 block ends without the count after a run of 4");
            }
            return ~crc;
        }

        /** Ensures the output has room for {@code more} bytes, within the maximum size. */
        private void room(int more) {
            long needed = (long) outSize + more;
            if (needed <= out.length) {
                return;
            }
            if (needed > maxSize) {
                throw new CompressionFormatException("bzip2 stream decodes to more than " + maxSize + " bytes");
            }
            out = Arrays.copyOf(out, (int) Math.min(maxSize, Math.max(needed, 2L * out.length)));
        }

        private void ensureTt(int needed, int cap) {
            if (needed > tt.length) {
                tt = Arrays.copyOf(tt, (int) Math.min(cap, Math.max(needed, Math.max(1024L, 2L * tt.length))));
            }
        }

        private CompressionFormatException tooBig(long nblock, int blockMax, long cap) {
            return nblock > blockMax
                    ? new CompressionFormatException("bzip2 block holds more than its " + blockMax + " bytes")
                    : new CompressionFormatException("bzip2 stream decodes to more than " + maxSize + " bytes");
        }

        /** {@code BZ2_hbCreateDecodeTables} for table {@code t}. */
        private void decodeTables(int t, int alphaSize, int min, int max) {
            int[] len = length[t];
            int[] lim = limit[t];
            int[] bas = base[t];
            int[] per = perm[t];
            int pp = 0;
            for (int i = min; i <= max; i++) {
                for (int j = 0; j < alphaSize; j++) {
                    if (len[j] == i) {
                        per[pp++] = j;
                    }
                }
            }
            Arrays.fill(bas, 0);
            for (int i = 0; i < alphaSize; i++) {
                bas[len[i] + 1]++;
            }
            for (int i = 1; i < bas.length; i++) {
                bas[i] += bas[i - 1];
            }
            Arrays.fill(lim, 0);
            int vec = 0;
            for (int i = min; i <= max; i++) {
                vec += bas[i + 1] - bas[i];
                lim[i] = vec - 1;
                vec <<= 1;
            }
            for (int i = min + 1; i <= max; i++) {
                bas[i] = ((lim[i - 1] + 1) << 1) - bas[i];
            }
            minLength[t] = min;
        }

        /** Reads one symbol with table {@code t} ({@code GET_MTF_VAL}). */
        private int symbol(int t, int alphaSize) {
            int[] lim = limit[t];
            int n = minLength[t];
            int code = bits(n);
            while (code > lim[n]) {
                if (++n > MAX_CODE_LEN) {
                    throw new CompressionFormatException("bzip2 Huffman code is longer than 20 bits");
                }
                code = (code << 1) | bits(1);
            }
            int index = code - base[t][n];
            if (index < 0 || index >= alphaSize) {
                throw new CompressionFormatException("bzip2 Huffman code " + code + " is not in its table");
            }
            return perm[t][index];
        }

        /** The next {@code n} (at most 24) bits, most significant first. */
        private int bits(int n) {
            while (bitCount < n) {
                if (pos >= end) {
                    throw new CompressionFormatException("bzip2 stream is truncated");
                }
                bitBuffer = (bitBuffer << 8) | (src[pos++] & 0xff);
                bitCount += 8;
            }
            bitCount -= n;
            return (int) (bitBuffer >>> bitCount) & ((1 << n) - 1);
        }

        private int bits32() {
            return (bits(16) << 16) | bits(16);
        }
    }
}
