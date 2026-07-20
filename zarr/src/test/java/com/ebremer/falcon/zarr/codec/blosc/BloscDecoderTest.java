package com.ebremer.falcon.zarr.codec.blosc;

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
 * Validates the from-scratch Blosc container decoder against reference buffers produced by
 * <b>c-blosc</b> (via numcodecs; see {@code tools/fixtures/gen_blosc_vectors.py}).
 *
 * <p>Every internal codec and shuffle filter in the vector set decodes; an undefined internal codec must
 * be reported as an error rather than silently mis-decoded, which is asserted just as strictly.
 */
class BloscDecoderTest {

    private record Vector(String name, String cname, int clevel, String shuffle, int typeSize,
                          byte[] original, byte[] buffer) {

        /** Every internal codec in the vector set is implemented (blosclz/lz4/lz4hc/zlib/zstd). */
        boolean supported() {
            return true;
        }
    }

    private static List<Vector> vectors() {
        var url = BloscDecoderTest.class.getResource("/fixtures/blosc_vectors.txt");
        if (url == null) {
            throw new IllegalStateException("blosc_vectors.txt missing; regenerate with "
                    + "tools/fixtures/gen_blosc_vectors.py");
        }
        try {
            List<Vector> out = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(url.toURI()), StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split(" ");
                out.add(new Vector(p[0], p[1], Integer.parseInt(p[2]), p[3], Integer.parseInt(p[4]),
                        hex(p[5]), hex(p[6])));
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
    void decodesEverySupportedReferenceBuffer() {
        List<Vector> vectors = vectors();
        assertTrue(vectors.size() >= 40, "expected the full vector set, got " + vectors.size());
        List<String> failures = new ArrayList<>();
        int decoded = 0;
        for (Vector v : vectors) {
            if (!v.supported()) {
                continue;
            }
            decoded++;
            try {
                byte[] out = BloscDecoder.decompress(v.buffer());
                if (out.length != v.original().length) {
                    failures.add(v.name() + ": length " + out.length + " != " + v.original().length);
                } else if (!java.util.Arrays.equals(v.original(), out)) {
                    int at = 0;
                    while (at < out.length && out[at] == v.original()[at]) {
                        at++;
                    }
                    failures.add(v.name() + ": content differs at byte " + at);
                }
            } catch (RuntimeException e) {
                failures.add(v.name() + " (" + v.cname() + "/" + v.shuffle() + "): threw " + e);
            }
        }
        assertTrue(decoded >= 25, "too few supported vectors exercised: " + decoded);
        assertTrue(failures.isEmpty(),
                failures.size() + "/" + decoded + " supported vectors failed:\n  "
                        + String.join("\n  ", failures));
    }

    @Test
    void anUnknownInternalCodecIsRefusedNotMisdecoded() {
        // Every defined internal codec (0..4) is now implemented, so craft a buffer using an undefined
        // codec code (5): a single block whose one stream is "compressed" so the codec is invoked.
        byte[] buffer = new byte[27];
        buffer[0] = 2;          // version
        buffer[1] = 1;          // version of the internal codec format
        buffer[2] = (byte) (5 << 5); // flags: internal codec 5 (undefined), no shuffle, no memcpy
        buffer[3] = 1;          // typesize
        putLe32(buffer, 4, 8);  // nbytes (decompressed)
        putLe32(buffer, 8, 8);  // blocksize
        putLe32(buffer, 12, 27); // cbytes (total)
        putLe32(buffer, 16, 20); // block 0 offset
        putLe32(buffer, 20, 3);  // stream compressed length (!= 8, so not stored raw)
        assertThrows(BloscFormatException.class, () -> BloscDecoder.decompress(buffer));
    }

    private static void putLe32(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
        b[off + 2] = (byte) (value >>> 16);
        b[off + 3] = (byte) (value >>> 24);
    }

    @Test
    void headerReportsDecompressedSize() {
        for (Vector v : vectors()) {
            assertEquals(v.original().length, BloscDecoder.decompressedSize(v.buffer()), v.name());
        }
    }

    @Test
    void rejectsShortAndCorruptBuffers() {
        assertThrows(BloscFormatException.class, () -> BloscDecoder.decompress(new byte[0]));
        assertThrows(BloscFormatException.class, () -> BloscDecoder.decompress(new byte[8]));
        byte[] buffer = vectors().stream().filter(v -> v.name().equals("lz4_repetitive"))
                .findFirst().orElseThrow().buffer();
        byte[] truncated = java.util.Arrays.copyOf(buffer, buffer.length / 2);
        assertThrows(RuntimeException.class, () -> BloscDecoder.decompress(truncated));
    }

    @Test
    void roundTripsThroughEachSupportedInternalCodec() {
        // every supported cname must appear among the vectors that actually decode
        for (String cname : List.of("blosclz", "lz4", "lz4hc", "zlib", "zstd")) {
            boolean seen = vectors().stream()
                    .anyMatch(v -> v.cname().equals(cname) && v.supported()
                            && (v.buffer()[2] & 0x02) == 0); // genuinely compressed, not memcpy'ed
            assertTrue(seen, "no compressed vector exercises " + cname);
        }
    }
}
