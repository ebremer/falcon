package com.ebremer.falcon.core.checksum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Known-answer tests for Jenkins lookup3.
 *
 * <p>The expected values are the canonical {@code lookup3.c} self-test vectors, independently
 * confirmed to match the reference HDF5 library: the empty and "Four score…" vectors are the ones
 * baked into Jenkins' own driver, and the whole function was cross-checked against a real HDF5
 * version-3 superblock checksum written by h5py / HDF5&nbsp;2.0 (= {@code 0xcaab3961} over its first
 * 44 bytes).
 */
class Lookup3Test {

    private static int hash(String s) {
        return Lookup3.hashLittle(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void canonicalVectors() {
        assertEquals(0xdeadbeef, Lookup3.hashLittle(new byte[0]));
        assertEquals(0x17770551, hash("Four score and seven years ago"));
        assertEquals(0x58d68708, hash("a"));
        assertEquals(0x0e397631, hash("abc"));
        assertEquals(0x95c45535, hash("message digest"));
    }

    @Test
    void binaryVector0Through19() {
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        assertEquals(0x94b659b5, Lookup3.hashLittle(data));
    }

    @Test
    void segmentOverloadMatchesArrayOverload() {
        byte[] data = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.US_ASCII);
        int viaArray = Lookup3.hashLittle(data);
        int viaSegment = Lookup3.hashLittle(MemorySegment.ofArray(data), 0, data.length, 0);
        assertEquals(viaArray, viaSegment);
    }

    @Test
    void offsetAndLengthHashesSubRange() {
        byte[] whole = "XXXFour score and seven years agoYYY".getBytes(StandardCharsets.US_ASCII);
        int sub = Lookup3.hashLittle(whole, 3, 30, 0); // the 30-byte middle
        assertEquals(0x17770551, sub);
    }

    @Test
    void initialValueChangesResult() {
        byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);
        assertEquals(0x0e397631, Lookup3.hashLittle(data, 0));
        // A different seed must yield a different hash (sanity, not a fixed vector).
        org.junit.jupiter.api.Assertions.assertNotEquals(Lookup3.hashLittle(data, 0), Lookup3.hashLittle(data, 1));
    }
}
