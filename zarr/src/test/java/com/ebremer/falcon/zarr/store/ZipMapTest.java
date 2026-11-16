package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ZipStore#map}: an uncompressed entry read where it lies in the archive. */
class ZipMapTest {

    private static byte[] random(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] bytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }

    @Test
    void aStoredEntryIsAViewOfTheArchive(@TempDir Path tmp) {
        // Sizes on both sides of a page and of Windows' 64 KiB mapping granularity, at offsets that are neither's.
        Map<String, byte[]> values = new LinkedHashMap<>();
        values.put("zarr.json", random(213, 1));
        values.put("a/c/0", random(1, 2));
        values.put("a/c/1", random(4096, 3));
        values.put("b/c/0", random(70_001, 4));
        values.put("b/c/1", random(3 * 65_536 + 17, 5));
        values.put("empty", new byte[0]);
        Path archive = tmp.resolve("store.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            values.forEach(zip::set);
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive)) {
            MemorySegment kept;
            try (Arena arena = Arena.ofShared()) {
                for (Map.Entry<String, byte[]> e : values.entrySet()) {
                    Optional<MemorySegment> mapped = zip.map(e.getKey(), arena);
                    assertTrue(mapped.isPresent(), e.getKey());
                    assertTrue(mapped.get().isReadOnly(), e.getKey());
                    assertEquals(e.getValue().length, mapped.get().byteSize(), e.getKey());
                    assertArrayEquals(e.getValue(), bytes(mapped.get()), e.getKey());
                }
                assertTrue(zip.map("absent", arena).isEmpty());
                assertFalse(zip.exists("absent"));
                kept = zip.map("b/c/1", arena).orElseThrow();
                assertThrows(IllegalArgumentException.class, () -> zip.map("/not-a-key", arena));
                assertThrows(NullPointerException.class, () -> zip.map("zarr.json", null));
            }
            // The view ends with its arena, and the store reads on.
            MemorySegment ended = kept;
            assertThrows(IllegalStateException.class, () -> ended.get(ValueLayout.JAVA_BYTE, 0));
            assertArrayEquals(values.get("b/c/1"), zip.get("b/c/1").orElseThrow());
        }
        // Nothing holds the archive once the arena and the store are closed (Windows refuses to delete a mapped file).
        assertTrue(archive.toFile().delete());
    }

    @Test
    void aCompressedEntryHasNoView(@TempDir Path tmp) throws IOException {
        byte[] stored = random(5000, 6);
        byte[] deflated = new byte[5000]; // zeros: it deflates to a few bytes
        Path archive = tmp.resolve("mixed.zip");
        try (OutputStream out = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry a = new ZipEntry("deflated");
            zip.putNextEntry(a);
            zip.write(deflated);
            zip.closeEntry();
            ZipEntry b = new ZipEntry("stored");
            b.setMethod(ZipEntry.STORED);
            b.setSize(stored.length);
            b.setCompressedSize(stored.length);
            CRC32 crc = new CRC32();
            crc.update(stored);
            b.setCrc(crc.getValue());
            zip.putNextEntry(b);
            zip.write(stored);
            zip.closeEntry();
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive); Arena arena = Arena.ofShared()) {
            assertTrue(zip.exists("deflated"));
            assertTrue(zip.map("deflated", arena).isEmpty(), "a deflated entry is not its value's bytes");
            assertArrayEquals(deflated, zip.get("deflated").orElseThrow());
            assertArrayEquals(stored, bytes(zip.map("stored", arena).orElseThrow()));
        }
    }

    @Test
    void aViewKeepsTheValueItWasMadeOf(@TempDir Path tmp) {
        byte[] first = random(9000, 7);
        byte[] second = random(12_000, 8);
        Path archive = tmp.resolve("grows.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            zip.set("k", first);
            try (Arena arena = Arena.ofShared()) {
                MemorySegment before = zip.map("k", arena).orElseThrow();
                zip.set("k", second); // appended: the first entry's bytes stay where they are
                assertArrayEquals(first, bytes(before));
                assertArrayEquals(second, bytes(zip.map("k", arena).orElseThrow()));
            }
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive); Arena arena = Arena.ofShared()) {
            assertArrayEquals(second, bytes(zip.map("k", arena).orElseThrow()));
        }
    }

    @Test
    void aRawChunkInAnArchiveIsReadInPlace(@TempDir Path tmp) {
        byte[] data = random(100_000, 9);
        Path archive = tmp.resolve("array.zip");
        try (ZipStore zip = ZipStore.create(archive)) {
            ZarrArray array = Zarr.createArray(zip, ArraySpec.builder(new long[]{data.length}, DataType.UINT8)
                    .chunkShape(60_000).build());
            array.writeRawBytes(data);
        }
        try (ZipStore zip = ZipStore.openReadOnly(archive); Arena arena = Arena.ofShared()) {
            ZarrArray array = Zarr.openArray(zip);
            assertTrue(array.hasRawChunks());
            MemorySegment head = array.chunkSegment(arena, 0).orElseThrow();
            MemorySegment tail = array.chunkSegment(arena, 1).orElseThrow();
            assertEquals(60_000, head.byteSize());
            assertEquals(60_000, tail.byteSize(), "a chunk is stored whole, past the array's edge too");
            byte[] read = new byte[data.length];
            MemorySegment.copy(head, ValueLayout.JAVA_BYTE, 0, read, 0, 60_000);
            MemorySegment.copy(tail, ValueLayout.JAVA_BYTE, 0, read, 60_000, 40_000);
            assertArrayEquals(data, read);
        }
    }
}
