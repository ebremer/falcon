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
 * <p>The public API package is exported, along with the {@code json} model (Zarr attributes and fill
 * values are arbitrary JSON) and the {@code store} SPI (callers supply a store to open). The metadata,
 * codec, and chunk machinery stays encapsulated.
 */
module com.ebremer.falcon.zarr {
    exports com.ebremer.falcon.zarr;
    exports com.ebremer.falcon.zarr.json;
    exports com.ebremer.falcon.zarr.store;
}
