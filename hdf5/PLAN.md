# Falcon HDF5 — Implementation Plan

**Falcon HDF5** (`com.ebremer.falcon.hdf5`) is a **pure-JDK 25, zero-runtime-dependency** reader and
writer for the [HDF5 File Format Specification v4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html)
(HDF5 2.0). It is **Falcon Phase 1** and the template for the Zarr module (Phase 2, `../zarr/PLAN.md`).
See the root [`../PLAN.md`](../PLAN.md) for the umbrella roadmap.

> **Status: read complete (H0–H6); write through H8; H9 essentially complete (robustness, >2 GB, perf,
> streaming API, CI, docs) — the HDF5 module is 1.0-ready. 144 tests green.** **Read (H0–H6):**
> every superblock/header/group form, all datatype classes, compact / contiguous / **external-file** /
> chunked storage with **every** chunk index at any scale (v1 B-tree, single-chunk, **implicit**, fixed
> array, extensible array, v2 B-tree), all six filters (incl. pure-Java szip), hyperslabs, dense
> links/attributes, vlen strings & sequences, committed datatypes, the large-set structures, object +
> region references, virtual datasets (external-source assembly incl. **strided / multi-block**
> mappings), the **superblock extension** (**File Space Info** + **free-space managers**), and **every**
> object-header message type. **Write (H7–H8):** `Hdf5Writer` emits a valid modern-format file — v3
> (checksummed) superblock, v2 (checksummed) object headers, a **nested group tree**, contiguous
> **every common atomic width (int8/16/32/64, float32/64, fixed-length string) / vlen string / compound
> / enum / object-reference / array / vlen-sequence / native complex** datasets (vlen via a global heap;
> references resolved across objects, forward refs included), **chunked** datasets (fixed-array index)
> with **all six built-in filters encoded** (deflate, shuffle, fletcher32, scale-offset, n-bit, and
> pure-Java **szip**), **compact or contiguous** layout, **custom fill values**, and scalar/array
> **attributes**, switching groups and objects to **dense storage** (fractal heap + v2 B-tree) past 8
> links/attributes, and optionally the **earliest on-disk format** (v0 superblock, symbol-table groups,
> v1 headers) — read back identically by Falcon *and h5py* (szip verified via libaec, since h5py's szip
> is disabled here). Remaining: a couple of niche write datatypes (bitfield / opaque / time). The read
> edge cases once deferred (SOHM shared messages, unlimited-pattern VDS, the revised reference encoding)
> are done, from fixtures made through h5py's bundled libhdf5 (see [`TODO.md`](TODO.md)).

This document is the **HDF5 module roadmap**, organized as stages **H0–H9** (§8). The sibling Zarr
module has its own [`../zarr/PLAN.md`](../zarr/PLAN.md); the umbrella phase table lives in the root
[`../PLAN.md`](../PLAN.md).

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
│   ├── PLAN.md                      this document
│   └── src/{main,test}/java/
│       ├── module-info.java         module com.ebremer.falcon.hdf5
│       └── com/ebremer/falcon/hdf5/…
└── zarr/                            Falcon Phase 2 — built (com.ebremer.falcon.zarr)
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

**Non-goals**

Each of these sits *outside* the boundary of the project: Falcon is a **format** library (it reads and
writes the on-disk container) under a **pure-JDK, zero-dependency** mandate, validated by **semantic
round-trip conformance** against h5py. Every non-goal falls out of that boundary.

- **SWMR** (Single-Writer/Multiple-Reader) concurrent access. SWMR is a *runtime concurrency protocol*,
  not part of the format: staying coherent depends on libhdf5's metadata-cache flush ordering and
  flush-dependency tracking between objects — coordination discipline, not bytes on disk. Falcon uses a
  batch model (open → read/write → close), which covers the overwhelming majority of Java workloads;
  live concurrent access is a large, orthogonal effort with its own correctness surface.
- **MPI / parallel I/O** (Parallel HDF5). Collective I/O across MPI ranks requires an MPI runtime — a
  native library and an entire distributed-computing model — which directly contradicts the
  zero-runtime-dependency, pure-JDK guarantee. This one is *precluded* by the core mandate, not merely
  deprioritized.
- **HDF5 high-level APIs** (images `H5IM`, tables `H5TB`, dimension scales `H5DS`). These are
  *conventions layered on the base objects* (an "image" is a dataset with particular attributes; a
  "dimension scale" is a dataset plus a specific attribute/reference pattern), not format primitives.
  Falcon exposes the underlying groups/datasets/attributes/references, so a caller can read or implement
  these conventions directly; porting the HL API would add convenience surface without adding *format*
  capability, and can always be layered on later. Falcon is a *format* library, not a port of the HL API.
- **Non-default file drivers** (family / multi / split) — read-only and low priority. These Virtual File
  Drivers spread one logical file across several physical files, and are rare in practice: almost every
  real `.h5` uses the default single-file driver. Not forbidden — just no demand pulling it in. (Distinct
  from *external raw data files* (External File List), a per-dataset spec feature that **is** supported.)
- **Byte-for-byte on-disk parity** with the C library — Falcon's writer chooses its own valid,
  conformant encodings. The spec deliberately allows many valid encodings of the same logical content
  (message ordering, chunk-index choice, free-space arrangement, allocation order, padding). Matching
  libhdf5 exactly would be *brittle* (its layout shifts across versions/settings), *constraining* (it
  would tie Falcon to libhdf5's internal choices rather than the spec), and *valueless to readers*
  (conformance means any spec-valid file is readable, not that two files are byte-identical). The right
  test is semantic round-trip — h5py reads what Falcon writes and vice versa — which Falcon does across
  the whole fixture matrix.

**Not a non-goal — deferred.** Distinct from the above (which are out of scope *by design*), a few
in-scope features are simply not done yet: the bitfield/opaque/time datatype classes on write. (SOHM
shared-message deduplication, the revised `H5R_ref_t` reference encoding, and unlimited-pattern (printf)
VDS mappings are read since P2, from fixtures made through h5py's bundled libhdf5.) See the stage roadmap (§8) and [`TODO.md`](TODO.md). **Zarr** is Falcon Phase 2 (§12).

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
| I/O backend | Foreign Function & Memory API — `MemorySegment` mapped via `FileChannel.map(…, Arena)`; bytes in memory wrap as a heap segment; any other source is read on demand through a public `RangeReader` (paged, cached metadata; direct data reads) behind the same `HdfBuffer` | Handles >2 GB files without the 2 GB `MappedByteBuffer` cap; zero-copy reads. Remote and in-memory files read through the same parsers (P2 A6, 2026-10-05). |
| Metadata endianness | Little-endian readers/writers | HDF5 metadata is little-endian; datatype *data* order is per-datatype. |
| Addresses/lengths | Widths from superblock ("size of offsets"/"size of lengths"); undefined = all-1s | Matches the format's parameterized addressing. |
| Checksums | Hand-written Jenkins lookup3 (+ fletcher32) | Not in the JDK. |
| Compression | `java.util.zip` for `deflate`; hand-written `shuffle`/`fletcher32`/`nbit`/`scaleoffset`; hand-written **szip** (§9) | All pure-JDK; zero deps. |
| Error model | Typed exceptions (`HdfFormatException`, `HdfUnsupportedException`) carrying byte offsets | Precise diagnostics against a binary format. |

## 6. HDF5 module — package layout (`com.ebremer.falcon.hdf5.*`)

```
com.ebremer.falcon.hdf5             Public API: Hdf5File, Group, Dataset, Attribute, Datatype, Dataspace, …
        …hdf5.io                    HdfBuffer over a MemorySegment or a paged RangeReader, little-endian reads,
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

### H3 — Contiguous & compact data reads  ✅ **done**
- **Data Layout message (8)**: compact + contiguous for versions 3/4 (tested) and 1/2 (best-effort);
  **Fill Value (5)** v1–v3 + **old (4)**. **External Data Files (7) ✓** (H6): a contiguous dataset whose
  raw data lives in external raw files (`message.ExternalFileList`), read via the slot table + a local
  heap of file names, resolved relative to the `.h5` file.
- Decode raw bytes → typed Java arrays for atomic types honoring byte order/precision.
- **Shipped:** `layout.DataLayout` / `DataLayoutMessage`, `message.FillValueMessage`, `data.Elements`;
  `Dataset.readInts/readLongs/readFloats/readDoubles/readStrings/readRawBytes/read()`.
- **Acceptance met:** values byte-for-byte equal to h5py across int (LE+BE, signed/unsigned),
  float32/64, 2-D, and fixed-length-string fixtures; an unallocated dataset reads back its fill value;
  compact storage reads correctly.

### H4 — Chunked storage & filters (incl. szip decode)  ✅ **decode done** (newer indexes → H5)
- Chunked layout (v3) via the **v1 B-tree (type 1)** index. The newer indexes (single-chunk /
  fixed-array / extensible-array / v2 B-tree, v4/v5 layout) accompany new-style files and are **done in
  H5** (§ H5 below).
- **Filter Pipeline message (11)** + filter decode: **deflate** (`java.util.zip`), **shuffle**,
  **fletcher32**, **scaleoffset** (integer), **nbit** (atomic), and **szip** (pure-Java CCSDS 121.0, §9).
- **Shipped:** `layout.DataLayoutMessage` (chunked v3) + `DataLayout.Chunked`, `btree.ChunkBTreeV1`,
  `data.ChunkedReader` (N-D assembly + boundary clamping), `filter.FilterPipeline`/`Filters`/`Aec`;
  `Dataset.select(...)` → `Selection` (hyperslab).
- **Acceptance met:** matches h5py for chunked (1-D/2-D) + deflate/shuffle/fletcher32/scaleoffset/nbit
  fixtures; szip validated against libaec vectors; hyperslab reads equal full-read subsets.
- Deferred: chunk-cache and touch-only-needed-chunks optimization (H9); float scaleoffset, compound
  nbit, signed szip, szip **encode** (H8).

### H5 — New-style groups, links, attributes, fractal heap, v2 B-trees
- **Link Info (2)**, **Link (6)**, **Group Info (10)**; **fractal heap**; **v2 B-trees** types 5/6
  (link name/creation-order) and 8/9 (attribute name/creation-order); types 1–4 (huge fractal-heap
  objects).
- **Newer chunk indexes ✓** (version-4/5 Data Layout): **single-chunk** (type 1), **fixed array**
  (`FAHD`/`FADB`, type 3), **extensible array** (`EAHD`/`EAIB`/`EASB`/`EADB`, type 4, incl. secondary
  blocks), and **v2-B-tree** (`BTHD`/`BTLF`, type 5, records 10/11) — each for non-filtered and
  filtered chunks. `index.{FixedArray,ExtensibleArray,ChunkBTreeV2}`. **Implicit (type 2) ✓**
  (`index.ImplicitIndex`): chunks laid out contiguously in row-major grid order at a base address, with
  no on-disk index (early allocation, no filters, fixed dims). **Large-set structures done**: deep v2 B-trees with
  `BTIN` internal nodes, indirect-block fractal heaps (doubling table), and paged extensible-array data
  blocks (checksummed pages + a page-init bitmap in the secondary block).
- **Attribute (12)** + **Attribute Info (21)**; **global heap** for variable-length data. **Vlen ✓**:
  both flavours of class-9 data resolve through the global heap — **strings** (`data.VlenStrings`) and
  **sequences / ragged arrays** (`data.VlenSequences`: int/long/float/double rows incl. empty rows),
  for datasets and attributes alike.
- **Committed datatypes ✓**: a shared Datatype message (flag `0x02`) whose body is a `header.SharedMessage`
  locating the committed type's object header; `DatatypeMessage.resolve` follows it (and any chain) for
  both datasets and attributes. Committed-type objects are a first-class `CommittedDatatype` in the
  object hierarchy (navigable via `group.committedType(name)`), across old- and new-style files.
  The **SOHM heap** form (messages deduplicated through the Shared Message Table, message 15, and the
  fractal heap of each index) is read since P2; its index (a list or v2 B-tree type 7) is not needed to
  read.
- **Milestone:** full read of modern HDF5 (dense links/attrs, vlen, committed types).
- **Acceptance:** listings + attribute values + vlen data match h5py for dense-storage, large-group,
  and shared-datatype fixtures.

### H6 — Advanced read & completeness  ✅ **done** (a few edge cases noted below)
- **Virtual datasets ✓**: layout class 3 + the global-heap mapping block; `VirtualDataset` opens each
  source file (relative to the VDS), reads its selection, and scatters it into the virtual layout,
  honouring the fill value. Selections resolve via a general gather-scatter over enumerated element
  offsets, so **regular hyperslabs incl. strided / multi-block** patterns work (ALL + single-block are a
  special case). Unlimited mappings, both unlimited and printf-style (`%b`), are read since P2, with the
  extent set from the sources as libhdf5 sets it.
- **References ✓**: **object references** (8-byte object-header address → navigable `Hdf5Object` via
  `readObjectReferences()`) and **region references** (global-heap ID → dataset + serialized selection
  → `Selection` via `readRegionReferences()`), on datasets and attributes. The revised (`H5R_ref_t`)
  encoding is read since P2, including attribute references (`readAttributeReferences()`).
- **Object metadata ✓**: **Object Comment (13)**, **Modification Time (18)** + version-2 header-prefix
  time, **Object Reference Count (22)** + version-1 prefix count — via `comment()` /
  `modificationTime()` / `referenceCount()` on every object.
- **Superblock extension ✓** (`Superblock.superblockExtensionAddress`): the extension object header is
  reached and its messages read. **File Space Info (23) ✓** + **free-space managers (FSHD) ✓** via
  `Hdf5File.fileSpaceInfo()` — strategy, page size, threshold, persist flag, end-of-file address, and
  total free space / section count (validated against h5py's `H5Fget_freespace`); the variable-bit-width
  FSSE section list is not decoded (no reader value). **Old modification time (14) ✓** feeds
  `modificationTime()`; **B-tree K Values (19) ✓** and **Driver Info (20) ✓** are public since P2 S6 as
  `Hdf5File.btreeKValues()` / `driverInfo()` (also from a version 0–1 superblock's fields and driver
  information block), tested on files libhdf5 writes with `H5Pset_sym_k`/`H5Pset_istore_k` and the family
  driver. File Space Info version 0 is read since P2 S5.
- **Milestone met:** every object-header message type parses; every structure on a read path is covered.
- **Filter edge cases done ✓**: **float (decimal-scaling) scale-offset** and **compound n-bit** decode,
  and **signed AEC/szip** decode (sign-extended reference/raw samples + signed unmap bounds, validated
  against libaec signed vectors). Threading the datatype's signedness into the szip *filter* to reach
  the signed AEC path on a real file remains (untestable here — szip is disabled in this h5py).
- **Deferred read edge cases:** multi-file drivers (family/multi/split, a non-goal). SOHM shared-message
  dedup (msg 15), unlimited-pattern (printf-style) VDS mappings, and the revised `H5R_ref_t` reference
  encoding, once deferred here, are done (P2).

### H7 — Write path foundations
- **File-space allocation**: end-of-file bump allocator ✓ (`write.GrowBuffer`, append + patch +
  lookup3); free-space manager + aggregators and the File Space Info strategy remain.
- **Superblock (v3, checksummed) ✓**, **object header v2 (checksummed) ✓**, message serialization ✓
  (dataspace, datatype, fill value, contiguous data layout, link info, group info, link, attribute),
  **global-heap writer ✓** (for variable-length strings). Dense-storage heap / B-tree writers remain.
- **Milestone ✓ (exceeded):** `Hdf5Writer` writes a nested group tree with contiguous
  **int32 / float64 / vlen-string** datasets and group/dataset attributes; **verified read-identical by
  Falcon and by h5py**.
- Remaining write breadth: chunked storage + filter *encode*, more datatypes, and old-style formats.

### H8 — Write path breadth (incl. szip encode)  ✅ **all six filters encode**
- **Chunked write ✓** (fixed-array index; boundary chunks fill-padded) + **filter encode ✓** for
  **deflate**, **shuffle**, and **fletcher32** (applied in write order shuffle&rarr;deflate&rarr;fletcher32;
  filtered fixed array, client id 1; layout version 5 for the 8-byte stored-size entry; multi-filter
  pipeline message). `writer.intChunkedDataset(...).shuffle().deflate(level).fletcher32()`.
- **Scale-offset ✓** (`.scaleOffset()`: reserves the all-ones fill code, packs `value − min`) and
  **n-bit ✓** (`.nbit(precision)`: unsigned reduced-precision datatype + significant-bit packing).
- **szip encode ✓** (`.szip()`): a pure-Java CCSDS extended-Rice **encoder** in `filter.Aec` (per-block
  cost-optimal sample-splitting vs uncompressed, no preprocessing), mirroring the decoder. **All six
  built-in filters now encode.** Verified by libaec/imagecodecs decode (h5py szip is disabled here).
- **Compound / enum / object-reference write ✓**: `compoundDataset` (packed named-column records,
  version-5 class-6 message), `enumDataset` (ordered `name -> code` members over a 32-bit base,
  class 8), and `referenceDataset` (class 7; target paths resolved to object-header addresses when the
  file is written, via a pending-patch list that also handles forward references and h5py-compatible
  null refs). All h5py-verified.
- **Array / vlen-sequence / native-complex write ✓**: `float32ArrayDataset` / `int32ArrayDataset`
  (class 10: fixed-shape sub-arrays), `intSequenceDataset` / `doubleSequenceDataset` (class 9 ragged
  arrays via the global heap, element-count ids), and `complexDataset` (class 11 native complex128).
  h5py reads them as subarray / vlen / native-complex dtypes.
- **Dense storage write ✓**: past 8 links/attributes an object switches from compact header messages
  to dense storage &mdash; a fractal heap (single checksummed direct block) of Link/Attribute message
  bodies plus a name-indexed v2 B-tree (type 5 links / type 8 attributes), referenced from a Link Info
  or Attribute Info message. Generic `writeFractalHeap` / `writeV2BTree` helpers; h5py-verified
  (listing, iteration, and lookup-by-name). Indirect-block heaps (very large sets) still throw.
- **Earliest-format write ✓** (`create(path, Format.EARLIEST)`): the original pre-1.8 format &mdash; a
  version-0 superblock reaching the root through a symbol-table entry, symbol-table groups (local heap
  of names + version-1 group B-tree + symbol-table node sorted by name), and version-1 object headers
  (the datatype/dataspace/layout/fill/attribute message bodies are reused unchanged). Object-header
  writing is a shared `List<Message>` framed as v1 or v2 by format; node structures are allocated at
  their fixed sizes. Scope: contiguous datasets, compact attributes, nested groups, **multi-node
  symbol-table groups** (name-sorted children across &le; 256 per group); chunked/filtered/dense throw.
  h5py-verified.
- **More atomics / layout / fill ✓**: signed **int8/16/64**, **float32**, and **fixed-length string**
  datasets; **compact** layout (`.compact()`, data inline in the header); **custom fill values**
  (`.fillValue(long|double)`, exposed on read via `Dataset.fillValueBytes()`). All h5py-verified.
- Remaining write breadth (niche): the last datatype classes (bitfield / opaque / time); indirect-block
  fractal heaps (dense sets over one direct block, i.e. thousands of links/attrs); szip preprocessing +
  zero-block / second-extension encode modes for better compression ratios. (All since done: WF1-WF4,
  WF5, and WF7, below.)
- **Milestone:** round-trip parity across the full fixture matrix.
- **Acceptance:** for every fixture, `Falcon-write → h5py-read` and `h5py-write → Falcon-read` agree on
  structure + data; property-based random round-trips pass.

- **Streaming writes and datatype breadth ✓** (P2 WF1–WF4): raw data streams to the file as it is
  written (files past 2 GB); datasets of any datatype (`createDataset`) grow (`maxShape`, `append`,
  version-1 B-tree index); typed and string attributes; soft and external links; region references.
  libhdf5 2.0 and 1.14 read all of it (`tools/fixtures/check_hdf5_writer.py`).
- **Changing files, and storage of any size ✓** (P2 WF5, WF6, WF8, WF9): `Hdf5Writer.open` changes an
  existing file in place (its own or libhdf5's: objects added, data written, links and attributes
  deleted); dense storage and group B-trees of any size; references in chunks and attributes; data given
  whole written as the next object is added. libhdf5 2.0 and 1.14 read the results and change them
  further.
- **Writer options, and changing files further ✓** (P2 WF7, WF10): szip's nearest-neighbour coding, its
  encoder a port of libaec's (byte for byte its output: zero-block runs, the second extension); user
  blocks; hard links and moves; changing a file now writes into datasets of every built-in filter (with
  libhdf5's own parameters: n-bit, scale-offset, szip of either coding), partial edge chunks kept
  unfiltered, and external raw data; changes attributes in the shared-message table; converts
  original-format groups for external links; returns shrunk dense storage to compact; and journals the
  writes over the file, redoing an interrupted change on the next open.
- **Writing through virtual datasets ✓** (P2 WF11): a write into a virtual dataset of a file being
  changed goes into its sources, as libhdf5's `H5Dwrite` puts it, through every kind of mapping, in the
  same file and in others (each changed in a session of its own); elements no mapping covers are
  refused before anything is written.

### H9 — API polish, performance, docs  (essentially complete)
- **Robustness ✓**: a corrupt-input fuzz test truncates and byte-flips 19 fixtures and forces a full
  read, asserting every failure is a typed `HdfException`/`IOException` &mdash; never a raw runtime
  exception, JVM crash, or hang. Fixes it drove: bounds-checked `HdfBuffer.segmentSlice` (+ overflow-safe
  `checkRange`); `Elements.checkedInt`/`checkedByteCount` reject overflowing/oversized element counts
  before allocating; filter-decode failures on a corrupt chunk are wrapped as `HdfFormatException`, and a
  short decoded chunk is rejected explicitly.
- **Large-file ✓**: a test maps a sparse file and reads a marker past the 2 GB (`Integer.MAX_VALUE`)
  offset boundary that `MappedByteBuffer` can't cross &mdash; validating the FFM `MemorySegment` backend.
- **Performance ✓**: a hyperslab / `blocks()` read of a chunked dataset touches **only the chunks
  overlapping the selection** (`ChunkedReader.assembleSelection`) rather than assembling the whole
  dataset (contiguous selections extract zero-copy), and a per-file **decoded-chunk LRU cache**
  (`io.ChunkCache`, ~16 MB) reuses filter-decode results across reads. Since P2 PF5&ndash;PF7, a small
  read looks its chunks up in the file's chunk index (an array entry, or a B-tree's path) rather than
  reading the whole index; what objects read of themselves is shared by every handle of the same object,
  in a bounded per-file cache; and a virtual dataset reads only the source elements a read needs, not
  the box that bounds them. PF8 copies a selection's elements out of each chunk a run at a time.
- **API ergonomics ✓**: scalar convenience reads (`Dataset.readInt/readLong/readDouble/readString`,
  `Attribute.readInt/…`) and **block streaming** (`Dataset.blocks(rows)` &rarr; `Stream<Selection>`) for
  processing a large dataset without materializing it whole.
- **Typed reads and selections ✓** (P2 A1, A4, A12): every datatype class reads into Java values
  (compound members by name, enumeration names, complex pairs, bit fields, opaque bytes, time as
  `Instant`), and strided and
  point selections read only the chunks they touch, with every reader a dataset has.
- **CI ✓**: GitHub Actions builds + tests the reactor on **JDK 25** (fixtures are committed and hermetic,
  so no HDF5/h5py at build time). **Docs ✓**: README usage examples + a standalone
  [user guide](USER_GUIDE.md).
- Remaining (optional polish): mmap-tuning benchmarks / a perf-regression harness.
- **Milestone essentially met:** the module is 1.0-ready &mdash; read-complete, write-broad, hardened
  against corrupt input, >2 GB-capable, documented, and CI-gated.

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
- **Status (H4):** the pure-Java `filter.Aec` decoder is implemented and **validated byte-for-byte
  against libaec** (via the `imagecodecs` Python package) across all coding modes — split,
  uncompressed, zero-block, second extension, ±preprocessing, 8/16/32-bit — using 84 committed
  reference vectors, plus 5 szip-chunk vectors through the filter. Scope: **unsigned** samples;
  signed-integer szip (libaec `DATA_SIGNED`) and the szip **encoder** (H8) remain. Reference vectors
  are regenerated by `tools/fixtures/gen_aec_vectors.py`; the codec was reverse-engineered and
  validated against libaec because this environment's HDF5/h5py build ships szip disabled.

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
| 7 | External Data Files | H6 | 19 | B-tree 'K' Values | H6 |
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
| Contiguous — external raw files (External File List) | **H6 ✓** |
| Chunked — v1 B-tree (type 1) | H4 |
| Chunked — single-chunk / fixed array / extensible array / v2 B-tree (v4/v5 layout) | **H5 ✓** |
| Chunked — implicit index (v4/v5 layout, type 2) | **H6 ✓** |
| Chunked — deep v2 B-trees (BTIN) / paged EA data blocks (very large sets) | **H5 ✓** |
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

## 12. Falcon Phase 2 — Zarr (built)

The `zarr` module (`com.ebremer.falcon.zarr`) is now built: it reads Zarr **v3** and **v2** and writes
**v3** — the store/group/array hierarchy, `zarr.json`/`.zarray`/`.zattrs` metadata, chunk grids and key
encoding, and the codec pipeline (bytes, transpose, blosc-family, gzip/zstd, crc32c, sharding,
vlen-utf8 strings), all pure-JDK with zero runtime dependencies. Its roadmap and remaining work live in
[`../zarr/PLAN.md`](../zarr/PLAN.md) and [`../zarr/TODO.md`](../zarr/TODO.md). A survey during Phase 2
found no shared *model* worth extracting into a `com.ebremer.falcon.core` module (data types, byte I/O,
checksums, and chunk indexing are all format-specific); it is deferred — see `../zarr/PLAN.md` §10.

## 13. Decisions locked (from review)

1. **HDF5 packages** under `com.ebremer.falcon.hdf5` (module `com.ebremer.falcon.hdf5`). ✅
2. **Multi-module Maven:** parent `falcon` (pom) → module **`hdf5`** (jar); Zarr later as `zarr`. ✅
3. **Git author display name:** `Erich Bremer <erich@ebremer.com>` (no co-author trailers). ✅
4. **License:** Apache-2.0 (`LICENSE` added; POMs declare it). ✅
5. **SZIP:** in scope — pure-Java CCSDS 121.0 decoder (H4) + encoder (H8). ✅
6. **Priority:** reader before writer. ✅
7. **Zarr:** Falcon Phase 2, pinned. ✅
8. **Conformance oracle:** h5py 3.16.0 / HDF5 2.0.0 (installed). ✅
9. **I/O backend:** FFM `MemorySegment`; bytes in memory and on-demand `RangeReader` sources since P2 A6. ✅
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
