package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * P2 F2 (consolidated metadata: writing it, and what a snapshot sees as the hierarchy changes) and F6
 * (changing attributes, deleting a node).
 */
class ConsolidatedTest {

    private static final String EMPTY_INLINE = "{\"kind\":\"inline\",\"must_understand\":false,\"metadata\":{}}";

    private static void put(Store store, String key, String json) {
        store.set(key, json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject json(Store store, String key) {
        return Json.parse(store.get(key).orElseThrow()).asObject();
    }

    private static JsonObject attrs(String json) {
        return Json.parse(json).asObject();
    }

    private static ArraySpec spec() {
        return ArraySpec.builder(new long[] {4}, DataType.INT32).chunkShape(2).build();
    }

    /** Root (attributes, plus an extension member it may ignore); arrays a1, B2; group g with x and sub. */
    private static MemoryStore tree() {
        MemoryStore store = new MemoryStore();
        put(store, "zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{\"title\":\"t\"},"
                + "\"ext\":{\"must_understand\":false,\"note\":1}}");
        ZarrGroup root = Zarr.openGroup(store);
        root.createArray("a1", spec()).writeInts(new int[] {1, 2, 3, 4});
        root.createArray("B2", spec());
        ZarrGroup g = root.createGroup("g", attrs("{\"level\":1}"));
        g.createArray("x", spec()).writeInts(new int[] {5, 6, 7, 8});
        g.createGroup("sub");
        root.createGroup("g2"); // shares g's prefix as text, not as a path
        return store;
    }

    private static List<String> keys(JsonObject o) {
        return new ArrayList<>(o.members().keySet());
    }

    // ---- consolidate() ----------------------------------------------------------------------------

    @Test
    void consolidateWritesZarrPythonsLayoutAndKeepsTheRootsOtherMembers() {
        MemoryStore store = tree();
        byte[] x = store.get("g/x/zarr.json").orElseThrow();
        ZarrGroup root = Zarr.openGroup(store).consolidate();
        assertTrue(root.isConsolidated());

        JsonObject doc = json(store, "zarr.json");
        assertEquals("t", doc.get("attributes").asObject().get("title").asString());
        assertEquals(attrs("{\"must_understand\":false,\"note\":1}"), doc.get("ext"));
        JsonObject member = doc.get("consolidated_metadata").asObject();
        assertEquals(List.of("kind", "must_understand", "metadata"), keys(member));
        assertEquals("inline", member.get("kind").asString());
        JsonObject metadata = member.get("metadata").asObject();
        // by depth, then case-folded, as zarr-python orders them
        assertEquals(List.of("a1", "B2", "g", "g2", "g/sub", "g/x"), keys(metadata));
        assertEquals(Json.parse(x), metadata.get("g/x")); // an array's document as stored
        assertEquals(Json.parse(EMPTY_INLINE), metadata.get("g").asObject().get("consolidated_metadata"));
        assertEquals(1, metadata.get("g").asObject().get("attributes").asObject().get("level").asNumber().intValue());
    }

    @Test
    void walkingAConsolidatedHandleReadsNothing() {
        ConsolidatedFixtureTest.RecordingStore store = new ConsolidatedFixtureTest.RecordingStore(tree());
        Zarr.openGroup(store).consolidate();
        store.calls.clear();
        ZarrGroup root = Zarr.openGroup(store);
        assertEquals(List.of("B2", "a1", "g", "g2"), root.childNames());
        assertEquals(List.of("sub", "x"), root.group("g").childNames());
        assertEquals(List.of("g", "g2"), root.groups().stream().map(ZarrNode::name).toList());
        assertEquals("g/x", root.group("g").array("x").path());
        assertEquals(List.of("get zarr.json"), store.calls);
        assertArrayEquals(new int[] {5, 6, 7, 8}, root.group("g").array("x").readInts());
    }

    @Test
    void aSnapshotIsStaleUntilConsolidatedAgain() {
        MemoryStore store = tree();
        ZarrGroup root = Zarr.openGroup(store).consolidate();

        ZarrArray late = root.createArray("late", spec()); // created through the consolidated handle
        late.writeInts(new int[] {9, 9, 9, 9});
        root.group("g").setAttributes(attrs("{\"level\":2}"));

        assertFalse(root.childNames().contains("late"));
        assertTrue(root.child("late").isEmpty());
        assertFalse(Zarr.openGroup(store).childNames().contains("late"));
        assertEquals(1, Zarr.openGroup(store).group("g").attributes().get("level").asNumber().intValue());

        ZarrGroup perNode = Zarr.openGroup(store, false);
        assertFalse(perNode.isConsolidated());
        assertTrue(perNode.childNames().contains("late"));
        assertEquals(2, perNode.group("g").attributes().get("level").asNumber().intValue());

        // consolidating again walks the store, not the old snapshot
        ZarrGroup fresh = root.consolidate();
        assertTrue(fresh.childNames().contains("late"));
        assertEquals(2, Zarr.openGroup(store).group("g").attributes().get("level").asNumber().intValue());
        assertArrayEquals(new int[] {9, 9, 9, 9}, Zarr.openGroup(store).array("late").readInts());
    }

    @Test
    void consolidateRefusesV2NodesAndReadOnlyStores() {
        MemoryStore v2 = new MemoryStore();
        put(v2, ".zgroup", "{\"zarr_format\":2}");
        assertThrows(UnsupportedOperationException.class, () -> Zarr.openGroup(v2).consolidate());

        MemoryStore mixed = tree();
        put(mixed, "g/old/.zgroup", "{\"zarr_format\":2}");
        byte[] before = mixed.get("zarr.json").orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> Zarr.openGroup(mixed).consolidate());
        assertArrayEquals(before, mixed.get("zarr.json").orElseThrow());

        assertThrows(UnsupportedOperationException.class, () -> Zarr.openGroup(new ReadOnly(tree())).consolidate());
    }

    /**
     * A node Falcon cannot read because it uses an unimplemented feature is valid Zarr: it is embedded for
     * readers that can, and left out of children() as when read node by node. A malformed node stops the
     * consolidation, which would otherwise hide it from every reader of the snapshot.
     */
    @Test
    void unsupportedNodesAreEmbeddedAndMalformedOnesStopTheConsolidation() {
        MemoryStore store = tree();
        put(store, "future/zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\",\"x\":{}}");
        ZarrGroup root = Zarr.openGroup(store).consolidate();
        assertTrue(root.childNames().contains("future"));
        assertFalse(root.children().stream().anyMatch(n -> n.name().equals("future")));
        assertThrows(ZarrUnsupportedException.class, () -> root.child("future"));

        put(store, "g/broken/zarr.json", "{\"zarr_format\":3,\"node_type\":\"array\"}");
        byte[] before = store.get("zarr.json").orElseThrow();
        assertThrows(ZarrFormatException.class, () -> Zarr.openGroup(store, false).consolidate());
        assertArrayEquals(before, store.get("zarr.json").orElseThrow());
    }

    // ---- reading snapshots ------------------------------------------------------------------------

    private static MemoryStore withSnapshot(String member) {
        MemoryStore store = new MemoryStore();
        put(store, "zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\",\"consolidated_metadata\":" + member + "}");
        put(store, "real/zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\"}");
        return store;
    }

    private static String inline(String metadata) {
        return "{\"kind\":\"inline\",\"must_understand\":false,\"metadata\":" + metadata + "}";
    }

    @Test
    void aMalformedSnapshotFailsToOpenUnlessItIsNotUsed() {
        for (String member : new String[] {
            "5", inline("[]"), inline("{\"a\":7}"), inline("{\"../a\":{}}"),
            inline("{\"x/y\":{\"zarr_format\":3,\"node_type\":\"group\"}}"), // no parent "x"
            inline("{\"x\":{\"zarr_format\":3,\"node_type\":\"array\"},\"x/y\":{\"zarr_format\":3,\"node_type\":\"group\"}}"),
            "{\"kind\":\"inline\"}"}) {
            MemoryStore store = withSnapshot(member);
            assertThrows(ZarrFormatException.class, () -> Zarr.open(store), member);
            ZarrGroup perNode = Zarr.openGroup(store, false);
            assertEquals(List.of("real"), perNode.childNames(), member);
        }
    }

    @Test
    void anUnknownKindIsIgnoredUnlessItMustBeUnderstood() {
        for (String member : new String[] {"null", "{\"kind\":\"external\",\"must_understand\":false}", "{}"}) {
            ZarrGroup root = Zarr.openGroup(withSnapshot(member));
            assertFalse(root.isConsolidated(), member);
            assertEquals(List.of("real"), root.childNames(), member);
        }
        assertThrows(ZarrUnsupportedException.class,
                () -> Zarr.open(withSnapshot("{\"kind\":\"external\",\"must_understand\":true}")));
    }

    /** I3's rules hold for a snapshot's entries: one bad entry is left out of children() and nowhere else. */
    @Test
    void aBadEntryAffectsOnlyItself() {
        ZarrGroup root = Zarr.openGroup(withSnapshot(inline("{\"bad\":{\"zarr_format\":3,\"node_type\":\"array\"},"
                + "\"ok\":{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{\"k\":1}}}")));
        assertTrue(root.isConsolidated());
        assertEquals(List.of("bad", "ok"), root.childNames());
        assertEquals(List.of("ok"), root.children().stream().map(ZarrNode::name).toList());
        assertThrows(ZarrFormatException.class, () -> root.child("bad"));
        assertEquals(1, root.group("ok").attributes().get("k").asNumber().intValue());
        assertTrue(root.child("real").isEmpty()); // in the store, not in the snapshot
    }

    /** zarr-python embeds a v2 node in v3 consolidated metadata as its .zarray with "attributes". */
    @Test
    void aV2EntryInAV3SnapshotIsRead() {
        ZarrGroup root = Zarr.openGroup(withSnapshot(inline("{\"old\":{\"zarr_format\":2,\"shape\":[2],\"chunks\":[2],"
                + "\"dtype\":\"<i4\",\"compressor\":null,\"fill_value\":0,\"order\":\"C\",\"filters\":null,"
                + "\"attributes\":{\"k\":1}},\"oldg\":{\"zarr_format\":2,\"attributes\":{\"j\":2}}}")));
        ZarrArray old = root.array("old");
        assertEquals(DataType.INT32, old.dataType());
        assertEquals(1, old.attributes().get("k").asNumber().intValue());
        assertEquals(2, root.group("oldg").attributes().get("j").asNumber().intValue());
    }

    private static MemoryStore v2Tree(String zmetadata) {
        MemoryStore store = new MemoryStore();
        put(store, ".zgroup", "{\"zarr_format\":2}");
        put(store, "a/.zarray", "{\"zarr_format\":2,\"shape\":[2],\"chunks\":[2],\"dtype\":\"<i4\","
                + "\"compressor\":null,\"fill_value\":0,\"order\":\"C\",\"filters\":null}");
        put(store, "g/.zgroup", "{\"zarr_format\":2}");
        put(store, ".zmetadata", zmetadata);
        return store;
    }

    @Test
    void zmetadataFormsAreReadOrIgnoredOrRefused() {
        String entries = "\".zgroup\":{\"zarr_format\":2},\"a/.zarray\":{\"zarr_format\":2,\"shape\":[2],\"chunks\":[2],"
                + "\"dtype\":\"<i4\",\"compressor\":null,\"fill_value\":0,\"order\":\"C\",\"filters\":null},"
                + "\"a/.zattrs\":{\"k\":1},\"g/.zgroup\":{\"zarr_format\":2},\"lonely/.zattrs\":{}";
        ZarrGroup root = Zarr.openGroup(v2Tree("{\"zarr_consolidated_format\":1,\"metadata\":{" + entries + "}}"));
        assertTrue(root.isConsolidated());
        assertEquals(List.of("a", "g"), root.childNames()); // attributes alone are no node
        assertEquals(1, root.array("a").attributes().get("k").asNumber().intValue());

        ZarrGroup future = Zarr.openGroup(v2Tree("{\"zarr_consolidated_format\":2,\"metadata\":{}}"));
        assertFalse(future.isConsolidated());
        assertEquals(List.of("a", "g"), future.childNames());

        assertThrows(ZarrFormatException.class,
                () -> Zarr.open(v2Tree("{\"zarr_consolidated_format\":1,\"metadata\":{\"a/.zfoo\":{}}}")));
        assertThrows(ZarrFormatException.class, () -> Zarr.open(v2Tree("{\"metadata\":")));
    }

    // ---- delete -----------------------------------------------------------------------------------

    @Test
    void deleteRemovesEveryKeyUnderTheChildMetadataFirst() {
        ConsolidatedFixtureTest.RecordingStore store = new ConsolidatedFixtureTest.RecordingStore(tree());
        ZarrGroup root = Zarr.openGroup(store);
        store.calls.clear();
        root.delete("g");
        List<String> deletes = store.calls.stream().filter(c -> c.startsWith("delete ")).toList();
        assertEquals("delete g/zarr.json", deletes.get(0));
        assertTrue(deletes.containsAll(List.of("delete g/x/zarr.json", "delete g/x/c/0", "delete g/sub/zarr.json")));
        assertTrue(store.listPrefix("g/").isEmpty());
        assertEquals(List.of("B2", "a1", "g2"), root.childNames());
        assertTrue(store.exists("g2/zarr.json"));
    }

    @Test
    void deleteRefusesWhatItCannotDelete() {
        ZarrGroup root = Zarr.openGroup(tree());
        assertThrows(NoSuchElementException.class, () -> root.delete("absent"));
        for (String name : new String[] {"", "g/x", ".", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> root.delete(name), name);
        }
        assertThrows(UnsupportedOperationException.class, () -> Zarr.openGroup(new ReadOnly(tree())).delete("g"));
    }

    /** As in zarr-python: deleting a child also removes it from the group's stored consolidated metadata. */
    @Test
    void deleteUpdatesTheGroupsConsolidatedMetadata() {
        MemoryStore store = tree();
        ZarrGroup root = Zarr.openGroup(store).consolidate();
        ZarrGroup g = root.group("g"); // opened before the delete, sharing the snapshot
        root.delete("g");
        assertEquals(List.of("B2", "a1", "g2"), root.childNames());
        assertTrue(root.child("g").isEmpty());
        assertTrue(g.childNames().isEmpty());
        assertEquals(List.of("a1", "B2", "g2"),
                keys(json(store, "zarr.json").get("consolidated_metadata").asObject().get("metadata").asObject()));
        assertEquals(List.of("B2", "a1", "g2"), Zarr.openGroup(store).childNames());

        // through a handle that reads node by node, the stored snapshot is kept current too
        Zarr.openGroup(store, false).delete("a1");
        assertEquals(List.of("B2", "g2"), Zarr.openGroup(store).childNames());
    }

    /**
     * Below a nested group of a snapshot, the delete updates the snapshot the open handles share, but the
     * snapshot stored further up lists the child until the hierarchy is consolidated again.
     */
    @Test
    void deleteBelowANestedGroupLeavesTheStoredSnapshotAboveStale() {
        MemoryStore store = tree();
        ZarrGroup root = Zarr.openGroup(store).consolidate();
        root.group("g").delete("x");
        assertEquals(List.of("sub"), root.group("g").childNames());
        assertEquals(List.of("sub", "x"), Zarr.openGroup(store).group("g").childNames());
        assertEquals(List.of("sub"), Zarr.openGroup(store, false).group("g").childNames());
        assertEquals(List.of("sub"), root.consolidate().group("g").childNames());
    }

    /** A child the snapshot lists but the store no longer has can still be deleted: from the snapshot. */
    @Test
    void aChildListedOnlyInTheSnapshotCanBeDeleted() {
        MemoryStore store = tree();
        ZarrGroup root = Zarr.openGroup(store).consolidate();
        for (String key : store.listPrefix("B2/")) {
            store.delete(key);
        }
        root.delete("B2");
        assertFalse(Zarr.openGroup(store).childNames().contains("B2"));
    }

    @Test
    void deleteInAConsolidatedV2HierarchyRewritesZmetadata() {
        String entries = "\"a/.zarray\":{\"zarr_format\":2,\"shape\":[2],\"chunks\":[2],\"dtype\":\"<i4\","
                + "\"compressor\":null,\"fill_value\":0,\"order\":\"C\",\"filters\":null},\"a/.zattrs\":{},"
                + "\"g/.zgroup\":{\"zarr_format\":2}";
        MemoryStore store = v2Tree("{\"zarr_consolidated_format\":1,\"metadata\":{" + entries + "}}");
        Zarr.openGroup(store).delete("a");
        assertFalse(store.exists("a/.zarray"));
        assertEquals(List.of("g/.zgroup"), keys(json(store, ".zmetadata").get("metadata").asObject()));
        assertEquals(List.of("g"), Zarr.openGroup(store).childNames());
    }

    // ---- attributes -------------------------------------------------------------------------------

    @Test
    void setAndUpdateAttributesRewriteOnlyTheAttributes() {
        MemoryStore store = new MemoryStore();
        Zarr.createGroup(store);
        put(store, "arr/zarr.json", "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[2],\"data_type\":\"int8\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[2]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,"
                + "\"codecs\":[{\"name\":\"bytes\"}],\"dimension_names\":[\"t\"],\"attributes\":{\"a\":1,\"b\":2},"
                + "\"ext\":{\"must_understand\":false}}");
        ZarrArray arr = Zarr.openGroup(store).array("arr");

        ZarrArray updated = arr.updateAttributes(attrs("{\"b\":20,\"c\":[3]}"));
        assertEquals(attrs("{\"a\":1,\"b\":20,\"c\":[3]}"), updated.attributes());
        assertEquals(attrs("{\"a\":1,\"b\":2}"), arr.attributes()); // the old handle is unchanged
        JsonObject doc = json(store, "arr/zarr.json");
        assertEquals(attrs("{\"a\":1,\"b\":20,\"c\":[3]}"), doc.get("attributes"));
        assertEquals(List.of("zarr_format", "node_type", "shape", "data_type", "chunk_grid", "chunk_key_encoding",
                "fill_value", "codecs", "dimension_names", "attributes", "ext"), keys(doc));

        ZarrArray set = updated.setAttributes(attrs("{\"only\":true}"));
        assertEquals(attrs("{\"only\":true}"), Zarr.openGroup(store).array("arr").attributes());
        assertEquals(attrs("{\"only\":true}"), set.attributes());

        ZarrGroup root = Zarr.openGroup(store).updateAttributes(attrs("{\"title\":\"x\"}"));
        assertEquals("x", Zarr.openGroup(store).attributes().get("title").asString());
        assertEquals(List.of("arr"), root.childNames());

        JsonValue nan = Json.parse("{\"fill\":NaN}"); // written back as the bare token, as Python writes it
        root.setAttributes(nan.asObject());
        assertTrue(Double.isNaN(Zarr.openGroup(store).attributes().get("fill").asNumber().doubleValue()));
    }

    @Test
    void attributesOfV2NodesGoToZattrs() {
        MemoryStore store = v2Tree("{\"metadata\":{}}");
        byte[] zarray = store.get("a/.zarray").orElseThrow();
        ZarrGroup root = Zarr.openGroup(store, false);
        ZarrArray a = root.array("a").setAttributes(attrs("{\"k\":1}"));
        assertEquals(attrs("{\"k\":1}"), json(store, "a/.zattrs"));
        assertArrayEquals(zarray, store.get("a/.zarray").orElseThrow());
        assertEquals(attrs("{\"k\":1,\"j\":2}"), a.updateAttributes(attrs("{\"j\":2}")).attributes());
        assertEquals(attrs("{\"k\":1,\"j\":2}"), Zarr.openGroup(store, false).array("a").attributes());

        root.group("g").updateAttributes(attrs("{\"level\":1}"));
        assertEquals(attrs("{\"level\":1}"), json(store, "g/.zattrs"));
        assertEquals(1, Zarr.openGroup(store, false).group("g").attributes().get("level").asNumber().intValue());
    }

    @Test
    void attributeChangesRefuseWhatTheyCannotDo() {
        MemoryStore store = tree();
        ZarrGroup root = Zarr.openGroup(store);
        ZarrArray a1 = root.array("a1");
        assertThrows(UnsupportedOperationException.class,
                () -> Zarr.openGroup(new ReadOnly(tree())).setAttributes(attrs("{}")));

        root.delete("a1");
        assertThrows(ZarrFormatException.class, () -> a1.setAttributes(attrs("{}")));

        ZarrGroup g = root.group("g");
        root.createArray("g", spec(), true); // now an array
        assertThrows(IllegalStateException.class, () -> g.setAttributes(attrs("{}")));
        assertEquals(attrs("{}"), root.array("g").attributes()); // nothing written

        assertThrows(NullPointerException.class, () -> root.setAttributes(null));
    }

    @Test
    void newHandlesKeepTheirOptionsAndSnapshots() {
        MemoryStore store = tree();
        ZarrArray cached = Zarr.openGroup(store).array("a1").withChunkCache(1 << 20);
        assertNotNull(cached.setAttributes(attrs("{\"k\":1}")).chunkCache());

        ZarrGroup root = Zarr.openGroup(store).consolidate().setAttributes(attrs("{\"title\":\"new\"}"));
        assertTrue(root.isConsolidated());
        ZarrGroup reopened = Zarr.openGroup(store);
        assertTrue(reopened.isConsolidated()); // the stored snapshot survives the attribute change
        assertEquals("new", reopened.attributes().get("title").asString());
        assertTrue(reopened.group("g").setAttributes(attrs("{}")).isConsolidated());
    }

    /** A read-only view of a store. */
    private static final class ReadOnly implements Store {
        private final Store delegate;

        ReadOnly(Store delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<byte[]> get(String key) {
            return delegate.get(key);
        }

        @Override
        public java.util.Optional<byte[]> getRange(String key, long offset, long length) {
            return delegate.getRange(key, offset, length);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public java.util.OptionalLong size(String key) {
            return delegate.size(key);
        }

        @Override
        public List<String> list() {
            return delegate.list();
        }

        @Override
        public List<String> listPrefix(String prefix) {
            return delegate.listPrefix(prefix);
        }

        @Override
        public List<String> listDir(String prefix) {
            return delegate.listDir(prefix);
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public void set(String key, byte[] value) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException("read-only");
        }
    }
}
