package com.ebremer.falcon.zarr.store;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A Zarr store: a map from string keys to byte sequences, with listing and partial (byte-range) reads.
 *
 * <p>Keys are {@code '/'}-separated paths such as {@code "zarr.json"}, {@code "group/zarr.json"}, or a
 * chunk key like {@code "array/c/0/1"}. A key must not be empty, must not begin or end with {@code '/'},
 * and must not contain an empty segment ({@code "//"}) or a {@code "."}/{@code ".."} segment; see
 * {@link StoreKeys#validate(String)}. Values are arbitrary byte arrays.
 *
 * <p>Reads that miss return {@link Optional#empty()} rather than throwing, because an absent chunk is
 * normal (it means "all fill value"). I/O and protocol failures surface as
 * {@link com.ebremer.falcon.zarr.ZarrException} (unchecked), so callers are not forced to handle
 * {@link java.io.IOException} on every access.
 *
 * <p>Listing methods return sorted, de-duplicated results for deterministic iteration.
 *
 * <p><b>Threads.</b> A store may be used from several threads at once: an array's blocks are often read
 * in parallel. Every store this package ships is safe for that ({@link MemoryStore}, {@link FileSystemStore},
 * {@link ZipStore}, {@link HttpStore}, {@link S3Store}), and an implementation of your own should be too.
 * Safe means each call is atomic on its own, as a {@code ConcurrentHashMap} is: a {@link #get} running beside
 * a {@link #set} of the same key returns the old value or the new one, never a mix, and a listing running
 * beside writes may or may not include them. A sequence of calls is not atomic.
 */
public interface Store {

    /**
     * The full value stored under {@code key}, or empty if the key is absent.
     *
     * @param key the key
     * @return the value, the caller's own, or empty
     * @throws IllegalArgumentException if {@code key} is not a legal key
     */
    Optional<byte[]> get(String key);

    /**
     * A byte range of the value under {@code key}: up to {@code length} bytes starting at {@code offset}.
     * The result is clamped to the value's size (a range past the end yields the available tail, possibly
     * empty). Returns empty only when the key itself is absent.
     *
     * @param key    the key
     * @param offset the range's first byte
     * @param length the most bytes to return
     * @return the bytes in range, or empty if the key is absent
     * @throws IllegalArgumentException if {@code key} is not a legal key, {@code offset} or {@code length} is
     *                                  negative, or {@code length} exceeds {@link Integer#MAX_VALUE}
     */
    Optional<byte[]> getRange(String key, long offset, long length);

    /**
     * The last {@code length} bytes of the value under {@code key}, or all of it if it is shorter; empty
     * only if the key is absent. A shard index stored at the end of a shard is read this way, without
     * asking for the shard's size first.
     *
     * <p>The default asks for the size and then reads the range, two calls that are not atomic together;
     * a store that can read a suffix in one step overrides it ({@link HttpStore} sends one request with
     * {@code Range: bytes=-length}).
     *
     * @param key    the key
     * @param length the most bytes to return, from the end
     * @return the value's tail, or empty if the key is absent
     * @throws IllegalArgumentException if {@code key} is not a legal key, or {@code length} is negative or
     *                                  exceeds {@link Integer#MAX_VALUE}
     */
    default Optional<byte[]> getSuffix(String key, long length) {
        MemoryStore.checkedLength(0, length);
        OptionalLong size = size(key);
        if (size.isEmpty()) {
            return Optional.empty();
        }
        return getRange(key, Math.max(0, size.getAsLong() - length), length);
    }

    /**
     * True if {@code key} is present.
     *
     * @param key the key
     * @return whether a value is stored under it
     * @throws IllegalArgumentException if {@code key} is not a legal key
     */
    boolean exists(String key);

    /**
     * The size in bytes of the value under {@code key}, or empty if the key is absent. Needed to address
     * a value's tail (a shard index stored at the end) without fetching the whole value.
     *
     * @param key the key
     * @return the value's size, or empty if the key is absent
     * @throws IllegalArgumentException if {@code key} is not a legal key
     */
    OptionalLong size(String key);

    /**
     * All keys in the store, sorted.
     *
     * @return the keys
     * @throws UnsupportedOperationException if the store cannot list its keys
     */
    List<String> list();

    /**
     * All keys that begin with {@code prefix}, sorted. An empty prefix lists everything.
     *
     * @param prefix the start every listed key has, compared as a plain string
     * @return the keys
     * @throws UnsupportedOperationException if the store cannot list its keys
     */
    List<String> listPrefix(String prefix);

    /**
     * The immediate children under {@code prefix}: the full keys directly beneath it, plus the full
     * child prefixes (each ending in {@code '/'}) that have descendants deeper down. Sorted.
     *
     * <p>For example, over keys {@code zarr.json}, {@code a/zarr.json}, {@code a/c/0},
     * {@code listDir("")} yields {@code [a/, zarr.json]} and {@code listDir("a/")} yields
     * {@code [a/c/, a/zarr.json]}. {@code prefix} is treated as a directory boundary; a non-empty prefix
     * that does not end in {@code '/'} has one appended.
     *
     * @param prefix the directory to list; empty for the store's top level
     * @return the keys and child prefixes
     * @throws UnsupportedOperationException if the store cannot list its keys
     */
    List<String> listDir(String prefix);

    /** {@return true if this store supports {@link #set} and {@link #delete}} */
    boolean isWritable();

    /**
     * Stores {@code value} under {@code key}, replacing any existing value.
     *
     * @param key   the key
     * @param value the bytes to store; the store keeps its own copy, or writes them out during the call
     * @throws IllegalArgumentException      if {@code key} is not a legal key
     * @throws UnsupportedOperationException if {@link #isWritable()} is false
     */
    void set(String key, byte[] value);

    /**
     * Removes {@code key} if present; a no-op otherwise.
     *
     * @param key the key
     * @throws IllegalArgumentException      if {@code key} is not a legal key
     * @throws UnsupportedOperationException if {@link #isWritable()} is false
     */
    void delete(String key);
}
