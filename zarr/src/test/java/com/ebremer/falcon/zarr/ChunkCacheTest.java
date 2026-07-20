package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.MemoryStore;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The decoded-chunk cache must speed re-reads without changing results, and stay correct across writes. */
class ChunkCacheTest {

    /** Counts how many times each key is fetched, to prove a cached chunk is not re-read. */
    static final class CountingStore implements Store {
        private final MemoryStore delegate = new MemoryStore();
        final List<String> gets = new ArrayList<>();

        @Override
        public Optional<byte[]> get(String key) {
            gets.add(key);
            return delegate.get(key);
        }

        @Override
        public Optional<byte[]> getRange(String key, long offset, long length) {
            gets.add(key);
            return delegate.getRange(key, offset, length);
        }

        @Override
        public OptionalLong size(String key) {
            return delegate.size(key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
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
            return delegate.isWritable();
        }

        @Override
        public void set(String key, byte[] value) {
            delegate.set(key, value);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }

        long countOf(String key) {
            return gets.stream().filter(key::equals).count();
        }
    }

    @Test
    void repeatedSelectionsDecodeEachChunkOnce() {
        CountingStore store = new CountingStore();
        ZarrArray array = Zarr.createArray(store, ArraySpec.builder(new long[] {12}, DataType.INT32)
                .chunkShape(4).gzip(5).build());
        array.writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});

        // reopen so nothing is warm, then read overlapping selections that all hit chunk 1 (indices 4..7)
        ZarrArray reopened = Zarr.openArray(store);
        store.gets.clear();
        assertArrayEquals(new int[] {4, 5}, reopened.select(new long[] {4}, new long[] {2}).readInts());
        assertArrayEquals(new int[] {6, 7}, reopened.select(new long[] {6}, new long[] {2}).readInts());
        assertArrayEquals(new int[] {5, 6}, reopened.select(new long[] {5}, new long[] {2}).readInts());

        assertEquals(1, store.countOf("c/1"), "chunk c/1 should be fetched once and then cached");
    }

    @Test
    void cacheIsInvalidatedOnWrite() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = Zarr.createArray(store,
                ArraySpec.builder(new long[] {8}, DataType.INT32).chunkShape(4).build());
        array.writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7});

        assertArrayEquals(new int[] {0, 1, 2, 3}, array.select(new long[] {0}, new long[] {4}).readInts());
        // overwrite chunk 0 through the same array (which holds the cache)
        array.select(new long[] {0}, new long[] {4}).writeInts(new int[] {10, 20, 30, 40});
        assertArrayEquals(new int[] {10, 20, 30, 40, 4, 5, 6, 7}, array.readInts());
    }

    @Test
    void clearingTheCacheKeepsResultsCorrect() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = Zarr.createArray(store,
                ArraySpec.builder(new long[] {6}, DataType.INT32).chunkShape(3).gzip(3).build());
        array.writeInts(new int[] {1, 2, 3, 4, 5, 6});
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, array.readInts());
        array.clearChunkCache();
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, array.readInts());
    }

    @Test
    void shardedReadsStayCorrectWithCaching() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = Zarr.createArray(store, ArraySpec.builder(new long[] {16}, DataType.INT32)
                .chunkShape(8).sharding(4).build());
        int[] data = new int[16];
        for (int i = 0; i < 16; i++) {
            data[i] = i;
        }
        array.writeInts(data);

        ZarrArray reopened = Zarr.openArray(store);
        // a partial shard region, then the whole thing, then another partial -- all must agree
        assertArrayEquals(new int[] {2, 3, 4, 5}, reopened.select(new long[] {2}, new long[] {4}).readInts());
        assertArrayEquals(data, reopened.readInts());
        assertArrayEquals(new int[] {9, 10, 11}, reopened.select(new long[] {9}, new long[] {3}).readInts());
    }

    @Test
    void blocksStreamCoversTheWholeArray() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = Zarr.createArray(store,
                ArraySpec.builder(new long[] {10}, DataType.INT32).chunkShape(4).build());
        int[] data = new int[10];
        for (int i = 0; i < 10; i++) {
            data[i] = i * i;
        }
        array.writeInts(data);

        ZarrArray reopened = Zarr.openArray(store);
        List<Selection> blocks = reopened.blocks().toList();
        assertEquals(3, blocks.size()); // grid = ceil(10/4)

        // reassemble the array one block at a time, as a streaming reader would
        int[] gathered = new int[10];
        for (Selection block : blocks) {
            int[] values = block.readInts();
            int start = (int) block.offset()[0];
            System.arraycopy(values, 0, gathered, start, values.length);
        }
        assertArrayEquals(data, gathered);
        // the last block is the edge chunk: 2 valid elements, not 4
        assertEquals(2, blocks.get(2).elementCount());
    }

    @Test
    void blocksStreamForTwoDimensionalArray() {
        MemoryStore store = new MemoryStore();
        ZarrArray array = Zarr.createArray(store,
                ArraySpec.builder(new long[] {4, 6}, DataType.INT32).chunkShape(2, 3).build());
        int[] data = new int[24];
        for (int i = 0; i < 24; i++) {
            data[i] = i;
        }
        array.writeInts(data);

        long total = Zarr.openArray(store).blocks().mapToLong(Selection::elementCount).sum();
        assertEquals(24, total); // four 2x3 chunks tile the array exactly
    }
}
