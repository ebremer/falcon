package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Blocks of any shape, and a sharded array's sub-chunks (P2 F10): {@code blocks()} yields whole shards,
 * which can be hundreds of MB; {@code blocks(innerChunkShape())} yields one sub-chunk at a time, and each
 * reads only that sub-chunk.
 */
class BlocksTest {

    private static int[] sequence(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        return out;
    }

    @Test
    void blocksTileTheArrayInCOrderWithEdgesCut() {
        ZarrArray a = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {7, 5}, DataType.INT32)
                .chunkShape(4, 4).build());
        a.writeInts(sequence(35));
        List<Selection> blocks = a.blocks(3, 2).toList();
        assertEquals(9, blocks.size());
        assertArrayEquals(new long[] {0, 0}, blocks.get(0).offset());
        assertArrayEquals(new long[] {0, 2}, blocks.get(1).offset());
        assertArrayEquals(new long[] {6, 4}, blocks.get(8).offset());
        assertArrayEquals(new long[] {1, 1}, blocks.get(8).shape()); // the corner, cut to the array
        assertArrayEquals(new long[] {3, 1}, blocks.get(2).shape());

        // Every element exactly once.
        int[] seen = new int[35];
        for (Selection s : blocks) {
            int[] values = s.readInts();
            for (int v : values) {
                seen[v]++;
            }
        }
        int[] once = new int[35];
        Arrays.fill(once, 1);
        assertArrayEquals(once, seen);

        assertEquals(a.blocks().toList().toString(), a.blocks(a.chunkShape()).toList().toString());
    }

    @Test
    void badBlockShapesAreRefused() {
        ZarrArray a = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {7, 5}, DataType.INT8).build());
        assertThrows(IllegalArgumentException.class, () -> a.blocks(3));
        assertThrows(IllegalArgumentException.class, () -> a.blocks(3, 0));
        assertThrows(IllegalArgumentException.class, () -> a.blocks(-1, 2));
        ZarrArray empty = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {0, 5}, DataType.INT8).build());
        assertEquals(0, empty.blocks(1, 1).count());
    }

    @Test
    void innerChunkShapeIsTheSubChunkShapeOfAShardedArray() {
        ZarrArray plain = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {8, 8}, DataType.INT32)
                .chunkShape(4, 4).build());
        assertArrayEquals(new long[] {4, 4}, plain.innerChunkShape());

        ZarrArray sharded = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {8, 8}, DataType.INT32)
                .chunkShape(8, 8).sharding(2, 4).gzip(1).build());
        assertArrayEquals(new long[] {8, 8}, sharded.chunkShape());
        assertArrayEquals(new long[] {2, 4}, sharded.innerChunkShape());

        // A transpose before the shard means each shard is decoded whole: no smaller unit to read.
        MemoryStore store = new MemoryStore();
        String json = "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[8,8],\"data_type\":\"int32\","
                + "\"chunk_grid\":{\"name\":\"regular\",\"configuration\":{\"chunk_shape\":[8,8]}},"
                + "\"chunk_key_encoding\":{\"name\":\"default\"},\"fill_value\":0,\"codecs\":["
                + "{\"name\":\"transpose\",\"configuration\":{\"order\":[1,0]}},"
                + "{\"name\":\"sharding_indexed\",\"configuration\":{\"chunk_shape\":[2,4],"
                + "\"codecs\":[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]}}]}";
        store.set("zarr.json", json.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(new long[] {8, 8}, Zarr.openArray(store).innerChunkShape());

        // Nested shards (F11): the outer shard's sub-chunks, as zarr-python 3.4 gives this array's chunks
        // (8, 6) and shards (16, 12).
        ZarrArray nested = Zarr.open(fixture("nested_2d")).asArray();
        assertArrayEquals(new long[] {16, 12}, nested.chunkShape());
        assertArrayEquals(new long[] {8, 6}, nested.innerChunkShape());
    }

    private static java.nio.file.Path fixture(String name) {
        try {
            return java.nio.file.Path.of(BlocksTest.class.getResource("/fixtures/" + name).toURI());
        } catch (java.net.URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name, e);
        }
    }

    @Test
    void eachSubChunkBlockReadsOneSubChunk() {
        PartialChunkIoTest.RecordingStore store = new PartialChunkIoTest.RecordingStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {16, 16}, DataType.INT32)
                .chunkShape(16, 16).sharding(4, 4).build());
        a.writeInts(sequence(256));
        int subChunkBytes = 4 * 4 * 4;

        // A plain handle: per block, the shard's index (one suffix read) and the sub-chunk (one range read).
        store.calls.clear();
        List<Selection> blocks = a.blocks(a.innerChunkShape()).toList();
        assertEquals(16, blocks.size());
        int[] first = blocks.get(5).readInts(); // rows 4..7, columns 4..7
        assertArrayEquals(new int[] {68, 69, 70, 71, 84, 85, 86, 87, 100, 101, 102, 103, 116, 117, 118, 119}, first);
        assertEquals(List.of("suffix c/0/0 260", "range c/0/0 " + subChunkBytes), store.calls);

        // A cached handle fetches the index once, then one range per sub-chunk: the whole shard, in pieces.
        ZarrArray cached = a.withChunkCache(1 << 20);
        store.calls.clear();
        int[] all = new int[256];
        for (Selection s : cached.blocks(cached.innerChunkShape()).toList()) {
            int[] values = s.readInts();
            long[] o = s.offset();
            for (int k = 0; k < values.length; k++) {
                all[(int) ((o[0] + k / 4) * 16 + o[1] + k % 4)] = values[k];
            }
        }
        assertArrayEquals(sequence(256), all);
        assertEquals(1, store.count("suffix "));
        assertEquals(16, store.count("range "));
        assertEquals(16L * subChunkBytes, store.bytesFetched());
    }
}
