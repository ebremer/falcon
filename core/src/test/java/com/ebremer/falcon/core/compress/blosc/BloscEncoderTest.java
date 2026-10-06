package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The from-scratch Blosc encoder must produce buffers the decoder reads back exactly (and, checked
 * separately by {@code tools/fixtures/check_blosc_encoder.py}, buffers c-blosc reads too).
 */
class BloscEncoderTest {

    private static void roundTrip(String name, byte[] data, int typeSize) {
        byte[] buffer = BloscEncoder.compress(data, typeSize);
        assertArrayEquals(data, BloscDecoder.decompress(buffer),
                name + " (" + data.length + " bytes -> " + buffer.length + ")");
    }

    @Test
    void roundTripsEdgeCases() {
        roundTrip("empty", new byte[0], 1);
        roundTrip("one", new byte[] {7}, 1);
        roundTrip("small", "hello".getBytes(), 1);
    }

    @Test
    void roundTripsWithByteShuffle() {
        ByteBuffer buf = ByteBuffer.allocate(2000 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 2000; i++) {
            buf.putInt(i % 500);
        }
        roundTrip("int32-shuffled", buf.array(), 4);

        ByteBuffer f = ByteBuffer.allocate(1000 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 1000; i++) {
            f.putDouble(i * 0.25);
        }
        roundTrip("float64-shuffled", f.array(), 8);
    }

    @Test
    void shuffledNumericDataShrinks() {
        ByteBuffer buf = ByteBuffer.allocate(4000 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4000; i++) {
            buf.putInt(i % 256);
        }
        byte[] buffer = BloscEncoder.compress(buf.array(), 4);
        assertTrue(buffer.length < buf.array().length / 2,
                "expected compression, got " + buffer.length + " from " + buf.array().length);
        assertArrayEquals(buf.array(), BloscDecoder.decompress(buffer));
    }

    @Test
    void incompressibleDataFallsBackToMemcpy() {
        Random random = new Random(3);
        byte[] data = new byte[2000];
        random.nextBytes(data);
        byte[] buffer = BloscEncoder.compress(data, 1);
        assertTrue((buffer[2] & 0x02) != 0, "random data should take the memcpy path");
        assertArrayEquals(data, BloscDecoder.decompress(buffer));
    }

    @Test
    void roundTripsVariousTypeSizes() {
        Random random = new Random(11);
        for (int ts : new int[] {1, 2, 4, 8, 16}) {
            byte[] data = new byte[ts * 300];
            // repetitive so shuffle+zstd engages
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) ((i / ts) % 4);
            }
            roundTrip("ts" + ts, data, ts);
            byte[] rnd = new byte[ts * 100];
            random.nextBytes(rnd);
            roundTrip("ts" + ts + "-random", rnd, ts);
        }
    }

    /**
     * The header stores the type size in one byte. A larger type size was written modulo 256 while the data
     * was shuffled with the full size, so 256 read back as 0 and 300 as 44: wrong bytes. Like c-blosc, the
     * encoder now writes 1 and does not shuffle.
     */
    @Test
    void typeSizesAbove255AreWrittenAsOne() {
        for (int ts : new int[] {255, 256, 300, 1000}) {
            byte[] data = new byte[ts * 40];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) ((i % ts) * 7 + i / ts); // compressible, and different in every element byte
            }
            byte[] buffer = BloscEncoder.compress(data, ts);
            assertEquals(ts > 255 ? 1 : ts, buffer[3] & 0xff, "header type size for " + ts);
            assertEquals(ts > 255 ? 0 : 1, buffer[2] & 0x01, "shuffle flag for " + ts);
            assertArrayEquals(data, BloscDecoder.decompress(buffer), "ts" + ts);
            byte[] empty = BloscEncoder.compress(new byte[0], ts);
            assertEquals(ts > 255 ? 1 : ts, empty[3] & 0xff, "empty buffer's type size for " + ts);
        }
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    /**
     * The encoder wrote one block per buffer, and c-blosc refuses blocks over about 715 MB (Zarr P1 I8). It
     * now sizes blocks as c-blosc's compute_blocksize does for zstd. Each expected value is what c-blosc
     * 1.21.7 (numcodecs 0.17) writes for the same type size, length, clevel, and forced size.
     */
    @Test
    void blocksAreSizedAsCBloscSizesThem() {
        int[][] automatic = { // clevel, type size, nbytes, c-blosc's block size
            {5, 4, 1000, 1000}, {5, 4, 100_000, 100_000}, {5, 4, 1 << 20, 262_144}, {5, 17, 1_048_560, 262_140},
            {5, 255, 2_999_820, 262_140}, {1, 4, 3_000_000, 32_768}, {1, 255, 1_048_560, 32_640},
            {3, 17, 2_999_990, 131_070}, {9, 4, 20_000_000, 1_048_576}, {9, 17, 1_048_560, 1_048_560}};
        for (int[] c : automatic) {
            assertEquals(c[3], BloscEncoder.blockSize(c[0], c[1], c[2], 0), java.util.Arrays.toString(c));
        }
        int[][] forced = { // type size, nbytes, requested, c-blosc's block size
            {4, 1 << 20, 100, 128}, {17, 17_000, 1000, 986}, {4, 4000, 100_000, 4000}, {4, 1 << 22, 1 << 20, 1 << 20},
            {8, 1 << 20, 65_537, 65_536}};
        for (int[] c : forced) {
            assertEquals(c[3], BloscEncoder.blockSize(5, c[0], c[1], c[2]), java.util.Arrays.toString(c));
        }

        // A 1 MiB buffer is four 256 KiB blocks, not one.
        ByteBuffer buf = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < (1 << 18); i++) {
            buf.putInt(i % 1000);
        }
        byte[] buffer = BloscEncoder.compress(buf.array(), 4);
        assertEquals(262_144, le32(buffer, 8));
        assertEquals(le32(buffer, 12), buffer.length);
        assertArrayEquals(buf.array(), BloscDecoder.decompress(buffer));
    }

    /** The options Zarr's blosc codec configuration names: the shuffle, the block size, and clevel (I9). */
    @Test
    void roundTripsEveryShuffleModeBlockSizeAndLevel() {
        Random random = new Random(5);
        for (int ts : new int[] {1, 2, 4, 8, 12}) {
            byte[] data = new byte[ts * 5000 + 3]; // a partial element at the end, too
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % ts == 0 ? random.nextInt(4) : i / ts / 7);
            }
            for (int shuffle = BloscEncoder.NOSHUFFLE; shuffle <= BloscEncoder.BITSHUFFLE; shuffle++) {
                for (int blockSize : new int[] {0, 128, 1000, 8192}) {
                    for (int clevel : new int[] {0, 1, 5, 9}) {
                        byte[] buffer = BloscEncoder.compress(data, ts, shuffle, blockSize, clevel);
                        String what = "ts " + ts + " shuffle " + shuffle + " block " + blockSize + " clevel " + clevel;
                        assertArrayEquals(data, BloscDecoder.decompress(buffer), what);
                        if (clevel == 0) {
                            assertEquals(0x02, buffer[2] & 0x02, what + ": clevel 0 stores the data");
                        }
                    }
                }
            }
            // A whole number of elements keeps the bit shuffle; a partial one switches to the byte shuffle,
            // whose trailing bytes c-blosc restores (it cannot restore a bit-shuffled block's).
            byte[] whole = java.util.Arrays.copyOf(data, ts * 5000);
            assertEquals(0x04, BloscEncoder.compress(whole, ts, BloscEncoder.BITSHUFFLE, 0)[2] & 0x05);
            if (ts > 1) {
                assertEquals(0x01, BloscEncoder.compress(data, ts, BloscEncoder.BITSHUFFLE, 0)[2] & 0x05);
            }
            assertEquals(0x00, BloscEncoder.compress(whole, ts, BloscEncoder.NOSHUFFLE, 0)[2] & 0x05);
        }
        assertThrows(IllegalArgumentException.class, () -> BloscEncoder.compress(new byte[8], 4, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> BloscEncoder.compress(new byte[8], 4, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> BloscEncoder.compress(new byte[8], 4, 1, 0, 10));
    }

    /**
     * clevel sets the zstd level inside Blosc as c-blosc 1.x sets it: {@code 2 * clevel - 1}, and zstd's
     * highest for 9. Measured against numcodecs 0.17's c-blosc, whose zstd frames for clevels 1 and 3 to 9
     * are libzstd's at exactly those levels. Before F12 Falcon's encoder had one level.
     */
    @Test
    void clevelSetsTheZstdLevelAsCBloscDoes() {
        int[] expected = {-1, 1, 3, 5, 7, 9, 11, 13, 15, 22};
        for (int clevel = 1; clevel <= 9; clevel++) {
            assertEquals(expected[clevel], BloscEncoder.zstdLevel(clevel), "clevel " + clevel);
        }
        StringBuilder sb = new StringBuilder();
        Random random = new Random(4);
        while (sb.length() < 60_000) {
            sb.append("chunk").append(random.nextInt(500)).append(random.nextBoolean() ? " shard " : " array ");
        }
        byte[] data = sb.toString().getBytes(StandardCharsets.US_ASCII);
        for (int clevel : new int[] {1, 5, 9}) {
            // One block, not shuffled: the stream at byte 24 is the zstd frame of the data itself.
            byte[] buffer = BloscEncoder.compress(data, 1, BloscEncoder.NOSHUFFLE, data.length, clevel);
            byte[] frame = Arrays.copyOfRange(buffer, 24, 24 + le32(buffer, 20));
            assertArrayEquals(ZstdEncoder.compress(data, BloscEncoder.zstdLevel(clevel), false), frame,
                    "clevel " + clevel);
            assertArrayEquals(data, BloscDecoder.decompress(buffer));
        }
    }
}
