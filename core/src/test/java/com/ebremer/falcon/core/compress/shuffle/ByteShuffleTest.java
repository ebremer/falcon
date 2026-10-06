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

    @Test
    void shufflesWithinARange() {
        byte[] src = {9, 9, 1, 2, 3, 4, 9};
        byte[] dst = new byte[6];
        ByteShuffle.shuffle(src, 2, dst, 1, 4, 2);
        assertArrayEquals(new byte[] {0, 1, 3, 2, 4, 0}, dst);
    }
}
