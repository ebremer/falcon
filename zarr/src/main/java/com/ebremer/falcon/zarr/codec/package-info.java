/**
 * The chunk codec pipeline and its codecs.
 *
 * <p>{@link com.ebremer.falcon.zarr.codec.ChunkPipeline} assembles a Zarr v3 codec chain
 * ({@code (array→array)* (array→bytes) (bytes→bytes)*}) and decodes a chunk's stored bytes into its
 * elements. Implemented codecs: {@code transpose} (axis permutation), {@code bytes} (element byte order),
 * {@code gzip} ({@code java.util.zip}), and {@code crc32c} (checksum verify/strip via
 * {@code java.util.zip.CRC32C}). The {@code sharding_indexed}, {@code blosc}, and {@code zstd} codecs
 * arrive in later stages and are reported as unsupported until then.
 *
 * <p>This package is Zarr-internal and not exported; arrays decode chunks through it in the read path.
 */
package com.ebremer.falcon.zarr.codec;
