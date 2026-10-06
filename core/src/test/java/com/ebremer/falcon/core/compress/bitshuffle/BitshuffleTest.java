package com.ebremer.falcon.core.compress.bitshuffle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.Vectors;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Bitshuffle against the bitshuffle library's own output (hdf5plugin's filter, via
 * tools/fixtures/gen_core_vectors.py): alone, with LZ4, and with zstd; default and small blocks; element
 * counts that are not a multiple of 8.
 */
class BitshuffleTest {

    @Test
    void decodesTheLibraryOutput() {
        var vectors = Vectors.read("bitshuffle_vectors.txt");
        assertEquals(34, vectors.size());
        for (String[] v : vectors) {
            int elementSize = Integer.parseInt(v[1]);
            int elements = Integer.parseInt(v[2]);
            int blockSize = Integer.parseInt(v[3]);
            byte[] original = Vectors.hex(v[5]);
            byte[] stream = Vectors.hex(v[6]);
            byte[] decoded = switch (v[4]) {
                case "none" -> Bitshuffle.unshuffle(stream, 0, elements, elementSize, blockSize);
                case "lz4" -> Bitshuffle.decompress(stream, 0, stream.length, elements, elementSize, blockSize,
                        Bitshuffle.BlockCodec.LZ4);
                default -> Bitshuffle.decompress(stream, 0, stream.length, elements, elementSize, blockSize,
                        Bitshuffle.BlockCodec.ZSTD);
            };
            assertArrayEquals(original, decoded, v[0]);
        }
    }

    /**
     * The forward transpose (written for Blosc's bit-shuffle filter, Zarr P1 I9) reproduces the library's
     * own bitshuffled streams block by block, and the inverse undoes it.
     */
    @Test
    void transposeReproducesTheLibrary() {
        int checked = 0;
        for (String[] v : Vectors.read("bitshuffle_vectors.txt")) {
            if (!v[4].equals("none")) {
                continue;
            }
            int elementSize = Integer.parseInt(v[1]);
            int elements = Integer.parseInt(v[2]);
            int requested = Integer.parseInt(v[3]);
            int blockSize = requested == 0 ? Bitshuffle.defaultBlockSize(elementSize) : requested;
            byte[] original = Vectors.hex(v[5]);
            byte[] stream = Vectors.hex(v[6]);
            byte[] shuffled = new byte[original.length];
            byte[] tmp = new byte[blockSize * elementSize];
            int done = 0;
            while (elements - done >= 8) {
                int n = elements - done >= blockSize ? blockSize : (elements - done) / 8 * 8;
                Bitshuffle.transpose(original, done * elementSize, shuffled, done * elementSize, n, elementSize, tmp);
                done += n;
            }
            int tail = (elements - done) * elementSize; // the last n % 8 elements are not shuffled
            System.arraycopy(original, done * elementSize, shuffled, done * elementSize, tail);
            assertArrayEquals(stream, shuffled, v[0]);
            checked++;
        }
        assertTrue(checked > 0);

        java.util.Random random = new java.util.Random(9);
        for (int elementSize : new int[] {1, 2, 3, 4, 8, 16, 33}) {
            for (int elements : new int[] {8, 64, 1000}) {
                byte[] data = new byte[elements * elementSize];
                random.nextBytes(data);
                byte[] shuffled = new byte[data.length];
                byte[] back = new byte[data.length];
                Bitshuffle.transpose(data, 0, shuffled, 0, elements, elementSize, new byte[data.length]);
                Bitshuffle.untranspose(shuffled, 0, back, 0, elements, elementSize);
                assertArrayEquals(data, back, elementSize + " x " + elements);
            }
        }
    }

    /**
     * The encoders reproduce the library's streams byte for byte: bit-shuffled alone, and with LZ4 (zstd's
     * frames are Falcon's own, so those round-trip instead), for element sizes of 1 to 16 bytes, blocks of 8
     * to 8192 elements, and counts that leave a partial block and a tail of fewer than 8 elements.
     */
    @Test
    void encodesAsTheLibrary() {
        int exact = 0;
        for (String[] v : Vectors.read("bitshuffle_vectors.txt")) {
            int elementSize = Integer.parseInt(v[1]);
            int elements = Integer.parseInt(v[2]);
            int blockSize = Integer.parseInt(v[3]);
            byte[] original = Vectors.hex(v[5]);
            byte[] stream = Vectors.hex(v[6]);
            switch (v[4]) {
                case "none" -> assertArrayEquals(stream, Bitshuffle.shuffle(original, 0, elements, elementSize, blockSize),
                        v[0]);
                case "lz4" -> assertArrayEquals(stream, Bitshuffle.compress(original, 0, elements, elementSize, blockSize,
                        Bitshuffle.BlockCodec.LZ4, 0), v[0]);
                default -> {
                    byte[] ours = Bitshuffle.compress(original, 0, elements, elementSize, blockSize,
                            Bitshuffle.BlockCodec.ZSTD, 3);
                    assertArrayEquals(original, Bitshuffle.decompress(ours, 0, ours.length, elements, elementSize,
                            blockSize, Bitshuffle.BlockCodec.ZSTD), v[0]);
                    continue;
                }
            }
            exact++;
        }
        assertEquals(29, exact);
    }

    @Test
    void encodersRoundTripAndCheckTheirArguments() {
        Random random = new Random(3);
        for (int elementSize : new int[] {1, 2, 5, 8, 24}) {
            for (int elements : new int[] {0, 7, 8, 9, 130, 1000}) {
                for (int blockSize : new int[] {0, 8, 64}) {
                    byte[] data = new byte[elements * elementSize + 3];
                    random.nextBytes(data);
                    byte[] part = Arrays.copyOfRange(data, 3, data.length);
                    byte[] shuffled = Bitshuffle.shuffle(data, 3, elements, elementSize, blockSize);
                    assertArrayEquals(part, Bitshuffle.unshuffle(shuffled, 0, elements, elementSize, blockSize));
                    for (Bitshuffle.BlockCodec codec : Bitshuffle.BlockCodec.values()) {
                        byte[] packed = Bitshuffle.compress(data, 3, elements, elementSize, blockSize, codec, 1);
                        assertArrayEquals(part, Bitshuffle.decompress(packed, 0, packed.length, elements, elementSize,
                                blockSize, codec), codec + " " + elementSize + " x " + elements + " / " + blockSize);
                    }
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Bitshuffle.shuffle(new byte[32], 0, 8, 4, 12));
        assertThrows(IllegalArgumentException.class,
                () -> Bitshuffle.compress(new byte[32], 0, 8, 4, 4, Bitshuffle.BlockCodec.LZ4, 0));
        assertThrows(IllegalArgumentException.class, () -> Bitshuffle.shuffle(new byte[31], 0, 8, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> Bitshuffle.shuffle(new byte[32], 0, 8, 0, 0));
    }

    @Test
    void defaultBlockSizeMatchesTheLibrary() {
        assertEquals(2048, Bitshuffle.defaultBlockSize(4));
        assertEquals(8192, Bitshuffle.defaultBlockSize(1));
        assertEquals(128, Bitshuffle.defaultBlockSize(128)); // never below 128 elements
    }

    @Test
    void malformedInputIsRejected() {
        // Fewer bytes than the elements need.
        assertThrows(CompressionFormatException.class, () -> Bitshuffle.unshuffle(new byte[4], 0, 8, 4, 0));
        // A block size that is not a multiple of 8.
        assertThrows(CompressionFormatException.class, () -> Bitshuffle.unshuffle(new byte[32], 0, 8, 4, 12));
        // A block claiming 9 compressed bytes where none follow.
        assertThrows(CompressionFormatException.class,
                () -> Bitshuffle.decompress(new byte[] {0, 0, 0, 9}, 0, 4, 8, 4, 0, Bitshuffle.BlockCodec.LZ4));
    }
}
