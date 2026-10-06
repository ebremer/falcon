package com.ebremer.falcon.core.compress.sz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.Vectors;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * SZ 2 against libSZ (SZ 2.1.12 through hdf5plugin's H5Z-SZ, via tools/fixtures/gen_sz_vectors.py): every
 * form the filter writes, in every data type and 1 to 4 dimensions, decodes as libSZ decodes it.
 */
class SzDecoderTest {

    static byte[] decode(String[] v, byte[] stream) {
        return SzDecoder.decompress(Integer.parseInt(v[1]), stream, 0, stream.length, Long.parseLong(v[2]),
                Long.parseLong(v[3]), Long.parseLong(v[4]), Long.parseLong(v[5]), Long.parseLong(v[6]), 1 << 24);
    }

    @Test
    void decodesAsLibSz() {
        var vectors = Vectors.read("sz_vectors.txt");
        assertEquals(124, vectors.size());
        int exact = 0;
        for (String[] v : vectors) {
            byte[] want = Vectors.hex(v[8]);
            byte[] got = decode(v, Vectors.hex(v[7]));
            if (v[0].startsWith("f8_") && v[0].contains("pwr")) {
                // Doubles under point-wise relative bounds go through pow/exp2, whose last bit is the C library's.
                assertWithinUlps(v[0], want, got, 64);
            } else {
                assertArrayEquals(want, got, v[0]);
            }
            exact += Arrays.equals(want, got) ? 1 : 0;
        }
        assertTrue(exact >= 119, exact + " of 124 bit for bit");
    }

    @Test
    void shortDataIsStoredAsItIs() {
        // SZ_compress keeps 20 values or fewer as they are, with no header (which libSZ then cannot read).
        byte[] raw = new byte[80];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7);
        }
        assertArrayEquals(raw, SzDecoder.decompress(SzDecoder.FLOAT, raw, 0, raw.length, 0, 0, 0, 0, 20, 1000));
        assertArrayEquals(raw, SzDecoder.decompress(SzDecoder.DOUBLE, raw, 0, raw.length, 0, 0, 0, 2, 5, 1000));
    }

    @Test
    void dimensionsOfLengthOneAreDropped() {
        assertArrayEquals(new long[] {40, 6, 0, 0, 0}, SzDecoder.filterDimension(0, 0, 6, 1, 40));
        assertArrayEquals(new long[] {7, 0, 0, 0, 0}, SzDecoder.filterDimension(0, 0, 0, 1, 7));
        assertArrayEquals(new long[] {5, 4, 3, 0, 0}, SzDecoder.filterDimension(0, 3, 1, 4, 5));
        assertEquals(1, SzDecoder.dimension(0, 0, 0, 0, 9));
    }

    @Test
    void badInputIsRejected() {
        String[] v = Vectors.read("sz_vectors.txt").getFirst();
        byte[] stream = Vectors.hex(v[7]);
        // more values than the caller allows
        assertThrows(CompressionFormatException.class, () -> SzDecoder.decompress(SzDecoder.FLOAT, stream, 0,
                stream.length, 0, 0, 0, 0, 300, 100));
        assertThrows(UnsupportedCompressionException.class, () -> SzDecoder.decompress(10, stream, 0,
                stream.length, 0, 0, 0, 0, 300, 1 << 20));
        // a version before 2.1.8 (libSZ exits)
        byte[] old = {2, 1, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThrows(UnsupportedCompressionException.class, () -> SzDecoder.decompress(SzDecoder.FLOAT, old, 0,
                old.length, 0, 0, 0, 0, 300, 1 << 20));
        for (int cut : new int[] {0, 5, 40, stream.length / 2}) {
            assertThrows(CompressionFormatException.class, () -> SzDecoder.decompress(SzDecoder.FLOAT, stream, 0, cut,
                    0, 0, 0, 0, 300, 1 << 20), "cut at " + cut);
        }
    }

    private static void assertWithinUlps(String name, byte[] want, byte[] got, long ulps) {
        assertEquals(want.length, got.length, name);
        var w = ByteBuffer.wrap(want).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
        var g = ByteBuffer.wrap(got).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
        for (int i = 0; i < w.capacity(); i++) {
            long a = w.get(i);
            long b = g.get(i);
            assertTrue(a == b || (a ^ b) >= 0 && Math.abs(a - b) <= ulps, name + " value " + i);
        }
    }
}
