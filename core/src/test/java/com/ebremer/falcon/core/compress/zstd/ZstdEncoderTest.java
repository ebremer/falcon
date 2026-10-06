package com.ebremer.falcon.core.compress.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
     * refused frames over 128 MiB ("Frame requires too much memory"; Zarr P1 I8). A frame larger than its
     * level's window now declares that window (512 KiB at level 1, 2 MiB at the default level) and keeps
     * its content size; a smaller one stays single-segment, as libzstd writes it.
     */
    @Test
    void framesLargerThanTheWindowDeclareIt() {
        assertEquals(1 << 19, ZstdEncoder.windowSize(1));
        assertEquals(1 << 21, ZstdEncoder.windowSize(ZstdEncoder.DEFAULT_LEVEL));
        assertEquals(1 << 21, ZstdEncoder.windowSize(0));
        assertEquals(1 << 23, ZstdEncoder.windowSize(22));

        byte[] small = new byte[1 << 19];
        byte[] frame = ZstdEncoder.compress(small, 1, false);
        assertEquals(0xE0, frame[4] & 0xff); // single segment, 8-byte content size
        assertEquals(1 << 19, le(frame, 5, 8));

        byte[] large = new byte[(1 << 19) + 1];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i / 300);
        }
        frame = ZstdEncoder.compress(large, 1, false);
        assertEquals(0xC0, frame[4] & 0xff); // 8-byte content size, not single segment
        assertEquals(0x48, frame[5] & 0xff); // window descriptor: 2^(10 + 9) = 512 KiB
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

    // ---- F12: levels, Huffman literals, fitted tables, repeat offsets, block splitting -------------------

    /** Inputs every level must round-trip: the edge sizes, every block layout, and each kind of data. */
    private static List<byte[]> corpus() {
        List<byte[]> inputs = new ArrayList<>();
        inputs.add(new byte[0]);
        inputs.add(new byte[] {7});
        inputs.add("hello, world".getBytes(StandardCharsets.UTF_8));
        Random random = new Random(12);
        byte[] r = new byte[5000];
        random.nextBytes(r);
        inputs.add(r);
        inputs.add(new byte[1 << 17]);                            // exactly one 128 KiB block, all zeros
        inputs.add(text(1 << 17));                                // exactly 128 KiB
        inputs.add(text((1 << 17) + 1));
        inputs.add(text(700_000));                                // several blocks, past level 1's window
        inputs.add(noisyFloats(100_000));                         // drifting statistics: split blocks
        ByteBuffer smooth = ByteBuffer.allocate(80_000 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 80_000; i++) {
            smooth.putDouble(Math.sin(i * 0.001) * 1000);
        }
        inputs.add(smooth.array());
        byte[] structured = new byte[100_000];
        for (int i = 0; i < structured.length; i++) {
            structured[i] = (byte) ((i % 40 < 12) ? i / 40 : i % 7); // records with a varying field
        }
        inputs.add(structured);
        return inputs;
    }

    /** Text-like bytes: words from a small vocabulary, so matches, literals, and repeats all occur. */
    static byte[] text(int size) {
        String[] words = {"zarr", "chunk", "shard", "array", "the", "of", "falcon", "codec", "index", "value",
            "public", "static", "final", "return", "int", "byte[]", "{", "}", ";", "\n    "};
        Random random = new Random(size);
        StringBuilder sb = new StringBuilder(size + 16);
        while (sb.length() < size) {
            sb.append(words[random.nextInt(words.length)]).append(random.nextInt(9) == 0 ? ".\n" : " ");
        }
        return sb.substring(0, size).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** float32 values of a slow sine plus Gaussian noise: no long matches, but skewed high bytes. */
    static byte[] noisyFloats(int count) {
        Random random = new Random(3);
        ByteBuffer b = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            b.putFloat((float) (Math.sin(i * 0.001) * 100 + random.nextGaussian()));
        }
        return b.array();
    }

    @Test
    void everyLevelRoundTrips() {
        int[] levels = {-131072, -5, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 99};
        for (byte[] input : corpus()) {
            for (int level : levels) {
                byte[] frame = ZstdEncoder.compress(input, level, level % 2 == 0);
                assertArrayEquals(input, ZstdDecoder.decompress(frame),
                        "level " + level + ", " + input.length + " bytes");
            }
        }
    }

    /**
     * Thousands of seeded inputs, each built from random pieces (fresh bytes, copies of earlier stretches
     * near and far, runs, small alphabets) so every coding path is reached: Huffman and raw and RLE
     * literals, every table mode, repeat offsets with and without literals, raw and RLE blocks.
     */
    @Test
    void seededRandomInputsRoundTrip() {
        Random random = new Random(20261006L);
        for (int trial = 0; trial < 3000; trial++) {
            int size = trial % 50 == 0 ? random.nextInt(400_000) : random.nextInt(6000);
            byte[] data = new byte[size];
            int pos = 0;
            int alphabet = 1 + random.nextInt(random.nextBoolean() ? 4 : 256);
            while (pos < size) {
                int piece = Math.min(size - pos, 1 + random.nextInt(random.nextInt(8) == 0 ? 3000 : 60));
                switch (random.nextInt(5)) {
                    case 0 -> {
                        for (int i = 0; i < piece; i++) {
                            data[pos + i] = (byte) random.nextInt(alphabet);
                        }
                    }
                    case 1, 2 -> {
                        if (pos > 0) { // a copy of an earlier stretch, maybe overlapping itself
                            int back = 1 + random.nextInt(Math.min(pos, random.nextBoolean() ? 16 : 200_000));
                            for (int i = 0; i < piece; i++) {
                                data[pos + i] = data[pos + i - back];
                            }
                        }
                    }
                    case 3 -> java.util.Arrays.fill(data, pos, pos + piece, (byte) random.nextInt(256));
                    default -> {
                        for (int i = 0; i < piece; i++) {
                            data[pos + i] = (byte) random.nextInt(256);
                        }
                    }
                }
                pos += piece;
            }
            int level = random.nextInt(25) - 2;
            byte[] frame = ZstdEncoder.compress(data, level, random.nextBoolean());
            assertArrayEquals(data, ZstdDecoder.decompress(frame), "trial " + trial + " level " + level);
        }
    }

    /**
     * Noisy floats have no matches worth taking, but their bytes are skewed: Huffman-coded literals
     * compress them, where raw literals stored them whole (ratio 1.000 before F12; libzstd's level 3
     * gets 0.883 on this data).
     */
    @Test
    void literalsAreHuffmanCoded() {
        byte[] floats = noisyFloats(1 << 18);
        byte[] frame = ZstdEncoder.compress(floats);
        assertTrue(frame.length < floats.length * 0.9, "ratio " + frame.length / (double) floats.length);
        byte[] text = text(50_000);
        frame = ZstdEncoder.compress(text);
        assertEquals(2, firstBlock(frame).literalsType, "the first block's literals are Huffman-coded");
    }

    @Test
    void higherLevelsCompressBetter() {
        byte[] text = text(600_000);
        int one = ZstdEncoder.compress(text, 1, false).length;
        int three = ZstdEncoder.compress(text, 3, false).length;
        int nineteen = ZstdEncoder.compress(text, 19, false).length;
        assertTrue(nineteen < three && three < one, one + " / " + three + " / " + nineteen);
        assertArrayEquals(ZstdEncoder.compress(text, 3, false), ZstdEncoder.compress(text, 0, false));
        assertArrayEquals(ZstdEncoder.compress(text), ZstdEncoder.compress(text, 3, false));
    }

    /** A block's sequence tables are built from its statistics (mode 2), not only the predefined ones. */
    @Test
    void sequenceTablesAreFittedToTheBlock() {
        Block block = firstBlock(ZstdEncoder.compress(text(100_000)));
        assertTrue(block.sequences > 100, block.sequences + " sequences");
        boolean anyFitted = ((block.modes >>> 6) & 3) == 2 || ((block.modes >>> 4) & 3) == 2
                || ((block.modes >>> 2) & 3) == 2;
        assertTrue(anyFitted, "compression modes " + Integer.toBinaryString(block.modes));
    }

    /**
     * Records whose fields repeat at a fixed distance code their offsets as repeat codes, which only cost
     * their FSE symbol: such data compresses far better than the same bytes without the structure.
     */
    @Test
    void repeatOffsetsKeepStridedDataSmall() {
        Random random = new Random(5);
        byte[] records = new byte[200_000];
        for (int i = 0; i < records.length; i += 20) {
            byte[] id = new byte[4];
            random.nextBytes(id);
            System.arraycopy(id, 0, records, i, 4);
            for (int j = 4; j < 20 && i + j < records.length; j++) {
                records[i + j] = (byte) j; // the same 16 bytes in every record: a repeat at offset 20
            }
        }
        byte[] frame = ZstdEncoder.compress(records);
        assertArrayEquals(records, ZstdDecoder.decompress(frame));
        // Each record's 4 random bytes cannot shrink (40 KB); the rest costs a little per record.
        assertTrue(frame.length < 52_000, frame.length + " bytes");
    }

    /**
     * Data whose byte statistics drift is cut into blocks at multiples of 8 KiB, so each gets its own
     * literal code (as libzstd's block splitter does); data whose statistics hold keeps 128 KiB blocks.
     */
    @Test
    void blocksSplitWhereStatisticsDrift() {
        byte[] floats = noisyFloats(1 << 18);
        List<Integer> floatBlocks = ZstdEncoder.blockSizes(floats);
        assertTrue(floatBlocks.size() > 8, floatBlocks.size() + " blocks for 1 MiB of drifting floats");
        for (int size : floatBlocks.subList(0, floatBlocks.size() - 1)) {
            assertEquals(0, size % 8192, "a split block is a multiple of 8 KiB: " + size);
        }
        assertEquals(floatBlocks.size(), blockCount(ZstdEncoder.compress(floats)));
        byte[] text = text(1 << 19);
        assertEquals(List.of(1 << 17, 1 << 17, 1 << 17, 1 << 17), ZstdEncoder.blockSizes(text));
        assertEquals(4, blockCount(ZstdEncoder.compress(text)));
    }

    // ---- frame parsing for the tests above ------------------------------------------------------------

    private record Block(int type, int literalsType, int sequences, int modes) {
    }

    /** The number of blocks in a single-segment frame with an 8-byte content size. */
    private static int blockCount(byte[] frame) {
        assertEquals(0xE0, frame[4] & 0xe0, "single segment, 8-byte content size");
        int p = 13;
        int blocks = 0;
        while (true) {
            int header = (int) le(frame, p, 3);
            p += 3;
            int type = (header >>> 1) & 3;
            p += type == 1 ? 1 : header >>> 3;
            blocks++;
            if ((header & 1) != 0) {
                return blocks;
            }
        }
    }

    private static int literalsRegenerated(byte[] b, int p) {
        int h = b[p] & 0xff;
        int type = h & 3;
        int format = (h >>> 2) & 3;
        if (type <= 1) {
            return switch (format) {
                case 0, 2 -> h >>> 3;
                case 1 -> (h >>> 4) | ((b[p + 1] & 0xff) << 4);
                default -> (h >>> 4) | ((b[p + 1] & 0xff) << 4) | ((b[p + 2] & 0xff) << 12);
            };
        }
        long v = (h >>> 4) | ((long) (b[p + 1] & 0xff) << 4) | ((long) (b[p + 2] & 0xff) << 12)
                | ((long) (b[p + 3] & 0xff) << 20) | ((long) (b[p + 4] & 0xff) << 28);
        return (int) switch (format) {
            case 0, 1 -> v & 0x3FF;
            case 2 -> v & 0x3FFF;
            default -> v & 0x3FFFF;
        };
    }

    /** The first block of a single-segment frame with an 8-byte content size. */
    private static Block firstBlock(byte[] frame) {
        int p = 13;
        int header = (int) le(frame, p, 3);
        p += 3;
        int type = (header >>> 1) & 3;
        if (type != 2) {
            return new Block(type, -1, 0, 0);
        }
        int h = frame[p] & 0xff;
        int literalsType = h & 3;
        int format = (h >>> 2) & 3;
        int headerSize;
        int literalsSize;
        if (literalsType <= 1) {
            headerSize = format == 0 || format == 2 ? 1 : format == 1 ? 2 : 3;
            int regenerated = literalsRegenerated(frame, p);
            literalsSize = literalsType == 0 ? regenerated : 1;
        } else {
            headerSize = format <= 1 ? 3 : format == 2 ? 4 : 5;
            long v = 0;
            for (int i = headerSize - 1; i >= 0; i--) {
                v = (v << 8) | (frame[p + i] & 0xff);
            }
            int bits = format <= 1 ? 10 : format == 2 ? 14 : 18;
            literalsSize = (int) ((v >>> (4 + bits)) & ((1L << bits) - 1));
        }
        p += headerSize + literalsSize;
        int first = frame[p] & 0xff;
        int sequences;
        if (first < 128) {
            sequences = first;
            p += 1;
        } else if (first < 255) {
            sequences = ((first - 128) << 8) + (frame[p + 1] & 0xff);
            p += 2;
        } else {
            sequences = (frame[p + 1] & 0xff) + ((frame[p + 2] & 0xff) << 8) + 0x7F00;
            p += 3;
        }
        return new Block(type, literalsType, sequences, sequences == 0 ? 0 : frame[p] & 0xff);
    }
}
