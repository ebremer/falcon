/**
 * The chunk codec pipeline and its codecs.
 *
 * <p>{@link com.ebremer.falcon.zarr.codec.ChunkPipeline} assembles a Zarr v3 codec chain
 * ({@code (array→array)* (array→bytes) (bytes→bytes)*}) and decodes a chunk's stored bytes into its
 * elements, or encodes them. Implemented codecs: {@code transpose} (axis permutation), {@code reshape}
 * (zarr-extensions: another shape, the same C order), {@code bytes} (element byte order), {@code vlen-utf8}
 * and {@code vlen-bytes} (variable-length elements), {@code sharding_indexed}, {@code gzip}
 * ({@code java.util.zip}), {@code crc32c} (checksum verify/strip via {@code java.util.zip.CRC32C}),
 * {@code zstd} and {@code blosc} (Falcon Core's encoders and decoders), and, under the names zarr-python 3
 * writes, numcodecs' {@code numcodecs.zlib}, {@code numcodecs.lz4}, and {@code numcodecs.bz2} compressors,
 * its {@code numcodecs.zfpy} (zfp, decoded only), and its filters and checksums ({@code numcodecs.delta},
 * {@code numcodecs.crc32}, &hellip;; see {@code Numcodecs}), which run a Zarr v2 array's filters.
 *
 * <p>{@code cast_value} (zarr-extensions) converts the elements to another data type by value, as
 * cast-value-rs, zarr-python's backend for it, converts them ({@code CastValueCodec}, {@code ValueCast}).
 *
 * <p>This package is Zarr-internal and not exported; arrays decode chunks through it in the read path.
 */
package com.ebremer.falcon.zarr.codec;
