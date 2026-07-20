package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Zarr <b>v2</b> read conformance against zarr-python (see
 * {@code tools/fixtures/gen_zarr_v2_fixtures.py}). Each fixture is a genuine v2 store
 * ({@code .zarray}/{@code .zgroup}/{@code .zattrs}, NumPy dtype strings, {@code compressor} objects);
 * Falcon must translate it to the v3 model and read exactly the values zarr-python wrote.
 */
class V2ConformanceTest {

    private static Path fixture(String name) {
        try {
            return Path.of(V2ConformanceTest.class.getResource("/fixtures/" + name).toURI());
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
        "v2_le_int32", "v2_be_int32", "v2_gzip_float64", "v2_zstd_int32", "v2_blosc_float64",
        "v2_uint8", "v2_bool", "v2_float32_nan", "v2_int64", "v2_slash_2d", "v2_partial_fill",
        "v2_attrs"})
    void readsZarrPythonV2Fixture(String name) {
        JsonObject meta = expected(name);
        ZarrArray array = Zarr.open(fixture(name)).asArray();

        var shape = meta.get("shape").asArray();
        assertEquals(shape.size(), array.rank(), name + ": rank");
        for (int i = 0; i < shape.size(); i++) {
            assertEquals(shape.get(i).asNumber().longValue(), array.shape()[i], name + ": shape[" + i + "]");
        }

        List<JsonValue> values = meta.get("values").asArray().values();
        double[] actual = array.readDoubles();
        assertEquals(values.size(), actual.length, name + ": element count");
        for (int i = 0; i < values.size(); i++) {
            assertElement(name + "[" + i + "]", values.get(i), actual[i]);
        }

        for (var entry : meta.get("attributes").asObject().members().entrySet()) {
            assertEquals(entry.getValue(), array.attributes().get(entry.getKey()),
                    name + ": attribute " + entry.getKey());
        }
    }

    private static void assertElement(String at, JsonValue want, double got) {
        switch (want) {
            case JsonString s -> {
                switch (s.value()) {
                    case "NaN" -> assertTrue(Double.isNaN(got), at + ": expected NaN");
                    case "Infinity" -> assertEquals(Double.POSITIVE_INFINITY, got, at);
                    case "-Infinity" -> assertEquals(Double.NEGATIVE_INFINITY, got, at);
                    default -> throw new AssertionError(at + ": " + s.value());
                }
            }
            case JsonBool b -> assertEquals(b.value() ? 1.0 : 0.0, got, at);
            case JsonNumber n -> assertEquals(n.doubleValue(), got, at);
            default -> throw new AssertionError(at + ": " + want.typeName());
        }
    }

    /** Big-endian v2 data must be read with the byte order from the dtype string. */
    @Test
    void bigEndianV2IsReadCorrectly() {
        ZarrArray a = Zarr.open(fixture("v2_be_int32")).asArray();
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, a.readInts());
    }

    /**
     * A v2 hierarchy of groups and arrays, built by hand ({@code .zgroup}/{@code .zarray}/{@code .zattrs}
     * with chunk keys joined by the dimension separator) since zarr-python 3.x does not write v2 groups.
     */
    @Test
    void readsV2GroupHierarchy() {
        MemoryStore store = new MemoryStore();
        put(store, ".zgroup", "{\"zarr_format\":2}");
        put(store, ".zattrs", "{\"title\":\"root\"}");

        // an int32 array "temp" at the root, one chunk, no compressor, "." separator
        put(store, "temp/.zarray", zarray("[6]", "[4]", "\"<i4\"", "0", "."));
        putChunk(store, "temp/0", intBytesLe(0, 1, 2, 3));
        putChunk(store, "temp/1", intBytesLe(4, 5, 0, 0)); // edge chunk padded to full size

        // a subgroup with an array
        put(store, "sub/.zgroup", "{\"zarr_format\":2}");
        put(store, "sub/vals/.zarray", zarray("[4]", "[4]", "\"<i4\"", "0", "."));
        putChunk(store, "sub/vals/0", intBytesLe(10, 20, 30, 40));

        ZarrGroup root = Zarr.open(store).asGroup();
        assertEquals("root", root.attributes().get("title").asString());
        assertEquals(List.of("sub", "temp"), root.childNames());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, root.array("temp").readInts());
        assertArrayEquals(new int[] {10, 20, 30, 40}, root.group("sub").array("vals").readInts());
    }

    private static String zarray(String shape, String chunks, String dtype, String fill, String sep) {
        return "{\"zarr_format\":2,\"shape\":" + shape + ",\"chunks\":" + chunks
                + ",\"dtype\":" + dtype + ",\"fill_value\":" + fill + ",\"order\":\"C\","
                + "\"filters\":null,\"dimension_separator\":\"" + sep + "\",\"compressor\":null}";
    }

    private static void put(Store store, String key, String value) {
        store.set(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void putChunk(Store store, String key, byte[] value) {
        store.set(key, value);
    }

    private static byte[] intBytesLe(int... values) {
        byte[] out = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            out[i * 4] = (byte) values[i];
            out[i * 4 + 1] = (byte) (values[i] >>> 8);
            out[i * 4 + 2] = (byte) (values[i] >>> 16);
            out[i * 4 + 3] = (byte) (values[i] >>> 24);
        }
        return out;
    }
}
