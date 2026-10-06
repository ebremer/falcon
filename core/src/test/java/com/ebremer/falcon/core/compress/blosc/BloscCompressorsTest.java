package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Blosc's internal compressors on the write side (F3): BloscLZ and Snappy at their edges, and the
 * container's rules for each compressor, as measured against c-blosc 1.21 (numcodecs 0.17). Byte identity
 * with c-blosc over a wide matrix is {@link BloscEncodeVectorsTest}'s.
 */
class BloscCompressorsTest {

    private static final int[] ALL = {BloscEncoder.BLOSCLZ, BloscEncoder.LZ4, BloscEncoder.LZ4HC,
        BloscEncoder.SNAPPY, BloscEncoder.ZLIB, BloscEncoder.ZSTD};

    @Test
    void everyCompressorRoundTripsWithEveryFilterAndTypeSize() {
        Random random = new Random(21);
        for (int ts : new int[] {1, 2, 3, 4, 8, 16, 17, 300}) {
            byte[] data = new byte[ts * 3000 + (ts > 1 ? 1 : 0)]; // a partial element at the end, too
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % ts == 0 ? random.nextInt(4) : i / ts / 9);
            }
            for (int compressor : ALL) {
                for (int shuffle = BloscEncoder.NOSHUFFLE; shuffle <= BloscEncoder.BITSHUFFLE; shuffle++) {
                    for (int clevel : new int[] {0, 1, 2, 5, 9}) {
                        for (int blockSize : new int[] {0, 128, 5000}) {
                            byte[] buffer = BloscEncoder.compress(data, ts, shuffle, blockSize, clevel, compressor);
                            String what = "compressor " + compressor + " ts " + ts + " shuffle " + shuffle
                                    + " clevel " + clevel + " block " + blockSize;
                            assertArrayEquals(data, BloscDecoder.decompress(buffer), what);
                            assertTrue(buffer.length <= data.length + 16, what);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theHeaderNamesTheCompressorAndTheSplit() {
        byte[] data = ints(100_000);
        int[] formats = {0, 1, 1, 2, 3, 4}; // LZ4HC writes LZ4's format
        for (int compressor : ALL) {
            byte[] buffer = BloscEncoder.compress(data, 4, BloscEncoder.SHUFFLE, 0, 5, compressor);
            assertEquals(2, buffer[0], "format version");
            assertEquals(1, buffer[1], "every compressor's format version is 1");
            assertEquals(formats[compressor], (buffer[2] & 0xff) >>> 5, "compressor " + compressor);
            // c-blosc splits every compressor but zstd into one stream per byte of the type
            assertEquals(compressor == BloscEncoder.ZSTD ? 0x10 : 0, buffer[2] & 0x10, "compressor " + compressor);
        }
        assertEquals(BloscEncoder.LZ4HC, BloscEncoder.compressor("lz4hc"));
        assertEquals(BloscEncoder.SNAPPY, BloscEncoder.compressor("snappy"));
        assertThrows(IllegalArgumentException.class, () -> BloscEncoder.compressor("LZ4"));
        assertThrows(IllegalArgumentException.class, () -> BloscEncoder.compressor("brotli"));
        assertThrows(IllegalArgumentException.class, () -> BloscEncoder.compress(data, 4, 1, 0, 5, 6));
        assertThrows(IllegalArgumentException.class, () -> BloscEncoder.compress(data, 4, 1, 0, 5, -1));
    }

    /**
     * {@code compute_blocksize} and {@code split_block} per compressor. Each expected value is what
     * numcodecs 0.17's c-blosc wrote for the same settings.
     */
    @Test
    void blocksAreSizedPerCompressorAsCBloscSizesThem() {
        int mib = 1 << 20;
        int[][] automatic = { // compressor, clevel, type size, nbytes, c-blosc's block size
            {BloscEncoder.BLOSCLZ, 1, 1, mib, 65_536}, {BloscEncoder.BLOSCLZ, 4, 1, mib, 131_072},
            {BloscEncoder.BLOSCLZ, 9, 1, mib, 262_144}, {BloscEncoder.BLOSCLZ, 1, 4, mib, 65_536},
            {BloscEncoder.BLOSCLZ, 3, 4, mib, 262_144}, {BloscEncoder.BLOSCLZ, 5, 4, mib, 524_288},
            {BloscEncoder.BLOSCLZ, 6, 4, mib, mib}, {BloscEncoder.BLOSCLZ, 2, 8, mib, 262_144},
            {BloscEncoder.LZ4, 5, 4, mib, 524_288}, {BloscEncoder.LZ4HC, 1, 1, mib, 65_536},
            {BloscEncoder.LZ4HC, 3, 1, mib, 131_072}, {BloscEncoder.LZ4HC, 9, 1, mib, 262_144},
            {BloscEncoder.LZ4HC, 2, 4, mib, 262_144}, {BloscEncoder.ZLIB, 1, 8, mib, 262_144},
            {BloscEncoder.ZSTD, 6, 4, mib, 524_288}, {BloscEncoder.ZSTD, 9, 8, mib, mib},
            {BloscEncoder.BLOSCLZ, 0, 1, mib, 8_192}, {BloscEncoder.LZ4HC, 0, 1, mib, 16_384},
            {BloscEncoder.BLOSCLZ, 5, 15, 300_000, 300_000}, {BloscEncoder.BLOSCLZ, 5, 17, 300_000, 131_070},
            {BloscEncoder.LZ4, 5, 32, 300_000, 131_072}, {BloscEncoder.ZLIB, 5, 17, 300_000, 262_140},
            {BloscEncoder.LZ4, 5, 15, 1000, 990}, {BloscEncoder.LZ4, 5, 4, 3, 1}};
        for (int[] c : automatic) {
            assertEquals(c[4], BloscEncoder.blockSize(c[0], c[1], c[2], c[3], 0), Arrays.toString(c));
        }
        int[][] forced = { // compressor, type size, nbytes, requested, c-blosc's block size
            {BloscEncoder.BLOSCLZ, 1, mib, 100, 65_536}, {BloscEncoder.BLOSCLZ, 4, mib, 100, 128},
            {BloscEncoder.BLOSCLZ, 4, mib, 1000, 65_536}, {BloscEncoder.BLOSCLZ, 4, mib, 50_000, 200_000},
            {BloscEncoder.LZ4, 4, mib, 300_000, mib}, {BloscEncoder.LZ4, 16, 1000, 4096, 992},
            {BloscEncoder.LZ4, 16, mib, 50_000, 800_000}, {BloscEncoder.ZSTD, 1, mib, 100, 128},
            {BloscEncoder.ZSTD, 16, mib, 50_000, 50_000}};
        for (int[] c : forced) {
            assertEquals(c[4], BloscEncoder.blockSize(c[0], 5, c[1], c[2], c[3]), Arrays.toString(c));
        }
        // the split: at most 16-byte types, at least 128 elements a block, never zstd
        assertTrue(BloscEncoder.splitBlock(BloscEncoder.LZ4, 16, 2048));
        assertTrue(!BloscEncoder.splitBlock(BloscEncoder.LZ4, 16, 2032));
        assertTrue(!BloscEncoder.splitBlock(BloscEncoder.LZ4, 17, 1 << 20));
        assertTrue(!BloscEncoder.splitBlock(BloscEncoder.ZSTD, 4, 1 << 20));
        // a forced 4096-byte block of 16-byte elements was enlarged, then cut to the 1000-byte buffer: the
        // header's split flag follows the final size (62 elements: not split)
        byte[] small = ints(250);
        assertEquals(0x10, BloscEncoder.compress(small, 16, 1, 4096, 5, BloscEncoder.LZ4)[2] & 0x10);
    }

    /**
     * What c-blosc writes when it stores the data whole: the header still names the compressor, the
     * filter, and the split, and its block size is the computed one (measured: lz4, a 5000-byte random
     * buffer, byte shuffle, flags {@code 0x23}; blosclz at clevel 0, a 1 MiB buffer, 8 KiB blocks).
     */
    @Test
    void aStoredBufferKeepsTheRestOfItsHeader() {
        byte[] random = new byte[5000];
        new Random(2).nextBytes(random);
        byte[] buffer = BloscEncoder.compress(random, 1, BloscEncoder.SHUFFLE, 0, 5, BloscEncoder.LZ4);
        assertEquals(0x23, buffer[2] & 0xff);
        assertEquals(5016, buffer.length);
        byte[] stored = BloscEncoder.compress(ints(1 << 18), 1, BloscEncoder.NOSHUFFLE, 0, 0, BloscEncoder.BLOSCLZ);
        assertEquals(0x02, stored[2] & 0xff);
        assertEquals(8192, le32(stored, 8));
        byte[] tiny = BloscEncoder.compress(new byte[100], 4, BloscEncoder.SHUFFLE, 0, 5, BloscEncoder.LZ4);
        assertEquals(0x33, tiny[2] & 0xff, "under 128 bytes: stored, with the shuffle, no split, and lz4's format");
        assertArrayEquals(new byte[100], BloscDecoder.decompress(tiny));
    }

    @Test
    void bloscLzReachesFarAndLong() {
        Random random = new Random(4);
        // a far match (beyond the 8191 a short distance holds), a long one (many 255 length bytes), a run
        byte[] data = new byte[200_000];
        random.nextBytes(data);
        System.arraycopy(data, 0, data, 40_000, 20_000);
        System.arraycopy(data, 100, data, 70_000, 3_000);
        Arrays.fill(data, 120_000, 190_000, (byte) 7);
        for (int clevel = 1; clevel <= 9; clevel++) {
            for (boolean split : new boolean[] {true, false}) {
                byte[] out = new byte[data.length];
                int n = BloscLz.compress(clevel, data, 0, data.length, out, 0, out.length, split);
                assertTrue(n > 0 && n < data.length * 7 / 10, "clevel " + clevel + ": " + n);
                assertEquals(0x20, out[0] & 0xe0, "the first opcode carries BloscLZ's marker");
                int[] kinds = matchKinds(out, n);
                assertTrue(kinds[0] > 0 && kinds[1] > 0, "far and long matches at clevel " + clevel);
                byte[] back = new byte[data.length];
                BloscLz.decompress(out, 0, n, back, 0, back.length);
                assertArrayEquals(data, back, "clevel " + clevel + " split " + split);
                // a smaller output budget: the same stream, or nothing
                for (int cap : new int[] {n - 1, n, n + 1, 66}) {
                    byte[] capped = new byte[cap];
                    int m = BloscLz.compress(clevel, data, 0, data.length, capped, 0, cap, split);
                    assertTrue(m == 0 || (m <= cap && Arrays.equals(out, 0, n, capped, 0, m)), "cap " + cap);
                }
            }
        }
    }

    /** BloscLZ gives up as c-blosc's does: on random data, a block under 16 bytes, and under 66 bytes of room. */
    @Test
    void bloscLzGivesUpWhereCBloscDoes() {
        byte[] random = new byte[10_000];
        new Random(6).nextBytes(random);
        for (int clevel = 1; clevel <= 9; clevel++) {
            assertEquals(0, BloscLz.compress(clevel, random, 0, random.length, new byte[20_000], 0, 20_000, true));
        }
        byte[] zeros = new byte[1000];
        assertEquals(0, BloscLz.compress(5, zeros, 0, 15, new byte[100], 0, 100, true));
        assertEquals(0, BloscLz.compress(5, zeros, 0, 1000, new byte[65], 0, 65, true));
        assertTrue(BloscLz.compress(5, zeros, 0, 1000, new byte[66], 0, 66, true) > 0);
    }

    @Test
    void snappyStreamsRoundTrip() {
        Random random = new Random(8);
        byte[] text = new byte[150_000]; // more than two 64 KiB fragments
        String[] words = {"chunk ", "shard ", "zarr ", "falcon ", "metadata\n"};
        for (int i = 0; i < text.length; ) {
            byte[] w = words[random.nextInt(words.length)].getBytes();
            System.arraycopy(w, 0, text, i, Math.min(w.length, text.length - i));
            i += w.length;
        }
        byte[] noisy = new byte[5000]; // literal runs of 60 bytes and more
        random.nextBytes(noisy);
        byte[] far = new byte[60_000]; // copies with 2-byte offsets
        random.nextBytes(far);
        System.arraycopy(far, 0, far, 30_000, 10_000);
        for (byte[] data : new byte[][] {new byte[0], new byte[5], new byte[16], new byte[100_000], text, noisy, far}) {
            byte[] out = new byte[Snappy.maxCompressedLength(data.length)];
            int n = Snappy.compress(data, 0, data.length, out, 0, out.length);
            assertTrue(n > 0 && n <= out.length);
            byte[] back = new byte[data.length];
            Snappy.decompress(out, 0, n, back, 0, back.length);
            assertArrayEquals(data, back, data.length + " bytes");
            // snappy_compress refuses a buffer smaller than its bound, whatever the data
            assertEquals(0, Snappy.compress(data, 0, data.length, out, 0, out.length - 1));
        }
        byte[] zeros = new byte[100_000];
        byte[] out = new byte[Snappy.maxCompressedLength(zeros.length)];
        assertTrue(Snappy.compress(zeros, 0, zeros.length, out, 0, out.length) < 5000, "copies of 64 bytes");
    }

    /** Counts a BloscLZ stream's far matches (a 16-bit distance) and long ones (255s in the length). */
    private static int[] matchKinds(byte[] stream, int length) {
        int far = 0;
        int longOnes = 0;
        int ip = 0;
        int ctrl = stream[ip++] & 31;
        while (true) {
            if (ctrl >= 32) {
                if ((ctrl >>> 5) == 7) {
                    int code;
                    do {
                        code = stream[ip++] & 0xff;
                        longOnes += code == 255 ? 1 : 0;
                    } while (code == 255);
                }
                int code = stream[ip++] & 0xff;
                if (code == 255 && (ctrl & 31) == 31) {
                    far++;
                    ip += 2;
                }
            } else {
                ip += ctrl + 1;
            }
            if (ip >= length) {
                return new int[] {far, longOnes};
            }
            ctrl = stream[ip++] & 0xff;
        }
    }

    private static byte[] ints(int count) {
        ByteBuffer buf = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            buf.putInt(i / 3);
        }
        return buf.array();
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}
