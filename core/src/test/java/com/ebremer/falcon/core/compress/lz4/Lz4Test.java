package com.ebremer.falcon.core.compress.lz4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The LZ4 block format: decoding and both compressors.
 *
 * <p>Block lengths (Zarr P1 H2). A length continues over bytes of 255, and its sum could approach
 * {@code Integer.MAX_VALUE}: the token's nibble then overflowed it to a negative length, which passed the
 * overrun checks and reached {@code System.arraycopy} as an {@code IndexOutOfBoundsException}. A length is
 * now refused as soon as it exceeds the output left.
 */
class Lz4Test {

    /** A token with a continued length of {@code extra}, as 255s and a final byte, after the token. */
    private static byte[] continued(int token, long extra) {
        int count255 = (int) (extra / 255);
        byte[] block = new byte[1 + count255 + 1];
        block[0] = (byte) token;
        Arrays.fill(block, 1, 1 + count255, (byte) 0xFF);
        block[block.length - 1] = (byte) (extra % 255);
        return block;
    }

    @Test
    void aLiteralLengthNearIntMaxIsRefused() {
        // 15 + (Integer.MAX_VALUE - 10) wraps negative
        byte[] block = continued(0xF0, Integer.MAX_VALUE - 10);
        assertThrows(CompressionFormatException.class,
                () -> Lz4.decompress(block, 0, block.length, new byte[100], 0, 100));
    }

    @Test
    void aMatchLengthNearIntMaxIsRefused() {
        // one literal, offset 1, then a match length of 4 + 15 + (Integer.MAX_VALUE - 15): negative
        byte[] tail = continued(0x1F, Integer.MAX_VALUE - 15);
        byte[] block = new byte[tail.length + 3];
        block[0] = tail[0];
        block[1] = 'x';
        block[2] = 1; // offset 1, little-endian
        block[3] = 0;
        System.arraycopy(tail, 1, block, 4, tail.length - 1);
        assertThrows(CompressionFormatException.class,
                () -> Lz4.decompress(block, 0, block.length, new byte[100], 0, 100));
    }

    @Test
    void validBlocksStillDecode() {
        // "abcabcabcabc": literals "abc", then a 9-byte overlapping match at offset 3, as one sequence, and
        // a final literal-only sequence.
        byte[] block = {(byte) 0x35, 'a', 'b', 'c', 3, 0, 0x10, 'd'};
        byte[] out = new byte[13];
        Lz4.decompress(block, 0, block.length, out, 0, out.length);
        assertArrayEquals("abcabcabcabcd".getBytes(), out);
    }

    // ---- the compressors (F3); byte identity with liblz4 is BloscEncodeVectorsTest's ----

    /** Inputs at the format's edges: empty, under the 13-byte minimum, runs, periods, far and near repeats. */
    private static List<byte[]> edgeInputs() {
        Random random = new Random(9);
        List<byte[]> inputs = new ArrayList<>();
        for (int n : new int[] {0, 1, 4, 12, 13, 14, 20, 100}) {
            byte[] b = new byte[n];
            Arrays.fill(b, (byte) 'z');
            inputs.add(b);
        }
        byte[] rnd = new byte[100_000];
        random.nextBytes(rnd);
        inputs.add(rnd); // incompressible
        inputs.add(new byte[1 << 20]); // one long match: length bytes of 255 by the thousand
        for (int period : new int[] {1, 2, 3, 7}) { // overlapping matches
            byte[] b = new byte[70_000];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) (i % period * 37);
            }
            inputs.add(b);
        }
        for (int distance : new int[] {65_535, 65_536, 70_000}) { // a repeat at the largest offset, and past it
            byte[] b = new byte[distance + 5000];
            random.nextBytes(b);
            System.arraycopy(b, 0, b, distance, 5000);
            inputs.add(b);
        }
        for (int n : new int[] {65_546, 65_547, 65_548}) { // either side of liblz4's 16-bit table limit
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) {
                b[i] = (byte) ((i / 5) % 251 ^ (i % 97 == 0 ? random.nextInt() : 0));
            }
            inputs.add(b);
        }
        return inputs;
    }

    @Test
    void compressedBlocksRoundTripAndKeepTheEndRules() {
        for (byte[] data : edgeInputs()) {
            for (int setting : new int[] {-3, 0, 1, 2, 3, 5, 9, 12, 100, 70_000}) {
                check(data, Lz4.compress(data, 0, data.length, setting), "fast " + setting);
                check(data, Lz4.compressHc(data, 0, data.length, setting), "hc " + setting);
            }
        }
    }

    /**
     * Decodes the block and checks the format's end rules (lz4's {@code lz4_Block_format.md}): the last
     * sequence is literals only, the last five bytes are literals, and the last match starts at least twelve
     * bytes before the end.
     */
    private static void check(byte[] data, byte[] block, String what) {
        String where = what + ", " + data.length + " bytes";
        assertTrue(block.length <= Lz4.maxCompressedLength(data.length), where);
        byte[] out = new byte[data.length];
        Lz4.decompress(block, 0, block.length, out, 0, out.length);
        assertArrayEquals(data, out, where);
        int in = 0;
        int pos = 0;
        int lastMatchStart = -1;
        int lastMatchEnd = 0;
        while (true) {
            int token = block[in++] & 0xff;
            int literals = token >>> 4;
            if (literals == 15) {
                int b;
                do {
                    b = block[in++] & 0xff;
                    literals += b;
                } while (b == 255);
            }
            in += literals;
            pos += literals;
            if (in >= block.length) {
                break;
            }
            in += 2;
            int match = (token & 15) + 4;
            if ((token & 15) == 15) {
                int b;
                do {
                    b = block[in++] & 0xff;
                    match += b;
                } while (b == 255);
            }
            lastMatchStart = pos;
            pos += match;
            lastMatchEnd = pos;
        }
        assertEquals(data.length, pos, where);
        if (lastMatchStart >= 0) {
            assertTrue(lastMatchEnd <= data.length - 5, where + ": the last five bytes must be literals");
            assertTrue(lastMatchStart <= data.length - 12, where + ": the last match starts too late");
        }
    }

    /** A capacity below the bound is checked as liblz4 checks it: the block, or 0 when it would not fit. */
    @Test
    void aCappedBlockIsTheSameBlockOrNothing() {
        for (byte[] data : edgeInputs()) {
            byte[] fast = Lz4.compress(data, 0, data.length, 1);
            byte[] hc = Lz4.compressHc(data, 0, data.length, 9);
            for (int cap : new int[] {0, 1, fast.length - 1, fast.length, fast.length + 8, data.length}) {
                capped(data, fast, Math.max(cap, 0), false);
            }
            for (int cap : new int[] {0, hc.length - 1, hc.length + 8, data.length}) {
                capped(data, hc, Math.max(cap, 0), true);
            }
        }
        byte[] random = new byte[10_000];
        new Random(1).nextBytes(random);
        assertEquals(0, Lz4.compress(random, 0, random.length, new byte[10_000], 0, random.length, 1),
                "incompressible data does not fit in its own size");
        byte[] zeros = new byte[10_000];
        byte[] dst = new byte[60];
        int n = Lz4.compress(zeros, 0, zeros.length, dst, 0, dst.length, 1);
        assertArrayEquals(Lz4.compress(zeros, 0, zeros.length, 1), Arrays.copyOf(dst, n));
        assertThrows(IllegalArgumentException.class, () -> Lz4.maxCompressedLength(-1));
        assertThrows(IllegalArgumentException.class, () -> Lz4.maxCompressedLength(0x7E000001));
    }

    private static void capped(byte[] data, byte[] whole, int cap, boolean hc) {
        byte[] dst = new byte[cap + 8];
        int n = hc ? Lz4.compressHc(data, 0, data.length, dst, 4, cap, 9) : Lz4.compress(data, 0, data.length, dst, 4, cap, 1);
        String what = (hc ? "hc" : "fast") + " cap " + cap + " of " + data.length;
        assertTrue(n <= cap, what);
        if (n > 0) {
            assertArrayEquals(whole, Arrays.copyOfRange(dst, 4, 4 + n), what);
        } else {
            // liblz4 keeps a few bytes of slack per sequence, so a block a little smaller than the cap is refused too
            assertTrue(whole.length > cap - 16, what + ": refused a block of " + whole.length);
        }
    }
}
