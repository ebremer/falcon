package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ZarrArray#resize} (P2 F6). Shrinking deletes the chunks wholly outside the new shape; growing sets
 * to the fill value the part of an old edge chunk that comes inside, so values cut off by a shrink never
 * reappear (zarr-python leaves them, and they do).
 */
class ResizeTest {

    /** A 2-D int32 array of {@code shape}, chunks 3 x 2, fill -1, element (r, c) = 100 r + c. */
    private static ZarrArray filled(Store store, int rows, int cols, ArraySpec.Builder builder) {
        ZarrArray a = Zarr.createArray(store, builder.build());
        int[] v = new int[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                v[r * cols + c] = 100 * r + c;
            }
        }
        a.writeInts(v);
        return a;
    }

    private static ArraySpec.Builder spec(int rows, int cols) {
        return ArraySpec.builder(new long[] {rows, cols}, DataType.INT32).chunkShape(3, 2).fillValue(-1)
                .attributes(new JsonObject(Map.of("units", Json.parse("\"m\""))));
    }

    /** What a {@code rows x cols} array reads after resizes that kept {@code [0, keptRows) x [0, keptCols)}. */
    private static int[] expected(int rows, int cols, int keptRows, int keptCols) {
        int[] v = new int[rows * cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                v[r * cols + c] = r < keptRows && c < keptCols ? 100 * r + c : -1;
            }
        }
        return v;
    }

    @Test
    void shrinkingDeletesChunksWhollyOutsideAndKeepsTheRest() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = filled(store, 7, 5, spec(7, 5)); // grid 3 x 3
        assertEquals(9, store.listPrefix("c/").size());
        ZarrArray small = a.resize(4, 3);              // grid 2 x 2
        assertArrayEquals(new long[] {4, 3}, small.shape());
        assertEquals(List.of("c/0/0", "c/0/1", "c/1/0", "c/1/1"), store.listPrefix("c/"));
        assertArrayEquals(expected(4, 3, 4, 3), small.readInts());
        assertArrayEquals(new long[] {4, 3}, Zarr.openArray(store).shape());
        assertEquals(Json.parse("\"m\""), Zarr.openArray(store).attributes().get("units"));
    }

    @Test
    void growingAfterAShrinkReadsFillWhereValuesWereCutOff() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = filled(store, 7, 5, spec(7, 5));
        ZarrArray back = a.resize(4, 3).resize(7, 5);
        assertArrayEquals(expected(7, 5, 4, 3), back.readInts());
        assertArrayEquals(expected(7, 5, 4, 3), Zarr.openArray(store).readInts());
    }

    @Test
    void growingClearsValuesAZarrPythonStyleShrinkLeftBehind() {
        // zarr-python's resize rewrites the shape and deletes outside chunks only: an edge chunk keeps the
        // values past the new shape. Make that state by hand, then grow with Falcon.
        MemoryStore store = new MemoryStore();
        filled(store, 6, 4, spec(6, 4));                // grid 2 x 2, every chunk full
        rewriteShape(store, "zarr.json", 5, 3);        // the edge chunks now hold old values past [5, 3)
        ZarrArray grown = Zarr.openArray(store).resize(6, 4);
        assertArrayEquals(expected(6, 4, 5, 3), grown.readInts());
    }

    private static void rewriteShape(MemoryStore store, String key, long... shape) {
        JsonObject doc = Json.parse(store.get(key).orElseThrow()).asObject();
        Map<String, com.ebremer.falcon.zarr.json.JsonValue> members = new LinkedHashMap<>(doc.members());
        members.put("shape", Json.parse(java.util.Arrays.toString(shape)));
        store.set(key, Json.writeBytes(new JsonObject(members)));
    }

    @Test
    void growingOneDimensionAndShrinkingAnother() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = filled(store, 7, 5, spec(7, 5));
        ZarrArray b = a.resize(8, 2);
        assertArrayEquals(expected(8, 2, 7, 2), b.readInts());
        assertArrayEquals(expected(8, 6, 7, 2), b.resize(8, 6).readInts());
    }

    @Test
    void shardedArraysClearInsideTheShard() {
        MemoryStore store = new MemoryStore();
        ArraySpec.Builder sharded = ArraySpec.builder(new long[] {7, 5}, DataType.INT32).chunkShape(6, 4)
                .sharding(3, 2).fillValue(-1);
        ZarrArray a = filled(store, 7, 5, sharded);
        assertArrayEquals(expected(7, 5, 4, 3), a.resize(4, 3).resize(7, 5).readInts());
    }

    @Test
    void stringArraysResizeToo() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {5}, DataType.STRING).chunkShape(2)
                .fillValue(Json.parse("\"-\"")).build());
        a.writeStrings(new String[] {"a", "b", "c", "d", "e"});
        ZarrArray b = a.resize(3).resize(6);
        assertArrayEquals(new String[] {"a", "b", "c", "-", "-", "-"}, b.readStrings());
        assertFalse(store.exists("c/2"));
    }

    @Test
    void theStoredDocumentKeepsItsOtherMembers() {
        MemoryStore store = new MemoryStore();
        filled(store, 4, 4, spec(4, 4));
        // An extension member Falcon does not know, but may ignore, must survive the rewrite.
        JsonObject doc = Json.parse(store.get("zarr.json").orElseThrow()).asObject();
        Map<String, com.ebremer.falcon.zarr.json.JsonValue> members = new LinkedHashMap<>(doc.members());
        members.put("x_extension", Json.parse("{\"must_understand\":false,\"k\":[1,2]}"));
        store.set("zarr.json", Json.writeBytes(new JsonObject(members)));

        Zarr.openArray(store).resize(2, 9);
        JsonObject after = Json.parse(store.get("zarr.json").orElseThrow()).asObject();
        assertEquals(members.keySet(), after.members().keySet());
        assertEquals(members.get("x_extension"), after.get("x_extension"));
        assertEquals(Json.parse("[2,9]"), after.get("shape"));
    }

    @Test
    void aV2ArrayHasItsZarrayRewritten() {
        MemoryStore store = new MemoryStore();
        String zarray = "{\"zarr_format\":2,\"shape\":[5],\"chunks\":[2],\"dtype\":\"<i4\",\"compressor\":null,"
                + "\"fill_value\":-1,\"order\":\"C\",\"filters\":null}";
        store.set(".zarray", zarray.getBytes(StandardCharsets.UTF_8));
        store.set(".zattrs", "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        ZarrArray a = Zarr.openArray(store);
        a.writeInts(new int[] {1, 2, 3, 4, 5});
        ZarrArray b = a.resize(3).resize(4);
        assertArrayEquals(new int[] {1, 2, 3, -1}, b.readInts());
        assertEquals(Json.parse("[4]"), Json.parse(store.get(".zarray").orElseThrow()).asObject().get("shape"));
        assertEquals("{\"a\":1}", new String(store.get(".zattrs").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(1, b.attributes().get("a").asNumber().intValue());
        assertFalse(store.exists("zarr.json"));
    }

    @Test
    void aCachedHandleDoesNotServeDeletedOrClearedChunks() {
        MemoryStore store = new MemoryStore();
        ZarrArray cached = filled(store, 7, 5, spec(7, 5)).withChunkCache(1 << 20);
        cached.readInts(); // every chunk cached
        ZarrArray back = cached.resize(4, 3).resize(7, 5);
        assertArrayEquals(expected(7, 5, 4, 3), back.readInts());
    }

    @Test
    void theCurrentShapeIsReadFromTheStore() {
        MemoryStore store = new MemoryStore();
        ZarrArray stale = filled(store, 7, 5, spec(7, 5));
        Zarr.openArray(store).resize(4, 3);      // through another handle
        ZarrArray back = stale.resize(7, 5);      // shrunk to 4 x 3 meanwhile, so 4 x 3 is what is kept
        assertArrayEquals(expected(7, 5, 4, 3), back.readInts());
    }

    @Test
    void zeroExtentsAndScalars() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = filled(store, 7, 5, spec(7, 5));
        ZarrArray empty = a.resize(0, 5);
        assertEquals(0, empty.size());
        assertEquals(List.of(), store.listPrefix("c/"));
        assertArrayEquals(expected(2, 5, 0, 0), empty.resize(2, 5).readInts());

        MemoryStore other = new MemoryStore();
        ZarrArray scalar = Zarr.createArray(other, ArraySpec.builder(new long[0], DataType.INT8).build());
        scalar.writeInts(new int[] {7});
        assertArrayEquals(new int[] {7}, scalar.resize().readInts());
    }

    @Test
    void badShapesAndReadOnlyStoresAreRefused() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = filled(store, 4, 4, spec(4, 4));
        assertThrows(IllegalArgumentException.class, () -> a.resize(4));
        assertThrows(IllegalArgumentException.class, () -> a.resize(4, -1));
        assertThrows(IllegalArgumentException.class, () -> a.resize(Long.MAX_VALUE, Long.MAX_VALUE));
        assertArrayEquals(new long[] {4, 4}, Zarr.openArray(store).shape()); // nothing changed

        ZarrArray readOnly = Zarr.openArray(new ReadOnly(store));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.resize(2, 2));
        assertTrue(store.exists("c/1/1"));
    }

    /** A read-only view of a store. */
    private record ReadOnly(MemoryStore inner) implements Store {
        @Override
        public java.util.Optional<byte[]> get(String key) {
            return inner.get(key);
        }

        @Override
        public java.util.Optional<byte[]> getRange(String key, long offset, long length) {
            return inner.getRange(key, offset, length);
        }

        @Override
        public boolean exists(String key) {
            return inner.exists(key);
        }

        @Override
        public java.util.OptionalLong size(String key) {
            return inner.size(key);
        }

        @Override
        public List<String> list() {
            return inner.list();
        }

        @Override
        public List<String> listPrefix(String prefix) {
            return inner.listPrefix(prefix);
        }

        @Override
        public List<String> listDir(String prefix) {
            return inner.listDir(prefix);
        }

        @Override
        public boolean isWritable() {
            return false;
        }

        @Override
        public void set(String key, byte[] value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }
    }
}
