# Falcon HDF5 — remaining work (prioritized)

**Status (2026-10-05, after the P0 pass):** build green, **206 HDF5 tests** (144 at the review, 187
after the top 10). The review's top 10 and **every P0 item** are done (see *Done* at the end). Falcon
now:

- reads the files the review showed it misreading:
  - real libhdf5 szip and scale-offset data;
  - every chunk index shape (maximum dims, layout v4, paged and sparse arrays, filtered single chunks,
    unwritten datasets);
  - user-block (MATLAB v7.3) files;
  - integers with a bit offset or reduced precision, and non-IEEE floats;
  - unsigned values (never wrapped);
  - virtual-dataset sources in the other byte order.
- writes files that **HDF5 2.0 and 1.14 read**, checked by `tools/fixtures/check_hdf5_writer.py`. The
  final run read 81/81 objects with HDF5 2.0 and 76/76 with 1.14.6. The P0 edge-case files written by
  the previous writer fail 19 objects under each version.
- verifies metadata and fletcher32 checksums, rejects loops and runaway sizes in corrupt files, and
  supports concurrent reads of one open file.

No known P0 remains. Valid files Falcon cannot read yet are P1 (V3–V12); the lifecycle gaps (C2/C3) are
P1 too.

**API changes in the P0 pass** (pre-1.0):
- `read()` returns `long[]` for `uint32` and `BigInteger[]` for `uint64`.
- `readInts()` and `readLongs()` throw on a value that does not fit, instead of wrapping it.
- `readInts()` now accepts `int64` data whose values fit in an `int`.
- `Datatype.FloatingPoint` gains `signLocation` and `normalization`.
- The writer rejects bad names, out-of-range fill values, and n-bit values when they are added.

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

1. **V3/V4 — links.** An old-style soft link makes its whole group unreadable, and new-style soft and
   external links are hidden.
2. **V5 — fractal-heap limits.** A dense attribute over 4 KiB (huge object), or a group of about 40k
   links, fails to read.
3. **V6/V7 — default-libver encodings.** VDS and region references written with the default libver are
   refused.
4. **H6 — security.** External-file and VDS paths can reach any local file.
5. **C2/C3 — lifecycle.** Use after `close()` is untyped, and a writer that fails part-way still writes a
   partial file.
6. **C4 — chunk shape validation.** A bad chunk shape fails at `close()` with a raw exception.
7. **V8–V12 — smaller format gaps:**
   - an unlimited External File List slot;
   - the v1 shared-message address;
   - shared (SOHM) messages other than datatypes;
   - reference type code 2;
   - v1 compound member dimensions.
8. **T2/T3 — remaining fixtures and fuzzing.** Add the V3–V7 fixtures, and run the fuzz tests under a
   small heap and stack.
9. **D1 — docs that overclaim.** PLAN.md and the README still say "1.0-ready" and "never returns wrong
   data".
10. **WF1 — streaming writes.** The writer builds the whole file in memory, which caps it at about 2 GB.

---

## P0 — silent wrong data / files libhdf5 rejects

Empty: every item is done (see *Done — 2026-10-05*). A new P0 is any silent wrong value, or any written
file that libhdf5 rejects or misreads.

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
  - ~~set the UTF-8 cset on link and attribute names~~ — done in the P0 pass (LATEST format).

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
  - ~~Javadoc of `readRawBytes`: says "not yet de-filtered"~~ — fixed.
- [ ] **D2 — USER_GUIDE gaps.**
  - **Done:** thread safety, filter rules, the writer's HDF5 1.10+ compatibility, and checksum
    verification are now documented.
  - **Also done (P0 pass):** unsigned and non-native numeric reads, VDS type rules, writer names and
    limits, typed fill values, and the EARLIEST message versions.
  - **Still to document:**
    - the write-on-close lifecycle and memory use (WF1);
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
