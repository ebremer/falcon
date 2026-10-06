/**
 * The Zarr store abstraction and its built-in implementations.
 *
 * <p>A {@link com.ebremer.falcon.zarr.store.Store} is a map from {@code '/'}-separated string keys to
 * byte sequences, with listing and partial (byte-range) reads &mdash; the substrate the hierarchy,
 * metadata, and chunk layers are built on. {@link com.ebremer.falcon.zarr.store.MemoryStore} keeps
 * everything in memory; {@link com.ebremer.falcon.zarr.store.FileSystemStore} maps keys to files under
 * a root directory (the canonical on-disk Zarr layout); {@link com.ebremer.falcon.zarr.store.ZipStore}
 * reads and writes a ZIP archive; {@link com.ebremer.falcon.zarr.store.HttpStore} reads over HTTP(S), with
 * headers of the caller's for authorization, and lists keys from a server's directory listing pages when
 * asked to; {@link com.ebremer.falcon.zarr.store.S3Store} reads, lists, and
 * writes S3-compatible object storage (Amazon S3, Google Cloud Storage, MinIO, R2), signing its requests.
 * {@link com.ebremer.falcon.zarr.store.StoreKeys} holds the shared key rules and listing logic.
 *
 * <p>The package is exported: a caller opens a store and passes it to
 * {@link com.ebremer.falcon.zarr.Zarr#open(com.ebremer.falcon.zarr.store.Store)}, and may implement
 * {@link com.ebremer.falcon.zarr.store.Store} for a backend of its own.
 */
package com.ebremer.falcon.zarr.store;
