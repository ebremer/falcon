package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.codec.ChunkBytes;
import com.ebremer.falcon.zarr.store.Store;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Byte-range access to the chunk stored under {@code key}. With a cache (a handle made with
 * {@code withChunkCache}), a shard's stored index is kept between reads, so reading many small regions of
 * one shard fetches its index once (PF1).
 */
record StoreChunkBytes(Store store, String key, ChunkCache cache) implements ChunkBytes {

    /** The cache key of a shard's stored index, beside its chunk's key. */
    static String indexKey(String key) {
        return key + "\u0000index";
    }

    @Override
    public OptionalLong size() {
        return store.size(key);
    }

    @Override
    public Optional<byte[]> readAll() {
        return store.get(key);
    }

    @Override
    public Optional<byte[]> readRange(long offset, long length) {
        return store.getRange(key, offset, length);
    }

    @Override
    public Optional<byte[]> readSuffix(long length) {
        return store.getSuffix(key, length); // one request where the store can (HttpStore: bytes=-N)
    }

    @Override
    public Optional<byte[]> readShardIndex(boolean atStart, long length) {
        if (cache == null) {
            return ChunkBytes.super.readShardIndex(atStart, length);
        }
        String indexKey = indexKey(key);
        byte[] hit = cache.get(indexKey);
        if (hit != null) {
            return Optional.of(hit);
        }
        long stamp = cache.stamp();
        Optional<byte[]> stored = ChunkBytes.super.readShardIndex(atStart, length);
        stored.ifPresent(bytes -> cache.put(indexKey, bytes, stamp));
        return stored;
    }
}
