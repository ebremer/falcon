# Falcon Zarr — remaining work

Stages **Z0–Z9 are complete**. The module **reads Zarr v2 and v3** and **writes Zarr v3**, across every
codec the ecosystem commonly uses, with no runtime dependencies beyond `java.base`. Everything is
verified against the reference implementations — zarr-python 3.2.1, libzstd, and c-blosc (see
`ConformanceTest`, the codec tests, and `tools/fixtures/`).

What follows is what is **left**. None of it blocks reading or writing real Zarr stores.

## Genuinely remaining

Nothing functional. Every codec, store, and API item from the plan is implemented and
reference-validated. What is left is the deliberately-refused, out-of-scope, and deferred work below.

The variable-length `string` data type (`vlen-utf8` codec) is supported for read **and** write:
`DataType.STRING`, `ZarrArray.readStrings()`/`writeStrings(String[])`, verified both directions against
zarr-python (see the `string_*` fixtures and `StringArrayTest`). Other vlen types (`vlen-bytes`) are not
implemented.

## Refused with a clear error (read side)

Recognized but not implemented; each fails with `ZarrUnsupportedException`, never a wrong result:

- **Zarr v2:** Fortran (`"F"`) order for rank&gt;1; v2 filters (delta, fixed-scale-offset, …); the
  top-level v2 `zlib` compressor (blosc's *internal* zlib is supported).
- **Metadata extensions:** any field marked `must_understand: true` that Falcon does not recognize, a
  non-`regular` chunk grid, an object (extension) data type, and storage transformers.

## Out of scope / inherent limits

- **Zarr v2 writing** — Falcon writes v3 only (v2 is read-compat).
- **zstd / blosc write tuning** — the encoders are correct and compress, but are single-level (no
  clevel/window tuning, greedy LZ). Fine for interop; not optimized for ratio.
- **`ZipStore`** is read-only — a ZIP has no random update, so build one with `ZipStore.pack`.
- **`HttpStore`** cannot list keys — plain HTTP has no directory listing, so a group's children cannot be
  *enumerated* over HTTP (a named child still opens fine).
- **Cloud object stores** (S3/GCS/Azure) — implement the `Store` SPI; the byte-range contract already fits.

## Deferred by design

- **`com.ebremer.falcon.core` extraction — investigated and deferred.** A survey of the two finished
  modules found no shared *model* to extract: data types, byte I/O, checksums, and chunk indexing are all
  format-specific and correctly separate (details in `zarr/PLAN.md` §10). The only mechanically-shareable
  code is a ~40-line n-d block-copy kernel — too small to justify a module. Revisit if a third format or a
  deliberate unified model emerges.

## Notes

- **Benchmarks** are in `Benchmarks.java` (skipped unless `-Dfalcon.bench=true`); results and how to
  run them are in [`BENCHMARKS.md`](BENCHMARKS.md). Headline: Falcon's `zstd` encoder writes ~4-5x faster
  than gzip at the same ratio, and the decoded-chunk cache gives ~8x on overlapping reads.
- **Sharding byte-range coalescing** is done: a sharded read sorts the needed sub-chunk ranges and
  merges adjacent ones (gaps up to 8&nbsp;KiB) into a single `readRange`, so a contiguous run of sub-chunks
  is one fetch instead of N &mdash; the win is over HTTP, where each fetch is a round trip.
- Both compression **encoders** (`zstd`, `blosc`) are implemented; `ArraySpec.zstd()` / `ArraySpec.blosc()`
  select them, and libzstd / c-blosc read Falcon's output. Falcon-written arrays are readable by
  zarr-python.
- Dev-time tools (not Falcon dependencies; `pip install zarr numcodecs`): `gen_zarr_fixtures.py` and
  `gen_zarr_v2_fixtures.py` (v3/v2 conformance fixtures, 32 + 12), `gen_zstd_vectors.py` /
  `gen_blosc_vectors.py` (decoder reference vectors), and `check_zstd_encoder.py` /
  `check_blosc_encoder.py` (encoder interop against libzstd / c-blosc).
