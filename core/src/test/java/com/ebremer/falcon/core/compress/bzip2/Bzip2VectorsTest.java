package com.ebremer.falcon.core.compress.bzip2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.Vectors;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * bzip2 against libbzip2 1.0.8 (tools/fixtures/gen_bzip2_vectors.py, through Python's bz2 module): Falcon's
 * encoder writes libbzip2's stream byte for byte at every block size the vectors use, and its decoder reads
 * libbzip2's streams. Each vector's input comes from a recipe {@link #make} repeats.
 */
class Bzip2VectorsTest {

    private static final List<String[]> VECTORS = Vectors.read("bzip2_vectors.txt");

    @Test
    void encoderWritesLibbzip2sStreams() {
        assertEquals(53, VECTORS.size());
        for (String[] v : VECTORS) {
            byte[] data = make(v[1], Integer.parseInt(v[2]), Integer.parseInt(v[3]));
            int level = Integer.parseInt(v[4]);
            byte[] stream = Bzip2Encoder.compress(data, level);
            String what = v[0] + " -" + level;
            assertEquals(Integer.parseInt(v[5]), stream.length, what + " length");
            assertEquals(v[6], sha256(stream), what + " SHA-256");
            if (!v[7].equals("-")) {
                assertArrayEquals(Vectors.hex(v[7]), stream, what);
            }
        }
    }

    @Test
    void decoderReadsLibbzip2sStreams() {
        int decoded = 0;
        for (String[] v : VECTORS) {
            if (v[7].equals("-")) {
                continue;
            }
            byte[] data = make(v[1], Integer.parseInt(v[2]), Integer.parseInt(v[3]));
            byte[] stream = Vectors.hex(v[7]);
            assertArrayEquals(data, Bzip2Decoder.decompress(stream, 0, stream.length, data.length), v[0]);
            if (data.length > 0) {
                assertThrows(CompressionFormatException.class,
                        () -> Bzip2Decoder.decompress(stream, 0, stream.length, data.length - 1), v[0] + " bound");
            }
            decoded++;
        }
        assertEquals(34, decoded);
    }

    @Test
    void largeVectorsRoundTrip() {
        for (String[] v : VECTORS) {
            if (!v[7].equals("-")) {
                continue;
            }
            byte[] data = make(v[1], Integer.parseInt(v[2]), Integer.parseInt(v[3]));
            byte[] stream = Bzip2Encoder.compress(data, Integer.parseInt(v[4]));
            assertArrayEquals(data, Bzip2Decoder.decompress(stream, 0, stream.length, data.length), v[0]);
        }
    }

    @Test
    void randomInputsRoundTrip() {
        Random random = new Random(307);
        for (int trial = 0; trial < 300; trial++) {
            int size = trial < 200 ? random.nextInt(3000) : random.nextInt(250_000);
            byte[] data = new byte[size];
            int alphabet = 1 + random.nextInt(256);
            for (int i = 0; i < size; i++) {
                data[i] = random.nextInt(4) == 0 && i > 0 ? data[i - 1] : (byte) random.nextInt(alphabet);
            }
            int level = 1 + random.nextInt(9);
            byte[] stream = Bzip2Encoder.compress(data, level);
            assertArrayEquals(data, Bzip2Decoder.decompress(stream, 0, stream.length, size), "trial " + trial);
        }
    }

    @Test
    void randomisedBlocksAreRead() {
        // bzip2 before 0.9.5 flipped bits of a block before sorting it; libbzip2 still reads such blocks.
        for (String recipe : new String[] {"text", "runs", "random", "zeros"}) {
            byte[] data = make(recipe, 120_000, 5);
            byte[] stream = Bzip2Encoder.compressRandomised(data, 1);
            assertArrayEquals(data, Bzip2Decoder.decompress(stream, 0, stream.length, data.length), recipe);
        }
    }

    @Test
    void bytesAfterTheStreamAreIgnored() {
        byte[] data = "falcon falcon falcon falcon".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = Bzip2Encoder.compress(data, 9);
        byte[] padded = Arrays.copyOf(stream, stream.length + 40);
        Arrays.fill(padded, stream.length, padded.length, (byte) 0x5a);
        assertArrayEquals(data, Bzip2Decoder.decompress(padded, 0, padded.length, 100));
        // From an offset, within a range.
        byte[] inside = new byte[stream.length + 10];
        System.arraycopy(stream, 0, inside, 5, stream.length);
        assertArrayEquals(data, Bzip2Decoder.decompress(inside, 5, stream.length, 100));
        // The encoder reads a range too.
        byte[] around = new byte[data.length + 7];
        System.arraycopy(data, 0, around, 3, data.length);
        assertArrayEquals(stream, Bzip2Encoder.compress(around, 3, data.length, 9));
    }

    /**
     * Streams that follow one another, and bytes after a stream, read as Python's {@code bz2.decompress} reads
     * them (bzip2_concat_vectors.txt): every stream's output joined, invalid bytes after the first stream
     * ignored, a stream cut short refused.
     */
    @Test
    void concatenatedStreamsReadAsPythonReadsThem() {
        List<String[]> vectors = Vectors.read("bzip2_concat_vectors.txt");
        assertEquals(23, vectors.size());
        int refused = 0;
        for (String[] v : vectors) {
            byte[] input = Vectors.hex(v[1].substring(1));
            if (v[2].equals("error")) {
                assertThrows(CompressionFormatException.class,
                        () -> Bzip2Decoder.decompressConcatenated(input, 0, input.length, 1 << 20), v[0]);
                refused++;
                continue;
            }
            String[] result = v[2].split("/");
            int size = Integer.parseInt(result[0]);
            // A generous bound: a stream that is ignored for a bad CRC is decoded first, against the bound.
            byte[] out = Bzip2Decoder.decompressConcatenated(input, 0, input.length, 1 << 20);
            assertEquals(size, out.length, v[0]);
            assertEquals(result[1], sha256(out), v[0]);
            if (size > 0) {
                assertThrows(CompressionFormatException.class,
                        () -> Bzip2Decoder.decompressConcatenated(input, 0, input.length, size - 1), v[0] + " bound");
            }
            // within a range of a larger array
            byte[] around = new byte[input.length + 9];
            System.arraycopy(input, 0, around, 4, input.length);
            assertArrayEquals(out, Bzip2Decoder.decompressConcatenated(around, 4, input.length, 1 << 20), v[0] + " range");
        }
        assertEquals(8, refused);
    }

    @Test
    void oneStreamIsReadAloneAndBoundsHoldAcrossStreams() {
        byte[] a = "falcon falcon falcon".getBytes(StandardCharsets.US_ASCII);
        byte[] b = make("runs", 3000, 4);
        byte[] sa = Bzip2Encoder.compress(a, 1);
        byte[] sb = Bzip2Encoder.compress(b, 9);
        byte[] both = Arrays.copyOf(sa, sa.length + sb.length);
        System.arraycopy(sb, 0, both, sa.length, sb.length);
        // decompress reads the first stream only; decompressConcatenated both
        assertArrayEquals(a, Bzip2Decoder.decompress(both, 0, both.length, 1 << 16));
        byte[] joined = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        assertArrayEquals(joined, Bzip2Decoder.decompressConcatenated(both, 0, both.length, joined.length));
        // the bound is on the streams together: a second stream past it is refused, not ignored, even one that
        // would turn out to be corrupt (Python, unbounded, would decode it to its CRC and drop it)
        assertThrows(CompressionFormatException.class,
                () -> Bzip2Decoder.decompressConcatenated(both, 0, both.length, a.length + 1));
        byte[] corrupt = both.clone();
        corrupt[sa.length + 10] ^= 0x10; // the second stream's block CRC
        assertArrayEquals(a, Bzip2Decoder.decompressConcatenated(corrupt, 0, corrupt.length, joined.length));
        assertThrows(CompressionFormatException.class,
                () -> Bzip2Decoder.decompressConcatenated(corrupt, 0, corrupt.length, a.length));
        assertArrayEquals(new byte[0], Bzip2Decoder.decompressConcatenated(both, 3, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> Bzip2Decoder.decompressConcatenated(both, 1, both.length, 10));
        assertThrows(IllegalArgumentException.class, () -> Bzip2Decoder.decompressConcatenated(both, 0, both.length, -1));
    }

    @Test
    void malformedStreamsAreRejected() {
        byte[] data = make("text", 5000, 3);
        byte[] stream = Bzip2Encoder.compress(data, 9);
        // Not bzip2, a bad block size digit, a bad block magic.
        assertThrows(CompressionFormatException.class, () -> Bzip2Decoder.decompress(new byte[] {'B', 'Z', 'x', '9'}, 0, 4, 10));
        byte[] digit = stream.clone();
        digit[3] = '0';
        assertThrows(CompressionFormatException.class, () -> Bzip2Decoder.decompress(digit, 0, digit.length, data.length));
        byte[] magic = stream.clone();
        magic[4] ^= 1;
        assertThrows(CompressionFormatException.class, () -> Bzip2Decoder.decompress(magic, 0, magic.length, data.length));
        // A stored block CRC that does not match, and a stored stream CRC that does not.
        byte[] blockCrc = stream.clone();
        blockCrc[10] ^= 0x10;
        assertThrows(CompressionFormatException.class, () -> Bzip2Decoder.decompress(blockCrc, 0, blockCrc.length, data.length));
        byte[] streamCrc = stream.clone();
        streamCrc[streamCrc.length - 2] ^= 0x10;
        assertThrows(CompressionFormatException.class, () -> Bzip2Decoder.decompress(streamCrc, 0, streamCrc.length, data.length));
        // Every truncation.
        for (int length = 0; length < stream.length; length++) {
            int cut = length;
            assertThrows(CompressionFormatException.class, () -> Bzip2Decoder.decompress(stream, 0, cut, data.length));
        }
        assertThrows(IllegalArgumentException.class, () -> Bzip2Decoder.decompress(stream, 1, stream.length, 10));
        assertThrows(IllegalArgumentException.class, () -> Bzip2Encoder.compress(data, 0));
        assertThrows(IllegalArgumentException.class, () -> Bzip2Encoder.compress(data, 10));
    }

    /** The input of a vector: the recipe gen_bzip2_vectors.py's {@code make} follows. */
    static byte[] make(String kind, int n, int seed) {
        XorShift rng = new XorShift(seed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(n + 64);
        switch (kind) {
            case "zeros" -> out.writeBytes(new byte[n]);
            case "random" -> {
                for (int i = 0; i < n; i++) {
                    out.write(rng.next() >>> 24);
                }
            }
            case "dna" -> {
                for (int i = 0; i < n; i++) {
                    out.write("ACGT".charAt(rng.next() >>> 30));
                }
            }
            case "text" -> {
                String[] words = {"falcon", "reads", "writes", "bzip2", "blocks", "of", "the", "chunk", "hdf5",
                    "filter", "burrows", "wheeler", "huffman", "tables", "and", "a", "run", "zarr"};
                while (out.size() < n) {
                    int r = rng.next();
                    out.writeBytes(words[Integer.remainderUnsigned(r, words.length)].getBytes(StandardCharsets.US_ASCII));
                    out.write((r >>> 8) % 11 == 0 ? '\n' : ' ');
                }
            }
            case "runs" -> {
                while (out.size() < n) {
                    int r = rng.next();
                    byte[] run = new byte[1 + (r & 0xffff) % 600];
                    Arrays.fill(run, (byte) (r >>> 24));
                    out.writeBytes(run);
                }
            }
            case "shortruns" -> {
                while (out.size() < n) {
                    int r = rng.next();
                    byte[] run = new byte[1 + (r & 0xff) % 6];
                    Arrays.fill(run, (byte) (r >>> 30));
                    out.writeBytes(run);
                }
            }
            case "periodic" -> {
                for (int i = 0; i < n; i++) {
                    out.write((i % seed) * 37 & 0xff);
                }
            }
            case "repeat" -> {
                byte[] unit = new byte[seed];
                for (int i = 0; i < seed; i++) {
                    unit[i] = (byte) (rng.next() >>> 24);
                }
                for (int i = 0; i < n; i++) {
                    out.write(unit[i % seed]);
                }
            }
            case "ints" -> {
                for (int i = 0; i < n / 4; i++) {
                    int value = i / 3 + (rng.next() >>> 29);
                    out.write(value);
                    out.write(value >>> 8);
                    out.write(value >>> 16);
                    out.write(value >>> 24);
                }
                out.writeBytes(new byte[n % 4]);
            }
            default -> throw new IllegalArgumentException(kind);
        }
        return Arrays.copyOf(out.toByteArray(), n);
    }

    /** Marsaglia's xorshift32, seeded as the generator seeds it. */
    private static final class XorShift {
        private int x;

        XorShift(int seed) {
            x = seed != 0 ? seed : 0x9E3779B9;
        }

        int next() {
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            return x;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
