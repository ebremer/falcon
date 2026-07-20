# Falcon Zarr — Implementation Plan

**Falcon Zarr** (`com.ebremer.falcon.zarr`) is a **pure-JDK 25, zero-runtime-dependency** reader and
writer for the [Zarr v3 core specification](https://zarr-specs.readthedocs.io/en/latest/v3/core/index.html).
It is **Falcon Phase 2**; the HDF5 module (Phase 1) is the sibling and the template for how this is built
(reader-before-writer, thin vertical slice first, every stage gated by conformance tests). See the root
[`PLAN.md`](../PLAN.md) for the umbrella roadmap.

> **Status: Z0–Z7 complete; Z8/Z9 partially complete.** The module reads and writes Zarr v3 and is
> **verified against zarr-python 3.2.1** — 25 conformance fixtures with expected-value sidecars. Both
> compressors the ecosystem actually uses are decoded by hand-written, pure-Java implementations: a
> **Zstandard** decoder (RFC 8878, validated against 18 libzstd frames — zarr-python's default) and a
> **Blosc** decoder (container + `lz4`/`lz4hc`/`zlib`/`zstd` + byte shuffle, validated against 40
> c-blosc buffers). Zarr **v2 stores are read** too (`.zarray`/`.zgroup`/`.zattrs` translated to the v3 model).
> Corrupt-input fuzzing is in place. Stores: memory, filesystem,
> **ZIP** archive, and read-only **HTTP** (byte-range requests). 222 tests green.
>
> **Remaining** (tracked in [`TODO.md`](TODO.md)): zstd/blosc *encode*; blosc's `blosclz`/`snappy` and
> bit-shuffle; and from Z9 the decoded-chunk cache, a
> streaming block API, a user guide, and benchmarks.

---

## 1. What Zarr v3 is (and what we implement)

Zarr stores **chunked, compressed, N-dimensional arrays** in a **key→value store** (a filesystem
directory, a zip file, an object store, memory). The v3 core spec defines, and Falcon implements:

- a **store** abstraction — a map from string keys to byte sequences, with listing and (for efficiency)
  partial byte-range reads;
- a **hierarchy** of **groups** and **arrays** addressed by `/`-separated paths, each node described by a
  JSON metadata document at `<path>/zarr.json`;
- **data types** (bool, signed/unsigned integers, floats, complex, raw bits);
- a **regular chunk grid** dividing an array into fixed-shape chunks, with a configurable **chunk key
  encoding**;
- a **fill value** for chunks absent from the store;
- a **codec pipeline** — ordered array→array, one array→bytes, then bytes→bytes codecs — that transforms
  a chunk between its array form and its stored bytes.

Falcon implements this directly from the specification, in pure Java, with no third-party libraries and no
native code. Strategy (as in HDF5): **reader before writer**, a **thin vertical slice first** (open a real
store and read one array end-to-end), then broaden data-type / codec / store coverage, each step gated by
conformance tests against data written by the reference **zarr-python** library (§8).

## 2. Repository & module structure

```
falcon/
├── hdf5/                            Falcon Phase 1 (built)
└── zarr/                            Falcon Phase 2 (this module)
    ├── pom.xml                      parent = com.ebremer:falcon
    ├── PLAN.md                      this document
    └── src/{main,test}/java/
        ├── module-info.java         module com.ebremer.falcon.zarr
        └── com/ebremer/falcon/zarr/…
```

Package layout (`com.ebremer.falcon.zarr.*`), only the first exported:

```
com.ebremer.falcon.zarr             Public API: Zarr, ZarrGroup, ZarrArray, ZarrNode, DataType, Selection …
        …zarr.json                  Hand-written JSON model + reader + writer (no JDK JSON in java.base)
        …zarr.store                 Store SPI; FileSystemStore, MemoryStore, ZipStore; byte-range reads
        …zarr.metadata              zarr.json parse/serialize: ArrayMetadata, GroupMetadata, extension fields
        …zarr.datatype              Zarr data types; element byte layout; fill-value JSON codec
        …zarr.chunk                 Regular chunk grid; chunk key encoding (default / v2); chunk coordinates
        …zarr.codec                 Codec pipeline SPI + bytes / transpose / gzip / crc32c / sharding_indexed
        …zarr.util                  Shared small utilities
```

**Shared model note.** The N-dimensional array, data-type, and chunk-grid abstractions overlap with HDF5's.
Once this module stabilizes, the common pieces may be promoted to a **`com.ebremer.falcon.core`** module
(as the root plan anticipates) so both formats share one in-memory model. Until then, no premature
abstraction — Zarr keeps its own model and HDF5 is untouched.

## 3. Goals & non-goals

**Goals**
- Full **read** conformance with Zarr v3 core: stores, hierarchy, all core data types, the regular chunk
  grid, both chunk-key encodings, fill values, and the codec pipeline with the essential codecs
  (`bytes`, `transpose`, `gzip`, `crc32c`, `sharding_indexed`).
- Full **write** conformance: emit stores that zarr-python opens and round-trips losslessly.
- A small, documented public API; efficient partial reads via byte-range store access and a
  touch-only-needed-chunks selection reader; zero runtime dependencies; a JPMS module on JDK 25.

**Non-goals / deferred**
- **Cloud object stores** (S3/GCS/Azure) — a `java.net.http`-based HTTP store is a possible later add
  (§ Z8), but signed/authenticated object-store access is out of scope.
- **Consolidated metadata** and other registered **extensions** (custom chunk grids, storage transformers)
  beyond what the core spec mandates — read past-through of `must_understand:false` fields only.
- **Blosc / Zstandard** codecs until they are hand-written in pure Java (staged in Z8) — the same
  "implement compression from scratch, no native/deps" decision made for HDF5's szip.
- **Zarr v2** — read-compatibility is an optional Z8 add; v2 writing is out of scope.

## 4. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Language level | JDK 25, `--release 25` | Matches the reactor. |
| Dependencies | None at runtime; JUnit 5 test-only | "Pure JDK" mandate. |
| JSON | **Hand-written** reader/writer (`zarr.json`) | `java.base` has no JSON; keeps zero-dependency. Small, spec-scoped (objects, arrays, strings, numbers, booleans, null; UTF-8; the special float strings). |
| Stores | Filesystem (`java.nio.file`), memory, zip (`java.util.zip`); **byte-range reads** in the SPI | Byte-range reads make sharding and partial selections cheap. Cloud/HTTP behind the same SPI later. |
| Codecs | `bytes` / `transpose` hand-written; `gzip` via `java.util.zip`; `crc32c` via `java.util.zip.CRC32C`; `sharding_indexed` hand-written; `blosc`/`zstd` **from scratch** (Z8) | All pure-JDK; external compressors implemented from the published formats, not wrapped. |
| Data model | Own N-D array/dtype/chunk model; promote to `com.ebremer.falcon.core` later | No premature cross-module abstraction. |
| Endianness | Per the `bytes` codec `endian` config | The spec puts byte order in the codec, not the data type. |
| Error model | Typed exceptions (`ZarrException`, `ZarrFormatException`, `ZarrUnsupportedException`) | Precise diagnostics; corrupt input never crashes the JVM (mirrors HDF5's hardening). |

## 5. Coverage matrices

### 5.1 Data types (Zarr v3 core)
| Type | Bytes | Stage | Type | Bytes | Stage |
|---|---|---|---|---|---|
| `bool` | 1 | Z2 | `uint8/16/32/64` | 1/2/4/8 | Z2 |
| `int8/16/32/64` | 1/2/4/8 | Z2 | `float16/32/64` | 2/4/8 | Z2 |
| `complex64/128` | 8/16 | Z2 | `r*` (raw bits, e.g. `r8`) | */8 | Z2 |

### 5.2 Chunk key encoding
| Encoding | Key example (coords 1,2) | Separator | Stage |
|---|---|---|---|
| `default` | `c/1/2` (0-d: `c`) | `/` (default) or `.` | Z3 |
| `v2` | `1.2` | `.` (default) or `/` | Z3 (v2 store read-compat: Z8 ✅) |

### 5.3 Codecs
| Codec | Kind | Approach | Decode | Encode |
|---|---|---|---|---|
| `bytes` | array → bytes | hand-written (endian pack/unpack) | Z4 | Z7 |
| `transpose` | array → array | hand-written (axis permutation) | Z4 | Z7 |
| `gzip` | bytes → bytes | `java.util.zip` (gzip container) | Z4 | Z7 |
| `crc32c` | bytes → bytes | `java.util.zip.CRC32C` (4-byte LE trailer) | Z4 | Z7 |
| `sharding_indexed` | array → bytes | hand-written (sub-chunks + offset/length index) | Z6 | Z7 |
| `blosc` | bytes → bytes | **from scratch, pure Java** (container + lz4/zlib/zstd + shuffle) | Z8 ✅ (partial) | not planned |
| `zstd` | bytes → bytes | **from scratch, pure Java** (RFC 8878) | Z8 ✅ | not planned |

### 5.4 Stores
| Store | Read | Write | Byte-range | Stage |
|---|---|---|---|---|
| `MemoryStore` | ✓ | ✓ | ✓ | Z0 |
| `FileSystemStore` | ✓ | ✓ | ✓ | Z0 / Z7 |
| `ZipStore` | ✓ | ✓ | (whole entry) | Z8 |
| `HttpStore` (read-only) | ✓ | — | ✓ (Range) | Z8 (optional) |

## 6. Roadmap — stages Z0–Z9

Each stage ends with a **milestone** and concrete **acceptance criteria**. "Reference store" = a Zarr v3
store written by zarr-python (§8). Stages are dependency-ordered.

### Z0 — Foundations ✅ *done*
- **JSON**: `zarr.json` package — an immutable JSON value model (`JsonObject`/`JsonArray`/`JsonString`/
  `JsonNumber`/`JsonBool`/`JsonNull`), a recursive-descent `JsonReader`, and a `JsonWriter` (stable key
  order, UTF-8, minimal + pretty modes). Handles the special float encodings (`"NaN"`, `"Infinity"`,
  `"-Infinity"`).
- **Store SPI**: `Store` (get / set / delete / exists / list / listDir / getRange) + `MemoryStore` and a
  read/write `FileSystemStore` (keys → paths under a root; `getRange` via `FileChannel`).
- **Error types**: `ZarrException` / `ZarrFormatException` / `ZarrUnsupportedException`.
- **Milestone:** JSON round-trips; a store holds and lists keys.
- **Acceptance:** `mvn -pl zarr test` green; JSON parses the canonical special values and re-serializes
  byte-stable; `MemoryStore`/`FileSystemStore` pass get/set/delete/list unit tests, including byte ranges.

### Z1 — Metadata & hierarchy (read) ✅ *done*
- **Metadata parse**: `ArrayMetadata` (`zarr_format`=3, `node_type`="array", `shape`, `data_type`,
  `chunk_grid`, `chunk_key_encoding`, `fill_value`, `codecs`, plus optional `attributes`,
  `dimension_names`, `storage_transformers`) and `GroupMetadata` (`zarr_format`, `node_type`,
  `attributes`). Unknown extension fields with `must_understand:false` are ignored; anything required and
  unrecognized throws `ZarrUnsupportedException`.
- **Hierarchy**: `Zarr.open(store)` → root `ZarrGroup`; navigate children by reading `<path>/zarr.json`;
  `ZarrGroup.arrays()/groups()/child(name)`; `ZarrArray` exposes `shape()`, `dataType()`, `chunkShape()`,
  `fillValue()`, `attributes()`, `codecs()`.
- **Milestone:** open a zarr-python store and describe every node.
- **Acceptance:** the hierarchy, shapes, data types, chunk shapes, fill values, and attributes match
  zarr-python for a fixture spanning nested groups + arrays.

### Z2 — Data types & fill values ✅ *done*
- All core data types (§5.1): element size + byte layout; the `r*` raw type; a `DataType` model with a
  Java mapping (int/long/float/double/…).
- **Fill-value JSON codec**: decode/encode per type — JSON numbers, the special-float strings, booleans,
  complex as `[re, im]`, raw as a byte array / `0x…` hex string — to/from the element's bytes.
- **Milestone:** every data type's element bytes and fill value are correct.
- **Acceptance:** byte-level tests per type (incl. NaN/Inf fills, complex, raw); values match zarr-python.

### Z3 — Chunk grid & chunk key encoding ✅ *done*
- **Regular chunk grid**: `chunk_shape`; grid dimensions = `ceil(shape/chunk_shape)`; chunk-coordinate ↔
  key math; **edge chunks** are full `chunk_shape` in the decoded form (the out-of-bounds remainder is
  fill).
- **Chunk key encoding**: `default` (`c` prefix, configurable separator, the 0-d `c` case) and `v2`
  (no prefix, configurable separator).
- **Milestone:** map any chunk coordinate to its store key, both encodings.
- **Acceptance:** generated keys match zarr-python for 1-D…4-D arrays and both encodings/separators.

### Z4 — Codec pipeline (decode) ✅ *done*
- **Pipeline model**: an ordered list validated as `array→array`* · one `array→bytes` · `bytes→bytes`*;
  decoding applies the inverse in reverse.
- **Codecs**: `bytes` (endian pack/unpack between the n-D array and a flat buffer), `transpose` (axis
  permutation), `gzip` (`java.util.zip`), `crc32c` (verify + strip the 4-byte LE trailer).
- **Missing chunk** → the whole chunk is the fill value.
- **Milestone:** decode one stored chunk to a typed array.
- **Acceptance:** a chunk's decoded values match zarr-python across `bytes`(+endian) / `transpose` /
  `gzip` / `crc32c` fixtures; a corrupt `crc32c` throws `ZarrFormatException`.

### Z5 — Array read ✅ *done*
- Read a whole array into the natural Java array (row-major), assembling from chunks and filling absent
  chunks; **selection (hyperslab) reads** that touch **only the chunks overlapping the selection**
  (mirrors HDF5's `ChunkedReader.assembleSelection`).
- **Milestone:** `array.readInts()/readDoubles()/…` and `array.select(offset,shape).read…()`.
- **Acceptance:** whole-array and slab reads equal zarr-python for chunked, filtered, multi-dimensional,
  and partially-written (fill) arrays; a slab reads only its chunks.

### Z6 — Sharding codec (read) ✅ *done*
- `sharding_indexed` (array→bytes): a shard packs many sub-chunks plus an **index** (per sub-chunk
  offset+length) encoded by `index_codecs` at `index_location` (`start`/`end`); read sub-chunks via the
  index using store **byte ranges**; inner `codecs` decode each sub-chunk; empty sub-chunks (all-ones
  offset/length) are fill.
- **Milestone:** read a sharded array.
- **Acceptance:** values match zarr-python for a sharded fixture (nested `bytes`+`gzip`, both index
  locations); only the needed shard byte ranges are fetched.

### Z7 — Write path ✅ *done*
- **File-space / store writes**: write `zarr.json` for groups and arrays (byte-stable, spec-ordered);
  create/populate the hierarchy; encode chunks by running the pipeline forward (`bytes`/`transpose`/
  `gzip`/`crc32c`); write only non-fill chunks (empty chunks omitted); **sharding write** (pack sub-chunks
  + build the index). Overwrite/delete semantics.
- **Milestone:** create a store zarr-python reads back losslessly.
- **Acceptance:** for each fixture, `Falcon-write → zarr-python-read` and `zarr-python-write →
  Falcon-read` agree on structure + data; property-based random round-trips pass.

### Z8 — Compression breadth & compatibility — *partial (zstd decode done)*
- **Pure-Java `zstd`** (RFC 8878 decode first, then encode) and/or **`blosc`** (blosclz/lz4 + shuffle) —
  from scratch, validated against numcodecs/zstd reference vectors (a dev-time tool, like libaec for szip).
- **Zarr v2 read compatibility**: `.zgroup`/`.zarray`/`.zattrs`, v2 dtype strings (`<i4`, `|u1`, …),
  v2 chunk keys, and the v2 compressor/filters mapping (blosc/zlib/…).
- **`ZipStore`**; optional read-only **`HttpStore`** (`java.net.http`, HTTP `Range`).
- **Milestone:** open blosc/zstd-compressed and v2 stores.
- **Acceptance:** blosc/zstd chunks decode to the reference values; a zarr-python v2 store reads correctly.

### Z9 — API polish, performance, robustness, docs — *partial (robustness + conformance + CI done)*
- Finalize the public API (typed convenience + scalar reads, block **streaming**, selection ergonomics),
  full Javadoc, worked examples, a user guide.
- **Performance**: touch-only-needed-chunks (done in Z5), a decoded-chunk **cache**, minimized copying,
  byte-range coalescing for sharding, benchmarks.
- **Robustness**: fuzz/corrupt-input tests asserting typed failures (never JVM crashes, OOM from a bad
  size, or infinite loops); CI on JDK 25 (extend the existing workflow to the reactor).
- **Milestone:** the Zarr module is 1.0-ready.

## 7. Codec pipeline notes

- A valid v3 pipeline is `(array→array)* · (array→bytes) · (bytes→bytes)*` with **exactly one**
  array→bytes codec (`bytes` or `sharding_indexed`). Falcon validates this on metadata parse.
- **`bytes`** is the fundamental array→bytes codec: it serializes the chunk's elements (in C order, after
  any `transpose`) to a flat little-/big-endian buffer per its `endian` field. 1-byte and `bool` types
  omit `endian`.
- **`crc32c`** appends a 4-byte little-endian CRC-32C of the preceding bytes; decode verifies and strips
  it (`java.util.zip.CRC32C`).
- **`sharding_indexed`** is itself array→bytes, so it replaces `bytes` in the outer pipeline; its
  `configuration.codecs` is the inner pipeline applied to each sub-chunk, and `index_codecs` (typically
  `bytes`+`crc32c`) encodes the offset/length table.

## 8. Testing & conformance strategy

1. **Unit tests** for every parser/codec against hand-built inputs with spec-cited layouts, plus
   known-answer vectors (crc32c, gzip, and — for Z8 — zstd/blosc reference vectors).
2. **Read conformance** against a committed corpus of reference stores under
   `zarr/src/test/resources/fixtures/`, generated by **zarr-python v3** (the reference oracle), each with
   an expected-values sidecar (JSON) so tests are hermetic — no zarr tooling at build time.
3. **Write conformance / round-trip**: Falcon writes → reopen with Falcon and re-read with zarr-python;
   also zarr-python writes → Falcon reads.
4. **Property-based round-trips**: random shapes/chunkings/dtypes/codecs, assert lossless.
5. **Robustness/fuzz**: truncated/corrupt metadata and chunks raise typed exceptions with context —
   never crash the JVM or return silently-wrong data.

**Reference oracle.** zarr-python (v3) + numcodecs, installed as a **dev-time tool** (`pip install zarr
numcodecs`), analogous to h5py for HDF5 — *not* a Falcon dependency. A small committed Python script
regenerates the fixtures offline. **Note:** zarr-python is not currently installed in this environment, so
Z0–Z3 lean on hand-crafted, spec-derived fixtures (the JSON metadata and chunk bytes are fully specified);
zarr-python is installed before the codec/array stages (Z4+) where an oracle adds the most value. For
codecs the JDK cannot produce (zstd/blosc), reference vectors come from numcodecs/zstd (dev-time tools),
mirroring the libaec approach for szip.

## 9. Sequencing at a glance

```
Z0  JSON + stores + errors          ── "hold and list keys; round-trip JSON"
Z1  metadata + hierarchy (read)     ── "open a store, describe every node"
Z2  data types + fill values        ── "every element/fill byte is correct"
Z3  chunk grid + key encoding       ── "map any chunk coord to its key"
Z4  codec pipeline (decode)         ── "decode one chunk to a typed array"
Z5  array read (+ selections)       ── "read arrays and slabs"
Z6  sharding (read)                 ── "read a sharded array"
Z7  write path                      ── "zarr-python round-trips our store"
Z8  zstd/blosc + v2 + zip/http      ── "read compressed & v2 stores"
Z9  API polish + perf + robustness  ── "Zarr module 1.0"
```

Read-usable after **Z5** ✅; read-complete (incl. sharding) after **Z6** ✅; write-complete after **Z7** ✅.

## 10. Relationship to the HDF5 module and a future `core`

Zarr and HDF5 share concepts — an N-dimensional typed array, chunk grids, filter/codec pipelines,
selections, checksums (crc32c here, lookup3/fletcher32 there), and byte I/O. This module reuses the
**patterns** proven in HDF5 (reader-before-writer, hermetic h5py/zarr-python fixtures, typed errors,
touch-only-needed-chunks, a decoded-chunk cache, corrupt-input fuzzing) but keeps its own code until the
shared array/dtype/chunk/selection model is stable enough to extract into
**`com.ebremer.falcon.core`**, at which point both modules depend on it. That extraction is a deliberate,
separate step — not a prerequisite for Zarr.
