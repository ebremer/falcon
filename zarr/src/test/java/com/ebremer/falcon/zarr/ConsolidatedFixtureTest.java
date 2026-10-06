package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.Store;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Consolidated metadata as zarr-python 3.4 writes it (P2 F2; the stores come from
 * {@code tools/fixtures/gen_zarr_consolidated_fixtures.py}). Each store's {@code .members.json} sidecar holds
 * what zarr-python sees below the root, opened by default (answering from consolidated metadata wherever
 * there is some) and opened reading each node's own metadata; Falcon's {@link Zarr#open(Store)} and
 * {@link Zarr#open(Store, boolean) open(store, false)} must see the same, and the first must do it without
 * reading the store for nodes the snapshot describes.
 */
class ConsolidatedFixtureTest {

    private static Path fixture(String name) {
        try {
            return Path.of(ConsolidatedFixtureTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name
                    + " (regenerate with tools/fixtures/gen_zarr_consolidated_fixtures.py)", e);
        }
    }

    private static JsonObject sidecar(String name) {
        try {
            return Json.parse(Files.readAllBytes(fixture(name + ".members.json"))).asObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records each read a call makes; over HTTP each is a round trip. */
    static final class RecordingStore implements Store {
        private final Store delegate;
        final List<String> calls = new ArrayList<>();

        RecordingStore(Store delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<byte[]> get(String key) {
            calls.add("get " + key);
            return delegate.get(key);
        }

        @Override
        public Optional<byte[]> getRange(String key, long offset, long length) {
            calls.add("getRange " + key);
            return delegate.getRange(key, offset, length);
        }

        @Override
        public boolean exists(String key) {
            calls.add("exists " + key);
            return delegate.exists(key);
        }

        @Override
        public OptionalLong size(String key) {
            calls.add("size " + key);
            return delegate.size(key);
        }

        @Override
        public List<String> list() {
            calls.add("list");
            return delegate.list();
        }

        @Override
        public List<String> listPrefix(String prefix) {
            calls.add("listPrefix " + prefix);
            return delegate.listPrefix(prefix);
        }

        @Override
        public List<String> listDir(String prefix) {
            calls.add("listDir " + prefix);
            return delegate.listDir(prefix);
        }

        @Override
        public boolean isWritable() {
            return delegate.isWritable();
        }

        @Override
        public void set(String key, byte[] value) {
            calls.add("set " + key);
            delegate.set(key, value);
        }

        @Override
        public void delete(String key) {
            calls.add("delete " + key);
            delegate.delete(key);
        }
    }

    /**
     * Every node below {@code group}, depth first in name order, in the sidecar's form. A node Falcon cannot
     * open is entered as its path alone, after checking that {@link ZarrGroup#child} says why and
     * {@link ZarrGroup#children()} leaves it out.
     */
    private static List<JsonObject> walk(ZarrGroup group, String prefix) {
        List<JsonObject> out = new ArrayList<>();
        List<String> openable = group.children().stream().map(ZarrNode::name).toList();
        for (String name : group.childNames()) {
            String path = prefix + name;
            if (!openable.contains(name)) {
                assertThrows(ZarrUnsupportedException.class, () -> group.child(name), path);
                out.add(JsonObject.builder().put("path", path).build());
                continue;
            }
            ZarrNode node = group.child(name).orElseThrow();
            assertEquals(path, node.path());
            JsonObject.Builder b = JsonObject.builder().put("path", path)
                    .put("kind", node.isGroup() ? "group" : "array").put("attributes", node.attributes());
            if (node instanceof ZarrArray a) {
                List<JsonValue> shape = new ArrayList<>();
                for (long d : a.shape()) {
                    shape.add(JsonNumber.of(d));
                }
                b.put("shape", new JsonArray(shape)).put("dtype", numpyName(a.dataType()));
            }
            out.add(b.build());
            if (node instanceof ZarrGroup child) {
                out.addAll(walk(child, path + "/"));
            }
        }
        return out;
    }

    /** The numpy name zarr-python's sidecar gives a data type: datetime64[s] for numpy.datetime64 in seconds. */
    private static String numpyName(DataType type) {
        if (type.kind() != DataTypeKind.DATETIME && type.kind() != DataTypeKind.TIMEDELTA) {
            return type.name();
        }
        String base = type.kind() == DataTypeKind.DATETIME ? "datetime64" : "timedelta64";
        return type.unit().equals("generic") ? base
                : base + "[" + (type.scaleFactor() == 1 ? "" : type.scaleFactor()) + type.unit() + "]";
    }

    /**
     * Falcon's view matches zarr-python's: the same paths in the same (sorted) order, and for each node
     * the same kind, attributes, shape, and data type. Falcon opens every node in these trees: the
     * datetime64 array it could not open before opens since P2 F14.
     */
    private static void assertSameView(JsonValue expected, List<JsonObject> actual, String what) {
        JsonArray want = expected.asArray();
        List<String> wantPaths = new ArrayList<>();
        for (JsonValue v : want.values()) {
            wantPaths.add(v.asObject().get("path").asString());
        }
        // zarr-python sorts full paths; a depth-first walk in name order lists them the same way here
        List<String> gotPaths = actual.stream().map(o -> o.get("path").asString()).sorted().toList();
        assertEquals(wantPaths, gotPaths, what + ": paths");
        for (JsonObject got : actual) {
            JsonObject w = want.values().get(wantPaths.indexOf(got.get("path").asString())).asObject();
            assertTrue(got.has("kind"), what + ": Falcon cannot open " + w);
            assertEquals(w.get("kind"), got.get("kind"), what + ": " + w);
            assertEquals(w.get("attributes"), got.get("attributes"), what + ": " + w);
            if (w.has("shape")) {
                assertEquals(w.get("shape"), got.get("shape"), what + ": " + w);
                assertEquals(w.get("dtype"), got.get("dtype"), what + ": " + w);
            }
        }
    }

    private static void assertMatchesZarrPython(String name) {
        JsonObject sidecar = sidecar(name);
        Store store = FileSystemStore.openReadOnly(fixture(name));
        assertSameView(sidecar.get("consolidated"), walk(Zarr.openGroup(store), ""), name + " consolidated");
        assertSameView(sidecar.get("per_node"), walk(Zarr.openGroup(store, false), ""), name + " per node");
    }

    @Test
    void aV3ConsolidatedTreeReadsAsZarrPythonReadsIt() {
        assertMatchesZarrPython("consolidated_v3");
    }

    @Test
    void aV2ConsolidatedTreeReadsAsZarrPythonReadsIt() {
        assertMatchesZarrPython("consolidated_v2");
    }

    @Test
    void aTreeConsolidatedAtASubgroupReadsAsZarrPythonReadsIt() {
        assertMatchesZarrPython("consolidated_subgroup");
    }

    /**
     * Changed after consolidation: the snapshot still shows the removed array, the old attributes, and not
     * the new array, for zarr-python opening it by default as for Falcon; reading node by node shows the
     * store as it is.
     */
    @Test
    void aStaleSnapshotReadsAsZarrPythonReadsIt() {
        assertMatchesZarrPython("consolidated_stale");
        ZarrGroup root = Zarr.openGroup(FileSystemStore.openReadOnly(fixture("consolidated_stale")));
        assertArrayEquals(new int[] {0, 0, 0, 0}, root.array("a").readInts()); // listed; its chunks are gone
    }

    /** P2 F2: once the root is read, walking a consolidated v3 tree reads nothing more from the store. */
    @Test
    void walkingAConsolidatedV3TreeCostsOneRequest() {
        RecordingStore store = new RecordingStore(FileSystemStore.openReadOnly(fixture("consolidated_v3")));
        ZarrGroup root = Zarr.openGroup(store);
        assertTrue(root.isConsolidated());
        walk(root, "");
        assertEquals(List.of("get zarr.json"), store.calls);

        store.calls.clear(); // arrays still read their chunks from the store
        assertArrayEquals(new double[] {0.5, 1.5, 2.5, 3.5, 4.5, 5.5}, root.group("g").array("inner").readDoubles());
        assertTrue(store.calls.stream().allMatch(c -> c.startsWith("get g/inner/c/")), store.calls::toString);
    }

    /** A v2 root group costs one more request, for .zmetadata; then nothing more. */
    @Test
    void walkingAConsolidatedV2TreeCostsTheRootsRequests() {
        RecordingStore store = new RecordingStore(FileSystemStore.openReadOnly(fixture("consolidated_v2")));
        ZarrGroup root = Zarr.openGroup(store);
        assertTrue(root.isConsolidated());
        walk(root, "");
        assertEquals(List.of("get zarr.json", "get .zarray", "get .zgroup", "get .zattrs", "get .zmetadata"),
                store.calls);
        assertArrayEquals(new int[] {1, 2, 3, 4}, root.array("a").readInts());

        store.calls.clear();
        ZarrGroup perNode = Zarr.openGroup(store, false);
        assertFalse(perNode.isConsolidated());
        assertEquals(List.of("get zarr.json", "get .zarray", "get .zgroup", "get .zattrs"), store.calls);
        walk(perNode, "");
        assertTrue(store.calls.contains("get g/inner/.zarray"));
    }

    /** A group below a root without a snapshot answers from its own once it is opened. */
    @Test
    void aConsolidatedSubgroupAnswersFromItsSnapshot() {
        RecordingStore store = new RecordingStore(FileSystemStore.openReadOnly(fixture("consolidated_subgroup")));
        ZarrGroup root = Zarr.openGroup(store);
        assertFalse(root.isConsolidated());
        ZarrGroup g = root.group("g");
        assertTrue(g.isConsolidated());
        store.calls.clear();
        assertEquals(List.of("deep", "inner"), g.childNames());
        walk(g, "g/");
        assertEquals(List.of(), store.calls);

        assertFalse(Zarr.openGroup(store, false).group("g").isConsolidated()); // not when told not to
    }

    /** P1's fixture, consolidated by zarr-python, now answers from its snapshot. */
    @Test
    void p1sConsolidatedFixtureAnswersFromItsSnapshot() {
        ZarrGroup root = Zarr.openGroup(FileSystemStore.openReadOnly(fixture("p1_consolidated")));
        assertTrue(root.isConsolidated());
        assertTrue(root.group("g").isConsolidated());
        assertEquals(List.of("deep", "inner"), root.group("g").childNames());
    }
}
