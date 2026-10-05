# Falcon HDF5 — remaining work (prioritized)

**Status (2026-10-04, after a full code review):** the build is green and all 144 tests pass. However,
the review checked Falcon against files from **real libhdf5 builds**: HDF5 2.0.0 (h5py 3.16), 1.14.6
(h5py 3.14), and 1.14.4 with libaec (HDFView's JNI). That found **silent wrong data in the reader** and
**writer output that libhdf5 rejects**. The suite missed both because:

- every fixture is HDF5 2.0 / `libver='latest'`;
- the szip vectors are bare AEC streams, not real szip chunks;
- writer output is only ever read back by Falcon itself.

**The "1.0-ready" claim should be withdrawn until P0 is closed.** Land each fix with the fixture that
reproduces it (see T1/T2).

How to read this list:
- **P0** — silent wrong data, or files other HDF5 tools reject or misread. Fix before any release.
- **P1** — valid files that fail to read; crashes, hangs, or OOM on corrupt input; thread safety; the
  test gaps that let P0 through.
- **P2** — features and API.
- **P3** — docs, build, housekeeping.

Each item gives the location, the failure, and the fix. "✔" means the failure was reproduced during the
review; anything else comes from code reading or the spec. Line numbers are as of commit `a887633`.
Abbreviations: `W` = `Hdf5Writer.java`; other paths are under `src/main/java/com/ebremer/falcon/hdf5/`.

## Do these first — top 10

1. **R1 szip decode vs real libhdf5** (+ W4 encode). Every real szip dataset (NASA / HDF-EOS) reads as
   garbage.
2. **R2 scale-offset decode** (+ W3 encode). Ordinary h5py `scaleoffset=` files read wrong.
3. **The chunk-index family, R3–R7:**
   - maxshape linearization;
   - layout-v4 filtered entry width;
   - filtered single-chunk layout;
   - extensible-array (EA) page bitmap;
   - paged fixed-array (FA) blocks.
4. **R8/R9:** a chunked dataset that was created but never written throws; so do unwritten vlen
   strings.
5. **W1/W2:** writer files with more than 1024 chunks, more than 45 links, or more than 29 attributes
   are unreadable by libhdf5.
6. **W5:** writer output for compound, enum, array, or filtered data is unreadable by HDF5 ≤ 1.14.
7. **V1 user block:** every MATLAB v7.3 `.mat` file is refused.
8. **T1 oracle in the loop:** read Falcon output with h5py (2.0 and 1.14), and add 1.10/1.14 fixtures,
   so P0 can't regress.
9. **H1–H4 hardening:**
   - verify checksums (metadata and fletcher32);
   - guard against cycles and recursion depth;
   - cap decompressed size.
10. **C1 thread safety:** concurrent reads of one `Hdf5File` throw `ConcurrentModificationException`.
    Fix it, or document single-threaded use.

---

## P0 — silent wrong data / files libhdf5 rejects

### Reader

- [ ] **R1 — szip decode doesn't match libhdf5 + libaec output.** ✔ (checked against HDF5 1.14.4 + libaec files)
  - **Where:** `filter/Filters.java:248-262`.
  - **Defects:**
    - It never skips H5Z-szip's 4-byte little-endian uncompressed-size header.
    - It doesn't undo libaec's SZ-compat byte interleaving: 32/64-bit pixels are coded as 8-bit samples.
    - It uses `rsi = floor(ppsl/ppb)` and doesn't strip scanline padding when `ppsl % ppb != 0`.
  - **Failure:** int16, float32, and int32 szip datasets decode to constants or garbage; padded
    scanlines throw.
  - **Why tests missed it:** `szip_chunks.txt` comes from `imagecodecs.aec_encode`, a bare AEC stream.
  - **Fix:** handle the header, interleaving, and padding. Regenerate the vectors with
    `imagecodecs.szip_encode(header=True)`, and commit real libhdf5 szip files.
  - **Same item, also fix:** signed szip at the filter level (old TODO item), and the AEC zero-block bug
    in R12.
- [ ] **R2 — scale-offset decode is wrong.** ✔
  - **Where (integer):** `Filters.java:69-86`.
    - Packed data is located "at the chunk's end". libhdf5 always starts it at byte 21, and its buffer is
      `floor(n·minbits/8)+1` bytes.
    - The all-ones code is treated as fill even when no fill is defined.
    - Fill decodes to 0 instead of `cd_values[8..]`.
    - Output is always little-endian, ignoring `cd[6]`.
    - The full-precision raw-copy path is missing.
  - **Failure (integer):** `arange(256,i4)` reads `[1,2,…,255,1]`; 1000..1015 reads `[1008,1016,1025,…]`.
    Big-endian, fill, and full-range data come back as garbage.
  - **Where (float):** `Filters.java:106,123`.
    - The fill is read from chunk bytes 13..20.
    - The decode multiplies by `10^-D` instead of dividing by `10^D`, so `25.5` reads as
      `25.500000000000004`.
    - Output is always little-endian.
  - **Fix:** port `H5Z__filter_scaleoffset` (decompress) exactly.
- [ ] **R3 — chunk-grid linearization uses the *current* dims.** ✔
  - **Where:** `index/FixedArray.java:46-50`, `index/ExtensibleArray.java:66-70,194-204`,
    `index/ImplicitIndex.java:22-28`.
  - **Defect:** libhdf5 linearizes over the *max* dims (`max_down_chunks`). For an EA it also moves the
    unlimited dimension to the slowest position.
  - **Failure:** `maxshape=(3,None)` reads scrambled (`[0,1,2,3,8,9,10,11,16,…]`), as do `maxshape>shape`
    on a non-leading fixed dim and the implicit index with maxshape.
  - **Fix:** pass `maxDims` in and apply the EA swizzle.
- [ ] **R4 — the filtered FA/EA chunk-size field width is hard-coded to `sizeOfLengths`.** ✔
  - **Where:** `index/FixedArray.java:63-64`, `index/ExtensibleArray.java:182-183`.
  - **Defect:** in layout v4 (HDF5 1.10–1.14) the width is variable: bytes(unfiltered chunk size) + 1,
    at most 8.
  - **Failure:** every filtered FA/EA dataset from those versions reads the wrong sizes and filter
    masks, and returns raw zlib bytes as data.
  - **Fix:** width = `entrySize − sizeOfOffsets − 4`, the same derivation `ChunkBTreeV2` already uses.
- [ ] **R5 — the filtered single-chunk index drops the filtered size and filter mask.** ✔
  - **Where:** `layout/DataLayoutMessage.java:121-126`, `data/ChunkedReader.java:86-87`.
  - **Failure:** h5py `libver='latest'` + compression + one chunk (the auto-chunk case for small
    datasets) reads out of bounds or as garbage.
  - **Fix:** carry the size and mask in `DataLayout.Chunked`.
- [ ] **R6 — the extensible-array page-init bitmap is skipped.** ✔
  - **Where:** `index/ExtensibleArray.java:114-123,132-137`.
  - **Failure:** for sparse appends, the uninitialized pages decode as chunk address 0, which holds the
    `\x89HDF` superblock bytes.
  - **Fix:** honour the bitmap, and reject chunk addresses of 0 or inside the superblock.
- [ ] **R7 — paged fixed-array data blocks are parsed flat.** ✔
  - **Where:** `index/FixedArray.java:52-55`.
  - **Defect:** blocks with more than 1024 entries (`1<<pageBits`) are paged, but the page bitmap and
    per-page checksums are read as if they were elements.
  - **Failure:** any `libver='latest'` dataset with more than 1024 chunks throws or reads garbage.
  - **Fix:** implement page addressing. Until then, throw `HdfUnsupportedException`.
- [ ] **R8 — a chunked dataset that was created but never written throws instead of returning fill.** ✔
  - **Where:** `ChunkedReader.java:84-98`.
  - **Failure:** "expected B-tree signature 'TREE' at -1". The same happens for FAHD, EAHD, BTHD, and
    single-chunk indexes.
  - **Fix:** if the index address is UNDEFINED, there are no chunks, so return all fill.
- [ ] **R9 — null vlen-string heap IDs are dereferenced.** ✔
  - **Where:** `data/VlenStrings.java:30-31`.
  - **Failure:** any partly written vlen-string dataset throws "expected 'GCOL' at 0". h5py returns `b''`.
  - **Fix:** treat length 0 or address 0 as empty, as `VlenSequences` already does.
- [ ] **R10 — fixed-point `bitOffset`/`bitPrecision` are ignored, and floats are decoded by size alone.** ✔
  - **Where:** `data/Elements.java:47-69,153-160`.
  - **Failure:** a 12-bit int at offset 4 reads `[-80,1600,32752]` instead of `[-5,100,2047]`. A bfloat16
    layout reads as IEEE half.
  - **Fix:** shift, mask, and sign-extend. Decode floats from their exponent/mantissa fields, or throw
    Unsupported for non-IEEE layouts.
- [ ] **R11 — the n-bit "no compression needed" flag (`cd[1]`) is ignored.** ✔
  - **Where:** `Filters.java:150-170`.
  - **Failure:** full-precision `<i4` data comes back byte-swapped.
  - **Fix:** if `cd[1]==1`, return the data unchanged. Also implement the NOOPTYPE member class (4)
    instead of throwing.
- [ ] **R12 — the AEC zero-block "remainder of segment" fills to the end of the RSI.** ✔
  - **Where:** `filter/Aec.java:147`.
  - **Defect:** libaec fills only to the next 64-block segment boundary.
  - **Failure:** with rsi > 64 (HDF5 allows up to 128), samples diverge from 512 onward.
  - **Fix:** apply `min(rsi−b, 64−(b%64))`.
- [ ] **R13 — unsigned values surface as negative.** ✔
  - **Where:** `Elements.java:64`, `Dataset.java:186`, `Attribute.java:204`.
  - **Failure:** `read()` maps uint32 to `int[]`, so 4000000000 reads as -294967296. `readInts()` on
    uint32 wraps silently. uint64 wraps in `readLongs()` with no documentation.
  - **Fix:** have `read()` return `long[]` for uint32; throw on out-of-range narrowing; document uint64,
    or add `readUnsignedLongs` / a `BigInteger` path.
- [ ] **R14 — VDS source/virtual datatype mismatch is neither converted nor rejected.** ✔ (the AIOOBE)
  - **Where:** `VirtualDataset.java:79-83`.
  - **Failure:** a `>i2` source in an `<i4` VDS throws a raw AIOOBE. A same-size source with different
    byte order would be silently byte-swapped.
  - **Fix:** convert via `Elements`, or throw Unsupported.

### Writer (files that libhdf5 rejects or misreads; every one passes Falcon's own round trip)

- [ ] **W1 — the fixed-array index is never paged.** ✔
  - **Where:** `W:758-792, W:1105`.
  - **Failure:** a chunked dataset with more than 1024 chunks (e.g. 100k ints, chunk 64) fails in h5py
    with "incorrect metadata checksum". Exactly 1024 chunks is fine.
  - **Fix:** emit the page bitmap, paged entries, and per-page checksums, or switch to an EA or v2
    B-tree index for large counts.
- [ ] **W2 — the dense-storage v2 B-tree is a single 512-byte leaf with no split.** ✔
  - **Where:** `W:1350-1381`; `BT2_NODE_SIZE` at `W:69`.
  - **Failure:** a group with more than 45 links, or an object with more than 29 attributes, is
    unreadable ("incorrect metadata checksum").
  - **Fix:** size the node to fit the records (node size is a header field), or build internal nodes.
- [ ] **W3 — the scale-offset encoder doesn't follow libhdf5's rules.** ✔
  - **Where:** `W:841-842, W:875-901`.
  - **Defect:** it emits minbits 0 for constant chunks while `filavail=1`, and it bit-packs when minbits
    ≥ 8×size instead of copying raw.
  - **Failure:** `[7]*16` reads as zeros in libhdf5; full-range int32 fails to decode.
  - **Fix:** mirror `H5Z__filter_scaleoffset` (compress). Shares a test matrix with R2.
- [ ] **W4 — szip output is not libhdf5-compatible.** ✔ (via libaec)
  - **Where:** `W:820, W:939-950`; hang at `Aec.java:47-58,232-237`.
  - **Defects:**
    - There is no 4-byte size header.
    - 32/64-bit samples are not interleaved.
    - The mask lacks RAW (128).
    - For 64-bit samples the cost sum overflows `long`, so `doubleChunkedDataset(...).szip()` **hangs**.
  - **Fix:** mirror the R1 fixes; cap bits per pixel or interleave; saturate the cost.
- [ ] **W5 — HDF5 2.0-only message versions are used everywhere.** ✔ (HDF5 1.14.6)
  - **Where:** `W:1095` (layout v5), `W:1733,1762,1826` (datatype v5).
  - **Failure:** HDF5 ≤ 1.14 rejects compound, enum, array, and every filtered dataset — even under
    `Format.EARLIEST`.
  - **Fix:**
    - Use datatype v3 for compound/enum/array; keep v5 only for complex.
    - Use layout v4 with the computed chunk-size width (FAHD element size 14, not 20).
    - Make `EARLIEST` avoid 1.8+ messages.
    - Consider a `Format.V110` / "maximally compatible" default.
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
- [ ] **W11 — filter setters don't validate the datatype or filter combination, and encode order ≠ message order.** ✔
  - **Where:** `W:482-508, 743-751` vs `816-844`.
  - **Failure:**
    - `scaleOffset()` and `nbit()` on float64 write garbage or zeros.
    - `nbit(8)` on -1 stores 255.
    - `scaleOffset().deflate()` silently drops deflate.
    - `scaleOffset().szip()` is unreadable.
  - **Fix:** use one ordered filter list for both encode and the message; validate when each filter is
    set.
- [ ] **W12 — `GrowBuffer.ensure()` doubles an `int`.** ✔
  - **Where:** `write/GrowBuffer.java:101-109`, `W:208-220`.
  - **Failure:** past 2^30 it overflows to 0 and **spins forever**, so any file over 1 GiB hangs. Because
    the whole file is built in memory and then copied, a 256 MB dataset peaks at 1.55 GB of heap.
  - **Fix:** throw immediately on overflow (a stopgap). The real fix is in **WF1**: stream data to a
    `FileChannel` with `long` offsets.
- [ ] **W13 — fixed-length strings, compound field names, and enum member names are encoded as US-ASCII.** ✔
  - **Where:** `W:271-288, 1740, 1833, 1877-1885`.
  - **Failure:** `"café"` is stored as `caf?` with no error.
  - **Fix:** use UTF-8 with cset=1, or reject non-ASCII.

## P1 — valid files that fail; hardening; concurrency; test gaps

### Valid files that fail to read

- [ ] **V1 — a user block (non-zero base address) is refused.** ✔
  - **Where:** `Hdf5File.java:47`.
  - **Failure:** every MATLAB v7.3 `.mat` file and every h5py `userblock_size=` file throws
    `HdfUnsupportedException`. It isn't listed in USER_GUIDE "Not supported".
  - **Fix:** add the base address to every file address, e.g. by slicing the mapped segment at the base.
- [ ] **V2 — the `DONT_FILTER_PARTIAL_BOUND_CHUNKS` layout flag (bit 0) is ignored.** ✔
  - **Where:** `layout/DataLayoutMessage.java:105`.
  - **Failure:** "deflate: incorrect header check" on the raw edge chunk.
  - **Fix:** carry the flag, and skip the pipeline for partial edge chunks.
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

The README promises corrupt input "never crashes the JVM or returns wrong data". These items break that.

- [ ] **H1 — checksums are never verified.**
  - **Metadata:** only the superblock checksum is checked. OHDR, OCHK, BTHD/BTIN/BTLF, FRHP/FHIB/FHDB,
    FAHD/FADB, and EA blocks/pages are not.
    - **Failure:** a single flipped dataspace byte silently changes dims and data, where libhdf5 refuses
      the file. ✔
  - **Data:** fletcher32 is stripped but never verified (`Filters.java:319`).
    - **Failure:** a flipped chunk bit returns 66 instead of 2, where h5py raises. ✔
  - **Fix:** verify lookup3 everywhere; compute fletcher32, including libhdf5's odd-length and legacy
    byte-swapped variants.
- [ ] **H2 — cycles and unbounded recursion become `StackOverflowError`, OOM, or hangs.** ✔
  - **Cycles:**
    - Object-header continuation loops (`header/ObjectHeader.java:134-139,186-195`).
    - B-tree self-pointers and shared subtrees, with no level-decrement check (`btree/BTreeV2.java:76-81`,
      `ChunkBTreeV1.java:61-65`, `GroupBTreeV1.java:60-64`).
    - VDS self-reference (`VirtualDataset.java:70,79`).
  - **Deep nesting:** nested datatypes (`DatatypeMessage.java:144-202`).
  - **Fix:** iterative worklists with visited sets; depth caps; record budgets bounded by file size.
- [ ] **H3 — corrupt length fields cause hangs and runaway allocation.** ✔
  - **Global heap:** an object size of -16 never advances the cursor, so it **hangs**
    (`heap/GlobalHeap.java:41`).
  - **Extensible array:** `maxBits` 255 hangs (`ExtensibleArray.java:72,97-99,168-170`). It also walks
    unallocated blocks element by element.
  - **Fixed array:** `entrySize` 0 with a huge `maxEntries` gives OOM.
  - **szip:** `ppb==0` or `ppsl<ppb` **hangs** (`Aec.java:99-103`).
  - **deflate:** unbounded inflate is a zip bomb; a 0.5 MB chunk gives OOM (`Filters.java:277-296`).
  - **Fix:** validate every count; pass the expected size into decompression and stop on overrun.
- [ ] **H4 — raw RuntimeExceptions escape instead of `HdfException`.** ✔
  - Layout rank 0 → `NegativeArraySizeException`.
  - v2 B-tree record size 0 → `ArithmeticException`.
  - A short fractal-heap ID → AIOOBE.
  - `Attribute.readInt()` on an empty attribute → AIOOBE.
  - `select({0,0},{0,3})` on 2-D contiguous data → IOOBE (`data/Hyperslab.java:29-30` checks only the
    last dim).
  - `blocks(1)` on a NULL dataspace → IOOBE.
  - A short unfiltered chunk → AIOOBE (`ChunkedReader.java:105`).
  - **Silent truncation:** the `(int)` narrowing casts silently truncate (`GlobalHeap.java:39`,
    `LinkMessage.java:66`, `ChunkBTreeV2.java:62`, `DataLayoutMessage.java:89,112,115`,
    `DatatypeMessage.java:78,193`, `BTreeV2.java:38,78`).
  - **Overflow:** `Dataspace.elementCount`, `select` with `offset+count`, and `blocks(Long.MAX_VALUE)`
    overflow.
  - **Fix:** checked narrowing and `Math.*Exact`.
- [ ] **H5 — a local heap name offset past the data segment silently returns `""`.**
  - **Where:** `heap/LocalHeap.java:39-48`.
- [ ] **H6 — security: external-file and VDS source paths are opened as written.** ✔
  - **Where:** `message/ExternalFileList.java:76`, `VirtualDataset.java:69`.
  - **Defect:** absolute paths, `..`, and Windows UNC paths are honoured. A crafted `.h5` can read any
    local file, or trigger an SMB/NTLM connection.
  - **Fix:** add a resolver policy — confined to the file's directory by default, with an opt-in for
    absolute paths and an `HDF5_EXTFILE_PREFIX` / `HDF5_VDS_PREFIX` equivalent. Document it.

### Concurrency & lifecycle

- [ ] **C1 — the `ChunkCache` is an access-ordered `LinkedHashMap` that `get()` mutates.** ✔
  - **Where:** `io/ChunkCache.java:19-43`; lazy fields in `io/FileContext.java:36-41` and `Dataset`.
  - **Failure:** 16 threads doing `select().readDoubles()` hit 62–69 `ConcurrentModificationException`s
    per 6400 reads. `blocks()` returns a Stream that invites `.parallel()`.
  - **Fix:** decide the contract. Concurrent reads of an mmapped file are a natural expectation, so
    prefer a synchronized or concurrent LRU plus safe publication. Document the contract in the Javadoc
    and USER_GUIDE either way, and add a concurrency test.
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

- [ ] **T1 — put an HDF5 oracle in the loop for the writer.**
  - **Gap:** `WriteTest` only round-trips through Falcon. Add `tools/fixtures/check_hdf5_writer.py`
    (like `check_zstd_encoder.py`) that writes the `WriteTest` matrix and reads it with h5py 3.16
    (HDF5 2.0) **and** h5py 3.14 (HDF5 1.14). Run it before every release.
  - **Optionally:** an opt-in CI job. It needs Python in CI, so ask Erich first.
- [ ] **T2 — add the reader fixtures the bugs above live in.** Each one needs a generator entry in
  `gen_fixtures.py`:
  - HDF5 1.10/1.14 files (layout v4 FA/EA, filtered);
  - `maxshape > shape`, and an unlimited dim that isn't first;
  - sparse EA pages;
  - more than 1024 FA chunks;
  - a filtered single chunk;
  - datasets that were created but never written (every index type);
  - null vlen elements;
  - **real libhdf5 szip chunks** (the review used HDFView's HDF5 1.14.4 JNI with libaec);
  - scale-offset with power-of-two chunks, fill, big-endian, and full range;
  - default-libver VDS and region references;
  - a user block;
  - old-style soft links;
  - `DONT_FILTER_PARTIAL`;
  - a dense attribute over 4 KiB;
  - full-precision n-bit.
- [ ] **T3 — strengthen `RobustnessTest`.**
  - **Coverage:** add `vds.h5`, `ea_paged.h5`, and `dense_links_big.h5` to the fixture list. Have
    `readEverything` also resolve vlen data, references, and typed reads, not just `readRawBytes()`.
  - **Crafted cases:** add cases for cycles, self-continuations, zero or negative sizes, and zip bombs.
    Run under a small `-Xss`/`-Xmx`.
  - **Assertions:** `StackOverflowError` and OOM must never occur.
- [ ] **T4 — add a concurrency test** once C1's contract is chosen.

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
  - USER_GUIDE.md:125: "filters apply in call order" — the order is hard-coded
    shuffle → deflate → fletcher32.
  - Javadoc of `readRawBytes`: says "not yet de-filtered", but the data *is* de-filtered.
- [ ] **D2 — USER_GUIDE gaps.** Document:
  - the thread-safety contract (C1);
  - the write-on-close lifecycle and memory use (WF1);
  - unsigned handling (R13);
  - the user-block limitation (until V1 lands);
  - external-path policy (H6).
- [ ] **D3 — PLAN.md is stale.**
  - §6 lists the non-existent `dataspace` and `util` packages, omits `data`, `index`, and `group`, and
    says only one package is exported (`datatype` is exported too).
  - §7's API sketch uses the old method names.
  - §2 still anticipates promoting code to `falcon.core`, contradicting the deferral.
  - §5 claims a ByteBuffer fallback.
- [ ] **D4 — Javadoc lint:** 318 `-Xdoclint:all` warnings, including 78 undocumented public members
  (28 in `Hdf5Writer`). 0 errors.
- [ ] **D5 — Python tooling.** CLAUDE.md says h5py 3.16 is installed, but the default `python` (the
  WindowsApps 3.12 install) has neither h5py nor zarr; the reviewers had to use scratch venvs.
  - **Add:** `tools/fixtures/requirements.txt` pinning h5py==3.16.0 (plus a 3.14 / HDF5 1.14 env for T1),
    numpy, and imagecodecs, with venv instructions.
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
