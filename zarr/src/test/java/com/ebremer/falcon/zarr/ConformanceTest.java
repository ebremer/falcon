package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Conformance against <b>zarr-python</b>, the reference implementation: every fixture under
 * {@code src/test/resources/fixtures} was written by zarr-python 3.2.1 (see
 * {@code tools/fixtures/gen_zarr_fixtures.py}) alongside a {@code .expected.json} sidecar holding the
 * values it round-trips. Falcon must read each store and produce exactly those values.
 *
 * <p>The sidecars keep these tests hermetic: no Python is needed at build time.
 */
class ConformanceTest {

    private static Path fixture(String name) {
        var url = ConformanceTest.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalStateException("fixture not found on the test classpath: " + name
                    + " (regenerate with tools/fixtures/gen_zarr_fixtures.py)");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject expected(String name) {
        try {
            return Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "plain_int32", "gzip_float64", "crc32c_int16", "gzip_crc32c_uint8", "bigendian_int32",
        "transpose_int32", "float32_nan_fill", "bool_array", "int64_array", "uint64_array",
        "float16_array", "uint16_2d", "sharded_int32", "sharded_2d_gzip", "partial_fill",
        "attrs_int32"})
    void readsZarrPythonFixture(String name) {
        JsonObject meta = expected(name);
        ZarrArray array = Zarr.open(fixture(name)).asArray();

        // shape and data type as zarr-python recorded them
        JsonArray shape = meta.get("shape").asArray();
        long[] actualShape = array.shape();
        assertEquals(shape.size(), actualShape.length, name + ": rank");
        for (int i = 0; i < shape.size(); i++) {
            assertEquals(shape.get(i).asNumber().longValue(), actualShape[i], name + ": shape[" + i + "]");
        }
        assertEquals(meta.get("dtype").asString(), array.dataType().name(), name + ": data type");

        // every element, read through the whole-array path
        List<JsonValue> values = meta.get("values").asArray().values();
        double[] actual = array.readDoubles();
        assertEquals(values.size(), actual.length, name + ": element count");
        for (int i = 0; i < values.size(); i++) {
            assertElement(name, i, values.get(i), actual[i]);
        }

        // attributes
        JsonObject attributes = meta.get("attributes").asObject();
        for (var entry : attributes.members().entrySet()) {
            assertEquals(entry.getValue(), array.attributes().get(entry.getKey()),
                    name + ": attribute " + entry.getKey());
        }
    }

    private static void assertElement(String name, int index, JsonValue want, double got) {
        String at = name + "[" + index + "]";
        switch (want) {
            case JsonString s -> {
                switch (s.value()) {
                    case "NaN" -> assertTrue(Double.isNaN(got), at + ": expected NaN, got " + got);
                    case "Infinity" -> assertEquals(Double.POSITIVE_INFINITY, got, at);
                    case "-Infinity" -> assertEquals(Double.NEGATIVE_INFINITY, got, at);
                    default -> throw new AssertionError(at + ": unexpected token " + s.value());
                }
            }
            case JsonBool b -> assertEquals(b.value() ? 1.0 : 0.0, got, at);
            case JsonNumber n -> assertEquals(n.doubleValue(), got, at);
            default -> throw new AssertionError(at + ": unexpected expected-value type " + want.typeName());
        }
    }

    /** A selection read must agree with the same region of the whole-array read. */
    @Test
    void selectionsAgreeWithWholeArrayReads() {
        ZarrArray a = Zarr.open(fixture("gzip_float64")).asArray(); // shape [4,6], chunks [2,3]
        double[] all = a.readDoubles();
        double[] slab = a.select(new long[] {1, 2}, new long[] {2, 3}).readDoubles();
        int k = 0;
        for (int row = 1; row < 3; row++) {
            for (int col = 2; col < 5; col++) {
                assertEquals(all[row * 6 + col], slab[k++], "slab element " + k);
            }
        }
    }

    /** zarr-python records the chunk grid; Falcon must derive the same chunk keys. */
    @Test
    void chunkKeysMatchTheStoreLayout() {
        ZarrArray a = Zarr.open(fixture("plain_int32")).asArray(); // shape [10], chunks [4] -> 3 chunks
        assertEquals(3, a.chunkCount());
        for (long c = 0; c < 3; c++) {
            Path chunk = fixture("plain_int32").resolve("c").resolve(Long.toString(c));
            assertTrue(Files.isRegularFile(chunk), "zarr-python wrote " + chunk);
            assertEquals("c/" + c, a.chunkKey(c));
        }
    }
}
