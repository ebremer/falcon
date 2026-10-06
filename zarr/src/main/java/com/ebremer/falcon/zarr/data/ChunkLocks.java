package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.store.Store;

/**
 * Serializes writes to one chunk (C1). A write that covers part of a chunk, or part of a shard, reads the
 * stored chunk, updates it, and stores it again; two such writes at once would each store their own
 * update and lose the other's. Every chunk write through the same {@link Store} object in this JVM holds
 * the lock for its key, so they take turns. Writes through another store object or another process are
 * not covered.
 *
 * <p>Locks are striped: a fixed set of monitors, chosen by the store's identity and the key, so nothing
 * accumulates. Two keys that share a stripe merely take turns.
 */
final class ChunkLocks {

    private static final Object[] STRIPES = new Object[256];

    static {
        for (int i = 0; i < STRIPES.length; i++) {
            STRIPES[i] = new Object();
        }
    }

    private ChunkLocks() {
    }

    /** The monitor guarding writes to {@code key} in {@code store}. */
    static Object of(Store store, String key) {
        int h = System.identityHashCode(store) * 31 + key.hashCode();
        return STRIPES[(h ^ (h >>> 16)) & (STRIPES.length - 1)];
    }
}
