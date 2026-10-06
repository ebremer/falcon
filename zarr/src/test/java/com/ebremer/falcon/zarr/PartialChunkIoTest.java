package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * What a read or write of part of a chunk costs (P1 PF1, PF2, PF7): which store calls it makes and how
 * much it fetches.
 */
class PartialChunkIoTest {

    /** Records every store call as "op key [length]". */
    static final class RecordingStore implements Store {
        final MemoryStore delegate = new MemoryStore();
        final List<String> calls = new ArrayList<>();

        long count(String prefix) {
            return calls.stream().filter(c -> c.startsWith(prefix)).count();
        }

        long bytesFetched() {
            return calls.stream().filter(c -> c.startsWith("range ")).mapToLong(c -> Long.parseLong(c.substring(c.lastIndexOf(' ') + 1))).sum();
        }

        @Override
        public Optional<byte[]> get(String key) {
            calls.add("get " + key);
            return delegate.get(key);
        }

        @Override
        public Optional<byte[]> getRange(String key, long offset, long length) {
            Optional<byte[]> value = delegate.getRange(key, offset, length);
            calls.add("range " + key + " " + value.map(v -> v.length).orElse(0));
            return value;
        }

        @Override
        public Optional<byte[]> getSuffix(String key, long length) {
            Optional<byte[]> value = delegate.getSuffix(key, length);
            calls.add("suffix " + key + " " + value.map(v -> v.length).orElse(0));
            return value;
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public OptionalLong size(String key) {
            calls.add("size " + key);
            return delegate.size(key);
        }

        @Override
        public List<String> list() {
            return delegate.list();
        }

        @Override
        public List<String> listPrefix(String prefix) {
            return delegate.listPrefix(prefix);
        }

        @Override
        public List<String> listDir(String prefix) {
            return delegate.listDir(prefix);
        }

        @Override
        public boolean isWritable() {
            return true;
        }

        @Override
        public void set(String key, byte[] value) {
            calls.add("set " + key);
            delegate.set(key, value);
        }

        @Override
        public void delete(String key) {
            calls.add("delete " + key);
            delegate.delete(key);
        }
    }

    private static int[] sequence(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        return out;
    }

    /** One 64 x 64 int32 shard of 16 x 16 sub-chunks (16 KiB decoded), index at the start. */
    private static ZarrArray shardedArray(Store store) {
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {64, 64}, DataType.INT32)
                .chunkShape(64, 64).sharding(16, 16).shardIndexAtStart().build());
        a.writeInts(sequence(64 * 64));
        return Zarr.openArray(store);
    }

    @Test
    void aPartialShardReadFetchesTheIndexAndOneSubChunk() {
        RecordingStore store = new RecordingStore();
        ZarrArray a = shardedArray(store);
        store.calls.clear();

        int[] tile = a.select(new long[] {20, 20}, new long[] {8, 8}).readInts();
        assertEquals(20 * 64 + 20, tile[0]);
        // PF1: no size() query, and only the 16-entry index plus one 1 KiB sub-chunk are fetched.
        assertEquals(0, store.count("size "), store.calls.toString());
        assertEquals(List.of("range c/0/0 260", "range c/0/0 1024"), store.calls);
    }

    @Test
    void anIndexAtTheEndIsReadAsASuffix() {
        RecordingStore store = new RecordingStore();
        Zarr.createArray(store, ArraySpec.builder(new long[] {64}, DataType.INT32).chunkShape(64).sharding(16).build())
                .writeInts(sequence(64));
        ZarrArray a = Zarr.openArray(store);
        store.calls.clear();
        assertArrayEquals(new int[] {40, 41}, a.select(new long[] {40}, new long[] {2}).readInts());
        // The index is read with Store.getSuffix: one call where the store supports it.
        assertEquals(List.of("suffix c/0 68", "range c/0 64"), store.calls);
    }

    @Test
    void aCachedHandleFetchesAShardIndexOnce() {
        RecordingStore store = new RecordingStore();
        ZarrArray a = shardedArray(store).withChunkCache(1 << 20);
        store.calls.clear();
        for (int i = 0; i < 4; i++) {
            a.select(new long[] {16 * i, 0}, new long[] {4, 4}).readInts();
        }
        assertEquals(1, store.calls.stream().filter(c -> c.equals("range c/0/0 260")).count(), store.calls.toString());

        // A write through the handle drops the cached index along with the chunk.
        a.select(new long[] {0, 0}, new long[] {1, 1}).writeInts(new int[] {-5});
        store.calls.clear();
        assertArrayEquals(new int[] {-5}, a.select(new long[] {0, 0}, new long[] {1, 1}).readInts());
        assertEquals(1, store.calls.stream().filter(c -> c.equals("range c/0/0 260")).count(), store.calls.toString());
    }

    @Test
    void aPartialShardWriteKeepsTheBytesOfUntouchedSubChunks() {
        RecordingStore store = new RecordingStore();
        Zarr.createArray(store, ArraySpec.builder(new long[] {64, 64}, DataType.INT32)
                .chunkShape(64, 64).sharding(16, 16).shardIndexAtStart().gzip(9).build()).writeInts(sequence(64 * 64));
        // Re-encode at level 1 from now on: a sub-chunk encoded again would come out different.
        String json = new String(store.delegate.get("zarr.json").orElseThrow(), java.nio.charset.StandardCharsets.UTF_8);
        store.delegate.set("zarr.json", json.replace("\"level\":9", "\"level\":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ZarrArray a = Zarr.openArray(store);
        byte[] before = store.delegate.get("c/0/0").orElseThrow();

        a.select(new long[] {17, 33}, new long[] {2, 2}).writeInts(new int[] {-1, -2, -3, -4});
        byte[] after = store.delegate.get("c/0/0").orElseThrow();

        // PF2: every sub-chunk but the one written to keeps its stored bytes, at the same place.
        ByteBuffer oldIndex = ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer newIndex = ByteBuffer.wrap(after).order(ByteOrder.LITTLE_ENDIAN);
        int written = 1 * 4 + 2; // sub-chunk row 1, column 2
        for (int i = 0; i < 16; i++) {
            long oldOffset = oldIndex.getLong(16 * i);
            long newOffset = newIndex.getLong(16 * i);
            long length = oldIndex.getLong(16 * i + 8);
            if (i != written) {
                assertEquals(length, newIndex.getLong(16 * i + 8));
                assertArrayEquals(java.util.Arrays.copyOfRange(before, (int) oldOffset, (int) (oldOffset + length)),
                        java.util.Arrays.copyOfRange(after, (int) newOffset, (int) (newOffset + length)), "sub-chunk " + i);
            }
        }
        int[] expected = sequence(64 * 64);
        expected[17 * 64 + 33] = -1;
        expected[17 * 64 + 34] = -2;
        expected[18 * 64 + 33] = -3;
        expected[18 * 64 + 34] = -4;
        assertArrayEquals(expected, Zarr.openArray(store).readInts());
    }

    @Test
    void clearingEveryWrittenSubChunkDeletesTheShard() {
        RecordingStore store = new RecordingStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {8}, DataType.INT32)
                .chunkShape(8).sharding(4).build());
        a.select(new long[] {5}, new long[] {1}).writeInts(new int[] {9});
        assertTrue(store.delegate.exists("c/0"));
        a.select(new long[] {5}, new long[] {1}).writeInts(new int[] {0});
        assertEquals(List.of("zarr.json"), store.delegate.list());
    }

    @Test
    void writingAWholeArrayDoesNotReadItsEdgeChunksBack() {
        RecordingStore store = new RecordingStore();
        ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {10, 10}, DataType.INT32)
                .chunkShape(4, 4).gzip(1).build());
        a.writeInts(sequence(100));
        store.calls.clear();
        a.writeInts(sequence(100));
        // PF7: every chunk, edge chunks included, is covered as far as the array reaches: nothing to read.
        assertEquals(0, store.count("get ") + store.count("range "), store.calls.toString());
        assertEquals(9, store.count("set "));
        assertArrayEquals(sequence(100), Zarr.openArray(store).readInts());
    }
}
