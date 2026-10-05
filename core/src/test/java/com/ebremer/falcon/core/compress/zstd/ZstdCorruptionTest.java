package com.ebremer.falcon.core.compress.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.Vectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The zstd decoder never returns data libzstd would not (P0 Z6/Z7): corrupt frames fail, several frames
 * decode to their concatenation, and nothing may follow the last frame. The vectors come from libzstd
 * (python-zstandard; {@code tools/fixtures/gen_zstd_corrupt_vectors.py}): one- and two-bit mutations of
 * frames with and without a content checksum, and inputs that test the framing.
 */
class ZstdCorruptionTest {

    @Test
    void xxh64MatchesItsReferenceValues() {
        // From the xxHash reference implementation (XXH64, seed 0, via the xxhash Python module): short
        // inputs, one past a 32-byte stripe, and many stripes with every kind of tail.
        assertEquals(0xEF46DB3751D8E999L, Xxh64.hash(new byte[0], 0, 0, 0));
        assertEquals(0xD24EC4F1A98C6E5BL, Xxh64.hash(bytes("a"), 0, 1, 0));
        assertEquals(0x44BC2CF5AD770999L, Xxh64.hash(bytes("abc"), 0, 3, 0));
        assertEquals(0xFBCEA83C8A378BF1L, Xxh64.hash(bytes("Nobody inspects the spammish repetition"), 0, 39, 0));
        byte[] long1000 = new byte[1000];
        for (int i = 0; i < long1000.length; i++) {
            long1000[i] = (byte) (i * 31 % 251);
        }
        assertEquals(0xD1BEE8E4F0603BBFL, Xxh64.hash(long1000, 0, long1000.length, 0));
        // An offset into a larger array hashes only its range.
        byte[] padded = bytes("xxabcxx");
        assertEquals(0x44BC2CF5AD770999L, Xxh64.hash(padded, 2, 3, 0));
    }

    @Test
    void neverReturnsWhatLibzstdWouldNot() throws NoSuchAlgorithmException {
        List<String[]> vectors = Vectors.read("zstd_corrupt_vectors.txt");
        assertTrue(vectors.size() > 400, "expected the full vector set, got " + vectors.size());
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        List<String> failures = new ArrayList<>();
        int rejected = 0;
        for (String[] v : vectors) {
            String label = v[0];
            String expected = v[1];
            byte[] input = v[2].equals("-") ? new byte[0] : Vectors.hex(v[2]);
            String actual;
            try {
                actual = "OK:" + HexFormat.of().formatHex(sha1.digest(ZstdDecoder.decompress(input)));
            } catch (CompressionFormatException e) {
                actual = "ERR";
                rejected++;
            } catch (RuntimeException e) {
                failures.add(label + ": untyped " + e);
                continue;
            }
            boolean ok = expected.startsWith("ANY:")
                    ? actual.equals("ERR") || actual.equals("OK:" + expected.substring(4))
                    : actual.equals(expected);
            if (!ok) {
                failures.add(label + ": expected " + expected + ", got " + actual + " for " + v[2].substring(0, Math.min(40, v[2].length())));
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + "/" + vectors.size() + " vectors failed:\n  "
                + String.join("\n  ", failures.subList(0, Math.min(20, failures.size()))));
        assertTrue(rejected > 240, "the corrupt vectors should mostly be rejected, got " + rejected);
    }

    @Test
    void framesDecodeToTheirConcatenation() {
        byte[] a = ZstdEncoder.compress(bytes("the first frame, the first frame, the first frame"));
        byte[] b = ZstdEncoder.compress(bytes("and the second"));
        byte[] skippable = concat(new byte[] {0x5A, 0x2A, 0x4D, 0x18, 3, 0, 0, 0}, bytes("xyz"));
        assertArrayEquals(bytes("the first frame, the first frame, the first frameand the second"),
                ZstdDecoder.decompress(concat(a, b)));
        assertArrayEquals(bytes("the first frame, the first frame, the first frameand the second"),
                ZstdDecoder.decompress(concat(skippable, a, skippable, b, skippable)));
        assertArrayEquals(new byte[0], ZstdDecoder.decompress(skippable));
        // The output limit counts every frame.
        assertThrows(CompressionFormatException.class, () -> ZstdDecoder.decompress(concat(a, b), 0, a.length + b.length, 60));
    }

    @Test
    void nothingButFramesMayFollowAFrame() {
        byte[] a = ZstdEncoder.compress(bytes("payload payload payload"));
        for (byte[] bad : List.of(concat(a, new byte[] {0}), concat(a, new byte[] {0x28, (byte) 0xB5, 0x2F}),
                concat(a, new byte[16]), Arrays.copyOf(a, a.length - 1),
                concat(a, new byte[] {0x50, 0x2A, 0x4D, 0x18, 9, 0, 0, 0, 1}), new byte[0])) {
            assertThrows(CompressionFormatException.class, () -> ZstdDecoder.decompress(bad), HexFormat.of().formatHex(bad));
        }
    }

    @Test
    void aCorruptChecksumOrSizeFails() {
        byte[] data = new byte[5000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7 % 13);
        }
        // libzstd frames with a checksum (from the reference vectors): flipping the checksum fails.
        for (String[] v : Vectors.read("zstd_corrupt_vectors.txt")) {
            if (v[0].equals("two-frames")) {
                byte[] input = Vectors.hex(v[2]);
                ZstdDecoder.decompress(input);
                // The first frame of "two-frames" carries a checksum; its last byte is the checksum's.
                int firstEnd = firstFrameLength(input);
                byte[] broken = input.clone();
                broken[firstEnd - 1] ^= 1;
                CompressionFormatException e = assertThrows(CompressionFormatException.class,
                        () -> ZstdDecoder.decompress(broken));
                assertTrue(e.getMessage().contains("checksum"), e.getMessage());
            }
        }
        byte[] frame = ZstdEncoder.compress(data);
        assertArrayEquals(data, ZstdDecoder.decompress(frame));
    }

    /** The length of the first frame of {@code input}: decode growing prefixes until one decodes alone. */
    private static int firstFrameLength(byte[] input) {
        for (int n = 6; n <= input.length; n++) {
            try {
                ZstdDecoder.decompress(input, 0, n);
                return n;
            } catch (CompressionFormatException e) {
                // not a whole frame yet
            }
        }
        throw new AssertionError("no complete first frame");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
