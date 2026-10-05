package com.ebremer.falcon.hdf5.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the pure-Java {@link Aec} decoder against reference vectors produced by libaec (via
 * imagecodecs) — the same extended-Rice implementation HDF5's szip filter uses.
 */
class AecTest {

    @Test
    void decodesLibaecReferenceVectors() throws IOException {
        assertTrue(decodeVectors("/fixtures/aec_vectors.txt") >= 80, "expected many reference vectors");
    }

    /**
     * Zero runs coded as "remainder of segment" inside a reference-sample interval longer than 64 blocks
     * (HDF5 allows up to 128 blocks per scanline): the run ends at the 64-block segment boundary, not at
     * the end of the interval.
     */
    @Test
    void zeroBlockRemainderOfSegmentStopsAtSegmentBoundary() throws IOException {
        assertTrue(decodeVectors("/fixtures/aec_ros_vectors.txt") >= 6, "expected remainder-of-segment vectors");
    }

    private static int decodeVectors(String resource) throws IOException {
        int count = 0;
        for (String line : lines(resource)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split(" ");
            int bpp = Integer.parseInt(p[0]);
            int bs = Integer.parseInt(p[1]);
            int rsi = Integer.parseInt(p[2]);
            int flags = Integer.parseInt(p[3]);
            byte[] enc = hex(p[4]);
            String[] valueStrings = p[5].split(",");
            long[] expected = new long[valueStrings.length];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = Long.parseLong(valueStrings[i]);
            }
            long[] actual = Aec.decode(enc, expected.length, bpp, bs, rsi, flags);
            assertArrayEquals(expected, actual, "bpp=" + bpp + " bs=" + bs + " rsi=" + rsi + " flags=" + flags);
            count++;
        }
        return count;
    }

    static List<String> lines(String resource) throws IOException {
        try (InputStream in = AecTest.class.getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> out = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                out.add(line);
            }
            return out;
        }
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
