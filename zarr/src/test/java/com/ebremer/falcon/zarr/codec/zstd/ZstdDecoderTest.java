package com.ebremer.falcon.zarr.codec.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Validates the from-scratch Zstandard decoder against reference frames produced by <b>libzstd</b>
 * (via numcodecs; see {@code tools/fixtures/gen_zstd_vectors.py}). Each vector pairs the original bytes
 * with the frame libzstd produced, so a decode that disagrees anywhere is caught.
 */
class ZstdDecoderTest {

    private record Vector(String name, int level, byte[] original, byte[] frame) {
    }

    private static List<Vector> vectors() {
        var url = ZstdDecoderTest.class.getResource("/fixtures/zstd_vectors.txt");
        if (url == null) {
            throw new IllegalStateException("zstd_vectors.txt missing; regenerate with "
                    + "tools/fixtures/gen_zstd_vectors.py");
        }
        try {
            List<Vector> out = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(url.toURI()), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ");
                out.add(new Vector(parts[0], Integer.parseInt(parts[1]),
                        hex(parts[2]), hex(parts[3])));
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
    void decodesEveryReferenceFrame() {
        List<Vector> vectors = vectors();
        assertTrue(vectors.size() >= 15, "expected the full vector set, got " + vectors.size());
        List<String> failures = new ArrayList<>();
        for (Vector v : vectors) {
            try {
                byte[] decoded = ZstdDecoder.decompress(v.frame());
                if (decoded.length != v.original().length) {
                    failures.add(v.name() + ": length " + decoded.length + " != " + v.original().length);
                } else if (!java.util.Arrays.equals(v.original(), decoded)) {
                    int at = 0;
                    while (at < decoded.length && decoded[at] == v.original()[at]) {
                        at++;
                    }
                    failures.add(v.name() + ": content differs at byte " + at);
                }
            } catch (RuntimeException e) {
                failures.add(v.name() + ": threw " + e);
            }
        }
        assertTrue(failures.isEmpty(),
                failures.size() + "/" + vectors.size() + " vectors failed:\n  "
                        + String.join("\n  ", failures));
    }

    @Test
    void rejectsNonZstdInput() {
        assertThrows(ZstdFormatException.class, () -> ZstdDecoder.decompress(new byte[] {1, 2, 3, 4, 5}));
    }

    @Test
    void rejectsTruncatedFrame() {
        byte[] frame = vectors().stream().filter(v -> v.name().equals("text_medium"))
                .findFirst().orElseThrow().frame();
        byte[] truncated = java.util.Arrays.copyOf(frame, frame.length / 2);
        assertThrows(RuntimeException.class, () -> ZstdDecoder.decompress(truncated));
    }
}
