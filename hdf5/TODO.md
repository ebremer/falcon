# Falcon HDF5 — remaining work (prioritized)

**Status (2026-10-04, after the code review and the first fix pass):** build green, **187 HDF5 tests**
(up from 144). The review's top 10 are done (see *Done* at the end). Falcon now:

- reads the files the review showed it misreading: real libhdf5 szip and scale-offset data, every chunk
  index shape (maximum dims, layout v4, paged and sparse arrays, filtered single chunks, unwritten
  datasets), user-block (MATLAB v7.3) files;
- writes files that **HDF5 2.0 and 1.14 read**, checked by `tools/fixtures/check_hdf5_writer.py`. The
  final run read 46/46 objects with HDF5 2.0 and 41/41 with 1.14.6; the same matrix written by the old
  writer failed 11 and 24;
- verifies metadata and fletcher32 checksums, rejects loops and runaway sizes in corrupt files, and
  supports concurrent reads of one open file.

The remaining P0 items are edge cases, but they are still silent wrong data or files libhdf5 rejects.
Hold the "1.0-ready" claim until they are closed.

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

1. **R10 — bit offset/precision is ignored.** A 12-bit integer, or a non-IEEE float, reads as garbage.
2. **R13 — unsigned values read as negative.** `read()` maps `uint32` to `int[]`.
3. **W6–W10, W13, W14 — writer edge cases libhdf5 rejects or misreads:**
   - zero-size datasets;
   - more than 65,535 vlen elements;
   - messages over 64 KiB;
   - unvalidated names;
   - typed fill values;
   - non-ASCII fixed strings;
   - out-of-range n-bit values.
4. **V3/V4 — links.** An old-style soft link makes its whole group unreadable, and new-style soft and
   external links are hidden.
5. **V5 — fractal-heap limits.** A dense attribute over 4 KiB, or a group of about 40k links, fails.
6. **V6/V7 — default-libver encodings.** VDS and region references written with the default libver are
   refused.
7. **H6 — security.** External-file and VDS paths can reach any local file.
8. **C2/C3 — lifecycle.** Use after `close()` is untyped, and a writer that fails part-way still writes a
   partial file.
9. **R14 — VDS byte order.** A source with a different byte order is copied without conversion.
10. **WF1 — streaming writes.** The writer builds the whole file in memory, which caps it at about 2 GB.

---

## P0 — silent wrong data / files libhdf5 rejects

### Reader

- [ ] **R10 — fixed-point `bitOffset`/`bitPrecision` are ignored, and floats are decoded by size alone.** ✔
  - **Where:** `data/Elements.java:47-69,153-160`.
  - **Failure:** a 12-bit int at offset 4 reads `[-80,1600,32752]` instead of `[-5,100,2047]`. A bfloat16
    layout reads as IEEE half.
  - **Fix:** shift, mask, and sign-extend. Decode floats from their exponent/mantissa fields, or throw
    Unsupported for non-IEEE layouts.
- [ ] **R13 — unsigned values surface as negative.** ✔
  - **Where:** `Elements.java:64`, `Dataset.java:186`, `Attribute.java:204`.
  - **Failure:** `read()` maps uint32 to `int[]`, so 4000000000 reads as -294967296. `readInts()` on
    uint32 wraps silently. uint64 wraps in `readLongs()` with no documentation.
  - **Fix:** have `read()` return `long[]` for uint32; throw on out-of-range narrowing; document uint64,
    or add `readUnsignedLongs` / a `BigInteger` path.
- [ ] **R14 — a VDS source with a different byte order is copied without conversion.**
  - **Done:** a source whose element *size* differs now throws `HdfUnsupportedException`; it used to
    throw a raw AIOOBE.
  - **Remaining:** a same-size source in the other byte order (or another layout) is still copied
    verbatim.
  - **Where:** `VirtualDataset.java`.
  - **Fix:** convert via `Elements`, or compare the datatypes and throw Unsupported.

### Writer (files that libhdf5 rejects or misreads; every one passes Falcon's own round trip)

- [ ] **W6 — a zero-size contiguous dataset gets a defined address.** ✔
  - **Where:** `W:694-700`.
  - **Failure:** libhdf5 reports "invalid dataset size, likely file corruption" for `int[0]`, shape
    `{3,0}`, or an empty `stringDataset`.
  - **Fix:** write UNDEFINED and skip the global heap.
- [ ] **W7 — global-heap object indices are u16 in one collection per dataset.** ✔
  - **Where:** `W:1231,1243`.
  - **Failure:** a vlen dataset with ≥ 65,536 elements is unreadable. Every vlen dataset also costs at
    least 4 KiB.
  - **Fix:** split into multiple collections of at most 65,535 objects; share collections across
    datasets.
- [ ] **W8 — header messages over 64 KiB are silently truncated.** ✔
  - **Where:** `W:1180` (u16 size), `W:1271-1313`, `W:525`.
  - **Defect:** in addition, dense attributes over 4 KiB break the heap's maximum managed-object size.
  - **Failure:** a compact 10,000-double attribute is unreadable. `compact()` accepts up to 65,535
    bytes, but more than 65,531 is unreadable.
  - **Fix:**
    - Validate the body size.
    - Store large dense attributes as huge objects (or reject them).
    - Lower the compact limit.
- [ ] **W9 — names are neither validated nor de-duplicated.** ✔
  - **Where:** `W:424-446, 621-639, 1540`.
  - **Failure:**
    - An empty name makes the parent group unreadable.
    - `"a/b"` is unreachable.
    - A duplicate dataset name silently drops the first dataset; under EARLIEST it writes two symbol
      entries.
    - Duplicate attributes are both written.
  - **Fix:** reject empty names, `/`, `.`, and duplicates at add time.
- [ ] **W10 — `fillValue(long|double)` encodes by the Java argument type, not the dataset type.** ✔
  - **Where:** `W:533-550`.
  - **Failure:**
    - `.fillValue(5)` on float64 stores 2.5e-323.
    - `.fillValue(5.0)` on int32 stores 1084227584.
    - On a vlen string, the creation properties become unreadable.
  - **Fix:** convert numerically for the datatype; reject fill on vlen, reference, and compound.
- [ ] **W13 — fixed-length strings, compound field names, and enum member names are encoded as US-ASCII.** ✔
  - **Where:** `W:271-288, 1740, 1833, 1877-1885`.
  - **Failure:** `"café"` is stored as `caf?` with no error.
  - **Fix:** use UTF-8 with cset=1, or reject non-ASCII.
- [ ] **W14 — `nbit(precision)` silently drops bits that don't fit.**
  - **Where:** `nbitEncode`.
  - **Failure:** `nbit(8)` on -1 stores 255.
  - **Fix:** validate values when writing (they must be non-negative and fit in `precision` bits), or
    document a clamp.
- [ ] **W15 — `Format.EARLIEST` still emits HDF5 1.8-era message versions.**
  - **Detail:** dataspace v2, fill value v3, attribute v3, and compound/enum/array datatype v3.
  - **Impact:** HDF5 1.8+ reads them, so this only matters for pre-1.8 readers.
  - **Fix:** use the v1 encodings in EARLIEST for full fidelity to the name.

## P1 — valid files that fail; hardening; concurrency; test gaps

### Valid files that fail to read

- [ ] **V3 — old-style soft links (symbol-table cache type 2) are classified at address -1.** ✔
  - **Where:** `group/SymbolTableNode.java:37-40` → `Group.java:160-167`.
  - **Failure:** the *whole group* becomes unreadable.
  - **Fix:** keep the scratch pad, and skip or resolve cache-type-2 entries.
- [ ] **V4 — soft and external links in new-style groups are silently dropped from `children()`.** ✔
  - **Fix:** expose links (`links()`, a `Link` record with kind and target, optional resolution), or at
    least don't hide them.
- [ ] **V5 — fractal-heap limits:**
  - **Huge objects:** a dense attribute over 4 KiB is a *huge* object, and *all* attributes on that
    object then fail. ✔
  - **Nested indirect blocks:** a group of ~40k links needs nested indirect blocks, and it fails. ✔
  - **Also missing:** filtered heaps and tiny objects.
  - **Where:** `heap/FractalHeap.java`. Also match libhdf5's ID length computation (`:95-96`).
- [ ] **V6 — VDS encodings:**
  - Heap-block **version 1** (same-file `"."` sources: per-entry flags, no file name) is misparsed as
    "selection type 65536".
  - **Hyperslab selection v1** (every default-libver VDS) is unsupported.
  - **Where:** `VirtualDataset.java:48,174`.
- [ ] **V7 — region references:**
  - Null refs (address 0) aren't detected.
  - Selection v1/v2 (default libver) is unsupported.
  - Point selections are unsupported.
  - One bad element fails the whole array.
  - **Where:** `Hdf5Object.java:175,197-206`.
- [ ] **V8 — an External File List slot of size `H5F_UNLIMITED` (allowed by the spec) gives a raw IOOBE.** ✔
  - **Where:** `message/ExternalFileList.java:77,86`.
  - **Also:** a negative offset gives IAE, and a bad name gives `InvalidPathException`.
  - **Fix:** treat it as unbounded, validate, and wrap the errors.
- [ ] **V9 — the shared-message v1 layout reads the address at body+8.** (spec)
  - **Where:** `header/SharedMessage.java:49`.
  - **Defect:** the address is at body+8+sizeOfLengths; body+8 is the link-name offset.
  - **Failure:** committed types in pre-1.6.1 files resolve to a bogus address.
- [ ] **V10 — the shared flag is honoured only for datatypes.** ✔
  - **Failure:** under SOHM, a dataspace throws "version 3", attributes misparse, and fill parses as
    "none".
  - **Fix:** resolve shared messages centrally. Throw `HdfUnsupportedException` for SOHM until P2 S1 is
    done.
- [ ] **V11 — reference type code 2 is reported as `ATTRIBUTE`.**
  - **Where:** `message/DatatypeMessage.java:232-238`.
  - **Defect:** in datatype v4, code 2 is the revised generic `H5R_ref_t`; in v<4 it is reserved.
  - **Fix:** add a REVISED kind, decoded per element (ties to P2 S3).
- [ ] **V12 — v1 compound member array dimensions are skipped.** Pre-1.4 array members decode as scalars.
  - **Where:** `DatatypeMessage.java:128-132`.

### Corrupt-input hardening

H1–H5 (checksums, cycles, runaway sizes, raw exceptions) are done; see *Done*.

- [ ] **H6 — security: external-file and VDS source paths are opened as written.** ✔
  - **Where:** `message/ExternalFileList.java:76`, `VirtualDataset.java:69`.
  - **Defect:** absolute paths, `..`, and Windows UNC paths are honoured. A crafted `.h5` can read any
    local file, or trigger an SMB/NTLM connection.
  - **Fix:** add a resolver policy — confined to the file's directory by default, with an opt-in for
    absolute paths and an `HDF5_EXTFILE_PREFIX` / `HDF5_VDS_PREFIX` equivalent. Document it.

### Concurrency & lifecycle

- [ ] **C2 — use after `close()` gives a raw `IllegalStateException`, and a second `close()` throws.** ✔
  - **Fix:** make `close()` idempotent, throw a typed exception on use after close, and add `isOpen()`.
- [ ] **C3 — writer failure semantics.** ✔
  - **Where:** `W:202-221`.
  - **Defects:**
    - `close()` writes whatever was defined even if the try body threw. An NPE mid-build still leaves a
      valid-looking, truncated file.
    - `written=true` is set before work that can fail, so a retried `close()` does nothing.
    - Adds after `close()` are silently dropped.
    - Errors surface at `close()` as raw RuntimeExceptions.
  - **Fix:** validate eagerly; add a closed-guard and `abort()`; write to a temp file and move it into
    place atomically on success.
- [ ] **C4 — chunk shape is never validated.** ✔
  - **Where:** `W:290-300, 1040-1050`.
  - **Failure:** a rank mismatch gives AIOOBE at close; a zero dim gives "/ by zero" at close; a chunked
    scalar is rejected by libhdf5.

### Test & oracle gaps (what let P0 through)

- [ ] **T2 — add the reader fixtures the remaining bugs live in.**
  - **Done:** fixtures for every fixed item now exist: `chunk_maxshape`, `layout_v4`, `paged_sparse`,
    `filtered_single`, `unwritten_*`, `scaleoffset` (plus `scaleoffset_chunks.txt`), real-format
    `szip` (plus `szip_chunks.txt`), `userblock_v0/v3`, `filter_edge`, `vds_loop`, and
    `aec_ros_vectors.txt`.
  - **Still to add**, each as a generator entry in `gen_fixtures.py`:
    - default-libver VDS (including same-file `"."` sources) and region references (V6, V7);
    - old-style soft links (V3), and new-style soft and external links (V4);
    - a dense attribute over 4 KiB, and a group of ~40k links (V5);
    - SOHM shared messages (V10, S1).
- [ ] **T3 — strengthen `RobustnessTest` further.**
  - **Done:**
    - The fuzz set now includes 10 more fixtures (`vds`, `dense_links_big`, and the new ones).
    - `readEverything` does typed reads that resolve vlen data and references; this found a
      region-reference leak, now fixed.
    - `HardeningTest` covers crafted cycles, self-continuations, negative sizes, deep nesting, and zip
      bombs.
  - **Still to do:**
    - Add `ea_paged.h5` (slow: 150k chunks per mutation).
    - Run the fuzz tests under a small `-Xss`/`-Xmx` in a dedicated surefire execution, so a regression
      to unbounded recursion or allocation fails fast.

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
- [ ] **A5 — paths:** `Group.child("a/b")` never matches paths, yet USER_GUIDE uses
  `child("maybe/missing")`. Add path lookup.
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
  - set the UTF-8 cset on link and attribute names (`W:1479,1546-1548`).

### Performance

- [ ] **PF1 — cache the chunk index per dataset and look up by coordinate.** Every `Selection` read
  re-walks the whole index, so `blocks()` costs O(blocks × chunks). (`ChunkedReader.java:72`)
- [ ] **PF2 — read VDS selections lazily.** Today they re-assemble the whole VDS and re-map every source
  file per call (`Dataset.java:348`).
- [ ] **PF3 — use name indexes for lookup.** `Group.child(name)` classifies every child (79 ms for 20k
  entries), and `attribute(name)` reparses every attribute on each call.
- [ ] **PF4 — benchmark / perf-regression harness.** Still optional; the Zarr module's opt-in
  `Benchmarks` pattern works. (carried over)

## P3 — docs, build, housekeeping

- [ ] **D1 — fix docs that overclaim.**
  - `PLAN.md`: "1.0-ready", "read back identically … by h5py", "szip verified via libaec", and "every
    structure on a read path is covered".
  - README: "corrupt input never … returns wrong data".
  - ~~USER_GUIDE.md:125 "filters apply in call order"~~ — now true (W11).
  - Javadoc of `readRawBytes`: says "not yet de-filtered", but the data *is* de-filtered.
- [ ] **D2 — USER_GUIDE gaps.**
  - **Done:** thread safety, filter rules, the writer's HDF5 1.10+ compatibility, and checksum
    verification are now documented.
  - **Still to document:**
    - the write-on-close lifecycle and memory use (WF1);
    - unsigned handling (R13);
    - the external-path policy (H6).
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
