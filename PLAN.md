# Falcon — Implementation Plan

**Falcon** is a multi-module Maven umbrella for **pure-JDK 25, zero-runtime-dependency** readers and
writers of scientific-data formats.

> **Status: H4 in progress — 55 tests green.** H0–H3 done. H4 increment 1 done: chunked storage
> (v1 B-tree index, 1-D/2-D, boundary chunks) with the **deflate / shuffle / fletcher32** filters.
> Next in H4: scaleoffset / nbit / szip filters and hyperslab reads. New-style (link) groups and the
> newer chunk indexes → H5.

## Program roadmap (Falcon)

| Phase | Module | Package | Scope | State |
|---|---|---|---|---|
| **1** | `hdf5` | `com.ebremer.falcon.hdf5` | Read then write of HDF5 File Format Spec **v4.0** (HDF5 2.0) | **Active** |
| **2** | `zarr` | `com.ebremer.falcon.zarr` | Read/write the **Zarr** storage spec | **Pinned** — see §12 |

The bulk of this document (§3–§11) is the **HDF5 module roadmap** (Falcon Phase 1), organized as
stages **H0–H9**. Zarr (Phase 2) is deliberately deferred and only sketched in §12.

---

## 1. What HDF5 is (and what we implement)

HDF5 is a self-describing, hierarchical binary container: a **superblock** locates a root group; a
tree of **groups** contains **datasets** and **attributes**; **object headers** carry typed
**messages** describing each object's dataspace, datatype, storage layout, and filters; and raw data
lives in contiguous, compact, or chunked storage indexed by **B-trees** and array indices, with
**heaps** holding names, small objects, and variable-length data.

Falcon implements this directly from the specification, in pure Java, with no native `libhdf5` and no
third-party libraries. Strategy: **reader before writer**, and within each, a **thin vertical slice
first** (open a real file end-to-end), then broaden coverage message-by-message and class-by-class,
each step gated by conformance tests against files produced by **h5py** (the reference oracle, §11).

## 2. Repository & module structure

```
falcon/                              parent aggregator POM (packaging: pom) — shared config only
├── pom.xml
├── LICENSE  CLAUDE.md  PLAN.md  README.md
├── hdf5/                            Falcon Phase 1 — built now
│   ├── pom.xml                      parent = com.ebremer:falcon
│   └── src/{main,test}/java/
│       ├── module-info.java         module com.ebremer.falcon.hdf5
│       └── com/ebremer/falcon/hdf5/…
└── zarr/                            Falcon Phase 2 — PLANNED / pinned (not yet created)
```

Shared machinery (byte I/O, checksums, the array/datatype/chunk data model) starts inside the `hdf5`
module and may be **promoted to a `com.ebremer.falcon.core` module** when Zarr lands, so both formats
share one in-memory model. Until then, no premature abstraction.

## 3. Goals & non-goals

**Goals**
- Full **read** conformance with HDF5 Format v4.0: all superblock versions (0–3), object header
  versions (1–2), all 24 header message types, all datatype classes (0–11) and message versions
  (1–5), all dataspace versions, all data layouts and chunk index types, and **all built-in filters
  including szip**.
- Full **write** conformance: emit files that h5py (and, if installed, `h5check`) validates and
  round-trips losslessly.
- A small, ergonomic, documented public API; efficient large-file access via memory mapping; zero
  runtime dependencies; JPMS modules; reproducible builds on JDK 25.

**Non-goals / deferred**
- **SWMR** concurrent-writer semantics, MPI/parallel I/O, and the HDF5 high-level APIs (images, tables,
  dimension scales) — Falcon is a *format* library, not a port of the HL API.
- **Non-default file drivers** beyond the common single-file case (multi/family/split) are read-only
  and low priority.
- Byte-for-byte layout parity with the C library — Falcon's writer chooses its own valid, conformant
  encodings.
- **Zarr** — Falcon Phase 2 (§12).

> **szip is IN scope** (changed from the draft): implemented from scratch in pure Java (§9).

## 4. Target specification

- **Format version 4.0** (HDF5 2.0) — the latest spec, a superset of earlier versions, so reading
  files written by 1.8/1.10/1.12 is covered by supporting the older structure/message versions the
  spec still documents.
- v4.0 specifics we must cover: **datatype message version 5** and the new **Complex** datatype class
  (**class 11**); the revised **Reference** encoding (datatype v4); checksummed superblocks (v2–3) and
  v2 B-trees using the **Jenkins lookup3** checksum.

## 5. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Language level | JDK 25, `--release 25` | Required by the brief. |
| Build | Multi-module Maven reactor; parent `falcon` (pom), module `hdf5` (jar) | Umbrella for HDF5 now, Zarr later. |
| Dependencies | None at runtime; JUnit 5 test-only | "Pure JDK" mandate. |
| Module system | JPMS module per format; export public pkg only | Clean encapsulation of format internals. |
| I/O backend | Foreign Function & Memory API — `MemorySegment` mapped via `FileChannel.map(…, Arena)` | Handles >2 GB files without the 2 GB `MappedByteBuffer` cap; zero-copy reads. A `ByteBuffer` fallback sits behind the same interface. |
| Metadata endianness | Little-endian readers/writers | HDF5 metadata is little-endian; datatype *data* order is per-datatype. |
| Addresses/lengths | Widths from superblock ("size of offsets"/"size of lengths"); undefined = all-1s | Matches the format's parameterized addressing. |
| Checksums | Hand-written Jenkins lookup3 (+ fletcher32) | Not in the JDK. |
| Compression | `java.util.zip` for `deflate`; hand-written `shuffle`/`fletcher32`/`nbit`/`scaleoffset`; hand-written **szip** (§9) | All pure-JDK; zero deps. |
| Error model | Typed exceptions (`HdfFormatException`, `HdfUnsupportedException`) carrying byte offsets | Precise diagnostics against a binary format. |

## 6. HDF5 module — package layout (`com.ebremer.falcon.hdf5.*`)

```
com.ebremer.falcon.hdf5             Public API: Hdf5File, Group, Dataset, Attribute, Datatype, Dataspace, …
        …hdf5.io                    MemorySegment/ByteBuffer abstraction, little-endian reads,
                                    address/length primitives, undefined-address handling
        …hdf5.checksum              Jenkins lookup3 (+ fletcher32 helper)
        …hdf5.superblock            Superblock v0–v3 parse/write; superblock extension; file-space info
        …hdf5.header                Object header v1/v2 prefix + message framing, continuation
        …hdf5.message               The 24 header-message types (parse/serialize)
        …hdf5.datatype              Datatype classes 0–11, message versions 1–5; encode/decode to Java
        …hdf5.dataspace             Dataspace v1/v2 (scalar/simple/null), hyperslab selection
        …hdf5.layout                Data layout v1–v4; contiguous/compact/chunked/virtual; chunk indices
        …hdf5.btree                 Version 1 B-trees (types 0,1); version 2 B-trees (types 0–11)
        …hdf5.heap                  Local heap, global heap, fractal heap; global-heap VDS block
        …hdf5.filter                Filter pipeline SPI + deflate/shuffle/fletcher32/nbit/scaleoffset/szip
        …hdf5.write                 File-space allocation, free-space manager, serialization orchestration
        …hdf5.util                  Shared small utilities
```

Only `com.ebremer.falcon.hdf5` is exported by `module-info.java`.

## 7. Public API sketch (read side; illustrative, will evolve)

```java
try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {    // AutoCloseable; mmaps the file
    Group root = h5.root();
    for (String name : root.childNames()) { ... }

    Dataset ds = root.dataset("/measurements/temperature");
    Datatype  dt = ds.datatype();                          // class, size, byte order, members…
    Dataspace sp = ds.dataspace();                         // rank, dims, maxDims
    long[]  dims = sp.dims();

    float[] all  = ds.readAllFloat();                      // whole dataset, decoded + de-filtered
    float[] slab = ds.select(offset, count).readFloat();   // hyperslab (partial) read

    for (Attribute a : ds.attributes()) { Object v = a.read(); }
}
```

The write API mirrors this (`Hdf5File.create`, `createGroup`, `createDataset(name, dt, sp)`,
`ds.write(array)`, filter/chunk configuration) and is designed at stage H7.

## 8. HDF5 module roadmap — stages H0–H9

Each stage ends with a **milestone** and concrete **acceptance criteria**. "Reference file" = an
`.h5` produced by h5py (§11). Stages are dependency-ordered.

### H0 — Foundations  ✅ **done**
- `io` layer: `MemorySegment`-backed random access with little-endian primitives, configurable
  offset/length widths, undefined-address sentinel; `checksum` package with Jenkins lookup3.
- **Shipped:** `io.HdfBuffer`, `io.MappedHdfFile` (FFM mmap, read-only), `checksum.Lookup3`,
  `HdfException` / `HdfFormatException`; 16 unit tests.
- **Acceptance met:** `mvn -pl hdf5 test` green; lookup3 reproduces the canonical Jenkins vectors
  **and** a real HDF5 v3 superblock checksum written by h5py; maps a file and reads arbitrary
  offsets/widths.

### H1 — Vertical slice: open a file, walk old-style groups  ✅ **done**
- Superblock **v0–v3** (locate root group; validate signature + lookup3 checksum on v2/v3).
- Object header **v1 & v2** prefix, message iteration, **Continuation (16)** and **NIL (0)**.
- **Symbol Table message (17)** → **v1 B-tree type 0** + **Symbol Table Node** + **Symbol Table
  Entry** + **Local Heap** for group link names.
- **Shipped:** `superblock.Superblock`, `header.ObjectHeader`/`HeaderMessage`/`MessageType`,
  `message.SymbolTableMessage`, `btree.GroupBTreeV1`, `group.SymbolTableNode`/`SymbolTableEntry`,
  `heap.LocalHeap`, `io.FileContext`; public `Hdf5File`/`Group`/`Dataset`/`Hdf5Object`,
  `HdfUnsupportedException`. New-style (link) groups are detected and deferred to H5.
- **Acceptance met:** the full group tree of an old-style fixture matches h5py; superblock v0 & v3 and
  object headers v1 & v2 parse against committed fixtures. Fixtures via `tools/fixtures/gen_fixtures.py`.

### H2 — Datatypes & dataspaces  ✅ **done**
- **Datatype message (3)**, versions 1–5, all classes 0–11 (fixed/float/time/string/bitfield/opaque/
  compound/reference/enum/vlen/array/complex), incl. nested compound/array/vlen.
- **Dataspace message (1)**, v1 & v2 (scalar/simple/null, dims + max dims, unlimited).
- **Shipped:** sealed `datatype.Datatype` model (record per class) + `DatatypeClass`; public
  `Dataspace`; internal `message.DatatypeMessage` (recursive) + `message.DataspaceMessage`;
  `Dataset.datatype()` / `Dataset.dataspace()`.
- **Acceptance met:** decoded type/shape match h5py for a fixture (`datatypes.h5`) covering classes
  0/1/3/5/6/7/8/9/10 (LE+BE, signed/unsigned, nested, unlimited); classes 2/4/11 covered by
  byte-level tests. (h5py stores complex as a compound `{r,i}`, so native class 11 has no h5py fixture.)
- Mapping decoded elements to Java values arrives with the data-read stages (H3–H4).

### H3 — Contiguous & compact data reads  ✅ **done** (external data files deferred)
- **Data Layout message (8)**: compact + contiguous for versions 3/4 (tested) and 1/2 (best-effort);
  **Fill Value (5)** v1–v3 + **old (4)**. **External Data Files (7)** deferred to a later stage.
- Decode raw bytes → typed Java arrays for atomic types honoring byte order/precision.
- **Shipped:** `layout.DataLayout` / `DataLayoutMessage`, `message.FillValueMessage`, `data.Elements`;
  `Dataset.readInts/readLongs/readFloats/readDoubles/readStrings/readRawBytes/read()`.
- **Acceptance met:** values byte-for-byte equal to h5py across int (LE+BE, signed/unsigned),
  float32/64, 2-D, and fixed-length-string fixtures; an unallocated dataset reads back its fill value;
  compact storage reads correctly.

### H4 — Chunked storage & filters (incl. szip decode)
- Chunked layout + all index types (Appendix C): **v1 B-tree type 1**, **single chunk**, **implicit**,
  **fixed array**, **extensible array**, **v2 B-tree**.
- **Filter Pipeline message (11)**; filter decode: `deflate` (`java.util.zip`), `shuffle`,
  `fletcher32`, `nbit`, `scaleoffset`, and **`szip` decode** (§9).
- Chunk cache; partial/hyperslab reads that touch only needed chunks.
- **Milestone:** read chunked + compressed datasets (incl. filter chains and szip) and hyperslabs.
- **Acceptance:** matches h5py for gzip/shuffle/fletcher32/nbit/scaleoffset/**szip** fixtures and for
  each chunk-index type; hyperslab reads match full-read subsets.

### H5 — New-style groups, links, attributes, fractal heap, v2 B-trees
- **Link Info (2)**, **Link (6)**, **Group Info (10)**; **fractal heap**; **v2 B-trees** types 5/6
  (link name/creation-order) and 8/9 (attribute name/creation-order); types 1–4 (huge fractal-heap
  objects).
- **Attribute (12)** + **Attribute Info (21)**; **global heap** for variable-length data;
  **Shared Message Table (15)** + shared/committed messages; v2 B-tree type 7.
- **Milestone:** full read of modern HDF5 (dense links/attrs, vlen, committed types).
- **Acceptance:** listings + attribute values + vlen data match h5py for dense-storage, large-group,
  and shared-datatype fixtures.

### H6 — Advanced read & completeness
- **Virtual datasets**: layout class 3 + **global heap block for VDS**; **reference** decode (object +
  region; revised & backward-compat encodings).
- Superblock **extension**, **File Space Info (23)**, **B-tree K Values (19)**, **Driver Info (20)**;
  **Object Comment (13)**, **Modification Time (18)** + **old (14)**, **Object Reference Count (22)**;
  **free-space manager** (read).
- **Milestone:** complete read coverage of every message type and structure in the spec.
- **Acceptance:** a broad corpus (h5py-generated across all matrices) reads without `Unsupported`
  errors; VDS resolves against source datasets.

### H7 — Write path foundations
- **File-space allocation**: end-of-file bump allocator first, then **free-space manager** +
  aggregators; **File Space Info** strategy.
- Write superblock (default v2/v3 with checksums), object header **v2**, message serialization,
  **local/global/fractal heap** writers, **v1/v2 B-tree** writers.
- **Milestone:** write a minimal valid file: root group + one contiguous atomic dataset + one attribute.
- **Acceptance:** h5py opens it and reads identical data; if `h5check` is installed, it reports valid.

### H8 — Write path breadth (incl. szip encode)
- Chunked write + filter pipeline **encode**: deflate/shuffle/fletcher32/nbit/scaleoffset + **szip
  encode** (§9); dense + compact group/attribute storage; all datatype classes
  (compound/array/vlen/enum/reference/complex); fill-value policies; user-selectable chunk shape,
  filter chain, and layout.
- **Milestone:** round-trip parity across the full fixture matrix.
- **Acceptance:** for every fixture, `Falcon-write → h5py-read` and `h5py-write → Falcon-read` agree on
  structure + data; property-based random round-trips pass.

### H9 — API polish, performance, docs
- Finalize the public API (typed convenience readers/writers, streaming, hyperslab ergonomics), full
  Javadoc, worked examples, a short user guide.
- Performance: mmap tuning, chunk-cache sizing, minimized copying via `MemorySegment`; benchmarks;
  large-file (>2 GB) tests.
- Robustness: fuzz/corrupt-input tests asserting typed failures (never JVM crashes or silent wrong
  data); CI on JDK 25.
- **Milestone:** HDF5 module is 1.0-ready.

## 9. SZIP filter plan (in scope, pure Java)

HDF5 filter **ID 4** ("szip") is lossless compression built on **extended-Rice / adaptive entropy
coding** (the algorithm standardized as **CCSDS 121.0-B** and implemented by the BSD-licensed
**libaec**). Falcon implements a **clean-room pure-Java** encoder/decoder of that published algorithm —
**not** a wrapper of the original patented szip code and **not** a native binding — keeping the
zero-dependency, pure-JDK guarantee intact.

- **Parameters** (from the filter pipeline's client data): `options_mask` (entropy-coding vs
  nearest-neighbor; MSB/LSB bit order; raw mode), `pixels_per_block` (typ. 8/16/32), `bits_per_pixel`,
  `pixels_per_scanline`. HDF5 also applies its scanline/block framing on top of the core coder.
- **H4 — decoder:** parse szip client data; implement extended-Rice decode with the nearest-neighbor
  preprocessor and the reference/second-extension/zero-block/FS coding options; validate against h5py
  szip fixtures.
- **H8 — encoder:** the symmetric encoder (block partitioning, option selection, packing).
- **IP note:** the CCSDS 121.0 algorithm is a public standard; libaec is a permissive clean-room
  implementation. Falcon's Java code is written from the standard/format description, carrying no
  third-party code. Recorded here so the provenance is auditable.

## 10. Coverage matrices  (Stage = HDF5 roadmap stage)

### 10.1 Object-header messages (target: all 24)
| # | Message | Stage | # | Message | Stage |
|---|---|---|---|---|---|
| 0 | NIL | H1 | 12 | Attribute | H5 |
| 1 | Dataspace | H2 | 13 | Object Comment | H6 |
| 2 | Link Info | H5 | 14 | Object Modification Time (old) | H6 |
| 3 | Datatype | H2 | 15 | Shared Message Table | H5 |
| 4 | Fill Value (old) | H3 | 16 | Object Header Continuation | H1 |
| 5 | Fill Value | H3 | 17 | Symbol Table | H1 |
| 6 | Link | H5 | 18 | Object Modification Time | H6 |
| 7 | External Data Files | H3 | 19 | B-tree 'K' Values | H6 |
| 8 | Data Layout | H3/H4 | 20 | Driver Info | H6 |
| 9 | Bogus (testing) | — | 21 | Attribute Info | H5 |
| 10 | Group Info | H5 | 22 | Object Reference Count | H6 |
| 11 | Filter Pipeline | H4 | 23 | File Space Info | H6 |

### 10.2 Datatype classes (target: all)
| Class | # | Stage | Class | # | Stage |
|---|---|---|---|---|---|
| Fixed-point | 0 | H2 | Compound | 6 | H2 |
| Floating-point | 1 | H2 | Reference | 7 | H2/H6 |
| Time | 2 | H2 | Enumerated | 8 | H2 |
| String | 3 | H2 | Variable-length | 9 | H2/H5 |
| Bit field | 4 | H2 | Array | 10 | H2 |
| Opaque | 5 | H2 | Complex (v5) | 11 | H2 |

### 10.3 Data layout & chunk indices
| Layout / index | Stage |
|---|---|
| Compact, Contiguous | H3 |
| Chunked — v1 B-tree (type 1) | H4 |
| Chunked — single / implicit / fixed array / extensible array / v2 B-tree | H4 |
| Virtual (VDS) | H6 |

### 10.4 Filters (all built-in; **szip included**)
| Filter | ID | Approach | Decode | Encode |
|---|---|---|---|---|
| deflate (gzip) | 1 | `java.util.zip` | H4 | H8 |
| shuffle | 2 | hand-written | H4 | H8 |
| fletcher32 | 3 | hand-written | H4 | H8 |
| **szip** | 4 | **pure-Java CCSDS 121.0 extended-Rice (§9)** | **H4** | **H8** |
| nbit | 5 | hand-written | H4 | H8 |
| scaleoffset | 6 | hand-written | H4 | H8 |

### 10.5 Structure versions
Superblock v0/v1/v2/v3 · Object header v1/v2 · Datatype msg v1–v5 · Dataspace v1/v2 · Data layout
v1–v4 · v1 & v2 B-trees · Reference encoding (revised + backward-compat).

## 11. Testing & conformance strategy

1. **Unit tests** for every parser/serializer against hand-built byte buffers with spec-cited field
   offsets, plus known-answer vectors (Jenkins lookup3, fletcher32, szip).
2. **Read conformance** against a checked-in corpus of reference `.h5` files
   (`hdf5/src/test/resources/fixtures/`) generated by h5py, spanning §10. Each fixture ships with an
   expected-values sidecar (JSON) so tests are hermetic — no HDF5 tooling needed at build time.
3. **Write conformance / round-trip:** Falcon writes → reopen with Falcon (self-consistency) and
   re-read with h5py; also h5py-writes → Falcon-reads. If `h5check` is later installed, add structural
   validation.
4. **Property-based round-trips:** random datatypes/shapes/chunkings/filters, assert lossless.
5. **Robustness/fuzz:** truncated/corrupt inputs raise typed exceptions with offsets — never crash the
   JVM or return silently-wrong data.

**Reference oracle (confirmed available):** **h5py 3.16.0 bundling HDF5 2.0.0** — an exact match for
the Format v4.0 target — is installed locally. A small committed Python + h5py script generates all
fixtures offline. The `h5dump`/`h5ls`/`h5check` CLIs are absent from `PATH` (optional, not required).

## 12. Falcon Phase 2 — Zarr (pinned)

**Deferred; do not start until asked.** When picked up, the `zarr` module
(`com.ebremer.falcon.zarr`) will implement the **Zarr** storage specification (targeting **v3** with
**v2** read compatibility): the store/group/array hierarchy, `zarr.json`/`.zarray`/`.zattrs` metadata,
chunk grids and key encoding, and codecs (bytes, transpose, blosc-family, gzip/zstd, crc32c, sharding).
It slots into the reactor as a sibling of `hdf5` and is expected to **share a `com.ebremer.falcon.core`
module** (the N-dimensional array + datatype + chunk-grid model extracted from `hdf5` at that time).
Pure-JDK/zero-dependency and reader-before-writer still apply; codecs unavailable in the JDK will be
implemented from scratch or scoped explicitly, mirroring the szip decision. A detailed Zarr roadmap is
written when Phase 2 begins.

## 13. Decisions locked (from review)

1. **HDF5 packages** under `com.ebremer.falcon.hdf5` (module `com.ebremer.falcon.hdf5`). ✅
2. **Multi-module Maven:** parent `falcon` (pom) → module **`hdf5`** (jar); Zarr later as `zarr`. ✅
3. **Git author display name:** `Erich Bremer <erich@ebremer.com>` (no co-author trailers). ✅
4. **License:** Apache-2.0 (`LICENSE` added; POMs declare it). ✅
5. **SZIP:** in scope — pure-Java CCSDS 121.0 decoder (H4) + encoder (H8). ✅
6. **Priority:** reader before writer. ✅
7. **Zarr:** Falcon Phase 2, pinned. ✅
8. **Conformance oracle:** h5py 3.16.0 / HDF5 2.0.0 (installed). ✅
9. **I/O backend:** FFM `MemorySegment`, `ByteBuffer` fallback. (Default; revisit only if it bites.)
10. **JUnit 5 test-only:** artifacts stay pure-JDK. (Default; say the word for a hand-rolled harness.)

## 14. Sequencing at a glance

```
H0  foundations (io + checksum)      ── mvn green, lookup3 vectors pass
H1  superblock → old groups          ── "open a file, list groups"
H2  datatypes + dataspaces           ── "describe any dataset"
H3  contiguous/compact reads         ── "read simple data"
H4  chunked + filters (+szip decode) ── "read real-world compressed data"
H5  new-style groups/attrs + heaps   ── "read modern HDF5 fully"
H6  VDS, refs, remaining messages    ── "complete read conformance"
H7  write foundations                ── "write a valid minimal file"
H8  write breadth (+szip encode)     ── "round-trip parity"
H9  API polish + perf + docs         ── "HDF5 module 1.0"
                                         then → Falcon Phase 2: Zarr (§12)
```

Read-usable after **H5**; read-complete after **H6**; write-complete after **H8**.
