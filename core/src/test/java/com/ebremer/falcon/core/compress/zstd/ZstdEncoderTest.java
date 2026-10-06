package com.ebremer.falcon.core.compress.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;

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

    private static long le(byte[] b, int off, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xff);
        }
        return v;
    }

    /**
     * Every frame was single-segment, so its window was its whole content, and libzstd's streaming API
     * refused frames over 128 MiB ("Frame requires too much memory"; Zarr P1 I8). A frame larger than
     * 128 KiB now declares a 128 KiB window and keeps its content size; zstandard's streaming decoder then
     * reads a 150 MB frame. Smaller frames are unchanged.
     */
    @Test
    void largeFramesDeclareA128KiBWindow() {
        byte[] small = new byte[1 << 17];
        byte[] frame = ZstdEncoder.compress(small);
        assertEquals(0xE0, frame[4] & 0xff); // single segment, 8-byte content size
        assertEquals(1 << 17, le(frame, 5, 8));

        byte[] large = new byte[(1 << 17) + 1];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i / 300);
        }
        frame = ZstdEncoder.compress(large);
        assertEquals(0xC0, frame[4] & 0xff); // 8-byte content size, not single segment
        assertEquals(0x38, frame[5] & 0xff); // window descriptor: 2^(10 + 7) = 128 KiB
        assertEquals(large.length, le(frame, 6, 8));
        assertArrayEquals(large, ZstdDecoder.decompress(frame));
    }

    /** The content checksum the zstd codec's {@code checksum} option asks for (Zarr P1 I9). */
    @Test
    void writesTheContentChecksumOnRequest() {
        byte[] data = "the quick brown fox jumps over the lazy dog".repeat(200).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(ZstdEncoder.compress(data), ZstdEncoder.compress(data, false));
        byte[] frame = ZstdEncoder.compress(data, true);
        assertEquals(0x04, frame[4] & 0x04);
        assertEquals(Xxh64.hash(data, 0, data.length, 0) & 0xffffffffL, le(frame, frame.length - 4, 4));
        assertArrayEquals(data, ZstdDecoder.decompress(frame));
        frame[frame.length - 1] ^= 1;
        byte[] corrupt = frame;
        assertThrows(CompressionFormatException.class, () -> ZstdDecoder.decompress(corrupt));
        // An empty frame with a checksum is byte for byte what libzstd writes.
        assertArrayEquals(new byte[] {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x24, 0x00, 0x01, 0x00, 0x00,
                (byte) 0x99, (byte) 0xE9, (byte) 0xD8, 0x51}, ZstdEncoder.compress(new byte[0], true));
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
