package com.ebremer.falcon.hdf5.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact conformance of the scale-offset filter with libhdf5: for every little-endian integer chunk
 * libhdf5 wrote in {@code scaleoffset.h5} (exported to {@code scaleoffset_chunks.txt} by the fixture
 * generator), Falcon's encoder must produce the identical bytes and client data, and its decoder must
 * restore the input. Covers power-of-two chunks, values equal to the fill value, a constant chunk, and a
 * full-range chunk stored verbatim.
 */
class ScaleOffsetTest {

    @Test
    void encodesExactlyAsLibhdf5() throws IOException {
        int count = 0;
        for (String line : AecTest.lines("/fixtures/scaleoffset_chunks.txt")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split(" ");
            int size = Integer.parseInt(p[0]);
            boolean signed = p[1].equals("1");
            int[] cd = Arrays.stream(p[2].split(",")).mapToInt(s -> (int) Long.parseLong(s)).toArray();
            byte[] input = AecTest.hex(p[3]);
            byte[] libhdf5 = AecTest.hex(p[4]);
            long fill = 0;
            for (int b = 0; b < size; b++) { // the fill value's little-endian bytes, packed from cd[8]
                fill |= (long) ((cd[8 + b / 4] >>> (8 * (b % 4))) & 0xff) << (8 * b);
            }
            String what = "size=" + size + " input=" + p[3];

            assertArrayEquals(libhdf5, ScaleOffset.encodeInteger(input, size, signed, fill), what);
            assertArrayEquals(cd, ScaleOffset.integerClientData(cd[2], size, signed, false, fill), what);
            assertArrayEquals(input, ScaleOffset.decode(libhdf5, cd, input.length), what);
            count++;
        }
        assertTrue(count >= 15, "expected many scale-offset chunks, parsed " + count);
    }
}
