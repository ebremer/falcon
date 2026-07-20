# Falcon Zarr — remaining work

Status as of the Z8/Z9 pass. Stages **Z0–Z7 are complete** and the module reads and writes Zarr v3,
verified against zarr-python 3.2.1 (see `ConformanceTest` and `tools/fixtures/`). What follows is what
is left, and why.

## Z8 — compression breadth & compatibility

| Item | Status |
|---|---|
| **zstd (decode)** | ✅ **Done.** Pure-Java, from RFC 8878; validated against 18 libzstd frames. This was the important one — zarr-python compresses with zstd by default, so Falcon can now read real-world stores. |
| **zstd (encode)** | Not started. Needs a full compressor (match finder + FSE/Huffman encoders); much larger than the decoder. Falcon writes `gzip` or raw, which every implementation reads, so this is a nice-to-have. |
| **blosc (decode)** | ✅ **Done.** Container, block/split layout, byte shuffle **and bit-shuffle**, and the `blosclz`/`lz4`/`lz4hc`/`zlib`/`zstd` internal compressors; validated against 51 c-blosc buffers plus 7 zarr-python fixtures. The only internal codec left is `snappy` (removed from modern c-blosc; refused with `ZarrUnsupportedException` rather than mis-decoded). |
| **blosc (encode)** | Not started, same reasoning as zstd encode. |
| **Zarr v2 read-compat** | ✅ **Done (read).** `.zarray`/`.zgroup`/`.zattrs` are translated into the v3 model on open; NumPy dtype strings, the `.`/`/` dimension separator, and the `gzip`/`zstd`/`blosc` compressors are mapped. Validated against 12 zarr-python v2 fixtures plus a hand-built group hierarchy. **Not** supported: Fortran order for rank>1, v2 filters (delta/etc.), the `zlib` compressor, and writing v2 — each refused with a clear error. |
| **ZipStore** | ✅ **Done (read).** Opens a `.zip` archive as a store (`java.util.zip`); `ZipStore.pack(source, target)` builds one from any store. In-place writes into a ZIP are not supported (ZIP has no random update). |
| **HttpStore (read-only)** | ✅ **Done.** GET/HEAD/`Range` over `java.net.HttpURLConnection` (kept in `java.base`, not `java.net.http`). Arrays (incl. sharded, via byte ranges) and named-child navigation work; directory listing does not exist over plain HTTP, so `list`/`listDir` are unsupported. |

## Z9 — API polish, performance, robustness, docs

| Item | Status |
|---|---|
| **Robustness / fuzzing** | ✅ **Done.** `RobustnessTest` fuzzes the zstd decoder (truncation, bit flips, random input), malformed metadata, oversized declared shapes, and corrupt/truncated chunks, asserting every failure is a typed, contained exception. |
| **Conformance harness** | ✅ **Done.** 21 zarr-python fixtures with expected-value sidecars; hermetic (no Python at build time). |
| **CI** | ✅ Already covered — the workflow runs `mvn -B verify` over the whole reactor, so the zarr module is built and tested. |
| **Decoded-chunk cache** | ✅ **Done.** A per-array LRU (~16 MB) caches whole decoded chunks, keyed by chunk key; overlapping/repeated selections decompress each chunk once. Writes evict the affected key; sharded partial-region decodes are not cached. `ZarrArray.clearChunkCache()` releases it. |
| **Streaming / block API** | ✅ **Done.** `ZarrArray.blocks()` returns a lazy `Stream<Selection>`, one per chunk (edge chunks clamped), so an array too big for one Java array can be read tile by tile. |
| **User guide** | ✅ **Done.** `zarr/USER_GUIDE.md` covers opening, reading, selections/streaming, writing, hierarchy, data types, codecs, and stores. |
| **Benchmarks** | Not started. |
| **Byte-range coalescing for sharding** | Not started. Sub-chunks are fetched individually; adjacent ranges could be merged into one request (matters most for a future HTTP store). |

## Notes

- `zstd` is **decode-only** by design; `ChunkPipeline` reports `ZarrUnsupportedException` on encode with a
  message pointing at gzip.
- Dev-time tools (not Falcon dependencies): `pip install zarr numcodecs` regenerates every fixture via
  `tools/fixtures/gen_zarr_fixtures.py` and `tools/fixtures/gen_zstd_vectors.py`.
- The `com.ebremer.falcon.core` extraction (shared array/dtype/chunk model with the hdf5 module) remains a
  deliberate later step; see `zarr/PLAN.md` §10.
