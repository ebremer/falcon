package com.ebremer.falcon.core.compress.bitshuffle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.Vectors;
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
        assertEquals(20, vectors.size());
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
