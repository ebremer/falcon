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

## Do these first — top 10

1. **Z1/Z2 — node replacement destroys or corrupts data.** Re-creating an array keeps its old chunks,
   and a bad spec overwrites good metadata.
2. **Z3/C1 — chunk cache.** Reads are stale across handles, and concurrent use throws
   `ConcurrentModificationException`.
3. **Z4 — silent numeric conversion on write.** `uint64` values saturate and narrowing wraps.
4. **Z5 — the blosc encoder corrupts data when the element size is ≥ 256 bytes** (`r2048` and up).
5. **Z6/Z7 — the zstd decoder accepts corrupt frames** (checksum not verified, bitstream underrun, size
   mismatch) **and drops trailing frames**.
6. **Z8 — bounds and overflow.** An offset near `Long.MAX` passes validation, and a size can wrap to 0.
7. **H1 — bound decompression.** Pass the expected size into every bytes→bytes codec; zip bombs and
   bogus header sizes cause OOM today.
8. **I1–I3 — interop.**
   - Bare `NaN` / `Infinity` JSON makes a node unopenable.
   - zarr-python's sharded string arrays fail.
   - One bad child aborts the whole `children()` listing.
9. **PF1 + F1/F2 — remote use.**
   - Partial shard reads fetch and allocate whole shards.
   - Add a shard-index cache.
   - Then cloud stores and consolidated metadata.
10. **T1/T2 — test gaps.** Put a zarr-python oracle in the loop for *writes*, add hierarchy fixtures,
    and tighten `RobustnessTest`. It currently accepts AIOOBE.

---

## P0 — silent wrong data / data loss

- [ ] **Z1 — `createArray` / `createGroup` "replace" a node by overwriting only `zarr.json`.** ✔
  - **Where:** `ZarrGroup.java:127-131`, `Zarr.java:77-79`.
  - **Defect:** the old chunk keys survive.
  - **Failure:**
    - Re-creating `x` with `fillValue(-1)` reads back the *old* `[1,2,3,4]`.
    - Re-creating it as float32 reads `[1.4E-45, …]` (the old int bits reinterpreted).
  - **Fix:** delete the node's prefix before writing new metadata, or refuse unless an explicit
    `overwrite` flag is passed (see F6).
- [ ] **Z2 — `ArraySpec.build()` validates almost nothing, and `zarr.json` is written before it is parsed.** ✔
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
- [ ] **Z3 — the chunk cache is per-instance and never invalidated by writes through other handles.** ✔
  - **Where:** `data/ChunkCache.java`, `ZarrArray.java:25,229-236`, `data/ChunkAssembler.java:138-141`.
  - **Failure:** a reader handle keeps returning old data after `g.array("x").writeInts(...)`, contrary
    to "reflects the store's current contents".
  - **Fix:** share one cache per (store, path), or validate entries by size or etag, or make caching
    opt-in. Evict *after* `store.set`, not before. Concurrency is C1.
- [ ] **Z4 — writes convert numbers silently and lossily.** ✔
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
- [ ] **Z5 — `BloscEncoder` writes the element size into a 1-byte header field as `(byte) typeSize`, but shuffles with the full size.** ✔
  - **Where:** `codec/blosc/BloscEncoder.java:37-38,76`.
  - **Failure:** for raw types with 256-byte or larger elements (`r2048`+), data is corrupted — even in
    Falcon's *own* round trip. c-blosc decodes the 300- and 1000-byte cases to wrong bytes and fails on
    256.
  - **Fix:** do what c-blosc does: when typesize > 255, write typesize 1 and don't shuffle.
- [ ] **Z6 — the zstd decoder accepts corrupt frames.** ✔
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
- [ ] **Z7 — zstd decodes only the first frame and silently ignores trailing frames and garbage.** ✔
  - **Where:** `ZstdDecoder.java:78-86`.
  - **Failure:** `frame("AAAA")+frame("BBBB")` returns `AAAA`; 73 of 84 two-frame cases came back short.
    `BytesCodec`'s length check catches this for fixed-size types, but **vlen-utf8 loses data silently**.
    Skippable frames are rejected.
  - **Fix:** loop over frames, skip `0x184D2A5?` frames, and error on trailing bytes.
- [ ] **Z8 — bounds and overflow in selections and sizes.** ✔
  - **Selections:** `offset+shape > arrayShape` overflows (`data/ChunkAssembler.java:42`).
    `select([Long.MAX_VALUE],[1])` is accepted: reads return fill, and writes store a stray key
    `c/2305843009213693951`.
  - **Sizes:** element-count, `ceilDiv`, and `chunkCount` products wrap (`chunk/RegularChunkGrid.java:44,71`,
    `ZarrArray.java:118-124`, `Selection.java:285-291`). Shape `[2^32,2^32]` gives `size()==0`, and
    `blocks()` yields nothing, silently skipping all data.
  - **Fix:** check `shape[i] > arrayShape[i] − offset[i]`; use `Math.*Exact`; reject at parse time.
- [ ] **Z9 — `FileSystemStore.set` truncates the file in place.** ✔
  - **Where:** `store/FileSystemStore.java:148`.
  - **Failure:** a concurrent reader sees half-written chunks (2 of 169 reads failed with "gzip decode
    failed"). A crash leaves a truncated chunk or `zarr.json`.
  - **Fix:** write a temp file in the same directory, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`.
- [ ] **Z10 — strings and fill values:**
  - **`null` handling is inconsistent** (`data/StringChunks.java:425-431` vs `codec/VlenUtf8.java:370`). ✔
    With fill `"zz"`, a null in a mixed chunk is stored as `""`, but the nulls in an all-null chunk read
    back as `"zz"`.
  - **NaN payloads are lost:** any NaN fill is serialized as `"NaN"`, so `"0x7fc00001"` doesn't
    round-trip (`datatype/DataType.java:257-258`).
  - **Integer fill written as a float:** `fillValue(double)` on an integer dtype writes `1.0`
    (`ArraySpec.java:204-212`). zarr-python ≥ 3.1 likely rejects it.

## P1 — valid stores that fail; hardening; concurrency; remote performance; test gaps

### Valid stores that fail to read (interop)

- [ ] **I1 — the JSON reader rejects bare `NaN`, `Infinity`, and `-Infinity`.** ✔
  - **Where:** `json/JsonReader.java:46`.
  - **Defect:** Python's `json.dumps` writes these by default, so they appear in zarr-python 2 /
    xarray `.zattrs` (`_FillValue`, `valid_min`).
  - **Failure:** the node is unopenable, and so is any parent's `children()`.
  - **Fix:** accept them leniently on read.
- [ ] **I2 — sharded string arrays fail.** ✔
  - **Where:** `codec/ChunkPipeline.java:110-115,162`.
  - **Defect:** zarr-python `create_array(dtype=str, shards=…)` builds a pipeline with no outer vlen
    codec.
  - **Failure:** both read and write throw a raw `IllegalStateException`.
  - **Fix:** support vlen-utf8 inside a shard, or reject the array with `ZarrUnsupportedException` when
    the pipeline is built.
- [ ] **I3 — `children()`, `arrays()`, and `groups()` parse every child eagerly.** ✔
  - **Where:** `ZarrGroup.java:64-92`.
  - **Failure:** one unsupported child (e.g. a v2 `<U8` array next to a `<f8` one) aborts the whole
    listing. This is common in xarray output.
  - **Fix:** return lazy handles, or skip and collect the failures.
- [ ] **I4 — transpose before vlen-utf8 is rejected as a format error.** ✔
  - **Where:** `codec/ChunkPipeline.java:69,96-99`.
  - **Defect:** zarr-python writes this ordering. Meanwhile an *invalid* transpose after vlen-utf8 is
    accepted and silently ignored.
  - **Fix:** implement the object transpose or throw Unsupported, and validate `arrayBytesSet`.
- [ ] **I5 — metadata forms the spec allows are rejected.** ✔ for codecs
  - **Where:** `metadata/ArrayMetadata.java:67-69,73,88,112`.
  - **Failure:** the shorthand `"codecs":["bytes"]` and an object-form core `data_type` such as
    `{"name":"int32"}` are both rejected.
  - **Fix:** have `NamedConfig.parse` accept a string, and resolve object data types by name.
- [ ] **I6 — v2 translation bugs.**
  - **Where:** `metadata/V2Metadata.java:84,148,177`.
  - **Failures:**
    - `"dimension_separator": null` throws. ✔
    - A non-numeric gzip `level` leaks a raw `JsonException`, which isn't a `ZarrException`. ✔
    - A complex dtype with `fill_value: null` is translated to `0`, then fails the two-element check.
- [ ] **I7 — `HttpStore` problems.**
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
- [ ] **I8 — interop of Falcon's own encoder output.** ✔
  - **zstd** (`ZstdEncoder.java:87`): always writes single-segment frames, so the window equals the
    content size. A 140 MB frame fails in libzstd's streaming API ("Frame requires too much memory").
    Fix: write a 2^17 window descriptor and keep the frame content size.
  - **blosc** (`BloscEncoder.java:52-57`): writes one block per chunk, and c-blosc rejects block sizes
    over ~715 MB. Fix: emit c-blosc-sized blocks.
- [ ] **I9 — writes into an existing array ignore its codec configuration.**
  - **Where:** `codec/ZstdCodec.java:21-23`, `codec/BloscCodec.java:30-32`.
  - **Defect:** zstd `level` and `checksum`, and blosc `cname`, `clevel`, `shuffle`, and `blocksize`,
    are not honoured. Output is still readable (blosc is self-describing), but it doesn't match the
    metadata.
  - **Fix:** honour the configuration or reject it (see F3).
- [ ] **I10 — `must_understand` is inverted.**
  - **Where:** `metadata/Fields.java:106-118`.
  - **Defect:** unknown members are ignored unless they say `must_understand: true`. The v3 spec's
    default is *true*: fail unless `must_understand: false`. ✔
  - **Fix:** invert the rule, with an allowlist for zarr-python's `consolidated_metadata`.
- [ ] **I11 — zstd frame coverage.**
  - Offset codes 29–31 in FSE mode (long-window frames such as `--long=29..31`) are rejected.
  - FSE accuracy logs above the RFC limits (LL/ML 9, OF 8) are accepted.
  - Dictionaries aren't supported (rejecting them is correct).
  - **Where:** `ZstdFse.java:77-80`, `ZstdDecoder.java:317-322`.
- [ ] **I12 — node names.**
  - **Where:** `ZarrGroup.java:142`.
  - **Defect:** spec-reserved names (`zarr.json`, `__…`) are accepted. ✔ On Windows, `FileSystemStore`
    treats `data./zarr.json` and `data/zarr.json` as the same file. ✔ There is no path escape.
  - **Fix:** validate names per the spec, and reject segments that end in `.` or a space.
- [ ] **I13 — JSON fidelity.** ✔ for the surrogate
  - **Where:** `json/JsonWriter.java:108`, `json/Json.java:239`, `json/JsonNumber.java:146`.
  - **Defects:**
    - A lone surrogate is written as `?`.
    - Duplicate keys are silently last-wins.
    - Invalid UTF-8 is silently replaced with U+FFFD.
    - `new JsonNumber("1.2.3")` emits invalid JSON.
  - **Fix:** use strict UTF-8 codecs and validate the literal.

### Corrupt-input hardening

- [ ] **H1 — bound all decompression.**
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
- [ ] **H2 — raw RuntimeExceptions escape instead of `ZarrFormatException`.** ✔
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
    - Chunk byte size (`count × elementSize`) can overflow (`codec/Pipelines.java:311-325`). A `[2^29]`
      float64 chunk gives `byte[0]`, then AIOOBE.
  - **Fix:** validate every header field, do size arithmetic in `long`, and validate shard entries
    against the shard size and the index range.
- [ ] **H3 — denial of service.** ✔
  - `"fill_value": 1e20000000` (≈250 bytes of JSON) takes **25.7 s** to open and builds a 20 MB
    exception message (`json/JsonNumber.java:197-204`). Reject when `precision−scale` is greater than
    about 20.
  - Blosc `nextOffsetAbove` is quadratic: a 4 MB input runs for minutes (`BloscDecoder.java:225-232`).
    Sort once instead.
- [ ] **H4 — mirror c-blosc's header sanity checks.**
  - **Where:** `BloscDecoder.java:64-101,131-137`.
  - **Checks:** typesize ≥ 1; the reserved 0x08 flag clear; version == 2; blocksize ≤ nbytes; not both
    shuffle flags.
  - **Failure:** with both shuffle flags set, Falcon bit-unshuffles while c-blosc byte-unshuffles.
- [ ] **H5 — define the exception contract.**
  - **Gap:** callers can get IOOBE, IAE, `NoSuchElementException`, ISE, UOE, CME, and a leaked
    `JsonException`, alongside the documented `ZarrException`s.
  - **Fix:** decide which unchecked exceptions are legitimate for *caller* errors, versus `ZarrException`
    for *data* errors.

### Concurrency

- [ ] **C1 — the chunk cache is an unsynchronized, access-ordered `LinkedHashMap` that `get()` mutates.** ✔
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

- [ ] **PF1 — partial shard reads.** ✔
  - **Where:** `codec/ShardingCodec.java:128-145`, `data/ChunkAssembler.java:135`.
  - **Defect:** every partial shard read allocates and fills a buffer the size of the *whole* shard, and
    re-fetches `size()` and the index each time.
  - **Failure:** 50 reads of one 256² inner chunk from a 64 MiB shard allocated ~3.2 GB and made 150
    requests.
  - **Fix:** decode into a region-sized buffer; cache decoded shard indexes per key; fetch the index with
    a suffix range (`bytes=-N`) so no size query is needed.
- [ ] **PF2 — partial shard writes decode and re-encode every inner chunk.** ✔
  - **Where:** `data/ChunkWriter.java:119-135`, `ShardingCodec.encode:241-305`.
  - **Failure:** writing 64 tiles one at a time into one shard took 376 ms; a single call took 6 ms.
  - **Fix:** reuse the byte ranges of untouched sub-chunks.
- [ ] **PF3 — `FileSystemStore.listDir` / `listPrefix` run `Files.walk` over the entire store on every call.** ✔
  - **Where:** `store/FileSystemStore.java:186-197`.
  - **Failure:** `childNames()` takes ~1 s next to an array with 20k chunks, and minutes at millions.
  - **Fix:** use `Files.list(dir)`, and walk only the prefix's directory.
- [ ] **PF4 — `ZipStore` costs.**
  - `getRange` decompresses the whole entry on every call, so sharded data in a zip costs
    O(sub-chunks × shard size).
  - `pack` DEFLATEs entries where the zarr convention is STORED.
- [ ] **PF5 — round trips.** Opening a node issues HEAD probes (`hasNode`) and then a GET, so a v2 array
  over HTTP costs ~5 round trips. Consolidated metadata (F2) is the structural fix.
- [ ] **PF6 — zstd decode is 5–8× slower than libzstd** (95–144 vs 752–962 MB/s). ✔
  - The bit reader loops once per bit (`ZstdBitReader.java:34-50`).
  - Predefined FSE tables are rebuilt per block.
  - Match copy goes byte by byte.
  - Blosc allocates a new `Inflater` per stream, and `BitShuffle` a temp buffer per block.
- [ ] **PF7 — smaller costs:**
  - Whole-array writes read each edge chunk as if it were partially covered (`ChunkWriter.java:110-125`).
  - Transpose does one `arraycopy` per element (`TransposeCodec.java:164-176`).
  - The typed converters switch per element (`Elements.java:26-35`).
  - `ZarrGroup.toString()` does I/O: a full tree walk on a filesystem, and `UnsupportedOperationException`
    on `HttpStore` (`ZarrGroup.java:150`) ✔.

### Test & oracle gaps (what let P0 through)

- [ ] **T1 — put zarr-python in the loop for *writes*.**
  - **Gap:** the hermetic tests never check Falcon-written arrays with zarr-python, so the bugs in Z4,
    Z5, and Z10 can't be caught.
  - **Fix:** add `tools/fixtures/check_zarr_writer.py` (like `check_zstd_encoder.py`) covering every
    dtype, codec, and sharding layout, and run it before every release.
- [ ] **T2 — `RobustnessTest` is too permissive.**
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
- [ ] **T3 — fixture coverage.** Every zarr-python fixture is a root array. Add:
  - groups and hierarchies;
  - `consolidated_metadata`;
  - NaN / Infinity attributes;
  - sharded and transposed strings;
  - `index_location: "start"` from zarr-python;
  - zstd frames with checksums and multiple frames;
  - blosc with typesize > 255;
  - chunks over 128 MB.
- [ ] **T4 — concurrency tests** for C1 once the contract is chosen.

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
  - an explicit `overwrite` flag for create (see Z1).
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

- [ ] **D1 — fix docs that overclaim.**
  - `USER_GUIDE.md:205-207` says "never … out-of-memory from a bogus declared size" (false; see H1).
  - README says "corrupt input never … returns wrong data" (false; see Z6 and Z7).
  - `ZarrArray`'s Javadoc says it "reflects the store's current contents" (false; see Z3).
- [ ] **D2 — USER_GUIDE gaps.** Document:
  - the thread-safety contract;
  - create/replace semantics;
  - numeric conversion and narrowing rules;
  - `HttpStore` limitations (key encoding, 403 handling).
- [ ] **D3 — `PLAN.md` is stale.**
  - The status block still says "Z0–Z7 complete; Z8/Z9 partially complete" and "228 tests green" (it is
    270).
  - The "Remaining" sentence is garbled ("and benchmarks and …") and lists work that is done.
  - §2 says only the first package is exported (four are), lists a non-existent `util` package, and
    omits `data`.
  - The §2 "shared model note" contradicts the §10 deferral.
- [ ] **D4 — Javadoc lint:** 245 `-Xdoclint:all` warnings (134 missing `@return`); 0 errors.
- [ ] **D5 — Python tooling.** zarr-python isn't installed in the default `python`; the reviewers used
  scratch installs.
  - **Add:** `tools/fixtures/requirements.txt` pinning zarr (3.2.1, the fixture version), numcodecs,
    zstandard, and imagecodecs, with venv instructions.
  - Repo-wide; mirrored in `../hdf5/TODO.md` D5.
- [ ] **B1 — the surefire `argLine` `--add-reads com.ebremer.falcon.zarr=jdk.httpserver` prints
  `WARNING: Unknown module` on every test run** (`zarr/pom.xml`). It is dead; remove it, or fix it so it
  takes effect.
- [ ] **B2 — repo-wide (mirrored in `../hdf5/TODO.md`):**
  - add a `windows-latest` CI leg;
  - release plumbing (source/Javadoc jars, enforcer, reproducible `outputTimestamp`, coverage) —
    **⚠ every new Maven plugin needs Erich's approval** under the dependency gate;
  - remove the committed `tools/fixtures/__pycache__/*.pyc` and ignore `__pycache__/`;
  - `CLAUDE.md` still calls Zarr "Planned / pinned … Do not start it until asked", and the root
    `pom.xml` says "a zarr module is planned". Editing CLAUDE.md is Erich's call.

## Notes (still accurate)

- **Benchmarks** live in `Benchmarks.java`, skipped unless `-Dfalcon.bench=true`. Results are in
  [`BENCHMARKS.md`](BENCHMARKS.md).
- **Sharding byte-range coalescing** is done: adjacent sub-chunk ranges with gaps up to 8 KiB merge into
  one `readRange`. PF1 is the remaining sharded-read cost.
- **Dev-time tools** (not Falcon dependencies):
  - `gen_zarr_fixtures.py` / `gen_zarr_v2_fixtures.py` — conformance fixtures;
  - `gen_zstd_vectors.py` / `gen_blosc_vectors.py` / `gen_snappy_vectors.py` — decoder vectors;
  - `check_zstd_encoder.py` / `check_blosc_encoder.py` — encoder interop.
