package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.Vectors;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Falcon's Blosc and LZ4 encoders reproduce c-blosc 1.21 and liblz4 1.10.0 byte for byte (F3). Each line
 * of {@code blosc_encode_vectors.txt} ({@code tools/fixtures/gen_blosc_encode_vectors.py}, numcodecs 0.17)
 * names an input made by a formula of its byte index, the settings, and the length and SHA-256 of what
 * c-blosc or liblz4 wrote: every Blosc internal compressor but zstd (whose encoder is Falcon's own), every
 * clevel and shuffle, type sizes 1 to 256, forced block sizes, tiny buffers, and buffers of many blocks; LZ4
 * blocks at four accelerations and every HC level, across the 64 KiB switch of liblz4's hash table.
 */
class BloscEncodeVectorsTest {

    private static final String[] WORDS = {"chunk   ", "shard   ", "array   ", "zarr    ", "falcon  ", "blosc   ",
        "group\n  ", "metadata", "attrs   ", "codec   "};

    private final Map<String, byte[]> inputs = new HashMap<>();

    @Test
    void bloscBuffersAreCBloscs() {
        List<String> mismatches = new ArrayList<>();
        int count = 0;
        for (String[] v : Vectors.read("blosc_encode_vectors.txt")) {
            if (!v[0].equals("blosc")) {
                continue;
            }
            byte[] data = input(v[1], Integer.parseInt(v[2]));
            int compressor = BloscEncoder.compressor(v[3]);
            int clevel = Integer.parseInt(v[4]);
            int shuffle = Integer.parseInt(v[5]);
            int typeSize = Integer.parseInt(v[6]);
            int blockSize = Integer.parseInt(v[7]);
            byte[] buffer = BloscEncoder.compress(data, typeSize, shuffle, blockSize, clevel, compressor);
            String what = String.join(" ", List.of(v).subList(0, 8));
            if (buffer.length != Integer.parseInt(v[8])
                    || !HexFormat.of().formatHex(buffer, 0, Math.min(16, buffer.length)).equals(v[9])
                    || !sha256(buffer).equals(v[10])) {
                mismatches.add(what + ": " + buffer.length + " bytes, header "
                        + HexFormat.of().formatHex(buffer, 0, Math.min(16, buffer.length)) + " vs " + v[8] + " " + v[9]);
            }
            assertArrayEquals(data, BloscDecoder.decompress(buffer), what);
            count++;
        }
        assertTrue(count > 1000, "vectors read: " + count);
        assertEquals(List.of(), mismatches);
    }

    /**
     * With a destination of the data's own size (hdf5-blosc's), each buffer either fits &mdash; compressed,
     * never stored whole, which takes 16 bytes more &mdash; and decodes, or is refused (null) where c-blosc
     * returns 0; with the data's size plus 16 it is numcodecs' buffer.
     */
    @Test
    void aDestinationOfTheDataSizeRefusesWhatDoesNotShrink() {
        int fitted = 0;
        int refused = 0;
        for (String[] v : Vectors.read("blosc_encode_vectors.txt")) {
            if (!v[0].equals("blosc")) {
                continue;
            }
            byte[] data = input(v[1], Integer.parseInt(v[2]));
            int compressor = BloscEncoder.compressor(v[3]);
            int clevel = Integer.parseInt(v[4]);
            int shuffle = Integer.parseInt(v[5]);
            int typeSize = Integer.parseInt(v[6]);
            int blockSize = Integer.parseInt(v[7]);
            String what = String.join(" ", List.of(v).subList(0, 8));
            assertArrayEquals(BloscEncoder.compress(data, typeSize, shuffle, blockSize, clevel, compressor),
                    BloscEncoder.compress(data, typeSize, shuffle, blockSize, clevel, compressor, data.length + 16), what);
            byte[] buffer = BloscEncoder.compress(data, typeSize, shuffle, blockSize, clevel, compressor, data.length);
            if (buffer == null) {
                refused++;
                continue;
            }
            assertTrue(buffer.length <= data.length && (buffer[2] & 0x02) == 0, what);
            assertArrayEquals(data, BloscDecoder.decompress(buffer), what);
            fitted++;
        }
        assertTrue(fitted > 500 && refused > 100, fitted + " fitted, " + refused + " refused");
        assertEquals(null, BloscEncoder.compress(new byte[15], 1, 0, 0, 5, BloscEncoder.LZ4, 15));
    }

    @Test
    void lz4BlocksAreLiblz4s() {
        List<String> mismatches = new ArrayList<>();
        int count = 0;
        for (String[] v : Vectors.read("blosc_encode_vectors.txt")) {
            if (!v[0].startsWith("lz4")) {
                continue;
            }
            byte[] data = input(v[1], Integer.parseInt(v[2]));
            int setting = Integer.parseInt(v[3]);
            byte[] block = v[0].equals("lz4") ? Lz4.compress(data, 0, data.length, setting)
                    : Lz4.compressHc(data, 0, data.length, setting);
            if (block.length != Integer.parseInt(v[4]) || !sha256(block).equals(v[5])) {
                mismatches.add(String.join(" ", List.of(v).subList(0, 4)) + ": " + block.length + " vs " + v[4]);
            }
            byte[] back = new byte[data.length];
            Lz4.decompress(block, 0, block.length, back, 0, back.length);
            assertArrayEquals(data, back, String.join(" ", v));
            count++;
        }
        assertTrue(count > 400, "vectors read: " + count);
        assertEquals(List.of(), mismatches);
    }

    private byte[] input(String generator, int n) {
        return inputs.computeIfAbsent(generator + " " + n, k -> {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) generate(generator, i);
            }
            return data;
        });
    }

    /** The generators of {@code gen_blosc_encode_vectors.py}, byte by byte. */
    private static int generate(String generator, int i) {
        return switch (generator) {
            case "ramp" -> ((i >> 2) / 3) >> (8 * (i & 3)) & 0xFF;
            case "noise16" -> {
                int k = i >> 1;
                yield ((k % 1000) + (fmix(k) & 3)) >> (8 * (i & 1)) & 0xFF;
            }
            case "f64" -> (int) (Double.doubleToRawLongBits((i >> 3) * 0.25) >>> (8 * (i & 7))) & 0xFF;
            case "text" -> WORDS[Integer.remainderUnsigned(fmix(i >> 3), WORDS.length)]
                    .getBytes(StandardCharsets.US_ASCII)[i & 7];
            case "runs" -> fmix(i >> 6) & 3;
            case "random" -> fmix(i) >>> 24;
            case "pattern3" -> "abc".charAt(i % 3);
            default -> throw new IllegalArgumentException(generator);
        };
    }

    /** murmur3's 32-bit finalizer of {@code x} times the golden ratio. */
    private static int fmix(int x) {
        x *= 0x9E3779B9;
        x ^= x >>> 16;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        x *= 0xC2B2AE35;
        x ^= x >>> 16;
        return x;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
