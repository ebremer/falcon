# Falcon Zarr — remaining work

Status as of the Z8/Z9 pass. Stages **Z0–Z7 are complete** and the module reads and writes Zarr v3,
verified against zarr-python 3.2.1 (see `ConformanceTest` and `tools/fixtures/`). What follows is what
is left, and why.

## Z8 — compression breadth & compatibility

| Item | Status |
|---|---|
| **zstd (decode)** | ✅ **Done.** Pure-Java, from RFC 8878; validated against 18 libzstd frames. This was the important one — zarr-python compresses with zstd by default, so Falcon can now read real-world stores. |
| **zstd (encode)** | Not started. Needs a full compressor (match finder + FSE/Huffman encoders); much larger than the decoder. Falcon writes `gzip` or raw, which every implementation reads, so this is a nice-to-have. |
| **blosc (decode)** | ✅ **Mostly done.** Container, block/split layout, byte shuffle, and the `lz4`/`lz4hc`/`zlib`/`zstd` internal compressors; validated against 40 c-blosc buffers. **Not** implemented: the `blosclz` and `snappy` internal codecs and the **bit-shuffle** filter — each is refused with `ZarrUnsupportedException` rather than mis-decoded. |
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
| **Decoded-chunk cache** | Not started. A selection that revisits a chunk currently re-fetches and re-decodes it. The hdf5 module's `ChunkCache` is the model. |
| **Streaming / block API** | Not started. Whole-array reads must fit one Java array (~2 GB); a chunk-at-a-time iterator would lift that. `Selection` already allows manual tiling. |
| **User guide** | Not started. `hdf5/USER_GUIDE.md` is the model. The public API is Javadoc'd throughout. |
| **Benchmarks** | Not started. |
| **Byte-range coalescing for sharding** | Not started. Sub-chunks are fetched individually; adjacent ranges could be merged into one request (matters most for a future HTTP store). |

## Notes

- `zstd` is **decode-only** by design; `ChunkPipeline` reports `ZarrUnsupportedException` on encode with a
  message pointing at gzip.
- Dev-time tools (not Falcon dependencies): `pip install zarr numcodecs` regenerates every fixture via
  `tools/fixtures/gen_zarr_fixtures.py` and `tools/fixtures/gen_zstd_vectors.py`.
- The `com.ebremer.falcon.core` extraction (shared array/dtype/chunk model with the hdf5 module) remains a
  deliberate later step; see `zarr/PLAN.md` §10.
