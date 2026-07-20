/**
 * The Zarr store abstraction and its built-in implementations.
 *
 * <p>A {@link com.ebremer.falcon.zarr.store.Store} is a map from {@code '/'}-separated string keys to
 * byte sequences, with listing and partial (byte-range) reads &mdash; the substrate the hierarchy,
 * metadata, and chunk layers are built on. {@link com.ebremer.falcon.zarr.store.MemoryStore} keeps
 * everything in memory; {@link com.ebremer.falcon.zarr.store.FileSystemStore} maps keys to files under
 * a root directory (the canonical on-disk Zarr layout). {@link com.ebremer.falcon.zarr.store.StoreKeys}
 * holds the shared key rules and listing logic.
 *
 * <p>This package is Zarr-internal and not exported; stores are reached through the public API.
 */
package com.ebremer.falcon.zarr.store;
