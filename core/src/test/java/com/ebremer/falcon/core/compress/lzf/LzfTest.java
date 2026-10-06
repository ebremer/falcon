package com.ebremer.falcon.core.compress.lzf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.Vectors;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * LZF against liblzf output (h5py's lzf filter, via tools/fixtures/gen_core_vectors.py): inputs of 1 byte to
 * 30 KiB, repeats inside and beyond the 8 KiB window, and inputs whose stream only just fits in their own
 * size, or does not ("-": h5py stores such a chunk unfiltered).
 */
class LzfTest {

    @Test
    void decodesLiblzfOutput() {
        var vectors = Vectors.read("lzf_vectors.txt");
        assertEquals(47, vectors.size());
        for (String[] v : vectors) {
            if (v[3].equals("-")) {
                continue;
            }
            byte[] original = Vectors.hex(v[2]);
            byte[] stream = Vectors.hex(v[3]);
            assertEquals(Integer.parseInt(v[1]), original.length);
            assertArrayEquals(original, Lzf.decompress(stream, 0, stream.length, original.length), v[0]);
            // Without a size hint the output grows as needed.
            assertArrayEquals(original, Lzf.decompress(stream, 0, stream.length, 0), v[0]);
        }
    }

    /**
     * Falcon's encoder writes h5py's liblzf stream byte for byte, in at most the input's own size (h5py's
     * limit), and gives up exactly where liblzf does.
     */
    @Test
    void compressesAsLiblzf() {
        int streams = 0;
        int refused = 0;
        for (String[] v : Vectors.read("lzf_vectors.txt")) {
            byte[] original = Vectors.hex(v[2]);
            byte[] compressed = Lzf.compress(original, 0, original.length, original.length);
            if (v[3].equals("-")) {
                assertNull(compressed, v[0]);
                refused++;
            } else {
                assertArrayEquals(Vectors.hex(v[3]), compressed, v[0]);
                streams++;
            }
        }
        assertEquals(23, streams);
        assertEquals(24, refused);
    }

    @Test
    void compressedStreamsDecodeAndRespectTheirLimit() {
        Random random = new Random(5);
        for (int n = 1; n < 3000; n += 1 + n / 7) {
            byte[] data = new byte[n + 9];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (random.nextInt(8) == 0 ? random.nextInt() : i % 13); // compressible, with noise
            }
            byte[] part = Arrays.copyOfRange(data, 9, 9 + n);
            for (int limit : new int[] {n, n / 2, n * 2 + 16}) {
                byte[] stream = Lzf.compress(data, 9, n, limit);
                if (stream != null) {
                    assertTrue(stream.length <= limit, n + " in " + limit);
                    assertArrayEquals(part, Lzf.decompress(stream, 0, stream.length, n));
                }
            }
            // With room for every literal and its run's control byte, liblzf never gives up.
            assertNotNull(Lzf.compress(data, 9, n, n + n / 32 + 4), "fits " + n);
        }
        assertNull(Lzf.compress(new byte[4], 0, 0, 10));
        assertNull(Lzf.compress(new byte[4], 0, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> Lzf.compress(new byte[4], 2, 3, 10));
    }

    @Test
    void overlappingBackReferences() {
        // A literal "ab", then a reference of length 3 + 2 = 5 at distance 2.
        byte[] stream = {1, 'a', 'b', (byte) (3 << 5), 1};
        assertArrayEquals("abababa".getBytes(), Lzf.decompress(stream, 0, stream.length, 0));
    }

    @Test
    void malformedStreamsAreRejected() {
        // A literal run longer than the input.
        assertThrows(CompressionFormatException.class, () -> Lzf.decompress(new byte[] {5, 'a'}, 0, 2, 0));
        // A reference before the start of the output.
        assertThrows(CompressionFormatException.class, () -> Lzf.decompress(new byte[] {0, 'a', 0x20, 5}, 0, 4, 0));
        // A reference whose distance byte is missing.
        assertThrows(CompressionFormatException.class, () -> Lzf.decompress(new byte[] {0, 'a', 0x20}, 0, 3, 0));
    }
}
