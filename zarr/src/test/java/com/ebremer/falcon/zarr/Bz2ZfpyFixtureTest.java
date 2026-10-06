package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Zarr v3 and v2 arrays zarr-python 3.4 wrote with numcodecs' {@code BZ2} and {@code ZFPY}
 * ({@code tools/fixtures/gen_zarr_bz2_zfpy_fixtures.py}): Falcon reads exactly what zarr-python reads (zfp's
 * lossy modes bit for bit), and writing the same values (for zfp's lossy modes, the values zarr-python was
 * given) stores the chunks zarr-python stored, byte for byte.
 */
class Bz2ZfpyFixtureTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "numcodecs_bz2_default_int32", "numcodecs_bz2_l9_float64", "numcodecs_bz2_l5_int16_2d",
        "numcodecs_bz2_three_blocks_uint8", "numcodecs_bz2_sharded_crc32c_int32", "numcodecs_bz2_concatenated_int32",
        "v2x_nc_bz2", "v2x_nc_bz2_default_f4", "v2x_nc_bz2_filter_zlib", "v2x_nc_delta_bz2",
        "v2x_nc_bz2_concatenated"})
    void readsZarrPythonsBz2Arrays(String name) {
        check(name, Zarr.open(fixture(name)).asArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "numcodecs_zfpy_f8_reversible_2d", "numcodecs_zfpy_f4_rate_1d", "numcodecs_zfpy_f8_precision_3d",
        "numcodecs_zfpy_f8_accuracy_4d", "numcodecs_zfpy_f4_expert_2d", "numcodecs_zfpy_i4_rate_2d",
        "numcodecs_zfpy_i8_reversible_3d", "numcodecs_zfpy_i4_precision_1d", "numcodecs_zfpy_i8_accuracy_4d",
        "numcodecs_zfpy_crc32c_f8", "numcodecs_zfpy_transpose_f4", "numcodecs_zfpy_sharded_f8",
        "v2x_nc_zfpy_f8_accuracy", "v2x_nc_zfpy_f4_rate", "v2x_nc_zfpy_i8_reversible_3d",
        "v2x_nc_zfpy_i4_precision_4d", "v2x_nc_zfpy_f8_expert", "v2x_nc_delta_zfpy_i4"})
    void readsZarrPythonsZfpyArrays(String name) {
        check(name, Zarr.open(fixture(name)).asArray());
    }

    /** Writing the values into an empty copy stores, chunk for chunk, numcodecs' bz2 streams (libbzip2's). */
    @ParameterizedTest
    @ValueSource(strings = {
        "numcodecs_bz2_default_int32", "numcodecs_bz2_l9_float64", "numcodecs_bz2_l5_int16_2d",
        "numcodecs_bz2_three_blocks_uint8", "numcodecs_bz2_sharded_crc32c_int32", "v2x_nc_bz2",
        "v2x_nc_bz2_default_f4", "v2x_nc_bz2_filter_zlib", "v2x_nc_delta_bz2"})
    void writingStoresWhatZarrPythonStored(String name) {
        Map<String, byte[]> original = files(fixture(name), true);
        MemoryStore store = store(files(fixture(name), false));
        ZarrArray empty = Zarr.openArray(store);
        if (expected(name).find("values").isPresent()) {
            write(empty, expected(name));
        } else { // a large array's sidecar holds a hash, which readsZarrPythonsBz2Arrays checks the elements against
            empty.writeRawBytes(Zarr.open(fixture(name)).asArray().readRawBytes());
        }
        assertEquals(original.keySet(), keys(store), name + ": keys");
        for (Map.Entry<String, byte[]> e : original.entrySet()) {
            if (!isMetadata(e.getKey())) {
                assertArrayEquals(e.getValue(), store.get(e.getKey()).orElseThrow(), name + ": chunk " + e.getKey());
            }
        }
    }

    /**
     * Chunks stored as two bzip2 streams, or one with bytes after it that are not a stream, read as Python's
     * {@code bz2.decompress} reads them; written again, each is the one stream numcodecs writes, as the
     * chunks zarr-python wrote are.
     */
    @ParameterizedTest
    @ValueSource(strings = {"numcodecs_bz2_concatenated_int32", "v2x_nc_bz2_concatenated"})
    void concatenatedStreamsAreRewrittenAsOne(String name) {
        Map<String, byte[]> original = files(fixture(name), true);
        MemoryStore store = store(original);
        ZarrArray a = Zarr.openArray(store);
        write(a, expected(name));
        int rewritten = 0;
        for (Map.Entry<String, byte[]> e : original.entrySet()) {
            byte[] now = store.get(e.getKey()).orElseThrow();
            if (isMetadata(e.getKey())) {
                continue;
            }
            if (!java.util.Arrays.equals(e.getValue(), now)) {
                rewritten++;
                assertTrue(e.getValue().length > now.length, e.getKey() + ": one stream is shorter");
                assertEquals('B', now[0]);
            }
        }
        assertEquals(name.startsWith("v2") ? 3 : 2, rewritten, name + ": the chunks rewritten by hand");
        check(name, a);
    }

    /**
     * Writing the values zarr-python was given into an empty copy of each zfpy array stores, chunk for chunk,
     * zfpy's streams: in every mode, type, and dimensionality, after a transpose, under a shard, before crc32c,
     * and in v2 after a delta filter (which hands zfpy a flat array).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "numcodecs_zfpy_f8_reversible_2d", "numcodecs_zfpy_f4_rate_1d", "numcodecs_zfpy_f8_precision_3d",
        "numcodecs_zfpy_f8_accuracy_4d", "numcodecs_zfpy_f4_expert_2d", "numcodecs_zfpy_i4_rate_2d",
        "numcodecs_zfpy_i8_reversible_3d", "numcodecs_zfpy_i4_precision_1d", "numcodecs_zfpy_i8_accuracy_4d",
        "numcodecs_zfpy_crc32c_f8", "numcodecs_zfpy_transpose_f4", "numcodecs_zfpy_sharded_f8",
        "v2x_nc_zfpy_f8_accuracy", "v2x_nc_zfpy_f4_rate", "v2x_nc_zfpy_i8_reversible_3d",
        "v2x_nc_zfpy_i4_precision_4d", "v2x_nc_zfpy_f8_expert", "v2x_nc_delta_zfpy_i4"})
    void writingStoresWhatZfpyStored(String name) {
        Map<String, byte[]> original = files(fixture(name), true);
        MemoryStore store = store(files(fixture(name), false));
        JsonObject want = expected(name);
        JsonValue inputs = want.find("inputs").orElse(want.get("values")); // a lossless array's values are its inputs
        write(Zarr.openArray(store), JsonObject.builder().put("values", inputs).build());
        assertEquals(original.keySet(), keys(store), name + ": keys");
        for (Map.Entry<String, byte[]> e : original.entrySet()) {
            if (!isMetadata(e.getKey())) {
                assertArrayEquals(e.getValue(), store.get(e.getKey()).orElseThrow(), name + ": chunk " + e.getKey());
            }
        }
        check(name, Zarr.openArray(store)); // and reads back as zarr-python read its own
    }

    /**
     * The builder's {@code zfpy} methods write the codec zarr-python writes (its configuration only the
     * arguments given), and zarr-python's inputs written into the array give its chunks byte for byte.
     */
    @Test
    void theBuilderWritesZarrPythonsZfpyArrays() {
        Map<String, ArraySpec> specs = Map.of(
                "numcodecs_zfpy_f4_rate_1d", ArraySpec.builder(new long[] {1000}, DataType.FLOAT32).chunkShape(300)
                        .zfpyRate(12).build(),
                "numcodecs_zfpy_f8_precision_3d", ArraySpec.builder(new long[] {9, 10, 11}, DataType.FLOAT64)
                        .chunkShape(5, 4, 6).zfpyPrecision(20).build(),
                "numcodecs_zfpy_f8_accuracy_4d", ArraySpec.builder(new long[] {6, 5, 7, 9}, DataType.FLOAT64)
                        .chunkShape(4, 3, 5, 4).zfpyAccuracy(1e-3).build(),
                "numcodecs_zfpy_f8_reversible_2d", ArraySpec.builder(new long[] {37, 23}, DataType.FLOAT64)
                        .chunkShape(16, 10).zfpy().build(),
                "numcodecs_zfpy_crc32c_f8", ArraySpec.builder(new long[] {50}, DataType.FLOAT64).chunkShape(20)
                        .zfpy().crc32c().build(),
                "numcodecs_zfpy_sharded_f8", ArraySpec.builder(new long[] {10, 7}, DataType.FLOAT64).chunkShape(8, 5)
                        .sharding(4, 5).zfpy().build());
        specs.forEach((name, spec) -> {
            Map<String, byte[]> original = files(fixture(name), true);
            MemoryStore store = new MemoryStore();
            ZarrArray a = Zarr.createArray(store, spec);
            JsonValue want = Json.parse(original.get("zarr.json")).asObject().get("codecs");
            V2CreateTest.assertSame(want, spec.toJson().get("codecs"), name + ": codecs"); // 12 is 12.0
            JsonObject expected = expected(name);
            JsonValue inputs = expected.find("inputs").orElse(expected.get("values"));
            write(a, JsonObject.builder().put("values", inputs).build());
            for (Map.Entry<String, byte[]> e : original.entrySet()) {
                if (!isMetadata(e.getKey())) {
                    assertArrayEquals(e.getValue(), store.get(e.getKey()).orElseThrow(), name + ": chunk " + e.getKey());
                }
            }
        });
        // zfp compresses only int32, int64, float32, and float64, in 1 to 4 dimensions; not strings
        assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {8}, DataType.INT16).zfpy().build());
        assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {2, 2, 2, 2, 2}, DataType.FLOAT32).zfpy().build());
        assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {8}, DataType.STRING).zfpy().build());
        assertThrows(IllegalArgumentException.class, () -> ArraySpec.builder(new long[] {8}, DataType.FLOAT32)
                .zfpyRate(0));
        // a cast to a type zfp compresses
        ArraySpec cast = ArraySpec.builder(new long[] {8}, DataType.FLOAT64).castValue(DataType.FLOAT32).zfpy().build();
        ZarrArray a = Zarr.createArray(new MemoryStore(), cast);
        a.writeDoubles(new double[] {0.5, 1.5, -2, 3.25, 0, 7, 8.125, -1});
        assertArrayEquals(new double[] {0.5, 1.5, -2, 3.25, 0, 7, 8.125, -1}, a.readDoubles());
    }

    /**
     * Resizing rewrites only the chunk the new edge cuts: growing again stores it with the fill value past the
     * old edge, re-encoded (zfp's lossy modes then move its other values a little, as zarr-python's own
     * read-modify-write does), and leaves every other chunk as it was.
     */
    @Test
    void resizingAZfpyArrayRewritesOnlyTheEdgeChunk() {
        MemoryStore store = store(files(fixture("numcodecs_zfpy_f4_rate_1d"), true));
        Map<String, byte[]> before = files(fixture("numcodecs_zfpy_f4_rate_1d"), true);
        ZarrArray a = Zarr.openArray(store);
        double[] values = a.readDoubles();
        ZarrArray grown = a.resize(950).resize(1000);
        double[] got = grown.readDoubles();
        for (String key : List.of("c/0", "c/1", "c/2")) {
            assertArrayEquals(before.get(key), store.get(key).orElseThrow(), key);
        }
        assertArrayEquals(java.util.Arrays.copyOf(values, 900), java.util.Arrays.copyOf(got, 900));
        for (int i = 900; i < 1000; i++) {
            assertEquals(i < 950 ? values[i] : 0, got[i], 0.05, "element " + i); // the fill, re-encoded lossily too
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** Compares the array's shape, codec names, and elements (or their SHA-256) with the sidecar's. */
    static void check(String name, ZarrArray a) {
        JsonObject want = expected(name);
        assertArrayEquals(want.get("shape").asArray().values().stream().mapToLong(v -> v.asNumber().longValue())
                .toArray(), a.shape(), name);
        assertEquals(want.get("codecs").asArray().values().stream().map(JsonValue::asString).toList(),
                a.codecNames(), name);
        if (want.find("sha256").isPresent()) {
            assertEquals(want.get("sha256").asString(), sha256(a.readRawBytes()), name);
            return;
        }
        List<JsonValue> values = want.get("values").asArray().values();
        switch (a.dataType().kind()) {
            case FLOAT -> {
                double[] got = a.readDoubles();
                assertEquals(values.size(), got.length, name);
                for (int i = 0; i < got.length; i++) {
                    // bit for bit: zfp's lossy modes decode to exactly zarr-python's values
                    assertEquals(Double.doubleToRawLongBits(values.get(i).asNumber().doubleValue()),
                            Double.doubleToRawLongBits(got[i]), name + "[" + i + "]");
                }
            }
            default -> assertArrayEquals(values.stream().mapToLong(v -> v.asNumber().longValue()).toArray(),
                    a.readLongs(), name);
        }
    }

    private static void write(ZarrArray a, JsonObject want) {
        List<JsonValue> values = want.get("values").asArray().values();
        switch (a.dataType().kind()) {
            case FLOAT -> a.writeDoubles(values.stream().mapToDouble(v -> v.asNumber().doubleValue()).toArray());
            default -> a.writeLongs(values.stream().mapToLong(v -> v.asNumber().longValue()).toArray());
        }
    }

    private static boolean isMetadata(String key) {
        return key.equals("zarr.json") || key.startsWith(".");
    }

    /** The fixture's files by store key: the metadata, and the chunks too if {@code withChunks}. */
    static Map<String, byte[]> files(Path dir, boolean withChunks) {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String key = dir.relativize(f).toString().replace('\\', '/');
                if (withChunks || isMetadata(key)) {
                    out.put(key, Files.readAllBytes(f));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static MemoryStore store(Map<String, byte[]> files) {
        MemoryStore store = new MemoryStore();
        files.forEach(store::set);
        return store;
    }

    static java.util.Set<String> keys(MemoryStore store) {
        return new java.util.TreeSet<>(store.list());
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static Path fixture(String name) {
        try {
            return Path.of(Bz2ZfpyFixtureTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name, e);
        }
    }

    static JsonObject expected(String name) {
        try {
            return Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
