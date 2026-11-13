package com.ebremer.falcon.core.compress.shuffle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The byte shuffle: like positions grouped, bytes past the last whole element copied through (as libhdf5's
 * shuffle filter and c-blosc leave them), and the inverse restoring every byte.
 */
class ByteShuffleTest {

    @Test
    void groupsLikeBytesAndKeepsTheTail() {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        // three 3-byte elements, then 2 bytes that are not a whole one
        assertArrayEquals(new byte[] {1, 4, 7, 2, 5, 8, 3, 6, 9, 10, 11}, ByteShuffle.shuffle(data, 3));
        assertArrayEquals(data, ByteShuffle.unshuffle(ByteShuffle.shuffle(data, 3), 3));
        assertArrayEquals(data, ByteShuffle.shuffle(data, 1));
        assertArrayEquals(data, ByteShuffle.shuffle(data, 16)); // no whole element: all tail
    }

    @Test
    void roundTripsAtEveryElementSizeAndLength() {
        Random random = new Random(2);
        for (int size = 1; size <= 17; size++) {
            for (int length = 0; length < 200; length += 7) {
                byte[] data = new byte[length];
                random.nextBytes(data);
                assertArrayEquals(data, ByteShuffle.unshuffle(ByteShuffle.shuffle(data, size), size), size + "/" + length);
            }
        }
    }

    /**
     * Every element size has the same definition, whichever loop moves it (elements of 2, 4, and 8 bytes
     * move whole): byte {@code b} of element {@code e} is byte {@code b * elements + e} of the shuffle.
     */
    @Test
    void isTheDefinitionAtEveryElementSizeWithinARange() {
        Random random = new Random(11);
        for (int size = 1; size <= 17; size++) {
            for (int length : new int[] {0, 1, size - 1, size, size + 1, 5 * size, 7 * size + size / 2, 64, 257, 1000}) {
                int srcOff = random.nextInt(9);
                int dstOff = random.nextInt(9);
                byte[] src = new byte[srcOff + length + 5];
                random.nextBytes(src);
                int elements = size <= 1 ? 0 : length / size;
                byte[] expected = new byte[dstOff + length + 5];
                java.util.Arrays.fill(expected, (byte) 0x5A);
                System.arraycopy(src, srcOff, expected, dstOff, length); // one-byte elements, and the tail
                for (int e = 0; e < elements; e++) {
                    for (int b = 0; b < size; b++) {
                        expected[dstOff + b * elements + e] = src[srcOff + e * size + b];
                    }
                }
                byte[] shuffled = new byte[expected.length];
                java.util.Arrays.fill(shuffled, (byte) 0x5A);
                byte[] before = src.clone();
                ByteShuffle.shuffle(src, srcOff, shuffled, dstOff, length, size);
                assertArrayEquals(expected, shuffled, "shuffle " + size + "/" + length);
                assertArrayEquals(before, src, "the source is left as it was");

                // And back, into another place: every byte returns, and none around them is touched.
                int backOff = random.nextInt(9);
                byte[] back = new byte[backOff + length + 5];
                java.util.Arrays.fill(back, (byte) 0x33);
                byte[] restored = back.clone();
                System.arraycopy(src, srcOff, restored, backOff, length);
                ByteShuffle.unshuffle(shuffled, dstOff, back, backOff, length, size);
                assertArrayEquals(restored, back, "unshuffle " + size + "/" + length);
            }
        }
    }

    /** The bytes of numbers, as the filters see them: the low bytes of little-endian values first. */
    @Test
    void groupsTheBytesOfNumbers() {
        byte[] shorts = {0x01, 0x11, 0x02, 0x12, 0x03, 0x13};
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x11, 0x12, 0x13}, ByteShuffle.shuffle(shorts, 2));
        byte[] ints = {1, 2, 3, 4, 5, 6, 7, 8, (byte) 0xF9, (byte) 0xFA, (byte) 0xFB, (byte) 0xFC};
        assertArrayEquals(new byte[] {1, 5, (byte) 0xF9, 2, 6, (byte) 0xFA, 3, 7, (byte) 0xFB, 4, 8, (byte) 0xFC},
                ByteShuffle.shuffle(ints, 4));
        byte[] longs = new byte[24];
        for (int i = 0; i < longs.length; i++) {
            longs[i] = (byte) (0x80 + i); // high bits set: no byte may spill into its neighbour
        }
        byte[] grouped = new byte[24];
        for (int e = 0; e < 3; e++) {
            for (int b = 0; b < 8; b++) {
                grouped[b * 3 + e] = longs[e * 8 + b];
            }
        }
        assertArrayEquals(grouped, ByteShuffle.shuffle(longs, 8));
        assertArrayEquals(shorts, ByteShuffle.unshuffle(ByteShuffle.shuffle(shorts, 2), 2));
        assertArrayEquals(ints, ByteShuffle.unshuffle(ByteShuffle.shuffle(ints, 4), 4));
        assertArrayEquals(longs, ByteShuffle.unshuffle(grouped, 8));
    }

    @Test
    void shufflesWithinARange() {
        byte[] src = {9, 9, 1, 2, 3, 4, 9};
        byte[] dst = new byte[6];
        ByteShuffle.shuffle(src, 2, dst, 1, 4, 2);
        assertArrayEquals(new byte[] {0, 1, 3, 2, 4, 0}, dst);
    }
}
