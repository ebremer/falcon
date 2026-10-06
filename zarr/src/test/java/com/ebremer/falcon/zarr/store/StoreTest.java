package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
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

    @Test
    void zipStoreContract(@TempDir Path tmp) {
        try (ZipStore zip = ZipStore.create(tmp.resolve("store.zip"))) {
            runContract(zip);
        }
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

    @Test
    void zipStoreByteRanges(@TempDir Path tmp) {
        try (ZipStore zip = ZipStore.create(tmp.resolve("store.zip"))) {
            runRangeContract(zip);
        }
    }

    // ---- suffix reads -----------------------------------------------------------------------------

    private static void runSuffixContract(Store store) {
        store.set("data", new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        store.set("empty", new byte[0]);
        assertArrayEquals(new byte[] {7, 8, 9}, store.getSuffix("data", 3).orElseThrow());
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, store.getSuffix("data", 10).orElseThrow());
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, store.getSuffix("data", 50).orElseThrow());
        assertArrayEquals(new byte[0], store.getSuffix("data", 0).orElseThrow());
        assertArrayEquals(new byte[0], store.getSuffix("empty", 4).orElseThrow());
        assertTrue(store.getSuffix("absent", 4).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.getSuffix("data", -1));
        assertThrows(IllegalArgumentException.class, () -> store.getSuffix("data", (long) Integer.MAX_VALUE + 1));
    }

    @Test
    void memoryStoreSuffixes() {
        runSuffixContract(new MemoryStore());
    }

    @Test
    void fileSystemStoreSuffixes(@TempDir Path tmp) {
        runSuffixContract(FileSystemStore.open(tmp.resolve("store")));
    }

    @Test
    void zipStoreSuffixes(@TempDir Path tmp) {
        try (ZipStore zip = ZipStore.create(tmp.resolve("store.zip"))) {
            runSuffixContract(zip);
        }
    }

    /** {@code Store.getSuffix}'s default, through size() and getRange(), for a store that does not override it. */
    @Test
    void theDefaultSuffixRead() {
        runSuffixContract(new Delegating(new MemoryStore()));
    }

    /** A store that only delegates, so it uses every default method of {@link Store}. */
    private record Delegating(Store store) implements Store {
        @Override
        public Optional<byte[]> get(String key) {
            return store.get(key);
        }

        @Override
        public Optional<byte[]> getRange(String key, long offset, long length) {
            return store.getRange(key, offset, length);
        }

        @Override
        public boolean exists(String key) {
            return store.exists(key);
        }

        @Override
        public OptionalLong size(String key) {
            return store.size(key);
        }

        @Override
        public List<String> list() {
            return store.list();
        }

        @Override
        public List<String> listPrefix(String prefix) {
            return store.listPrefix(prefix);
        }

        @Override
        public List<String> listDir(String prefix) {
            return store.listDir(prefix);
        }

        @Override
        public boolean isWritable() {
            return store.isWritable();
        }

        @Override
        public void set(String key, byte[] value) {
            store.set(key, value);
        }

        @Override
        public void delete(String key) {
            store.delete(key);
        }
    }

    // ---- threads (C1) -----------------------------------------------------------------------------

    /**
     * {@code MemoryStore} was a plain {@code HashMap}: parallel block writes lost keys, zarr.json among
     * them, and a listing beside a write threw {@code ConcurrentModificationException} (P1 C1).
     */
    @Test
    void memoryStoreTakesParallelWritersAndReaders() throws Exception {
        for (int round = 0; round < 5; round++) {
            MemoryStore store = new MemoryStore();
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                threads.add(new Thread(() -> {
                    try {
                        for (int i = 0; i < 2000; i++) {
                            store.set("w" + id + "/c/" + i, new byte[] {(byte) i});
                            if (i % 50 == 0) {
                                store.listDir("");
                                store.listPrefix("w" + id + "/");
                                store.get("w" + id + "/c/" + (i / 2)).orElseThrow();
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }));
            }
            threads.forEach(Thread::start);
            for (Thread thread : threads) {
                thread.join();
            }
            assertTrue(errors.isEmpty(), () -> "a thread failed: " + errors.peek());
            assertEquals(16_000, store.list().size(), "keys were lost");
        }
    }

    // ---- FileSystemStore listings (PF3) -----------------------------------------------------------

    /**
     * Listings walked the whole store on every call, about a second next to 20,000 chunks (P1 PF3); they
     * now read only the prefix's directory. They must still list exactly what a whole-tree walk filtered
     * by prefix lists, for every prefix.
     */
    @Test
    void fileSystemStoreListingsMatchAWholeTreeWalk(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("store");
        FileSystemStore store = FileSystemStore.open(root);
        for (String key : new String[] {"zarr.json", "a/zarr.json", "a/c/0/0", "a/c/0/1", "a/c/1/0", "ab/zarr.json",
                "ab/x", "b/deep/er/k", "c.json", ".zattrs"}) {
            store.set(key, bytes(key));
        }
        Files.createDirectories(root.resolve("empty/inside")); // holds no key: not a child prefix
        Files.createDirectories(root.resolve("a/c/hollow"));

        List<String> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> all.add(root.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(StoreKeys.listPrefix(all, ""), store.list());
        TreeSet<String> prefixes = new TreeSet<>(List.of("", "/", "//", "a//", "./", "../", "a/../", "zz/", "zarr.json/",
                "a/c/0/1/", "A/", "a/C/"));
        for (String key : all) {
            for (int i = 0; i <= key.length(); i++) {
                prefixes.add(key.substring(0, i));
            }
        }
        for (String prefix : prefixes) {
            assertEquals(StoreKeys.listPrefix(all, prefix), store.listPrefix(prefix), "listPrefix(\"" + prefix + "\")");
            assertEquals(StoreKeys.listDir(all, prefix), store.listDir(prefix), "listDir(\"" + prefix + "\")");
        }
        assertEquals(List.of(".zattrs", "a/", "ab/", "b/", "c.json", "zarr.json"), store.listDir(""));
        assertEquals(List.of("a/c/0/", "a/c/1/"), store.listDir("a/c"));
        assertEquals(List.of("ab/x", "ab/zarr.json"), store.listPrefix("ab"));
        assertEquals(List.of(), store.listDir("A/")); // keys are case-sensitive, even where files are not
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

    /**
     * Windows drops a trailing dot or space from a file name and splits at a backslash, so
     * {@code FileSystemStore} read and wrote "data./zarr.json" as data/zarr.json (P1 I12). Such segments
     * are refused on every platform, so a store reads the same everywhere.
     */
    @Test
    void fileSystemStoreRefusesSegmentsWindowsWouldAlias(@TempDir Path tmp) {
        FileSystemStore store = FileSystemStore.open(tmp.resolve("store"));
        store.set("data/zarr.json", bytes("good"));
        for (String bad : new String[] {"data./zarr.json", "data /zarr.json", "data/zarr.json.", "a\\b", "x/y ",
                "..."}) {
            assertThrows(IllegalArgumentException.class, () -> store.set(bad, bytes("bad")), bad);
            assertThrows(IllegalArgumentException.class, () -> store.get(bad), bad);
        }
        assertArrayEquals(bytes("good"), store.get("data/zarr.json").orElseThrow());
        // A dot inside or at the start of a segment is fine, and other stores take any segment.
        store.set(".zattrs", bytes("ok"));
        store.set("v2/0.0", bytes("ok"));
        MemoryStore memory = new MemoryStore();
        memory.set("data./zarr.json", bytes("ok"));
        assertTrue(memory.exists("data./zarr.json"));
    }

    /**
     * Where the file system allows such names, a listing leaves them out: no key can reach them. Windows
     * cannot hold them, so there this checks nothing (the Linux CI leg runs it).
     */
    @Test
    void fileSystemStoreListingsSkipNamesNoKeyCanReach(@TempDir Path tmp) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            return; // not skipped: a skip prints a build warning
        }
        Path root = tmp.resolve("store");
        FileSystemStore store = FileSystemStore.open(root);
        store.set("a/zarr.json", bytes("ok"));
        Files.createDirectories(root.resolve("data."));
        Files.write(root.resolve("data./zarr.json"), bytes("x"));
        Files.write(root.resolve("a/trailing "), bytes("x"));
        assertEquals(List.of("a/"), store.listDir(""));
        assertEquals(List.of("a/zarr.json"), store.list());
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

    /**
     * {@code set} truncated the file and wrote it in place, so a concurrent reader could see a short or
     * mixed value (P0 Z9). It now writes a temporary file and renames it over the old one.
     */
    @Test
    void aConcurrentReaderSeesWholeValuesOnly(@TempDir Path tmp) throws Exception {
        FileSystemStore store = FileSystemStore.open(tmp);
        byte[] big = new byte[256 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        byte[] small = new byte[1000];
        java.util.Arrays.fill(small, (byte) 'b');
        store.set("c/0", small);

        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> torn = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            while (!done.get() && torn.get() == null) {
                byte[] value = store.get("c/0").orElse(null);
                if (value == null || !(java.util.Arrays.equals(value, big) || java.util.Arrays.equals(value, small))) {
                    torn.set(value == null ? "absent" : value.length + " bytes");
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 300 && torn.get() == null; i++) {
                store.set("c/0", i % 2 == 0 ? big : small);
            }
        } finally {
            done.set(true);
            reader.join();
        }
        assertEquals(null, torn.get(), "a reader saw part of a write");
        // No temporary file is left behind.
        try (java.util.stream.Stream<Path> files = Files.list(tmp.resolve("c"))) {
            assertEquals(List.of("0"), files.map(p -> p.getFileName().toString()).toList());
        }
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
