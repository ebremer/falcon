# Falcon HDF5 — remaining work (prioritized)

**Status (2026-10-05, after the P1 pass):** build green, **228 HDF5 tests** (144 at the review, 187
after the top 10, 206 after P0). The review's top 10, **every P0 item, and every P1 item** are done (see
*Done* at the end). Falcon now:

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
  - shared messages (SOHM is reported, not misread).
- writes files that **HDF5 2.0 and 1.14 read**, checked by `tools/fixtures/check_hdf5_writer.py`. The
  final run read 83/83 objects with HDF5 2.0 and 78/78 with 1.14.6. The P0 edge-case files written by
  the previous writer fail 19 objects under each version.
- writes atomically (temp file, then move), can be aborted, and validates input when it is added;
- verifies metadata and fletcher32 checksums, rejects loops and runaway sizes in corrupt files, survives
  fuzzing under a 128 MB heap and 256 KB stack, confines external files to the HDF5 file's directory by
  default, and supports concurrent reads of one open file.

P0 and P1 are empty. What remains is features and API (P2) and docs and build (P3).

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
- Regenerate fixtures with `tools/fixtures/gen_fixtures.py`. Pass names to regenerate only those.
- Check writer interop with `tools/fixtures/check_hdf5_writer.py [--python114 PATH]`.
- The Python environment is pinned in `tools/fixtures/requirements.txt`.

## Next up — top 10

1. **A1 — typed reads for compound, enum, array, and complex.** The writer produces them, but `read()`
   cannot return them.
2. **WF1 — streaming writes.** The writer builds the whole file in memory, which caps it at about 2 GB
   and rules out append and resizable datasets.
3. **WF2 — datatype breadth:**
   - string attributes;
   - unsigned integers;
   - chunking for every type.
4. **S1 — SOHM.** Objects whose messages live in the shared-message heap are reported as unsupported.
5. **S4 — third-party filters:** LZF, blosc, zstd, lz4, and bitshuffle. This needs the `falcon.core`
   decision first.
6. **A4/A5 — selections and paths:**
   - strided and point selections in `select`;
   - vlen readers on `Selection`;
   - `child("a/b")` path lookup.
7. **PF1/PF3 — lookups:** cache each dataset's chunk index; look attributes up by name.
8. **S2/S3 — unlimited-pattern VDS mappings and revised `H5R_ref_t` references.**
9. **D1/D3 — docs that overclaim, and a stale PLAN.md.**
10. **B1/B2 — CI and release plumbing:** a Windows CI leg, source and Javadoc jars, and the enforcer.
    New plugins need Erich's approval.

---

## P0 — silent wrong data / files libhdf5 rejects

Empty: every item is done (see *Done — 2026-10-05*). A new P0 is any silent wrong value, or any written
file that libhdf5 rejects or misreads.

## P1 — valid files that fail; hardening; concurrency; test gaps

Empty: every item is done (see *Done — 2026-10-05 (P1)*).

## P2 — features, API, performance

### Read features

- [ ] **S1 — SOHM shared-message deduplication** (message 15 + its fractal heap). (carried over)
  - Now producible: the review generated a SOHM file with h5py via `H5Pset_shared_mesg_index`.
- [ ] **S2 — unlimited-pattern (printf) VDS mappings.** (carried over)
  - **Also:** `%%` unescaping, and libhdf5's source search order: `HDF5_VDS_PREFIX`, then the CWD, then
    the basename in the VDS directory (`VirtualDataset.java:217-223`). Today a VDS moved with its
    sources reads as fill.
- [ ] **S3 — the revised `H5R_ref_t` reference encoding** (HDF5 1.12+). (carried over; see V11)
- [ ] **S4 — third-party filters common in the wild:**
  - LZF (32000, built into h5py);
  - bitshuffle (32008);
  - blosc (32001);
  - zstd (32015);
  - lz4 (32004).

  The Zarr module already has pure-Java zstd, blosc, and lz4. Reusing them reopens the
  `falcon.core` question (see `../zarr/PLAN.md` §10), so decide that deliberately rather than copy the
  code.
- [ ] **S5 — layout v1/v2 chunked storage (HDF5 ≤ 1.6.2), VAX float byte order, and File Space Info v0.**
- [ ] **S6 — public accessors for B-tree K-values (msg 19) and Driver Info (msg 20).** Both already parse.
  (carried over)

### Read API

- [ ] **A1 — typed reads for compound, enum, array, complex, bitfield, and opaque.**
  - **Gap:** `read()` throws Unsupported for all of these, although the writer produces compound, enum,
    array, and complex. USER_GUIDE implies all atomic types map to Java arrays.
  - **Add:** compound field accessors (by member name), enum names, complex pairs, and raw opaque bytes.
- [ ] **A2 — numeric conversion.** `readDoubles` on integer data throws; offer widening conversions.
- [ ] **A3 — dataset storage metadata:** chunk shape, filter list, layout class, and storage size.
- [ ] **A4 — selections:**
  - strided and blocked hyperslabs and point selections in `select`;
  - raw, vlen, and reference readers on `Selection` (`readStrings` on vlen throws today).
- [ ] **A5 — paths:** `Group.child("a/b")` never matches paths. (The USER_GUIDE example that implied it
  is fixed.) Add path lookup, following soft links as `Group` now does.
- [ ] **A6 — open from something other than a `Path`:** `byte[]`, `SeekableByteChannel`, or a range
  reader (remote / object store). PLAN §5 claims a `ByteBuffer` fallback exists; it doesn't.
- [ ] **A7 — configurable chunk-cache size.** It is fixed at 16 MB (`io/ChunkCache.java:17`).
- [ ] **A8 — remove internal types from public signatures.** `Attribute`'s public constructor exposes the
  non-exported `io.FileContext`. A dereferenced object reports `path()` as `""` and prints `Dataset[/]`.

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
  - sort EARLIEST symbol tables by UTF-8 bytes (`strcmp`), not UTF-16 (`W:1602`) ✔;
  - ~~set the UTF-8 cset on link and attribute names~~ — done in the P0 pass (LATEST format).

### Performance

- [ ] **PF1 — cache the chunk index per dataset and look up by coordinate.** Every `Selection` read
  re-walks the whole index, so `blocks()` costs O(blocks × chunks). (`ChunkedReader.java:72`)
- [ ] **PF2 — read VDS selections lazily.** Today they re-assemble the whole VDS and re-map every source
  file per call (`Dataset.java:348`).
- [ ] **PF3 — use name indexes for lookup.**
  - `attribute(name)` reparses every attribute on each call.
  - A dense group's `link(name)` scans the link list; the name-hash B-tree could find it directly.
  - Partly done: `child(name)` now classifies only the object it returns, not every child.
- [ ] **PF4 — benchmark / perf-regression harness.** Still optional; the Zarr module's opt-in
  `Benchmarks` pattern works. (carried over)

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
  - **Still to document:** the writer's memory use (WF1).
- [ ] **D3 — PLAN.md is stale.**
  - §6 lists the non-existent `dataspace` and `util` packages, omits `data`, `index`, and `group`, and
    says only one package is exported (`datatype` is exported too).
  - §7's API sketch uses the old method names.
  - §2 still anticipates promoting code to `falcon.core`, contradicting the deferral.
  - §5 claims a ByteBuffer fallback.
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
