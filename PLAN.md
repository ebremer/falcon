# Falcon — Implementation Plan

A pure-JDK 25 reader/writer for the HDF5 file format, implementing the
**[HDF5 File Format Specification, Version 4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html)**
(as shipped with HDF5 2.0).

> **Status: DRAFT — awaiting your review.** Nothing in the roadmap below (Phase 1 onward) has been
> implemented yet. Phase 0 scaffolding (Maven module, git repo, this plan) is in place. Please read
> §10 *Open questions* and confirm/adjust before I start Phase 1.

---

## 1. Overview

HDF5 is a self-describing, hierarchical binary container: a **superblock** locates a root group; a
tree of **groups** contains **datasets** and **attributes**; **object headers** carry typed
**messages** describing each object's dataspace, datatype, storage layout, and filters; and the raw
data lives in contiguous, compact, or chunked storage indexed by **B-trees** and array indices, with
**heaps** holding names, small objects, and variable-length data.

Falcon implements this format directly from the specification, in pure Java, with no native `libhdf5`
and no third-party libraries. The strategy is **read first, then write**, and within each, a **thin
vertical slice first** (open a real file end-to-end), then broaden coverage message-by-message and
class-by-class, each step gated by conformance tests against files produced by the reference HDF5
tooling.

## 2. Goals & non-goals

**Goals**
- Full **read** conformance with Format v4.0: all superblock versions (0–3), object header versions
  (1–2), all 24 header message types, all datatype classes (0–11) and message versions (1–5), all
  dataspace versions, all data layouts and chunk index types, and the built-in filters.
- Full **write** conformance: emit files that the reference tools (`h5dump`, `h5check`, `h5py`) open
  and validate, round-tripping data losslessly.
- A small, ergonomic, well-documented public API. Efficient large-file access via memory mapping.
- Zero runtime dependencies; JPMS module; deterministic, reproducible builds on JDK 25.

**Non-goals (initially) / known gaps**
- **SZIP filter** (encode/decode): patent-adjacent and complex; not in the JDK. Falcon will *detect*
  szip-compressed data and fail with a clear, actionable error rather than silently mis-decode.
  A pure-Java implementation may be added later behind the same filter SPI. (Open question §10.)
- **Non-default file drivers** beyond the common single-file case (multi/family/split driver info)
  are read-only and low priority; the single contiguous file driver is the default target.
- **SWMR** concurrent-writer semantics, MPI/parallel I/O, and the high-level HDF5 APIs (images,
  tables, dimension scales) are out of scope — Falcon is a *format* library, not a port of the HL API.
- No attempt to preserve the exact byte layout the C library would choose; Falcon's writer picks its
  own valid, spec-conformant encodings.

## 3. Target specification

- **Format version:** 4.0 (HDF5 2.0). This is the "latest" spec and is a superset of earlier
  versions, so reading older files (written by 1.8/1.10/1.12) is covered by supporting the older
  structure/message versions the spec still documents.
- Notable v4.0 additions we must cover: **datatype message version 5** and the new **Complex**
  datatype class (**class 11**); the revised **Reference** encoding (datatype v4); checksummed
  superblocks (v2–3) and v2 B-trees using the **Jenkins lookup3** checksum.

## 4. Design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Language level | JDK 25, `--release 25` | Required by the brief. |
| Dependencies | None at runtime; JUnit 5 test-only | "Pure JDK" mandate. |
| Module system | JPMS module `com.ebremer.falcon`, export public pkg only | Clean encapsulation of format internals. |
| I/O backend | Foreign Function & Memory API — `MemorySegment` mapped via `FileChannel.map(…, Arena)` | Handles >2 GB files without the 2 GB `MappedByteBuffer` cap; zero-copy reads; modern idiom. A `ByteBuffer` fallback sits behind the same interface for non-mappable sources / streams. |
| Metadata endianness | Little-endian readers/writers | HDF5 stores format metadata little-endian; datatype *data* order is per-datatype. |
| Addresses/lengths | Configurable width (from superblock "size of offsets"/"size of lengths"), "undefined address" = all-1s | Matches the format's parameterized addressing. |
| Checksums | Hand-written Jenkins lookup3 | Not in the JDK; used by v2+ superblock, v2 B-trees, fractal heap, checksummed chunks. |
| Compression | `java.util.zip.Inflater/Deflater` for `deflate`; hand-written `shuffle`, `fletcher32`, `nbit`, `scaleoffset` | All pure-JDK; only szip is out (§2). |
| Error model | Typed exceptions (`HdfFormatException`, `HdfUnsupportedException`) with byte offsets | Precise diagnostics against a binary format. |

## 5. Architecture & package layout

```
com.ebremer.falcon            Public API: HdfFile, Group, Dataset, Attribute, Datatype, Dataspace, …
com.ebremer.falcon.io         MemorySegment/ByteBuffer abstraction, little-endian reads,
                              address/length primitives, undefined-address handling
com.ebremer.falcon.checksum   Jenkins lookup3 (+ fletcher32 helper)
com.ebremer.falcon.superblock Superblock v0–v3 parse/write; superblock extension; file-space info
com.ebremer.falcon.header     Object header v1/v2 prefix + message framing, continuation
com.ebremer.falcon.message    The 24 header-message types (parse/serialize)
com.ebremer.falcon.datatype   Datatype classes 0–11, message versions 1–5; encode/decode to Java
com.ebremer.falcon.dataspace  Dataspace v1/v2 (scalar/simple/null), hyperslab selection
com.ebremer.falcon.layout     Data layout v1–v4; contiguous/compact/chunked/virtual; chunk indices
com.ebremer.falcon.btree      Version 1 B-trees (types 0,1); version 2 B-trees (types 0–11)
com.ebremer.falcon.heap       Local heap, global heap, fractal heap; global-heap VDS block
com.ebremer.falcon.filter     Filter pipeline SPI + deflate/shuffle/fletcher32/nbit/scaleoffset
com.ebremer.falcon.write      File-space allocation, free-space manager, serialization orchestration
com.ebremer.falcon.util       Shared small utilities
```

Internal packages are **not** exported by `module-info.java`; only `com.ebremer.falcon` is.

## 6. Public API sketch (read side; illustrative, will evolve)

```java
try (HdfFile h5 = HdfFile.open(Path.of("data.h5"))) {      // AutoCloseable; mmaps the file
    Group root = h5.root();
    for (String name : root.childNames()) { ... }

    Dataset ds = root.dataset("/measurements/temperature");
    Datatype  dt = ds.datatype();                          // class, size, byte order, members…
    Dataspace sp = ds.dataspace();                         // rank, dims, maxDims
    long[]  dims = sp.dims();

    float[] all = ds.readAllFloat();                       // whole dataset, decoded + de-filtered
    float[] slab = ds.select(offset, count).readFloat();   // hyperslab (partial) read

    for (Attribute a : ds.attributes()) { Object v = a.read(); }
}
```

The write API mirrors this (`HdfFile.create`, `root.createGroup`, `createDataset(name, dt, sp)`,
`ds.write(array)`, filter/chunk configuration) and is designed in Phase 7.

## 7. Roadmap

Each phase ends with a **milestone** and concrete **acceptance criteria**. "Reference file" means an
`.h5` produced by the HDF5 C tools or `h5py` (see §9). Phases are dependency-ordered; earlier phases
unblock later ones.

### Phase 0 — Scaffolding & foundations  ✅ (this commit) / 🔜 (I/O layer)
- ✅ Maven module, JDK 25, JPMS `module-info`, git repo, `CLAUDE.md`, this `PLAN.md`, smoke test.
- 🔜 `io` layer: `MemorySegment`-backed random access with little-endian primitives, configurable
  offset/length widths, undefined-address sentinel; `checksum` package with Jenkins lookup3.
- **Acceptance:** `mvn test` green; lookup3 reproduces known test vectors; can map a file and read
  arbitrary offsets/widths.

### Phase 1 — Vertical slice: open a file, walk old-style groups
- Superblock **v0–v3** (locate root group; validate signature + checksum).
- Object header **v1 & v2** prefix, message iteration, **Continuation (16)** and **NIL (0)**.
- **Symbol Table message (17)** → **v1 B-tree type 0** + **Symbol Table Node** + **Symbol Table
  Entry** + **Local Heap** for group link names.
- **Milestone:** open a real `.h5`, print the group tree by name.
- **Acceptance:** group listing matches `h5ls` for a fixture with nested old-style groups.

### Phase 2 — Datatypes & dataspaces
- **Datatype message (3)**, versions 1–5, all classes 0–11 (fixed/float/time/string/bitfield/opaque/
  compound/reference/enum/vlen/array/complex), including nested compound/array/vlen.
- **Dataspace message (1)**, v1 & v2 (scalar/simple/null, dims + max dims).
- Decoders mapping atomic types to Java primitives/`MemorySegment`; describe compound member layout.
- **Milestone:** fully describe any dataset's type + shape.
- **Acceptance:** type/shape reports match `h5dump -H` for a fixture matrix covering every class.

### Phase 3 — Contiguous & compact data reads
- **Data Layout message (8)** v1–v4 for contiguous + compact; **Fill Value (5)** + **old Fill Value
  (4)**; **External Data Files (7)**.
- Decode raw bytes → typed Java arrays for atomic types (honoring per-datatype byte order/precision).
- **Milestone:** read complete data of contiguous & compact datasets.
- **Acceptance:** values byte-for-byte equal to `h5py` reads across integer/float/string fixtures,
  including non-native byte order and fill-value gaps.

### Phase 4 — Chunked storage & filters
- Chunked layout + all index types (Appendix C): **v1 B-tree type 1**, **single chunk**, **implicit**,
  **fixed array**, **extensible array**, **v2 B-tree**.
- **Filter Pipeline message (11)**; filters: `deflate` (`java.util.zip`), `shuffle`, `fletcher32`,
  `nbit`, `scaleoffset`; graceful unsupported-filter error for `szip`.
- Chunk cache; partial/hyperslab reads that touch only needed chunks.
- **Milestone:** read chunked + compressed datasets (incl. filter chains) and hyperslabs.
- **Acceptance:** matches `h5py` for gzip/shuffle/fletcher32/nbit/scaleoffset fixtures and for each
  chunk-index type; hyperslab reads match full-read subsets.

### Phase 5 — New-style groups, links, attributes, fractal heap, v2 B-trees
- **Link Info (2)**, **Link (6)**, **Group Info (10)**; **fractal heap**; **v2 B-trees** types 5/6
  (link name/creation-order) and 8/9 (attribute name/creation-order); types 1–4 (huge fractal-heap
  objects).
- **Attribute (12)** + **Attribute Info (21)**; **global heap** for variable-length data;
  **Shared Message Table (15)** + shared/committed messages; v2 B-tree type 7.
- **Milestone:** full read of files written by modern HDF5 (dense links/attrs, vlen, committed types).
- **Acceptance:** listings + attribute values + vlen data match `h5dump`/`h5py` for dense-storage,
  large-group, and shared-datatype fixtures.

### Phase 6 — Advanced read & completeness
- **Virtual datasets**: layout class 3 (Virtual) + **global heap block for VDS**; **reference**
  decode (object + region, revised & backward-compat encodings).
- Superblock **extension** details, **File Space Info (23)**, **B-tree K Values (19)**,
  **Driver Info (20)**; **Object Comment (13)**, **Modification Time (18)** + **old (14)**,
  **Object Reference Count (22)**; **free-space manager** (read).
- **Milestone:** complete read coverage of every message type and structure in the spec.
- **Acceptance:** a broad corpus (h5py + HDFView + HDF5 test suite samples) reads without
  `Unsupported` errors; VDS resolves against source datasets.

### Phase 7 — Write path foundations
- **File-space allocation**: end-of-file bump allocator first, then **free-space manager** +
  aggregators; **File Space Info** strategy.
- Write superblock (choose version — default v2/v3 with checksums), object header **v2**, message
  serialization, **local/global/fractal heap** writers, **v1/v2 B-tree** writers.
- **Milestone:** write a minimal valid file: root group + one contiguous atomic dataset + one
  attribute.
- **Acceptance:** `h5check` reports the file valid; `h5dump`/`h5py` read back identical data.

### Phase 8 — Write path breadth
- Chunked write + filter pipeline (deflate/shuffle/fletcher32/nbit/scaleoffset); dense + compact
  group/attribute storage; all datatype classes (compound/array/vlen/enum/reference/complex);
  fill-value policies; user-selectable chunk shape, filter chain, and layout.
- **Milestone:** round-trip parity across the full fixture matrix.
- **Acceptance:** for every fixture, `write → reference-tool read` and `reference-tool write → Falcon
  read` agree on structure + data; property-based random round-trips pass.

### Phase 9 — API polish, performance, docs
- Finalize public API (typed convenience readers/writers, streaming, hyperslab selection ergonomics),
  full Javadoc, worked examples, and a short user guide.
- Performance: mmap tuning, chunk-cache sizing, minimized copying via `MemorySegment`; benchmarks vs.
  a baseline; large-file (>2 GB) tests.
- Robustness: fuzz/corrupt-input tests asserting typed failures (never JVM crashes or silent wrong
  data); CI on JDK 25.
- **Milestone:** 1.0-ready library.

## 8. Coverage matrices

### 8.1 Object-header messages (target: all 24)
| # | Message | Phase | # | Message | Phase |
|---|---|---|---|---|---|
| 0 | NIL | 1 | 12 | Attribute | 5 |
| 1 | Dataspace | 2 | 13 | Object Comment | 6 |
| 2 | Link Info | 5 | 14 | Object Modification Time (old) | 6 |
| 3 | Datatype | 2 | 15 | Shared Message Table | 5 |
| 4 | Fill Value (old) | 3 | 16 | Object Header Continuation | 1 |
| 5 | Fill Value | 3 | 17 | Symbol Table | 1 |
| 6 | Link | 5 | 18 | Object Modification Time | 6 |
| 7 | External Data Files | 3 | 19 | B-tree 'K' Values | 6 |
| 8 | Data Layout | 3/4 | 20 | Driver Info | 6 |
| 9 | Bogus (testing) | — | 21 | Attribute Info | 5 |
| 10 | Group Info | 5 | 22 | Object Reference Count | 6 |
| 11 | Filter Pipeline | 4 | 23 | File Space Info | 6 |

### 8.2 Datatype classes (target: all)
| Class | # | Phase | Class | # | Phase |
|---|---|---|---|---|---|
| Fixed-point | 0 | 2 | Compound | 6 | 2 |
| Floating-point | 1 | 2 | Reference | 7 | 2/6 |
| Time | 2 | 2 | Enumerated | 8 | 2 |
| String | 3 | 2 | Variable-length | 9 | 2/5 |
| Bit field | 4 | 2 | Array | 10 | 2 |
| Opaque | 5 | 2 | Complex (v5) | 11 | 2 |

### 8.3 Data layout & chunk indices
| Layout / index | Phase |
|---|---|
| Compact, Contiguous | 3 |
| Chunked — v1 B-tree (type 1) | 4 |
| Chunked — single / implicit / fixed array / extensible array / v2 B-tree | 4 |
| Virtual (VDS) | 6 |

### 8.4 Filters
| Filter | ID | Approach | Phase |
|---|---|---|---|
| deflate (gzip) | 1 | `java.util.zip` | 4 |
| shuffle | 2 | hand-written | 4 |
| fletcher32 | 3 | hand-written | 4 |
| szip | 4 | **unsupported** (clear error) | — |
| nbit | 5 | hand-written | 4 |
| scaleoffset | 6 | hand-written | 4 |

### 8.5 Structure versions
Superblock v0/v1/v2/v3 · Object header v1/v2 · Datatype msg v1–v5 · Dataspace v1/v2 · Data layout
v1–v4 · v1 & v2 B-trees · Reference encoding (revised + backward-compat).

## 9. Testing & conformance strategy

1. **Unit tests** for every parser/serializer against hand-built byte buffers with spec-cited field
   offsets, plus known-answer vectors (e.g. Jenkins lookup3, fletcher32).
2. **Read conformance** against a checked-in corpus of reference `.h5` files (in
   `src/test/resources/fixtures/`) generated by `h5py`/HDF5 CLI tools, spanning the coverage matrices
   in §8. Each fixture ships with an expected-values sidecar (JSON) so tests are hermetic and need no
   HDF5 tooling at build time.
3. **Write conformance / round-trip:** Falcon writes → reopen with Falcon (self-consistency) and,
   where the toolchain is available, validate with `h5check` and re-read with `h5py`. Also
   reference-writes → Falcon-reads.
4. **Property-based round-trips:** random datatypes/shapes/chunkings/filters, assert lossless.
5. **Robustness/fuzz:** truncated/corrupt inputs must raise typed exceptions with offsets — never
   crash the JVM or return silently-wrong data.

Fixtures are generated by a small, committed **Python + h5py** script (run once, offline) so CI needs
no native HDF5 at build time. **Confirmed available on this machine: h5py 3.16.0 bundling HDF5 2.0.0** —
an exact match for the Format v4.0 target — so every row in the §8 matrices can be generated and
cross-validated against the reference implementation. The standalone CLIs (`h5dump`/`h5ls`/`h5check`)
are not on `PATH`, but h5py's API covers structure inspection, value reads, and file validation; the
CLIs are optional and can be installed later if desired.

## 10. Open questions (please confirm during review)

1. **Coordinates / package** — `groupId=com.ebremer`, `artifactId=falcon`, base package
   `com.ebremer.falcon`. OK? (Derived from your email domain.)
2. **Git author display name** — I've pinned commits to `Erich Bremer <erich@ebremer.com>`. Confirm
   the display name "Erich Bremer" (email is fixed per your instruction).
3. **JPMS module** — include `module-info.java` (recommended). Any objection?
4. **I/O backend** — Foreign Function & Memory API (`MemorySegment`) as primary, with a `ByteBuffer`
   fallback. Agree?
5. **JUnit 5 test-only** — acceptable for tests while the shipped artifact stays pure-JDK? Or do you
   want a *literally* zero-dependency `pom` (hand-rolled test harness)?
6. **SZIP** — OK to ship as an explicit "unsupported" error initially (§2), or is szip required for
   v1.0?
7. **Priority** — read-first then write (my recommendation), or interleave write earlier?
8. **Conformance toolchain — RESOLVED.** h5py 3.16.0 (bundling **HDF5 2.0.0**, i.e. exactly the
   Format v4.0 target) is installed and will be the reference oracle for fixture generation and
   cross-validation. The `h5dump`/`h5ls`/`h5check` CLIs are absent from `PATH` but are not required.
   Tell me if you'd also like the CLI tools wired up.
9. **License** — `pom.xml` currently declares Apache-2.0. Confirm or change; I'll add a `LICENSE`
   file to match.

## 11. Sequencing at a glance

```
Phase 0  scaffold + io + checksum        ── foundation
Phase 1  superblock → old groups         ── "open a file, list groups"
Phase 2  datatypes + dataspaces          ── "describe any dataset"
Phase 3  contiguous/compact reads        ── "read simple data"
Phase 4  chunked + filters               ── "read real-world compressed data"
Phase 5  new-style groups/attrs + heaps  ── "read modern HDF5 fully"
Phase 6  VDS, refs, remaining messages   ── "complete read conformance"
Phase 7  write foundations               ── "write a valid minimal file"
Phase 8  write breadth                   ── "round-trip parity"
Phase 9  API polish + perf + docs        ── "1.0"
```

Read-usable after Phase 5; read-complete after Phase 6; write-complete after Phase 8.
