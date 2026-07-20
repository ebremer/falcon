package com.ebremer.falcon.zarr.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An in-memory {@link Store} backed by a map from key to bytes. Always writable. Values are copied in
 * and out, so a stored array is immutable to callers.
 *
 * <p>Handy for tests and for building a store in memory before serializing it elsewhere. Not
 * thread-safe.
 */
public final class MemoryStore implements Store {

    private final Map<String, byte[]> data = new HashMap<>();

    @Override
    public Optional<byte[]> get(String key) {
        StoreKeys.validate(key);
        byte[] value = data.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    @Override
    public Optional<byte[]> getRange(String key, long offset, long length) {
        StoreKeys.validate(key);
        int len = checkedLength(offset, length);
        byte[] value = data.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (offset >= value.length) {
            return Optional.of(new byte[0]);
        }
        int from = (int) offset;
        int to = (int) Math.min((long) from + len, value.length);
        byte[] slice = new byte[to - from];
        System.arraycopy(value, from, slice, 0, slice.length);
        return Optional.of(slice);
    }

    @Override
    public boolean exists(String key) {
        StoreKeys.validate(key);
        return data.containsKey(key);
    }

    @Override
    public OptionalLong size(String key) {
        StoreKeys.validate(key);
        byte[] value = data.get(key);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value.length);
    }

    @Override
    public List<String> list() {
        return listPrefix("");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        return StoreKeys.listPrefix(data.keySet(), prefix);
    }

    @Override
    public List<String> listDir(String prefix) {
        return StoreKeys.listDir(data.keySet(), prefix);
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    @Override
    public void set(String key, byte[] value) {
        StoreKeys.validate(key);
        data.put(key, value.clone());
    }

    @Override
    public void delete(String key) {
        StoreKeys.validate(key);
        data.remove(key);
    }

    /** Validates the range arguments and returns the length as an int. */
    static int checkedLength(long offset, long length) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative: " + length);
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("length exceeds Integer.MAX_VALUE: " + length);
        }
        return (int) length;
    }
}
