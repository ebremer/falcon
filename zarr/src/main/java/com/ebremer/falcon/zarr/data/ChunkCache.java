package com.ebremer.falcon.zarr.data;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small least-recently-used cache of decoded chunks, keyed by chunk store key, bounded by total
 * decoded bytes. Overlapping or repeated selections then decompress each chunk once instead of on every
 * read.
 *
 * <p>Only whole, present chunks are cached (an absent chunk is cheap to regenerate as fill, and a
 * partially decoded shard region would be wrong to reuse), and the stored indexes of shards. A write through the array handle that owns
 * the cache invalidates the chunk's entry after the store has changed; a write through any other handle
 * or process is not seen. The cache is thread-safe: a read that started before an invalidation does not
 * cache what it read (see {@link #stamp()}).
 *
 * <p>This type is module-internal (the {@code data} package is not exported); an array handle made with
 * {@code ZarrArray.withChunkCache} owns one and threads it into its reads and writes.
 */
public final class ChunkCache {

    private final long maxBytes;
    private final LinkedHashMap<String, byte[]> entries;
    private long bytes;
    private long invalidations; // counts invalidate() and clear() calls; see stamp()

    /**
     * A cache bounded by {@code maxBytes} of decoded chunk data.
     *
     * @throws IllegalArgumentException if {@code maxBytes} is not positive
     */
    public ChunkCache(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("chunk cache size must be positive, was " + maxBytes);
        }
        this.maxBytes = maxBytes;
        this.entries = new LinkedHashMap<>(16, 0.75f, true); // access-order: eldest is least-recently-used
    }

    /** The cache's budget in bytes of decoded chunk data. */
    public long maxBytes() {
        return maxBytes;
    }

    /** The decoded chunk for {@code key}, or {@code null} if not cached. */
    synchronized byte[] get(String key) {
        return entries.get(key);
    }

    /**
     * A stamp to take before reading a chunk from the store and to pass to {@link #put}: the put is then
     * dropped if anything was invalidated in between, since what was read may predate that write.
     */
    synchronized long stamp() {
        return invalidations;
    }

    /**
     * Caches a decoded chunk read after {@code stamp} was taken, evicting least-recently-used entries to
     * stay within the budget.
     */
    synchronized void put(String key, byte[] chunk, long stamp) {
        if (stamp != invalidations || chunk.length > maxBytes) {
            return; // possibly stale, or a single chunk larger than the whole budget
        }
        byte[] previous = entries.put(key, chunk);
        if (previous != null) {
            bytes -= previous.length;
        }
        bytes += chunk.length;
        evict();
    }

    /** Drops {@code key} (and its shard index), after the chunk stored under it was written or deleted. */
    synchronized void invalidate(String key) {
        invalidations++;
        for (String k : new String[] {key, StoreChunkBytes.indexKey(key)}) {
            byte[] previous = entries.remove(k);
            if (previous != null) {
                bytes -= previous.length;
            }
        }
    }

    /** Empties the cache. */
    public synchronized void clear() {
        invalidations++;
        entries.clear();
        bytes = 0;
    }

    private void evict() {
        Iterator<Map.Entry<String, byte[]>> it = entries.entrySet().iterator();
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= it.next().getValue().length;
            it.remove();
        }
    }
}
