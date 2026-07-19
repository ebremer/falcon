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
        int count = 0;
        for (String line : lines("/fixtures/aec_vectors.txt")) {
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
        assertTrue(count >= 80, "expected many reference vectors, parsed " + count);
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
