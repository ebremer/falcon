package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * lossy modes bit for bit), writing the same values into a bz2 array stores the chunks zarr-python stored, and
 * a zfpy array refuses every write.
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

    /** A zfpy array is read-only: every write, even of the fill value alone, is refused before the store changes. */
    @ParameterizedTest
    @ValueSource(strings = {"numcodecs_zfpy_f8_reversible_2d", "numcodecs_zfpy_i4_rate_2d", "numcodecs_zfpy_sharded_f8",
        "numcodecs_zfpy_crc32c_f8", "v2x_nc_zfpy_f4_rate", "v2x_nc_delta_zfpy_i4"})
    void zfpyArraysRefuseWrites(String name) {
        Map<String, byte[]> original = files(fixture(name), true);
        MemoryStore store = store(original);
        ZarrArray a = Zarr.openArray(store);
        int count = (int) java.util.Arrays.stream(a.shape()).reduce(1, (x, y) -> x * y);
        int size = a.dataType().byteCount();
        ZarrUnsupportedException whole = assertThrows(ZarrUnsupportedException.class,
                () -> a.writeRawBytes(new byte[count * size]));
        assertTrue(whole.getMessage().contains("numcodecs.zfpy"), whole.getMessage());
        long[] origin = new long[a.shape().length];
        long[] one = new long[origin.length];
        java.util.Arrays.fill(one, 1);
        assertThrows(ZarrUnsupportedException.class, () -> a.select(origin, one).writeRawBytes(new byte[size]));
        assertEquals(original.keySet(), keys(store));
        for (Map.Entry<String, byte[]> e : original.entrySet()) {
            assertArrayEquals(e.getValue(), store.get(e.getKey()).orElseThrow(), e.getKey());
        }
        check(name, a);
    }

    /**
     * Shrinking a zfpy array writes no chunk, so it is done; growing it must set the part of an edge chunk it
     * brings back inside the array to the fill value, a write, so it is refused and the shape stays.
     */
    @Test
    void resizingAZfpyArrayWritesNoChunk() {
        MemoryStore store = store(files(fixture("numcodecs_zfpy_f4_rate_1d"), true));
        ZarrArray a = Zarr.openArray(store);
        double[] before = a.readDoubles();
        ZarrArray shrunk = a.resize(950);
        assertArrayEquals(java.util.Arrays.copyOf(before, 950), shrunk.readDoubles());
        assertThrows(ZarrUnsupportedException.class, () -> shrunk.resize(1000));
        assertEquals(950, Zarr.openArray(store).shape()[0]);
        ZarrArray cut = shrunk.resize(600); // the chunks past it are deleted: no write either
        assertEquals(List.of("c/0", "c/1", "zarr.json"), store.list().stream().sorted().toList());
        assertArrayEquals(java.util.Arrays.copyOf(before, 600), cut.readDoubles());
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
