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
        return arrayDoc(shape, dtype, chunks, "{\"name\":\"default\"}");
    }

    private static String arrayDoc(String shape, String dtype, String chunks, String encoding) {
        return "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + shape
                + ",\"data_type\":\"" + dtype + "\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunks + "}},"
                + "\"chunk_key_encoding\":" + encoding + ","
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
        assertEquals("float64", temperature.dataType().name());
        assertEquals(16, temperature.size());
        assertEquals(List.of("bytes"), temperature.codecNames());

        ZarrArray values = group.group("nested").array("values");
        assertEquals("nested/values", values.path());
        assertEquals("values", values.name());
        assertArrayEquals(new long[] {10}, values.shape());
        assertEquals("int32", values.dataType().name());
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
        assertEquals("uint8", Zarr.openArray(store).dataType().name());
        assertThrows(IllegalStateException.class, () -> Zarr.openGroup(store));
    }

    @Test
    void zarrV2GroupIsOpenedViaTranslation() {
        MemoryStore store = new MemoryStore();
        put(store, ".zgroup", "{\"zarr_format\":2}");
        put(store, ".zattrs", "{\"note\":\"v2\"}");
        ZarrNode root = Zarr.open(store);
        assertTrue(root.isGroup());
        assertEquals("v2", root.asGroup().attributes().get("note").asString());
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
    void chunkKeysAndGridArithmetic() {
        MemoryStore store = new MemoryStore();
        put(store, "zarr.json", PLAIN_GROUP);
        // 10x10 array, 4x4 chunks -> 3x3 grid, default "/" encoding, under path "img".
        put(store, "img/zarr.json", arrayDoc("[10,10]", "uint8", "[4,4]"));

        ZarrArray img = Zarr.openGroup(store).array("img");
        assertArrayEquals(new long[] {3, 3}, img.gridShape());
        assertEquals(9, img.chunkCount());
        assertEquals("img/c/0/0", img.chunkKey(0, 0));
        assertEquals("img/c/2/1", img.chunkKey(2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> img.chunkKey(3, 0));
        assertThrows(IllegalArgumentException.class, () -> img.chunkKey(0));
    }

    @Test
    void chunkKeyHonorsEncodingAndRootPath() {
        MemoryStore store = new MemoryStore();
        // Root array with v2 encoding and "." separator: chunk key has no path prefix and no "c".
        put(store, "zarr.json",
                arrayDoc("[6,6]", "int8", "[2,2]", "{\"name\":\"v2\",\"configuration\":{\"separator\":\".\"}}"));

        ZarrArray root = Zarr.openArray(store);
        assertEquals("v2", root.chunkKeyEncoding());
        assertEquals(".", root.separator());
        assertEquals("1.2", root.chunkKey(1, 2));
        assertEquals("0.0", root.chunkKey(0, 0));
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

    // ---- P1 ---------------------------------------------------------------------------------------

    /** Counts the store requests a call makes: over HTTP, each is a round trip. */
    private static final class CountingStore implements Store {
        private final MemoryStore delegate = new MemoryStore();
        final List<String> calls = new java.util.ArrayList<>();
        boolean failListing;

        @Override
        public java.util.Optional<byte[]> get(String key) {
            calls.add("get " + key);
            return delegate.get(key);
        }

        @Override
        public java.util.Optional<byte[]> getRange(String key, long offset, long length) {
            calls.add("getRange " + key);
            return delegate.getRange(key, offset, length);
        }

        @Override
        public boolean exists(String key) {
            calls.add("exists " + key);
            return delegate.exists(key);
        }

        @Override
        public java.util.OptionalLong size(String key) {
            calls.add("size " + key);
            return delegate.size(key);
        }

        @Override
        public List<String> list() {
            return listPrefix("");
        }

        @Override
        public List<String> listPrefix(String prefix) {
            if (failListing) {
                throw new UnsupportedOperationException("no listing");
            }
            return delegate.listPrefix(prefix);
        }

        @Override
        public List<String> listDir(String prefix) {
            if (failListing) {
                throw new UnsupportedOperationException("no listing");
            }
            return delegate.listDir(prefix);
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public void set(String key, byte[] value) {
            delegate.set(key, value);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }
    }

    /**
     * P1 PF5: opening a node probed with exists() (a HEAD over HTTP) and then fetched: a v2 array cost five
     * requests. It now fetches zarr.json, then .zarray, then .zgroup, stopping at the first present.
     */
    @Test
    void openingANodeFetchesWithoutProbing() {
        CountingStore store = new CountingStore();
        populate(store);
        store.calls.clear();
        Zarr.open(store);
        assertEquals(List.of("get zarr.json"), store.calls);

        store.calls.clear();
        ZarrGroup root = Zarr.openGroup(store);
        store.calls.clear();
        assertTrue(root.child("temperature").isPresent());
        assertEquals(List.of("get temperature/zarr.json"), store.calls);

        store.calls.clear();
        assertTrue(root.child("absent").isEmpty());
        assertEquals(List.of("get absent/zarr.json", "get absent/.zarray", "get absent/.zgroup"), store.calls);

        CountingStore v2 = new CountingStore();
        put(v2, ".zarray", "{\"zarr_format\":2,\"shape\":[2],\"chunks\":[2],\"dtype\":\"<i4\",\"fill_value\":0,"
                + "\"order\":\"C\",\"filters\":null,\"compressor\":null}");
        v2.calls.clear();
        assertTrue(Zarr.open(v2).isArray());
        assertEquals(List.of("get zarr.json", "get .zarray", "get .zattrs"), v2.calls);
    }

    /** P1 PF7: ZarrGroup.toString() listed the children: a full store walk, and an exception on HTTP. */
    @Test
    void toStringDoesNoIo() {
        CountingStore store = new CountingStore();
        populate(store);
        ZarrGroup root = Zarr.openGroup(store);
        ZarrGroup nested = root.group("nested");
        store.failListing = true;
        store.calls.clear();
        assertEquals("ZarrGroup[/]", root.toString());
        assertEquals("ZarrGroup[nested]", nested.toString());
        assertEquals(List.of(), store.calls);
    }

    /**
     * P1 I3: a child whose metadata is malformed or unsupported is left out of children(); other failures,
     * such as the store failing, still surface.
     */
    @Test
    void childrenLeaveOutOnlyChildrenThatCannotBeOpened() {
        MemoryStore store = new MemoryStore();
        populate(store);
        put(store, "broken/zarr.json", "{\"zarr_format\":3,\"node_type\":\"array\"");          // malformed JSON
        put(store, "future/zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\",\"x\":{}}"); // must understand x
        ZarrGroup root = Zarr.openGroup(store);
        assertEquals(List.of("broken", "future", "nested", "temperature"), root.childNames());
        assertEquals(List.of("nested", "temperature"), root.children().stream().map(ZarrNode::name).toList());
        assertThrows(ZarrFormatException.class, () -> root.child("broken"));
        assertThrows(ZarrUnsupportedException.class, () -> root.group("future"));

        CountingStore failing = new CountingStore();
        populate(failing);
        ZarrGroup failingRoot = Zarr.openGroup(failing);
        failing.failListing = true;
        assertThrows(UnsupportedOperationException.class, failingRoot::children);
    }

    /**
     * P1 I12: names the v3 specification reserves, names that collide with metadata keys, and names
     * Windows would alias (a trailing '.' or space) are refused for new nodes; lookups keep only the rules
     * that make a name one path segment.
     */
    @Test
    void newNodeNamesFollowTheSpecification() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        ArraySpec spec = ArraySpec.builder(new long[] {2}, com.ebremer.falcon.zarr.datatype.DataType.INT8).build();
        for (String name : new String[] {"", "a/b", ".", "..", "...", "__meta", "__", "zarr.json", ".zarray",
                ".zgroup", ".zattrs", ".zmetadata", "data.", "data "}) {
            assertThrows(IllegalArgumentException.class, () -> root.createGroup(name), "group '" + name + "'");
            assertThrows(IllegalArgumentException.class, () -> root.createArray(name, spec), "array '" + name + "'");
        }
        assertEquals(List.of("zarr.json"), store.list()); // nothing was written
        for (String name : new String[] {"a.b", "_x", "x__", ".hidden", "with space", "ünïcode", "c"}) {
            assertEquals(name, root.createGroup(name).name());
        }
        // a lookup only needs valid path segments; "a/b" is a path to a grandchild (F9), here absent
        for (String name : new String[] {"", ".", "..", "a//b", "a/"}) {
            assertThrows(IllegalArgumentException.class, () -> root.child(name), "'" + name + "'");
        }
        assertTrue(root.child("a/b").isEmpty());
        assertTrue(root.child("__meta").isEmpty());
        put(store, "__meta/zarr.json", PLAIN_GROUP); // written by another tool
        assertTrue(root.child("__meta").isPresent());
    }
}
