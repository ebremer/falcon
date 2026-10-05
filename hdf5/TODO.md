# Falcon HDF5 — remaining work (prioritized)

**Status (2026-10-05, after P2 S1–S7, A2, A3, A5, A6, and PF1–PF4):** build green, **610 HDF5 tests**
(144 at the review, 187 after the top 10, 206 after P0, 228 after P1, 243 after S1–S3, 256 after S4–S7,
439 after A2–A6), plus 33 in the `core` module. The review's top 10, every P1 item, **P2 S1–S7**,
**A2, A3, A5, A6**, and **PF1–PF4** are done (see *Done* at the end). P0 has one item, inherited with the
shared zstd decoder. Falcon now:

- reads the files the review showed it misreading:
  - real libhdf5 szip and scale-offset data;
  - every chunk index shape (maximum dims, layout v4, paged and sparse arrays, filtered single chunks,
    unwritten datasets);
  - user-block (MATLAB v7.3) files;
  - integers with a bit offset or reduced precision, and non-IEEE floats;
  - unsigned values (never wrapped);
  - virtual-dataset sources in the other byte order;
  - soft and external links (old- and new-style groups);
  - fractal-heap huge and tiny objects and nested indirect blocks;
  - every VDS mapping and region-reference selection encoding;
  - shared messages, including SOHM (the shared-message heap);
  - revised references (`H5R_ref_t`);
  - unlimited and printf-style VDS mappings, with libhdf5's source search order, views, and printf gap;
  - the third-party filters LZF, Blosc, LZ4, bitshuffle, and Zstandard, through Falcon Core's codecs;
  - HDF5 1.6.2-era chunked layouts (layout message versions 1 and 2), VAX floats, and File Space Info
    version 0.
- opens files from a path (mapped), from bytes, or through a `RangeReader` (an object store, HTTP
  ranges, any channel) that it reads on demand;
- looks objects up by path, reads integers as floating point as libhdf5 converts them, and reports each
  dataset's layout, chunk shape, filters, and storage size as libhdf5 does;
- reads only what a read needs:
  - chunks are looked up by coordinate in an index read once per dataset;
  - virtual-dataset selections read only the parts of the sources they map to;
  - names are found through the name indexes (see `BENCHMARKS.md`);
- writes files that **HDF5 2.0 and 1.14 read**, checked by `tools/fixtures/check_hdf5_writer.py`. The
  final run read 83/83 objects with HDF5 2.0 and 78/78 with 1.14.6. The P0 edge-case files written by
  the previous writer fail 19 objects under each version.
- writes atomically (temp file, then move), can be aborted, and validates input when it is added;
- verifies metadata and fletcher32 checksums, rejects loops and runaway sizes in corrupt files, survives
  fuzzing under a 128 MB heap and 256 KB stack, confines external files to the HDF5 file's directory by
  default, and supports concurrent reads of one open file.

P1 is empty, and P0 holds only Z6/Z7, the zstd decoder's defects. What remains is features and API (P2)
and docs and build (P3).

**Behaviour changes in P2 PF1–PF4** (pre-1.0; no API change):
- `Hdf5Object.attributes()` returns an unmodifiable list, read once per handle. It used to return a new
  mutable list on each call.
- A `Dataset` handle keeps its chunk index, and a virtual dataset's source datasets, for later reads.
- **Source files stay open:** the files a virtual dataset reads stay open until its file closes, as in
  libhdf5. They used to be opened and closed on every read.
- **Corrupt chunk indexes fail:** a chunk at a misaligned offset, or two chunks at the same one, is now a
  format error. Only a corrupt index makes them, and libhdf5 would not find them either.
- **Writer:** `Hdf5Writer` (EARLIEST format) orders symbol tables by UTF-8 bytes, as libhdf5's `strcmp`
  does, so libhdf5 and Falcon find every name by lookup.

**API changes in P2 A2, A3, A5, A6** (pre-1.0):
- **Numeric reads:** `readDoubles()` and `readFloats()` (on `Dataset`, `Attribute`, `Selection`, and
  vlen sequences) accept integer data, converted as libhdf5 converts it. They used to throw.
- **Storage:** `Dataset.layout()` (the new `Dataset.Layout` enum), `chunkShape()`, `filters()` (the new
  `Filter` record), and `storageSize()`.
- **Paths:** `Group.child`, `link`, `group`, `dataset`, and `committedType` take paths. A plain name works
  as before; a failed lookup's message names the component that failed.
- **Opening:**
  - `Hdf5File.open(byte[])` and `open(RangeReader)`, each with an `OpenOptions` overload;
  - the new `RangeReader` interface, with `RangeReader.of(SeekableByteChannel)`;
  - `Hdf5File.path()` is `null` for those. Without a directory of its own, such a file opens no other
    file unless `allowDirectory(...)` or `unrestricted()` lets it.
- **Behaviour:** a selection of contiguous data reads only the selected runs, so it also works on
  datasets of more than 2³¹ elements.

**API changes in P2 S4–S7** (pre-1.0):
- **Opening:** `Hdf5File.open(path, OpenOptions)`. `OpenOptions` holds the `ExternalFileAccess` policy,
  `virtualView` (`LAST_AVAILABLE` / `FIRST_MISSING`), and `virtualPrintfGap`.
- **New `Hdf5File` accessors:**
  - `btreeKValues()`, returning the new `BTreeKValues` record (libhdf5's defaults when the file records
    none);
  - `driverInfo()`, returning `Optional<DriverInfo>`.
- **`Datatype.FloatingPoint`** gains a `vaxOrder` component. The 11-argument constructor remains.
- **Dependencies:** the module now depends on Falcon's `core` (`requires com.ebremer.falcon.core`), and
  `mvn -pl hdf5` needs `-am`.

**API changes in P2 S1–S3** (pre-1.0):
- References:
  - new `readAttributeReferences()` on `Dataset` and `Attribute`, and `Attribute.readRegionReferences()`;
  - `readObjectReferences()` and `readRegionReferences()` also read revised references;
  - `read()` returns `Hdf5Object[]` for revised references;
  - `Attribute.read()` returns `Selection[]` for region references.
- Virtual datasets:
  - `Dataset.dataspace()` of a virtual dataset with unlimited mappings takes the extent from its sources.
    It may open them, and fails under a policy that refuses them.
  - A missing source dataset in an existing file reads as the fill value, as in libhdf5. It used to
    throw `HdfFormatException`.
  - Sources are looked for in libhdf5's order: an absolute name falls back to its file name, and
    `unrestricted()` also tries the working directory.

**API changes in the P0 pass** (pre-1.0):
- `read()` returns `long[]` for `uint32` and `BigInteger[]` for `uint64`.
- `readInts()` and `readLongs()` throw on a value that does not fit, instead of wrapping it.
- `readInts()` now accepts `int64` data whose values fit in an `int`.
- `Datatype.FloatingPoint` gains `signLocation` and `normalization`.
- The writer rejects bad names, out-of-range fill values, and n-bit values when they are added.

**API changes in the P1 pass:**
- Links:
  - new `Link` (`Hard`, `Soft`, `External`, `UserDefined`) and `Group.links()` / `link(name)`;
  - `childNames()` now names every link;
  - `children()` and `child()` follow soft links.
- `Hdf5File`:
  - `Hdf5File.open(path, ExternalFileAccess)`, with `sameDirectory()` as the default;
  - `isOpen()`, and `close()` is idempotent;
  - a closed file throws the new `HdfClosedException`.
- `Selection.isRectangular()` and `elementCount()`; a region reference may now be points or blocks.
- `Datatype.ReferenceKind`: `ATTRIBUTE` is replaced by `REVISED_OBJECT`, `REVISED_DATASET_REGION`, and
  `REVISED_ATTRIBUTE`.
- `Hdf5Writer`:
  - new `abort()` and `isOpen()`; `close()` writes atomically and can be retried after a failure;
  - adding after close throws `HdfClosedException`;
  - chunk shapes are validated when added;
  - `EARLIEST` datasets accept any number of attributes.

How to read this list:
- **P0** — silent wrong data, or files other HDF5 tools reject or misread. Fix before any release.
- **P1** — valid files that fail to read; crashes, hangs, or OOM on corrupt input; thread safety; the
  test gaps that let P0 through.
- **P2** — features and API.
- **P3** — docs, build, housekeeping.

Each item gives the location, the failure, and the fix. "✔" means the failure was reproduced during the
review; anything else comes from code reading or the spec. Line numbers are as of commit `a887633`, the
review baseline. Abbreviations: `W` = `Hdf5Writer.java`; other paths are under
`src/main/java/com/ebremer/falcon/hdf5/`.

**Tooling.**
- Regenerate fixtures with `tools/fixtures/gen_fixtures.py`. Pass names to regenerate only those. Then
  rerun `tools/fixtures/gen_storage_metadata.py`, which records libhdf5's storage report for every
  dataset (the oracle for `Dataset.layout()`, `chunkShape()`, `filters()`, and `storageSize()`).
- Check writer interop with `tools/fixtures/check_hdf5_writer.py [--python114 PATH]`.
- The Python environment is pinned in `tools/fixtures/requirements.txt`.

## Next up — top 10

1. **P0 Z6/Z7 — the shared zstd decoder accepts corrupt frames.** HDF5's zstd filter (and Blosc's
   internal zstd) inherit this. Fix it once in core (see P0 below).
2. **A1 — typed reads for compound, enum, array, and complex.** The writer produces them, but `read()`
   cannot return them.
3. **WF1 — streaming writes.** The writer builds the whole file in memory, which caps it at about 2 GB
   and rules out append and resizable datasets.
4. **WF2 — datatype breadth:**
   - string attributes;
   - unsigned integers;
   - chunking for every type.
5. **A4 — selections:**
   - strided and point selections in `select`;
   - vlen readers on `Selection`.
6. **A8 — internal types out of the public API.** `Attribute`'s public constructor exposes `FileContext`;
   pre-1.0 is the time to fix it.
7. **D1/D3 — docs that overclaim, and a stale PLAN.md.**
8. **B1/B2 — CI and release plumbing:** a Windows CI leg, source and Javadoc jars, and the enforcer.
   New plugins need Erich's approval.
9. **A9/A10 — remote files, continued:** other files of a remote file (external raw data, VDS sources)
   through the reader, and paths that cannot be mapped.
10. **PF5 — chunk lookups without reading the whole index,** for very large or remote datasets.

---

## P0 — silent wrong data / files libhdf5 rejects

A new P0 is any silent wrong value, or any written file that libhdf5 rejects or misreads. The earlier
items are done (see *Done — 2026-10-05*).

- [ ] **Z6/Z7 — corrupt zstd chunks can decode to wrong data.** Since S4, HDF5's zstd filter (32015) and
  Blosc's internal zstd use the zstd decoder in `core`. The Zarr review (`../zarr/TODO.md` Z6/Z7) found it
  accepts corrupt frames:
  - the XXH64 content checksum is not verified;
  - bitstreams are not checked for exact consumption;
  - the declared content size is not compared with the output;
  - frames after the first are ignored.

  In HDF5 a chunk that decodes to the wrong size still fails. One that keeps its size reads wrong
  values unless the dataset also has fletcher32. Fix it once, in core, as Z6/Z7 describe.

## P1 — valid files that fail; hardening; concurrency; test gaps

Empty: every item is done (see *Done — 2026-10-05 (P1)*).

## P2 — features, API, performance

### Read features

- S1–S7 are done (see *Done — 2026-10-05 (P2: S1–S3)* and *(P2: S4–S7)*). Still open around them:
  - [ ] **S8 — writing the third-party filters.** Falcon reads LZF, Blosc, LZ4, bitshuffle, and zstd. The
    writer cannot apply them yet, though core has zstd and Blosc encoders.
  - [ ] **S9 — more registered filters:** Blosc2 (32026), bzip2 (307), ZFP (32013), and SZ (32017).
    Each needs a pure-Java codec.

### Read API

- [ ] **A1 — typed reads for compound, enum, array, complex, bitfield, and opaque.**
  - **Gap:** `read()` throws Unsupported for all of these, although the writer produces compound, enum,
    array, and complex. USER_GUIDE implies all atomic types map to Java arrays.
  - **Add:** compound field accessors (by member name), enum names, complex pairs, and raw opaque bytes.
- A2, A3, A5, and A6 are done (see *Done — 2026-10-05 (P2: A2, A3, A5, A6)*).
- [ ] **A4 — selections:**
  - strided and blocked hyperslabs and point selections in `select`;
  - raw, vlen, and reference readers on `Selection` (`readStrings` on vlen throws today).
- [ ] **A7 — configurable cache sizes.** Each is fixed:
  - the decoded-chunk cache at 16 MB (`io/ChunkCache.java`);
  - a `RangeReader`'s page size (64 KiB) and page cache (16 MiB, `io/PagedSource.java`). Larger pages suit
    high-latency stores.
- [ ] **A8 — remove internal types from public signatures.** `Attribute`'s public constructor exposes the
  non-exported `io.FileContext`. A dereferenced object reports `path()` as `""` and prints `Dataset[/]`.
- [ ] **A9 — a `Path` that cannot be mapped.** `Hdf5File.open(Path)` fails on a file system whose channels
  do not map (a zip file system, an in-memory one) with `UnsupportedOperationException`. Fall back to
  on-demand reads through `RangeReader.of(Files.newByteChannel(path))`. Until then, callers can do that
  themselves.
- [ ] **A10 — other files of a remote file.** External raw data and virtual-dataset sources are opened
  as local paths only, so a file read through a `RangeReader` reaches them only on local disk. Add an
  `OpenOptions` resolver from a name to a `RangeReader`, under the same policy ideas.

### Write features & API

- [ ] **WF1 — streaming writes.** Write raw data straight to a `FileChannel` (`long` offsets, positional
  header patches) instead of building the whole file in memory (see W12). This enables:
  - files over 2 GB;
  - append, and resizable datasets with `maxdims` / unlimited dimensions;
  - chunk-by-chunk writes (`writeChunk` / `Selection.write`).
- [ ] **WF2 — datatype breadth:**
  - string attributes (`units`, CF conventions — the most-missed one);
  - unsigned uint8/16/32/64 (`byteDataset` is signed, which blocks image data);
  - bool and big-endian types;
  - chunking for all types, not just int32/float64;
  - opaque / bitfield / time. (carried over)
- [ ] **WF3 — a generic `createDataset(Datatype, Dataspace)`** plus a typed writer. Today about 20
  per-type methods are duplicated across `Hdf5Writer`, `GroupWriter`, and `DatasetWriter`, and the
  surface grows with every type.
- [ ] **WF4 — links and references:** soft and external links; region references.
- [ ] **WF5 — indirect-block fractal heaps** for dense sets beyond one direct block. (carried over; W2
  must come first)
- [ ] **WF6 — open an existing file for modification:** add, overwrite, and delete.
- [ ] **WF7 — lower-priority options:**
  - a user-block option;
  - szip better-ratio modes (NN preprocessing, zero-block, second extension) — carried over;
  - ~~sort EARLIEST symbol tables by UTF-8 bytes (`strcmp`), not UTF-16~~ — done in PF3, which looks
    names up by that order;
  - ~~set the UTF-8 cset on link and attribute names~~ — done in the P0 pass (LATEST format).

### Performance

- PF1–PF4 are done (see *Done — 2026-10-05 (P2: PF1–PF4)*). Still open around them:
  - [ ] **PF5 — look chunks up without reading the whole index.** PF1 reads a dataset's whole chunk
    index on its first read, about 32 bytes kept per chunk.
    - **Cost:** for a dataset of millions of chunks, or a remote one through a `RangeReader`, the first
      small read pays for the whole index.
    - **Fix:** each index type can find one chunk directly:
      - an implicit, fixed-array, or extensible-array index by arithmetic on the chunk's linear index;
      - a v1 or v2 B-tree by descending its keys.
  - [ ] **PF6 — share per-object caches between handles.** Each `Dataset`, `Group`, or attribute list is
    cached per handle, and `group.dataset("x")` returns a new handle each time. A per-file cache keyed by
    object-header address would share them. Bound it, since a file can hold millions of objects.
  - [ ] **PF7 — virtual mappings that scatter.** A virtual selection reads the bounding box of the source
    elements it needs. For regular mappings that is about what it needs. For a mapping whose virtual and
    source shapes differ, or whose source is strided, the box can be much larger than the elements:
    read those by runs.

## P3 — docs, build, housekeeping

- [ ] **D1 — fix docs that overclaim.**
  - `PLAN.md`: "1.0-ready", "read back identically … by h5py", "szip verified via libaec", and "every
    structure on a read path is covered".
  - README: "corrupt input never … returns wrong data".
  - ~~USER_GUIDE.md:125 "filters apply in call order"~~ — now true (W11).
  - ~~Javadoc of `readRawBytes`: says "not yet de-filtered"~~ — fixed.
- [ ] **D2 — USER_GUIDE gaps.**
  - **Done:** thread safety, filter rules, the writer's HDF5 1.10+ compatibility, and checksum
    verification are now documented.
  - **Also done (P0 pass):** unsigned and non-native numeric reads, VDS type rules, writer names and
    limits, typed fill values, and the EARLIEST message versions.
  - **Also done (P1 pass):** links, the external-file policy, closed files, region-reference selections,
    and the writer's lifecycle (atomic close, `abort()`, retry).
  - **Also done (P2 S1–S3):** revised references, unlimited and printf-style VDS, and where VDS sources
    are looked for.
  - **Also done (P2 A2–A6):** other sources (bytes, `RangeReader`), paths, integers as floating point,
    and storage metadata.
  - **Also done (P2 PF1–PF4):** what reads keep per handle and per file, and `BENCHMARKS.md`.
  - **Still to document:** the writer's memory use (WF1).
- [ ] **D3 — PLAN.md is stale.**
  - §6 lists the non-existent `dataspace` and `util` packages, omits `data`, `index`, and `group`, and
    says only one package is exported (`datatype` is exported too).
  - §7's API sketch uses the old method names.
  - §2 still anticipates promoting code to `falcon.core`, contradicting the deferral.
  - ~~§5 claims a ByteBuffer fallback~~ — §5 now describes the `RangeReader` sources (A6).
- [ ] **D4 — Javadoc lint:** 318 `-Xdoclint:all` warnings, including 78 undocumented public members
  (28 in `Hdf5Writer`). 0 errors.
- [ ] **B1 — CI: add a `windows-latest` leg** (mmap and file-deletion semantics differ). Repo-wide.
- [ ] **B2 — release plumbing.**
  - **Add:** source and Javadoc jars, `maven-enforcer` (JDK 25 / Maven 3.9 / banned dependencies),
    `project.build.outputTimestamp` for reproducible builds, and coverage (JaCoCo).
  - **⚠ Approval:** every new Maven plugin needs Erich's approval under the dependency gate.
- [ ] **B3 — housekeeping:** `tools/fixtures/__pycache__/*.pyc` is committed and not ignored. Remove it
  and add `__pycache__/` to `.gitignore`. Repo-wide.
- [ ] **D6 — repo-wide staleness.**
  - `CLAUDE.md` still calls Zarr "Planned / pinned … Do not start it until asked".
  - The root `pom.xml` description says "a zarr module is planned".

  Editing CLAUDE.md is Erich's call.

## Done — 2026-10-05 (P2: PF1–PF4)

Measured with the new `Benchmarks` before and after on one machine (`BENCHMARKS.md`):
- 1,000 small chunked selections: 13× faster;
- one-element virtual selections: 93–127× faster;
- lookups by name on new handles: 25–75× faster;
- whole reads and listings: unchanged within run-to-run noise.

- [x] **PF1 — chunk index read once per dataset, looked up by coordinate.**
  - **The index.** `data/ChunkIndex` keeps every stored chunk compactly, sorted by grid position: about
    32 bytes per chunk.
  - **Lookups.** A selection binary-searches each grid cell it covers, or, when it covers more cells than
    there are chunks, makes one pass over the chunks. A whole read uses the chunks within the extent.
    `storageSize()` uses the same index.
  - **Corrupt indexes.** A misaligned or duplicated chunk offset fails as a format error.
  - **Tests:** `ChunkIndex` against a brute-force scan (200 random sparse grids, 4,000 queries). Every
    chunked and virtual fixture dataset (148) is read in 12 random boxes and in `blocks()`, and
    each must match the same part of a whole read.
- [x] **PF2 — virtual datasets read lazily.**
  - **Pairing by position.** `data/SelectedElements` gives a selection's elements by their position in
    iteration order. A regular hyperslab or "all" is kept as per-dimension runs, so position and
    coordinate convert by arithmetic, and the elements inside a box cost only what the box holds.
  - **Selections.** A virtual selection skips mappings that miss the box, before looking for their
    sources. It pairs each virtual element in the box with its source element, and reads the source's
    bounding box of those through the source's own selection reads.
  - **Kept for later reads:**
    - each `Dataset` keeps its parsed mappings and the sources it found;
    - the printf sources found when the extent was set are reused for reading, so the two agree;
    - source files stay open in a per-file `SourceFiles` until the file closes;
    - a missing source is looked for again on the next read, as libhdf5 does.
  - **Tests:**
    - a corrupt second source fails only the selections that reach it;
    - sources survive repeated reads, and a closed file refuses them;
    - every virtual fixture dataset matches its whole read box by box.
- [x] **PF3 — lookups through the name indexes.**
  - **`attribute(name)`:** a dense attribute set is searched through its name-hash v2 B-tree (type-8
    records).
  - **`Group.link(name)`** (and so `child`, `group`, `dataset`, and paths):
    - a dense group through its type-5 name-hash B-tree;
    - an old-style group by descending its v1 B-tree of names and binary-searching the symbol-table
      node, as libhdf5's `H5G__node_found` does;
    - a compact group from its header.
  - **The search.** `BTreeV2.find` descends only the children whose key range can hold the hash, so
    colliding names are all found.
  - **Caching.** Once a handle has read its full lists, lookups use them (by a map); `attributes()` is
    read once per handle.
  - **Writer order.** Falcon's EARLIEST writer now orders symbol tables by UTF-8 bytes (`strcmp`). Before,
    a supplementary character sorted as UTF-16 does, and lookups, libhdf5's and now Falcon's, could miss
    it. libhdf5 2.0 and 1.14.6 find all 67 names of a Falcon-written test file.
  - **Fixtures.** `dense_big.h5` (20,000 dense links, 3,000 dense attributes) and `oldstyle_big.h5`
    (5,000 links, a multi-level B-tree) are new. Both hold non-ASCII names whose UTF-8 and UTF-16 orders
    differ.
  - **Tests:** every link and attribute of 13 fixtures is found on a new handle, and missing names are
    not. A dense-group lookup through a `RangeReader` reads under a third of the bytes the listing reads.
    The old-sort regression is caught.
- [x] **PF4 — benchmark harness.** `Benchmarks` (opt-in, `-Dfalcon.bench=true`, as in Zarr) times whole,
  partial, streaming, virtual, lookup, and remote reads. `BENCHMARKS.md` gives the before-and-after
  table. Deterministic guards run in every build: `PerformanceTest`'s byte counts, and the
  lookup-not-listing check.
- **T3:** the fuzz test also looks names up on new handles, reads a one-element box of every dataset
  (chunk lookup and lazy virtual read), and covers `oldstyle_big.h5` (126 s against a 300 s limit).

## Done — 2026-10-05 (P2: A2, A3, A5, A6)

- [x] **A2 — integers read as floating point.**
  - **What:** `readDoubles()` and `readFloats()` read integer data on `Dataset`, `Attribute`, `Selection`,
    and vlen sequences. They convert as libhdf5 converts to `H5T_NATIVE_DOUBLE` and `H5T_NATIVE_FLOAT`:
    exact up to 53 (24) significant bits, otherwise rounded once to nearest, ties to even.
  - **Unsigned 64-bit values** of 2⁶³ or more are halved with a sticky bit before the one rounding. So
    `float` results are not rounded twice (through `double`), which would get 2⁶³ + 2³⁹ + 1 wrong.
  - **Tested:** `conversions.h5` (new) holds every integer width in both byte orders with edge values, and
    libhdf5's own conversions as attributes; 2.0 and 1.14.6 agree on all of them. Also checked: the bit-offset
    and reduced-precision integers of `numeric.h5`, attributes, selections, and vlen sequences.
- [x] **A3 — storage metadata:** `Dataset.layout()`, `chunkShape()`, `filters()`, and `storageSize()`.
  - **Filters** carry the id, the stored name (from either pipeline message version), libhdf5's name for a
    built-in filter otherwise, whether the filter is optional, and the client data.
  - **Storage size** follows `H5Dget_storage_size`:
    - chunked: the stored size of every chunk in the index, over all six index types;
    - contiguous: its size once allocated, external data included;
    - compact: its size;
    - virtual: 0.
  - **Tested** against libhdf5's report on every dataset of every fixture (952 datasets, from
    `tools/fixtures/gen_storage_metadata.py` → `storage_metadata.txt`). The only allowance is for szip:
    this libhdf5 build lacks it, so it names the filter "Unknown library filter".
- [x] **A5 — paths.** Every `Group` lookup takes a path, as libhdf5's functions do:
  - relative, or absolute from the root;
  - repeated and trailing slashes ignored, `.` for the group itself;
  - soft links followed along the way, and the object named by the path taken.

  `group`/`dataset`/`committedType` report which component failed and why (missing, not a group,
  dangling soft link, external link). The VDS source lookup now uses it.
- [x] **A6 — other sources.**
  - **Bytes in memory:** `Hdf5File.open(byte[])` reads them in place.
  - **On demand:** `Hdf5File.open(RangeReader)`, with `RangeReader.of(SeekableByteChannel)` (positional
    reads for a `FileChannel`, serialized ones otherwise). `HdfBuffer` now reads either a segment or a
    `PagedSource`:
    - metadata comes from 64 KiB cached pages;
    - reads of a page or more go to the reader directly;
    - reader failures are `UncheckedIOException`, or the `IOException` of `open`.
  - **Contiguous selections** read only the selected runs (folding trailing whole dimensions into longer
    runs). That benefits mapped files too, and works past 2³¹ elements.
  - **Other files:** a file without a path has no directory of its own. `ExternalFileAccess` then allows
    only `allowDirectory(...)` directories, or, under `unrestricted()`, the working directory (as libhdf5
    does for a file in memory). Paths are now resolved against the file's absolute directory.
  - **Tests (`OpenSourcesTest`):**
    - every fixture reads identically mapped, from bytes, through a `FileChannel`, and through a
      non-file channel with short reads;
    - a 2 × 2 selection plus one chunk of a 16 MiB file fetches 258 KiB in 5 calls;
    - parallel `blocks()` reads through each reader type;
    - reader failures, short reads, closed files, user blocks, and the external-file policy without a
      path.
  - **Fuzzing:** `RobustnessTest` also reads every truncation and every fifth byte flip through a
    `RangeReader`. It calls the new storage accessors on every dataset, and covers `conversions.h5`.

## Done — 2026-10-05 (P2: S4–S7)

Fixtures come from libhdf5 2.0 through h5py and hdf5plugin, with libhdf5 as the oracle for each. Where
no current libhdf5 writes a form, libhdf5 writes the file and `gen_fixtures.py` rewrites the one message
in place:
- layout versions 1 and 2, in the v1 object header;
- File Space Info version 0, re-checksummed with a Python lookup3.

libhdf5 2.0 and 1.14.6 then read each rewritten file back correctly.

- [x] **S4 — third-party filters, through a new `core` module.**
  - **The module.** By Erich's decision, Zarr's pure-Java zstd and Blosc code (with BloscLZ, Snappy, LZ4,
    and the shuffles) moved to `core` (`com.ebremer.falcon.core`, exported only to `hdf5` and `zarr`).
    Its unit tests, vectors, and fuzzing moved with it. It adds:
    - an LZF decoder;
    - the bitshuffle library's blocked and LZ4/zstd forms;
    - output limits for zstd and LZF;
    - one exception pair, `CompressionFormatException` and `UnsupportedCompressionException`.
  - **The filters.** `filter/ThirdPartyFilters` frames each one as its reference plugin does:
    - LZF 32000 (h5py);
    - Blosc 32001 (hdf5-blosc);
    - LZ4 32004 (H5Zlz4: big-endian sizes, raw blocks);
    - bitshuffle 32008 (bshuf_h5filter: element size, block size, LZ4 or zstd);
    - Zstandard 32015.

    Every declared size is checked against the chunk before anything is allocated.
  - **Hardening found on the way.** The stricter core fuzz test accepts only typed exceptions, and it
    found leaks the Zarr test had accepted:
    - zstd read block-header bytes past the block end;
    - zstd's Huffman weight count was off by one;
    - Blosc, BloscLZ, Snappy, and LZ4 had `int` overflow in bounds checks;
    - Blosc's block table could overflow, and a block could be larger than its buffer.

    All are fixed; zstd and Blosc ran 2.1M and 160K mutated streams clean.
  - **Tests:**
    - `PluginFiltersTest`: 20 datasets, against unfiltered copies, including chunks a filter skipped.
    - core's `LzfTest` and `BitshuffleTest`, against liblzf and bitshuffle output
      (`tools/fixtures/gen_core_vectors.py`), and `CompressionRobustnessTest`.
- [x] **S5 — older forms.**
  - **Layout messages of versions 1 and 2** (`H5O__layout_decode`): chunked storage is a v1 B-tree
    address and the chunk dimensions plus element size after 5 reserved bytes.
  - **VAX floats** (`H5T_VAX_F32`, `H5T_VAX_F64`, datatype version 3+): the 16-bit words are reversed into
    little-endian order, then decoded from the type's fields as libhdf5's `H5T__conv_f_f` does.
  - **File Space Info version 0** (HDF5 1.10.0), mapped as `H5O__fsinfo_decode` does:
    - the old strategies 1–4 become FSM_AGGR (persisting or not), AGGR, and NONE;
    - the six free-space managers of "all, persisting" are followed;
    - the page size and page-end threshold take their defaults.
  - **Tested by** `P2FormatsTest` on `legacy_layouts.h5`, `vax.h5` (against libhdf5's own conversion), and
    `fsinfo_v0_{persist,aggr}.h5` (1312 free bytes, as `H5Fget_freespace` reports).
- [x] **S6 — superblock accessors.** `Hdf5File.btreeKValues()` and `driverInfo()`, from:
  - a version 0–1 superblock: the 'K' fields, and the driver information block;
  - or the extension's messages 19 and 20.

  Like libhdf5, a version 0–1 superblock's "free-space info" slot is now read as the extension address.
  Tested on files written with `H5Pset_sym_k(8, 6)` and `H5Pset_istore_k(64)`, and with the family
  driver (1 MiB members), in both superblock generations.
- [x] **S7 — VDS views and printf gap,** as `OpenOptions.virtualView` and `virtualPrintfGap`:
  - first missing takes the shortest unlimited mapping, up to where its next block would start;
  - a printf mapping there ends at its first missing source, whatever the gap;
  - mappings that reach further are cut, mid-block if need be;
  - the gap lets the printf search skip missing sources, which read as fill.

  Tested by `P2FormatsTest.virtualDatasetViewsAndPrintfGaps`: three datasets under both views and gaps 0
  and 1, against libhdf5's reading of each (2.0 and 1.14.6 agree on all 24 results).
- **T3:** the fuzz set adds the plugin-filter, legacy-layout, VAX, File Space Info v0, K-value, family,
  and VDS-view fixtures, and reads the new file-level accessors (about 76 s against a 300 s limit).

## Done — 2026-10-05 (P2: S1–S3)

Each fixture comes from libhdf5 2.0 via `gen_fixtures.py`, through h5py's bundled library (ctypes)
where h5py has no API: `sohm` (`sohm.h5`, `sohm_latest.h5`), `revised_refs`, and `vds_unlimited`. HDF5
1.14.6 writes the same revised-reference bytes and reads every VDS case to the same shape and values.

- [x] **S1 — SOHM.**
  - New `header/SharedMessageTable` reads the master table (`SMTB`), found from the superblock
    extension's message 15. It is verified by checksum, and libhdf5's type flags (`1 << message type`,
    confirmed against libhdf5) pick each message type's index.
  - A version-3 shared message of type 1 is read from that index's fractal heap: managed, huge, or tiny.
    A tiny object lives in its heap ID, so `HeaderMessage.buffer()` may be its own; every message parser
    now reads from the message's buffer.
  - Dense attribute records flagged as shared name the SOHM heap, not the object's own heap (P1 looked in
    the wrong heap).
  - Shared datatypes and the dataspaces of attributes go through the same path.
  - **Found on the way:** with creation order tracked, a v2 object-header message header is 6 bytes. A
    4–5 byte gap at a chunk's end was misparsed as a message (`ObjectHeader.java`), which made the root
    group of `sohm_latest.h5` unreadable.
  - Tested by `P2ReadTest.sharedObjectHeaderMessages` (both formats: shared datasets, a tiny scalar
    dataspace, a huge 8000-byte attribute, 13 dense shared attributes, a shared compound type).
- [x] **S2 — unlimited and printf-style VDS mappings.**
  - `Dataset.dataspace()` takes the extent from the sources, as `H5D__virtual_set_extent_unlim` does with
    libhdf5's defaults (last-available view, printf gap 0):
    - unlimited mappings clip the source selection to the source's extent and the virtual one to match;
    - printf mappings (`%b`; `%%` is `%`) map source *b* to block *b* until the first missing source;
    - fixed mappings floor the extent.
  - New `DataspaceSelection` operations: `unlimitedDimension`, `selectedBelow`, `extentSelecting`,
    `clippedOffsets`, `blockOffsets`, `blockEnd`, `highCorner`.
  - Source search, measured against libhdf5 2.0 and 1.14.6:
    - an absolute name is tried, then its file name alone;
    - a relative name is tried beside the VDS, then in the working directory;
    - a relative name with a directory gets no file-name fallback.

    Falcon follows this order within the `ExternalFileAccess` policy, trying the working directory only
    under `unrestricted()`. The TODO's earlier order (CWD before the VDS directory) was wrong.
  - A refused name with no allowed candidate on disk fails rather than filling. Exception: past a printf
    mapping's first source, a refusal ends the search as a missing source does.
  - A missing source dataset fills, as in libhdf5, rather than throwing.
  - Tested by `P2ReadTest.virtualDatasetsReadAsLibhdf5ReadsThem`: ten datasets checked against libhdf5's
    own reading (rows, interleaved columns, printf in file and dataset names, `%%`, a gap, none found,
    floored, moved, and relative with a directory). Also `virtualSourcesThePolicyRefuses` and
    `printfSourceNames`.
- [x] **S3 — revised references.**
  - New `RevisedReference` decodes the disk form (`H5T__ref_disk_*`):
    - a local object reference is stored in the element;
    - everything else is `size · global heap ID` of the encoded reference (token, an optional external
      file name, then a region's `size · rank · selection` or an attribute name);
    - an all-zero element is null.
  - libhdf5 writes every `H5T_STD_REF` datatype as `REVISED_OBJECT`, so elements carry their own kind.
    `readObjectReferences` resolves any kind to its object, and `readRegionReferences` makes a
    non-region element an unresolved selection. New `readAttributeReferences`, and
    `Attribute.readRegionReferences` (which also reads original region references in attributes).
  - A reference into another file names it; Falcon does not follow it.
  - Tested by `P2ReadTest.revisedReferences`: objects, regions (block, points, all, two blocks), and
    attributes; nulls, a mix, an external reference, and an attribute holding references.
- **T3:** the fuzz set adds `sohm_latest.h5`, `refs_revised.h5`, and `vds_unlimited.h5`. It also reads
  the regions and attributes of revised references. Each fuzz pass takes about 80 s against a 300 s
  limit.

## Done — 2026-10-05 (P1)

Every reader fix has an h5py fixture made by `gen_fixtures.py`: `links`, `heap_limits`,
`vds_encodings`, `region_refs`, `sohm`, and `external_paths`.

- [x] **V3/V4 — links.**
  - New public `Link` (`Hard`, `Soft`, `External`, `UserDefined`) and `Group.links()` / `link(name)`.
  - Old-style groups read soft links from symbol-table cache type 2 (the target path is in the local
    heap); before, the whole group was unreadable.
  - Soft links resolve absolute and relative paths, follow chains, and stop after 16 links (libhdf5's
    limit), so cycles reach nothing.
  - External links are listed but not followed; `dataset()` on one names the target file.
  - Tested by `LinksTest`.
- [x] **V5 — fractal heap.**
  - Huge objects, direct or through the huge-object v2 B-tree, as for dense attributes over 4 KiB or
    64 KiB.
  - Tiny objects, kept in the heap ID.
  - Nested indirect blocks (child rows = log2(size) − log2(start × width) + 1), as for a group of 2100
    long-named links, whose heap has 16 rows against 9 direct.
  - Filtered heaps still report Unsupported.
  - Tested by `P1ReadTest.hugeHeapObjectsAndNestedIndirectBlocks`.
- [x] **V6 — VDS encodings.**
  - New `data/DataspaceSelection` reads every selection encoding: hyperslab v1 block lists, v2 and v3
    regular, v3 irregular; points v1 and v2; all; none.
  - Both mapping-block versions: v0 with `"."` for the same file, which Falcon used to resolve to the
    directory and fill silently. And v1 (HDF5 2.0): per-entry flags for a same-file source (0x04) or a
    file or dataset name shared by entry index (0x01/0x02), verified against libhdf5 output.
  - Tested by `P1ReadTest.virtualDatasetsInEveryMappingEncoding`.
- [x] **V7 — region references.**
  - A null reference (address 0) is detected.
  - Points and multi-block selections become a non-rectangular `Selection`, read in libhdf5's order.
  - A bad element becomes a selection that throws when used, so it no longer fails the array.
  - Tested by `P1ReadTest.regionReferencesInEverySelectionEncoding` and
    `oneUnresolvableRegionReferenceDoesNotFailTheOthers`.
- [x] **V8 — External File List.**
  - An `H5F_UNLIMITED` slot reads to the end of its file.
  - Bad offsets or sizes, and bad names, are `HdfFormatException`.
- [x] **V9** — the v1 shared message's address is read after the symbol-table entry's heap offset
  (`H5O__shared_decode`). Tested by `SharedMessageTest`.
- [x] **V10 — shared messages.** `SharedMessage.resolve` follows a shared dataspace, fill value, filter
  pipeline, or attribute (in the header, or flagged in a dense attribute record). An attribute's shared
  dataspace is resolved too. A message in the SOHM heap is reported as Unsupported instead of being
  misparsed (`sohm.h5`).
- [x] **V11** — reference type codes 2–4 are the revised references of datatype v4
  (`REVISED_OBJECT`, `REVISED_DATASET_REGION`, `REVISED_ATTRIBUTE`), and reserved before v4. Tested by
  `DatatypeMessageTest`.
- [x] **V12** — v1 compound members with dimensions read as `Datatype.Array` members. Tested by
  `DatatypeMessageTest`.
- [x] **H6 — external files.** A new `ExternalFileAccess` policy governs External File List and VDS
  sources:
  - **default:** the file's own directory tree;
  - **options:** `allowDirectory(...)` (Falcon's `HDF5_EXTFILE_PREFIX` / `HDF5_VDS_PREFIX`),
    `unrestricted()`, and `none()`;
  - **refusals:** absolute, `..`, and UNC names are refused with Unsupported, and a refused VDS source
    is never silently filled.

  Tested by `P1ReadTest.externalFilesOutsideTheDirectoryAreRefusedByDefault`.
- [x] **C2 — closing.** `close()` is idempotent and there is `isOpen()`. Any read of a closed file's
  objects (links, metadata, data, attributes) throws `HdfClosedException`.
- [x] **C3 — writer lifecycle.**
  - `close()` builds the file, writes a temp file beside the target, and moves it into place
    atomically.
  - A failed close leaves nothing and the writer open for a retry.
  - `abort()` discards; additions after close throw `HdfClosedException`.
  - Unexpected internal errors are wrapped as `HdfException`.
  - Earliest-format limits are checked when added: chunked datasets are refused, and so are more than
    256 children per group. Datasets keep any number of attributes in v1 headers.
- [x] **C4 — chunk shapes.** Checked when added: same rank, each dimension at least 1, no chunked
  scalar, a chunk under 2 GiB. A chunk larger than the dataset stays allowed; libhdf5 reads it.
- **Tests (C3/C4):** `WriterEdgeCaseTest`, plus libhdf5 interop of 12 attributes in both formats.
- [x] **T2** — every fixture listed above.
- [x] **T3 — fuzzing.**
  - The fuzz set adds every new fixture and `ea_paged.h5`.
  - It reads links and region selections, and samples datasets declared over 16 MB.
  - It runs in its own surefire execution (`fuzz`) with `-Xss256k -Xmx128m`. That found and fixed:
    - out-of-memory errors from corrupt dimensions: a contiguous or compact layout's stored size must now
      equal dataspace × datatype, as libhdf5 checks;
    - two raw exceptions: an attribute whose data is shorter than its dataspace needs, and
      unbounded or overflowing selection arithmetic.

## Done — 2026-10-05 (P0)

Each fix is checked against libhdf5: reader fixtures come from h5py, and writer output is read back by
HDF5 2.0 and 1.14.6 (`check_hdf5_writer.py`, files `edges.h5` and `edges_earliest.h5`).

- [x] **R10 — bit offset/precision.** Integers are read from their `bitPrecision` bits at `bitOffset`
  and sign-extended. Any 1–8-byte container works, including a 24-bit integer in 3 bytes. Floats
  outside IEEE binary16/32/64 are decoded from their sign, exponent, and mantissa fields as
  `H5T__conv_f_f` does:
  - the bit offset is not used (libhdf5 ignores it for floats; verified);
  - an all-ones exponent is infinity or NaN;
  - "no normalization" (x87 extended) is a plain fraction.

  `Datatype.FloatingPoint` now carries `signLocation` and `normalization`. VAX order throws Unsupported.
  - **Fixture:** `numeric.h5` holds a 12-bit int at offset 4, a big-endian u12 at offset 3, a 24-bit
    int, a 40-bit int at offset 20, bfloat16, float32-in-6-bytes, and x87. Each carries libhdf5's own
    conversion as an `expected` attribute.
  - **Test:** `NumericTypesTest`.
- [x] **R13 — unsigned values.** `readInts()` and `readLongs()` are exact: a value that does not fit
  throws instead of wrapping.
  - `read()` returns `int[]` when every value of the type fits, `long[]` for `uint32` and `int64`, and
    `BigInteger[]` for `uint64`.
  - Attributes and vlen sequences follow the same rules.
  - **Tests:** `NumericTypesTest.uint32ReadsAsLongAndNeverWraps` and `uint64ReadsAsBigIntegerAndNeverWraps`.
- [x] **R14 — VDS byte order.** A source of the same atomic type in the other byte order is
  byte-swapped. Any other type difference throws Unsupported; libhdf5 would convert it (it clamps a
  `uint32` 4e9 to 2³¹−1). Vlen and reference VDS types also throw Unsupported, because their elements
  point into the source file.
  - **Fixture:** `vds_byteorder.h5`.
  - **Tests:** `VirtualDatasetTest.convertsSourcesInTheOtherByteOrder` and `refusesSourcesOfAnotherType`.
- [x] **W6 — empty datasets.** Written with an undefined address and no global heap (contiguous), or no
  chunk index (chunked).
- [x] **W7 — vlen data.** Global-heap collections are shared by every dataset in the file and written
  last, with vlen ids patched. A new collection starts at 65,535 objects or past 1 MiB, so a dataset of
  70,000 strings and a single 3 MiB string both work. Four tiny vlen datasets now share one 4 KiB
  collection.
- [x] **W8 — message sizes.**
  - Compact data is capped at 65,524 bytes and attribute messages at 65,514; both are checked when added.
  - Every header message is size-checked when written.
  - A dense-storage attribute over 4 KiB stays a managed heap object: the heap's maximum managed-object
    size is raised to fit it, so it is not a "huge" object.
- [x] **W9 — names.** Rejected when added:
  - an empty link name, `"."`, `'/'`, NUL, or a duplicate link;
  - an empty or duplicate attribute name;
  - an empty or duplicate compound field name;
  - an enum member with a duplicate name or value.

  Link names over 255 bytes use a wider length field instead of failing at `close()`. Non-ASCII link
  and attribute names carry the UTF-8 character set.
- [x] **W10 — fill values.** `fillValue(long)` and `fillValue(double)` convert to the dataset's type:
  integers must be whole and in range (and fit n-bit), floats round to the type. Datasets with no numeric
  fill throw `IllegalStateException`.
- [x] **W13 — UTF-8.** Fixed-length strings, compound field names, and enum member names are UTF-8; the
  string type is marked UTF-8 when needed, and truncation keeps whole characters.
- [x] **W14 — n-bit values.** `nbit(p)` rejects data or a fill value that is negative or wider than `p`
  bits.
- [x] **W15 — EARLIEST message versions.** The versions libhdf5's own earliest setting writes:
  - dataspace v1;
  - compound and enum datatypes v1, array datatype v2;
  - fill value v2;
  - attribute v1.

  Checked by an h5py `libver="earliest"` dump.
- **Tests (W6–W15):** `WriterEdgeCaseTest`, with 10 cases, plus the `edges.h5` and
  `edges_earliest.h5` interop files.
- **Tool fix:** `check_hdf5_writer.py` no longer crashes while printing a non-ASCII failure on a Windows
  console.

## Done — 2026-10-04 (the review's top 10)

Each fix landed with the fixture or test that reproduces it.

- [x] **R1 szip decode** (+ **W4** encode) — new `filter/Szip.java` implements libaec's SZ layer: the
  4-byte size header, byte interleaving for 32/64-bit pixels, and padded scanlines. Falcon writes EC
  coding at 8 pixels per block and stores chunks szip cannot shrink unfiltered, as libhdf5 does.
  - Verified by `FilterConformanceTest.szipMatchesLibhdf5` and `SzipFilterTest`.
  - The vectors are byte-identical to HDF5 1.14.4 + libaec output.
  - The old "signed szip at the filter level" item is moot: the SZ layer codes every sample unsigned.
- [x] **R2 scale-offset decode** (+ **W3** encode) — `filter/ScaleOffset.java` ports
  `H5Z__filter_scaleoffset`.
  - **Decode:** `FilterConformanceTest.scaleOffsetMatchesLibhdf5`, 13 datasets including lossy floats,
    each compared with libhdf5's own decoding.
  - **Encode:** byte-identical to libhdf5's chunks (`ScaleOffsetTest`).
- [x] **R3–R7 chunk indexes**
  - **Code:** new `index/ChunkGrid.java` (max-dims linearization plus the EA swizzle); `FixedArray` and
    `ExtensibleArray` derive the entry width and honour paging and page-init bitmaps;
    `DataLayout.Chunked` carries the filtered single chunk's size and mask.
  - **Tests:** `ChunkIndexTest`.
- [x] **R8/R9** — an undefined chunk-index address reads as fill, and a null vlen-string id reads as
  `""`. Tested by `ChunkIndexTest`.
- [x] **R11 (bonus)** — n-bit "no compression needed" is honoured.
- [x] **R12** — the AEC remainder-of-segment stops at the 64-block segment. Tested by
  `AecTest.zeroBlockRemainderOfSegmentStopsAtSegmentBoundary`; the old decoder fails all 6 vectors.
- [x] **V1 user block** — addresses are taken relative to the superblock's actual location, as libhdf5
  does, so a user block prepended after the fact also works. Tested by `UserBlockTest`.
- [x] **V2 (bonus)** — `DONT_FILTER_PARTIAL_BOUND_CHUNKS` is honoured.
- [x] **W1/W2** — the fixed array is paged beyond 1024 chunks, and the dense-storage v2 B-tree leaf is
  sized to its records. Tested by `WriteTest.roundTripPagedFixedArray` and `roundTripLargeDenseStorage`.
- [x] **W5** — compound, enum, and array datatypes are written as v3, and chunked layouts as v4 with the
  1.10–1.14 chunk-size width. Files are readable by HDF5 1.10+; native complex is 2.0-only.
- [x] **W11** — one ordered filter pipeline:
  - filters apply in call order, each at most once;
  - scale-offset and n-bit are integer-only and must come first;
  - szip may only follow shuffle;
  - each chunk carries its own filter mask.
  - **Tests:** `WriteTest.roundTripFilterPipelines` and `rejectsInvalidFilterPipelines`.
- [x] **W12** — `GrowBuffer` growth no longer overflows; past the array limit it throws instead of
  spinning. The real fix is WF1.
- [x] **H1–H5 hardening:**
  - **Checksums:** lookup3 verified on OHDR/OCHK, BTHD/BTIN/BTLF, FRHP/FHIB/FHDB, and FAHD/FADB/pages,
    EAHD/EAIB/EASB/EADB/pages; fletcher32 verified (including the legacy byte-swapped value).
  - **Loops and nesting:** iterative continuation chunks with a visited set; B-trees checked for level,
    visited nodes, and a record budget; datatype nesting capped at 64; VDS nesting capped at 32.
  - **Sizes:** global-heap, local-heap, EA/FA, and szip parameters validated; deflate output bounded by
    the chunk size.
  - **Edges:** API-edge overflow and empty-selection fixes.
  - **Tests:** `HardeningTest` and the widened `RobustnessTest`.
- [x] **C1 concurrent reads** — synchronized chunk cache, safely published lazy metadata, and the
  contract documented. Tested by `ConcurrencyTest`, which fails with `ConcurrentModificationException`
  if the cache synchronization is removed.
- [x] **T1 oracle in the loop** — `WriterInteropExport` plus `tools/fixtures/check_hdf5_writer.py`. An
  opt-in CI job would need Python in CI (ask Erich).
- [x] **T4** — `ConcurrencyTest`.
- [x] **D5** — `tools/fixtures/requirements.txt`. Repo-wide.

## Explicit non-goals (unchanged)

Out of scope by design. The reasoning is in `PLAN.md` §3.

- SWMR concurrent-writer semantics; MPI / parallel I/O.
- The HDF5 high-level APIs (images, tables, dimension scales).
- Multi-file drivers (family / multi / split).
- Byte-for-byte on-disk parity with libhdf5. Note that **semantic** interop with libhdf5 *is* a goal,
  and the P0 writer items are failures of it.

## Beyond this module

The Zarr module's list is [`../zarr/TODO.md`](../zarr/TODO.md). Repo-wide items (B1, B3, D5, D6) are
mirrored there.
