package com.ebremer.falcon.core.checksum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Fletcher-32 as HDF5's {@code H5_checksum_fletcher32} computes it: against the textbook definition (sums of
 * big-endian 16-bit words modulo 65535, an odd last byte as a high byte), which its batches of 360 words must
 * reproduce for every length; and its offset form. libhdf5's and numcodecs' own values are checked where the
 * formats use it (HDF5's fletcher32 fixtures, Zarr's numcodecs vectors).
 */
class Fletcher32Test {

    /** The definition, one word at a time, reduced modulo 65535 as the batched form folds it. */
    private static int reference(byte[] data, int offset, int length) {
        long sum1 = 0;
        long sum2 = 0;
        for (int i = 0; i < length; i += 2) {
            int word = (data[offset + i] & 0xff) << 8 | (i + 1 < length ? data[offset + i + 1] & 0xff : 0);
            sum1 = (sum1 + word) % 65535;
            sum2 = (sum2 + sum1) % 65535;
        }
        return (int) (sum2 << 16 | sum1);
    }

    /** Equal modulo 65535 in each half: the batched form leaves 0xffff where the definition has 0. */
    private static void assertSameSums(int expected, int actual, String what) {
        assertEquals((expected >>> 16) % 65535, (actual >>> 16) % 65535, what + " (sum2)");
        assertEquals((expected & 0xffff) % 65535, (actual & 0xffff) % 65535, what + " (sum1)");
    }

    @Test
    void matchesTheDefinitionAtEveryLength() {
        Random random = new Random(32);
        byte[] data = new byte[2000];
        random.nextBytes(data);
        for (int length = 0; length <= data.length; length += length < 760 ? 1 : 37) {
            assertSameSums(reference(data, 0, length), Fletcher32.checksum(data, length), "length " + length);
        }
        byte[] ones = new byte[1500];
        Arrays.fill(ones, (byte) 0xff); // the sums' largest values, across several batches
        assertSameSums(reference(ones, 0, ones.length), Fletcher32.checksum(ones, ones.length), "0xff bytes");
    }

    @Test
    void anOffsetChecksumsTheBytesFromThere() {
        byte[] data = new byte[777];
        new Random(3).nextBytes(data);
        assertEquals(Fletcher32.checksum(Arrays.copyOfRange(data, 100, 601), 501), Fletcher32.checksum(data, 100, 501));
        assertEquals(Fletcher32.checksum(data, data.length), Fletcher32.checksum(data, 0, data.length));
    }

    @Test
    void knownValues() {
        assertEquals(0, Fletcher32.checksum(new byte[0], 0));
        assertEquals(0x00010001, Fletcher32.checksum(new byte[] {0, 1}, 2));
        assertEquals(0x01000100, Fletcher32.checksum(new byte[] {1}, 1)); // an odd byte is a high byte
        assertEquals(0x00010001, Fletcher32.legacyByteSwapped(0x01000100));
    }
}
