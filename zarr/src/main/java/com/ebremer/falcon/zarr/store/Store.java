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
 */
public interface Store {

    /** The full value stored under {@code key}, or empty if the key is absent. */
    Optional<byte[]> get(String key);

    /**
     * A byte range of the value under {@code key}: up to {@code length} bytes starting at {@code offset}.
     * The result is clamped to the value's size (a range past the end yields the available tail, possibly
     * empty). Returns empty only when the key itself is absent.
     *
     * @throws IllegalArgumentException if {@code offset} or {@code length} is negative, or {@code length}
     *                                  exceeds {@link Integer#MAX_VALUE}
     */
    Optional<byte[]> getRange(String key, long offset, long length);

    /** True if {@code key} is present. */
    boolean exists(String key);

    /**
     * The size in bytes of the value under {@code key}, or empty if the key is absent. Needed to address
     * a value's tail (a shard index stored at the end) without fetching the whole value.
     */
    OptionalLong size(String key);

    /** All keys in the store, sorted. */
    List<String> list();

    /** All keys that begin with {@code prefix}, sorted. An empty prefix lists everything. */
    List<String> listPrefix(String prefix);

    /**
     * The immediate children under {@code prefix}: the full keys directly beneath it, plus the full
     * child prefixes (each ending in {@code '/'}) that have descendants deeper down. Sorted.
     *
     * <p>For example, over keys {@code zarr.json}, {@code a/zarr.json}, {@code a/c/0},
     * {@code listDir("")} yields {@code [a/, zarr.json]} and {@code listDir("a/")} yields
     * {@code [a/c/, a/zarr.json]}. {@code prefix} is treated as a directory boundary; a non-empty prefix
     * that does not end in {@code '/'} has one appended.
     */
    List<String> listDir(String prefix);

    /** True if this store supports {@link #set} and {@link #delete}. */
    boolean isWritable();

    /**
     * Stores {@code value} under {@code key}, replacing any existing value.
     *
     * @throws UnsupportedOperationException if {@link #isWritable()} is false
     */
    void set(String key, byte[] value);

    /**
     * Removes {@code key} if present; a no-op otherwise.
     *
     * @throws UnsupportedOperationException if {@link #isWritable()} is false
     */
    void delete(String key);
}
