package com.ebremer.falcon.zarr.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A read that races a write must not cache what it read (P0 Z3). The writer stores the chunk and then
 * invalidates it; a reader that fetched the old bytes before the store changed would otherwise put them
 * back in the cache after the invalidation, and they would be served until evicted.
 */
class ChunkCacheStampTest {

    @Test
    void aPutStartedBeforeAnInvalidationIsDropped() {
        ChunkCache cache = new ChunkCache(1024);
        long stamp = cache.stamp();          // reader: about to fetch c/0
        cache.invalidate("c/0");             // writer: stored c/0, now invalidates it
        cache.put("c/0", new byte[] {1}, stamp); // reader: caches what it fetched, possibly the old bytes
        assertNull(cache.get("c/0"));

        long fresh = cache.stamp();
        cache.put("c/0", new byte[] {2}, fresh);
        assertArrayEquals(new byte[] {2}, cache.get("c/0"));
    }

    @Test
    void clearingAlsoDropsPutsInFlight() {
        ChunkCache cache = new ChunkCache(1024);
        long stamp = cache.stamp();
        cache.clear();
        cache.put("c/0", new byte[] {1}, stamp);
        assertNull(cache.get("c/0"));
    }

    @Test
    void aChunkLargerThanTheBudgetIsNotCached() {
        ChunkCache cache = new ChunkCache(4);
        cache.put("c/0", new byte[5], cache.stamp());
        assertNull(cache.get("c/0"));
    }
}
