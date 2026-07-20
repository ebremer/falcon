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
 * partially decoded shard region would be wrong to reuse). Writes evict the affected key so a read after
 * a write sees fresh data. Not thread-safe, matching the rest of the read path.
 *
 * <p>This type is module-internal (the {@code data} package is not exported); an array owns one and
 * threads it into its reads and writes.
 */
public final class ChunkCache {

    /** Default budget: enough for a handful of typical chunks without holding much memory. */
    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;

    private final long maxBytes;
    private final LinkedHashMap<String, byte[]> entries;
    private long bytes;

    /** A cache with the {@linkplain #DEFAULT_MAX_BYTES default} budget. */
    public ChunkCache() {
        this(DEFAULT_MAX_BYTES);
    }

    /** A cache bounded by {@code maxBytes} of decoded chunk data. */
    public ChunkCache(long maxBytes) {
        this.maxBytes = maxBytes;
        this.entries = new LinkedHashMap<>(16, 0.75f, true); // access-order: eldest is least-recently-used
    }

    /** The decoded chunk for {@code key}, or {@code null} if not cached. */
    byte[] get(String key) {
        return entries.get(key);
    }

    /** Caches a decoded chunk, evicting least-recently-used entries to stay within the budget. */
    void put(String key, byte[] chunk) {
        if (chunk.length > maxBytes) {
            return; // a single chunk larger than the whole budget is not worth caching
        }
        byte[] previous = entries.put(key, chunk);
        if (previous != null) {
            bytes -= previous.length;
        }
        bytes += chunk.length;
        evict();
    }

    /** Removes {@code key} (after it is written or deleted). */
    void remove(String key) {
        byte[] previous = entries.remove(key);
        if (previous != null) {
            bytes -= previous.length;
        }
    }

    /** Empties the cache. */
    public void clear() {
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
