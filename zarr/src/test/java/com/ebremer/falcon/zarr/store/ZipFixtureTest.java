package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Archives zarr-python's ZipStore wrote in modes "w" and "a" ({@code gen_zarr_zip_fixtures.py}), with keys
 * written again: zarr-python adds a second entry of the same name, and reads the last (P2 F13). Falcon must
 * read what zarr-python reads, and rewriting such an archive must keep the newest entry of each name only.
 */
class ZipFixtureTest {

    private static Path fixture(String name) {
        try {
            return Path.of(ZipFixtureTest.class.getResource("/fixtures/" + name).toURI());
        } catch (Exception e) {
            throw new AssertionError("missing fixture " + name + " (tools/fixtures/gen_zarr_zip_fixtures.py)", e);
        }
    }

    private static JsonObject expected(String name) throws Exception {
        return Json.parse(Files.readAllBytes(fixture(name + ".expected.json"))).asObject();
    }

    /** The names the archive's central directory holds more than once, as the JDK's reader lists them. */
    private static List<String> duplicates(Path archive) throws Exception {
        Map<String, Integer> counts = new TreeMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                counts.merge(entries.nextElement().getName(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
    }

    private static List<String> strings(JsonValue array) {
        List<String> out = new ArrayList<>();
        for (JsonValue v : array.asArray().values()) {
            out.add(v.asString());
        }
        return out;
    }

    /** Every group's children, attributes, and array values, as zarr-python read them. */
    private static void readsAsZarrPythonDoes(Store store, JsonObject want) {
        ZarrGroup root = Zarr.openGroup(store);
        for (Map.Entry<String, JsonValue> e : want.get("children").asObject().members().entrySet()) {
            ZarrGroup group = e.getKey().isEmpty() ? root : root.group(e.getKey());
            assertEquals(strings(e.getValue()), group.childNames(), "children of '" + e.getKey() + "'");
        }
        for (Map.Entry<String, JsonValue> e : want.get("attributes").asObject().members().entrySet()) {
            JsonObject attributes = e.getKey().isEmpty() ? root.attributes() : root.child(e.getKey()).orElseThrow().attributes();
            assertEquals(e.getValue().asObject(), attributes, "attributes of '" + e.getKey() + "'");
        }
        for (Map.Entry<String, JsonValue> e : want.get("arrays").asObject().members().entrySet()) {
            ZarrArray array = root.array(e.getKey());
            JsonObject a = e.getValue().asObject();
            List<JsonValue> values = a.get("values").asArray().values();
            String dtype = a.get("dtype").asString();
            if (dtype.startsWith("int") || dtype.startsWith("uint")) {
                assertArrayEquals(values.stream().mapToInt(v -> (int) v.asNumber().longValue()).toArray(),
                        array.readInts(), e.getKey());
            } else if (dtype.startsWith("float")) {
                assertArrayEquals(values.stream().mapToDouble(v -> v.asNumber().doubleValue()).toArray(),
                        array.readDoubles(), e.getKey());
            } else {
                assertArrayEquals(strings(a.get("values")).toArray(new String[0]), array.readStrings(), e.getKey());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zip_w", "zip_a"})
    void readsWhatZarrPythonWroteWithKeysWrittenAgain(String name) throws Exception {
        JsonObject want = expected(name);
        Path archive = fixture(name + ".zip");
        assertEquals(strings(want.get("duplicates")), duplicates(archive), "the fixture holds names twice");
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertEquals(zip.list().stream().distinct().toList(), zip.list());
            readsAsZarrPythonDoes(zip, want);
        }
    }

    /** Adding to such an archive rewrites its central directory with the newest entry of each name only. */
    @ParameterizedTest
    @ValueSource(strings = {"zip_w", "zip_a"})
    void rewritingItKeepsTheNewestEntryOfEachName(String name, @TempDir Path tmp) throws Exception {
        JsonObject want = expected(name);
        Path copy = Files.copy(fixture(name + ".zip"), tmp.resolve(name + ".zip"));
        try (ZipStore zip = ZipStore.open(copy)) {
            zip.set("falcon/marker", new byte[] {1});
        }
        assertEquals(List.of(), duplicates(copy));
        try (ZipStore zip = ZipStore.openReadOnly(copy)) {
            readsAsZarrPythonDoes(zip, want);
        }
    }
}
