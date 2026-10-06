package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.PartialChunkIoTest.RecordingStore;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Nested sharding (P2 F11): a shard's sub-chunks are themselves shards, as zarr-python writes them. A read of
 * part of a nested shard fetches only the indexes and inner sub-chunks it needs; a write to part of one
 * re-encodes only the inner sub-chunks it touches. zarr-python's own nested arrays are read in
 * {@link DataFixturesTest#nestedSharding()}.
 */
class NestedShardingTest {

    private static final String BYTES = "{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}";
    private static final String INDEX = "[" + BYTES + ",{\"name\":\"crc32c\"}]";

    /** A sharding codec of {@code chunkShape} sub-chunks with the given inner codecs, index at the end. */
    static String sharding(String chunkShape, String codecs, String indexCodecs) {
        return "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":" + chunkShape + ",\"codecs\":"
                + codecs + ",\"index_codecs\":" + indexCodecs + ",\"index_location\":\"end\"}}";
    }

    /** An array's zarr.json, hand-made as zarr-python makes it (ArraySpec builds one level of sharding). */
    static void create(Store store, String shape, String chunks, String dataType, String fill, String codecs) {
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + shape + ",\"data_type\":\"" + dataType
                + "\",\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":" + chunks + "}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":" + fill + ",\"codecs\":" + codecs
                + ",\"attributes\":{}}";
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
    }

    /** A copy of a fixture store, put straight into the recording store's backing store (not recorded). */
    private static RecordingStore fixture(String name) throws Exception {
        Path root = Path.of(NestedShardingTest.class.getResource("/fixtures/" + name).toURI());
        RecordingStore store = new RecordingStore();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                store.delegate.set(root.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                        Files.readAllBytes(file));
            }
        }
        return store;
    }

    /** The (offset, length) entries of a shard whose index (bytes, then crc32c) is at its end. */
    static long[] index(byte[] shard, int subChunks, boolean crc) {
        int size = subChunks * 16;
        ByteBuffer b = ByteBuffer.wrap(shard, shard.length - size - (crc ? 4 : 0), size).order(ByteOrder.LITTLE_ENDIAN);
        long[] entries = new long[2 * subChunks];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = b.getLong();
        }
        return entries;
    }

    /** The stored bytes of each sub-chunk of a shard ({@code null} for an empty one). */
    static List<byte[]> payloads(byte[] shard, int subChunks) {
        long[] entries = index(shard, subChunks, true);
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < subChunks; i++) {
            long offset = entries[2 * i];
            long length = entries[2 * i + 1];
            out.add(offset == -1 && length == -1 ? null
                    : Arrays.copyOfRange(shard, (int) offset, (int) (offset + length)));
        }
        return out;
    }

    private static int[] sequence(int n, int start) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = start + i;
        }
        return out;
    }

    // ---- what a read fetches ---------------------------------------------------------------------------

    @Test
    void aSmallReadFetchesBothIndexesAndOneInnerSubChunk() throws Exception {
        // nested_2d: one int32 shard of 16 x 12, sub-chunks of 8 x 6, each a shard of 4 x 3 sub-chunks.
        RecordingStore store = fixture("nested_2d");
        ZarrArray a = Zarr.openArray(store);
        store.calls.clear();
        assertArrayEquals(new int[] {(5 * 12 + 4) * 3 - 100},
                a.select(new long[] {5, 4}, new long[] {1, 1}).readInts());
        // The outer index (4 entries + crc32c), the inner shard's index, and one 4 x 3 int32 sub-chunk:
        // 184 bytes of the 2.6 KB shard.
        assertEquals(List.of("suffix c/0/0 68", "range c/0/0 68", "range c/0/0 48"), store.calls);
    }

    @Test
    void aReadCoveringAWholeInnerShardFetchesItInOneRange() throws Exception {
        RecordingStore store = fixture("nested_2d");
        ZarrArray a = Zarr.openArray(store);
        store.calls.clear();
        int[] block = a.select(new long[] {8, 6}, new long[] {8, 6}).readInts();
        assertEquals((8 * 12 + 6) * 3 - 100, block[0]);
        assertEquals(List.of("suffix c/0/0 68", "range c/0/0 260"), store.calls); // 4 x 48 bytes + its index
    }

    @Test
    void anInnerShardWithACodecAfterItIsFetchedWhole() throws Exception {
        // nested_compressed: a crc32c after the inner shard, so the inner shard must be read whole.
        RecordingStore store = fixture("nested_compressed");
        ZarrArray a = Zarr.openArray(store);
        store.calls.clear();
        assertArrayEquals(new double[] {(2 * 8 + 1) * 0.25},
                a.select(new long[] {2, 1}, new long[] {1, 1}).readDoubles());
        assertEquals(2, store.calls.size(), store.calls.toString());
        assertEquals("suffix c/0/0 36", store.calls.get(0));
        assertTrue(store.calls.get(1).startsWith("range c/0/0 "), store.calls.toString());
    }

    @Test
    void fiveLevelsDeepAReadFetchesOneIndexPerLevel() {
        RecordingStore store = new RecordingStore();
        String codecs = "[" + BYTES + "]";
        for (int size : new int[] {2, 4, 8, 16, 32}) {
            codecs = "[" + sharding("[" + size + "]", codecs, INDEX) + "]";
        }
        create(store.delegate, "[64]", "[64]", "int16", "0", codecs);
        Zarr.openArray(store).writeInts(sequence(64, 1));
        ZarrArray a = Zarr.openArray(store);
        store.calls.clear();
        assertArrayEquals(new int[] {38, 39, 40}, a.select(new long[] {37}, new long[] {3}).readInts());
        // The outer index, then one index per inner level (4), then the innermost data in one range.
        assertEquals("suffix c/0", store.calls.get(0).substring(0, 10));
        assertEquals(0, store.count("get "), store.calls.toString());
        assertEquals(6, store.calls.size(), store.calls.toString());
        assertArrayEquals(sequence(64, 1), Zarr.openArray(store).readInts());
    }

    @Test
    void aCachedHandleFetchesTheOuterIndexOnce() throws Exception {
        RecordingStore store = fixture("nested_2d");
        ZarrArray a = Zarr.openArray(store).withChunkCache(1 << 20);
        store.calls.clear();
        a.select(new long[] {0, 0}, new long[] {1, 1}).readInts();
        a.select(new long[] {3, 5}, new long[] {1, 1}).readInts();
        a.select(new long[] {12, 9}, new long[] {1, 1}).readInts();
        assertEquals(1, store.count("suffix "), store.calls.toString());
        // Inner indexes are not cached: each read fetches its inner shard's index and one sub-chunk.
        assertEquals(6, store.count("range "), store.calls.toString());
    }

    // ---- writes -----------------------------------------------------------------------------------------

    /** int32 10 x 9 (edge chunks), chunks 8 x 6, sub-chunks 4 x 3, inner sub-chunks 2 x 3; fill -1. */
    private static void nestedInts(Store store, String innermost) {
        create(store, "[10,9]", "[8,6]", "int32", "-1",
                "[" + sharding("[4,3]", "[" + sharding("[2,3]", innermost, INDEX) + "]", INDEX) + "]");
    }

    @Test
    void writesAndReadsRoundTripWithPartsOfShardsAbsent() {
        MemoryStore store = new MemoryStore();
        nestedInts(store, "[" + BYTES + "]");
        int[] model = new int[90];
        Arrays.fill(model, -1);
        ZarrArray a = Zarr.openArray(store);
        Random random = new Random(11);
        for (int step = 0; step < 60; step++) {
            int r0 = random.nextInt(10);
            int c0 = random.nextInt(9);
            int rows = 1 + random.nextInt(10 - r0);
            int cols = 1 + random.nextInt(9 - c0);
            int[] values = new int[rows * cols];
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextInt(4) == 0 ? -1 : random.nextInt(1000); // fill too, so parts empty out
                model[(r0 + i / cols) * 9 + c0 + i % cols] = values[i];
            }
            a.select(new long[] {r0, c0}, new long[] {rows, cols}).writeInts(values);

            int qr = random.nextInt(10);
            int qc = random.nextInt(9);
            int qrows = 1 + random.nextInt(10 - qr);
            int qcols = 1 + random.nextInt(9 - qc);
            int[] want = new int[qrows * qcols];
            for (int i = 0; i < want.length; i++) {
                want[i] = model[(qr + i / qcols) * 9 + qc + i % qcols];
            }
            assertArrayEquals(want, Zarr.openArray(store).select(new long[] {qr, qc}, new long[] {qrows, qcols})
                    .readInts(), "step " + step);
        }
        assertArrayEquals(model, Zarr.openArray(store).readInts());

        // Writing fill everywhere empties every level, and so the store.
        int[] fill = new int[90];
        Arrays.fill(fill, -1);
        a.writeInts(fill);
        assertEquals(List.of("zarr.json"), store.list());
    }

    @Test
    void aPartialWriteKeepsTheBytesOfUntouchedInnerSubChunks() {
        MemoryStore store = new MemoryStore();
        create(store, "[8,8]", "[8,8]", "int32", "0", "[" + sharding("[4,4]", "["
                + sharding("[2,2]", "[" + BYTES + ",{\"name\":\"gzip\",\"configuration\":{\"level\":9}}]", INDEX)
                + "]", INDEX) + "]");
        Zarr.openArray(store).writeInts(sequence(64, 1000));
        // Re-encode at level 1 from now on: an inner sub-chunk encoded again would come out different.
        String json = new String(store.get("zarr.json").orElseThrow(), StandardCharsets.UTF_8);
        store.set("zarr.json", json.replace("\"level\":9", "\"level\":1").getBytes(StandardCharsets.UTF_8));
        byte[] before = store.get("c/0/0").orElseThrow();

        Zarr.openArray(store).select(new long[] {1, 1}, new long[] {1, 1}).writeInts(new int[] {-5});
        byte[] after = store.get("c/0/0").orElseThrow();

        List<byte[]> outerBefore = payloads(before, 4);
        List<byte[]> outerAfter = payloads(after, 4);
        for (int i = 1; i < 4; i++) {
            assertArrayEquals(outerBefore.get(i), outerAfter.get(i), "outer sub-chunk " + i);
        }
        // In the inner shard written to, only inner sub-chunk 0 is encoded again.
        List<byte[]> innerBefore = payloads(outerBefore.get(0), 4);
        List<byte[]> innerAfter = payloads(outerAfter.get(0), 4);
        assertNotEquals(Arrays.toString(innerBefore.get(0)), Arrays.toString(innerAfter.get(0)));
        for (int i = 1; i < 4; i++) {
            assertArrayEquals(innerBefore.get(i), innerAfter.get(i), "inner sub-chunk " + i);
        }
        int[] want = sequence(64, 1000);
        want[9] = -5;
        assertArrayEquals(want, Zarr.openArray(store).readInts());
    }

    @Test
    void writeEmptyChunksReachesEveryLevel() {
        MemoryStore store = new MemoryStore();
        create(store, "[8]", "[8]", "uint8", "0",
                "[" + sharding("[4]", "[" + sharding("[2]", "[" + BYTES + "]", INDEX) + "]", INDEX) + "]");
        Zarr.openArray(store).withWriteEmptyChunks(true).writeInts(new int[8]);
        List<byte[]> outer = payloads(store.get("c/0").orElseThrow(), 2);
        for (byte[] inner : outer) {
            for (byte[] sub : payloads(inner, 2)) {
                assertTrue(sub != null && sub.length == 2, "every inner sub-chunk stored");
            }
        }
        // A partial write through such a handle keeps the inner sub-chunks it touches, fill or not.
        ZarrArray keeping = Zarr.openArray(store).withWriteEmptyChunks(true);
        keeping.select(new long[] {5}, new long[] {1}).writeInts(new int[] {0});
        assertTrue(payloads(payloads(store.get("c/0").orElseThrow(), 2).get(1), 2).get(0) != null);
        // A plain handle drops what it leaves empty.
        Zarr.openArray(store).select(new long[] {4}, new long[] {2}).writeInts(new int[2]);
        assertEquals(null, payloads(payloads(store.get("c/0").orElseThrow(), 2).get(1), 2).get(0));
    }

    @Test
    void stringsAndBytesNestRoundTrip() {
        MemoryStore store = new MemoryStore();
        create(store, "[5,4]", "[4,4]", "string", "\"-\"",
                "[" + sharding("[2,4]", "[" + sharding("[1,2]", "[{\"name\":\"vlen-utf8\"}]", INDEX) + "]", INDEX)
                        + "]");
        ZarrArray a = Zarr.openArray(store);
        String[] values = new String[20];
        for (int i = 0; i < 20; i++) {
            values[i] = i % 5 == 0 ? "-" : "s" + i + "é";
        }
        a.writeStrings(values);
        assertArrayEquals(values, Zarr.openArray(store).readStrings());
        assertArrayEquals(new String[] {values[5], values[6], values[9], values[10]},
                Zarr.openArray(store).select(new long[] {1, 1}, new long[] {2, 2}).readStrings());
        a.select(new long[] {3, 2}, new long[] {1, 1}).writeStrings(new String[] {"new"});
        values[14] = "new";
        assertArrayEquals(values, Zarr.openArray(store).readStrings());

        MemoryStore other = new MemoryStore();
        create(other, "[6]", "[6]", "variable_length_bytes", "\"\"",
                "[" + sharding("[3]", "[" + sharding("[1]", "[{\"name\":\"vlen-bytes\"}]", INDEX) + "]", INDEX) + "]");
        byte[][] blobs = {{1}, {}, {2, 3}, {4, 5, 6}, {}, {(byte) 255}};
        Zarr.openArray(other).writeByteArrays(blobs);
        assertArrayEquals(blobs, Zarr.openArray(other).readByteArrays());
        assertArrayEquals(new byte[][] {{4, 5, 6}, {}}, Zarr.openArray(other).select(new long[] {3}, new long[] {2})
                .readByteArrays());
    }

    // ---- damage -----------------------------------------------------------------------------------------

    @Test
    void anInnerIndexPointingPastItsSubChunkIsAFormatError() {
        MemoryStore store = new MemoryStore();
        String index = "[" + BYTES + "]"; // no checksum, so the damage reaches the reader
        create(store, "[8]", "[8]", "int32", "0",
                "[" + sharding("[4]", "[" + sharding("[2]", "[" + BYTES + "]", index) + "]", index) + "]");
        Zarr.openArray(store).writeInts(sequence(8, 1));
        byte[] shard = store.get("c/0").orElseThrow();
        long[] outer = index(shard, 2, false);
        // The first inner shard's own index: its first entry's offset now points far past the inner shard's
        // 16 bytes of data and 32 of index.
        int innerIndexAt = (int) (outer[0] + outer[1]) - 32;
        ByteBuffer.wrap(shard).order(ByteOrder.LITTLE_ENDIAN).putLong(innerIndexAt, 1000);
        store.set("c/0", shard);
        assertThrows(ZarrFormatException.class, () -> Zarr.openArray(store).readInts());
        assertThrows(ZarrFormatException.class,
                () -> Zarr.openArray(store).select(new long[] {0}, new long[] {1}).readInts());
        assertThrows(ZarrFormatException.class,
                () -> Zarr.openArray(store).select(new long[] {1}, new long[] {1}).writeInts(new int[] {9}));
    }

    @Test
    void indexCodecsStillCannotBeSharded() {
        MemoryStore store = new MemoryStore();
        // The index of 2 sub-chunks is a 2 x 2 array of uint64; a sharding codec there is refused.
        create(store, "[8]", "[8]", "int32", "0", "[" + sharding("[4]", "[" + BYTES + "]",
                "[" + sharding("[1,2]", "[" + BYTES + "]", INDEX) + "]") + "]");
        assertThrows(ZarrUnsupportedException.class, () -> Zarr.openArray(store).readInts());
    }
}
