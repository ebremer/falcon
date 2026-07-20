package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- shared contract, run against every Store implementation ----------------------------------

    private static void runContract(Store store) {
        assertTrue(store.isWritable());
        assertTrue(store.get("zarr.json").isEmpty());
        assertFalse(store.exists("zarr.json"));

        store.set("zarr.json", bytes("root"));
        store.set("a/zarr.json", bytes("group-a"));
        store.set("a/c/0", bytes("chunk-0"));
        store.set("a/c/1", bytes("chunk-1"));
        store.set("b/zarr.json", bytes("group-b"));

        assertTrue(store.exists("zarr.json"));
        assertArrayEquals(bytes("chunk-0"), store.get("a/c/0").orElseThrow());

        assertEquals(List.of("a/c/0", "a/c/1", "a/zarr.json", "b/zarr.json", "zarr.json"), store.list());
        assertEquals(List.of("a/", "b/", "zarr.json"), store.listDir(""));
        assertEquals(List.of("a/c/", "a/zarr.json"), store.listDir("a/"));
        assertEquals(List.of("a/c/", "a/zarr.json"), store.listDir("a"));
        assertEquals(List.of("a/c/0", "a/c/1"), store.listDir("a/c/"));
        assertEquals(List.of("a/c/0", "a/c/1", "a/zarr.json"), store.listPrefix("a/"));
        assertEquals(List.of("a/c/0", "a/c/1"), store.listPrefix("a/c/"));

        store.delete("a/c/0");
        assertFalse(store.exists("a/c/0"));
        assertTrue(store.get("a/c/0").isEmpty());
        assertEquals(List.of("a/c/1"), store.listPrefix("a/c/"));
        store.delete("does/not/exist");   // no-op, no throw
    }

    @Test
    void memoryStoreContract() {
        runContract(new MemoryStore());
    }

    @Test
    void fileSystemStoreContract(@TempDir Path tmp) {
        runContract(FileSystemStore.open(tmp.resolve("store")));
    }

    // ---- byte-range reads -------------------------------------------------------------------------

    private static void runRangeContract(Store store) {
        store.set("data", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        assertArrayEquals(new byte[] {0, 1, 2, 3}, store.getRange("data", 0, 4).orElseThrow());
        assertArrayEquals(new byte[] {5, 6, 7}, store.getRange("data", 5, 3).orElseThrow());
        assertArrayEquals(new byte[] {8, 9}, store.getRange("data", 8, 10).orElseThrow());   // clamped
        assertArrayEquals(new byte[0], store.getRange("data", 10, 5).orElseThrow());          // past end
        assertArrayEquals(new byte[0], store.getRange("data", 3, 0).orElseThrow());           // zero length
        assertTrue(store.getRange("absent", 0, 4).isEmpty());                                 // missing key

        assertThrows(IllegalArgumentException.class, () -> store.getRange("data", -1, 4));
        assertThrows(IllegalArgumentException.class, () -> store.getRange("data", 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> store.getRange("data", 0, (long) Integer.MAX_VALUE + 1));
    }

    @Test
    void memoryStoreByteRanges() {
        runRangeContract(new MemoryStore());
    }

    @Test
    void fileSystemStoreByteRanges(@TempDir Path tmp) {
        runRangeContract(FileSystemStore.open(tmp.resolve("store")));
    }

    // ---- key validation ---------------------------------------------------------------------------

    @Test
    void invalidKeysAreRejected() {
        MemoryStore store = new MemoryStore();
        for (String bad : new String[] {"", "/leading", "trailing/", "a//b", "a/./b", "a/../b"}) {
            assertThrows(IllegalArgumentException.class, () -> store.set(bad, bytes("x")),
                    "should reject key: '" + bad + "'");
        }
        // A '.' inside a segment is fine — "zarr.json" is a normal key.
        store.set("zarr.json", bytes("ok"));
        assertTrue(store.exists("zarr.json"));
    }

    @Test
    void valuesAreCopiedNotShared() {
        MemoryStore store = new MemoryStore();
        byte[] value = bytes("mutable");
        store.set("k", value);
        value[0] = 'X';                                   // mutate caller's array after set
        assertArrayEquals(bytes("mutable"), store.get("k").orElseThrow());
        byte[] out = store.get("k").orElseThrow();
        out[0] = 'Y';                                     // mutate returned array
        assertArrayEquals(bytes("mutable"), store.get("k").orElseThrow());
    }

    // ---- FileSystemStore specifics ----------------------------------------------------------------

    @Test
    void fileSystemStoreWritesFilesAtExpectedPaths(@TempDir Path tmp) {
        Path root = tmp.resolve("store");
        FileSystemStore store = FileSystemStore.open(root);
        store.set("a/c/0", bytes("chunk"));
        Path chunk = root.resolve("a").resolve("c").resolve("0");
        assertTrue(Files.isRegularFile(chunk));
        assertArrayEquals(bytes("chunk"), assertReadable(chunk));
    }

    @Test
    void readOnlyStoreRejectsMutation(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("store");
        Files.createDirectories(root);
        Files.write(root.resolve("zarr.json"), bytes("root"));

        FileSystemStore store = FileSystemStore.openReadOnly(root);
        assertFalse(store.isWritable());
        assertArrayEquals(bytes("root"), store.get("zarr.json").orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> store.set("x", bytes("y")));
        assertThrows(UnsupportedOperationException.class, () -> store.delete("zarr.json"));
    }

    @Test
    void listingAnAbsentRootIsEmpty(@TempDir Path tmp) {
        FileSystemStore store = FileSystemStore.openReadOnly(tmp.resolve("missing"));
        assertEquals(List.of(), store.list());
        assertEquals(List.of(), store.listDir(""));
        assertFalse(store.exists("zarr.json"));
        assertTrue(store.get("zarr.json").isEmpty());
    }

    private static byte[] assertReadable(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
