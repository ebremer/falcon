/**
 * Falcon Zarr &mdash; a pure-JDK Zarr reader/writer implementing the
 * <a href="https://zarr-specs.readthedocs.io/en/latest/v3/core/index.html">Zarr v3 core specification</a>.
 *
 * <p>The module has <strong>no runtime dependencies beyond {@code java.base}</strong>. Zarr metadata is
 * JSON, which {@code java.base} does not parse, so a small JSON reader/writer is hand-written; the
 * {@code gzip} codec uses {@code java.util.zip} and the {@code crc32c} codec uses
 * {@code java.util.zip.CRC32C}, both in {@code java.base}. Codecs not available in the JDK
 * (e.g. {@code blosc}, {@code zstd}) are implemented from scratch in pure Java, mirroring the HDF5
 * module's {@code szip} decision.
 *
 * <p>Only the public API package is exported; the store, metadata, codec, and chunk machinery is
 * encapsulated.
 */
module com.ebremer.falcon.zarr {
    exports com.ebremer.falcon.zarr;
}
