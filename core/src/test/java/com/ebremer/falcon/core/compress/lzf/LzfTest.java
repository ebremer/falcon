package com.ebremer.falcon.core.compress.lzf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.Vectors;
import org.junit.jupiter.api.Test;

/** LZF against liblzf output (h5py's lzf filter, via tools/fixtures/gen_core_vectors.py). */
class LzfTest {

    @Test
    void decodesLiblzfOutput() {
        var vectors = Vectors.read("lzf_vectors.txt");
        assertEquals(4, vectors.size());
        for (String[] v : vectors) {
            byte[] original = Vectors.hex(v[2]);
            byte[] stream = Vectors.hex(v[3]);
            assertEquals(Integer.parseInt(v[1]), original.length);
            assertArrayEquals(original, Lzf.decompress(stream, 0, stream.length, original.length), v[0]);
            // Without a size hint the output grows as needed.
            assertArrayEquals(original, Lzf.decompress(stream, 0, stream.length, 0), v[0]);
        }
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
