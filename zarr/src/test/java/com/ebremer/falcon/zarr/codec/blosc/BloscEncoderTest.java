package com.ebremer.falcon.zarr.codec.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
}
