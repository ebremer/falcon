package com.ebremer.falcon.core.compress.blosc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.compress.Vectors;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

/**
 * Falcon's Blosc2 encoder against the frames hdf5plugin's Blosc2 filter (hdf5-blosc2, c-blosc2 3.3.2) wrote
 * for one-chunk datasets ({@code tools/fixtures/gen_blosc2_encoder_vectors.py}): plain one-chunk super-chunk
 * frames for rank 1, b2nd frames for ranks 2 to 4; every codec, clevel, and filter; type sizes 1 to 300;
 * zeros, NaN, repeated values, noise, and several blocks. Every frame is c-blosc2's byte for byte but those
 * whose chunk is zstd's (Falcon's zstd frames are not libzstd's): those must decode to the data, their frame
 * header the plugin's but for the lengths.
 */
class Blosc2EncoderTest {

    private record Vector(String name, int[] cd, int mask, byte[] input, byte[] frame, int length, String sha256,
                          byte[] header) {
    }

    private static List<Vector> vectors() {
        List<Vector> out = new ArrayList<>();
        Map<String, byte[]> inputs = new HashMap<>();
        for (String[] v : Vectors.read("blosc2_encoder_vectors.txt")) {
            int[] cd = Arrays.stream(v[1].split(",")).mapToInt(Integer::parseInt).toArray();
            byte[] input = v[3].startsWith("=") ? inputs.get(v[3].substring(1)) : inflate(Vectors.hex(v[3]));
            inputs.put(v[0], input);
            if (v[4].startsWith("sha256:")) {
                String[] parts = v[4].split(":");
                out.add(new Vector(v[0], cd, Integer.parseInt(v[2]), input, null, Integer.parseInt(parts[1]), parts[2],
                        Vectors.hex(parts[3])));
            } else {
                byte[] frame = Vectors.hex(v[4]);
                out.add(new Vector(v[0], cd, Integer.parseInt(v[2]), input, frame, frame.length, null, frame));
            }
        }
        return out;
    }

    @Test
    void writesWhatTheBlosc2FilterWrote() {
        List<Vector> vectors = vectors();
        assertTrue(vectors.size() > 900, "expected the full vector set, got " + vectors.size());
        List<String> wrong = new ArrayList<>();
        int exact = 0;
        int zstd = 0;
        int b2nd = 0;
        for (Vector v : vectors) {
            if (v.mask() != 0) {
                continue; // the filter failed (truncated precision): nothing of c-blosc2's to compare
            }
            byte[] h = v.header();
            int clevel = (h[27] & 0xff) >>> 4;
            int compressor = h[27] & 0x0f;
            int filter = h[76];
            Map<String, byte[]> metalayers = Blosc2Frame.metalayers(h, Blosc2Frame.be32(h, 11));
            byte[] meta = metalayers.get("b2nd");
            byte[] actual;
            if (meta != null) {
                b2nd++;
                Meta m = Meta.parse(meta);
                actual = Blosc2Encoder.b2ndFrame(v.input(), v.cd()[2], m.shape(), m.chunkShape(), m.blockShape(),
                        m.dtype(), clevel, compressor, filter);
            } else {
                actual = Blosc2Encoder.frame(v.input(), v.cd()[2], clevel, compressor, filter, v.cd()[1]);
            }
            if (compressor == Blosc2Encoder.ZSTD) {
                zstd++;
                byte[] decoded = decode(actual, v.input().length);
                if (!Arrays.equals(v.input(), decoded)) {
                    wrong.add(v.name() + ": decodes to something else");
                }
                int headerLen = Blosc2Frame.be32(h, 11);
                byte[] mine = Arrays.copyOf(actual, headerLen);
                byte[] theirs = Arrays.copyOf(h, headerLen);
                for (byte[] b : new byte[][] {mine, theirs}) {
                    Arrays.fill(b, 16, 24, (byte) 0); // frame length
                    Arrays.fill(b, 39, 47, (byte) 0); // compressed bytes
                }
                if (!Arrays.equals(theirs, mine)) {
                    wrong.add(v.name() + ": frame header differs");
                }
                continue;
            }
            boolean same = v.frame() != null ? Arrays.equals(v.frame(), actual)
                    : actual.length == v.length() && sha256(actual).equals(v.sha256());
            if (same) {
                exact++;
            } else {
                wrong.add(v.name() + ": " + actual.length + " bytes, expected " + v.length()
                        + firstDifference(v.frame(), actual));
            }
        }
        assertEquals(List.of(), wrong.subList(0, Math.min(wrong.size(), 40)), wrong.size() + " of " + vectors.size()
                + " vectors differ");
        System.out.println("Blosc2 encoder vectors: " + exact + " exact, " + zstd + " zstd, " + b2nd + " b2nd, of "
                + vectors.size());
        assertTrue(exact > 600, "exact: " + exact);
        assertTrue(zstd > 100 && b2nd > 400, "zstd " + zstd + ", b2nd " + b2nd);
    }

    @Test
    void framesReadBack() {
        Random random = new Random(32026);
        for (int compressor : new int[] {Blosc2Encoder.BLOSCLZ, Blosc2Encoder.LZ4, Blosc2Encoder.LZ4HC,
            Blosc2Encoder.ZLIB, Blosc2Encoder.ZSTD}) {
            for (int filter = 0; filter <= 3; filter++) {
                for (int typeSize : new int[] {1, 2, 3, 4, 8, 17, 300}) {
                    int items = 1 + random.nextInt(20000);
                    byte[] data = new byte[items * typeSize];
                    for (int i = 0; i < data.length; i++) {
                        data[i] = (byte) (random.nextInt(4) == 0 ? random.nextInt() : i / 7);
                    }
                    int clevel = random.nextInt(10);
                    byte[] plain = Blosc2Encoder.frame(data, typeSize, clevel, compressor, filter, 0);
                    assertArrayEquals(data, decode(plain, data.length), compressor + "/" + filter + "/" + typeSize);
                    long[] shape = {items / 3 + 1, 3};
                    byte[] cut = Arrays.copyOf(data, (int) (shape[0] * shape[1] * typeSize));
                    if (cut.length == data.length || typeSize > 255) {
                        continue;
                    }
                    byte[] nd = Blosc2Encoder.b2ndFrame(cut, typeSize, shape, new int[] {7, 2}, new int[] {3, 2},
                            Blosc2Encoder.opaqueDtype(typeSize), clevel, compressor, filter);
                    assertArrayEquals(cut, decode(nd, cut.length), "b2nd " + compressor + "/" + filter + "/" + typeSize);
                }
            }
        }
    }

    @Test
    void chunksAreCblosc2s() {
        // A chunk of zeros is the special zero chunk: its header alone.
        byte[] zeros = Blosc2Encoder.compress(new byte[4000], 4, 5, Blosc2Encoder.LZ4, Blosc2Encoder.SHUFFLE, 0);
        assertEquals(32, zeros.length);
        assertEquals(0x10, zeros[31] & 0xff);
        assertArrayEquals(new byte[4000], BloscDecoder.decompress(zeros));
        // Under 32 bytes, and at clevel 0, a chunk is stored whole.
        byte[] small = Blosc2Encoder.compress(new byte[31], 1, 9, Blosc2Encoder.ZSTD, Blosc2Encoder.NOFILTER, 0);
        assertEquals(32 + 31, small.length);
        assertEquals(0x07, small[2] & 0xff);
        byte[] stored = Blosc2Encoder.compress(new byte[1000], 4, 0, Blosc2Encoder.BLOSCLZ, Blosc2Encoder.SHUFFLE, 0);
        assertEquals(32 + 1000, stored.length);
        // The tuner's block sizes: split streams of shuffled small types, 64 KiB times the type at clevel 5.
        assertEquals(256 * 1024, Blosc2Encoder.automaticBlockSize(1 << 20, 4, 5, Blosc2Encoder.BLOSCLZ,
                Blosc2Encoder.SHUFFLE));
        assertEquals(128 * 1024, Blosc2Encoder.automaticBlockSize(1 << 20, 4, 5, Blosc2Encoder.BLOSCLZ,
                Blosc2Encoder.NOFILTER));
        assertEquals(256 * 1024, Blosc2Encoder.automaticBlockSize(1 << 20, 4, 5, Blosc2Encoder.ZLIB,
                Blosc2Encoder.SHUFFLE));
        assertEquals(1000, Blosc2Encoder.automaticBlockSize(1000, 4, 5, Blosc2Encoder.LZ4, Blosc2Encoder.SHUFFLE));
    }

    @Test
    void refusesWhatCblosc2Refuses() {
        byte[] data = new byte[100];
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.compress(data, 0, 5, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.compress(data, 4, 10, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.compress(data, 4, 5, 3, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.compress(data, 4, 5, 0, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.compress(data, 4, 5, 0, 1, 6));
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.compressor("snappy"));
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.b2ndFrame(data, 8, new long[] {5, 5},
                new int[] {5, 5}, new int[] {2, 2}, "|V8", 5, 0, 1)); // 25 items of 8 bytes are not 100 bytes
        assertThrows(IllegalArgumentException.class, () -> Blosc2Encoder.b2ndFrame(data, 1, new long[] {10, 10},
                new int[] {5, 5}, new int[] {6, 2}, "|V1", 5, 0, 1)); // a block larger than its chunk
    }

    private static byte[] decode(byte[] frame, int size) {
        Blosc2Frame f = Blosc2Frame.read(frame, size);
        B2ndArray array = B2ndArray.of(f);
        return array != null ? array.read() : f.chunk(0);
    }

    /** The b2nd metalayer's shapes and dtype. */
    private record Meta(long[] shape, int[] chunkShape, int[] blockShape, String dtype) {

        static Meta parse(byte[] meta) {
            int ndim = meta[2];
            int p = 4;
            long[] shape = new long[ndim];
            for (int i = 0; i < ndim; i++, p += 9) {
                shape[i] = Blosc2Frame.be64(meta, p + 1);
            }
            int[][] dims = new int[2][ndim];
            for (int[] d : dims) {
                p++;
                for (int i = 0; i < ndim; i++, p += 5) {
                    d[i] = Blosc2Frame.be32(meta, p + 1);
                }
            }
            int len = Blosc2Frame.be32(meta, p + 2);
            return new Meta(shape, dims[0], dims[1], new String(meta, p + 6, len, StandardCharsets.US_ASCII));
        }
    }

    private static byte[] inflate(byte[] packed) {
        Inflater inflater = new Inflater();
        inflater.setInput(packed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        try {
            while (!inflater.finished()) {
                out.write(buffer, 0, inflater.inflate(buffer));
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException(e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstDifference(byte[] expected, byte[] actual) {
        if (expected == null) {
            return "";
        }
        int n = Math.min(expected.length, actual.length);
        for (int i = 0; i < n; i++) {
            if (expected[i] != actual[i]) {
                return ", first difference at " + i;
            }
        }
        return "";
    }
}
