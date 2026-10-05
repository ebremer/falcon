package com.ebremer.falcon.core.compress.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The from-scratch Zstandard encoder must produce frames the decoder reads back exactly (and, checked
 * separately by {@code tools/fixtures/check_zstd_encoder.py}, frames libzstd reads too).
 */
class ZstdEncoderTest {

    private static void roundTrip(String name, byte[] data) {
        byte[] frame = ZstdEncoder.compress(data);
        byte[] decoded = ZstdDecoder.decompress(frame);
        assertArrayEquals(data, decoded, name + " (" + data.length + " bytes -> " + frame.length + ")");
    }

    @Test
    void roundTripsEdgeCases() {
        roundTrip("empty", new byte[0]);
        roundTrip("one", new byte[] {42});
        roundTrip("two", new byte[] {1, 2});
        roundTrip("short", "hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsRepetitiveData() {
        roundTrip("zeros", new byte[10000]);
        roundTrip("same", "Z".repeat(8192).getBytes(StandardCharsets.UTF_8));
        roundTrip("pattern", "abcdefgh".repeat(4000).getBytes(StandardCharsets.UTF_8));
        roundTrip("text", ("the quick brown fox jumps over the lazy dog. ".repeat(500))
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void repetitiveDataActuallyShrinks() {
        byte[] data = "abcdefgh".repeat(4000).getBytes(StandardCharsets.UTF_8); // 32000 bytes
        byte[] frame = ZstdEncoder.compress(data);
        assertTrue(frame.length < data.length / 4,
                "expected real compression, got " + frame.length + " from " + data.length);
        assertArrayEquals(data, ZstdDecoder.decompress(frame));
    }

    @Test
    void roundTripsNumericLikeChunks() {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4000 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4000; i++) {
            buf.putInt(i % 1000);
        }
        roundTrip("int32-ramp", buf.array());

        java.nio.ByteBuffer f = java.nio.ByteBuffer.allocate(2000 * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 2000; i++) {
            f.putDouble(i * 0.5);
        }
        roundTrip("float64-ramp", f.array());
    }

    @Test
    void roundTripsRandomData() {
        Random random = new Random(20260720L);
        for (int trial = 0; trial < 50; trial++) {
            byte[] data = new byte[random.nextInt(5000)];
            random.nextBytes(data);
            roundTrip("random#" + trial, data);
        }
    }

    @Test
    void roundTripsMultiBlockInput() {
        // larger than one 64 KiB block, with structure so it both matches and has literals
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 31 + (i / 97)) & 0xff);
        }
        roundTrip("multiblock", data);
    }

    @Test
    void roundTripsMixedMatchesAndLiterals() {
        Random random = new Random(7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append((char) ('a' + random.nextInt(4)));
            if (i % 5 == 0) {
                sb.append("COMMON_SUBSTRING");
            }
        }
        roundTrip("mixed", sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
