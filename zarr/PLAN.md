# Falcon Zarr — Implementation Plan

**Falcon Zarr** (`com.ebremer.falcon.zarr`) is a **pure-JDK 25, zero-runtime-dependency** reader and
writer for the [Zarr v3 core specification](https://zarr-specs.readthedocs.io/en/latest/v3/core/index.html).
It is **Falcon Phase 2**; the HDF5 module (Phase 1) is the sibling and the template for how this is built
(reader-before-writer, thin vertical slice first, every stage gated by conformance tests). See the root
[`PLAN.md`](../PLAN.md) for the umbrella roadmap.

> **Status (2026-10-06): Z0–Z9 complete, and the 2026-10-04 review's P0–P3 all done.** The module reads
> and writes Zarr v2 and v3, checked against **zarr-python** both ways.
> Falcon reads 144 fixture stores and 2 ZIP archives zarr-python wrote (3.2.1 for the first, 3.4.0 since),
> each against an expected-value sidecar. zarr-python 3.4 reads the 273 arrays, 58 written-into and 58 created
> v2 nodes, 6 hierarchies, and 4 ZIP archives Falcon writes (`check_zarr_writer.py`, `check_zarr_v2_writes.py`,
> `check_zarr_hierarchies.py`, `check_zip_store.py`).
> - **Data:** every core data type, variable-length strings and bytes, the extension types zarr-python
>   writes (datetimes, fixed-size strings and bytes, structs), the regular and rectilinear chunk grids,
>   sharding (nested, with byte-range reads and partial writes), resizing, and consolidated metadata;
>   Zarr v2's NumPy dtypes, Fortran order, and numcodecs filters and compressors, read and written, v2
>   arrays and groups created as zarr-python creates them, and v2 consolidated metadata; the zarr-extensions
>   `cast_value` (cast-value-rs's results bit for bit) and `reshape` codecs, and numcodecs' bz2 and zfpy
>   (each written byte for byte as numcodecs writes it).
> - **Compression,** hand-written in pure Java in `core`:
>   - zstd and Blosc are decoded: 316 libzstd frames, 500 c-blosc buffers, 122 c-blosc2 chunks.
>   - zstd is encoded: libzstd reads 101 Falcon frames at every level.
>   - Blosc is encoded with every internal compressor, as c-blosc's own bytes but for zstd: Falcon
>     reproduces 1,512 c-blosc buffers and 468 liblz4 blocks exactly, and c-blosc decodes all 18,435
>     non-empty buffers of a cross-check matrix.
>   - numcodecs' filters run byte for byte as numcodecs does (772 vectors).
>   - gzip, zlib, and the CRC-32 checksums come from `java.util.zip`.
> - **Stores:** memory, filesystem, ZIP (read and written), and read-only HTTP (byte ranges; listing from
>   directory index pages when asked). Amazon S3 and S3-compatible storage are the `s3` module's
>   `S3Store`, over the AWS SDK (moved out of this module on 2026-10-07, so it keeps no dependencies).
> - **Robustness:** corrupt input fails with typed exceptions, fuzzed under a small heap and stack;
>   handles are safe across threads; an opt-in decoded-chunk cache. 917 tests, and 19 more under a small
>   heap, pass; the public API's Javadoc is complete and checked by the compile.
>
> **Remaining** (tracked in [`TODO.md`](TODO.md)): nothing from the review; the non-goals below.

---

## 1. What Zarr v3 is (and what we implement)

Zarr stores **chunked, compressed, N-dimensional arrays** in a **key→value store** (a filesystem
directory, a zip file, an object store, memory). The v3 core spec defines, and Falcon implements:

- a **store** abstraction — a map from string keys to byte sequences, with listing and (for efficiency)
  partial byte-range reads;
- a **hierarchy** of **groups** and **arrays** addressed by `/`-separated paths, each node described by a
  JSON metadata document at `<path>/zarr.json`;
- **data types** (bool, signed/unsigned integers, floats, complex, raw bits), plus the registered
  extension types zarr-python writes (variable-length strings and bytes, datetimes, fixed-size strings
  and bytes, structs);
- a **regular chunk grid** dividing an array into fixed-shape chunks (and the registered `rectilinear`
  grid, whose chunks differ in shape), with a configurable **chunk key encoding**;
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
├── core/                            com.ebremer.falcon.core: the compression codecs (zstd, Blosc, LZ4, …)
├── hdf5/                            Falcon Phase 1 (built)
└── zarr/                            Falcon Phase 2 (this module)
    ├── pom.xml                      parent = com.ebremer:falcon; requires core
    ├── PLAN.md                      this document
    ├── TODO.md                      remaining work, and what was done
    ├── USER_GUIDE.md                the public API, with examples
    ├── BENCHMARKS.md                measured throughput
    └── src/{main,test}/java/
        ├── module-info.java         module com.ebremer.falcon.zarr
        └── com/ebremer/falcon/zarr/…
```

Package layout (`com.ebremer.falcon.zarr.*`); the first four are exported:

```
com.ebremer.falcon.zarr             Public API: Zarr, ZarrGroup, ZarrArray, ZarrNode, ArraySpec, Selection …
        …zarr.datatype              Zarr data types; element byte layout; fill-value JSON codec
        …zarr.json                  Hand-written JSON model + reader + writer (no JDK JSON in java.base)
        …zarr.store                 Store SPI; Memory, FileSystem, Zip, and Http stores; byte-range reads
                                    (S3: the s3 module's S3Store, over the AWS SDK)
        …zarr.metadata              zarr.json / v2 parse and serialize; consolidated metadata; extension fields
        …zarr.chunk                 Regular and rectilinear chunk grids; chunk key encoding (default / v2)
        …zarr.codec                 Codec pipeline + bytes / vlen / transpose / cast_value / reshape / gzip /
                                    crc32c / sharding_indexed, the zstd / blosc / numcodecs.lz4 / bz2 / zfpy
                                    codecs over core's codecs, and numcodecs' zlib, filters, and checksums
                                    (with NumPy's dtype arithmetic)
        …zarr.data                  Chunk assembly and writing, element conversion, the chunk cache, resize
```

The data model is Zarr's own; only the compression codecs are shared with HDF5, in `core` (§10).

## 3. Goals & non-goals

**Goals**
- Full **read** conformance with Zarr v3 core: stores, hierarchy, all core data types, the regular chunk
  grid, both chunk-key encodings, fill values, and the codec pipeline with the essential codecs
  (`bytes`, `transpose`, `gzip`, `crc32c`, `sharding_indexed`).
- Full **write** conformance: emit stores that zarr-python opens and round-trips losslessly.
- A small, documented public API; efficient partial reads via byte-range store access and a
  touch-only-needed-chunks selection reader; zero runtime dependencies; a JPMS module on JDK 25.

**Non-goals / deferred**
- ~~**Cloud object stores**~~ — done in P2 (F1, 2026-10-06): `HttpStore` takes auth headers, and an
  `S3Store` signed its requests with SigV4 (`javax.crypto`, in `java.base`). On 2026-10-07, by Erich's
  decision, S3 moved to a module of its own, `s3`, over the AWS SDK for Java 2.x (its credential chain,
  retries, and endpoints), which also reads HDF5 from S3; this module's hand-written `S3Store` was removed.
  Azure Shared Key and GCS OAuth remain out of scope (SAS URLs and bearer tokens work).
- ~~**Consolidated metadata**~~ — done in P2 (F2, 2026-10-06): read (v3 inline, v2 `.zmetadata`) and
  written (v3; v2 since F17).
- ~~**Other registered extensions**~~ — done in P2 (F14, 2026-10-06): the `rectilinear` chunk grid (the
  only other one registered) is read and written, and so are the extension data types zarr-python writes;
  storage transformers with `must_understand: false` are read past (none is registered), as are other
  `must_understand: false` fields. Registry data types zarr-python does not write remain out of scope.
- ~~**Blosc / Zstandard** codecs until they are hand-written in pure Java~~ — done (Z8, and P2's F12 for
  the zstd encoder's levels): decoded and encoded from scratch, now in `core`, the same "implement
  compression from scratch, no native/deps" decision made for HDF5's szip.
- ~~**Creating Zarr v2 arrays**~~ — done (F17, 2026-10-06). Zarr v2 is read (Z8; P2's F4 closed the
  gaps: NumPy's string, byte, time, structured, and object dtypes, Fortran order, numcodecs' filters, and
  the zlib and lz4 compressors), written into, and created: arrays (`ArraySpec.Builder.zarrFormat(2)`,
  with `order`, `filters`, and `compressor`), groups, and consolidated `.zmetadata`, as zarr-python 3.4
  writes them.

## 4. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Language level | JDK 25, `--release 25` | Matches the reactor. |
| Dependencies | None at runtime; JUnit 5 test-only | "Pure JDK" mandate. |
| JSON | **Hand-written** reader/writer (`zarr.json`) | `java.base` has no JSON; keeps zero-dependency. Small, spec-scoped (objects, arrays, strings, numbers, booleans, null; UTF-8; the special float strings). |
| Stores | Filesystem (`java.nio.file`), memory, ZIP (read and written by hand; CRC-32 and inflate from `java.util.zip`), HTTP (`HttpURLConnection`); **byte-range reads** in the SPI. S3 in the `s3` module, over the AWS SDK | Byte-range reads make sharding and partial selections cheap. `java.net.http` would be a module beyond `java.base`, so `HttpStore` uses `java.net.HttpURLConnection`. S3 is a module of its own so that this one keeps no dependencies. |
| Codecs | `bytes` / `transpose` / `vlen-*` hand-written; `gzip` via `java.util.zip`; `crc32c` via `java.util.zip.CRC32C`; `sharding_indexed` hand-written; `blosc`/`zstd` **from scratch** (Z8), in `core` | All pure-JDK; external compressors implemented from the published formats, not wrapped. |
| Data model | Own N-D array/dtype/chunk model; only the codecs are shared, in `core` (§10) | A shared model would distort both formats' models (§10). |
| Endianness | Per the `bytes` codec `endian` config | The spec puts byte order in the codec, not the data type. |
| Error model | Typed exceptions (`ZarrException`, `ZarrFormatException`, `ZarrUnsupportedException`) | Precise diagnostics; corrupt input never crashes the JVM (mirrors HDF5's hardening). |

## 5. Coverage matrices

### 5.1 Data types (Zarr v3 core, then the extension types zarr-python writes)
| Type | Bytes | Stage | Type | Bytes | Stage |
|---|---|---|---|---|---|
| `bool` | 1 | Z2 | `uint8/16/32/64` | 1/2/4/8 | Z2 |
| `int8/16/32/64` | 1/2/4/8 | Z2 | `float16/32/64` | 2/4/8 | Z2 |
| `complex64/128` | 8/16 | Z2 | `r*` (raw bits, e.g. `r8`) | */8 | Z2 |
| `string` (vlen-utf8) | variable | after Z9 | `variable_length_bytes` (vlen-bytes) | variable | F5 |
| `numpy.datetime64` / `numpy.timedelta64` | 8 | F14 | `fixed_length_utf32` | 4 × length | F14 |
| `null_terminated_bytes` / `raw_bytes` | length | F14 | `struct` (legacy `structured` read) | sum of fields | F14 |

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
| `cast_value` (zarr-extensions) | array → array (changes the element type) | hand-written, cast-value-rs 0.4.2's results bit for bit | F15 ✅ | F15 ✅ |
| `reshape` (zarr-extensions) | array → array (changes the shape) | hand-written | F16 ✅ | F16 ✅ |
| `gzip` | bytes → bytes | `java.util.zip` (gzip container) | Z4 | Z7 |
| `crc32c` | bytes → bytes | `java.util.zip.CRC32C` (4-byte LE trailer) | Z4 | Z7 |
| `vlen-utf8` / `vlen-bytes` | array → bytes | hand-written (numcodecs' VLen layout) | after Z9 / F5 | after Z9 / F5 |
| `sharding_indexed` | array → bytes | hand-written (sub-chunks + offset/length index; nested, F11) | Z6 | Z7 |
| `blosc` | bytes → bytes | **from scratch, pure Java**, in `core` (container + blosclz/lz4/lz4hc/zlib/zstd/snappy + byte/bit shuffle; c-blosc2's format too, F14) | Z8 ✅ | Z8 ✅ (no/byte/bit shuffle, clevel; every internal compressor, c-blosc's bytes but for zstd, F3) |
| `zstd` | bytes → bytes | **from scratch, pure Java**, in `core` (RFC 8878) | Z8 ✅ | Z8 ✅ (levels 1–22, F12) |
| `numcodecs.zlib` / `numcodecs.lz4` | bytes → bytes | `java.util.zip`; LZ4 from scratch in `core` (numcodecs' size-prefixed block) | F3 ✅ | F3 ✅ |
| `numcodecs.bz2` | bytes → bytes | `core`'s bzip2, from scratch (libbzip2 1.0.8's bytes; concatenated streams) | F16 ✅ | F16 ✅ |
| `numcodecs.zfpy` | array → bytes | `core`'s zfp decoder and encoder, from scratch (zfp 1.0.1) | F16 ✅ | F18 ✅ |
| numcodecs filters and checksums (`numcodecs.delta`, `fixedscaleoffset`, `quantize`, `bitround`, `astype`, `packbits`, `shuffle`, `crc32`, `crc32c`, `adler32`, `fletcher32`, `jenkins_lookup3`) | bytes → bytes (a v2 array's filters; shuffle and the checksums in v3 too) | hand-written, NumPy 2's casts and promotion | F4 ✅ | F4 ✅ |

### 5.4 Stores
| Store | Read | Write | Byte-range | Stage |
|---|---|---|---|---|
| `MemoryStore` | ✓ | ✓ | ✓ | Z0 |
| `FileSystemStore` | ✓ | ✓ | ✓ | Z0 / Z7 |
| `ZipStore` | ✓ | ✓ (written in place, F13) | ✓ (STORED entries) | Z8 / F13 |
| `HttpStore` (read-only) | ✓ (lists from HTML index pages, opt-in, F13) | — | ✓ (Range) | Z8 (optional) |
| `S3Store` (the `s3` module, AWS SDK) | ✓ | ✓ | ✓ | F1; the SDK since 2026-10-07 |

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

### Z8 — Compression breadth & compatibility ✅ *done (v2's Fortran order and filters since P2's F4; creating v2 arrays since F17)*
- **Pure-Java `zstd`** (RFC 8878 decode first, then encode) and/or **`blosc`** (blosclz/lz4 + shuffle) —
  from scratch, validated against numcodecs/zstd reference vectors (a dev-time tool, like libaec for szip).
- **Zarr v2 read compatibility**: `.zgroup`/`.zarray`/`.zattrs`, v2 dtype strings (`<i4`, `|u1`, …),
  v2 chunk keys, and the v2 compressor/filters mapping (blosc/zlib/…).
- **`ZipStore`**; optional read-only **`HttpStore`** (`java.net.HttpURLConnection`, HTTP `Range`;
  `java.net.http` would be a module beyond `java.base`).
- **Milestone:** open blosc/zstd-compressed and v2 stores.
- **Acceptance:** blosc/zstd chunks decode to the reference values; a zarr-python v2 store reads correctly.

### Z9 — API polish, performance, robustness, docs ✅ *done*
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

**Reference oracle.** zarr-python (v3) + numcodecs, installed as a **dev-time tool**
(`tools/fixtures/requirements.txt`), analogous to h5py for HDF5 — *not* a Falcon dependency. Committed
`tools/fixtures/gen_zarr_*.py` scripts write the fixtures, and the `check_*.py` scripts read Falcon's
output back with zarr-python (`WriteZarrCases.java` and its siblings write it). Z0–Z3 began with
hand-crafted, spec-derived fixtures; zarr-python 3.2.1 wrote the first oracle fixtures and 3.4.0 the rest,
and 3.4.0 regenerates every one of them with the same metadata and values (P3's D5). For the codecs the JDK
cannot produce (zstd, Blosc), reference vectors come from libzstd, c-blosc, and c-blosc2 (through
numcodecs, zstandard, and imagecodecs), mirroring the libaec approach for szip.

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

Zarr and HDF5 share *concepts* — an N-dimensional typed array, chunk grids, filter/codec pipelines,
selections, checksums, and byte I/O. This module reuses the **patterns** proven in HDF5
(reader-before-writer, hermetic h5py/zarr-python fixtures, typed errors, touch-only-needed-chunks, a
decoded-chunk cache, corrupt-input fuzzing).

**Decision (both modules complete): a `com.ebremer.falcon.core` module is not extracted.** A survey of
the two finished modules found that the concepts they share are implemented by *format-specific* code
that should stay separate:

- **Data types** — HDF5's `Datatype` models on-disk classes 0–11 with byte order from the datatype
  message; Zarr's `DataType` names the core types and carries a fill-value JSON codec. Different models;
  a shared abstraction would distort both.
- **Byte I/O** — HDF5 uses `MemorySegment`/`Arena` (Foreign Function &amp; Memory, for &gt;2&nbsp;GB
  memory-mapped files); Zarr uses `byte[]`/`ByteBuffer` over a key-value `Store` SPI. Different substrates.
- **Checksums** — HDF5 hand-writes lookup3 and fletcher32; Zarr calls the JDK's `java.util.zip.CRC32C`.
  No shared code. (Since P2's F4, Zarr's numcodecs checksums use the same two; they moved to `core`,
  2026-10-06: see the update below.)
- **Chunk indexing** — HDF5's v1/v2 B-trees and fixed/extensible arrays vs Zarr's regular and
  rectilinear grids plus sharding. Different.

The only genuinely identical, mechanically-shareable code is a ~40-line N-dimensional row-major
strided block copy (HDF5's `ChunkedReader.copyIntersection`, Zarr's `data.Blocks.copy`) — too small to
justify a module and its cross-module dependency. A shared *model* is therefore **deferred until a real
one emerges** (for example a third format, or a deliberate unification effort); it is not a gap.

**Update (2026-10-05): the codecs moved to `core`.** HDF5's third-party filters (Blosc 32001, zstd
32015, LZ4 32004, bitshuffle 32008, LZF 32000) need exactly the compression code this module had written,
so — by Erich's decision — it moved to a `core` module (`com.ebremer.falcon.core`) instead of being
copied:
- **Packages:** `compress.zstd`, `compress.blosc` (with BloscLZ, Snappy, byte shuffle), `compress.lz4`,
  `compress.bitshuffle`, and the new `compress.lzf`, exported only to the two format modules.
- **Exceptions:** malformed data is `CompressionFormatException` and unsupported variants
  `UnsupportedCompressionException`; each module maps them to its own exceptions.
- **Tests and vectors:** the codec unit tests, their vectors, and the codec fuzzing moved with the code.

Data types, byte I/O, checksums, and chunk indexing stay format-specific, as above.

**Update (2026-10-06): what both modules had written twice moved to `core` too,** by Erich's decision:
- **`checksum`:** Fletcher-32 and Jenkins' lookup3, HDF5's metadata and `fletcher32` checksums and
  numcodecs' `fletcher32` and `jenkins_lookup3` (Zarr had its own copies since F4). HDF5's
  `MetadataChecksum`, which verifies a structure's stored hash, stays in the HDF5 module.
- **`compress.shuffle`:** the byte shuffle of HDF5's `shuffle` filter, Blosc, and numcodecs' `shuffle`.
  Bytes past the last whole element are copied through, as libhdf5 does; HDF5's writer had dropped
  them, which corrupted a chunk shuffled after a filter that changes its length.
- **`compress.zlib`:** zlib streams through `java.util.zip`, HDF5's `deflate` filter and numcodecs'
  `zlib`, decoded bounded and strict (a truncated stream, a failed Adler-32 check, or a preset dictionary
  is refused, as zlib's `inflate` refuses it).

Data types, byte I/O, and chunk indexing stay format-specific. So do checksums the JDK provides (CRC-32,
CRC-32C, Adler-32), gzip (Zarr's alone), and the codecs only one format has.
