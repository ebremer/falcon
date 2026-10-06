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

## Do these first — top 10

1. ~~**Z1/Z2 — node replacement destroys or corrupts data.**~~ Done 2026-10-05 (below).
2. **Z3/C1 — chunk cache.** ~~Reads are stale across handles~~ (Z3, done 2026-10-05), ~~and concurrent use
   throws `ConcurrentModificationException`~~ (the cache part of C1, done with Z3). The rest of C1 is open.
3. ~~**Z4 — silent numeric conversion on write.**~~ Done 2026-10-05 (below).
4. ~~**Z5 — the blosc encoder corrupts data when the element size is ≥ 256 bytes.**~~ Done 2026-10-05 in
   core (below).
5. ~~**Z6/Z7 — the zstd decoder accepts corrupt frames and drops trailing frames.**~~ Done 2026-10-05 in
   core (below).
6. ~~**Z8 — bounds and overflow.**~~ Done 2026-10-05 (below).
7. ~~**H1 — bound decompression.**~~ Done 2026-10-06 (below).
8. ~~**I1–I3 — interop.**~~ Done 2026-10-06 (below).
9. **PF1 + F1/F2 — remote use.** ~~Partial shard reads; a shard-index cache~~ (PF1, done 2026-10-06).
   Cloud stores and consolidated metadata (F1/F2, P2) remain.
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
    - **Still not honoured:** zstd's `level` (one encoder level, F12) and a Blosc `cname` other than `zstd`
      (Falcon compresses with zstd inside Blosc; valid, self-describing Blosc, but not the named
      compressor: F3). Documented in the guide.
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

Items 1–5 of the previous TODO's top-5 are F1–F5 below.

- [ ] **F1 — cloud object stores (S3 / GCS / Azure).** The primary Zarr use case. First add `HttpStore`
  hooks (auth and custom headers, the missing-status policy, presigned URLs; see I7) so `HttpStore` can
  serve public and presigned buckets. (carried over)
- [ ] **F2 — consolidated metadata (read + write).** One fetch instead of one per node; pairs with F1 and
  PF5. (carried over)
- [ ] **F3 — full blosc encode configurations** (lz4 / lz4hc / zlib + bit-shuffle at a chosen `clevel`),
  and honouring the configured codec parameters on write (I9). (carried over)
- [ ] **F4 — Zarr v2 read gaps:**
  - filters (delta, fixed-scale-offset, …);
  - the top-level `zlib` and `lz4` compressors;
  - Fortran (`"F"`) order;
  - `<U` / `|S` / `|O` dtypes.

  (carried over and extended)
- [ ] **F5 — the `vlen-bytes` data type.** (carried over)
- [ ] **F6 — a mutation API:**
  - resize;
  - update attributes;
  - delete a node;
  - ~~an explicit `overwrite` flag for create~~: done with Z1.
- [ ] **F7 — `write_empty_chunks` option.** Writing an all-fill chunk deletes it today, which is correct;
  expose the zarr-python knob.
- [ ] **F8 — exact unsigned and complex reads.**
  - `readLongs` on uint64 always throws (it's honest, but there's no exact path short of raw bytes).
    Offer `readUnsignedLongs` (raw bits) or allow `readLongs` when all values fit.
  - Add complex helpers.
- [ ] **F9 — path-based navigation:** `Zarr.open(store, "a/b/c")` and `child("a/b")`. Today every level
  must be chained.
- [ ] **F10 — sharded `blocks()`.** It yields whole-shard selections, which can be hundreds of MB each.
  Offer inner-chunk iteration.
- [ ] **F11 — nested sharding.** It is written by zarr-python, and Falcon refuses it today.
- [ ] **F12 — zstd encoder ratio.**
  - **Gap:** there are no Huffman-coded literals, no repeat offsets, and no cross-block matching. Noisy
    float32 stays at ratio 1.000 (libzstd level 3: 0.896), and text compresses to 0.648 (libzstd: 0.365).
  - **Also:** no clevel or window tuning. (carried over)
- [ ] **F13 — `ZipStore` writing and `HttpStore` key listing.** Both are inherent limits; keep them
  documented. (carried over)
- [ ] **F14 — extensions:** non-`regular` chunk grids, storage transformers, extension data types, and
  blosc2 (format ≥ 3). All are refused cleanly today. (carried over)

**Out of scope / deferred (unchanged):**
- **Zarr v2 *writing*** — Falcon writes v3 only.
- **`com.ebremer.falcon.core` extraction** — investigated and deferred (`PLAN.md` §10). Revisit if HDF5
  S4 (third-party HDF5 filters) wants Falcon's zstd, blosc, or lz4.

## P3 — docs, build, housekeeping

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
- [ ] **D3 — `PLAN.md` is stale.**
  - The status block still says "Z0–Z7 complete; Z8/Z9 partially complete" and "228 tests green" (it is
    270).
  - The "Remaining" sentence is garbled ("and benchmarks and …") and lists work that is done.
  - §2 says only the first package is exported (four are), lists a non-existent `util` package, and
    omits `data`.
  - The §2 "shared model note" contradicts the §10 deferral.
- [ ] **D4 — Javadoc lint:** 245 `-Xdoclint:all` warnings (134 missing `@return`); 0 errors.
- [x] **D5 — Python tooling.** `tools/fixtures/requirements.txt` now exists (added with the HDF5 fixes).
  It lists `zarr>=3.2,<4`, numcodecs, zstandard, h5py and imagecodecs, with venv instructions.
  - Remaining: pin zarr to the exact fixture version (3.2.1) if byte-identical regeneration matters.
- [ ] **B1 — the surefire `argLine` `--add-reads com.ebremer.falcon.zarr=jdk.httpserver` prints
  `WARNING: Unknown module` on every test run** (`zarr/pom.xml`). It is dead; remove it, or fix it so it
  takes effect.
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
