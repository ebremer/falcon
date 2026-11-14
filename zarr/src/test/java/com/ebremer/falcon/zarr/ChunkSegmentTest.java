package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A chunk read in place: {@link ZarrArray#chunkSegment}, the view of a raw chunk where a
 * {@link FileSystemStore} keeps it, {@link ZarrArray#hasRawChunks()}, which says whether an array has such
 * chunks, and {@link ZarrArray#byteOrder()}, which says how to read one.
 */
class ChunkSegmentTest {

    private static byte[] bytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }

    private static byte[] ramp(int n, int seed) {
        byte[] out = new byte[n];
        new Random(seed).nextBytes(out);
        return out;
    }

    @Test
    void aRawChunkIsItsFileInPlace(@TempDir Path tmp) throws IOException {
        FileSystemStore store = FileSystemStore.open(tmp.resolve("store"));
        ZarrGroup root = Zarr.createGroup(store);
        ZarrArray array = root.createArray("bytes",
                ArraySpec.builder(new long[] {10}, DataType.UINT8).chunkShape(4).build());
        byte[] data = ramp(10, 1);
        array.writeRawBytes(data);

        assertTrue(array.hasRawChunks());
        assertEquals(ByteOrder.LITTLE_ENDIAN, array.byteOrder());
        try (Arena arena = Arena.ofShared()) {
            for (int chunk = 0; chunk < 3; chunk++) {
                MemorySegment segment = array.chunkSegment(arena, chunk).orElseThrow();
                assertTrue(segment.isReadOnly());
                assertEquals(4, segment.byteSize(), "the whole declared chunk, at the array's edge too");
                byte[] stored = Files.readAllBytes(tmp.resolve("store/bytes/c/" + chunk));
                assertArrayEquals(stored, bytes(segment), "the segment is the chunk's file");
                int inside = Math.min(4, 10 - 4 * chunk);
                assertArrayEquals(java.util.Arrays.copyOfRange(data, 4 * chunk, 4 * chunk + inside),
                        bytes(segment.asSlice(0, inside)));
            }
            // What a range read of the chunk's key returns, as hasRawChunks() promises.
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 5, 8),
                    store.getRange(array.chunkKey(1), 1, 3).orElseThrow());
        }
        // The same through a handle opened again, read-only, and through one with a chunk cache.
        ZarrArray reopened = Zarr.openArray(FileSystemStore.openReadOnly(tmp.resolve("store")), "bytes");
        try (Arena arena = Arena.ofConfined()) {
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 0, 4),
                    bytes(reopened.chunkSegment(arena, 0).orElseThrow()));
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 4, 8),
                    bytes(reopened.withChunkCache(1 << 20).chunkSegment(arena, 1).orElseThrow()));
        }
    }

    @Test
    void elementsLieInCOrderInTheArraysByteOrder(@TempDir Path tmp) {
        FileSystemStore store = FileSystemStore.open(tmp);
        ZarrGroup root = Zarr.createGroup(store);
        int[] values = IntStream.range(0, 24).map(i -> 0x01020304 * (i + 1)).toArray();
        for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            ZarrArray array = root.createArray("int32-" + order.toString().charAt(0),
                    ArraySpec.builder(new long[] {4, 6}, DataType.INT32).chunkShape(2, 3).endian(order).build());
            array.writeInts(values);
            assertTrue(array.hasRawChunks());
            assertEquals(order, array.byteOrder());
            ValueLayout.OfInt layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(order);
            try (Arena arena = Arena.ofConfined()) {
                for (int cy = 0; cy < 2; cy++) {
                    for (int cx = 0; cx < 2; cx++) {
                        MemorySegment segment = array.chunkSegment(arena, cy, cx).orElseThrow();
                        assertEquals(2 * 3 * 4, segment.byteSize());
                        for (int y = 0; y < 2; y++) {
                            for (int x = 0; x < 3; x++) {
                                assertEquals(values[(2 * cy + y) * 6 + 3 * cx + x],
                                        segment.get(layout, 4L * (y * 3 + x)), order + " chunk " + cy + "," + cx);
                            }
                        }
                        // The same bytes the raw read of that block returns.
                        assertArrayEquals(array.select(new long[] {2L * cy, 3L * cx}, new long[] {2, 3}).readRawBytes(),
                                bytes(segment));
                    }
                }
            }
        }
    }

    @Test
    void aV2ArrayWithoutCompressorHasRawChunks(@TempDir Path tmp) {
        FileSystemStore store = FileSystemStore.open(tmp);
        ZarrGroup root = Zarr.createGroup(store, new com.ebremer.falcon.zarr.json.JsonObject(java.util.Map.of()), false, 2);
        byte[] data = ramp(12, 2);
        ZarrArray raw = root.createArray("raw",
                ArraySpec.builder(new long[] {3, 4}, DataType.UINT8).chunkShape(3, 4).zarrFormat(2).build());
        raw.writeRawBytes(data);
        assertTrue(raw.hasRawChunks());
        try (Arena arena = Arena.ofConfined()) {
            assertArrayEquals(data, bytes(raw.chunkSegment(arena, 0, 0).orElseThrow()));
        }
        // Column-major chunks are stored transposed: not the elements in C order.
        ZarrArray fortran = root.createArray("fortran", ArraySpec.builder(new long[] {3, 4}, DataType.UINT8)
                .chunkShape(3, 4).zarrFormat(2).order('F').build());
        fortran.writeRawBytes(data);
        assertFalse(fortran.hasRawChunks());
        try (Arena arena = Arena.ofConfined()) {
            assertTrue(fortran.chunkSegment(arena, 0, 0).isEmpty());
        }
    }

    @Test
    void anEncodedChunkHasNoSegment(@TempDir Path tmp) {
        FileSystemStore store = FileSystemStore.open(tmp);
        ZarrGroup root = Zarr.createGroup(store);
        byte[] data = ramp(64, 3);
        ArraySpec[] encoded = {
            ArraySpec.builder(new long[] {64}, DataType.UINT8).chunkShape(64).gzip(1).build(),
            ArraySpec.builder(new long[] {64}, DataType.UINT8).chunkShape(64).zstd().build(),
            ArraySpec.builder(new long[] {64}, DataType.UINT8).chunkShape(64).crc32c().build(),
            ArraySpec.builder(new long[] {64}, DataType.UINT8).chunkShape(64).sharding(16).build(),
            ArraySpec.builder(new long[] {64}, DataType.UINT8).chunkShape(64).castValue(DataType.UINT16).build(),
        };
        try (Arena arena = Arena.ofConfined()) {
            for (int i = 0; i < encoded.length; i++) {
                ZarrArray array = root.createArray("encoded" + i, encoded[i]);
                array.writeRawBytes(data);
                assertTrue(store.exists(array.chunkKey(0)), array.codecNames().toString());
                assertFalse(array.hasRawChunks(), array.codecNames().toString());
                assertTrue(array.chunkSegment(arena, 0).isEmpty(), array.codecNames().toString());
                assertArrayEquals(data, array.readRawBytes());
            }
        }
        // Variable-length elements have no fixed place in a chunk.
        ZarrArray strings = root.createArray("strings",
                ArraySpec.builder(new long[] {2}, DataType.STRING).chunkShape(2).build());
        strings.writeStrings(new String[] {"a", "bc"});
        assertFalse(strings.hasRawChunks());
        try (Arena arena = Arena.ofConfined()) {
            assertTrue(strings.chunkSegment(arena, 0).isEmpty());
        }
    }

    @Test
    void aChunkNotStoredAndAStoreThatMapsNothingHaveNoSegment(@TempDir Path tmp) {
        FileSystemStore files = FileSystemStore.open(tmp);
        ZarrArray array = Zarr.createGroup(files).createArray("a",
                ArraySpec.builder(new long[] {8}, DataType.UINT8).chunkShape(4).build());
        array.select(new long[] {4}, new long[] {4}).writeRawBytes(new byte[] {1, 2, 3, 4});
        try (Arena arena = Arena.ofConfined()) {
            // Chunk 0 was never written: it reads as the fill value, and there is nothing to map.
            assertFalse(files.exists(array.chunkKey(0)));
            assertTrue(array.chunkSegment(arena, 0).isEmpty());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes(array.chunkSegment(arena, 1).orElseThrow()));
            // A handle that keeps chunks holding only the fill value has one to map.
            array.withWriteEmptyChunks(true).select(new long[] {0}, new long[] {4}).writeRawBytes(new byte[4]);
            assertArrayEquals(new byte[4], bytes(array.chunkSegment(arena, 0).orElseThrow()));

            assertThrows(IndexOutOfBoundsException.class, () -> array.chunkSegment(arena, 2));
            assertThrows(IllegalArgumentException.class, () -> array.chunkSegment(arena, 0, 0));
            assertThrows(NullPointerException.class, () -> array.chunkSegment(null, 0));
        }

        Store memory = new MemoryStore();
        ZarrArray inMemory = Zarr.createGroup(memory).createArray("a",
                ArraySpec.builder(new long[] {4}, DataType.UINT8).chunkShape(4).build());
        inMemory.writeRawBytes(new byte[] {1, 2, 3, 4});
        assertTrue(inMemory.hasRawChunks(), "raw, so a range of the key is a range of the elements");
        assertArrayEquals(new byte[] {2, 3}, memory.getRange(inMemory.chunkKey(0), 1, 2).orElseThrow());
        try (Arena arena = Arena.ofConfined()) {
            assertTrue(memory.exists(inMemory.chunkKey(0)));
            assertTrue(inMemory.chunkSegment(arena, 0).isEmpty(), "a store that cannot map");
        }
    }

    @Test
    void aChunkOfTheWrongSizeIsRefused(@TempDir Path tmp) throws IOException {
        FileSystemStore store = FileSystemStore.open(tmp);
        ZarrArray array = Zarr.createGroup(store).createArray("a",
                ArraySpec.builder(new long[] {8}, DataType.INT16).chunkShape(8).build());
        array.writeRawBytes(ramp(16, 4));
        for (int size : new int[] {15, 17, 0}) {
            Files.write(tmp.resolve("a/c/0"), new byte[size]);
            try (Arena arena = Arena.ofConfined()) {
                ZarrFormatException e = assertThrows(ZarrFormatException.class, () -> array.chunkSegment(arena, 0));
                assertTrue(e.getMessage().contains("a/c/0") && e.getMessage().contains(size + " bytes")
                        && e.getMessage().contains("expected 16"), e.getMessage());
            }
        }
    }

    @Test
    void theSegmentLastsAsLongAsItsArena(@TempDir Path tmp) throws Exception {
        FileSystemStore store = FileSystemStore.open(tmp);
        ZarrArray array = Zarr.createGroup(store).createArray("a",
                ArraySpec.builder(new long[] {1 << 16}, DataType.UINT8).chunkShape(1 << 16).build());
        byte[] data = ramp(1 << 16, 5);
        array.writeRawBytes(data);

        Arena arena = Arena.ofShared();
        MemorySegment segment = array.chunkSegment(arena, 0).orElseThrow();
        // Shared: read from another thread, while this one reads too.
        AtomicReference<byte[]> elsewhere = new AtomicReference<>();
        Thread reader = new Thread(() -> elsewhere.set(bytes(segment)));
        reader.start();
        assertArrayEquals(data, bytes(segment));
        reader.join();
        assertArrayEquals(data, elsewhere.get());
        assertThrows(IllegalArgumentException.class, () -> segment.set(ValueLayout.JAVA_BYTE, 0, (byte) 1)); // read-only

        arena.close();
        assertThrows(IllegalStateException.class, () -> segment.get(ValueLayout.JAVA_BYTE, 0));
        // Unmapped: the chunk's file is free again, on Windows too.
        byte[] next = ramp(1 << 16, 6);
        array.writeRawBytes(next);
        try (Arena again = Arena.ofConfined()) {
            assertArrayEquals(next, bytes(array.chunkSegment(again, 0).orElseThrow()));
        }
        store.delete(array.chunkKey(0));
        assertFalse(Files.exists(tmp.resolve("a/c/0")));
    }

    @Test
    void aStoreMapsAValueOrSaysItCannot(@TempDir Path tmp) {
        FileSystemStore store = FileSystemStore.open(tmp.resolve("store"));
        byte[] value = ramp(1000, 7);
        store.set("group/value", value);
        store.set("empty", new byte[0]);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = store.map("group/value", arena).orElseThrow();
            assertTrue(mapped.isReadOnly());
            assertArrayEquals(value, bytes(mapped));
            assertEquals(0, store.map("empty", arena).orElseThrow().byteSize());
            assertTrue(store.map("absent", arena).isEmpty());
            assertTrue(store.map("group", arena).isEmpty(), "a directory is not a value");
            assertArrayEquals(value, bytes(FileSystemStore.openReadOnly(tmp.resolve("store"))
                    .map("group/value", arena).orElseThrow()));

            assertThrows(IllegalArgumentException.class, () -> store.map("../escape", arena));
            assertThrows(IllegalArgumentException.class, () -> store.map("", arena));
            assertThrows(NullPointerException.class, () -> store.map("group/value", null));

            // The default: no view, whether the key is there or not, and the same checks of its arguments.
            Store memory = new MemoryStore();
            memory.set("value", value);
            assertEquals(Optional.empty(), memory.map("value", arena));
            assertEquals(Optional.empty(), memory.map("absent", arena));
            assertThrows(IllegalArgumentException.class, () -> memory.map("../escape", arena));
            assertThrows(NullPointerException.class, () -> memory.map("value", null));
        }
    }
}
