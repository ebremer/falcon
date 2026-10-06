package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Blosc headers checked as c-blosc 1.x checks them (Zarr P1 H4), sizes bounded before allocation (H1), and
 * block lookup that is not quadratic (H3). Each rule was checked against c-blosc 1.21.7 (numcodecs 0.17):
 * it rejects every header refused here, and decodes both-shuffle-flag buffers as asserted here.
 */
class BloscHeaderTest {

    private static final int HEADER = 16;

    /** A memcpy'ed buffer holding {@code data}, as c-blosc writes one. */
    private static byte[] memcpy(byte[] data, int typeSize) {
        byte[] out = new byte[HEADER + data.length];
        header(out, 2, 0x02 | 0x10, typeSize, data.length, data.length, out.length);
        System.arraycopy(data, 0, out, HEADER, data.length);
        return out;
    }

    private static void header(byte[] out, int version, int flags, int typeSize, int nbytes, int blocksize,
                               int cbytes) {
        out[0] = (byte) version;
        out[1] = 1;
        out[2] = (byte) flags;
        out[3] = (byte) typeSize;
        putLe32(out, 4, nbytes);
        putLe32(out, 8, blocksize);
        putLe32(out, 12, cbytes);
    }

    private static void putLe32(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
        b[off + 2] = (byte) (value >>> 16);
        b[off + 3] = (byte) (value >>> 24);
    }

    private static byte[] sample(int n) {
        byte[] data = new byte[n];
        for (int i = 0; i < n; i++) {
            data[i] = (byte) (i * 7 + i / 64);
        }
        return data;
    }

    private static void assertRefused(byte[] buffer, String expected) {
        CompressionFormatException e = assertThrows(CompressionFormatException.class,
                () -> BloscDecoder.decompress(buffer));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    @Test
    void headersCBloscRefusesAreRefused() {
        byte[] data = sample(64);
        assertArrayEquals(data, BloscDecoder.decompress(memcpy(data, 4))); // the valid form

        byte[] version1 = memcpy(data, 4);
        version1[0] = 1;
        assertRefused(version1, "version 1");
        // Versions 3 to 6 are c-blosc2 chunks (Blosc2DecoderTest); a short header reads as c-blosc2 reads it.
        for (int version = 3; version <= 6; version++) {
            byte[] blosc2 = memcpy(data, 4);
            blosc2[0] = (byte) version;
            assertArrayEquals(data, BloscDecoder.decompress(blosc2), "version " + version);
        }
        byte[] version7 = memcpy(data, 4);
        version7[0] = 7;
        assertThrows(UnsupportedCompressionException.class, () -> BloscDecoder.decompress(version7));

        byte[] reserved = memcpy(data, 4);
        reserved[2] |= 0x08;
        assertRefused(reserved, "reserved flag");

        byte[] typeSize0 = memcpy(data, 0);
        assertRefused(typeSize0, "type size of zero");

        byte[] blocksize0 = memcpy(data, 4);
        putLe32(blocksize0, 8, 0);
        assertRefused(blocksize0, "block size 0");

        byte[] blocksizeOver = memcpy(data, 4);
        putLe32(blocksizeOver, 8, data.length + 1);
        assertRefused(blocksizeOver, "block size 65");

        // c-blosc requires a memcpy'ed buffer to be exactly its data plus the header.
        byte[] padded = Arrays.copyOf(memcpy(data, 4), HEADER + data.length + 1);
        putLe32(padded, 12, padded.length);
        assertRefused(padded, "does not hold");
    }

    @Test
    void bothShuffleFlagsMeanTheByteShuffleForWideTypes() {
        // c-blosc's blosc_d: the byte shuffle wins for a type wider than a byte; Falcon bit-unshuffled.
        byte[] data = sample(4096);
        byte[] byteShuffled = BloscEncoder.compress(data, 4);
        assertEquals(0x01, byteShuffled[2] & 0x05);
        byteShuffled[2] |= 0x04;
        assertArrayEquals(data, BloscDecoder.decompress(byteShuffled));
    }

    @Test
    void forOneByteTypesTheBitShuffleFlagApplies() {
        // With a type size of 1 c-blosc ignores the byte-shuffle flag, so a bit-shuffled buffer that also
        // has it set is bit-unshuffled.
        byte[] data = sample(4096);
        byte[] bitShuffled = BloscEncoder.compress(data, 1, BloscEncoder.BITSHUFFLE, 0);
        assertEquals(0x04, bitShuffled[2] & 0x05);
        bitShuffled[2] |= 0x01;
        assertArrayEquals(data, BloscDecoder.decompress(bitShuffled));
    }

    @Test
    void aDeclaredSizeOverTheMaximumFailsBeforeAllocating() {
        // A 16-byte buffer claiming 2 GiB: refused from the header alone, the memcpy path included.
        byte[] huge = new byte[HEADER];
        header(huge, 2, 0x02 | 0x10, 1, Integer.MAX_VALUE - 8, 1 << 20, HEADER);
        CompressionFormatException e = assertThrows(CompressionFormatException.class,
                () -> BloscDecoder.decompress(huge, 1 << 20));
        assertTrue(e.getMessage().contains("more than the 1048576 expected"), e.getMessage());

        byte[] data = sample(1000);
        byte[] buffer = BloscEncoder.compress(data, 4);
        assertArrayEquals(data, BloscDecoder.decompress(buffer, 1000));
        assertThrows(CompressionFormatException.class, () -> BloscDecoder.decompress(buffer, 999));
        // Without a maximum, the memcpy path still checks the header before allocating.
        assertRefused(huge, "does not hold");
    }

    @Test
    void manyBlocksDecodeInLinearithmicTime() {
        // 262,144 raw blocks of 128 bytes. Finding each block's end scanned every offset (H3): about 34
        // billion comparisons, some 8 s. A binary search makes it milliseconds.
        int blocks = 1 << 18;
        int blocksize = 128;
        int nbytes = blocks * blocksize;
        byte[] buffer = new byte[HEADER + 4 * blocks + blocks * (4 + blocksize)];
        header(buffer, 2, 0x10 | (4 << 5), 1, nbytes, blocksize, buffer.length);
        int position = HEADER + 4 * blocks;
        for (int b = 0; b < blocks; b++) {
            putLe32(buffer, HEADER + 4 * b, position);
            putLe32(buffer, position, blocksize); // a stream as long as its block is stored raw
            Arrays.fill(buffer, position + 4, position + 4 + blocksize, (byte) b);
            position += 4 + blocksize;
        }
        byte[] out = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> BloscDecoder.decompress(buffer));
        assertEquals(nbytes, out.length);
        assertEquals((byte) 12345, out[12345 * blocksize]);
    }
}
