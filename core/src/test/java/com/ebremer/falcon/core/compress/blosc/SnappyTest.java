package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the Snappy block decoder against reference vectors from <b>cramjam</b> (see
 * {@code tools/fixtures/gen_snappy_vectors.py}), and that a Blosc buffer using the snappy internal codec
 * decodes end-to-end.
 */
class SnappyTest {

    private record Vector(String name, byte[] original, byte[] raw) {
    }

    private static List<Vector> vectors() {
        var url = SnappyTest.class.getResource("/fixtures/snappy_vectors.txt");
        if (url == null) {
            throw new IllegalStateException("snappy_vectors.txt missing; run gen_snappy_vectors.py");
        }
        try {
            List<Vector> out = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(url.toURI()), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split(" ");
                out.add(new Vector(p[0], hex(p[1]), hex(p[2])));
            }
            return out;
        } catch (IOException | URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    void decodesEveryReferenceVector() {
        List<Vector> vectors = vectors();
        assertTrue(vectors.size() >= 10, "expected the full vector set, got " + vectors.size());
        List<String> failures = new ArrayList<>();
        for (Vector v : vectors) {
            byte[] out = new byte[v.original().length];
            try {
                Snappy.decompress(v.raw(), 0, v.raw().length, out, 0, out.length);
                if (!java.util.Arrays.equals(v.original(), out)) {
                    failures.add(v.name() + ": content differs");
                }
            } catch (RuntimeException e) {
                failures.add(v.name() + ": threw " + e);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n  ", failures));
    }

    /** A Blosc buffer with the snappy internal codec (hand-built, since c-blosc no longer emits it). */
    @Test
    void bloscBufferWithSnappyDecodes() {
        Vector v = vectors().stream().filter(s -> s.name().equals("int32_like")).findFirst().orElseThrow();
        byte[] payload = v.raw();
        int nbytes = v.original().length;

        // c-blosc container, format 2: header, 1-entry offset table, one stream [length][payload].
        byte[] buffer = new byte[16 + 4 + 4 + payload.length];
        buffer[0] = 2;              // version
        buffer[1] = 1;              // version of the internal codec format
        buffer[2] = (byte) (2 << 5); // internal codec 2 (snappy), typesize 1: no shuffle, no split
        buffer[3] = 1;              // typesize
        putLe32(buffer, 4, nbytes);
        putLe32(buffer, 8, nbytes); // blocksize == nbytes: a single block
        putLe32(buffer, 12, buffer.length);
        putLe32(buffer, 16, 20);            // block 0 starts after the offset table
        putLe32(buffer, 20, payload.length); // stream length (< block size, so read as compressed)
        System.arraycopy(payload, 0, buffer, 24, payload.length);

        assertArrayEquals(v.original(), BloscDecoder.decompress(buffer));
    }

    private static void putLe32(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
        b[off + 2] = (byte) (value >>> 16);
        b[off + 3] = (byte) (value >>> 24);
    }
}
