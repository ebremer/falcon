package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Path-based navigation (P2 F9): {@code group.child("a/b/c")}, {@code group(...)}, {@code array(...)}, and
 * {@code Zarr.open(store, "a/b/c")}. A node is opened directly, one request whatever its depth, unless a
 * consolidated group can answer from its snapshot, with none.
 */
class PathNavigationTest {

    /** root / model (attributes) / layers / weights (int8 [3]); root / data (float64 [2]). */
    private static void tree(Store store) {
        ZarrGroup root = Zarr.createGroup(store);
        ZarrGroup layers = root.createGroup("model", Json.parse("{\"kind\":\"net\"}").asObject()).createGroup("layers");
        layers.createArray("weights", ArraySpec.builder(new long[] {3}, DataType.INT8).build())
                .writeInts(new int[] {1, 2, 3});
        root.createArray("data", ArraySpec.builder(new long[] {2}, DataType.FLOAT64).build())
                .writeDoubles(new double[] {0.5, 1.5});
    }

    @Test
    void aChildIsReachedByItsPath() {
        MemoryStore store = new MemoryStore();
        tree(store);
        ZarrGroup root = Zarr.openGroup(store);
        ZarrArray weights = root.array("model/layers/weights");
        assertEquals("model/layers/weights", weights.path());
        assertEquals("weights", weights.name());
        assertArrayEquals(new int[] {1, 2, 3}, weights.readInts());

        ZarrGroup layers = root.group("model/layers");
        assertEquals(List.of("weights"), layers.childNames());
        assertEquals("model/layers", layers.path());
        assertTrue(root.child("model/layers/weights").isPresent());
        assertTrue(root.group("model").child("layers/weights").isPresent());
        assertEquals("net", root.group("model").attributes().get("kind").asString());
    }

    @Test
    void zarrOpensANodeByItsPath() {
        MemoryStore store = new MemoryStore();
        tree(store);
        assertArrayEquals(new int[] {1, 2, 3}, Zarr.openArray(store, "model/layers/weights").readInts());
        assertArrayEquals(new int[] {1, 2, 3}, Zarr.openArray(store, "/model/layers/weights").readInts());
        assertEquals(List.of("layers"), Zarr.openGroup(store, "model").childNames());
        assertEquals("", Zarr.open(store, "").path());
        assertEquals("", Zarr.open(store, "/").path());
        assertThrows(IllegalStateException.class, () -> Zarr.openArray(store, "model"));
    }

    @Test
    void absentNodesAndBadPaths() {
        MemoryStore store = new MemoryStore();
        tree(store);
        ZarrGroup root = Zarr.openGroup(store);
        assertFalse(root.child("model/nothing").isPresent());
        assertThrows(NoSuchElementException.class, () -> root.array("model/nothing"));
        assertThrows(NoSuchElementException.class, () -> Zarr.open(store, "model/nothing"));
        assertThrows(IllegalArgumentException.class, () -> root.group("model/layers/weights")); // an array
        for (String bad : new String[] {"", "/model", "model/", "model//layers", "model/./layers", "model/../data", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> root.child(bad), bad);
        }
        for (String bad : new String[] {"model/", "//model", "model//layers", "a/../b"}) {
            assertThrows(IllegalArgumentException.class, () -> Zarr.open(store, bad), bad);
        }
        // An empty store has no root to open.
        assertThrows(ZarrFormatException.class, () -> Zarr.open(new MemoryStore(), ""));
    }

    @Test
    void aDeepNodeCostsOneRequest() {
        PartialChunkIoTest.RecordingStore store = new PartialChunkIoTest.RecordingStore();
        tree(store);
        ZarrGroup root = Zarr.openGroup(store, false);
        store.calls.clear();
        root.array("model/layers/weights");
        assertEquals(List.of("get model/layers/weights/zarr.json"), store.calls);

        store.calls.clear();
        Zarr.openArray(store, "model/layers/weights");
        assertEquals(List.of("get model/layers/weights/zarr.json"), store.calls);
    }

    @Test
    void aConsolidatedGroupAnswersFromItsSnapshot() {
        PartialChunkIoTest.RecordingStore store = new PartialChunkIoTest.RecordingStore();
        tree(store);
        Zarr.openGroup(store).consolidate();
        ZarrGroup root = Zarr.openGroup(store);
        store.calls.clear();
        ZarrGroup layers = root.group("model/layers");
        ZarrArray weights = root.array("model/layers/weights");
        assertTrue(layers.isConsolidated());
        assertEquals(List.of("weights"), layers.childNames());
        assertEquals(List.of(), store.calls); // no request: everything came from the root's snapshot
        assertArrayEquals(new int[] {1, 2, 3}, weights.readInts());
        assertFalse(root.child("model/nothing").isPresent());
    }

    @Test
    void zarrOpenUsesTheNodesOwnConsolidatedMetadata() {
        MemoryStore store = new MemoryStore();
        tree(store);
        Zarr.openGroup(store).group("model").consolidate();
        assertTrue(Zarr.openGroup(store, "model").isConsolidated());
        assertFalse(Zarr.open(store, "model", false).asGroup().isConsolidated());
        assertFalse(Zarr.openGroup(store, "model/layers").isConsolidated()); // its own zarr.json has none
    }

    @Test
    void v2NodesOpenByPathToo() {
        MemoryStore store = new MemoryStore();
        String zarray = "{\"zarr_format\":2,\"shape\":[2],\"chunks\":[2],\"dtype\":\"<i4\",\"compressor\":null,"
                + "\"fill_value\":7,\"order\":\"C\",\"filters\":null}";
        store.set(".zgroup", "{\"zarr_format\":2}".getBytes(StandardCharsets.UTF_8));
        store.set("g/.zgroup", "{\"zarr_format\":2}".getBytes(StandardCharsets.UTF_8));
        store.set("g/a/.zarray", zarray.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(new int[] {7, 7}, Zarr.openArray(store, "g/a").readInts());
        assertArrayEquals(new int[] {7, 7}, Zarr.openGroup(store).array("g/a").readInts());
    }
}
