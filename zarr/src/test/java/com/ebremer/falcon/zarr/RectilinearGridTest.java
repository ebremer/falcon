package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.PartialChunkIoTest.RecordingStore;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Arrays on a rectilinear chunk grid (P2 F14; zarr-extensions {@code chunk-grids/rectilinear}, which zarr-python
 * 3.4 writes behind {@code array.rectilinear_chunks}): chunks differ in shape along a dimension, and every path
 * that walks chunks (selections, writes, shards, strings, the chunk cache, {@code writeEmptyChunks}, resizing,
 * consolidated metadata) follows the grid. zarr-python's own rectilinear arrays are read in
 * {@link RectilinearFixtureTest}.
 */
class RectilinearGridTest {

    /** A 10 x 7 int32 array with rows chunked 3, 3, 4 and columns 2, 5. */
    private static ArraySpec.Builder tenBySeven() {
        return ArraySpec.builder(new long[] {10, 7}, DataType.INT32).chunkLengths(0, 3, 3, 4).chunkLengths(1, 2, 5);
    }

    private static int[] sequence(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        return out;
    }

    private static String document(Store store, String key) {
        return new String(store.get(key).orElseThrow(), StandardCharsets.UTF_8);
    }

    private static String chunkShapes(Store store, String key) {
        return Json.parse(store.get(key).orElseThrow()).asObject().get("chunk_grid").asObject()
                .get("configuration").asObject().get("chunk_shapes").toJson();
    }

    // ---- reading and writing ---------------------------------------------------------------------------

    @Test
    void chunksDifferInShapeAndAreStoredAtTheirOwnShape() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, tenBySeven().build());
        assertEquals("[[[3,2],4],[2,5]]", chunkShapes(store, "zarr.json"));
        a.writeInts(sequence(70));
        assertArrayEquals(sequence(70), a.readInts());
        // Uncompressed, each chunk holds exactly its own elements.
        assertEquals(3 * 2 * 4, store.get("c/0/0").orElseThrow().length);
        assertEquals(3 * 5 * 4, store.get("c/1/1").orElseThrow().length);
        assertEquals(4 * 5 * 4, store.get("c/2/1").orElseThrow().length);
        assertEquals(6, store.listPrefix("c/").size());
        // A selection across chunks of three shapes.
        int[] got = a.select(new long[] {2, 1}, new long[] {5, 3}).readInts();
        int[] want = new int[15];
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 3; c++) {
                want[r * 3 + c] = (r + 2) * 7 + c + 1;
            }
        }
        assertArrayEquals(want, got);
    }

    /** As zarr-python encodes it: a chunk reaching past the array keeps its listed length, the tail fill. */
    @Test
    void anEdgeChunkIsStoredAtItsListedLength() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {10}, DataType.INT16)
                .chunkLengths(0, 4, 4, 4, 8).fillValue(-1).build());
        assertArrayEquals(new long[] {3}, a.gridShape()); // the 8 lies wholly past the array
        a.writeInts(sequence(10));
        byte[] edge = store.get("c/2").orElseThrow();
        assertEquals(4 * 2, edge.length);
        assertEquals(-1, (short) ((edge[6] & 0xff) | edge[7] << 8)); // element 11, past the array: fill
        assertFalse(store.exists("c/3"));
        assertArrayEquals(new int[] {8, 9}, a.select(new long[] {8}, new long[] {2}).readInts());
    }

    /** Random writes and reads of random grids, against a plain Java array, through every kind of handle. */
    @Test
    void randomWritesAndReadsMatchAModel() {
        Random random = new Random(1414);
        for (int trial = 0; trial < 120; trial++) {
            int rank = 1 + random.nextInt(3);
            long[] shape = new long[rank];
            boolean sharded = random.nextInt(3) == 0;
            long[] sub = new long[rank];
            long[] step = new long[rank];
            List<long[]> listed = new ArrayList<>();
            for (int i = 0; i < rank; i++) {
                shape[i] = random.nextInt(rank == 3 ? 7 : 13);
                sub[i] = 1 + random.nextInt(2);
                step[i] = sub[i] * (1 + random.nextInt(3));
                if (random.nextInt(4) == 0) {
                    listed.add(null); // this dimension repeats step[i]
                    continue;
                }
                List<Long> lengths = new ArrayList<>();
                long sum = 0;
                long target = shape[i] + (random.nextInt(3) == 0 ? random.nextInt(6) : 0);
                do {
                    long length = sub[i] * (1 + random.nextInt(3));
                    lengths.add(length);
                    sum += length;
                } while (sum < target);
                listed.add(lengths.stream().mapToLong(Long::longValue).toArray());
            }
            int fill = random.nextInt(5) - 2;
            ArraySpec.Builder b = ArraySpec.builder(shape, DataType.INT32).chunkShape(step).fillValue(fill);
            boolean anyListed = false;
            for (int i = 0; i < rank; i++) {
                if (listed.get(i) != null) {
                    b.chunkLengths(i, listed.get(i));
                    anyListed = true;
                }
            }
            if (!anyListed) {
                b.chunkLengths(0, step[0] * 100); // one chunk covering dimension 0
            }
            switch (random.nextInt(3)) {
                case 1 -> b.zstd();
                case 2 -> b.gzip(1).crc32c();
                default -> {
                }
            }
            if (sharded) {
                b.sharding(sub);
            }
            MemoryStore store = new MemoryStore();
            ZarrArray created = Zarr.createArray(store, b.build());
            assertTrue(created.isRectilinear());
            ZarrArray a = switch (random.nextInt(3)) {
                case 1 -> created.withChunkCache(1 << 20);
                case 2 -> created.withWriteEmptyChunks(true);
                default -> created;
            };
            int size = (int) a.size();
            int[] model = new int[size];
            Arrays.fill(model, fill);
            String context = "trial " + trial + ": " + a + " " + document(store, "zarr.json");
            for (int op = 0; op < 8; op++) {
                long[] offset = new long[rank];
                long[] extent = new long[rank];
                for (int i = 0; i < rank; i++) {
                    offset[i] = shape[i] == 0 ? 0 : random.nextInt((int) shape[i]);
                    extent[i] = random.nextInt((int) (shape[i] - offset[i]) + 1);
                }
                int n = (int) Arrays.stream(extent).reduce(1, (x, y) -> x * y);
                int[] values = new int[n];
                boolean allFill = random.nextInt(4) == 0;
                for (int k = 0; k < n; k++) {
                    values[k] = allFill ? fill : random.nextInt(1000);
                }
                a.select(offset, extent).writeInts(values);
                forEachInBox(shape, offset, extent, (k, flat) -> model[flat] = values[k]);

                for (int i = 0; i < rank; i++) {
                    offset[i] = shape[i] == 0 ? 0 : random.nextInt((int) shape[i]);
                    extent[i] = random.nextInt((int) (shape[i] - offset[i]) + 1);
                }
                int[] want = new int[(int) Arrays.stream(extent).reduce(1, (x, y) -> x * y)];
                forEachInBox(shape, offset, extent, (k, flat) -> want[k] = model[flat]);
                assertArrayEquals(want, a.select(offset, extent).readInts(), context);
            }
            assertArrayEquals(model, a.readInts(), context);
            assertArrayEquals(model, Zarr.openArray(store).readInts(), context);
            // blocks() tiles the array by its chunks, which differ in shape.
            List<Selection> blocks = a.blocks().toList();
            assertEquals(a.chunkCount(), blocks.size(), context);
            long covered = 0;
            for (Selection s : blocks) {
                int[] want = new int[(int) s.elementCount()];
                forEachInBox(shape, s.offset(), s.shape(), (k, flat) -> want[k] = model[flat]);
                assertArrayEquals(want, s.readInts(), context);
                covered += s.elementCount();
            }
            assertEquals(a.size(), covered, context);
        }
    }

    interface BoxVisitor {
        void visit(int k, int flat);
    }

    /** Visits each element of the box {@code [offset, offset + extent)} of an array of {@code shape}, in C order. */
    static void forEachInBox(long[] shape, long[] offset, long[] extent, BoxVisitor visitor) {
        int rank = shape.length;
        for (long e : extent) {
            if (e == 0) {
                return;
            }
        }
        long[] pos = new long[rank];
        int k = 0;
        while (true) {
            int flat = 0;
            for (int i = 0; i < rank; i++) {
                flat = flat * (int) shape[i] + (int) (offset[i] + pos[i]);
            }
            visitor.visit(k++, flat);
            int d = rank - 1;
            for (; d >= 0; d--) {
                if (++pos[d] < extent[d]) {
                    break;
                }
                pos[d] = 0;
            }
            if (d < 0) {
                return;
            }
        }
    }

    @Test
    void stringsAndByteStringsFollowTheGrid() {
        for (boolean sharded : new boolean[] {false, true}) {
            ArraySpec.Builder b = ArraySpec.builder(new long[] {7, 5}, DataType.STRING)
                    .chunkLengths(0, 2, 4, 2).chunkLengths(1, 4, 2).fillValue(new JsonString("-"));
            if (sharded) {
                b.sharding(2, 2).zstd();
            }
            ZarrArray a = Zarr.createArray(new MemoryStore(), b.build());
            String[] values = new String[35];
            for (int i = 0; i < 35; i++) {
                values[i] = i % 4 == 0 ? "-" : "s" + i;
            }
            a.writeStrings(values);
            assertArrayEquals(values, a.readStrings());
            String[] part = a.select(new long[] {2, 3}, new long[] {2, 2}).readStrings();
            assertArrayEquals(new String[] {"s13", "s14", "s18", "s19"}, part);

            ZarrArray bytes = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {9}, DataType.BYTES)
                    .chunkLengths(0, 1, 5, 4).build());
            byte[][] raw = new byte[9][];
            for (int i = 0; i < 9; i++) {
                raw[i] = new byte[i];
                Arrays.fill(raw[i], (byte) i);
            }
            bytes.writeByteArrays(raw);
            assertArrayEquals(raw, bytes.readByteArrays());
        }
    }

    @Test
    void writeEmptyChunksStoresEveryChunk() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, tenBySeven().build()).withWriteEmptyChunks(true);
        a.writeInts(new int[70]);
        assertEquals(6, store.listPrefix("c/").size());
        ZarrArray plain = a.withWriteEmptyChunks(false);
        plain.select(new long[] {0, 0}, new long[] {6, 7}).writeInts(new int[42]);
        assertEquals(2, store.listPrefix("c/").size()); // the four chunks of rows 0-5 hold only fill: deleted
    }

    @Test
    void theChunkCacheKeepsEachChunkAtItsShape() {
        MemoryStore store = new MemoryStore();
        Zarr.createArray(store, tenBySeven().zstd().build()).writeInts(sequence(70));
        RecordingStore recording = new RecordingStore();
        for (String key : store.list()) {
            recording.delegate.set(key, store.get(key).orElseThrow());
        }
        ZarrArray cached = Zarr.openArray(recording).withChunkCache(1 << 20);
        recording.calls.clear();
        assertArrayEquals(sequence(70), cached.readInts());
        long fetches = recording.calls.size();
        assertArrayEquals(sequence(70), cached.readInts());
        assertEquals(fetches, recording.calls.size()); // every chunk came from the cache
        assertArrayEquals(new int[] {68, 69}, cached.select(new long[] {9, 5}, new long[] {1, 2}).readInts());
    }

    // ---- shards ------------------------------------------------------------------------------------------

    /** zarr-python's rectilinear shards: the shard grid is rectilinear, the sub-chunks inside regular. */
    @Test
    void shardsOnARectilinearGridReadOnlyTheSubChunksTheyNeed() {
        MemoryStore source = new MemoryStore();
        ZarrArray created = Zarr.createArray(source, ArraySpec.builder(new long[] {12, 8}, DataType.FLOAT32)
                .chunkLengths(0, 4, 8).chunkLengths(1, 8).sharding(2, 4).build());
        double[] values = new double[96];
        for (int i = 0; i < 96; i++) {
            values[i] = i * 0.5;
        }
        created.writeDoubles(values);
        assertArrayEquals(new long[] {2, 4}, created.innerChunkShape());
        assertArrayEquals(new long[][] {{4, 8}, {8}}, created.chunkSizes());

        RecordingStore store = new RecordingStore();
        for (String key : source.list()) {
            store.delegate.set(key, source.get(key).orElseThrow());
        }
        ZarrArray a = Zarr.openArray(store);
        store.calls.clear();
        assertArrayEquals(new double[] {9 * 8 * 0.5 + 2.5}, a.select(new long[] {9, 5}, new long[] {1, 1}).readDoubles());
        // The second shard (rows 4-11): its index of 4 x 2 entries + crc32c, then one 2 x 4 float32 sub-chunk.
        assertEquals(List.of("suffix c/1/0 132", "range c/1/0 32"), store.calls);
        // blocks(innerChunkShape()) goes sub-chunk by sub-chunk across shards of two shapes.
        assertEquals(12, a.blocks(a.innerChunkShape()).count());
        assertEquals(2, a.blocks().count());
    }

    /** A pipeline is built for each chunk shape it meets: a shape the codecs cannot take fails only there. */
    @Test
    void eachChunkShapeGetsItsOwnPipeline() {
        MemoryStore store = new MemoryStore();
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[9],\"data_type\":\"uint8\","
                + "\"chunk_grid\":{\"name\":\"rectilinear\",\"configuration\":{\"kind\":\"inline\","
                + "\"chunk_shapes\":[[4,5]]}},\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,"
                + "\"codecs\":[" + NestedShardingTest.sharding("[2]", "[{\"name\":\"bytes\"}]",
                "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}},{\"name\":\"crc32c\"}]")
                + "],\"attributes\":{}}";
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
        ZarrArray a = Zarr.openArray(store); // opening builds no pipeline
        a.select(new long[] {0}, new long[] {4}).writeInts(new int[] {1, 2, 3, 4});
        assertArrayEquals(new int[] {1, 2, 3, 4}, a.select(new long[] {0}, new long[] {4}).readInts());
        // The second chunk, 5 long, does not divide into sub-chunks of 2.
        ZarrFormatException e = assertThrows(ZarrFormatException.class,
                () -> a.select(new long[] {3}, new long[] {2}).readInts());
        assertTrue(e.getMessage().contains("sharding chunk_shape"), e.getMessage());
        assertThrows(ZarrFormatException.class, () -> a.select(new long[] {5}, new long[] {1}).writeInts(new int[1]));
        assertEquals(List.of("c/0", "zarr.json"), store.list());
    }

    // ---- what the API says about the grid -----------------------------------------------------------

    @Test
    void chunkShapeIsUndefinedOnARectilinearGrid() {
        ZarrArray a = Zarr.createArray(new MemoryStore(), tenBySeven().build());
        assertTrue(a.isRectilinear());
        assertThrows(UnsupportedOperationException.class, a::chunkShape);
        assertThrows(UnsupportedOperationException.class, a::innerChunkShape); // not sharded: zarr-python's chunks
        assertArrayEquals(new long[][] {{3, 3, 4}, {2, 5}}, a.chunkSizes());
        assertArrayEquals(new long[] {3, 2}, a.gridShape());
        assertEquals(6, a.chunkCount());
        assertEquals("c/2/1", a.chunkKey(2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> a.chunkKey(3, 0));
        assertTrue(a.toString().contains("chunks=rectilinear"), a.toString());
        List<long[]> shapes = a.blocks().map(Selection::shape).toList();
        assertEquals(List.of("[3, 2]", "[3, 5]", "[3, 2]", "[3, 5]", "[4, 2]", "[4, 5]"),
                shapes.stream().map(Arrays::toString).toList());

        ZarrArray regular = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {10}, DataType.INT8)
                .chunkShape(4).build());
        assertFalse(regular.isRectilinear());
        assertArrayEquals(new long[][] {{4, 4, 2}}, regular.chunkSizes());
    }

    @Test
    void theBuilderChecksTheGrid() {
        assertThrows(IndexOutOfBoundsException.class,
                () -> ArraySpec.builder(new long[] {4}, DataType.INT8).chunkLengths(1, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {4}, DataType.INT8).chunkLengths(0));
        assertThrows(IllegalArgumentException.class, // short of the extent
                () -> ArraySpec.builder(new long[] {10}, DataType.INT8).chunkLengths(0, 4, 4).build());
        assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {10}, DataType.INT8).chunkLengths(0, 4, 0, 6).build());
        // A shard length that is not a multiple of the sub-chunk length, wherever it is in the list.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ArraySpec.builder(new long[] {12, 4}, DataType.INT8).chunkLengths(0, 4, 4, 2, 6)
                        .chunkShape(4, 4).sharding(4, 2).build());
        assertTrue(e.getMessage().contains("invalid array spec"), e.getMessage());
        // Lengths may run past the extent (zarr-python creates only an exact fit, but reads this).
        ArraySpec spec = ArraySpec.builder(new long[] {10}, DataType.INT8).chunkLengths(0, 4, 4, 4).build();
        assertEquals("[[[4,3]]]", spec.toJson().get("chunk_grid").asObject().get("configuration").asObject()
                .get("chunk_shapes").toJson());
        // A dimension without listed lengths repeats its chunkShape entry, or covers the array in one chunk.
        assertEquals("[[1,2],3,9]", ArraySpec.builder(new long[] {3, 7, 4}, DataType.INT8).chunkShape(2, 3, 9)
                .chunkLengths(0, 1, 2).build().toJson().get("chunk_grid").asObject().get("configuration").asObject()
                .get("chunk_shapes").toJson());
        assertEquals("[[1,2],7]", ArraySpec.builder(new long[] {3, 7}, DataType.INT8).chunkLengths(0, 1, 2).build()
                .toJson().get("chunk_grid").asObject().get("configuration").asObject().get("chunk_shapes").toJson());
    }

    // ---- resizing -------------------------------------------------------------------------------------

    /**
     * zarr-python's update_shape: growing past the listed lengths adds one chunk covering the rest, and
     * shrinking keeps them all. Falcon's resize semantics hold as on a regular grid (F6): shrinking deletes the
     * chunks wholly outside, and growing clears what a shrink cut off, where zarr-python brings it back.
     */
    @Test
    void resizingFollowsZarrPythonsGrid() {
        MemoryStore store = new MemoryStore();
        ZarrArray a = Zarr.createArray(store, tenBySeven().fillValue(-1).build());
        a.writeInts(sequence(70));

        ZarrArray grown = a.resize(12, 7);
        assertEquals("[[[3,2],4,2],[2,5]]", chunkShapes(store, "zarr.json"));
        assertArrayEquals(new long[][] {{3, 3, 4, 2}, {2, 5}}, grown.chunkSizes());
        int[] rows = grown.select(new long[] {8, 0}, new long[] {4, 1}).readInts();
        assertArrayEquals(new int[] {56, 63, -1, -1}, rows);

        String grid = chunkShapes(store, "zarr.json");
        ZarrArray shrunk = grown.resize(4, 7);
        assertEquals(grid, chunkShapes(store, "zarr.json")); // the lengths stay
        assertArrayEquals(new long[][] {{3, 1}, {2, 5}}, shrunk.chunkSizes());
        assertEquals(List.of("c/0/0", "c/0/1", "c/1/0", "c/1/1"), store.listPrefix("c/"));

        ZarrArray regrown = shrunk.resize(9, 7);
        assertEquals(grid, chunkShapes(store, "zarr.json"));
        // Rows 4 and 5 were cut off in chunk 1: they read as fill (zarr-python's resize shows 28 and 35 again).
        assertArrayEquals(new int[] {0, 7, 14, 21, -1, -1, -1, -1, -1},
                regrown.select(new long[] {0, 0}, new long[] {9, 1}).readInts());
    }

    /** A grid that keeps its lengths keeps its chunk_grid as stored, whatever form it is written in. */
    @Test
    void resizingLeavesAnUnchangedGridAsStored() {
        MemoryStore store = new MemoryStore();
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[12],\"data_type\":\"uint8\","
                + "\"chunk_grid\":{\"name\":\"rectilinear\",\"configuration\":{\"kind\":\"inline\","
                + "\"chunk_shapes\":[[4,4,4]]}},\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,"
                + "\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}";
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
        Zarr.openArray(store).resize(10);
        assertEquals("[[4,4,4]]", chunkShapes(store, "zarr.json"));
        Zarr.openArray(store).resize(13);
        assertEquals("[[[4,3],1]]", chunkShapes(store, "zarr.json")); // grown: rewritten, as zarr-python does
    }

    // ---- metadata ----------------------------------------------------------------------------------------

    private static String array(String shape, String chunkGrid) {
        return "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":" + shape + ",\"data_type\":\"uint8\","
                + "\"chunk_grid\":" + chunkGrid + ",\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,"
                + "\"codecs\":[{\"name\":\"bytes\"}],\"attributes\":{}}";
    }

    private static ZarrArray open(String json) {
        MemoryStore store = new MemoryStore();
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
        return Zarr.openArray(store);
    }

    private static String rectilinear(String chunkShapes) {
        return "{\"name\":\"rectilinear\",\"configuration\":{\"kind\":\"inline\",\"chunk_shapes\":" + chunkShapes + "}}";
    }

    @Test
    void malformedGridsAreRefused() {
        Function<String, Class<? extends Throwable>> refusal = grid -> assertThrows(ZarrException.class,
                () -> open(array("[6,6]", grid))).getClass();
        assertEquals(ZarrFormatException.class, refusal.apply(
                "{\"name\":\"rectilinear\",\"configuration\":{\"chunk_shapes\":[3,3]}}")); // no kind
        assertEquals(ZarrUnsupportedException.class, refusal.apply(
                "{\"name\":\"rectilinear\",\"configuration\":{\"kind\":\"file\",\"chunk_shapes\":[3,3]}}"));
        assertEquals(ZarrFormatException.class, refusal.apply(
                "{\"name\":\"rectilinear\",\"configuration\":{\"kind\":\"inline\"}}")); // no chunk_shapes
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[3]"))); // rank
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[],3]")));
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[0,3]")));
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[3,-3],3]")));
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[[3,2,1]],3]")));
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[[3,0]],3]")));
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[\"3\"],3]")));
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[2.5,4],3]")));
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[2,3],3]"))); // short of 6
        assertEquals(ZarrFormatException.class, refusal.apply(rectilinear("[[[4611686018427387904,2],1],3]")));
        assertEquals(ZarrUnsupportedException.class, refusal.apply("{\"name\":\"variable\"}"));
        // must_understand false does not let a chunk grid be ignored (the v3 specification forbids it).
        assertEquals(ZarrUnsupportedException.class,
                refusal.apply("{\"name\":\"variable\",\"must_understand\":false}"));
    }

    /** Runs are kept as runs: a count of 10^18 opens at once, and only the chunks over the array count. */
    @Test
    void aHugeRunCountIsNotExpanded() {
        ZarrArray a = open(array("[5,4]", rectilinear("[[[1,1000000000000000000]],2]")));
        assertArrayEquals(new long[][] {{1, 1, 1, 1, 1}, {2, 2}}, a.chunkSizes());
        a.writeInts(sequence(20));
        assertArrayEquals(sequence(20), a.readInts());
    }

    /** A consolidated group's snapshot carries the grid as stored. */
    @Test
    void consolidatedMetadataCarriesTheGrid() {
        MemoryStore store = new MemoryStore();
        ZarrGroup root = Zarr.createGroup(store);
        root.createArray("r", tenBySeven().build()).writeInts(sequence(70));
        root.consolidate();
        JsonObject snapshot = Json.parse(store.get("zarr.json").orElseThrow()).asObject()
                .get("consolidated_metadata").asObject().get("metadata").asObject().get("r").asObject();
        assertEquals("rectilinear", snapshot.get("chunk_grid").asObject().get("name").asString());
        RecordingStore recording = new RecordingStore();
        for (String key : store.list()) {
            recording.delegate.set(key, store.get(key).orElseThrow());
        }
        ZarrGroup opened = Zarr.openGroup(recording);
        assertTrue(opened.isConsolidated());
        ZarrArray r = opened.array("r");
        assertArrayEquals(new long[][] {{3, 3, 4}, {2, 5}}, r.chunkSizes());
        assertArrayEquals(sequence(70), r.readInts());
        assertFalse(recording.calls.contains("get r/zarr.json"), recording.calls.toString());
    }
}
