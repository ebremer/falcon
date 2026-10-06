package com.ebremer.falcon.core.compress.lz4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * LZ4 block lengths (Zarr P1 H2). A length continues over bytes of 255, and its sum could approach
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
}
