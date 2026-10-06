package com.ebremer.falcon.core.compress.zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The format's limits on sequence tables (Zarr P1 I11), checked on hand-built frames, and the bit reader
 * the decoder now reads them with (PF6).
 *
 * <p>Each frame holds one compressed block with no literals and one sequence. Its tables are RLE
 * ({@code 0x01} mode: one symbol, no bits) or FSE-compressed with a single symbol, whose normalized-count
 * header {@code F5 7F} (accuracy log 10) or {@code F4 3F} (log 9) was written with libzstd's
 * {@code FSE_writeNCount} algorithm.
 */
class ZstdLimitsTest {

    private static final byte[] LOG_10 = {(byte) 0xF5, 0x7F};
    private static final byte[] LOG_9 = {(byte) 0xF4, 0x3F};

    /** {@link #frame(byte[])} of a block given as unsigned byte values. */
    private static byte[] frame(int... block) {
        byte[] bytes = new byte[block.length];
        for (int i = 0; i < block.length; i++) {
            bytes[i] = (byte) block[i];
        }
        return frame(bytes);
    }

    /** A single-segment frame declaring 200 bytes, holding {@code block} as its one compressed block. */
    private static byte[] frame(byte[] block) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD});
        out.write(0x20); // single segment, 1-byte content size
        out.write(200);
        int header = 1 | (2 << 1) | (block.length << 3); // last, compressed
        out.write(header);
        out.write(header >>> 8);
        out.write(header >>> 16);
        out.writeBytes(block);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static void assertRefused(byte[] frame, String expected) {
        CompressionFormatException e = assertThrows(CompressionFormatException.class,
                () -> ZstdDecoder.decompress(frame));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    /**
     * RFC 8878 4.1.1 caps the accuracy log at 9 for literal and match lengths and at 8 for offsets. Up to 15
     * was accepted (Zarr P1 I11). libzstd's own frames, which use these limits, still decode (ZstdDecoderTest).
     */
    @Test
    void accuracyLogsAboveTheFormatsLimitsAreRefused() {
        byte[] stream = {0x00, 0x08}; // 11 bits and the end marker
        // literal lengths FSE (log 10), offsets RLE code 1, match lengths RLE code 0
        assertRefused(frame(concat(new byte[] {0x00, 0x01, (byte) 0x94}, LOG_10, new byte[] {0x01, 0x00}, stream)),
                "accuracy log 10 exceeds the format's limit of 9");
        // offsets FSE (log 9), the others RLE
        assertRefused(frame(concat(new byte[] {0x00, 0x01, 0x64, 0x00}, LOG_9, new byte[] {0x00}, stream)),
                "accuracy log 9 exceeds the format's limit of 8");
        // match lengths FSE (log 10), the others RLE
        assertRefused(frame(concat(new byte[] {0x00, 0x01, 0x58, 0x00, 0x01}, LOG_10, stream)),
                "accuracy log 10 exceeds the format's limit of 9");
    }

    /**
     * Offset codes up to 31 are valid: a long-window frame (libzstd --long, window log 29 to 31) uses 29
     * and above. Codes above 28 were refused before P0's zstd fixes; these frames show 29 and 31 are read
     * through to the match, which then reaches before the (empty) output. A real code-29 and code-30 frame
     * (600 MB and 1.1 GB, made by libzstd with long-distance matching) was checked to decode exactly; code
     * 31 needs more than 2 GiB of output, which no Java array holds.
     */
    @Test
    void offsetCodesUpTo31AreRead() {
        // RLE tables: literal length code 0, offset code 29 or 31, match length code 0; the bitstream holds
        // exactly the offset's extra bits.
        assertRefused(frame(0x00, 0x01, 0x54, 0x00, 29, 0x00, 0x00, 0x00, 0x00, 0x20), "reaches before");
        assertRefused(frame(0x00, 0x01, 0x54, 0x00, 31, 0x00, 0x00, 0x00, 0x00, 0x80), "reaches before");
        assertRefused(frame(0x00, 0x01, 0x54, 0x00, 32, 0x00, 0x00, 0x00, 0x00, 0x80), "out of range");
    }

    /** The register-based bit reader agrees with reading one bit at a time, past the front included. */
    @Test
    void bitReaderMatchesReadingBitByBit() {
        Random random = new Random(42);
        for (int trial = 0; trial < 2000; trial++) {
            int length = 1 + random.nextInt(40);
            byte[] data = new byte[length + 6];
            random.nextBytes(data);
            int start = 3;
            if (data[start + length - 1] == 0) {
                data[start + length - 1] = 1;
            }
            ZstdBitReader reader = new ZstdBitReader(data, start, length);
            int highest = 31 - Integer.numberOfLeadingZeros(data[start + length - 1] & 0xff);
            int position = (length - 1) * 8 + highest - 1; // the reference: next bit to read
            while (position > -40) {
                int count = random.nextInt(32);
                int expected = 0;
                for (int i = 0; i < count; i++) {
                    int bit = position - i;
                    int b = bit < 0 ? 0 : (data[start + (bit >>> 3)] >>> (bit & 7)) & 1;
                    expected = (expected << 1) | b;
                }
                if (random.nextBoolean()) {
                    assertEquals(expected, reader.peekBits(count));
                    reader.skipBits(count);
                } else {
                    assertEquals(expected, reader.readBits(count));
                }
                position -= count;
                assertEquals(position == -1, reader.finished());
                assertEquals(position < -1, reader.overflowed());
            }
        }
    }
}
