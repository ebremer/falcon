package com.ebremer.falcon.hdf5.io;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small least-recently-used cache of <em>decoded</em> chunk bytes, keyed by the chunk's file address
 * and bounded by total cached bytes. It lets repeated or streaming reads that revisit the same chunk
 * (e.g. adjacent blocks sharing a boundary chunk) reuse the filter-decode result instead of decoding it
 * again. Cached arrays are treated as read-only.
 *
 * <p>Thread-safe: an open file may be read from several threads at once, and every lookup reorders the
 * access-ordered map, so access is synchronized (the critical sections are tiny next to a filter decode).
 */
public final class ChunkCache {

    private static final long MAX_BYTES = 16L << 20; // hold at most ~16 MB of decoded chunks

    private final LinkedHashMap<Long, byte[]> entries = new LinkedHashMap<>(16, 0.75f, true); // access-order
    private long cachedBytes;

    /** The decoded bytes cached for the chunk at {@code address}, or {@code null} if not cached. */
    public synchronized byte[] get(long address) {
        return entries.get(address);
    }

    /** Caches the decoded bytes for the chunk at {@code address}, evicting least-recently-used entries. */
    public synchronized void put(long address, byte[] decoded) {
        if (decoded.length > MAX_BYTES) {
            return; // a single chunk larger than the whole budget is not worth caching
        }
        byte[] previous = entries.put(address, decoded);
        cachedBytes += decoded.length - (previous == null ? 0 : previous.length);
        Iterator<Map.Entry<Long, byte[]>> it = entries.entrySet().iterator();
        while (cachedBytes > MAX_BYTES && it.hasNext()) {
            Map.Entry<Long, byte[]> eldest = it.next(); // access-order => least-recently-used first
            if (eldest.getKey() == address) {
                continue; // never evict the entry we just inserted
            }
            cachedBytes -= eldest.getValue().length;
            it.remove();
        }
    }
}
