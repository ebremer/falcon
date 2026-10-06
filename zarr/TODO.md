# Falcon Zarr — remaining work (prioritized)

**Status (2026-10-04, after a full code review):** stages Z0–Z9 are built, the build is green, and all
270 tests pass. Decoding is solid against the reference libraries:

- all 316 libzstd frames decoded exactly (levels −7..22, long-distance matching (LDM), streaming,
  checksums);
- all 500 c-blosc buffers decoded exactly (every compressor and shuffle mode, typesizes 1–255);
- transpose, sharding (index at start and end), crc32c, edge chunks, and omitted all-fill inner chunks
  all round-trip both ways against zarr-python 3.4.

**But** the review found silent wrong data and data loss on the **write and mutation paths**, a zstd
decoder that accepts corrupt frames, decoders that can be driven to OOM, and **thread-unsafe, stale
chunk caching**. The README and USER_GUIDE claim corrupt input "never … returns wrong data" and never
causes "out-of-memory from a bogus declared size"; both claims are currently false.

How to read this list:
- **P0** — silent wrong data or data loss. Fix before any release.
- **P1** — valid stores that fail to read; crashes or OOM on corrupt input; thread safety; remote
  performance; the test gaps that let P0 through.
- **P2** — features and API.
- **P3** — docs, build, housekeeping.

Each item gives the location, the failure, and the fix. "✔" means the failure was reproduced during the
review; anything else comes from code reading or the spec. Line numbers are as of commit `a887633`.
Paths are under `src/main/java/com/ebremer/falcon/zarr/`.

**Update (2026-10-05): the zstd and Blosc code moved to Falcon Core.** It is now in
`core/src/main/java/com/ebremer/falcon/core/compress/{zstd,blosc,lz4,bitshuffle}` (see PLAN §10). Two
consequences:
- **Shared items.** Z5–Z7 now affect HDF5's zstd and Blosc filters too, so fix them once, in core.
- **Partly done in core:**
  - The codec parts of **H2** are done: header bounds, the Huffman guard, `int` overflow in size checks,
    typesize 0, and internal-zstd errors escaping Blosc. Core now throws only
    `CompressionFormatException`.
  - So are the codec parts of **T2**: core's `CompressionRobustnessTest` fuzzes zstd, Blosc (every
    internal codec), LZF, and bitshuffle, accepting only typed exceptions.
  - For **H1**, core's zstd and LZF decoders take a maximum size, but Zarr's pipeline does not pass one
    yet.

**Update (2026-10-05): P0 is done.** Every silent-wrong-data and data-loss finding is fixed; 286 Zarr
tests and 39 core tests pass. Three fixes change behaviour, each as Erich chose:
- creating a node where one exists is refused, unless `overwrite = true`, which deletes the old node's
  keys first (Z1);
- the decoded-chunk cache is opt-in, per handle (`withChunkCache`), so a plain handle always reads the
  store (Z3);
- the typed writers store a value exactly (a float type rounds to nearest) or throw
  `IllegalArgumentException` (Z4).

zarr-python 3.4 and numcodecs 0.17 (c-blosc) read every fixed case as Falcon now writes it. That was a
one-off check; T1 is the committed version of it.

**Update (2026-10-06): P1 is done.** 350 Zarr tests, 15 more under a small heap (the `fuzz` execution),
and 55 core tests pass. Every item below is done; each says what changed, then gives the original
finding. Behaviour changes worth knowing:
- `children()`/`arrays()`/`groups()` leave out a child that cannot be opened (I3);
- an unknown `zarr.json` member fails unless it says `"must_understand": false` (I10);
- new node names follow the specification (I12);
- Blosc headers are checked as c-blosc checks them, so a version-1 Blosc buffer is refused (H4);
- writes follow a codec's configuration (I9).

New API: `HttpStore.builder(url)` with `missingStatuses(...)` (I7), `Store.getSuffix` (PF1), and in core
`ZstdEncoder.compress(data, checksum)`, `BloscEncoder.compress(data, typeSize, shuffle, blockSize[, clevel])`,
`BloscDecoder.decompress(src, maxSize)`. What remains is P2, and P3's D3, D4, and B1.

**Update (2026-10-06): P2's F1, F2, F5, F6, and F7 are done.** 431 Zarr tests, 15 more under a small heap,
and 55 core tests pass; zarr-python 3.4 reads all 189 arrays `check_zarr_writer.py` checks and all 6
hierarchies `check_zarr_hierarchies.py` checks. Each item says what changed, then gives the original
finding. Behaviour changes worth knowing:
- `Zarr.open` answers from consolidated metadata when a group has some (F2), so a listing shows the
  hierarchy as it was consolidated; `Zarr.open(store, false)` reads node by node;
- `StringChunks` and `VlenUtf8` became `VlenChunks` and `VlenCodec`, serving strings and byte strings
  (F5; internal).

New API: `S3Store`, `HttpStore.builder(url).header(...)`/`requestHeaders(...)` (F1);
`Zarr.open(store, useConsolidated)`, `ZarrGroup.consolidate()`/`isConsolidated()` (F2);
`DataType.BYTES`, `readByteArrays`/`writeByteArrays` (F5); `ZarrArray.resize`,
`ZarrNode.setAttributes`/`updateAttributes`, `ZarrGroup.delete` (F6); `ZarrArray.withWriteEmptyChunks`
(F7). What remains is P2's F3, F4, and F8–F14, and P3's D3, D4, and B1.

**Update (2026-10-06): P2's F8–F12 are done too.** 465 Zarr tests, 16 more under a small heap, and 63
core tests pass; zarr-python 3.4 reads all 197 arrays `check_zarr_writer.py` checks, libzstd all 101
frames `check_zstd_encoder.py` checks, and c-blosc 240 Blosc buffers. Behaviour changes worth knowing:
- Falcon's zstd output is smaller and slower to make than before, and follows the configured `level`
  (F12); Blosc's `clevel` now sets its zstd level;
- `group.child("a/b")` is a path lookup where it used to be refused (F9);
- nested shards open (F11).

New API: `readUnsignedLongs`/`writeUnsignedLongs`, `readComplex`/`writeComplex` (F8);
`Zarr.open(store, path)`, `openGroup`/`openArray(store, path)`, paths in `child`/`group`/`array` (F9);
`ZarrArray.blocks(long...)`, `innerChunkShape()` (F10); `ArraySpec.Builder.zstd(level)`, and in core
`ZstdEncoder.compress(data, level, checksum)` (F12). What remains is P2's F3, F4, F13, and F14, and P3's
D3, D4, and B1.

**Update (2026-10-06): P2's F13 and F14, and P3's D4 and B1, are done too.** 567 Zarr tests, 18 more under
a small heap, and 72 core tests pass; zarr-python 3.4 reads all 250 arrays `check_zarr_writer.py` checks
and 4 of 4 ZIP archives `check_zip_store.py` checks, and Falcon's core reads 122 of 122 c-blosc2 chunks.
Behaviour changes worth knowing:
- arrays with the extension data types zarr-python writes, rectilinear chunk grids, or storage
  transformers marked `must_understand: false` now open (F14), among them the datetime64 arrays P1's
  fixtures left out; `DataType.equals` compares configurations;
- `ZarrArray.chunkShape()` throws on a rectilinear array (F14);
- `ZipStore` reads its archive itself rather than through `java.util.zip.ZipFile`, and checks each
  entry's CRC-32 on `get` (F13);
- Blosc chunks written by c-blosc2 decode, in Zarr and in HDF5's Blosc filter (F14);
- the zarr compile fails on a missing or malformed Javadoc in the exported packages (D4), and tests run on
  the class path explicitly in every module (B1).

New API: `ZipStore.create`/`open`, `HttpStore.Builder.directoryListing` (F13);
`ArraySpec.Builder.chunkLengths`, `ZarrArray.chunkSizes()`/`isRectilinear()`, the `DataType` factories
`datetime64`, `timedelta64`, `fixedLengthUtf32`, `nullTerminatedBytes`, `rawBytes`, `struct` with
`DataType.Field`, and `DataType.fromJson`/`toJson`/`hasByteOrder`/`unit`/`scaleFactor`/`fields`/
`fieldOffset`/`defaultFillValue` (F14). What remains is P2's F3 and F4, and P3's D3.

**Update (2026-10-06): P3 is done.** D3 rewrote `PLAN.md`'s stale parts (status, packages, the shared-model
note, coverage, the oracle note), and D5's last item pinned `zarr==3.4.0` and `numcodecs==0.17.0`, which
regenerate every Zarr fixture with the same metadata and values. What remains is P2's F3 and F4.

**Update (2026-10-06): P2's F3 and F4 are done, and with them the whole review.** 683 Zarr tests, 18 more
under a small heap, and 83 core tests pass. Checked against zarr-python 3.4:
- zarr-python reads all 273 arrays `check_zarr_writer.py` checks and all 42 v2 arrays Falcon wrote into
  (`check_zarr_v2_writes.py`);
- Falcon reads 40 more zarr-python v2 arrays and 8 v3 ones.

Checked against c-blosc and numcodecs:
- c-blosc decodes every Falcon Blosc buffer, and outside zstd they are c-blosc's bytes;
- Falcon matches 772 numcodecs filter vectors.

Behaviour changes worth knowing:
- Blosc's header follows c-blosc where Falcon's used to differ (F3):
  - a buffer under 128 bytes is stored whole;
  - a buffer stored whole keeps its compressor, split flag, and block size;
  - a type size above 255 keeps its shuffle flag.
- v2 arrays with `U`, `S`, `V`, time, structured, or `|O` dtypes, Fortran order, numcodecs filters, or zlib
  and lz4 compressors now open (F4); a hierarchy listing that skipped them shows them.
- Writes into v2 arrays follow the blosc and zstd configuration, and a v2 gzip without a `level` now writes
  at numcodecs' level 1, not 5 (F4).
- `ZarrArray.codecNames()` of a v2 array names the translated codecs, `numcodecs.<id>` among them (F4).

New API: `ArraySpec.Builder.blosc(cname, clevel, shuffle)`. In core: `BloscEncoder`'s compressor constants,
`compressor(cname)`, and `compress(data, typeSize, shuffle, blockSize, clevel, compressor)`; and
`Lz4.compress`, `compressHc`, and `maxCompressedLength`. Nothing remains open in P0–P3; what is left are the
non-goals below (creating v2 arrays, the extension data types zarr-python does not write, and the rest).

## Do these first — top 10

1. ~~**Z1/Z2 — node replacement destroys or corrupts data.**~~ Done 2026-10-05 (below).
2. ~~**Z3/C1 — chunk cache.**~~ ~~Reads are stale across handles~~ (Z3, done 2026-10-05), ~~and concurrent
   use throws `ConcurrentModificationException`~~ (the cache part of C1, done with Z3). The rest of C1 was
   done 2026-10-06 (below).
3. ~~**Z4 — silent numeric conversion on write.**~~ Done 2026-10-05 (below).
4. ~~**Z5 — the blosc encoder corrupts data when the element size is ≥ 256 bytes.**~~ Done 2026-10-05 in
   core (below).
5. ~~**Z6/Z7 — the zstd decoder accepts corrupt frames and drops trailing frames.**~~ Done 2026-10-05 in
   core (below).
6. ~~**Z8 — bounds and overflow.**~~ Done 2026-10-05 (below).
7. ~~**H1 — bound decompression.**~~ Done 2026-10-06 (below).
8. ~~**I1–I3 — interop.**~~ Done 2026-10-06 (below).
9. ~~**PF1 + F1/F2 — remote use.**~~ Done 2026-10-06: partial shard reads and a shard-index cache
   (PF1), then cloud stores and consolidated metadata (F1/F2).
10. ~~**T1/T2 — test gaps.**~~ Done 2026-10-06 (below).

---

## P0 — silent wrong data / data loss

All of P0 is done (2026-10-05). Each item says what was done, then gives the original finding.

- [x] **Z1 — `createArray` / `createGroup` "replace" a node by overwriting only `zarr.json`.** ✔ **Done
  2026-10-05.** Erich chose refuse-by-default over replace:
    - `createArray` and `createGroup`, on `Zarr` (the root) and `ZarrGroup`, refuse with
      `IllegalArgumentException` a path that holds a node. `createArray` also refuses a path with any key
      under it, since stray chunks would read as the new array's data.
    - New overloads take `overwrite`. It deletes every key under the path (for the root, the whole store),
      the node's metadata first, then writes the new node.
    - zarr-python 3.4 does the same: `ContainsArrayError` by default, and `overwrite=True` deletes the
      prefix.
    - `FileSystemStore` leaves the emptied directories behind; nothing reads them.
    - Tests: `ZarrCreateTest`.

  The original finding follows.
  - **Where:** `ZarrGroup.java:127-131`, `Zarr.java:77-79`.
  - **Defect:** the old chunk keys survive.
  - **Failure:**
    - Re-creating `x` with `fillValue(-1)` reads back the *old* `[1,2,3,4]`.
    - Re-creating it as float32 reads `[1.4E-45, …]` (the old int bits reinterpreted).
  - **Fix:** delete the node's prefix before writing new metadata, or refuse unless an explicit
    `overwrite` flag is passed (see F6).
- [x] **Z2 — `ArraySpec.build()` validates almost nothing, and `zarr.json` is written before it is parsed.** ✔
  **Done 2026-10-05.**
    - `build()` serializes the spec's `zarr.json`, parses it as opening would, and builds the codec
      pipeline. Any failure throws `IllegalArgumentException` before anything touches the store.
    - The pipeline now also refuses a chunk larger than one Java array (2 GB). So the default single chunk
      of a huge array fails at `build()`, not at the first write.
    - Tests: `ZarrCreateTest`.

  The original finding follows.
  - **Where:** `ArraySpec.java:281`, `ZarrGroup.java:129`, `Zarr.java:78`.
  - **Failure:** a bad spec overwrites a good array's metadata and leaves the node unopenable, and the
    parent's `children()` then throws too. Examples:
    - a rank-mismatched chunk shape;
    - a negative shape;
    - wrong-length `dimensionNames`;
    - `sharding(3)` on chunk 8;
    - `gzip(42)`.
  - **Fix:** parse `spec.toJson()` and build the pipeline inside `build()`, throwing
    `IllegalArgumentException` *before* anything touches the store.
- [x] **Z3 — the chunk cache is per-instance and never invalidated by writes through other handles.** ✔
  **Done 2026-10-05.** Erich chose opt-in:
    - A plain `ZarrArray` handle has no cache: every read goes to the store.
    - `withChunkCache(maxBytes)` returns a handle with its own LRU. It is documented to see its own writes,
      but not other handles' or processes' until `clearChunkCache()`.
    - The cache is synchronized, and a write invalidates its key *after* the store changes.
    - A read stamps the cache before it fetches, and its put is dropped if anything was invalidated since.
      So a read that races a write cannot re-cache the old bytes.
    - Tests: `ChunkCacheTest`, `ChunkCacheStampTest`.

  The original finding follows.
  - **Where:** `data/ChunkCache.java`, `ZarrArray.java:25,229-236`, `data/ChunkAssembler.java:138-141`.
  - **Failure:** a reader handle keeps returning old data after `g.array("x").writeInts(...)`, contrary
    to "reflects the store's current contents".
  - **Fix:** share one cache per (store, path), or validate entries by size or etag, or make caching
    opt-in. Evict *after* `store.set`, not before. Concurrency is C1.
- [x] **Z4 — writes convert numbers silently and lossily.** ✔ **Done 2026-10-05.** Erich chose exact or
  error:
    - **Integer types:** a value must be a whole number in range. Otherwise `IllegalArgumentException`
      names the value and its index, and nothing is written.
    - **uint64:** takes doubles in [0, 2^64) exactly.
    - **Float types:** round to nearest, and refuse a finite value beyond the type's range.
      - `float16` rounds once, from the double (`data/Float16`). It matches numpy on 1.6M doubles,
        ties included, and the JDK's `floatToFloat16` on all 2^32 floats.
      - `long` → `float32` rounds once too.
    - **bool:** stores any nonzero value, NaN included, as true, as numpy does.
    - **Also fixed:** `readDoubles` on uint64 misrounded when halving dropped the low bit (2^63 + 1025
      read as 2^63). It now keeps that bit (round to odd).
    - Tests: `ConversionTest`, `Float16Test`.

  The original finding follows.
  - **Where:** `data/Elements.java:106,115,139-153`, `datatype/DataType.java:231`.
  - **Failures:**
    - `writeDoubles` / `writeFloats` into `uint64` saturates at 2^63−1, so `1.8e19` is stored as
      9223372036854775807 even though `readDoubles` decodes it correctly.
    - int8 `writeInts{200,-129,1000}` stores `[-56,127,-24]`.
    - int32 `writeDoubles{2.9,NaN,1e12}` stores `[2,0,-727379968]`.
    - bool `writeDoubles{0.5,NaN}` stores `[0,0]`, where numpy gives true for both.
    - float16 rounds twice (double→float→half), so `1+2^-11+2^-40` stores `1.0`.
  - **Fix:**
    - Range-check and throw a typed error, or document an explicit clamp policy.
    - Fix the uint64 conversion (`v ≥ 2^63 → (long)(v−2^63) ^ Long.MIN_VALUE`).
    - Round double→half directly.
- [x] **Z5 — `BloscEncoder` writes the element size into a 1-byte header field as `(byte) typeSize`, but shuffles with the full size.** ✔
  **Done 2026-10-05, in core** (`core/.../compress/blosc/BloscEncoder.java`):
    - As c-blosc does, a type size above 255 is written as 1 and the data is not shuffled.
    - c-blosc (numcodecs 0.17) decodes Falcon's 256-, 300-, and 1000-byte-element chunks exactly.
    - Tests: `BloscEncoderTest`, `ZarrWriteTest.bloscRoundTripsElementsOf256BytesAndMore`.

  The original finding follows.
  - **Where:** `codec/blosc/BloscEncoder.java:37-38,76`.
  - **Failure:** for raw types with 256-byte or larger elements (`r2048`+), data is corrupted — even in
    Falcon's *own* round trip. c-blosc decodes the 300- and 1000-byte cases to wrong bytes and fails on
    256.
  - **Fix:** do what c-blosc does: when typesize > 255, write typesize 1 and don't shuffle.
- [x] **Z6 — the zstd decoder accepts corrupt frames.** ✔ **Done 2026-10-05, in core** (with Z7; see
  `../hdf5/TODO.md`, *Done — 2026-10-05 (P0: Z6/Z7)*): the shared zstd decoder (`core`) is fixed:
    - **Checksum:** a frame's XXH64 content checksum is verified (XXH64 written from its specification).
    - **Content size:** the declared size must be what the frame decodes to. A size larger than the frame
      could hold is rejected before anything is allocated.
    - **Bitstreams:** every Huffman literal stream and every sequence bitstream must be consumed to its
      last bit.
    - **Blocks:** a block may not decode to more than the frame's block size limit (window size, at most
      128 KiB). A compressed block must carry its sequences header and nothing after an empty one.
    - **Frames:** a match may not reach back before its own frame, and each frame starts with fresh
      entropy tables and repeat offsets.
    - **Several frames** decode to their concatenation and skippable frames are skipped, as libzstd's
      `ZSTD_decompress` does. Any other bytes after the last frame, or a frame cut short, are an error.
  Against libzstd 1.5.7 over this review's 50,400 cases, Falcon now returns data libzstd rejects in none,
  and differs from libzstd's output in none. `ZstdCorruptionTest` holds 436 libzstd vectors. The original
  finding follows.
  - **Where:** `codec/zstd/ZstdDecoder.java:81,144-149,336-364`, `ZstdBitReader.java:66-71`,
    `ZstdHuffman.java:185-191`.
  - **Defects:**
    - The XXH64 content checksum is skipped, never verified.
    - Reading past the front of a bitstream yields zeros forever.
    - Huffman streams and the sequence bitstream are never checked for exact consumption.
    - The decoded size is never compared with the declared Frame_Content_Size.
  - **Failure:**
    - Frames with a checksum: Falcon returned wrong data for 11,561 of 15,129 mutated frames that libzstd
      rejected.
    - Frames without one: 264 of ~15.6K.
    - A 15-byte frame with an empty Huffman stream decodes to 10 invented bytes.
  - **Fix:** implement XXH64; require `bitPos == -1` after each stream; require `op == contentSize` when
    the frame content size is present.
- [x] **Z7 — zstd decodes only the first frame and silently ignores trailing frames and garbage.** ✔
  **Done 2026-10-05, in core** (with Z6): frames decode to their concatenation, skippable frames are
  skipped, and other trailing bytes are an error. vlen-utf8 no longer loses data. The original finding
  follows.
  - **Where:** `ZstdDecoder.java:78-86`.
  - **Failure:** `frame("AAAA")+frame("BBBB")` returns `AAAA`; 73 of 84 two-frame cases came back short.
    `BytesCodec`'s length check catches this for fixed-size types, but **vlen-utf8 loses data silently**.
    Skippable frames are rejected.
  - **Fix:** loop over frames, skip `0x184D2A5?` frames, and error on trailing bytes.
- [x] **Z8 — bounds and overflow in selections and sizes.** ✔ **Done 2026-10-05.**
    - **Selections** are checked as `shape > arrayShape − offset`, never forming `offset + shape`.
    - **Chunk overlaps** end at `origin + min(extent, selEnd − origin)`. `origin + extent` overflowed in
      the last chunk of an array near `Long.MAX_VALUE`.
    - **Grid arithmetic:** `ceilDiv` is `Math.ceilDiv`. Element and chunk counts come from
      `RegularChunkGrid.elementCount`, which is exact and 0 when any dimension is 0.
    - **Opening** an array of more than 2^63 − 1 elements fails with `ZarrUnsupportedException`.
    - Tests: `BoundsTest`. An array of shape `[Long.MAX_VALUE]` reads and writes its last chunk.

  The original finding follows.
  - **Selections:** `offset+shape > arrayShape` overflows (`data/ChunkAssembler.java:42`).
    `select([Long.MAX_VALUE],[1])` is accepted: reads return fill, and writes store a stray key
    `c/2305843009213693951`.
  - **Sizes:** element-count, `ceilDiv`, and `chunkCount` products wrap (`chunk/RegularChunkGrid.java:44,71`,
    `ZarrArray.java:118-124`, `Selection.java:285-291`). Shape `[2^32,2^32]` gives `size()==0`, and
    `blocks()` yields nothing, silently skipping all data.
  - **Fix:** check `shape[i] > arrayShape[i] − offset[i]`; use `Math.*Exact`; reject at parse time.
- [x] **Z9 — `FileSystemStore.set` truncates the file in place.** ✔ **Done 2026-10-05.**
    - `set` writes `.<name>.<random>.tmp` beside the target, then renames it over the target with
      `ATOMIC_MOVE`.
    - Windows refuses to replace a file that another handle has open, a reader's included, so the rename
      retries for about 0.75 s.
    - It does not fsync; the class Javadoc and the guide say so.
    - Test: `StoreTest.aConcurrentReaderSeesWholeValuesOnly`. It fails on the old in-place write (a reader
      saw 0 bytes) and passes now.

  The original finding follows.
  - **Where:** `store/FileSystemStore.java:148`.
  - **Failure:** a concurrent reader sees half-written chunks (2 of 169 reads failed with "gzip decode
    failed"). A crash leaves a truncated chunk or `zarr.json`.
  - **Fix:** write a temp file in the same directory, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`.
- [x] **Z10 — strings and fill values.** **Done 2026-10-05.**
    - **null** is `""` everywhere, as numcodecs writes `None`. A chunk of nulls counts as all fill only
      when the fill is `""`.
    - **NaN payloads:** a NaN other than the canonical quiet NaN is written as a hex fill
      (`"0x7fc00001"`). It survives the round trip, and zarr-python 3.4 reads the bits back.
    - **Numeric fills:** `fillValue(long)` and `fillValue(double)` convert as the writers do, then write
      the type's own JSON: `1` for an integer type, `true` for bool, hex for a NaN payload. A value the type
      can't hold fails `build()`.
      - zarr-python 3.4 turned out to read an integer fill of `1.0` as well, so this was less urgent than
        the review thought.
    - Tests: `ConversionTest`, `StringArrayTest`, `DataTypeTest`.

  The original finding follows.
  - **`null` handling is inconsistent** (`data/StringChunks.java:425-431` vs `codec/VlenUtf8.java:370`). ✔
    With fill `"zz"`, a null in a mixed chunk is stored as `""`, but the nulls in an all-null chunk read
    back as `"zz"`.
  - **NaN payloads are lost:** any NaN fill is serialized as `"NaN"`, so `"0x7fc00001"` doesn't
    round-trip (`datatype/DataType.java:257-258`).
  - **Integer fill written as a float:** `fillValue(double)` on an integer dtype writes `1.0`
    (`ArraySpec.java:204-212`). zarr-python ≥ 3.1 likely rejects it.

## P1 — valid stores that fail; hardening; concurrency; remote performance; test gaps

All of P1 is done (2026-10-06). Each item says what was done, then gives the original finding. The work
was split four ways: the core codecs (I8, I11, H3–H4 in Blosc, PF6, the encoder options for I9), the
stores (I7, PF3, PF4, getSuffix, MemoryStore), JSON and metadata (I1, I3, I5, I6, I10, I12, I13, PF5),
and the codec pipeline and data path (the rest).

### Valid stores that fail to read (interop)

- [x] **I1 — the JSON reader rejects bare `NaN`, `Infinity`, and `-Infinity`.** ✔
  **Done 2026-10-06.**
    Bare `NaN`, `Infinity`, and `-Infinity` read as `JsonNumber`s whose literal is the token
    (`doubleValue()` gives the non-finite value; `isFinite()` is false; `longValue()` throws). They are
    written back as the same bare tokens, as Python does; metadata Falcon generates stays strict JSON.
    - zarr-python 3.4 writes these tokens in v3 attributes too, not only v2 `.zattrs`.
    - Tests: `JsonTest.pythonsNonFiniteTokensAreReadAndWrittenBack`, `MetadataTest.attributesMayHoldNaNAndInfinity`,
      `HierarchyFixtureTest.readsNaNAndInfinityAttributes`.

  The original finding follows.
  - **Where:** `json/JsonReader.java:46`.
  - **Defect:** Python's `json.dumps` writes these by default, so they appear in zarr-python 2 /
    xarray `.zattrs` (`_FillValue`, `valid_min`).
  - **Failure:** the node is unopenable, and so is any parent's `children()`.
  - **Fix:** accept them leniently on read.
- [x] **I2 — sharded string arrays fail.** ✔
  **Done 2026-10-06.**
    Sharded string arrays read and write.
    - `ShardingCodec` decodes and encodes shards of `vlen-utf8` sub-chunks through the inner pipeline;
      `ChunkPipeline`'s string path (`decodeStringChunk`, `encodeStrings`) handles `vlen-utf8` or a shard of
      it, so the outer pipeline needs no string codec.
    - A partial read fetches only the sub-chunks it overlaps; a sub-chunk holding only the fill is omitted
      on write. A write re-encodes the whole shard (PF2's reuse is for fixed-size elements).
    - `ArraySpec` builds sharded string arrays (it used to refuse them).
    - zarr-python 3.4 reads Falcon's sharded strings, and Falcon reads zarr-python's (`sharded_string`,
      `sharded_string_partial` fixtures).
    - Tests: `StringArrayTest.shardedStringsRoundTrip`, `DataFixturesTest.shardedStrings`.

  The original finding follows.
  - **Where:** `codec/ChunkPipeline.java:110-115,162`.
  - **Defect:** zarr-python `create_array(dtype=str, shards=…)` builds a pipeline with no outer vlen
    codec.
  - **Failure:** both read and write throw a raw `IllegalStateException`.
  - **Fix:** support vlen-utf8 inside a shard, or reject the array with `ZarrUnsupportedException` when
    the pipeline is built.
- [x] **I3 — `children()`, `arrays()`, and `groups()` parse every child eagerly.** ✔
  **Done 2026-10-06.**
    `children()`, `arrays()`, and `groups()` leave out a child whose metadata is malformed
    or unsupported (`ZarrFormatException`, `ZarrUnsupportedException`); any other failure, such as the store
    failing, still propagates. `childNames()` lists every child, and `child`/`array`/`group(name)` throw that
    child's reason. Documented on each method.
    - Tests: `HierarchyFixtureTest.childrenThatCannotBeOpenedAreLeftOut` (a zarr-python `datetime64` array
      and a v2 `<U8` array beside a good one), `ZarrHierarchyTest.childrenLeaveOutOnlyChildrenThatCannotBeOpened`.

  The original finding follows.
  - **Where:** `ZarrGroup.java:64-92`.
  - **Failure:** one unsupported child (e.g. a v2 `<U8` array next to a `<f8` one) aborts the whole
    listing. This is common in xarray output.
  - **Fix:** return lazy handles, or skip and collect the failures.
- [x] **I4 — transpose before vlen-utf8 is rejected as a format error.** ✔
  **Done 2026-10-06.**
    `transpose` before `vlen-utf8`, zarr-python's order, works: `TransposeCodec` permutes a
    `String[]` as well as bytes, and the string path undoes it after `vlen-utf8` (or a shard of it).
    - A `transpose` after the array→bytes codec, `vlen-utf8` included, is now a format error (it used to be
      accepted and ignored).
    - zarr-python 3.4's transposed string array (`transposed_string` fixture) reads, and zarr-python reads
      what Falcon writes into it.
    - Tests: `StringArrayTest.transposeBeforeVlenUtf8RoundTripsAndAfterItIsRefused`,
      `DataFixturesTest.transposedStrings`.

  The original finding follows.
  - **Where:** `codec/ChunkPipeline.java:69,96-99`.
  - **Defect:** zarr-python writes this ordering. Meanwhile an *invalid* transpose after vlen-utf8 is
    accepted and silently ignored.
  - **Fix:** implement the object transpose or throw Unsupported, and validate `arrayBytesSet`.
- [x] **I5 — metadata forms the spec allows are rejected.** ✔ for codecs
  **Done 2026-10-06.**
    The specification's shorthand is accepted: a codec, chunk grid, or chunk key encoding
    given by its bare name (codec lists are normalized in `ArrayMetadata`, inside `sharding_indexed`'s
    `codecs` and `index_codecs` too); a core data type as `{"name": "int32"}` with no or an empty
    configuration. A data type with a configuration (an extension) is `ZarrUnsupportedException`; any
    other non-string form is `ZarrFormatException`. `"dimension_names": null` means no names, as zarr-python
    reads it.
    - Tests: `MetadataTest.shorthandNamesAreAccepted` (through a pipeline, plain and sharded),
      `anObjectDataTypeWithAConfigurationIsUnsupported`, `nullDimensionNamesMeanNone`.

  The original finding follows.
  - **Where:** `metadata/ArrayMetadata.java:67-69,73,88,112`.
  - **Failure:** the shorthand `"codecs":["bytes"]` and an object-form core `data_type` such as
    `{"name":"int32"}` are both rejected.
  - **Fix:** have `NamedConfig.parse` accept a string, and resolve object data types by name.
- [x] **I6 — v2 translation bugs.**
  **Done 2026-10-06.**
    `"dimension_separator": null` is `"."` (zarr-python 2 wrote it; 3.4 refuses it); a
    non-numeric gzip/zlib level is a `ZarrFormatException`; a complex dtype with `fill_value: null` is
    `[0.0, 0.0]`, which is how zarr-python 3.4 reads it. No raw `JsonException` escapes v2 or v3 parsing
    (`Metadata.wrapJson`; `Fields.integer` refuses `zarr_format: 3.5` or `"3"`).
    - Tests: the `MetadataTest.v2…` tests, `wrongJsonTypesAreFormatErrors`, `HierarchyFixtureTest.readsTheV2Cases`.

  The original finding follows.
  - **Where:** `metadata/V2Metadata.java:84,148,177`.
  - **Failures:**
    - `"dimension_separator": null` throws. ✔
    - A non-numeric gzip `level` leaks a raw `JsonException`, which isn't a `ZarrException`. ✔
    - A complex dtype with `fill_value: null` is translated to `0`, then fails the two-element check.
- [x] **I7 — `HttpStore` problems.**
  **Done 2026-10-06.**
    `HttpStore` (still on `HttpURLConnection`: `java.net.http` would be a module beyond
    `java.base`):
    - **Keys:** every byte of a key's UTF-8 that is not unreserved is percent-encoded; `/` still separates
      segments.
    - **Size:** when a HEAD has no Content-Length (or answers 405/501), a `GET Range: bytes=0-0` gives it from
      `Content-Range` (416: size 0; 200: the body's length).
    - **Absent keys:** `HttpStore.builder(url).missingStatuses(404, 403).build()` opts in to more statuses;
      the default stays 404, since treating a real denial as absent would read it as fill. Applied
      everywhere (get, getRange, getSuffix, size, exists). `builder(url).timeoutMillis(int)` too.
    - **Base URLs:** the query of a presigned or SAS URL is re-appended after each key.
    - **Redirects:** followed by hand (up to 10; 301/302/303/307/308; relative Locations resolved; the
      Range header kept); http→https yes, https→http never.
    - **Bodies:** a whole value is capped at the 2 GB array limit; a 206 longer than asked for, or at the
      wrong offset, fails; a server ignoring Range is read only up to the range.
    - Tests: `HttpStoreTest`. An http URL on raw.githubusercontent.com now follows its 301 to https (P0:
      "HTTP 301").

  The original finding follows.
  - **Where:** `store/HttpStore.java:52,74,119,126,166,171,182`.
  - **Failures:**
    - Keys aren't percent-encoded: `a b`, `50%`, `a#b`, `q?x`, and `café` all fail or hit the wrong
      URL. ✔
    - A HEAD response with no `Content-Length` reports size 0, which breaks sharded reads. ✔
    - Only 404 counts as "missing", but S3/GCS public buckets return **403** for absent keys, so sparse
      arrays throw. ✔
    - A base URL with a query string (presigned or SAS) gets keys appended *inside* the query. ✔
    - http→https redirects aren't followed.
    - The body read has no size cap.
  - **Fix:** encode each path segment; use a configurable missing-status set; split the base URL into
    path and query; follow redirects manually; bound the body read.
- [x] **I8 — interop of Falcon's own encoder output.** ✔
  **Done 2026-10-06.**
    In core:
    - **zstd:** a frame over 128 KiB declares a 128 KiB window (and an 8-byte content size) instead of
      being single-segment. libzstd's streaming API rejected a 150 MB frame ("Frame requires too much
      memory") and reads it now. Frames up to 128 KiB are byte-identical to before.
    - **Blosc:** blocks follow c-blosc's `compute_blocksize` (256 KiB for zstd at clevel 5), equal to
      c-blosc's own in 1,264 compared buffers; c-blosc decodes a 750 MB buffer. A buffer compression does
      not shrink below memcpy is stored as memcpy.
    - Tests: `ZstdEncoderTest.largeFramesDeclareA128KiBWindow`, `BloscEncoderTest.blocksAreSizedAsCBloscSizesThem`;
      `check_zstd_encoder.py` now also decodes through libzstd's streaming API.

  The original finding follows.
  - **zstd** (`ZstdEncoder.java:87`): always writes single-segment frames, so the window equals the
    content size. A 140 MB frame fails in libzstd's streaming API ("Frame requires too much memory").
    Fix: write a 2^17 window descriptor and keep the frame content size.
  - **blosc** (`BloscEncoder.java:52-57`): writes one block per chunk, and c-blosc rejects block sizes
    over ~715 MB. Fix: emit c-blosc-sized blocks.
- [x] **I9 — writes into an existing array ignore its codec configuration.**
  **Done 2026-10-06.**
    Writing follows the configuration, whoever made the array: zstd's `checksum` (each frame
    carries its content checksum; `ZstdEncoder.compress(data, checksum)`), and Blosc's `shuffle`
    (`noshuffle`, `shuffle`, `bitshuffle`), `typesize`, `blocksize`, and `clevel` (the new 5-argument
    `BloscEncoder.compress`). The configuration is validated when the pipeline is built (an unknown
    `cname` or `shuffle`, a `clevel` outside 0..9, a non-positive `typesize`, a negative `blocksize`), and a
    missing field (v2 metadata records none) takes the previous behaviour.
    - ~~**Still not honoured:** zstd's `level` (one encoder level) and a Blosc `cname` other than `zstd`
      (Falcon compresses with zstd inside Blosc; valid, self-describing Blosc, but not the named
      compressor).~~ Both are honoured now: zstd's `level` since F12, the `cname` since F3.
    - Tests: `CodecConfigurationTest` (the frame's checksum flag; Blosc's shuffle flags, type size, block
      size, and memcpy at clevel 0); `check_zarr_writer.py` checks the stored bytes of arrays configured
      that way, and zarr-python reads them.

  The original finding follows.
  - **Where:** `codec/ZstdCodec.java:21-23`, `codec/BloscCodec.java:30-32`.
  - **Defect:** zstd `level` and `checksum`, and blosc `cname`, `clevel`, `shuffle`, and `blocksize`,
    are not honoured. Output is still readable (blosc is self-describing), but it doesn't match the
    metadata.
  - **Fix:** honour the configuration or reject it (see F3).
- [x] **I10 — `must_understand` is inverted.**
  **Done 2026-10-06.**
    An unknown member fails to open (`ZarrUnsupportedException`) unless it is an object
    with `"must_understand": false`. Groups allowlist `consolidated_metadata`, in any form, null included.
    zarr-python 3.4 writes nothing else unknown (arrays add only `storage_transformers: []`, which Falcon
    knows) and itself refuses extra keys, so Falcon is no stricter; every fixture still opens.
    - Tests: `MetadataTest.anUnknownFieldIsIgnoredOnlyWithMustUnderstandFalse`,
      `consolidatedMetadataInAGroupIsIgnored`, `HierarchyFixtureTest.readsAConsolidatedGroupTree`.

  The original finding follows.
  - **Where:** `metadata/Fields.java:106-118`.
  - **Defect:** unknown members are ignored unless they say `must_understand: true`. The v3 spec's
    default is *true*: fail unless `must_understand: false`. ✔
  - **Fix:** invert the rule, with an allowlist for zarr-python's `consolidated_metadata`.
- [x] **I11 — zstd frame coverage.**
  **Done 2026-10-06.**
    FSE accuracy logs above RFC 8878's limits (9 for literal and match lengths, 8 for
    offsets) are refused. Offset codes 29–31 were already read since P0's Z6: real libzstd long-window
    frames with codes 29 and 30 (600 MB, 1.1 GB) decode exactly; code 31 needs more than 2 GiB of output,
    which no Java array holds. Dictionaries stay unsupported (rejected, correctly).
    - Tests: `ZstdLimitsTest.accuracyLogsAboveTheFormatsLimitsAreRefused`, `offsetCodesUpTo31AreRead`.

  The original finding follows.
  - Offset codes 29–31 in FSE mode (long-window frames such as `--long=29..31`) are rejected.
  - FSE accuracy logs above the RFC limits (LL/ML 9, OF 8) are accepted.
  - Dictionaries aren't supported (rejecting them is correct).
  - **Where:** `ZstdFse.java:77-80`, `ZstdDecoder.java:317-322`.
- [x] **I12 — node names.**
  **Done 2026-10-06.**
    A new node (`createGroup`/`createArray`) refuses a name that is empty, contains `/`,
    consists only of periods, starts with `__`, is a metadata key (`zarr.json`, `.zarray`, `.zgroup`,
    `.zattrs`, `.zmetadata`), or ends in `.` or a space. Looking up an existing child keeps only the
    structural rules, so an existing `__meta` child still opens. `FileSystemStore` refuses key segments
    that end in `.` or a space or contain `\` (Windows splits paths at a backslash), on every platform,
    and its listings leave such names out.
    - Tests: `ZarrHierarchyTest.newNodeNamesFollowTheSpecification`, `StoreTest`.

  The original finding follows.
  - **Where:** `ZarrGroup.java:142`.
  - **Defect:** spec-reserved names (`zarr.json`, `__…`) are accepted. ✔ On Windows, `FileSystemStore`
    treats `data./zarr.json` and `data/zarr.json` as the same file. ✔ There is no path escape.
  - **Fix:** validate names per the spec, and reject segments that end in `.` or a space.
- [x] **I13 — JSON fidelity.** ✔ for the surrogate
  **Done 2026-10-06.**
    A lone surrogate is written as a `\uXXXX` escape; a repeated key is a parse error
    (`ZarrFormatException` for metadata); `Json.parse(byte[])` decodes UTF-8 strictly; `new JsonNumber(...)`
    validates the RFC 8259 grammar (plus the three non-finite tokens).
    - Tests: four in `JsonTest`; `MetadataTest.wrongJsonTypesAreFormatErrors`.

  The original finding follows.
  - **Where:** `json/JsonWriter.java:108`, `json/Json.java:239`, `json/JsonNumber.java:146`.
  - **Defects:**
    - A lone surrogate is written as `?`.
    - Duplicate keys are silently last-wins.
    - Invalid UTF-8 is silently replaced with U+FFFD.
    - `new JsonNumber("1.2.3")` emits invalid JSON.
  - **Fix:** use strict UTF-8 codecs and validate the literal.

### Corrupt-input hardening

- [x] **H1 — bound all decompression.**
  **Done 2026-10-06.**
    Every bytes→bytes codec decodes against a limit (`BytesBytesCodec.decode(input, maxSize)`):
    the array→bytes codec's encoded size (exact for `bytes`; for a shard, its sub-chunks' bounds plus the
    index), grown stage by stage through each codec's `maxEncodedSize` (a compressor's bound is
    2 × size + 64 KiB, generous on purpose; it only has to stop a bomb).
    - gzip reads at most the limit (`readNBytes`), zstd passes it to the decoder, and Blosc refuses a header
      claiming more before decoding (`BloscDecoder.decompress(src, maxSize)`, new in core, refuses before
      allocating; even without a maximum, core now checks a memcpy header before allocating).
    - A shard's sub-chunks are bounded by the sub-chunk size. zstd raw and RLE blocks were already capped
      by Z6.
    - **Not bounded:** a `vlen-utf8` chunk, whose decoded size is not known in advance; only the 2 GB a Java
      array holds limits it. Documented.
    - Tests: `DecodeBoundsTest` (a 256 MB gzip bomb, 8 KB of zstd RLE blocks claiming 256 MB, a Blosc
      header claiming 2 GB, a bomb inside a shard, gzip inside gzip), run under `-Xmx128m`; core's
      `aDeclaredSizeOverTheMaximumFailsBeforeAllocating`.

  The original finding follows.
  - **Gap:** the pipeline knows the exact decoded chunk size but never passes it to
    `BytesBytesCodec.decode`.
  - **Failures (✔):**
    - gzip: `readAllBytes` is unbounded, so a 1.5 MB chunk gives OOM (`codec/GzipCodec.java:239-240`).
    - zstd: an 11-byte frame declaring FCS=0x7FFFFFF0 gives OOM (`ZstdDecoder.java:128`).
    - zstd: RLE and raw blocks aren't capped at 128 KiB, so 2,000 RLE blocks try to produce 4 GiB
      (`:160-166,457-463`).
    - blosc: a memcpy header with nbytes≈2^31 gives OOM, and so does a blocksize larger than nbytes
      (`BloscDecoder.java:82,120`).
  - **Fix:** thread `expectedSize` through the codec chain and fail as soon as output exceeds it.
- [x] **H2 — raw RuntimeExceptions escape instead of `ZarrFormatException`.** ✔
  **Done 2026-10-06.**
    The rest:
    - **`VlenUtf8`:** lengths are compared without forming `off + length`.
    - **Shard indexes:** each entry is checked when the index is read. Empty means both fields all-ones;
      otherwise the offset and length must be non-negative as signed (below 2^63), must not overflow
      `offset + length`, and the length must fit one array. A range past the end of the shard fails as
      "truncated" when fetched.
    - **Core:** an LZ4 length is refused as soon as it exceeds the output left (a literal length near
      `Integer.MAX_VALUE` wrapped negative and threw a raw `IndexOutOfBoundsException`). BloscLZ's
      accumulator was already safe.
    - Tests: `DecodeBoundsTest.aVlenLengthThatOverflowsIsAFormatError`, `shardIndexEntriesAreChecked`,
      core's `Lz4Test.aLiteralLengthNearIntMaxIsRefused`; the T2 fuzzing.

  The original finding follows.
  - **zstd:**
    - Header bytes aren't bounds-checked, giving AIOOBE (`ZstdDecoder.java:190-414`).
    - The Huffman weight guard is off by one: 257 weights give AIOOBE, and symbol 256 aliases to 0
      (`ZstdHuffman.java:97-111,140`).
  - **blosc:**
    - typesize 0 + bit-shuffle → `/ by zero` (`BitShuffle.java:22`).
    - `HEADER_LENGTH + 4*blockCount` and `HEADER_LENGTH + nbytes` overflow `int` (`BloscDecoder.java:89,106`).
    - Internal-zstd `ZstdFormatException`s escape `BloscCodec` unwrapped (`BloscDecoder.java:184`).
    - The length accumulators in `BloscLz.java:38-45` and `Lz4.java:37,62-65` overflow.
  - **Other codecs and metadata:**
    - `VlenUtf8.java:356`: `off+length` overflows → SIOOBE.
    - Shard index entries are never validated (`ShardingCodec.java:169-176,208-228`). An entry counts as
      empty only if *both* fields are all-ones. Offsets ≥ 2^63, `off+len` overflow, and ranges past the
      shard all pass.
    - ~~Chunk byte size (`count × elementSize`) can overflow (`codec/Pipelines.java:311-325`). A `[2^29]`
      float64 chunk gives `byte[0]`, then AIOOBE.~~ Done with Z2/Z8: building the pipeline refuses a chunk
      over 2 GB.
  - **Fix:** validate every header field, do size arithmetic in `long`, and validate shard entries
    against the shard size and the index range.
- [x] **H3 — denial of service.** ✔
  **Done 2026-10-06.**
    Both:
    - **JSON:** `bigIntegerValue()` refuses more than 4,300 integer digits (Python's bound,
      `JsonNumber.MAX_INTEGER_DIGITS`), judged from precision and scale before building the integer, and
      `longValue()` more than 19; a nonzero value below 1 fails at once (`1e-20000000` also built 10^N).
      Messages quote at most 40 characters of a literal. `1e20000000` now fails in milliseconds.
    - **Blosc:** the block-end lookup is a binary search (262,144 blocks: over 2 s, now 0.05 s).
    - Tests: `JsonTest.anAbsurdIntegerIsRefusedQuickly`, `MetadataTest.anAbsurdExponentFailsFastWithAShortMessage`,
      core's `BloscHeaderTest.manyBlocksDecodeInLinearithmicTime`.

  The original finding follows.
  - `"fill_value": 1e20000000` (≈250 bytes of JSON) takes **25.7 s** to open and builds a 20 MB
    exception message (`json/JsonNumber.java:197-204`). Reject when `precision−scale` is greater than
    about 20.
  - Blosc `nextOffsetAbove` is quadratic: a 4 MB input runs for minutes (`BloscDecoder.java:225-232`).
    Sort once instead.
- [x] **H4 — mirror c-blosc's header sanity checks.**
  **Done 2026-10-06.**
    Blosc headers are checked as c-blosc 1.21.7 checks them, each rule probed with numcodecs:
    format version exactly 2; the reserved flag bit 0x08 clear; type size at least 1 (memcpy too); block
    size from 1 to `nbytes` and at most 715,827,542 (memcpy too); a memcpy buffer exactly `nbytes + 16`;
    compressor codes 5–7 refused before decoding; `nbytes` 0 is empty before any other check. With both
    shuffle flags set, a type size above 1 is byte-unshuffled, as c-blosc does. Every existing vector and
    fixture still decodes.
    - Tests: core's `BloscHeaderTest` (`headersCBloscRefusesAreRefused`,
      `bothShuffleFlagsMeanTheByteShuffleForWideTypes`, `forOneByteTypesTheBitShuffleFlagApplies`).

  The original finding follows.
  - **Where:** `BloscDecoder.java:64-101,131-137`.
  - **Checks:** typesize ≥ 1; the reserved 0x08 flag clear; version == 2; blocksize ≤ nbytes; not both
    shuffle flags.
  - **Failure:** with both shuffle flags set, Falcon bit-unshuffles while c-blosc byte-unshuffles.
- [x] **H5 — define the exception contract.**
  **Done 2026-10-06.**
    The contract is in the `com.ebremer.falcon.zarr` package documentation and the guide's
    *Errors and threads*:
    - **The store:** a `ZarrException` (`ZarrFormatException`: malformed metadata, chunks, or compressed data;
      `ZarrUnsupportedException`: a feature Falcon lacks; plain: I/O, a selection too large for one array,
      a typed read or write the data type does not support).
    - **The call:** the JDK's exceptions (`IllegalArgumentException`, `IndexOutOfBoundsException`,
      `NoSuchElementException`, `IllegalStateException`, `UnsupportedOperationException`).
    - The CME is gone with the cache (Z3/C1), and no `JsonException` escapes (I6). `RobustnessTest` now
      accepts only `ZarrException` for bad stored data (T2).

  The original finding follows.
  - **Gap:** callers can get IOOBE, IAE, `NoSuchElementException`, ISE, UOE, CME, and a leaked
    `JsonException`, alongside the documented `ZarrException`s.
  - **Fix:** decide which unchecked exceptions are legitimate for *caller* errors, versus `ZarrException`
    for *data* errors.

### Concurrency

- [x] **C1 — the chunk cache is an unsynchronized, access-ordered `LinkedHashMap` that `get()` mutates.** ✔
  **Done 2026-10-06.**
    The remaining parts are done:
    - **Lost updates:** writes to the same chunk, or the same shard, through the same `Store` object take
      turns (`data/ChunkLocks`, striped monitors keyed by the store's identity and the chunk key), so a
      partial write's read-modify-write no longer loses another's. Different store objects or processes
      are not coordinated; documented.
    - **`MemoryStore`** is backed by a `ConcurrentHashMap`; `Store`'s Javadoc states the contract (each call
      atomic; all shipped stores safe for concurrent use).
    - **The contract** is in the package documentation and the guide (*Errors and threads*).
    - Tests (T4): `ConcurrencyTest` (element writes from 8 threads into one chunk, one shard, and one string
      chunk all land; parallel block reads while a chunk is rewritten), `StoreTest` (parallel `MemoryStore`
      writers).

  The original finding follows.
  - **Partly done (2026-10-05, with Z3):** the cache is synchronized and held in a final field. A read
    racing a write can no longer re-cache old bytes, and `blocks().parallel()` on a cached handle no
    longer throws. Still open: `MemoryStore`, lost updates in a shard's read-modify-write, the documented
    contract, and T4.
  - **Where:** `data/ChunkCache.java:36`; the lazy cache creation in `ZarrArray.java:230` isn't safely
    published.
  - **Failures:**
    - `blocks().parallel()` reads threw 266 CMEs.
    - Parallel read+write of distinct chunks returned a stale chunk (silent wrong data).
    - `MemoryStore` lost `zarr.json` under parallel block writes.
    - Two threads writing different inner chunks of one shard lose updates, because a partial shard
      write is a read-modify-write of the whole shard.
  - **Fix:** a synchronized or concurrent LRU with safe initialization. Document the contract on
    `ZarrArray` / `Selection` / `Store` and in the USER_GUIDE, including shard-level write granularity.
    Add concurrency tests.

### Remote / large-data performance

Zarr's main use case is sharded data in object storage, so these matter more than usual.

- [x] **PF1 — partial shard reads.** ✔
  **Done 2026-10-06.**
    A partial read of a shard allocates only the region (`ChunkPipeline.decodeRegion`), and
    asks no size: the index at the end is a suffix read (`Store.getSuffix`, new; one `Range: bytes=-N`
    request on `HttpStore`). A cached handle (`withChunkCache`) also keeps each shard's stored index, so
    many small reads of one shard fetch it once; writes through the handle drop it with the chunk.
    - Measured, 50 reads of one 256×256 sub-chunk of a 64 MiB shard: 3.4 GB allocated and 196 ms before,
      66 MB and 18 ms now.
    - Tests: `PartialChunkIoTest` (the exact store calls: index plus one sub-chunk, no size query; the index
      fetched once on a cached handle).

  The original finding follows.
  - **Where:** `codec/ShardingCodec.java:128-145`, `data/ChunkAssembler.java:135`.
  - **Defect:** every partial shard read allocates and fills a buffer the size of the *whole* shard, and
    re-fetches `size()` and the index each time.
  - **Failure:** 50 reads of one 256² inner chunk from a 64 MiB shard allocated ~3.2 GB and made 150
    requests.
  - **Fix:** decode into a region-sized buffer; cache decoded shard indexes per key; fetch the index with
    a suffix range (`bytes=-N`) so no size query is needed.
- [x] **PF2 — partial shard writes decode and re-encode every inner chunk.** ✔
  **Done 2026-10-06.**
    A write to part of a shard decodes and re-encodes only the sub-chunks it touches; the
    others keep their stored bytes (`ShardingCodec.update`). A shard left with no sub-chunk is deleted.
    - Measured, 64 tile writes into one shard: 214–330 ms before, 14–48 ms now.
    - Tests: `PartialChunkIoTest.aPartialShardWriteKeepsTheBytesOfUntouchedSubChunks` (re-encoding at another
      gzip level would change them), `clearingEveryWrittenSubChunkDeletesTheShard`.

  The original finding follows.
  - **Where:** `data/ChunkWriter.java:119-135`, `ShardingCodec.encode:241-305`.
  - **Failure:** writing 64 tiles one at a time into one shard took 376 ms; a single call took 6 ms.
  - **Fix:** reuse the byte ranges of untouched sub-chunks.
- [x] **PF3 — `FileSystemStore.listDir` / `listPrefix` run `Files.walk` over the entire store on every call.** ✔
  **Done 2026-10-06.**
    `listDir` reads only the prefix's directory; `listPrefix` walks only the directory the
    prefix names. Next to 20k chunk files, `listDir("")` went from 955 ms to 0.8 ms and `listPrefix("g/")`
    from 1088 ms to 1.0 ms. Results match a whole-tree walk for every prefix (`StoreTest`).

  The original finding follows.
  - **Where:** `store/FileSystemStore.java:186-197`.
  - **Failure:** `childNames()` takes ~1 s next to an array with 20k chunks, and minutes at millions.
  - **Fix:** use `Files.list(dir)`, and walk only the prefix's directory.
- [x] **PF4 — `ZipStore` costs.**
  **Done 2026-10-06.**
    `getRange`/`getSuffix` skip through an entry's stream: a STORED entry costs O(1) to reach
    the range, a DEFLATED one inflates only up to the range's end (200 random 4 KiB reads from a 64 MiB
    STORED entry: 8.7 s → 5.5 ms). `pack` writes STORED entries, as zarr-python does. zarr-python 3.4 reads
    a zip Falcon packed, and Falcon reads one zarr-python wrote (`ZipStoreTest`).

  The original finding follows.
  - `getRange` decompresses the whole entry on every call, so sharded data in a zip costs
    O(sub-chunks × shard size).
  - `pack` DEFLATEs entries where the zarr convention is STORED.
- [x] **PF5 — round trips.** Opening a node issues HEAD probes (`hasNode`) and then a GET, so a v2 array
  over HTTP costs ~5 round trips. Consolidated metadata (F2) is the structural fix.
  **Done 2026-10-06.**
    Nodes open by GET alone (`ZarrNode.tryOpen`: `zarr.json`, then `.zarray`, then `.zgroup`),
    with no `exists()` probes: a v3 node is one request, a v2 array three (was five). `child(name)` makes
    one attempt. `childNames()` still checks presence, which is what it reports.
    - Test: `ZarrHierarchyTest.openingANodeFetchesWithoutProbing` (the exact requests).

  The original finding follows.
- [x] **PF6 — zstd decode is 5–8× slower than libzstd** (95–144 vs 752–962 MB/s). ✔
  **Done 2026-10-06.**
    In core: a register-based bit reader (checked against a bit-by-bit reference), the
    predefined FSE tables built once, `arraycopy` for non-overlapping matches (zstd, LZ4, BloscLZ, Snappy)
    and doubling chunks for overlapping zstd ones, and one Inflater and one bitshuffle buffer per Blosc
    decode. Measured back to back (MB/s, P0 → now):

    | Decode | P0 | Now |
    |---|---|---|
    | zstd, libzstd frame of float32 | 99 | 305 |
    | zstd, libzstd frame of text | 133 | 212 |
    | Blosc + zstd, byte shuffle | 362 | 541 |
    | Blosc + zstd, bit shuffle | 245 | 435 |
    | Blosc + LZ4, byte shuffle | 576 | 627 |

    libzstd itself does 1,132 and 663 MB/s on the two frames, so zstd is now 2–4× slower than libzstd
    rather than 5–8×. All 316 libzstd frames, 436 corruption vectors, and 500 c-blosc buffers still decode,
    and a 960k-input fuzz of the old and new decoders differs only where H4 meant it to.

  The original finding follows.
  - The bit reader loops once per bit (`ZstdBitReader.java:34-50`).
  - Predefined FSE tables are rebuilt per block.
  - Match copy goes byte by byte.
  - Blosc allocates a new `Inflater` per stream, and `BitShuffle` a temp buffer per block.
- [x] **PF7 — smaller costs:**
  **Done 2026-10-06.**
    All four:
    - **Edge chunks:** a write covering every element of a chunk that lies inside the array stores it
      without reading it back (`PartialChunkIoTest.writingAWholeArrayDoesNotReadItsEdgeChunksBack`).
    - **Transpose:** the source offset is updated as the index advances, and whole runs are copied when the
      last axis stays last (2048×2048 int32 write+read: 86 → 72 ms; [64,256,256] with order [1,0,2]:
      70 → 24 ms).
    - **Typed readers:** the conversion is chosen once per call, then a loop over a typed buffer view
      (readDoubles of 16M float64: 83 → 43 ms; float32: 49 → 28 ms).
    - **`ZarrGroup.toString()`** does no I/O (`ZarrGroup[<path>]`).

  The original finding follows.
  - Whole-array writes read each edge chunk as if it were partially covered (`ChunkWriter.java:110-125`).
  - Transpose does one `arraycopy` per element (`TransposeCodec.java:164-176`).
  - The typed converters switch per element (`Elements.java:26-35`).
  - `ZarrGroup.toString()` does I/O: a full tree walk on a filesystem, and `UnsupportedOperationException`
    on `HttpStore` (`ZarrGroup.java:150`) ✔.

### Test & oracle gaps (what let P0 through)

- [x] **T1 — put zarr-python in the loop for *writes*.**
  **Done 2026-10-06.**
    `tools/fixtures/WriteZarrCases.java` (run with the JDK's source launcher) writes 167 arrays
    with Falcon: every core data type × 10 layouts (plain, gzip, zstd, blosc, gzip+crc32c, big-endian,
    sharded, sharded with the index at the start + zstd, sharded + blosc + crc32c, v2 chunk keys), partial
    writes into shards, five string layouts (sharded ones included), transposed arrays, and codec
    configurations Falcon must honour in arrays it did not create. `tools/fixtures/check_zarr_writer.py`
    reads every one with zarr-python 3.4 and checks each element against the same formula: all 167 match.

  The original finding follows.
  - **Gap:** the hermetic tests never check Falcon-written arrays with zarr-python, so the bugs in Z4,
    Z5, and Z10 can't be caught.
  - **Fix:** add `tools/fixtures/check_zarr_writer.py` (like `check_zstd_encoder.py`) covering every
    dtype, codec, and sharding layout, and run it before every release.
- [x] **T2 — `RobustnessTest` is too permissive.**
  **Done 2026-10-06.**
    `RobustnessTest` accepts only a `ZarrException` for bad stored data (the old
    `IndexOutOfBoundsException` was the superclass of the AIOOBE it meant to catch). Added: mutated
    `zarr.json` (600 one-byte flips, deletions, insertions), shard indexes without a checksum (random
    entries, through whole reads, partial reads, and partial writes), damaged `vlen-utf8` lengths, and a
    bit flipped in each chunk of 14 zarr-python stores (Blosc with lz4, blosclz, zlib, zstd, both
    shuffles; type size 300; sharded and transposed strings; zstd with checksums and several frames). The
    H1 bombs are `DecodeBoundsTest`. Both run in a new `fuzz` execution under `-Xmx128m -Xss256k`. The
    codec decoders themselves are fuzzed in core's `CompressionRobustnessTest`.

  The original finding follows.
  - **Defect:** `assertHandled` catches `IndexOutOfBoundsException` — the *superclass* of AIOOBE and
    SIOOBE, which its own Javadoc calls defects — plus IAE, ISE, and `ArithmeticException`. The codec
    truncation tests use `assertThrows(RuntimeException.class)`.
  - **Fix:** narrow both to the `ZarrException` hierarchy (plus documented caller errors).
  - **Add fuzzing for:**
    - the blosc, lz4, snappy, and blosclz decoders (none today);
    - shard indexes *without* crc32c;
    - vlen length fields;
    - JSON;
    - the H1 bombs, under a small `-Xmx`.
- [x] **T3 — fixture coverage.** Every zarr-python fixture is a root array. Add:
  **Done 2026-10-06.**
    New zarr-python 3.4 fixtures, from two new generators (the existing fixtures are untouched):
    - `gen_zarr_p1_fixtures.py`: a group hierarchy, consolidated metadata, NaN/Infinity attributes, the v2
      cases of I6, and unopenable children beside good ones (`HierarchyFixtureTest`).
    - `gen_zarr_data_fixtures.py` (also numcodecs and zstandard): sharded strings (whole and partial),
      transposed strings, `index_location: "start"`, zstd frames with checksums and two frames in a chunk,
      c-blosc with a 300-byte type size, and a 140 MB chunk (17 KB stored; more than libzstd's block size)
      (`DataFixturesTest`).

  The original finding follows.
  - groups and hierarchies;
  - `consolidated_metadata`;
  - NaN / Infinity attributes;
  - sharded and transposed strings;
  - `index_location: "start"` from zarr-python;
  - zstd frames with checksums and multiple frames;
  - blosc with typesize > 255;
  - chunks over 128 MB.
- [x] **T4 — concurrency tests** for C1 once the contract is chosen.
  **Done 2026-10-06.**
    `ConcurrencyTest` (see C1).

  The original finding follows.

## P2 — features & API

Items 1–5 of the previous TODO's top-5 are F1–F5 below. All of P2 is done (2026-10-06); each item says
what was done, then gives the original finding.

- [x] **F1 — cloud object stores (S3 / GCS / Azure).** The primary Zarr use case. First add `HttpStore`
  hooks (auth and custom headers, the missing-status policy, presigned URLs; see I7) so `HttpStore` can
  serve public and presigned buckets. (carried over)
  **Done 2026-10-06.**
    Both halves, as Erich chose ("S3 store + HttpStore hooks"):
    - **`HttpStore` hooks:** `builder(url).header(name, value)` and `requestHeaders((method, uri) -> headers)`,
      called for each request. Headers are checked: a valid name; no control characters; none of the
      headers the store or the JDK owns (`Range`, `Host`, `Content-Length`, …). They go only to the base
      URL's origin, so a redirect to another origin gets none, as curl drops `Authorization`.
    - **`S3Store`:** S3-compatible object storage (Amazon S3, GCS through HMAC keys, MinIO, R2).
      - SigV4 signing (`javax.crypto`, in `java.base`), the body's hash and a session token included;
        anonymous (unsigned) use is read-only.
      - Built from a bucket or an `s3://bucket/prefix` URL: a region, an endpoint, path-style or host-name
        addressing, a prefix, credentials given or from the environment.
      - Range and suffix GETs, HEAD, PUT, DELETE. Listings use ListObjectsV2 with continuation tokens,
        `encoding-type=url`, and the `/` delimiter for `listDir`.
      - S3's error code and message go into the exception; a 301 names the bucket's region (redirects are
        not followed); 5xx and failed connections are retried 3 times; `missingStatuses` as on `HttpStore`.
      - A hand-written XML reader (`java.xml` is beyond `java.base`) that refuses a DOCTYPE and bounds
        nesting.
    - **Not done:** `~/.aws` profiles, instance roles, SSO; Azure Shared Key (SAS URLs work through
      `HttpStore`); GCS OAuth (a bearer token through the `HttpStore` hook works for reads).
    - Tests:
      - `SigV4Test`: AWS's four worked examples from the S3 API reference, and 9 vectors made with botocore
        1.43.108 (a dev-time oracle in the scratchpad, never committed): keys with spaces, unicode, `%`,
        `+`, `=`, `~`; a session token; port 9000; a listing query; a PUT body.
      - `S3StoreTest` against `FakeS3`, an in-process S3 (`jdk.httpserver`, test scope) that verifies each
        signature itself: a Zarr round trip under a prefix, a sharded partial read, paged and encoded
        listings, 403 as absent, a wrong secret or region, retries, parallel reads.
      - `XmlTest` (malformed input, a "billion laughs" DOCTYPE, deep nesting); `HttpStoreTest` (the hooks,
        no headers sent to another origin).

  The original finding follows.
- [x] **F2 — consolidated metadata (read + write).** One fetch instead of one per node; pairs with F1 and
  PF5. (carried over)
  **Done 2026-10-06.**
    As Erich chose ("use when present", snapshot semantics, both as zarr-python 3):
    - **Reading:** `Zarr.open(store)` uses consolidated metadata when present: inline in a v3 group's
      `zarr.json`, or a v2 root's `.zmetadata` (one more request). `Zarr.open(store, false)` and
      `openGroup(store, false)` read node by node. A consolidated group answers `childNames`, `children`,
      `child`, `group`, and `array` from the snapshot, nested groups included, so a v3 tree costs one GET;
      arrays still read chunks from the store. `ZarrGroup.isConsolidated()` says which a group does.
      - A malformed snapshot is a `ZarrFormatException` at open, as zarr-python fails; an unknown `kind` is
        ignored (`must_understand: false`). Entries are parsed lazily, so a bad one affects only itself, as
        I3 has it.
    - **Writing:** `ZarrGroup.consolidate()` walks every node below by its own metadata and writes zarr-python
      3.4's layout: each node's stored document, flat keys ordered by depth and then by NFKC casefold, child
      groups with an empty marker. It refuses v2 nodes (Falcon writes v3 only) and fails, before writing,
      on a malformed node.
    - **Staleness:** creating, resizing, or changing attributes leaves a snapshot stale until `consolidate()`
      runs again; deleting a child also removes it from the group's stored and in-memory snapshot, as
      zarr-python does.
    - Oracles:
      - 4 zarr-python fixtures (`gen_zarr_consolidated_fixtures.py`: v3, v2, a subgroup, a stale snapshot),
        each with a sidecar of zarr-python's two views, which Falcon matches.
      - `WriteZarrHierarchies.java` + `check_zarr_hierarchies.py`: zarr-python 3.4 reads 6 of 6
        Falcon-written hierarchies (21 checks), and its own re-consolidation of a copy lists the same keys in
        the same order.
    - Tests: `ConsolidatedFixtureTest` (with exact request counts), `ConsolidatedTest`.

  The original finding follows.
- [x] **F3 — full blosc encode configurations** (lz4 / lz4hc / zlib + bit-shuffle at a chosen `clevel`),
  and honouring the configured codec parameters on write (I9). (carried over)
  **Done 2026-10-06.**
    Falcon writes Blosc with every internal compressor, and each buffer is c-blosc 1.21's, byte for byte,
    for every compressor but zstd (Falcon's own encoder, unchanged).
    - **Core encoders**, ports checked against the libraries numcodecs 0.17 links:
      - LZ4: `LZ4_compress_fast` (`Lz4.compress`) and LZ4HC levels 1–9 (`Lz4.compressHc`, including lz4
        1.10's "lz4mid" levels 1–2), from liblz4 1.10.0;
      - BloscLZ: c-blosc 1.21.6's `blosclz_compress`, its entropy probe and bail-outs included;
      - Snappy: snappy 1.1.10's `CompressFragment` (1.2.2's since HDF5's S8, 2026-10-06: hdf5plugin
        builds it, and its hash tables reach 2^15 entries);
      - zlib: `java.util.zip`, zlib 1.3.1 like c-blosc's.
    - **The container** follows c-blosc's write rules, each measured against numcodecs' c-blosc:
      - `compute_blocksize` per compressor: the high-ratio codecs' doubled blocks, and the enlargement for
        codecs that split, forced sizes included;
      - `split_block`: every codec but zstd splits a block into type-size streams when the type size is at
        most 16 and a block holds at least 128 elements; the leftover block never splits;
      - each compressor's level: lz4's acceleration is `10 − clevel`; lz4hc, zlib, and BloscLZ take the
        clevel;
      - raw streams, and buffers stored whole (clevel 0, under 128 bytes, or not shrinking). Such a header
        keeps the compressor, the split flag, and the block size, and a type size above 255 keeps its
        shuffle flag.
    - **Kept deviation:** a buffer that is not a whole number of elements is byte-shuffled when the bit
      shuffle is asked for. c-blosc 1.18 and later restore the partial element; 1.17 and older garble it.
    - **Zarr:**
      - `blosc` honours `cname` on write, I9's last gap.
      - `ArraySpec.Builder.blosc(cname, clevel, shuffle)` picks the settings for a new array.
      - New `numcodecs.zlib` {level} and `numcodecs.lz4` {acceleration} codecs, zarr-python 3's names for
        numcodecs' Zlib and LZ4, read and write numcodecs' own bytes. They serve both v3 arrays and F4's v2
        compressors.
    - **New core API:**
      - `BloscEncoder.BLOSCLZ`…`ZSTD` (c-blosc's compcodes, which are also HDF5's Blosc `cd_values[6]`);
      - `BloscEncoder.compressor(cname)`;
      - `BloscEncoder.compress(data, typeSize, shuffle, blockSize, clevel, compressor)`;
      - `Lz4.compress`, `Lz4.compressHc`, and `Lz4.maxCompressedLength`.
    - Oracles:
      - `gen_blosc_encode_vectors.py`: Falcon reproduces 1,512 c-blosc buffers and 468 liblz4 blocks
        exactly (`BloscEncodeVectorsTest`, by SHA-256).
      - `check_blosc_encoder.py`, rewritten to run `WriteBloscCases.java` itself, over 20,027 cases:
        - c-blosc decodes all 18,435 non-empty Falcon buffers;
        - all 13,964 non-zstd buffers outside the kept deviation are c-blosc's bytes;
        - the headers match except for the 1,100 partial-element cases, and 3 zstd buffers with forced
          128-byte blocks, which Falcon stores whole because its zstd frames run larger;
        - ratios equal c-blosc's for blosclz, lz4, lz4hc, and zlib, and are within about 2–3% for zstd.
      - Snappy: numcodecs' c-blosc has none. imagecodecs' c-blosc 1.21.6 decodes all 324 Falcon snappy
        buffers, and snappy 1.2.2 decodes every raw stream.
      - `check_zarr_writer.py` gains 23 arrays: every cname × shuffle, `ArraySpec.blosc`,
        `numcodecs.zlib`/`lz4`, and lz4 inside a shard. Each chunk is checked to be numcodecs' bytes, and
        zarr-python 3.4 reads 273 of 273 arrays.
      - `gen_zarr_numcodecs_fixtures.py`: Falcon reads 7 zarr-python arrays (`numcodecs.zlib`/`lz4`,
        lz4hc).
    - Tests: `BloscCompressorsTest`, `BloscEncodeVectorsTest`, `NumcodecsCompressorsTest`, and additions to
      `Lz4Test` and `CodecConfigurationTest`. `ZarrWriteTest.roundTripsWithBlosc` now writes a chunk that
      compresses; c-blosc stores its old 96-byte chunk whole, and so does Falcon.

  The original finding follows.
- [x] **F4 — Zarr v2 read gaps:**
  - filters (delta, fixed-scale-offset, …);
  - the top-level `zlib` and `lz4` compressors;
  - Fortran (`"F"`) order;
  - `<U` / `|S` / `|O` dtypes.

  (carried over and extended)
  **Done 2026-10-06.**
    `V2Metadata` was rewritten. It translates every v2 array, `.zmetadata` entries included, into a v3
    document, checking each convention against how zarr-python 3.4 reads and writes v2.
    - **Dtypes:**
      - `U<n>` → `fixed_length_utf32`, `S<n>` → `null_terminated_bytes`, `V<n>` → `raw_bytes`;
      - `M8`/`m8` with a unit and multiplier → `numpy.datetime64`/`numpy.timedelta64`; a bare `<M8` gets
        NumPy's generic unit;
      - structured lists, nested too → `struct`, the `bytes` codec's endian taken from the multi-byte
        fields;
      - `|O` with `vlen-utf8` or `vlen-bytes` first in `filters` → `string` or `variable_length_bytes`,
        that object codec becoming the array→bytes codec.
    - **Fill values** read as zarr-python 3.4 reads them. `null` is the type's default (zero, NaT, empty
      text, an all-zero struct). A structured base64 fill is decoded in the dtype's byte order and kept in
      Falcon's object form. A `vlen-utf8` array's numeric fill (zarr-python 2's `0`) reads as its text.
    - **Fortran order:** a `transpose` reversing the axes, ahead of the array→bytes codec, strings
      included.
    - **Filters, then the compressor,** become bytes→bytes codecs after the array→bytes codec, in v2
      order:
      - `gzip`, `zstd`, and `blosc` keep their v3 names, their configurations now translated with
        numcodecs' defaults (gzip level 1, where Falcon wrote 5);
      - Blosc's type size is the element size numcodecs hands c-blosc, after the filters, and its automatic
        shuffle resolves as numcodecs resolves it;
      - every other codec becomes `numcodecs.<id>`, zarr-python 3's name, configured as numcodecs is.
    - **The filters,** new codecs in `codec`, read and write byte for byte as numcodecs 0.17 with NumPy 2
      does: delta, fixedscaleoffset, quantize, bitround, astype, packbits, shuffle, and the checksums
      crc32, crc32c, adler32, fletcher32, and jenkins_lookup3. `zlib` and `lz4` are F3's codecs.
      - `NumpyType` does NumPy's dtype parsing, casts, promotion, and NEP 50 arithmetic.
      - The element type is tracked along the chain, so a filter gets what the previous one made.
      - Where NumPy is undefined (a NaN or out-of-range float cast to an integer), Falcon converts as Java
        does and wraps; such cases are left out of the vectors.
      - In Zarr v3 metadata the byte codecs (shuffle, the checksums) work where zarr-python 3 writes them.
        The element filters, which zarr-python 3 writes before `bytes` as array→array codecs, are refused
        there.
    - **Writing into a v2 array** goes through the same translation, so it now follows the blosc and zstd
      configuration (it used to drop it). Falcon writes the chunks zarr-python writes.
    - `ZarrArray.codecNames()` gives the translated list, such as `[transpose, bytes, numcodecs.delta,
      numcodecs.zlib]`.
    - **Refused** (`ZarrUnsupportedException`, naming the id and its role):
      - the `categorize` filter;
      - object codecs other than vlen-utf8/vlen-bytes;
      - bz2, lzma, pcodec, zfpy;
      - subarray, object, and mixed-endian struct fields;
      - zero-length U/S/V.
    - **More lenient than zarr-python 3.4:** nested structured dtypes, a struct's `null` fill, and a
      `vlen-bytes` fill of `0`.
    - Oracles:
      - `gen_zarr_v2_ext_fixtures.py`: 40 zarr-python 3.4 v2 arrays covering every dtype, F order, every
        filter, zlib, lz4, blosc and zstd configurations, and combinations. Falcon reads them, and writing
        the same values into empty copies stores zarr-python's chunks byte for byte where the codecs are
        deterministic.
      - `WriteZarrV2Cases.java` + `check_zarr_v2_writes.py`: Falcon writes into copies of them (reversed
        values, resizes, boxes across chunks). zarr-python 3.4 reads 42 of 42, and the script checks each
        Blosc header's compressor, type size, and shuffle.
      - `gen_numcodecs_filter_vectors.py`: 772 numcodecs vectors, checked both ways byte for byte, plus
        NumPy's promotion table and the quantize scales. It also writes a zarr-python v3 array using
        shuffle and every checksum, which Falcon reads and rewrites to identical chunks.
    - Tests: `V2FixtureTest`, `V2MetadataTest`, `NumcodecsVectorsTest`, `NumcodecsPipelineTest` (with a
      damage fuzz), `NumcodecsFixtureTest`. `HierarchyFixtureTest` reads the v2 `<U8` child it used to skip.

  The original finding follows.
- [x] **F5 — the `vlen-bytes` data type.** (carried over)
  **Done 2026-10-06.**
    `DataType.BYTES` is zarr-python's `variable_length_bytes` (`"bytes"` opens too),
    serialized by `vlen-bytes`; the fill value is base64. `readByteArrays`/`writeByteArrays` on `ZarrArray`
    and `Selection` take and give `byte[][]`, each array the caller's own.
    - The string path became a variable-length one: `VlenCodec` (`vlen-utf8`, `vlen-bytes`) replaces
      `VlenUtf8`, `VlenChunks` replaces `StringChunks`, and a chunk is a `String[]` or a `byte[][]`. Bytes
      therefore get everything strings have: compression, sharding, partial shard reads, `transpose` before
      the codec. A codec that does not match the data type (`vlen-utf8` on bytes) is a format error.
    - zarr-python 3.4's arrays read (`bytes_plain`, `bytes_sharded`, `bytes_transposed`, from the new
      `gen_zarr_bytes_fixtures.py`); zarr-python reads what Falcon writes (7 more `check_zarr_writer.py`
      cases); the fuzz runs over the new fixtures.
    - Zarr v2's `|O` with a `vlen-bytes` filter reads since F4.
    - Tests: `BytesArrayTest`, `DataFixturesTest.variableLengthBytes`.

  The original finding follows.
- [x] **F6 — a mutation API:**
  **Done 2026-10-06.**
    **Resize:** `ZarrArray.resize(long...)`, as Erich chose:
    - **Shrinking** deletes every chunk wholly outside the new shape.
    - **Growing** first sets to fill the part of each old edge chunk that comes inside, so values cut
      off by a shrink never reappear, whoever shrank the array. zarr-python's own resize brings them
      back (checked: 70 and 5 reappear after (13,7) → (10,5) → (13,7)).
    - The current shape is read from the store, and only `shape` is rewritten in the stored document
      (`zarr.json`, or a v2 `.zarray`); a cached handle drops what it deleted or cleared.
    - zarr-python reads Falcon-resized arrays, sharded, strings, and bytes included (9
      `check_zarr_writer.py` cases).
    - Tests: `ResizeTest`.

    **Attributes and deleting:**
    - `ZarrNode.setAttributes(JsonObject)` replaces and `updateAttributes(JsonObject)` merges top-level
      members, with covariant returns on `ZarrGroup` and `ZarrArray`. Each rereads the stored document,
      rewrites only its attributes (a v2 node's `.zattrs`), parses the result before writing, and returns a
      new handle that keeps the old handle's options.
    - `ZarrGroup.delete(String)` deletes the child and everything under it, metadata keys first, and
      removes it from the group's consolidated metadata; `NoSuchElementException` if there is no such child.
    - zarr-python reads the results (`check_zarr_hierarchies.py`). Tests: `ConsolidatedTest`.

  The original finding follows.
  - resize;
  - update attributes;
  - delete a node;
  - ~~an explicit `overwrite` flag for create~~: done with Z1.
- [x] **F7 — `write_empty_chunks` option.** Writing an all-fill chunk deletes it today, which is correct;
  expose the zarr-python knob.
  **Done 2026-10-06.**
    `ZarrArray.withWriteEmptyChunks(boolean)` returns a handle that stores a chunk a write
    leaves holding only the fill value, rather than deleting it. In a shard, each sub-chunk the write
    touches is then stored too, as zarr-python does. Like the chunk cache, it is a handle option, not
    metadata; handles made from the handle keep it (`withChunkCache`, `resize`). Strings and bytes honour
    it too.
    - zarr-python reads arrays written this way, and every chunk and sub-chunk is checked to be stored
      (6 `check_zarr_writer.py` cases).
    - Tests: `WriteEmptyChunksTest`.

  The original finding follows.
- [x] **F8 — exact unsigned and complex reads.**
  **Done 2026-10-06.**
    Both, without changing what the existing readers accept (whether a type fits is still decided
    by the type, as `readInts` refuses every uint32 array):
    - `readUnsignedLongs()` / `writeUnsignedLongs(long[])` on `ZarrArray` and `Selection`: any unsigned type
      exactly, uint64 as its 64 bits (a value of 2^63 or more is a negative `long` that `Long`'s unsigned
      methods read). A narrower type refuses a value above its maximum, naming it in unsigned decimal.
      `readLongs` on uint64 still throws, now pointing to `readUnsignedLongs`.
    - `readComplex()` / `writeComplex(double[])`: complex64 and complex128 as interleaved (real, imaginary)
      doubles, as numpy lays them out; a complex64 part rounds once to float and a finite part beyond its
      range is refused, as Z4 has it. The numeric readers' messages point to these for complex arrays.
    - zarr-python 3.4's uint64 values up to 2^64 − 1 (most with no exact double) and complex64/complex128
      arrays, NaN and infinite parts, big-endian, and sharded, read exactly (the new
      `gen_zarr_exact_fixtures.py`).
    - Tests: `ExactAccessorsTest`.

  The original finding follows.
  - `readLongs` on uint64 always throws (it's honest, but there's no exact path short of raw bytes).
    Offer `readUnsignedLongs` (raw bits) or allow `readLongs` when all values fit.
  - Add complex helpers.
- [x] **F9 — path-based navigation:** `Zarr.open(store, "a/b/c")` and `child("a/b")`. Today every level
  must be chained.
  **Done 2026-10-06.**
    `ZarrGroup.child`, `group`, and `array` take a path through child groups
    (`root.array("model/layers/weights")`), and `Zarr.open(store, path)` (with `openGroup`/`openArray` and a
    `useConsolidated` form) opens a node by its path from the store's root; `""` or `"/"` is the root.
    - The node's metadata is fetched directly, one request whatever the depth, as zarr-python's
      `open(store, path=...)` does: the groups along the path are not opened. A consolidated group finds the
      node in its snapshot, with no request. A group opened by path uses its own consolidated metadata.
    - A path with an empty name, `.`, or `..` is an `IllegalArgumentException`; `Zarr.open` of a path to
      nothing is a `NoSuchElementException`. Creating and deleting still take one name.
    - Tests: `PathNavigationTest` (exact request counts), and `ZarrHierarchyTest`, whose check that
      `child("a/b")` is refused now expects a path lookup.

  The original finding follows.
- [x] **F10 — sharded `blocks()`.** It yields whole-shard selections, which can be hundreds of MB each.
  Offer inner-chunk iteration.
  **Done 2026-10-06.**
    `ZarrArray.blocks(long... blockShape)` tiles the array in blocks of any shape (C order, edges
    cut to the array), and `innerChunkShape()` is the shape of a sharded array's sub-chunks (zarr-python's
    `chunks`; `chunkShape()` is its `shards`), or the chunk shape when there are none to read alone (no
    sharding, or a transpose before it). `blocks(innerChunkShape())` reads one sub-chunk at a time.
    - A plain handle fetches, per block, the shard's index and that sub-chunk; a cached handle fetches each
      shard's index once, and then exactly the shard's bytes, a sub-chunk at a time.
    - Tests: `BlocksTest` (the request log of a 16-sub-chunk shard read block by block).

  The original finding follows.
- [x] **F11 — nested sharding.** It is written by zarr-python, and Falcon refuses it today.
  **Done 2026-10-06.**
    Nested sharding reads and writes, to any depth, fixed-size and variable-length alike; index
    codecs still may not be sharded.
    - A read of part of a nested shard reads it through a slice of the outer source (`ChunkBytes.slice`),
      fetching only the inner index and the inner sub-chunks it needs: one element of a 16×12 → 8×6 → 4×3
      shard is 184 bytes in 3 requests, and five levels deep a 3-element read is 6 requests. An inner shard
      with a codec after it (`crc32c`, say) is read whole.
    - Writes recurse: PF2's `update` goes through `inner.updateShard`, and `writeEmptyChunks` applies at
      every level. H1's decode limits and H2's index checks hold at every level.
    - Empty inner sub-chunks read as the fill value (the region and fill are passed down; they read as zeros
      before), and `ChunkBytes.of(...).readRange` past the end is empty, as `Store.getRange` is.
    - zarr-python 3.4's nested arrays read (`gen_zarr_nested_fixtures.py`: 2-D, partial, zstd inside with
      `crc32c` after the inner shard, three levels, strings); zarr-python reads Falcon's (6
      `check_zarr_writer.py` cases, each checked to be stored nested).
    - **Not done:** inner shard indexes are not cached, so a cached handle refetches them.
    - Tests: `NestedShardingTest` (request counts, a random write/read model, PF2 at both levels,
      `writeEmptyChunks`, strings and bytes, a damaged inner index), `DataFixturesTest.nestedSharding`,
      `RobustnessTest.corruptNestedShardsWithoutIndexChecksumsAreRejected`, the nested fixtures in the
      bit-flip fuzz.

  The original finding follows.
- [x] **F12 — zstd encoder ratio.**
  **Done 2026-10-06.**
    The encoder (core) now works as libzstd's lazy strategies do:
    - Huffman-coded literals (1 or 4 streams; weights FSE-compressed or direct, each description checked
      by reading it back), or raw or RLE when that is smaller;
    - FSE sequence tables fitted to each block, or RLE, predefined, or repeat, by estimated size;
    - the three repeat offsets, tracked as the decoder tracks them;
    - a hash-chain match finder with greedy, lazy, and lazy2 evaluation, matching across blocks within the
      window;
    - blocks split at 8 KiB steps where byte statistics change, as libzstd's splitter does.
    - **Levels** follow libzstd's table for large inputs: 1 to 22, 0 meaning 3, negative the fastest
      settings. Windows run from 512 KiB (level 1) to 8 MiB (17 and up), so libzstd's streaming API reads
      every frame. `ZstdEncoder.compress(data, level, checksum)`; Zarr's `zstd` `level` takes effect;
      `ArraySpec.Builder.zstd(level)`. Blosc's `clevel` sets its zstd level as c-blosc does, 2c − 1 and 22 at
      9 (for clevels 1 and 3–9, c-blosc's frames are byte-identical to libzstd's at those levels).
    - **Ratios** (frame / input), Falcon / libzstd 1.5.7, with Falcon's before F12 in parentheses:

      | Input | L1 | L3 | L9 | L19 |
      |---|---|---|---|---|
      | noisy float32 (1.000) | 0.883 / 0.897 | 0.883 / 0.883 | 0.883 / 0.896 | 0.886 / 0.894 |
      | smooth float64 (1.000) | 0.933 / 0.951 | 0.933 / 0.932 | 0.933 / 0.953 | 0.937 / 0.923 |
      | 2 MB of Java source (0.371) | 0.242 / 0.249 | 0.215 / 0.227 | 0.198 / 0.196 | 0.190 / 0.177 |
      | a sharded float32 chunk (0.669) | 0.451 / 0.507 | 0.367 / 0.357 | 0.304 / 0.296 | 0.291 / 0.273 |

    - **Speed:** it is slower than before, as the price of the ratio: 30–90 MB/s at levels 1–3 by input
      (before: 65–154), 14–40 at 9, 3–11 at 19. No input found is pathological: 32 MB of zeros, short
      periods, near-repeats, and random bytes encode at 9 MB/s or more at level 19 and 88 MB/s or more at
      level 3.
    - Oracles: libzstd reads 101 of 101 frames one-shot and streaming, every level from −5 to 22 and a 150
      MB frame (`WriteZstdCases.java` + `check_zstd_encoder.py`, which prints the ratio table); c-blosc
      reads 240 of 240 Blosc buffers (clevel 0–9, three shuffles); zarr-python reads every
      `check_zarr_writer.py` array.
    - **Not done:** optimal parsing and binary-tree match finding (levels 16 and up trail libzstd by
      0.01–0.02), treeless literals, dictionaries.
    - Tests: `ZstdEncoderTest` (every level, 3,000 seeded random inputs, Huffman literals, fitted tables,
      repeat offsets, block splits, windows), `BloscEncoderTest.clevelSetsTheZstdLevelAsCBloscDoes`,
      `CodecConfigurationTest` (`level` and `clevel` take effect, `zstd(level)`).

  The original finding follows.
  - **Gap:** there are no Huffman-coded literals, no repeat offsets, and no cross-block matching. Noisy
    float32 stays at ratio 1.000 (libzstd level 3: 0.896), and text compresses to 0.648 (libzstd: 0.365).
  - **Also:** no clevel or window tuning. (carried over)
- [x] **F13 — `ZipStore` writing and `HttpStore` key listing.**
  **Done 2026-10-06.** Both built.
    - **ZipStore writes**, as zarr-python's does in modes "w" and "a": `ZipStore.create(path)` starts an
      archive, `ZipStore.open(path)` adds to one (or starts it). `set` appends a STORED entry to the file at
      once; `close()` writes the central directory, with Zip64 records past 65,535 entries or 4 GiB
      (APPNOTE 6.3.10 §4.3.14–4.3.16, §4.5.3). Everything written reads back before then. A key written
      again appends an entry and the directory names only the newest; `delete` drops it from the directory
      (zarr-python's cannot delete); `pack` compacts, and refuses to pack a store into its own file.
      Appending keeps existing entries (deflated ones, directories, comments), an unchanged session leaves
      the file as it was, and an archive with bytes before it reads. `get` checks each entry's CRC-32.
    - One reader for every mode: the central directory is read by Falcon, not `java.util.zip.ZipFile`. An
      interrupted thread no longer closes the store for everyone (the JDK closes a `FileChannel` on
      interrupt; the store reopens the file).
    - Duplicate names, checked: zarr-python writes a key again as a second entry of the same name. The JDK's
      `ZipFile`, behind the old reader, already returned the last, as Python does (all 8 duplicated names in
      the fixtures), so no data was read wrong; the new reader keeps the last explicitly.
    - **HttpStore listing**, opt-in: `HttpStore.builder(url).directoryListing(true)` lists keys from the
      HTML index pages static file servers make (Python's `http.server`, nginx `autoindex`, Apache
      `mod_autoindex`), as fsspec does; only links to names directly below a directory count (no sort,
      parent, or other-host links). `list`/`listPrefix` walk a page per directory, at most 128 levels down.
      `childNames()` then works over HTTP without consolidated metadata. Off, listing still throws
      `UnsupportedOperationException`, now naming the option.
    - Oracles: Falcon reads zarr-python's archives written in modes "w" and "a" with duplicate names
      (`gen_zarr_zip_fixtures.py`); zarr-python and Python's zipfile read 4 of 4 Falcon-written archives
      (`WriteZipCases.java` + `check_zip_store.py`: a consolidated hierarchy with keys written over and
      deleted, an appended session, 70,002 entries, Zip64 offsets); Falcon lists a zarr-python hierarchy
      served by `python -m http.server` exactly (15 keys; dev-time check).
    - **Not done:** ZIP compression on write (STORED only, as zarr-python), in-place compaction; HTTP
      listings other than HTML pages (WebDAV, JSON), and parallel or cached listing.
    - Tests: `ZipStoreTest` (15 new), `ZipFixtureTest`, `HttpListingTest`, and the ZIP store in
      `StoreTest`'s shared contracts.

  The original finding follows.
  - Both are inherent limits; keep them documented. (carried over)
- [x] **F14 — extensions.**
  **Done 2026-10-06.** All four parts, each checked against its reference implementation both ways where
  one writes it.
    - **The `rectilinear` chunk grid** (zarr-extensions `chunk-grids/rectilinear`, the only other grid
      registered; zarr-python 3.4 behind `array.rectilinear_chunks`) reads and writes.
      - A dimension lists its chunk lengths (bare lengths and `[length, count]` runs) or repeats one; the
        lengths may run past the extent, and a chunk running past it is encoded at its full listed length,
        as zarr-python encodes it. Runs are kept as runs (indexing is a binary search), so a run of 10^18
        chunks opens at once (zarr-python would expand it).
      - `chunk/ChunkGrid` generalises the grid arithmetic; every chunk walk (reads, shard-region reads,
        writes and PF2's shard update, strings and bytes, the chunk cache, `writeEmptyChunks`, F6's resize)
        follows it, and a codec pipeline is built per chunk shape (lazily; a shape the codecs refuse fails
        only where it is touched, and `ArraySpec.build()` checks every listed length).
      - API: `ArraySpec.Builder.chunkLengths(dimension, lengths...)`, `ZarrArray.chunkSizes()`
        (zarr-python's `write_chunk_sizes`), `isRectilinear()`. `chunkShape()` throws
        `UnsupportedOperationException` on a rectilinear array, as zarr-python's `chunks`/`shards` raise;
        `innerChunkShape()` is a sharded one's regular sub-chunk shape; `blocks()` streams the grid's chunks.
      - Shards follow zarr-python: the shard grid is rectilinear, the sub-chunks regular, every length a
        multiple of the sub-chunk's. Resizing follows its `update_shape` (a dimension growing past its
        lengths gains one chunk; shrinking keeps them), with F6's semantics; `chunk_grid` is rewritten only
        then, in zarr-python's compressed form.
      - zarr-python 3.4 reads the 15 rectilinear arrays Falcon writes, and Falcon reads its 14
        (`gen_zarr_rectilinear_fixtures.py`: 1- to 5-D, runs, lengths past the array, a grown-and-shrunk
        array, shards, strings, bytes, a transpose, v2 keys, the extension's own example, a consolidated
        group).
      - Tests: `RectilinearChunkGridTest`, `RectilinearGridTest` (a random model of 120 grids through plain,
        cached, and write-empty handles), `RectilinearFixtureTest`,
        `RobustnessTest.corruptRectilinearArraysAreRejected` (bit flips, and 400 hostile `chunk_shapes` in
        the fuzz JVM).
    - **Extension data types:** the six zarr-python 3.4 writes are read and written —
      `numpy.datetime64` and `numpy.timedelta64` (`readLongs`/`writeLongs`: int64 counts of the unit,
      `Long.MIN_VALUE` for NaT), `fixed_length_utf32` (`readStrings`/`writeStrings`), and
      `null_terminated_bytes`, `raw_bytes`, and `struct` (`readByteArrays`/`writeByteArrays`; a struct as
      whole elements, every number little-endian whatever the stored order; legacy `structured` read too).
      - `DataType`: `datetime64`, `timedelta64`, `fixedLengthUtf32`, `nullTerminatedBytes`, `rawBytes`,
        `struct(Field...)`, `fromJson`/`toJson`, `hasByteOrder`, `unit`/`scaleFactor`,
        `fields`/`fieldOffset`, `defaultFillValue`; six new `DataTypeKind`s; `equals` now compares the
        configuration. Fill values parse and serialise as zarr-python's do (`"NaT"`, base64, struct objects
        or base64); byte order follows the `bytes` codec, recursively through nested structs, as numpy's
        `newbyteorder`; `ArraySpec` writes `endian` only for a type with one. `r*` takes
        `readByteArrays`/`writeByteArrays` too.
      - Through sharding, transpose, every compressor, the chunk cache, resize, `write_empty_chunks`,
        consolidated metadata, and rectilinear grids. The datetime64 arrays P1 left out (`p1_mixed/when`,
        `consolidated_*/when`) now open. An element type over 1 MiB has its fill checked on first decode, so
        a document claiming 2 GB elements allocates nothing on open.
      - zarr-python quirks: its struct default fill casts 0 (`S`/`U` fields "0", time fields the epoch; a
        `V` field cannot be defaulted at all), where Falcon uses each field's default; it compares struct
        chunks to the fill field by field without NaN equality, where Falcon compares bytes (stored chunks
        may differ, values do not).
      - Oracles: zarr-python reads the 38 extension-type arrays `check_zarr_writer.py` gained (each type
        plain, sharded, blosc, partly written with a non-default fill, transposed, big-endian,
        write_empty_chunks, resized); Falcon reads zarr-python's 11 `gen_zarr_extension_fixtures.py` arrays
        element for element. A one-off check: zarr-python reads 12 of 12 Falcon arrays of each type on
        rectilinear grids, sharded and not.
      - Tests: `ExtensionDataTypesTest`, the extension fixtures in `RobustnessTest`'s bit-flip fuzz,
        `RobustnessTest.aHugeDeclaredElementIsNotAllocatedOnOpen`, and updates to `MetadataTest`,
        `HierarchyFixtureTest`, and `ConsolidatedFixtureTest` (datetime arrays now open).
    - **Storage transformers:** none is registered (zarr-extensions' `storage-transformers` holds only a
      README), so Falcon implements none, and now reads past one that says `"must_understand": false`, as
      the v3 specification allows for an extension object; any other is refused by name
      (`ZarrUnsupportedException`). Falcon's rewrites (attributes, resize, consolidate) keep the list.
      zarr-python 3.4 refuses every non-empty list. Unknown codecs stay refused even with
      `must_understand: false`: skipping one would decode the wrong bytes. Tests: `StorageTransformersTest`.
    - **c-blosc2 chunks** (Blosc format versions 3 to 6) read, in core, so Zarr's `blosc` codec and HDF5's
      Blosc filter (32001) both take them; c-blosc 1.x, and so zarr-python, refuses them. Writing stays
      c-blosc's format 2.
      - Read: the 32-byte extended header and headers without it (filters from the flags); the filter
        pipeline, last slot first: byte shuffle (`filters_meta` as the group size), bit shuffle (whole groups
        of 8, the rest copied, as from format 3), XOR delta against the first block, and truncated precision
        (nothing to undo); split and unsplit blocks; zero, run, raw, and compressed streams; memcpy'ed
        chunks; and the header-only chunks of zeros, NaN, one repeated value (of any size), or uninitialised
        values (zeros here). blosclz, lz4/lz4hc, zlib, and zstd, as for Blosc 1.
      - Refused (`UnsupportedCompressionException`, Zarr's `ZarrUnsupportedException`): variable-length
        blocks (format 6), dictionaries, lazy chunks, instrumented chunks, user-defined and plugin codecs and
        filters (bytedelta, int_trunc, ndcell, ndmean, zfp, …), and versions above 6; malformed what c-blosc2
        refuses, plus a split block that is not a whole number of streams (c-blosc2 decodes it wrongly).
        Sizes are checked before allocating.
      - Oracle: c-blosc2 3.3.2, through imagecodecs (`gen_blosc2_vectors.py`): 122 chunks, 95 that c-blosc2
        compressed (every codec × filter, type sizes 1–255, split modes, odd and clamped block sizes, empty
        to multi-block and ragged sizes, memcpy and special-zero chunks) and 27 assembled byte by byte and
        decoded by c-blosc2 (NaN and repeated-value chunks, several filters, `filters_meta`, truncated
        precision, short headers, versions 3, 4, and 6, blocks out of order). All decode exactly.
      - Tests: `Blosc2DecoderTest`, `CompressionRobustnessTest.blosc2SurvivesCorruption`, Zarr's
        `Blosc2ChunkTest`; `BloscHeaderTest` now reads versions 3–6 and refuses 7.
    - **Not done:** registry data types zarr-python does not write (`bfloat16`, `float8_*`, `float6_*`,
      `float4_e2m1fn`, `int2`/`int4`/`uint2`/`uint4`, `complex_*`); ~~v2 dtype strings for the extension types~~ (done with
      F4); per-field struct accessors; storage transformers that must be understood (none exists);
      c-blosc2 variable-length blocks and dictionaries (imagecodecs cannot write them, so there is no
      oracle), bytedelta and the other plugins, lazy chunks and super-chunk frames, and HDF5's Blosc2 filter
      (32026; see hdf5 S9).

  The original finding follows.
  - Non-`regular` chunk grids, storage transformers, extension data types, and blosc2 (format ≥ 3). All
    are refused cleanly today. (carried over)

**Out of scope / deferred:**
- **Creating Zarr v2 arrays** — Falcon creates v3 only. Writing into an existing v2 array works (F4).
- **A shared data model in `com.ebremer.falcon.core`** — investigated and deferred (`PLAN.md` §10): data
  types, byte I/O, checksums, and chunk indexing stay format-specific. The compression codecs did move to
  `core` (2026-10-05), when HDF5's S4 (third-party HDF5 filters) needed Falcon's zstd, Blosc, and LZ4.

## P3 — docs, build, housekeeping

All of P3 is done (2026-10-06).

- [x] **D1 — fix docs that overclaim.** Done 2026-10-06, with H1: the guide now says decompression is
  bounded by what a chunk holds, except a string chunk, bounded only by the 2 GB a Java array holds.
  - ~~`USER_GUIDE.md` (*What is and isn't supported*) says "never … out-of-memory from a bogus declared
    size" (false; see H1).~~
  - ~~README says "corrupt input never … returns wrong data".~~ The README no longer says it; it was
    rewritten with HDF5's P3 D1.
  - ~~"reflects the store's current contents" (false; see Z3).~~ Only groups claim it (`ZarrGroup`, the
    guide), and that is true. After Z3, `ZarrArray`'s Javadoc says a plain handle reads the store each
    time, and a cached one doesn't.
- [x] **D2 — USER_GUIDE gaps.** Done 2026-10-06: the guide's *Errors and threads* gives the
  thread-safety and exception contracts (C1, H5), and *Stores* the `HttpStore` behaviour (I7).
  - ~~the thread-safety contract~~;
  - ~~create/replace semantics~~ and ~~numeric conversion and narrowing rules~~: done with Z1 and Z4
    (*Writing*);
  - ~~`HttpStore` limitations (key encoding, 403 handling)~~.
- [x] **D3 — `PLAN.md` is stale.** Done 2026-10-06.
  - ~~The status block still says "Z0–Z7 complete; Z8/Z9 partially complete" and "228 tests green"~~: it
    now gives the state after P2 (Z0–Z9, P0, P1, P3, and P2 but F3/F4), what is checked against
    zarr-python, libzstd, c-blosc, and c-blosc2 and with what counts, the stores, and 567 + 18 tests.
  - ~~The "Remaining" sentence is garbled~~: it names F3 and F4 and the non-goals.
  - ~~§2 says only the first package is exported, lists a non-existent `util` package, and omits
    `data`~~: four are exported, `util` is gone, `data` is listed, and the tree shows `core` and the
    module's docs.
  - ~~The §2 "shared model note" contradicts the §10 deferral~~: it now points to §10 (no shared model;
    only the codecs are shared, in `core`), as §4's data-model row does.
  - Also corrected: §1 and §5 (extension data types, the rectilinear grid, the `vlen-*` codecs, nested
    sharding, c-blosc2, zstd levels, `S3Store`); §3's Blosc/zstd non-goal (done) and v2 (read since Z8);
    Z8's `HttpStore`, which uses `java.net.HttpURLConnection`, not `java.net.http` (a module beyond
    `java.base`); §8's oracle note, which said zarr-python was not installed. In this file, the top-10
    list said "the rest of C1 is open"; it was done with P1.
- [x] **D4 — Javadoc lint.** Done 2026-10-06. `-Xdoclint:all` over the four exported packages
  (`com.ebremer.falcon.zarr`, `.datatype`, `.json`, `.store`): **0 warnings**, from 308 (245 at the
  review; the API grew). `-Xdoclint:all,-missing` over every package: 0 too.
  - Every public and protected member has a comment, with `@param`, `@return` (or `{@return}` for a
    one-line description), and `@throws` for what it throws: an illegal store key, a store that cannot list,
    a value a type cannot hold. Record components are documented. `JsonObject.Builder` and `MemoryStore`
    have explicit, documented constructors (the implicit ones were public too).
  - Corrected on the way: `DataType.decodeFillValue` and `encodeFillValue` had their descriptions'
    verbs swapped ("Encodes" for the JSON-to-bytes decode, and back); `readDoubles` said "any numeric
    type" (complex is refused); `readLongs`/`readInts` now name what they refuse (uint64; int64, uint32,
    uint64) and that bool reads.
  - **Kept so:** the zarr POM's `default-compile` runs javac with `-Xdoclint:all/protected` limited to the
    four exported packages and `-Werror`, with `showWarnings` on, so a missing tag fails the build and names
    the file and line (checked: removing one `@return` fails the compile). Tests and internal packages are
    not checked. The API F13 and F14 added came in documented.
- [x] **D5 — Python tooling.** `tools/fixtures/requirements.txt` now exists (added with the HDF5 fixes).
  It lists zarr, numcodecs, zstandard, h5py and imagecodecs, with venv instructions.
  - ~~Remaining: pin zarr to the exact fixture version (3.2.1) if byte-identical regeneration matters.~~
    Done 2026-10-06: pinned to `zarr==3.4.0` and `numcodecs==0.17.0`, not 3.2.1. The generators since P1
    were run with 3.4.0, and 3.4.0 regenerates the 3.2.1 fixtures too: every one of the 11 `gen_zarr_*.py` scripts, run with 3.4.0 into a scratch
    copy, wrote the same metadata and the same values, and the same chunk bytes but for gzip's header
    timestamp (and a crc32c after it), one 3.2.1 gzip shard whose streams 3.4.0 compresses 4–5 bytes
    shorter (`sharded_2d_gzip`; decoded identically), and the ZIP fixtures' entry times and order
    (zarr-python writes chunks concurrently). Those bytes are not reproducible under any pin.
- [x] **B1 — the surefire `argLine` `--add-reads com.ebremer.falcon.zarr=jdk.httpserver` prints
  `WARNING: Unknown module` on every test run** (`zarr/pom.xml`). Done 2026-10-06: removed.
  - **Why it was dead:** Surefire 3.5.2 ran every module's tests on the class path, not the module path.
    It reads `module-info.class` with plexus-java 1.3.0's ASM 9.7, which cannot parse a release-25 class
    file ("Unsupported class file major version 69", reproduced), and silently falls back. On the class
    path `com.ebremer.falcon.zarr` is no module (hence the warning), and `jdk.httpserver`, which exports an
    API, is resolved by default, so `--add-modules` was redundant too.
  - zarr's surefire `argLine` is gone (the fuzz execution keeps `@{argLine} -Xss256k -Xmx128m`; JaCoCo's
    agent still attaches under `-Pcoverage`, checked). The test compile keeps `--add-modules` and
    `--add-reads`: it patches the tests into the module.
  - The parent POM now sets `useModulePath` false for every module, with the reason, so a Surefire upgrade
    that can read the descriptor does not move the tests onto the module path unannounced (zarr's tests
    would then need `jdk.httpserver` read). Running the tests inside the module would need a newer Surefire,
    a build-plugin change for Erich to approve, and the runtime flags back.
- [x] **B2 — repo-wide** (done 2026-10-05; details in `../hdf5/TODO.md`, *Done — 2026-10-05 (P3: D2,
  D6, B1–B3)*):
  - a `windows-latest` CI leg;
  - release plumbing, each plugin approved by Erich: sources and Javadoc jars, the enforcer, reproducible
    jars (`outputTimestamp`), and coverage (`mvn verify -Pcoverage`; zarr: 88.7% of lines, 80.6% of
    branches). Zarr's surefire `argLine` starts with `@{argLine}` for the coverage agent, and its
    `--add-reads …=jdk.httpserver` compiler argument is passed to the test compile only;
  - the committed `tools/fixtures/__pycache__/*.pyc` removed and `__pycache__/` ignored;
  - `CLAUDE.md` and the root `pom.xml` call Zarr built. The refusal of `zarr_format 2` in a `zarr.json`
    no longer calls v2 "planned".

## Notes (still accurate)

- **Benchmarks** live in `Benchmarks.java`, skipped unless `-Dfalcon.bench=true`. Results are in
  [`BENCHMARKS.md`](BENCHMARKS.md).
- **Sharding byte-range coalescing** is done: adjacent sub-chunk ranges with gaps up to 8 KiB merge into
  one `readRange`. PF1 is the remaining sharded-read cost.
- **Dev-time tools** (not Falcon dependencies):
  - `gen_zarr_fixtures.py` / `gen_zarr_v2_fixtures.py` — conformance fixtures;
  - `gen_zstd_vectors.py` / `gen_blosc_vectors.py` / `gen_snappy_vectors.py` — decoder vectors;
  - `check_zstd_encoder.py` / `check_blosc_encoder.py` — encoder interop.
