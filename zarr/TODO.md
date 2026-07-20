# Falcon Zarr — remaining work

Stages **Z0–Z9 are complete**. The module **reads Zarr v2 and v3** and **writes Zarr v3**, across every
codec the ecosystem commonly uses, with no runtime dependencies beyond `java.base`. Everything is
verified against the reference implementations — zarr-python 3.2.1, libzstd, and c-blosc (see
`ConformanceTest`, the codec tests, and `tools/fixtures/`).

What follows is what is **left**. None of it blocks reading or writing real Zarr stores.

## Genuinely remaining

| Item | Notes |
|---|---|
| **Benchmarks** | Not started. No throughput/latency measurements exist yet. |
| **Byte-range coalescing for sharding** | Sub-chunks are fetched individually; adjacent ranges could be merged into one request. Matters most over HTTP, where each fetch is a round trip. |
| **blosc `snappy` internal codec (decode)** | The one blosc internal codec not implemented (dropped from modern c-blosc). Refused with `ZarrUnsupportedException` rather than mis-decoded. |

## Refused with a clear error (read side)

Recognized but not implemented; each fails with `ZarrUnsupportedException`, never a wrong result:

- **Zarr v2:** Fortran (`"F"`) order for rank&gt;1; v2 filters (delta, fixed-scale-offset, …); the
  top-level v2 `zlib` compressor (blosc's *internal* zlib is supported).
- **Metadata extensions:** any field marked `must_understand: true` that Falcon does not recognize, a
  non-`regular` chunk grid, an object (extension) data type, and storage transformers.

(For blosc, only `snappy` is left — listed above; `blosclz` and bit-shuffle both decode.)

## Out of scope / inherent limits

- **Zarr v2 writing** — Falcon writes v3 only (v2 is read-compat).
- **zstd / blosc write tuning** — the encoders are correct and compress, but are single-level (no
  clevel/window tuning, greedy LZ). Fine for interop; not optimized for ratio.
- **`ZipStore`** is read-only — a ZIP has no random update, so build one with `ZipStore.pack`.
- **`HttpStore`** cannot list keys — plain HTTP has no directory listing, so a group's children cannot be
  *enumerated* over HTTP (a named child still opens fine).
- **Cloud object stores** (S3/GCS/Azure) — implement the `Store` SPI; the byte-range contract already fits.

## Deferred by design

- **`com.ebremer.falcon.core` extraction** — promoting the shared array/dtype/chunk model out of both the
  hdf5 and zarr modules into one core module (see `zarr/PLAN.md` §10). A planned refactor, not a gap.

## Notes

- Both compression **encoders** (`zstd`, `blosc`) are implemented; `ArraySpec.zstd()` / `ArraySpec.blosc()`
  select them, and libzstd / c-blosc read Falcon's output. Falcon-written arrays are readable by
  zarr-python.
- Dev-time tools (not Falcon dependencies; `pip install zarr numcodecs`): `gen_zarr_fixtures.py` and
  `gen_zarr_v2_fixtures.py` (v3/v2 conformance fixtures, 28 + 12), `gen_zstd_vectors.py` /
  `gen_blosc_vectors.py` (decoder reference vectors), and `check_zstd_encoder.py` /
  `check_blosc_encoder.py` (encoder interop against libzstd / c-blosc).
