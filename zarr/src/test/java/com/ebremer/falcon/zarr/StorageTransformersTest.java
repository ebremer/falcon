package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Storage transformers (P2 F14). Falcon implements none (zarr-extensions registers none), so it reads past one
 * only when it says {@code "must_understand": false}, as the v3 specification allows for an extension object,
 * and refuses any other by name. zarr-python 3.4 refuses every non-empty list, that one too.
 */
class StorageTransformersTest {

    private static final String IGNORABLE = "{\"name\":\"made_up\",\"must_understand\":false,\"configuration\":{\"x\":1}}";

    private static MemoryStore store(String transformers) {
        MemoryStore store = new MemoryStore();
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[4],\"data_type\":\"int32\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[2]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,"
                + "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}],\"attributes\":{},"
                + "\"storage_transformers\":" + transformers + "}";
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
        return store;
    }

    private static String transformers(MemoryStore store, String key) {
        return Json.parse(store.get(key).orElseThrow()).asObject().get("storage_transformers").toJson();
    }

    @Test
    void oneThatNeedNotBeUnderstoodIsReadPast() {
        MemoryStore store = store("[" + IGNORABLE + "]");
        ZarrArray a = Zarr.openArray(store);
        a.writeInts(new int[] {1, 2, 3, 4});
        assertArrayEquals(new int[] {1, 2, 3, 4}, Zarr.openArray(store).readInts());
        assertTrue(store.exists("c/0")); // written straight to the store's keys
    }

    @Test
    void anyOtherIsRefusedByName() {
        for (String transformers : new String[] {"[{\"name\":\"made_up\"}]",
            "[{\"name\":\"made_up\",\"must_understand\":true}]", "[\"made_up\"]",
            "[" + IGNORABLE + ",{\"name\":\"made_up\",\"configuration\":{}}]"}) {
            ZarrUnsupportedException e = assertThrows(ZarrUnsupportedException.class,
                    () -> Zarr.openArray(store(transformers)), transformers);
            assertTrue(e.getMessage().contains("storage transformer 'made_up'"), e.getMessage());
        }
    }

    @Test
    void malformedListsAreRefused() {
        for (String transformers : new String[] {"{}", "[3]", "[{\"configuration\":{}}]", "[{\"name\":7}]"}) {
            assertThrows(ZarrFormatException.class, () -> Zarr.openArray(store(transformers)), transformers);
        }
        // An empty list, or null, is no transformer.
        assertEquals(4, Zarr.openArray(store("[]")).size());
        assertEquals(4, Zarr.openArray(store("null")).size());
    }

    /** Falcon's metadata rewrites keep every member they do not change, the transformers among them. */
    @Test
    void rewritesKeepTheList() {
        MemoryStore store = store("[" + IGNORABLE + "]");
        String list = transformers(store, "zarr.json");
        ZarrArray a = Zarr.openArray(store).setAttributes(new JsonObject(Map.of("k", new JsonString("v"))));
        assertEquals(list, transformers(store, "zarr.json"));
        a.resize(6).updateAttributes(new JsonObject(Map.of("j", new JsonString("w"))));
        assertEquals(list, transformers(store, "zarr.json"));

        MemoryStore hierarchy = new MemoryStore();
        Zarr.createGroup(hierarchy);
        hierarchy.set("a/zarr.json", store.get("zarr.json").orElseThrow());
        Zarr.openGroup(hierarchy).consolidate();
        JsonObject snapshot = Json.parse(hierarchy.get("zarr.json").orElseThrow()).asObject()
                .get("consolidated_metadata").asObject().get("metadata").asObject().get("a").asObject();
        assertEquals(list, snapshot.get("storage_transformers").toJson());
        assertEquals(6, Zarr.openGroup(hierarchy).array("a").size()); // opened from the snapshot
    }
}
