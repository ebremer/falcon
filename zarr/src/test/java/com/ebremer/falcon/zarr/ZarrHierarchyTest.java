package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZarrHierarchyTest {

    private static final String ROOT_GROUP =
            "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{\"title\":\"root\"}}";
    private static final String PLAIN_GROUP = "{\"zarr_format\":3,\"node_type\":\"group\"}";

    private static String arrayDoc(String shape, String dtype, String chunks) {
        return "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + shape
                + ",\"data_type\":\"" + dtype + "\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunks + "}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},"
                + "\"fill_value\":0,"
                + "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]}";
    }

    private static void put(Store store, String key, String json) {
        store.set(key, json.getBytes(StandardCharsets.UTF_8));
    }

    /** A store with: root group; array "temperature"; group "nested" holding array "values". */
    private static void populate(Store store) {
        put(store, "zarr.json", ROOT_GROUP);
        put(store, "temperature/zarr.json", arrayDoc("[4,4]", "float64", "[2,2]"));
        put(store, "nested/zarr.json", PLAIN_GROUP);
        put(store, "nested/values/zarr.json", arrayDoc("[10]", "int32", "[5]"));
    }

    @Test
    void opensAndDescribesEveryNode() {
        MemoryStore store = new MemoryStore();
        populate(store);

        ZarrNode root = Zarr.open(store);
        assertTrue(root.isGroup());
        assertEquals("", root.name());
        assertEquals("", root.path());

        ZarrGroup group = root.asGroup();
        assertEquals("root", group.attributes().get("title").asString());
        assertEquals(List.of("nested", "temperature"), group.childNames());
        assertEquals(List.of("temperature"), group.arrays().stream().map(ZarrNode::name).toList());
        assertEquals(List.of("nested"), group.groups().stream().map(ZarrNode::name).toList());

        ZarrArray temperature = group.array("temperature");
        assertEquals("temperature", temperature.name());
        assertEquals("temperature", temperature.path());
        assertArrayEquals(new long[] {4, 4}, temperature.shape());
        assertArrayEquals(new long[] {2, 2}, temperature.chunkShape());
        assertEquals("float64", temperature.dataType());
        assertEquals(16, temperature.size());
        assertEquals(List.of("bytes"), temperature.codecNames());

        ZarrArray values = group.group("nested").array("values");
        assertEquals("nested/values", values.path());
        assertEquals("values", values.name());
        assertArrayEquals(new long[] {10}, values.shape());
        assertEquals("int32", values.dataType());
    }

    @Test
    void childOfMissingNameIsEmpty() {
        MemoryStore store = new MemoryStore();
        populate(store);
        assertTrue(Zarr.open(store).asGroup().child("absent").isEmpty());
    }

    @Test
    void typedAccessorRejectsWrongKind() {
        MemoryStore store = new MemoryStore();
        populate(store);
        ZarrGroup root = Zarr.openGroup(store);
        assertThrows(IllegalArgumentException.class, () -> root.group("temperature")); // it's an array
        assertThrows(IllegalArgumentException.class, () -> root.array("nested"));       // it's a group
    }

    @Test
    void rootMayBeAnArray() {
        MemoryStore store = new MemoryStore();
        put(store, "zarr.json", arrayDoc("[3]", "uint8", "[3]"));

        ZarrNode root = Zarr.open(store);
        assertTrue(root.isArray());
        assertEquals("uint8", Zarr.openArray(store).dataType());
        assertThrows(IllegalStateException.class, () -> Zarr.openGroup(store));
    }

    @Test
    void zarrV2StoreIsReportedUnsupported() {
        MemoryStore store = new MemoryStore();
        put(store, ".zgroup", "{\"zarr_format\":2}");
        assertThrows(ZarrUnsupportedException.class, () -> Zarr.open(store));
    }

    @Test
    void storeWithoutRootMetadataIsFormatError() {
        assertThrows(ZarrFormatException.class, () -> Zarr.open(new MemoryStore()));
    }

    @Test
    void opensFromFilesystemPath(@TempDir Path tmp) {
        Path root = tmp.resolve("store");
        FileSystemStore fs = FileSystemStore.open(root);
        populate(fs);

        ZarrGroup group = Zarr.open(root).asGroup();
        assertEquals(List.of("nested", "temperature"), group.childNames());
        assertArrayEquals(new long[] {4, 4}, group.array("temperature").shape());
        assertArrayEquals(new long[] {10}, group.group("nested").array("values").shape());
    }

    @Test
    void groupReflectsStoreAdditions() {
        MemoryStore store = new MemoryStore();
        put(store, "zarr.json", PLAIN_GROUP);
        ZarrGroup root = Zarr.openGroup(store);
        assertEquals(List.of(), root.childNames());

        put(store, "later/zarr.json", arrayDoc("[2]", "int8", "[2]"));
        assertEquals(List.of("later"), root.childNames()); // read on demand, not cached
        assertFalse(root.arrays().isEmpty());
    }
}
