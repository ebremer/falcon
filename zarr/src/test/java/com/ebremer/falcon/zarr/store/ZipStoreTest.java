package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.OptionalLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipStoreTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void packsAndReadsBackAStore(@TempDir Path tmp) {
        MemoryStore source = new MemoryStore();
        source.set("zarr.json", bytes("root"));
        source.set("a/zarr.json", bytes("group-a"));
        source.set("a/c/0", bytes("chunk-0"));
        source.set("a/c/1", bytes("chunk-1"));

        Path archive = tmp.resolve("store.zip");
        ZipStore.pack(source, archive);

        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertFalse(zip.isWritable());
            assertEquals(List.of("a/c/0", "a/c/1", "a/zarr.json", "zarr.json"), zip.list());
            assertEquals(List.of("a/", "zarr.json"), zip.listDir(""));
            assertEquals(List.of("a/c/", "a/zarr.json"), zip.listDir("a/"));
            assertArrayEquals(bytes("chunk-0"), zip.get("a/c/0").orElseThrow());
            assertTrue(zip.exists("a/c/1"));
            assertFalse(zip.exists("missing"));
            assertEquals(OptionalLong.of(7), zip.size("a/c/0"));
            assertTrue(zip.size("missing").isEmpty());
            // byte ranges are sliced from the whole entry
            assertArrayEquals(bytes("unk"), zip.get("a/c/0").map(b -> new byte[] {b[2], b[3], b[4]}).orElseThrow());
            assertArrayEquals(new byte[] {'c', 'h'}, zip.getRange("a/c/0", 0, 2).orElseThrow());
            assertArrayEquals(new byte[0], zip.getRange("a/c/0", 100, 5).orElseThrow());
            assertThrows(UnsupportedOperationException.class, () -> zip.set("x", bytes("y")));
        }
    }

    /**
     * {@code pack} deflated every entry, though Zarr archives (zarr-python's ZipStore) store entries
     * uncompressed: the chunks are compressed already, and only a stored entry can be read a range at a
     * time (P1 PF4).
     */
    @Test
    void packStoresEntriesUncompressed(@TempDir Path tmp) throws Exception {
        MemoryStore source = new MemoryStore();
        source.set("zarr.json", bytes("root"));
        source.set("a/c/0", new byte[100_000]);
        source.set("empty", new byte[0]);
        Path archive = tmp.resolve("store.zip");
        ZipStore.pack(source, archive);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                assertEquals(ZipEntry.STORED, entry.getMethod(), entry.getName());
            }
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            assertArrayEquals(new byte[100_000], zip.get("a/c/0").orElseThrow());
            assertArrayEquals(new byte[0], zip.get("empty").orElseThrow());
        }
    }

    /**
     * {@code getRange} decompressed or read the whole entry on every call, so sharded data in a ZIP cost a
     * shard per sub-chunk (P1 PF4). A stored entry is now read at the range; a deflated one is inflated only
     * up to the range's end. Both must still give the same bytes.
     */
    @Test
    void rangesAndSuffixesOfStoredAndDeflatedEntries(@TempDir Path tmp) throws Exception {
        byte[] value = new byte[300_000];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (i % 251 * 3 + i / 4096);
        }
        Path stored = tmp.resolve("stored.zip");
        MemoryStore source = new MemoryStore();
        source.set("v", value);
        ZipStore.pack(source, stored);
        Path deflated = tmp.resolve("deflated.zip");
        try (OutputStream out = Files.newOutputStream(deflated); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("v")); // DEFLATED, the ZipOutputStream default
            zip.write(value);
            zip.closeEntry();
        }
        for (Path archive : new Path[] {stored, deflated}) {
            try (ZipStore zip = ZipStore.openReadOnly(archive)) {
                assertArrayEquals(Arrays.copyOfRange(value, 0, 10), zip.getRange("v", 0, 10).orElseThrow());
                assertArrayEquals(Arrays.copyOfRange(value, 123_456, 133_456), zip.getRange("v", 123_456, 10_000).orElseThrow());
                assertArrayEquals(Arrays.copyOfRange(value, 299_990, 300_000), zip.getRange("v", 299_990, 100).orElseThrow());
                assertArrayEquals(new byte[0], zip.getRange("v", 300_000, 5).orElseThrow());
                assertArrayEquals(new byte[0], zip.getRange("v", 5, 0).orElseThrow());
                assertArrayEquals(Arrays.copyOfRange(value, 299_000, 300_000), zip.getSuffix("v", 1000).orElseThrow());
                assertArrayEquals(value, zip.getSuffix("v", 1_000_000).orElseThrow());
                assertTrue(zip.getRange("absent", 0, 4).isEmpty());
                assertTrue(zip.getSuffix("absent", 4).isEmpty());
            }
        }
    }

    @Test
    void readsAZarrHierarchyFromAZip(@TempDir Path tmp) {
        // Build a real Zarr store, pack it, and read the array back out of the archive.
        MemoryStore memory = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(memory);
        root.createArray("data", ArraySpec.builder(new long[] {10}, DataType.INT32)
                .chunkShape(4).gzip(5).build())
                .writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        Path archive = tmp.resolve("data.zip");
        ZipStore.pack(memory, archive);

        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            ZarrGroup group = Zarr.open(zip).asGroup();
            assertEquals(List.of("data"), group.childNames());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, group.array("data").readInts());
            assertArrayEquals(new int[] {3, 4, 5, 6},
                    group.array("data").select(new long[] {3}, new long[] {4}).readInts());
        }
    }
}
