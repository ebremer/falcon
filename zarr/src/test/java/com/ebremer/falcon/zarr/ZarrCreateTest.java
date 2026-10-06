package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Creating a node where one exists (P0 Z1), and checking a spec before anything is stored (P0 Z2).
 *
 * <p>Re-creating a node used to overwrite only its {@code zarr.json}: the old chunks survived and read
 * back as the new array's data. A bad spec was written before it was checked, so it replaced good
 * metadata with metadata that could not be opened.
 */
class ZarrCreateTest {

    private static final JsonObject NO_ATTRIBUTES = new JsonObject(Map.of());

    private static ArraySpec int32(long n, long chunk) {
        return ArraySpec.builder(new long[] {n}, DataType.INT32).chunkShape(chunk).build();
    }

    // ---- Z1: an existing node is refused, or deleted with overwrite --------------------------------

    @Test
    void recreatingAnArrayIsRefusedAndKeepsItsData() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("x", int32(4, 2)).writeInts(new int[] {1, 2, 3, 4});

        ArraySpec replacement = ArraySpec.builder(new long[] {4}, DataType.INT32).chunkShape(2).fillValue(-1).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> root.createArray("x", replacement));
        assertTrue(e.getMessage().contains("overwrite"), e.getMessage());
        assertArrayEquals(new int[] {1, 2, 3, 4}, root.array("x").readInts());
    }

    @Test
    void overwriteDeletesTheOldArraysChunks() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("x", int32(4, 2)).writeInts(new int[] {1, 2, 3, 4});

        // Before the fix this read the old [1, 2, 3, 4].
        ZarrArray filled = root.createArray("x",
                ArraySpec.builder(new long[] {4}, DataType.INT32).chunkShape(2).fillValue(-1).build(), true);
        assertArrayEquals(new int[] {-1, -1, -1, -1}, filled.readInts());
        assertEquals(List.of("x/zarr.json"), store.listPrefix("x/"));

        root.array("x").writeInts(new int[] {5, 6, 7, 8});
        // Before the fix this read the old int bits as floats: [1.4E-45, ...].
        ZarrArray floats = root.createArray("x",
                ArraySpec.builder(new long[] {4}, DataType.FLOAT32).chunkShape(2).build(), true);
        assertArrayEquals(new float[] {0, 0, 0, 0}, floats.readFloats());
    }

    @Test
    void recreatingAGroupIsRefusedAndOverwriteDeletesItsDescendants() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        ZarrGroup g = root.createGroup("g");
        g.createGroup("inner").createArray("a", int32(2, 2)).writeInts(new int[] {1, 2});

        assertThrows(IllegalArgumentException.class, () -> root.createGroup("g"));
        assertEquals(List.of("inner"), root.group("g").childNames());

        ZarrGroup fresh = root.createGroup("g", NO_ATTRIBUTES, true);
        assertEquals(List.of(), fresh.childNames());
        assertEquals(List.of("g/zarr.json"), store.listPrefix("g/"));
    }

    @Test
    void anArrayAndAGroupReplaceEachOtherOnlyWithOverwrite() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("n", int32(4, 2)).writeInts(new int[] {1, 2, 3, 4});
        assertThrows(IllegalArgumentException.class, () -> root.createGroup("n"));

        assertTrue(root.createGroup("n", NO_ATTRIBUTES, true).isGroup());
        assertEquals(List.of("n/zarr.json"), store.listPrefix("n/")); // the chunks went with the array

        root.group("n").createGroup("child");
        assertThrows(IllegalArgumentException.class, () -> root.createArray("n", int32(4, 2)));
        assertArrayEquals(new int[] {0, 0, 0, 0}, root.createArray("n", int32(4, 2), true).readInts());
        assertEquals(List.of("n/zarr.json"), store.listPrefix("n/"));
    }

    @Test
    void siblingsWithACommonPrefixAreUntouched() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("x", int32(2, 2)).writeInts(new int[] {1, 2});
        root.createArray("x2", int32(2, 2)).writeInts(new int[] {3, 4});

        root.createArray("x", int32(2, 2), true);
        assertArrayEquals(new int[] {3, 4}, root.array("x2").readInts());
    }

    @Test
    void anArrayIsNotCreatedOverStrayChunks() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("x", int32(4, 2)).writeInts(new int[] {1, 2, 3, 4});
        store.delete("x/zarr.json"); // chunks with no node: a new array would read them as its own

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> root.createArray("x", int32(4, 2)));
        assertTrue(e.getMessage().contains("x/c/0"), e.getMessage());
        assertArrayEquals(new int[] {0, 0, 0, 0}, root.createArray("x", int32(4, 2), true).readInts());
    }

    @Test
    void theRootIsRefusedOnceItExists() {
        MemoryStore store = new MemoryStore();
        Zarr.createGroup(store);
        assertThrows(IllegalArgumentException.class, () -> Zarr.createGroup(store));
        assertThrows(IllegalArgumentException.class, () -> Zarr.createArray(store, int32(2, 2)));

        MemoryStore other = new MemoryStore();
        other.set("notes.txt", new byte[] {1});
        // A root array's chunk keys sit at the top of the store, so any key is in its way ...
        assertThrows(IllegalArgumentException.class, () -> Zarr.createArray(other, int32(2, 2)));
        // ... but a group reads only its own zarr.json.
        assertTrue(Zarr.createGroup(other).isGroup());
        assertTrue(other.exists("notes.txt"));
    }

    @Test
    void overwritingTheRootEmptiesTheStore() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("a", int32(2, 2)).writeInts(new int[] {1, 2});
        store.set("notes.txt", new byte[] {1});

        ZarrArray array = Zarr.createArray(store, int32(3, 2), true);
        assertArrayEquals(new int[] {0, 0, 0}, array.readInts());
        assertEquals(List.of("zarr.json"), store.list());

        array.writeInts(new int[] {1, 2, 3});
        Zarr.createGroup(store, NO_ATTRIBUTES, true);
        assertEquals(List.of("zarr.json"), store.list());
    }

    @Test
    void overwriteDeletesFilesOnDisk(@TempDir Path dir) {
        FileSystemStore store = FileSystemStore.open(dir);
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("x", int32(4, 2)).writeInts(new int[] {1, 2, 3, 4});
        assertTrue(Files.exists(dir.resolve("x/c/1")));

        root.createArray("x", int32(4, 2), true);
        assertFalse(Files.exists(dir.resolve("x/c/0")));
        assertFalse(Files.exists(dir.resolve("x/c/1")));
        assertArrayEquals(new int[] {0, 0, 0, 0}, root.array("x").readInts());
    }

    @Test
    void aReadOnlyStoreRefusesCreation(@TempDir Path dir) {
        Zarr.createGroup(FileSystemStore.open(dir));
        Store readOnly = FileSystemStore.openReadOnly(dir);
        ZarrGroup root = Zarr.openGroup(readOnly);
        assertThrows(UnsupportedOperationException.class, () -> root.createGroup("g"));
        assertThrows(UnsupportedOperationException.class, () -> root.createArray("a", int32(2, 2), true));
        assertThrows(UnsupportedOperationException.class, () -> Zarr.createGroup(readOnly, NO_ATTRIBUTES, true));
    }

    // ---- Z2: build() checks the spec, so a bad one never reaches the store --------------------------

    @Test
    void buildRejectsWhatOpeningWouldReject() {
        assertBuildFails(ArraySpec.builder(new long[] {4, 4}, DataType.INT32).chunkShape(2), "rank");
        assertBuildFails(ArraySpec.builder(new long[] {-4}, DataType.INT32), "non-negative");
        assertBuildFails(ArraySpec.builder(new long[] {4, 4}, DataType.INT32).dimensionNames("y"), "dimension_names");
        assertBuildFails(ArraySpec.builder(new long[] {8}, DataType.INT32).chunkShape(8).sharding(3), "divide");
        assertBuildFails(ArraySpec.builder(new long[] {8}, DataType.INT32).gzip(42), "gzip level");
        assertBuildFails(ArraySpec.builder(new long[] {8}, DataType.INT32).chunkKeyEncoding("v9"), "encoding");
        assertBuildFails(ArraySpec.builder(new long[] {8}, DataType.INT32).separator("-"), "separator");
        assertBuildFails(ArraySpec.builder(new long[] {8}, DataType.INT32).chunkShape(0), "positive");
        // The default chunk covers the array, and a chunk is decoded into one Java array.
        assertBuildFails(ArraySpec.builder(new long[] {1L << 30}, DataType.INT32), "2 GB");
        assertBuildFails(ArraySpec.builder(new long[] {1L << 32, 1L << 32}, DataType.INT8).chunkShape(1, 1),
                "elements");
    }

    @Test
    void aBadSpecLeavesTheExistingArrayAlone() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("x", int32(4, 2)).writeInts(new int[] {1, 2, 3, 4});

        // Before the fix each of these was written over x/zarr.json, and x (and root.children()) failed.
        assertThrows(IllegalArgumentException.class,
                () -> root.createArray("x", ArraySpec.builder(new long[] {4}, DataType.INT32).chunkShape(2, 2).build(), true));
        assertThrows(IllegalArgumentException.class,
                () -> root.createArray("x", ArraySpec.builder(new long[] {4}, DataType.INT32).gzip(42).build(), true));
        assertArrayEquals(new int[] {1, 2, 3, 4}, root.array("x").readInts());
        assertEquals(1, root.children().size());
    }

    private static void assertBuildFails(ArraySpec.Builder builder, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }
}
