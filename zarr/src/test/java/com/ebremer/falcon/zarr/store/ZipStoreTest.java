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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
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
