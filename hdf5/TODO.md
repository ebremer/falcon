# Falcon HDF5 — remaining work (prioritized)

**Status (2026-10-06, after P2 S1–S10, A1–A12, PF1–PF8, WF1–WF11, and all of P3):** build green,
**1128 HDF5 tests** (144 at the review, 187 after the top 10, 206 after P0, 228 after P1, 243 after S1–S3, 256
after S4–S7, 439 after A2–A6, 610 after PF1–PF4, 677 after A1–A10, 692 after A11–A12, 707 after WF1–WF4,
743 after WF5–WF9, 782 after WF7 and WF10, 850 after WF11 and PF5–PF7, 855 after P3, 1117 after S8–S9; the
checksum tests then moved to `core`, S10's ZFP added four, and its Blosc2 and SZ eleven), plus 147 in the `core`
module. The review's top 10, every P1 item, **P2 S1–S10**, **A1–A12**, **PF1–PF8**, **WF1–WF11**,
**every P3 item**, the P0 zstd fix (Z6/Z7, in `core`), and the P0 found with S8 (types libhdf5 refuses in
version-1 object headers) are done (see *Done* at the end). Falcon now:

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
  - the third-party filters LZF, Blosc, LZ4, bitshuffle, Zstandard, bzip2, Blosc2 (b2nd frames
    included), ZFP, and SZ, through Falcon Core's codecs (ZFP's and SZ's lossy values bit for bit libzfp's
    and libSZ's, but for SZ's doubles under a point-wise relative bound);
  - HDF5 1.6.2-era chunked layouts (layout message versions 1 and 2), VAX floats, and File Space Info
    version 0.
- opens files from a path (mapped), from bytes, or through a `RangeReader` (an object store, HTTP
  ranges, any channel) that it reads on demand; the `s3` module's `S3RangeReader` reads one from Amazon S3;
- looks objects up by path, reads integers as floating point as libhdf5 converts them, and reports each
  dataset's layout, chunk shape, filters, and storage size as libhdf5 does;
- reads every datatype class: compound members by name, enumeration names, arrays, complex numbers (as
  libhdf5 converts them), bit fields, opaque data, and time values (as `Instant`s);
- selects regular hyperslabs with gaps and single points, reading only the chunks they touch, and
  reads anything a dataset can from a selection;
- opens a path on a file system that cannot map it, takes its cache sizes as options, and opens the
  other files of a remote file through an application's resolver;
- names an object reached through a reference by the path libhdf5 gives it;
- follows external links, and references into other files, as libhdf5 does, under the same policy or
  resolver as every other file;
- reads only what a read needs:
  - a small read looks its chunks up in the file's chunk index (an array entry, or a B-tree's path), and
    a large one reads the index once, kept for later reads;
  - virtual-dataset reads take from each source only the elements they map to, also for strided
    sources, sources of another shape, and strided or point selections;
  - names are found through the name indexes (see `BENCHMARKS.md`);
  - every handle of an object shares what any of them read of it, in a bounded per-file cache;
  - selected elements come out of each chunk a run at a time;
- writes files that **HDF5 2.0 and 1.14 read, and change**, checked by `tools/fixtures/check_hdf5_writer.py`.
  The last run (2026-10-06) read 548/548 objects with HDF5 2.0 and hdf5plugin 7.1.0; HDF5 1.14.6 last read
  298/298, before S8. Each library then changed every file (an attribute on every object, a dataset in every
  group, a row on every growable dataset, the shared attributes a file names deleted) and read it all back;
  each refused a change left interrupted.
  The P0 edge-case files written by the previous writer fail 19 objects under each version.
- changes existing files in place (`Hdf5Writer.open`), its own and libhdf5's, in either format: adds
  objects, writes into datasets through every built-in filter as libhdf5 encodes it, and through every
  third-party filter it reads as its plugin does (and through virtual datasets into their sources), hard-links, moves and deletes links, changes attributes (in the
  shared-message table too), through a journal that redoes an interrupted change;
- writes szip's nearest-neighbour coding, its encoder a port of libaec's (byte for byte its output), and
  user blocks;
- writes every third-party filter it reads (LZF, Blosc, LZ4, bitshuffle, Zstandard, bzip2, ZFP, Blosc2, and
  SZ) as h5py and hdf5plugin do: the same client data and names, and each chunk the plugin's own bytes, but for
  zstd's frames (Falcon's own, in Zstandard, Blosc, Blosc2, bitshuffle, and SZ's last stage) and SZ's int64
  range, which hdf5plugin's Windows libSZ gets wrong;
- streams what it writes: raw data goes to the file as it is written, so files may pass 2 GB and memory;
  datasets grow (`maxShape`, `append`), and any datatype is written (`createDataset`), with soft and
  external links, and object and region references anywhere (chunks, attributes);
- writes groups and attribute sets of any size: dense storage beyond one heap block and one B-tree node,
  and original-format groups of several B-tree levels;
- writes atomically (temp file, then move), can be aborted, and validates input when it is added;
- verifies metadata and fletcher32 checksums, rejects loops and runaway sizes in corrupt files, survives
  fuzzing under a 128 MB heap and 256 KB stack, confines external files to the HDF5 file's directory by
  default, and supports concurrent reads of one open file.

P0, P1, and P3 are empty, and so is P2 but for its known limits and non-goals: S10, writing Blosc2 and SZ,
was the last open item.

**S10's Blosc2 and SZ (2026-10-06)** write the Blosc2 (32026) and SZ (32017) filters: Falcon Core gains a
c-blosc2 encoder (chunks, frames, b2nd arrays) and an SZ 2.1.12 compressor. `Hdf5Writer.DatasetWriter` gains
`blosc2()`, `blosc2(cname, clevel, filter)`, `szAbsolute(bound)`, `szRelative(ratio)`,
`szPointwiseRelative(ratio)`, and `sz()`; writing into datasets Blosc2 or SZ filters (`open()` refused them)
works.

**S10's ZFP (2026-10-06)** writes the ZFP filter (32013): Falcon Core gains a zfp encoder, every stream libzfp
1.0.1's byte for byte. `Hdf5Writer.DatasetWriter` gains `zfpRate(rate)`, `zfpPrecision(precision)`,
`zfpAccuracy(tolerance)`, `zfpReversible()`, and `zfpExpert(minbits, maxbits, maxprec, minexp)`; writing into
datasets ZFP filters (`open()` refused them) works.

**Shared with Zarr in `core` (2026-10-06)** changes no API. The checksums, byte shuffle, and zlib moved
to `core`. A deflate chunk that ends early or fails its Adler-32 check is now an `HdfFormatException`
("deflate filter: zlib stream ends early") where it was "decoded chunk is N bytes"; a shuffle after a
filter that changes the chunk's length no longer corrupts the chunk (a P0, below).

**P2 S8, S9** add API and change behaviour:
- `Hdf5Writer.DatasetWriter` gains `lzf()`, `blosc()`, `blosc(cname, clevel, shuffle)`, `lz4()`,
  `lz4(blockBytes)`, `bitshuffle()`, `bitshuffle(compression, blockElements, zstdLevel)`, `zstd()`,
  `zstd(level)`, `bzip2()`, and `bzip2(blockSize)`; `Filter` gains `BZIP2`, `ZFP`, `SZ`, and `BLOSC2`.
- Writing into datasets filtered with LZF, Blosc, LZ4, bitshuffle, Zstandard, or bzip2 works; it threw
  `HdfUnsupportedException`.
- In the earliest format (version-1 object headers), `createDataset`, `attribute`, and `nbit` refuse a
  numeric type of two or more bytes with more than half its bits unused, nested ones too, with
  `IllegalArgumentException`: libhdf5 refuses to create or read them there.
- Falcon Core's Snappy follows snappy 1.2.2, so Blosc+snappy chunks (Zarr's too) are 1.2.2's bytes; the
  values are unchanged.

**P3 D2, D6, B1–B3** change no behaviour or API. The build changed (repo-wide): each module also
builds a sources jar and a Javadoc jar; the enforcer fails a build on JDK below 25, Maven below 3.9, or a
dependency other than Falcon's modules and JUnit 5 (test scope); jars are reproducible
(`project.build.outputTimestamp`); `-Pcoverage` reports coverage; `maven-compiler-plugin` is 3.15.0.

**P3 D1, D3, D4** change no behaviour or API. The module's build now fails on a public or protected
member of an exported package without complete Javadoc (javac's doclint, with `-Werror`, in the `hdf5`
POM's `default-compile`).

**P2 PF8** changes no behaviour or API: strided and point selections of chunked data read faster.

**Behaviour and API changes in P2 WF11, PF5–PF7** (pre-1.0):
- **Writing through virtual datasets** (`Hdf5Writer.open`): `write` and `writeRaw` on a virtual dataset of
  the file write into its sources, in this file and in others, which are changed in sessions of their own,
  completed by `close()` and undone by `abort()`. They threw `HdfUnsupportedException`. A write including
  elements no mapping covers (or whose source is missing, or that two mappings cover) throws
  `IllegalArgumentException`; `extend` and `append` throw `IllegalStateException`.
- **New: `OpenOptions.objectCacheSize(bytes)`** (default 16 MiB), and `DEFAULT_OBJECT_CACHE_SIZE`.
- **Handles share what they read** (P2 PF6): an object's header, attributes, links, and a dataset's
  datatype, shape, layout, chunk index and virtual mappings are kept per file, by object, for every handle
  of it, within that bound. They were kept per handle. A handle keeps what it has read either way.
  `attributes()` and `links()` of two handles of one object may now return the same list.
- **Chunk lookups** (P2 PF5): a read covering under an eighth of the chunk grid looks its chunks up in the
  file's index instead of reading the whole of it. Reading the whole index still rejects a corrupt one (a
  chunk misaligned, or two at one offset); a lookup does not read the chunks it does not need, so it does
  not see them, as libhdf5's does not.
- **Virtual reads** (P2 PF7) read from a source only the elements wanted, when they fill less than a
  quarter of the box that bounds them; a strided or point selection of a virtual dataset no longer reads
  the box that bounds it.

**Behaviour and API changes in P2 WF7, WF10** (pre-1.0):
- **New API:**
  - `Hdf5Writer.create(path, format, userBlock)`: a file that starts with a user block.
  - `DatasetWriter.szip(SzipCoding, pixelsPerBlock)` and the `Hdf5Writer.SzipCoding` enum (`ENTROPY`,
    `NEAREST_NEIGHBOUR`); `szip()` is `szip(ENTROPY, 8)`.
  - `GroupWriter.hardLink(name, targetPath)` and `move(name, newPath)`, and their root delegates.
- **szip chunks are libaec's** (zero-block runs, the second extension, its choice of `k`): smaller than
  before for runs of zeros and small values. A big-endian dataset's szip client data now names its byte
  order (MSB), as libhdf5's does; it named LSB.
- **n-bit chunks** are libhdf5's size (the significant bits rounded down to whole bytes, plus one): one
  byte more than before when they end on a byte boundary.
- **The earliest format** writes external links: a group holding one is written in the new format (link
  messages in its version-1 header), as libhdf5 converts it. `externalLink` threw.
- **Changing a file** (`open`):
  - writes into datasets filtered with n-bit, scale-offset, or szip's nearest-neighbour coding, datasets
    whose partial edge chunks are stored unfiltered, and data in external raw files; it threw;
  - changes attributes kept in the shared-message table; it threw;
  - adds external links to (and moves them into) groups of the original format, converting them; it
    threw;
  - returns links or attributes shrunk below the minimum for dense storage (6) to compact messages;
  - opens an object reached by several hard links once, whichever link reaches it;
  - journals `close()`'s writes over the file: a `close()` that fails while making them is redone by a
    retry, or by the next `open`, and `abort()` after one keeps the journal. A version-3 superblock is
    marked as open by a writer while they are made.
- `GroupWriter.dataset(name)` also opens a dataset reached by a hard link added in the session.

**Behaviour and API changes in P2 WF5, WF6, WF8, WF9** (pre-1.0):
- **New: `Hdf5Writer.open(path)`** changes an existing file in place.
  - `GroupWriter.group(name)` opens a group the file holds, rather than refusing the name.
  - New: `GroupWriter.dataset(name)` (and `Hdf5Writer.dataset`) gives a dataset the group holds, or one
    added in the session; `GroupWriter.delete(name)` (and `Hdf5Writer.delete`) deletes a link;
    `deleteAttribute(name)` on groups and datasets.
  - Setting an attribute an object has in the file replaces it.
  - A dataset of the file keeps its storage: `chunked`, `maxShape`, the filters, `fillValue`, and
    `compact` throw `IllegalStateException` on it.
- **Datasets given their data whole** (the per-type methods) write it when the next dataset or group is
  added (or on close): configure them (filters, layout, fill value) before then. Configuring one after
  another object was added now throws `IllegalStateException`.
- **References** are written in chunked (and filtered) and compact datasets and in attributes: chunked
  ones used to throw `IllegalStateException`, attributes `HdfUnsupportedException`. A chunked dataset of
  object references keeps its chunks until `close()`.
- **Dense storage** is laid out as libhdf5 lays it out: heaps of 512-byte first blocks with indirect
  blocks as needed, and name indexes of 512-byte nodes. A dense set was one direct block and one B-tree
  leaf sized to fit, which failed past about 64 KiB.
- **The earliest format** writes groups of any size (multi-level group B-trees); it refused more than 256
  children.
- **`compact()`** accepts variable-length and reference datasets; it refused them.

**Behaviour and API changes in P2 WF1–WF4** (pre-1.0):
- **Streaming:** the writer streams raw data into its temporary file as data is written and appends the
  metadata on `close()`. Files may pass 2 GB.
  - The temporary file now exists from the first write, not just during `close()`. A failed `close()`
    leaves it, for the retry; `abort()` deletes it.
  - An I/O error while writing data is an `UncheckedIOException`.
- **New: `createDataset(name, Datatype, shape...)`** (on `Hdf5Writer` and `GroupWriter`), with
  `DatasetWriter.chunked`, `maxShape` (`Hdf5Writer.UNLIMITED`), `write`, `writeRaw`, `append`, `extend`,
  and `shape`.
  - The chunk shape, maximum shape, filters, fill value, and compact layout are set before the first
    write; afterwards they throw `IllegalStateException`.
  - `write` and the rest are for `createDataset` datasets: one given its data when made throws.
- **Datatypes:**
  - new factories on `Datatype` (`int8()` ... `uint64()`, `float16/32/64()`, `bool()`, `string(n)`,
    `variableString()`, `sequenceOf`, `arrayOf`, `complexOf`, `compound`, `opaque`, `bitField`,
    `unixTime`, `objectReference`, `regionReference`);
  - `withByteOrder` on integer, float, bit-field, and time types.
- **Attributes:** new `attribute(name, Datatype, shape, values)` and `stringAttribute(name, value)` on
  groups and datasets.
- **Links and references:**
  - new `softLink`, `externalLink`, and `regionReferenceDataset`;
  - the new `Hdf5Writer.Region` (`all`, `block`, `hyperslab`, `points`).
- **The earliest format** now writes chunked datasets (version-1 B-tree index, filter pipeline version 1)
  and soft links; it used to refuse chunked datasets.
- **Scale-offset** now records an unsigned integer type as unsigned (it was always signed); Falcon wrote
  only signed integer types before.

**Behaviour and API changes in P2 A11, A12** (pre-1.0):
- **External links are followed.** `children()`, `child`, `group`, `dataset`, and paths follow them, as
  libhdf5 does, opening the link's file under the `ExternalFileAccess` policy (by default, files in the
  HDF5 file's directory tree only). Before, they were listed but not followed.
  - What a link reaches is named by its path in the link's file, as libhdf5 names it.
  - A link whose file is missing, whose object is missing, or which loops reaches nothing, as a dangling
    soft link does; `dataset()` says which.
  - A file the policy refuses fails `child`, `group`, and `dataset` with `HdfUnsupportedException`, and
    is left out of `children()`.
- **References into other files are followed** by `readObjectReferences()`, `readRegionReferences()`,
  and `readAttributeReferences()`. They used to throw `HdfUnsupportedException`. A file that is refused
  still throws it; one that is not found throws `HdfException`.
- `ExternalFileAccess.Purpose` gains `EXTERNAL_LINK` and `REFERENCE`.
- **Time values** read: `read()` gives `java.time.Instant[]`, the integer readers seconds since 1970.
  `read()` used to throw.

**API changes in P2 A1, A4, A7–A10** (pre-1.0):
- **Typed reads** (on `Dataset`, `Attribute`, and `Selection` alike):
  - `read()` returns a `Map<String, Object>` of member values for a compound, member names for an
    enumeration, `byte[][]` for opaque data, (real, imaginary) pairs for a complex number, the base
    type's values for an array, and `Object[]` rows for a sequence of any other base type. Each used to
    throw.
  - The integer readers accept enumerations (their values) and bit fields (unsigned); `readFloats()` and
    `readDoubles()` also complex numbers (the real part, as libhdf5 converts); `readStrings()` also
    enumerations (names); every reader also arrays of those (flattened).
  - New: `readComplexDoubles()` and `readComplexFloats()`; `member(name)`; `Selection.datatype()`;
    `Attribute.readRawBytes()`.
  - `Datatype.Enumeration.Member.value()` is the base type's integer. It was read as unsigned
    little-endian, wrong for a big-endian or negative member.
- **Selections:**
  - new `Dataset.select(start, stride, count, block)` and `selectPoints(long[][])`;
  - `Selection` has every reader a dataset has; `readStrings()` reads variable-length strings.
- **Options:** `OpenOptions.chunkCacheSize`, `readerPageSize`, and `readerCacheSize`.
- **Other files:**
  - new `ExternalFileAccess.resolvedBy(Resolver)`, with `ExternalFileAccess.Resolver` and `Purpose`;
    `allowDirectory` on a resolver's policy throws `IllegalStateException`;
  - names are read as paths of the HDF5 file's own file system;
  - a failed read of external raw data names the stored name, not the resolved path.
- **Opening:** `Hdf5File.open(Path)` reads through a channel when the file system cannot map the file.
- **Internal types:** `Attribute`'s constructor is no longer public.
- **Referenced objects:** `path()` and `name()` are found as libhdf5's `H5Iget_name` finds them. They
  used to be `"/"` and `""`. `toString()` shows the object's address until its path is known.

**Behaviour changes in the Z6/Z7 fix** (shared with Zarr):
- **Corrupt frames fail:** zstd data that fails its checksum, its declared size, or any of libzstd's
  structural checks now fails with `HdfFormatException` instead of decoding. In HDF5, a chunk that kept
  its size used to read wrong values.
- **Several frames** decode to their concatenation; libzstd's own `ZSTD_decompress` does the same.
- **Skippable frames** are skipped.
- **Trailing bytes** after the last frame are an error.

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

Empty: S10, the last open item, is done (see *Done — 2026-10-06 (P2: S10's Blosc2 and SZ)*). Falcon writes
every filter it reads.

---

## P0 — silent wrong data / files libhdf5 rejects

Empty: every item is done (see *Done — 2026-10-05*, *(P0: Z6/Z7)* for the zstd decoder, and *Done —
2026-10-06 (P2: S8, S9)* for types libhdf5 refuses in version-1 object headers, and *(core: what HDF5 and
Zarr share)* for a shuffle after a length-changing filter). A new P0 is any silent wrong value, or any
written file that libhdf5 rejects or misreads.

## P1 — valid files that fail; hardening; concurrency; test gaps

Empty: every item is done (see *Done — 2026-10-05 (P1)*).

## P2 — features, API, performance

### Read features

- S1–S10 are done (see *Done — 2026-10-06 (P2: S10's Blosc2 and SZ)*, *(P2: S10's ZFP)*, *(P2: S8, S9)*,
  *Done — 2026-10-05 (P2: S1–S3)*, and *(P2: S4–S7)*). Known limits around them, not planned:
  - **SZ's doubles under a point-wise relative bound** can differ from libSZ's in their last bits. libSZ computes them with its C runtime's `pow`, whose last bit differs between
    platforms (a Linux build of libSZ differs from hdf5plugin's Windows one the same way); Falcon uses
    `Math.pow`. Writing, Falcon's `log2` and `pow` matched libSZ's in every vector, but a rare value could be
    coded one quantization step apart from libSZ's, still within its bound.
  - **SZ's int64 range:** Falcon writes int64 data as a 64-bit build of libSZ does, not as hdf5plugin's Windows
    build, which computes the range over 32-bit `long`s and so writes data needing more than 32 bits far
    outside its bound. libSZ reads Falcon's streams correctly.
  - **zstd's bytes** are Falcon's own wherever a plugin uses zstd (Zstandard, Blosc, Blosc2, bitshuffle, and SZ's
    last stage): valid frames that libzstd reads, not libzstd's.

### Read API

- A1–A12 are done (see *Done — 2026-10-05 (P2: A11, A12)*, *(P2: A1, A4, A7–A10)*, and
  *(P2: A2, A3, A5, A6)*).

### Write features & API

- WF1–WF11 are done (see *Done — 2026-10-05 (P2: WF11, PF5–PF7)*, *(P2: WF7, WF10)*,
  *(P2: WF5, WF6, WF8, WF9)* and *(P2: WF1–WF4)*). Still open around them:
  - Third-party filters: S8 writes LZF, Blosc, LZ4, bitshuffle, Zstandard, and bzip2, and S10 ZFP, Blosc2, and
    SZ: every filter Falcon reads.
  - **Not planned: writing through a virtual dataset of variable-length or reference data** (Falcon reads
    none either: its elements point into each source's own heaps and objects), **or converting types** on
    the way (other than the byte order), which libhdf5 does.
  - **Not planned: reusing space a session frees** (a deleted object's, a replaced attribute's, an old
    symbol table's). A session writes only after the file's end until `close()`, which is what lets
    `abort()` and a crash before `close()` leave the file as it was, and the journal stay small; libhdf5
    does not reuse such space across sessions either. `h5repack` reclaims it.
  - **Seen in libhdf5 (1.14.6 and 2.0):** resizing a dataset whose dataspace is shared in the
    shared-message table corrupts an object header ("message size exceeds buffer end" / "bad flag
    combination for message"), in a file libhdf5 alone wrote. Falcon gives such a dataset its own dataspace
    when it grows one, so libhdf5 can resize it afterwards.

### Performance

- PF1–PF8 are done (see *Done — 2026-10-05 (P2: PF8)*, *(P2: WF11, PF5–PF7)* and *(P2: PF1–PF4)*).
  Nothing is open here. Strided single elements of contiguous data are still copied one by one (runs of
  adjacent elements are one copy); a benchmark shows no gain worth the change yet.

## P3 — docs, build, housekeeping

- D1, D2, D3, D4, D6, B1, B2, and B3 are done (see *Done — 2026-10-05 (P3: D2, D6, B1–B3)* and
  *(P3: D1, D3, D4)*). P3 is empty.

## Done — 2026-10-06 (P2: S10's Blosc2 and SZ)

- [x] **S10's Blosc2 — writing the Blosc2 filter (32026, hdf5-blosc2 with c-blosc2 3.3.2).**
  - **The encoder** (`core`'s `compress.blosc.Blosc2Encoder`) writes c-blosc2 chunks (format version 5, the
    32-byte header), one-chunk super-chunk frames, and b2nd arrays in a frame, as `blosc2_compress_ctx`,
    `blosc2_schunk_to_buffer`, and `b2nd_from_cbuffer` with `b2nd_to_cframe` do: the tuner's block sizes, the
    split rule, the shuffle, bit shuffle, and delta filters, runs of one byte and all-zero chunks, the memcpy
    fallback, and the frame's header, offsets index, and trailer. BloscLZ gains c-blosc2's variant
    (`BloscLz.compress2`: a longer entropy probe, a shift and minimum length of 4); LZ4, LZ4HC, and zlib already
    are the libraries hdf5plugin builds. Every chunk is c-blosc2's byte for byte but zstd's (Falcon's own frames,
    which c-blosc2 reads). Where c-blosc2 leaves bytes undefined (past the last whole element of a delta block,
    its scratch buffer's), Falcon writes the previous block's, or zeros.
  - **The filter:** `DatasetWriter.blosc2()` and `blosc2(cname, clevel, filter)` (`blosclz`, `lz4`, `lz4hc`,
    `zlib`, `zstd`; clevel 0 to 9; `nofilter`, `shuffle`, `bitshuffle`, `delta`), as `hdf5plugin.Blosc2` sets
    them up, store the client data `blosc2_set_local` stores (rank and dimensions for chunks of rank 2 to 16)
    and each chunk as `blosc2_filter_function` compresses it: a b2nd array whose block shape
    `compute_b2nd_block_shape` derives from the block size (0: the tuner's, with the byte shuffle), or a plain
    frame at rank 1 and wherever the filter's input is not the chunk (an array type; scale-offset or n-bit
    before it). Writing into Blosc2 datasets works too, with the file's client data: a block size there (which
    hdf5plugin never sets) is used, truncated precision (which the plugin cannot apply: it sets no
    `filters_meta`) leaves chunks unfiltered as the plugin does, and c-blosc2's filter and codec plugins
    (numbered from 32) and a block size that is not a whole number of elements are refused.
  - **Found:** hdf5-blosc2 writes b2nd chunks of elements over 255 bytes that it cannot read back (c-blosc2
    records their type size as 1). Falcon refuses them with `IllegalStateException`.
  - **Oracles:**
    - `gen_blosc2_encoder_vectors.py`: 978 frames the plugin wrote for one-chunk datasets (every codec, clevel 0
      to 9, every filter, type sizes 1 to 300, ranks 1 to 4, blocks that do and do not divide the chunk, block
      sizes in the client data, zeros, NaN, repeated values, noise, several blocks): 785 byte for byte; the 189
      zstd ones decode to their data and have the plugin's frame header but for the lengths; 4 with truncated
      precision were stored unfiltered.
    - `blosc2_write.h5` (`gen_fixtures.py blosc2_write`): 53 datasets the plugin wrote (ranks 1 to 5, partial
      chunks, compound, array, and 300-byte types, a shuffle or scale-offset before it and fletcher32 after,
      block sizes). Falcon re-writes 47 through the API with the same client data and 215 chunks byte for byte
      (zstd's by value), and writes into all 53 through `open()` (`WriteBlosc2Test`).
    - `check_hdf5_writer.py`: libhdf5 2.0 with hdf5plugin reads Falcon's Blosc2 datasets (every codec, ranks 1
      to 3, clevel 0, noise, a shuffle and fletcher32 around it, strings, arrays, compounds), appends a row
      through the plugin to a growable one, and reads Falcon's writes into `blosc2_write.h5`.
  - Tests: `Blosc2EncoderTest` (core), `WriteBlosc2Test`,
    `ThirdPartyFiltersEncodeTest.blosc2WritesWhatItReadsAndFailsWhereThePluginFails`.
- [x] **S10's SZ — writing the SZ filter (32017, SZ 2.1.12's H5Z-SZ).**
  - **The encoder** (`core`'s `compress.sz.SzEncoder`) ports SZ 2.1.12's compression (`SZ_compress_args` with
    `SZ_Init(NULL)`'s defaults, which are H5Z-SZ's) path for path: floats and doubles in 1 to 4 used
    dimensions (1-D's Lorenzo coder; SZ 2.1's blocked regression in 2-D and 3-D, 4-D through 3-D; point-wise
    relative bounds in both forms, MSST19 from 1e-5 and pre_log below; constant data; stored copies; 20 values
    or fewer kept as they are), the eight integer types (1 to 4 dimensions), interval optimisation, Huffman
    trees, and the zstd stage (level 3, Falcon's own frames). Modes ABS, REL, ABS_AND_REL, ABS_OR_REL, and
    PW_REL are written; the others, and point-wise relative integers (on which libSZ exits), are refused.
  - **Exact:** every stream is libSZ's byte for byte beneath its zstd stage, but where libSZ leaves bytes
    undefined (a header byte it never writes; the 2 values past its input an integer 1-D stored copy reads) and
    in one deliberate difference: hdf5plugin's Windows libSZ computes an int64 range over 32-bit `long`s, so
    int64 data needing more than 32 bits decodes far outside its bound (3e15 off, seen); Falcon computes it as
    a 64-bit build does, and libSZ reads its streams back correctly. libSZ's `log2` and `pow` are its C
    runtime's; Falcon's matched them in every vector (UCRT's `log2` differs from Falcon's correctly rounded one
    on 658 of 200,000 inputs, none once rounded to float; `Math.pow` from UCRT's on 28 of 50,000).
  - **The filter:** `DatasetWriter.szAbsolute(bound)`, `szRelative(ratio)`, `szPointwiseRelative(ratio)`, and
    `sz()` (`hdf5plugin.SZ()`'s point-wise relative 1e-5) store H5Z-SZ's client data (the chunk's dimensions
    longer than 1, fastest first, and its 1-D slip: a `(1, n)` chunk records one value, so it is stored as it
    is; then the nine error-bound values) and each chunk as H5Z-SZ compresses it; chunks of fewer than 20
    values are stored as they are. SZ must be the first filter, over little-endian floats or integers of 1 to 8
    bytes, in chunks of at most 4 dimensions longer than 1. Writing into H5Z-SZ's datasets works too.
  - **Oracles:**
    - `gen_sz_encoder_vectors.py`: 1,014 cases libSZ compressed through H5Z-SZ (550 floats and doubles, 464
      integers: every type, mode, and dimensionality, constant data, stored copies, NaN and infinities, 20 and
      21 values): all 1,014 match beneath zstd, and Falcon's streams decode as libSZ's do but for the int64
      range (25 cases). libSZ, reading Falcon's streams, decodes them as Falcon does (1,462 of 1,464 in an
      earlier, larger set; the other 2 are double pre_log 3-D, in `pow`'s last bits).
    - `sz_write.h5` (`gen_fixtures.py sz_write`): 35 datasets H5Z-SZ wrote, their inputs under `/input`;
      Falcon stores the same client data and chunks beneath zstd (`WriteSzTest`), but for `i8_big`, where
      hdf5plugin's chunks lose more than half the values and Falcon's keep them within the bound.
    - `check_hdf5_writer.py`: libhdf5 2.0 with hdf5plugin reads Falcon's SZ datasets (ranks 1 to 4, every
      mode, dimensions of size 1, fletcher32 after it, short and `(1, n)` chunks, integers of each width) as
      Falcon reads them, and reads Falcon's writes into `sz_write.h5`. Double point-wise relative 3-D data is
      left out: there libSZ's decoding and Falcon's differ in `pow`'s last bits.
  - Integers are not always within their bound, as libSZ's own are not (its 4-D coder's slips; fractional
    bounds, which it rounds); Falcon's decode as libSZ's do.
  - Tests: `SzEncoderTest` (core), `WriteSzTest`.
- **Interop:** `check_hdf5_writer.py` read 548/548 objects with libhdf5 2.0 and hdf5plugin 7.1.0, and libhdf5
  changed 547 and read them back.
- **Tests:** core 138 → 147, hdf5 1105 → 1116, plus the 12 under a small heap.

## Done — 2026-10-06 (P2: S10's ZFP)

- [x] **S10's ZFP — writing the ZFP filter (32013, LLNL's H5Z-ZFP 1.1.1, zfp 1.0.1).**
  - **The encoder** (`core`'s `compress.zfp.ZfpEncoder`) ports zfp 1.0.1's: every mode (fixed rate, precision,
    accuracy, expert, reversible), every scalar type, 1 to 4 dimensions, partial blocks padded as libzfp pads
    them, and the stream padded to whole 8- or 64-bit words. `ZfpHeader` gains `of`, `withRate`,
    `withPrecision`, `withAccuracy`, `withReversible`, `withParameters` (zfp's `zfp_stream_set_*`), and
    `encodedMode` (`zfp_stream_mode`). Where C is undefined (NaN and overflowing values, a subnormal or
    infinite block maximum), it follows libzfp on x86-64 Windows, the builds it is checked against; glibc's
    `frexp` of an infinity differs, so a Linux libzfp codes such a block differently.
  - **The filter:** `DatasetWriter.zfpRate`, `zfpPrecision`, `zfpAccuracy`, `zfpReversible`, and `zfpExpert`,
    as `hdf5plugin.Zfp` sets each up, store H5Z-ZFP's client data (its version, then zfp's header of a chunk
    without its dimensions of size 1, the rate set knowing the type) and each chunk as H5Z-ZFP compresses it.
    It must be the first filter; 4- and 8-byte little-endian integers and floats only, in chunks of 1 to 4
    dimensions longer than 1, as H5Z-ZFP's `can_apply` demands. Writing into H5Z-ZFP's datasets works too.
  - **Oracles:**
    - `gen_zfp_encoder_vectors.py`: 556 libzfp streams (274 through H5Z-ZFP, 282 through zfpy) from their
      inputs, every mode, type, and dimensionality, whole and partial blocks, NaN, infinities, subnormals,
      and integers at their limits; Falcon's encoder matches each byte for byte, and its mode factories
      every header. (zfpy's fixed rate below a float block's exponent overruns zfpy's own buffer, so those
      cases are left out.)
    - `zfp_write.h5` (`gen_fixtures.py zfp_write`): 43 datasets hdf5plugin wrote, each with its input kept
      uncompressed; Falcon, writing the input with the same settings, stores the same client data and every
      chunk byte for byte (`WriteZfpTest`).
    - `check_hdf5_writer.py`: libhdf5 2.0 with hdf5plugin reads Falcon's ZFP datasets (every mode, 1 to 4
      dimensions, dimensions of size 1, fletcher32 after it, integers) as Falcon reads them, appends a row
      through H5Z-ZFP to a growable one, and reads Falcon's writes into `zfp_write.h5`: 492 objects, OK.
  - Tests: `ZfpEncoderTest` (core), `WriteZfpTest`.

## Done — 2026-10-06 (core: what HDF5 and Zarr share)

By Erich's decision, what both modules had written twice moved to `core`. HDF5's `checksum` package keeps
`MetadataChecksum`, which verifies a structure's stored hash.

- [x] **`core.checksum`:** Fletcher-32 (`H5_checksum_fletcher32`, numcodecs' `fletcher32`) and Jenkins'
  lookup3 (`H5_checksum_lookup3`, numcodecs' `jenkins_lookup3`), moved from HDF5 (its `Lookup3Test` with
  them). Zarr's `ChecksumCodec` drops its own copies. lookup3 now reads whole little-endian words: HDF5's
  byte-at-a-time segment reads hashed arrays at half the speed of Zarr's copy (46 against 22 ms for
  64 MiB); now 18 ms.
- [x] **`core.compress.shuffle.ByteShuffle`:** the byte shuffle of HDF5's `shuffle` filter, Blosc, and
  numcodecs' `shuffle`, moved from Blosc. Bytes past the last whole element are copied through.
- [x] **`core.compress.zlib.Zlib`:** zlib streams for HDF5's `deflate` filter and numcodecs' `zlib`, through
  `java.util.zip`, decoded within a bound and strictly: a stream that ends early, fails its Adler-32 check,
  or needs a preset dictionary is refused, as zlib's `inflate` refuses it for libhdf5 and numcodecs; bytes
  after the stream are ignored. HDF5's deflate had stopped quietly at the end of its input, so a truncated
  chunk failed later, by its size.
- [x] **P0, found on the way: a shuffle after a length-changing filter corrupted the chunk.** HDF5's
  writer shuffled only whole elements and left the bytes past the last one zero, where libhdf5's
  `H5Z__filter_shuffle` (and Falcon's reader) copy them. So `deflate(1).shuffle()` or
  `scaleOffset().shuffle()` wrote chunks neither Falcon nor libhdf5 could read back ("incorrect data
  check"). The shared shuffle keeps them (`WriterEdgeCaseTest.shuffleKeepsBytesPastTheLastElement`).
- **Tests:** core 129 (`Fletcher32Test`, `ByteShuffleTest`, `ZlibTest`, `Lookup3Test` moved from HDF5,
  and zlib in `CompressionRobustnessTest`); hdf5 1101 + 12; zarr 683 + 18.

## Done — 2026-10-06 (P2: S8, S9)

The fixtures come from libhdf5 2.0 through h5py and hdf5plugin 7.1.0, whose plugins are the oracle. The
plugins' C sources (hdf5plugin's source distribution, and h5py's for LZF) are the reference for each port.
ZFP and SZ are lossy, so their fixtures hold what libzfp and libSZ decode, read from the file reopened: in
the session that wrote it, libhdf5 returns its cached chunks unfiltered.

- [x] **S8 — writing the third-party filters.**
  - **API** (`Hdf5Writer.DatasetWriter`, chunked datasets, each an optional filter with hdf5plugin's
    defaults):
    - `lzf()`;
    - `blosc()` (LZ4, clevel 5, byte shuffle) and `blosc(cname, clevel, shuffle)`, with Zarr's names;
    - `lz4()` (a block per chunk) and `lz4(blockBytes)`;
    - `bitshuffle()` (with LZ4) and `bitshuffle(compression, blockElements, zstdLevel)`;
    - `zstd()` (level 3) and `zstd(level)`;
    - `bzip2()` (900,000-byte blocks) and `bzip2(blockSize)`.
  - **On disk,** as each plugin's `set_local` leaves it:
    - the client data: LZF `[4, 0x0105, chunk bytes]`; Blosc `[2, 2, type size, chunk bytes, clevel,
      shuffle, compressor]` (an array's base type size, and 1 above 255); bitshuffle `[0, 4, element size,
      block, compression]` and zstd's level; LZ4, Zstandard, and bzip2 their one value;
    - the name the plugin registers, which libhdf5 stores for ids of 256 and up (padded to 8 bytes in a
      version-1 pipeline message);
    - a chunk LZF or Blosc cannot shrink into the chunk's own size (the destination the plugins give) is
      stored unfiltered, its filter-mask bit set.
  - **Writing into datasets** so filtered (`Hdf5Writer.open`) works, with the client data the file holds.
    `open()` now refuses only Blosc2, ZFP, SZ, and filters Falcon does not know.
  - **Core encoders**, each the plugin's bytes:
    - `Lzf.compress`, h5py's liblzf (HLOG 17, ULTRA_FAST). Of h5py's 81 chunks, 58 match and 22 are skipped
      where h5py skipped them. One differs because h5py builds liblzf with an uninitialised hash table: its
      match there reaches a position the chunk never hashed. Falcon's stream decodes to the same bytes.
    - Bitshuffle's blocked transpose, alone and with LZ4 or zstd: 29 vectors and 130 chunks match (zstd
      aside).
    - `BloscEncoder.compress` with a destination size, hdf5-blosc's: 190 chunks match, and 53 are skipped
      where hdf5-blosc skipped them. **Snappy** now follows snappy 1.2.2, as hdf5plugin builds it (hash
      tables of up to 2^15 entries; Falcon had followed 1.1.10), which changes Zarr's Blosc+snappy bytes
      too.
    - LZ4 in H5Zlz4.c's blocks (`LZ4_compress_default`): 55 chunks match.
    - Zstandard, and zstd inside Blosc and bitshuffle: Falcon's own frames, which libzstd reads.
    - bzip2 (from S9): all 63 chunks of `bzip2.h5` match.
  - **End to end:** the 100 datasets of `plugin_filters_write.h5`, written again by Falcon from their
    values, have the plugins' filters and, zstd aside, their chunks and filter masks.
  - **Interop:** `check_hdf5_writer.py` read 480/480 objects with libhdf5 2.0 and hdf5plugin. They cover
    every API form in both formats, with built-in filters, over compound, array, and long string types,
    with skipped chunks, and the plugins' own fixtures changed by Falcon. libhdf5 then wrote a row through
    each plugin into every growable dataset, and read all 479 changed objects back.
- [x] **S9 — more registered filters,** read through new pure-Java codecs in `core`:
  - **bzip2 (307,** PyTables' `H5Zbzip2.c`): `compress.bzip2` decodes a stream, verifying its block and
    stream CRCs (randomised blocks included). It also encodes, byte for byte as libbzip2 1.0.8: the block
    sort is ported step for step, since a repetitive block's tie order decides the output. It reproduces 53
    libbzip2 vectors, and `bzip2.h5`'s 11 datasets read as their unfiltered copies.
  - **Blosc2 (32026,** hdf5-blosc2 with c-blosc2 3.3.2): `Blosc2Frame` reads a contiguous frame (header,
    metalayers, offsets index, special chunks, trailer), and `B2ndArray` its b2nd (or caterva) array, its
    blocks scattered into C order and their padding dropped. A rank-1 chunk is a plain one-chunk frame.
    Tested on 24 frame vectors (11 crafted from c-blosc2's writers: several chunks, special offsets, more
    metalayers) and `blosc2.h5`'s 25 datasets: every compressor, clevel, shuffle, and delta, ranks 1 to 4,
    padded blocks. Uninitialised chunks, which c-blosc2 leaves undefined, read as zeros.
  - **ZFP (32013,** LLNL's H5Z-ZFP 1.1.1, zfp 1.0.1): `compress.zfp` ports zfp's decoder: every mode
    (fixed rate, precision, accuracy, expert, reversible), int32, int64, float, and double, in 1 to 4
    dimensions. The header comes from the client data, byte-swapped for a big-endian writer. Every value is
    libzfp's bit for bit: 190 vectors, and `zfp.h5`'s 14 datasets.
  - **SZ (32017,** SZ 2.1.12's H5Z-SZ): `compress.sz` ports SZ's decompression for floats, doubles, and the
    eight integer types in 1 to 4 dimensions: every error-bound mode (point-wise relative in both its
    forms), the regression predictor, constant data, stored copies, and the zstd and zlib stages. Every
    value is libSZ's bit for bit (124 vectors, `sz.h5`'s 72 datasets, 1,879 of 1,912 swept chunks), but for
    **doubles under a point-wise relative bound**: libSZ's `pow` there is its C runtime's, which no Java
    method reproduces. 33 swept chunks differ, most by 1 ulp in one or two values (at most 71 ulps); the
    tests allow 128 ulps for them. Falcon also reads what libSZ cannot: exactly 20 values, which libSZ
    stores raw and then fails to read (it calls `exit`).
  - **Hardening:** each decoder throws only typed exceptions and bounds its output by the chunk.
    `CompressionRobustnessTest` fuzzes each, and the four fixtures join `RobustnessTest`'s set.
  - `Filter` gains `BZIP2`, `ZFP`, `SZ`, and `BLOSC2`.
- [x] **P0, found with S8: types libhdf5 refuses in version-1 object headers.**
  - libhdf5 1.14.4 and later (2.0 included) take some numeric types for corruption in an object header
    without a checksum, the earliest format's: an integer, float, or bit field of two or more bytes whose
    precision and offset reach less than half its bits (`H5T_is_numeric_with_unusual_unused_bits`).
  - It creates no such dataset there, and reads no such datatype back (an attribute's included) unless
    `H5Pset_relax_file_integrity_checks` allows it.
  - Falcon wrote them, `nbit(12)` of an `int32` in `Format.EARLIEST` for one, so libhdf5 2.0 refused the
    file.
  - Now `createDataset`, `attribute`, and `nbit` refuse them there with `IllegalArgumentException`, nested
    ones too. The latest format's checksummed headers take any
    (`WriterEdgeCaseTest.refusesWhatLibhdf5RefusesInVersion1Headers`).
- **Tests:** core 83 → 112; hdf5 843 → 1105, plus the 12 under a small heap. New: `WritePluginFiltersTest`,
  `ThirdPartyFiltersEncodeTest`, `Bzip2FilterTest`, `Blosc2FilterTest`, `ZfpFilterTest`, `SzFilterTest`, a
  third-party case in `WriteFilterConformanceTest`, and the new fixtures' rows in `storage_metadata.txt`.
- **Tools:**
  - `gen_fixtures.py` builds `plugin_filters_write`, `bzip2`, `blosc2`, `zfp`, and `sz`.
  - `gen_core_vectors.py` makes more LZF and bitshuffle vectors.
  - New: `gen_bzip2_vectors.py`, `gen_blosc2_frame_vectors.py`, `gen_zfp_vectors.py`, and
    `gen_sz_vectors.py`.

## Done — 2026-10-05 (P3: D2, D6, B1–B3)

- [x] **D2 — USER_GUIDE gaps.** Every public name of the two exported packages was checked against the
  guide. The guide now also covers each object's `comment()`, `modificationTime()`, `referenceCount()`
  and `objectHeaderAddress()`; the `Dataspace` accessors (`maxDimensions()`, `UNLIMITED`,
  `isUnlimited(i)`, `elementCount()`, `kind()`); `Dataset.fillValueBytes()`; `Selection.elementCount()`;
  and the writer's second element types (`int32ArrayDataset`, `doubleSequenceDataset`,
  `doubleChunkedDataset`, `doubleAttribute`). What it documented before:
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
  - **Also done (P2 A1, A4, A7–A10):** a table of every datatype class's readers and `read()` value,
    compound members, strided and point selections, cache sizes, resolvers, paths that cannot be mapped,
    and the paths of referenced objects.
  - **Also done (P2 A11, A12):** following external links and references into other files, and time
    values.
  - **Also done (P2 WF1–WF4):** streaming writes and what stays in memory, `createDataset` and its
    value table, growing datasets, typed and string attributes, links, and region references.
  - **Also done (P2 WF5, WF6, WF8, WF9):** changing a file in place (what it changes, what it refuses,
    what `abort()` undoes), references anywhere, and when data given whole is written.
  - **Also done (P2 WF7, WF10):** szip's codings, user blocks, hard links and moves, the earliest format's
    external links, and changing a file further (filters with parameters, shared attributes, external raw
    data, the journal).
  - **Also done (P2 WF11, PF5–PF7):** writing through virtual datasets, chunk lookups, the object cache
    (`objectCacheSize`), and what virtual reads read; `BENCHMARKS.md` has the bytes a small read reads.
  - **Also done (P2 PF8):** the performance note on selections, and the selection rows in
    `BENCHMARKS.md`.
  - **Also done (P3 D1):** *Error handling* says which bytes no checksum covers, and that corrupting them
    goes unnoticed.
- [x] **B1 — CI: a `windows-latest` leg.** `.github/workflows/ci.yml` builds and tests on `ubuntu-latest`
  and `windows-latest` (a matrix, `fail-fast: false`), where mapping and deleting files differ. Repo-wide.
  The suite already passed on Windows locally; the CI leg is proven only once the workflow runs.
- [x] **B2 — release plumbing** (repo-wide; each plugin approved by Erich, 2026-10-05):
  - **Sources and Javadoc jars** beside each module's jar (`maven-source-plugin` 3.3.1,
    `maven-javadoc-plugin` 3.12.0). The Javadoc jar fails on broken links, bad HTML, or wrong tags
    (`doclint` `all,-missing`; the hdf5 compile checks for missing comments itself).
  - **`maven-enforcer-plugin` 3.6.3:** JDK 25+, Maven 3.9+, and banned dependencies: only `com.ebremer`
    modules, and JUnit 5 with its own jars (opentest4j, apiguardian, junit-platform) in test scope. Tried:
    JUnit in compile scope fails the build with the dependency gate's message.
  - **Reproducible builds:** `project.build.outputTimestamp`, and the lifecycle's plugins pinned to the
    versions Maven 3.9.16 binds (clean 3.2.0, resources 3.4.0, jar 3.5.0, install 3.1.4, deploy 3.1.4).
    Two clean builds give byte-identical jars, all nine (classes, sources, Javadoc). `maven-compiler-plugin`
    went from 3.13.0 to 3.15.0: with the timestamp set it rewrites `module-info.class`, and 3.13.0's ASM
    cannot read JDK 25 class files.
  - **Coverage:** `mvn verify -Pcoverage` (`jacoco-maven-plugin` 0.8.15) writes each module's report to
    `target/site/jacoco/`. Surefire configurations that set their own `argLine` (the hdf5 fuzz run,
    zarr's) start with `@{argLine}` so the agent reaches them. At this commit: hdf5 92.1% of lines and
    81.0% of branches, core 95.4% and 88.4%, zarr 88.7% and 80.6%.
  - **Quieter build:** compiler 3.15.0 shows warnings, so `core`'s module-info suppresses "module not
    found" for the modules it exports to (built after it), and zarr's `--add-reads …=jdk.httpserver` is
    passed to the test compile only. The build prints no warnings.
- [x] **B3 — housekeeping:** `tools/fixtures/__pycache__/gen_fixtures.cpython-314.pyc` is removed, and
  `.gitignore` ignores `__pycache__/` and `*.py[cod]`. Repo-wide.
- [x] **D6 — repo-wide staleness.**
  - `CLAUDE.md` (at Erich's request): Zarr is built (reads v2 and v3, writes v3), `core` is described,
    the build commands use `-am` (hdf5 needs core), each module exports its API packages, code both
    formats need goes in `core` (a shared model was deferred), and the build plugins approved under the
    dependency gate are listed.
  - The root `pom.xml` description already named the zarr and core modules.
  - Zarr's refusal of `zarr_format 2` in a `zarr.json` said v2 was "planned (stage Z8)"; it now says v2
    metadata is read from `.zarray` and `.zgroup`.

## Done — 2026-10-05 (P0: Z6/Z7)

- [x] **Z6/Z7 — corrupt zstd chunks decoded to wrong data.** HDF5's zstd filter (32015), Blosc's internal
  zstd, and Zarr's zstd codec share one decoder; the shared zstd decoder (`core`) is fixed:
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
- **Verified against libzstd 1.5.7** over the Zarr review's 50,400 cases (python-zstandard):
  - **Before:** the review counted 11,561 checksummed and 264 unchecksummed mutated frames that libzstd
    rejects and Falcon decoded to wrong data.
  - **After, over all 50,000 mutations** (30,000 one- and two-bit, half with a checksum; 20,000 single-bit,
    without): Falcon decodes no frame libzstd rejects, no output differs from libzstd's, and no exception
    is untyped.
  - **400 valid frames** (streamed, multi-frame, long-distance matching, no content size) all decode
    exactly.
  - **Where they still differ:** in 5,210 mutated frames Falcon is the stricter. Each time, libzstd's
    output is corrupt: its fast Huffman decoder checks a literal stream's output length, not that the
    stream ends there.
- **Tests:**
  - `core`'s `ZstdCorruptionTest` checks XXH64 against reference values, and 436 vectors from libzstd
    (`tools/fixtures/gen_zstd_corrupt_vectors.py` → `zstd_corrupt_vectors.txt`): mutations, several
    frames, skippable frames, and trailing data. The old decoder fails 137 of them.
  - The core fuzzer: 1.5M further mutated frames threw only typed exceptions.

## Done — 2026-10-05 (P3: D1, D3, D4)

- [x] **D1 — docs that overclaim.** Each claim now says what is true:
  - **"1.0-ready"** (`hdf5/PLAN.md` status and H9, the root `README.md` and `PLAN.md`): pre-1.0. The API
    may still change, CI builds on Linux only (B1), and there is no release plumbing (B2).
  - **"read back identically … by h5py"**: Falcon's tests read back what it writes, and the dev-time
    `check_hdf5_writer.py` (not part of the build) has libhdf5 2.0 and 1.14 read every value against a
    manifest, then change each file.
  - **"szip verified via libaec"**: true, and now says it is libaec alone. Falcon's szip chunks are
    decoded and re-encoded byte for byte by libaec, and `szip.h5`'s chunks come from libaec's SZ layer;
    no libhdf5 build with szip has read or written them, since h5py ships szip disabled.
  - **"every structure on a read path is covered"**: the structures that lead to groups, attributes, and
    data are read. The status names what is not: filtered fractal heaps, the family/multi/split drivers'
    files, and the free-space section lists and shared-message index, which nothing read needs.
  - **README "corrupt input never … returns wrong data"**: corrupt input fails with a typed exception,
    never a crash or a hang, and every stored checksum is verified; bytes no checksum covers can be
    corrupted unnoticed. The README's "every HDF5 structure" lists what the reader handles instead.
    `USER_GUIDE.md`'s *Error handling* lists those bytes (raw data without fletcher32, the global heap,
    unchecksummed fractal-heap direct blocks, the earliest format's metadata), as do `PLAN.md` §11 and
    `RobustnessTest`'s Javadoc, which had promised "never … silently-wrong data" though the test accepts a
    mutation that reads.
  - **Also corrected:** `PLAN.md` §11 promised a JSON sidecar of expected values per fixture (the tests
    assert the generator's values, and `storage_metadata.txt` holds libhdf5's storage report) and
    property-based round-trips (seeded randomized checks exist; generated round-trips over random types,
    shapes, chunkings, and filters do not).
- [x] **D3 — PLAN.md is stale.**
  - **§6** lists the packages there are (`data`, `index`, `group`, and `write` among them; no `dataspace`
    or `util`), and says both `com.ebremer.falcon.hdf5` and `…hdf5.datatype` are exported.
  - **§7's** sketch uses today's names (`readFloats`, `dimensions()`, a path from the group) and says how
    `Hdf5Writer` writes.
  - **§2** shows the `core` module and the docs, and says what `core` holds and that a shared data model
    was not made (`zarr/PLAN.md` §10).
  - **Elsewhere:** the status (H0–H9 and P2 done, 855 tests, pre-1.0, what is read and written and how
    it is checked); §3's deferred write datatypes (done in WF1–WF4) and what is open (S8, S9); §5's
    compression row (the third-party filters through `core`); H5 marked done; H4's deferrals, H7's
    "remains", and H8's "still throw" as since done or not planned (the free-space manager); H6's signed
    szip (libaec's SZ layer has no signed option); §9's szip status; §11's 1.14 oracle; §13's Zarr
    (built).
- [x] **D4 — Javadoc lint.** `-Xdoclint:all` over the exported packages: **0 warnings**, from 595 (318 at
  the review; the API grew). `-Xdoclint:all,-missing -package`: 0 too.
  - Every public and protected member has a comment, with `@param`, `@return` (or `{@return}` for a
    one-line description), and `@throws` where it throws a checked exception or doclint asks; record
    components and enum constants are documented. `Hdf5Writer.EnumType` has an explicit, documented
    constructor (the implicit one was public too).
  - Corrected on the way: `Format.EARLIEST` said "every HDF5 version reads" it (the guide says 1.10 and
    later); now "the original format, libhdf5's default".
  - **Kept so:** the `hdf5` POM's `default-compile` runs javac with `-Xdoclint:all/protected`, limited to
    the two exported packages, and `-Werror`, with `showWarnings` on, so a missing tag fails the build
    and names the file and line. Tests and internal packages are not checked.

## Done — 2026-10-05 (P2: PF8)

- [x] **PF8 — selected elements copied a run at a time.**
  - **Runs:** new `SelectedElements.forEachRunInBox` visits the elements in a box as `forEachInBox` does,
    but a run at a time: a run is elements at consecutive positions, each a fixed step after the one before
    in the last dimension.
    - A regular hyperslab's last axis gives one run per block (step 1), one run spanning its blocks when
      they touch (stride = block) or it is one block, and one run of its indices when its blocks are
      single indices (step = the stride).
    - Listed points one after another along a row form runs of step 1.
  - **Chunked data** (`ChunkedReader.gather`): each run is one `System.arraycopy`, and a strided run one
    loop of `long`, `int`, or `short` moves (other sizes copy each element). Points are ordered by chunk
    with one sort of `long`s, each a chunk's row-major number times the point count plus the point's index.
    A grid too large for that number falls back to the comparator sort, which is stable.
  - **Contiguous data** (`SelectedElements.gather`) uses the same runs, merging those adjacent in the
    file and in the selection, as it did.
  - **Measured** (`BENCHMARKS.md`): every other row of 2048 × 2048 doubles chunked 64 × 64 takes
    14.9–16.3 ms, from 31.7–35.3; every other element 9.9–11.9 ms, from 17.8–20.0; 100,000 random points
    18.6–21.3 ms, from 52.9–57.0. The whole read takes 19–21 ms.
  - **Tests:** `SelectedElementsRunsTest`:
    - 3,000 random hyperslabs (touching blocks, gaps, blocks of one) and boxes visit the same elements by
      runs as one by one;
    - listed points, with runs, repeats and boxes;
    - `gather` against an element-by-element copy.

    `SelectionTest.runsOfElementsAreCopiedAcrossChunks` (blocks crossing chunks, point runs across a chunk
    boundary, repeats) and `pointsOfAVastGridAreOrderedByChunk` (a 2^40 × 2^40 grid: the fallback order).
  - **Benchmarks** gained the selection rows. The listing row opens its file with `objectCacheSize(0)`,
    since PF6 keeps a group's links.

## Done — 2026-10-05 (P2: WF11, PF5–PF7)

- [x] **PF5 — chunk lookups without reading the whole index.** A `ChunkIndex` is made when a dataset is
  first read and holds no chunks yet. A read covering fewer than an eighth of the grid's cells looks each
  cell up in the file's index through a `layout.ChunkLookup`, as libhdf5's `H5D__chunk_lookup` does:
  - an implicit index by arithmetic (`ImplicitIndex.lookup`);
  - a fixed array by the chunk's entry, in its data block or page (`FixedArray.lookup`; paged blocks check
    the page-init bitmap);
  - an extensible array through the index block, the super block's secondary block and the data block
    (`ExtensibleArray.lookup`, paged blocks too);
  - a version-1 B-tree by descending its keys, each a child's first chunk (`ChunkBTreeV1.lookup`);
  - a version-2 B-tree by `BTreeV2.find` on the records' scaled offsets (`ChunkBTreeV2.lookup`).

  Each block, page or node is checksum-verified the first time a lookup reads it, as reading the whole
  index verifies it. A larger read reads the whole index (once, then kept), as does every read once the
  lookups made add up to the grid's cells. A single-chunk index is read whole.
  - **Tests:** `ChunkLookupTest`: every element sampled, points and strided selections of every index
    type of thousands of chunks (paged, sparse, filtered, multi-level B-trees, rank 3) find what the whole
    index finds; a one-element read reads under a quarter of the bytes the index takes. New fixtures
    `big_index.h5` and `big_index_old.h5` (libhdf5's).
  - **Measured:** one element of `ea_paged.h5` (150,000 chunks) reads 24,598 bytes in 0.30 ms; with the
    whole index, 2.9 MB in 19 ms (`BENCHMARKS.md`).
- [x] **PF6 — share per-object caches between handles.** New `ObjectCache`, a per-file resource of
  `ObjectCache.State`s by object-header address: the header, attributes, links (and the map of them by
  name), and a dataset's datatype, dataspace, layout, filters, fill value, chunk index and virtual
  dataset. Every handle of an object gets its state, however it was reached (a path, a hard link, a
  reference, `children()`); `classify` parses a header into the state the handle then uses. A group's
  `children()` stays per handle, since its children are named by the handle's path.
  - **Bound:** `OpenOptions.objectCacheSize(bytes)`, 16 MiB by default, least recently used first out,
    by estimate: 512 bytes a state, 48 bytes a header message, about 160 bytes an attribute and 128 a
    link, and 16 + 8 × rank bytes a chunk once an index is read whole. The state being charged is never
    evicted. 0 shares nothing.
  - **Tests:** `ObjectCacheTest`: handles reached by two hard links, a path, a reference and `children()`
    share one state; 600 objects under a 64 KiB bound stay within it, and evicted objects still read;
    a bound of 0 shares nothing; threads share one object's state; the option.
- [x] **PF7 — virtual mappings that scatter.** `VirtualDataset` splits a read into `Part`s (a mapping, or
  a printf source, with the virtual elements it fills and the source elements they take). A shared
  `transfer` reads each part's source elements one of three ways:
  - the box that bounds them, when they fill a quarter of it or more (a mapping of the same shape, or a
    little strided);
  - every element the source selection picks, through the source's `selectedData`, when all are wanted;
  - otherwise the elements themselves, in batches of 65,536 points, so only the chunks that hold them are
    read.

  A strided or point selection of a virtual dataset (`Dataset.selectedData`) now goes through
  `VirtualDataset.gather`, which pairs the selection's elements with each mapping's target positions
  (new `SelectedElements.positionOf`) instead of reading the bounding box.
  - **Tests:** `VirtualScatterTest`: a strided source, a source of another shape read by column, and a
    strided selection read under a third of the bytes of the box; every strided and point selection of 12
    virtual datasets (other files, gaps, columns, strides, byte order, unlimited, printf, same-file) reads
    what the whole read holds there. New fixture `vds_scatter.h5` (libhdf5's, same-file sources).
- [x] **WF11 — writing through virtual datasets.** `DatasetWriter.write` and `writeRaw` on a virtual dataset
  of a file being changed write into its sources, as libhdf5's `H5Dwrite` (`H5D__virtual_write`) does,
  through the same `Part`s reads use: each element into the source element its mapping pairs it with, in
  runs along the source's last dimension, byte-swapped for a source of the other byte order.
  - **Same file:** the source is opened in the session by its header's address. **Another file:** a
    session of its own (`Hdf5Writer.open`, one per file), completed by `close()` once this file's reader,
    which may hold it open, is closed. `abort()` aborts them too; a failed one is completed by a retry.
  - **Refused before anything is written,** as libhdf5 refuses: a box with elements no mapping covers, or
    whose source is missing, or that two mappings cover (`IllegalArgumentException`, "write requested to
    unmapped portion of virtual dataset"); a source of another type; elements past a source's extent; a
    source file read through a resolver; variable-length or reference data. `extend` and `append` are
    refused: the sources set the extent. Writes through a virtual dataset whose sources are virtual nest
    at most 32 deep, so a dataset mapping itself fails.
  - **Tests:** `WriteVirtualTest` (8): sources in other files (regular, columns, strided, byte order,
    unlimited, printf), in the same file in one session (interleaved columns, printf names, a floored
    mapping), scattered sources; refusals with nothing written; `abort()`; a dataset mapping itself.
  - **Interop:** the export writes through the unlimited, printf and same-file mappings of
    `vds_unlimited.h5`. HDF5 2.0 reads the virtual datasets (1.14 cannot read that file's version-1 mapping
    block, whoever writes it), and both read the source files changed.
- [x] **Benchmarks:** the documented benchmark command no longer fails in the 128 MB fuzz execution, which
  `-Dtest=Benchmarks` also selects; the benchmark skips itself there.

## Done — 2026-10-05 (P2: WF7, WF10)

- [x] **WF7 — szip's better-ratio modes, and a user block.**
  - **szip:** `filter.Aec.encode` is now a port of libaec's encoder (`encode.c`), so its stream is
    libaec's, byte for byte:
    - nearest-neighbour preprocessing (a reference sample per interval, then mapped differences), signed
      and unsigned;
    - zero-block runs, ended at a 64-block segment or the interval's end ("remainder of segment");
    - the second extension, sample splitting with libaec's search for `k` (carried from block to block),
      and uncompressed blocks, chosen as libaec chooses, in its 64-bit arithmetic;
    - a final, shorter interval padded with its last sample.

    `Szip.encode` pads scanlines as libaec's SZ layer does (the last pixel repeated under
    nearest-neighbour coding). `DatasetWriter.szip(SzipCoding, pixelsPerBlock)` writes either coding,
    blocks of 2 to 32.
  - **User block:** `Hdf5Writer.create(path, format, userBlock)`: the bytes, zero-padded to 512, 1024,
    ...; the superblock's base address is where it is, and its end-of-file address absolute, as libhdf5
    writes them. A block holding the HDF5 signature where readers look for one is refused.
  - **Tests:** `AecTest.encodesLibaecReferenceVectorsByteForByte` (all 122 libaec vectors: signed, unsigned,
    preprocessed, 8 to 32 bits, remainder of segment); `SzipFilterTest.encodesSzipChunksAsLibaecDoes`;
    `WriteFilterConformanceTest` (every szip chunk of `szip.h5`, re-encoded, is libhdf5's);
    `WriteEditFiltersTest.writesSzipNearestNeighbour` and `writesAUserBlock` (both formats),
    `refusesAUserBlockThatLooksLikeHdf5`.
- [x] **WF10 — what changing a file refused or left.**
  - **Filters with parameters of their own:** a dataset of the file keeps the client data libhdf5 stored,
    and its chunks are encoded with it:
    - n-bit (new `filter.Nbit`): libhdf5's whole datatype description (atomic, array, compound, no-op
      types, nested), both ways, so the reader also decodes arrays and nested compounds now; its index
      walk is libhdf5's, quirk included (after an array of arrays or compounds it stays at the base type);
    - scale-offset (`ScaleOffset.encode`): integers of fixed or automatic minbits, and decimal-scaled
      floats, in libhdf5's float or double arithmetic, with fill values, in either byte order;
    - szip of either coding (WF7).

    All three re-encode every chunk of libhdf5's fixtures byte for byte (`WriteFilterConformanceTest`).
  - **Partial edge chunks stored unfiltered** stay so under a fixed-array index (whose version-4 layout
    keeps the flag); under a version-1 B-tree (a dataset that can grow) every chunk is filtered, and
    chunks that stop being partial are filtered, as libhdf5 filters them when a dataset grows.
  - **External raw data** is written into its files' slots, the files found as the reader finds them
    (under its access policy), created if missing as libhdf5 creates them.
  - **Shared-message table (new `SharedMessages`):** an attribute kept there, deleted or replaced (in a
    header or in dense storage), is released: its count drops, and at 0 its record leaves the index (a
    list rewritten in place, a B-tree written anew; the table's count updated). Shared attributes are
    kept by their heap IDs when an object's attributes are rewritten, compact or dense. A dataset that
    grows gets its own dataspace message: a shared one (in the heap) is released by its ID, a shareable
    one kept in its header by its encoding's hash, as libhdf5 finds it.
  - **Links:** the layout now writes each object once, where its first link reaches it, with paths
    resolved through the session's tree (and the file's links), so:
    - `hardLink(name, targetPath)` names an existing object again (cycles included, laid out twice and
      checked); counts are kept: a Reference Count message (or the version-1 prefix) for new objects, the
      header's count for the file's;
    - `move(name, newPath)` moves or renames a link, a group's contents going with it;
    - a link deleted leaves its object to its other links; an object of the file opened is written
      however it is reached.
  - **External links in groups of the original format** convert the group, as `H5G_obj_insert` does: its
    entries and new links become Link messages (dense beyond 8), with Link Info and Group Info, in place
    of its Symbol Table message; the root's superblock entry then caches no table. New files in the
    earliest format do the same.
  - **Dense back to compact:** links or attributes shrunk below the minimum for dense storage (6, or the
    object's own) go back into the header, the info message pointing at no heap.
  - **Object headers:** bytes left over, too few for a message, become a gap at the chunk's end, the
    messages after them moving forward (`H5O__add_gap`), so a small header can still make room.
  - **The journal:** `close()` writes, after the new metadata, a redo journal of every write it will make
    over the file (the superblock, changed headers, shared-message indexes) with a trailer at the file's
    end, flushes it all, then makes the writes and cuts the journal off. A version-3 superblock is marked as
    open by a writer meanwhile (as libhdf5 marks it). `Hdf5Writer.open` redoes a journal it finds; a
    retried `close()` redoes its own; `abort()` after a failed `close()` keeps it.
  - **Tests:** `WriteEditFiltersTest` (szip, scale-offset, n-bit, partial edge chunks, external raw data),
    `WriteLinksEditTest` (10: hard links and cycles, moves and renames, conversions, dense shrinking,
    in new files and in libhdf5's), `WriteEditSharedTest` (3: lists and B-tree indexes, shared and
    shareable dataspaces), `WriteJournalTest` (5: interrupted changes redone by `open` and by a retried
    `close`, a version-3 superblock marked meanwhile, a journal cut short ignored), and
    `ObjectHeaderEditorTest.bytesTooFewForAMessageBecomeAGapAtTheChunksEnd`.
- **Verified by libhdf5:** `WriterInteropExport` gained `szip_nn.h5` (both formats, every chunk
  re-encoded by libaec to the same bytes), `userblock.h5` (both), `hard_links.h5` (both), and edited
  copies of libhdf5's szip, scale-offset, n-bit, partial-edge, group, external-data, dense and SOHM
  fixtures, plus an interrupted change (refused) and one redone. In the change pass libhdf5 also deletes
  the shared attributes a file names, through the indexes Falcon left. HDF5 2.0 read 341/341 objects and
  1.14.6 296/296; each changed every file and read it back.

## Done — 2026-10-05 (P2: WF5, WF6, WF8, WF9)

- [x] **WF9 — data given whole is written when the next object is added.** A dataset made with its data
  (the per-type methods) writes it when the next dataset or group is added, or on `close()`, so the
  writer holds one such dataset's data at most. Its storage is configured until then.
  - **Tests:** `WriteStreamingTest.givenDataIsWrittenWhenTheNextObjectIsAdded` (both formats).
- [x] **WF8 — references in chunked datasets and in attributes.**
  - **Two layouts:** `close()` lays the metadata out once to place every object. It then fills the
    object references into attributes and compact data, and writes the chunks that held them after the
    metadata's space. A second layout writes the same metadata with the references in; it is checked to
    put every object in the same place.
  - Region references were never the problem: their heap objects are filled in where they are.
  - **Memory:** a chunked dataset of object references keeps its chunks until `close()`.
  - **Tests:** `WriteTypesAndLinksTest.referencesInChunksCompactDataAndAttributes` (filtered and growable
    chunks, compound members, compact data, scalar and dense attributes, region references, both
    formats) and `aMissingReferenceTargetCanBeAddedAndCloseRetried`.
- [x] **WF5 — dense storage of any size**, in `write/FractalHeapWriter` and `write/BTreeV2Writer`:
  - fractal heaps as libhdf5 lays them out (`H5G_FHEAP_*`): a doubling table four 512-byte blocks wide,
    direct blocks up to 64 KiB, then child indirect blocks nested as deep as needed. Objects go in order
    into the first block with room for them; blocks skipped stay unallocated. The allocation iterator
    is left after the last block, so libhdf5 can add to the heap;
  - version-2 B-trees of 512-byte nodes, built bottom-up to as many levels as the records need, with the
    count widths `H5B2__hdr_init` derives; names of equal hash ordered by their bytes;
  - original-format groups of any size: multi-level group B-trees, with the file's K values.
  - **Tests:** `WriteDenseStorageTest` (100,000 links: child indirect blocks and a four-level name index;
    3,000 attributes, attributes up to 58 KB, names of equal hash) and
    `WriterEdgeCaseTest.earliestFormatHoldsManyAttributesAndChildren` (9,000 children).
- [x] **WF6 — changing an existing file in place: `Hdf5Writer.open`.**
  - **The file:** `ExistingFile` opens it through the reader, and refuses files the writer cannot
    change safely:
    - addresses or lengths other than 8 bytes;
    - a non-default file driver;
    - persistent free-space tracking, or paged allocation;
    - consistency flags set by an open writer.

    Writing continues at its end-of-file address, relative to its superblock, so a user block is kept.
    New objects take the file's format (superblock version 0–1: the earliest); B-trees take its K values.
  - **Object headers** change through `write/ObjectHeaderEditor`, in either version:
    - a removed message becomes a null message;
    - an added one takes a null message large enough, or a new continuation chunk after the file's end,
      pointed at from a null message or from the place of a message moved there to make room;
    - same-size replacements are in place;
    - a version-2 chunk ending in a gap never keeps a null message (libhdf5 rejects that): the gap is
      merged into the null space, as `H5O__eliminate_gap` does;
    - changed chunks are rewritten whole, with their checksums (version 2) or message count (version 1).
  - **Groups:**
    - original-format groups get a new symbol table, with the caches of the group symbol tables they
      hold, and the superblock's root entry is updated;
    - new-style groups add or remove compact Link messages within their compact limit, else are written
      densely anew;
    - groups that track creation order give new links the next creation index and keep their
      creation-order index (B-tree type 6).
  - **Attributes:**
    - version-1 headers: messages;
    - version 2: compact within the header's limit, else dense anew, with the creation-order index (type
      9) of objects that track it;
    - an attribute set again replaces the file's.
  - **Datasets:** a dataset of the file is written through the same `Storage` as a new one:
    - contiguous data in place, or a block allocated (filled with the fill value) for one never written;
    - compact data in its header;
    - chunked data through its filters (deflate, shuffle, fletcher32, szip), with chunks read back
      through the reader's pipeline.

    Its chunk index is written anew (fixed array, or version-1 B-tree if it can grow) and its Layout
    message replaced; a grown dataset's Dataspace message is updated in place.
  - **Deleting** a link lowers its object's hard-link count when another link remains; a reference to a
    deleted object fails `close()`.
  - **Completing:** the new metadata goes after the data and is flushed; then the superblock (new end,
    root cache), then the changed header chunks, each one write. `abort()` truncates what was added.
  - **Tests:**
    - `WriteEditTest` (21) changes files Falcon wrote, in two sessions, and files libhdf5 wrote:
      - groups of both styles, compact and dense links and attributes, creation order;
      - every chunk index type, contiguous, compact, never-written and big-endian data;
      - filtered chunks of the earliest format, shared messages, a second hard link;
      - user blocks, non-default K values;
      - refusals, and `abort()`.
    - `ObjectHeaderEditorTest` (5): null space, continuation chunks with a message moved, gaps, reference
      counts, both versions.
- **Verified by libhdf5:** `WriterInteropExport` gained:
  - `references.h5` and `dense_big.h5`, each in both formats;
  - eighteen `edit_*.h5` files changed from Falcon's and libhdf5's (fixtures copied, then opened).

  The checker gained checks of counts, deleted names, reference counts, references in attributes and
  compound members, and creation order. A new pass has each library change every file and read it all
  back. HDF5 2.0 reads 246/246 objects and 1.14.6 226/226, and each changes every file. Three sources
  libhdf5 2.0 wrote with version-5 layouts are 2.0-only.
- **New fixtures:** `tracked_order.h5`, `tracked_order_old.h5` (`gen_fixtures.py tracked_order`).

## Done — 2026-10-05 (P2: WF1–WF4)

The writer was rebuilt around streaming. Its serialization of groups, headers, heaps, and filters was
kept; the new parts are `write/OutputFile`, `write/DatatypeEncoder`, `write/ChunkIndexWriter`,
`ValueEncoder`, and the writer's `Storage` and `GlobalHeaps`.

- [x] **WF1 — streaming writes.**
  - **The file:** the superblock's bytes are reserved at offset 0. Raw data is written after them, as it
    comes, into a sparse temporary file, at 64-bit offsets. On `close()`, the metadata goes after the
    data (a `GrowBuffer` now starts at a base offset), the superblock is written, and the file is moved
    into place.
  - **Contiguous data** is allocated at its first write and written in place; a non-zero fill value is
    written across it first.
  - **Chunks** are kept in memory until all of their elements within the dataset's bounds are written,
    then filtered and written. A chunk written again is read back through the reader's filter pipeline
    and written anew. Partly written chunks are written on `close()`.
  - **Variable-length data:** global-heap collections are written as they fill (about 1 MiB or 65,535
    objects), or when a chunk referring to them is written. An id written before its collection is has
    its address filled in when it is.
  - **Growing datasets** (`maxShape`, `extend`, `append`) are indexed by a version-1 B-tree (layout
    version 3), built bottom-up from 64-entry nodes, as libhdf5 allocates them; fixed-size ones by a fixed
    array, as before.
  - **References:** object references and region references' heap objects are filled in once the
    objects' addresses are known.
  - **Tests:** `WriteStreamingTest` (10):
    - a 4 MB dataset is in the temporary file block by block, before `close()`;
    - appends, a rewritten filtered chunk, extents with unwritten rows, and 5,000 chunks in a 3-level
      B-tree, in both formats;
    - 70,000 streamed variable-length strings, across several heap collections;
    - checks on values, boxes, growth, and configuration order;
    - a file past 2 GB: a 2 GiB dataset written at its two ends, and a dataset after it.
- [x] **WF2 — datatype breadth**, through WF3: every datatype class, in either byte order where one
  applies:
  - unsigned and big-endian integers, `float16`, `bool`, bit fields, opaque, time;
  - fixed and variable-length strings, enumerations over any integer type;
  - compounds with array and variable-length members, sequences of any fixed-size type, and complex
    numbers (modern format);
  - chunked, for every type but references.

  Typed and string attributes (`attribute`, `stringAttribute`), including variable-length strings.
- [x] **WF3 — a generic `createDataset(name, Datatype, shape...)`** and a typed writer (`write`,
  `append`):
  - `DatatypeEncoder` writes any `Datatype` record as its message, in the versions libhdf5 writes for
    each format;
  - `ValueEncoder` converts the Java values `read()` returns back to elements, refusing values that do
    not fit.

  The per-type methods remain as conveniences; new types need no new methods.
- [x] **WF4 — links and references:**
  - soft and external links, compact and dense, and soft links in the earliest format's symbol tables;
  - region references to all of a dataset, a block, a regular hyperslab, or points.
- **Verified by libhdf5:** `WriterInteropExport` gained `streaming.h5`, `datatypes.h5` and `links.h5`,
  each in both formats (except external links, and complex). libhdf5 2.0 reads all 146 objects, and
  1.14.6 all 140 (complex numbers are 2.0-only). This includes:
  - every datatype, with its numpy dtype checked;
  - unlimited maximum shapes and multi-level B-trees;
  - links followed by h5py, and region references read through h5py's `dset[regref]`.

  The checker gained those checks, and now runs Maven with `-am`.
- **Tests:** `WriteTypesAndLinksTest` (5), and two `WriterEdgeCaseTest` cases updated: the earliest format
  now writes chunked datasets, and a failed `close()` keeps the temporary file.

## Done — 2026-10-05 (P2: A11, A12)

- [x] **A11 — external links and references into other files.**
  - **One way to other HDF5 files.** Virtual sources, external links' files, and references' files are
    all found by `SourceFiles.find`:
    - under the file's `ExternalFileAccess` policy or resolver;
    - in libhdf5's order (`H5F_prefix_open_file`, which libhdf5 uses for virtual sources and external
      links alike);
    - opened once and kept open until the file closes;
    - a name that leads back to the file itself reuses it, as libhdf5 does.
  - **External links** are followed as libhdf5's `H5L__extern_traverse` follows them:
    - the object path is taken from the other file's root, and what it reaches is named by its path
      there;
    - soft and external links together are limited to 16 on one path, across files, as libhdf5's
      `H5L_NUM_LINKS` limits them, so a loop between two files ends.
  - **Revised references into other files** are resolved to that file's object, region, or attribute.
    The reference's address is read at its own width and checked against the other file's.
  - **Tests:** `OtherFileObjectsTest`, on new h5py fixtures `elinks.h5`, `elinks_target.h5`, and
    `elinks_sub/inner.h5`, with h5py's names as the oracle:
    - eleven links: to a group, a dataset, a soft link and a root; a chain across two files; a loop; a
      subdirectory; an absolute name libhdf5 finds by its file name alone; a missing file; a missing
      object; and a file outside the directory;
    - every link reaches what libhdf5 reaches, named as libhdf5 names it;
    - the policy (`none()` refuses), a resolver serving a file read from bytes, and closing.
    - `refs_revised.h5` now also holds a region and an attribute reference into its other file. They are
      read through the default policy, `none()`, a resolver, and with the other file missing.
  - **Tests changed:** `LinksTest`, `P2ApiTest`, and `P2ReadTest` asserted that external links and
    references were not followed. `PerformanceTest` now makes its fresh handles from each object's own
    file. `storage_metadata.txt` is regenerated for the new fixtures.
- [x] **A12 — time values.** HDF5's only time types are `H5T_UNIX_D32*` and `H5T_UNIX_D64*`, Unix `time_t`
  values:
  - the integer readers give the signed seconds;
  - `read()` gives `Instant`s.

  Tested on 32-bit little-endian and 64-bit big-endian datasets and an attribute, written by libhdf5 into
  `typed.h5`. h5py cannot read these.
- **T3:** the fuzz test covers `elinks.h5` (130 s against a 300 s limit).

## Done — 2026-10-05 (P2: A1, A4, A7–A10)

The readers of `Dataset`, `Attribute`, and `Selection` now share one decoder, `ElementReader`, so the
three read the same types the same ways.

- [x] **A1 — typed reads for every datatype class but time.**
  - **Compound:** `member(name)` (on all three) reads one member like a dataset of its type; members of
    members by chaining. `read()` returns each member's values by name, in member order.
  - **Enumeration:** the integer readers give its values; `readStrings()` and `read()` give member names
    (`null` for a value no member has).
  - **Array:** read through its base type's readers, every element's values in turn.
  - **Complex** (HDF5 2.0):
    - `readComplexDoubles()` and `readComplexFloats()` give (real, imaginary) pairs;
    - `readDoubles()` and `readFloats()` give the real part, as libhdf5 2.0 converts to
      `H5T_NATIVE_DOUBLE` (checked with `H5Dread`);
    - h5py's `{r, i}` compound reads as complex too, and a real number as complex with an imaginary part
      of 0, as libhdf5 converts it.
  - **Bit field:** read as unsigned integers, as h5py reads them; libhdf5 has no bit-field-to-integer
    conversion.
  - **Opaque:** `read()` gives one `byte[]` per element.
  - **Sequences** of any other base type read as `Object[]` rows.
  - **Bug found on the way:** `Datatype.Enumeration.Member.value()` was read as an unsigned little-endian
    number whatever the base type. So a big-endian or negative member was wrong: −5 as a big-endian
    `int16` read as 64,507. It is now the base type's integer.
  - **Tests:** `TypedReadTest` (12), on files Falcon writes and on `typed.h5`, a new h5py fixture with:
    - nested compounds with array, enumeration, string, variable-length string, and complex members,
      contiguous and chunked;
    - big-endian, unsigned, and unknown-value enumerations;
    - arrays of floats and of strings;
    - complex numbers: h5py's compound, and native ones in three precisions and both byte orders;
    - bit fields, opaque data, and a sequence of compounds;
    - compound, enumeration, and complex attributes.
- [x] **A4 — selections.**
  - **New:**
    - `Dataset.select(start, stride, count, block)`, as `H5Sselect_hyperslab`, read in the shape of its
      indices;
    - `selectPoints(long[][])`, as `H5Sselect_elements`, read in the order given.
  - **Reading only what is selected:**
    - a strided selection reads only the chunks in the grid cells its indices fall in, in each dimension;
    - points read the chunks they fall in, each once;
    - contiguous data in the file is read run by run, and other layouts read the bounding box.
  - **Every reader on `Selection`:** strings (also variable-length ones and enumeration names), sequences,
    the three reference readers, `readRawBytes()`, `read()`, and `member(name)`. A region reference's
    regular hyperslab is no longer expanded into a list of coordinates.
  - **Tests:** `SelectionTest` (33):
    - every dataset of 28 fixtures, covering every layout, chunk index, filter, and datatype class but
      time, is read with 6 random strided and 6 random point selections, each byte-identical to the same
      elements of a whole read;
    - through a `RangeReader`, every 100th row reads under a fifth of the file, three points under a
      twentieth, and 16 runs of contiguous data at most 32 pages;
    - every reader on a selection;
    - argument checks.

    Breaking the point grouping and the per-dimension chunk cells fails 21 of the 33.
  - **Timing:** for 2000 × 2000 doubles, against a whole read of 12–18 ms:

    | Selection | Chunked | Contiguous |
    |---|--:|--:|
    | every other element (1M) | 16 ms | 21–28 ms |
    | 100,000 random points | 49–57 ms | 14–17 ms |
- [x] **A7 — cache sizes:** `OpenOptions.chunkCacheSize(bytes)` (0 turns it off), `readerPageSize(bytes)`,
  and `readerCacheSize(bytes)`. A virtual dataset's source files inherit them.
  - **Tests:**
    - any sizes, down to 521-byte pages (the smallest is 512, as libhdf5's) and no caches, read four
      fixtures identically;
    - one 1 MiB page reads a file of under 1 MiB in one request;
    - a one-page cache makes more requests than the default.
- [x] **A8 — internal types out of the public API.**
  - `Attribute`'s constructor is package-private: the attribute-message parser returns the parts.
  - **`ApiSurfaceTest`** checks every public or protected signature in the exported packages by
    reflection. It fails on the old constructor.
  - **Referenced objects' paths** are found as libhdf5's `H5Iget_name` finds them: depth-first through
    hard links, each group's in its native order.
    - Checked against h5py's names for 16 references in `paths.h5` and `paths_latest.h5`.
    - These include objects with several hard links in old-style, compact, and dense groups, which
      libhdf5 names in name order, creation order, and name-hash order.
    - `toString()` shows the address until the path is known.
- [x] **A9 — paths that cannot be mapped.** `Hdf5File.open(Path)` reads through the file system's channel
  when it cannot map the file (`UnsupportedOperationException`), and closes the channel with the file.
  External raw data and virtual sources are found in the HDF5 file's own file system.
  - **Test file system:** a test-only one over the default, whose provider cannot open a `FileChannel`,
    so `jdk.zipfs` is not needed.
  - **Tested:** data, external raw data, virtual sources, the policy, and the channel's closing.
- [x] **A10 — other files of a remote file:** `ExternalFileAccess.resolvedBy(Resolver)`.
  - **The resolver** gets each name and its `Purpose` (`RAW_DATA`, `VIRTUAL_SOURCE`). It returns a
    `RangeReader`, or `null` for a missing file (fill for a virtual source, an error for raw data), or
    refuses with `HdfUnsupportedException`. An `IOException` makes a virtual source missing.
  - **Closing:** an `AutoCloseable` reader is closed after a raw-data read, or when the file closes.
  - **Tests:**
    - virtual datasets and external raw data of files opened from bytes, from readers, and from paths;
    - resolvers that find nothing, fail, or refuse;
    - closing.
- **T3:** the fuzz test also reads a strided and a point selection of every dataset and finds the path of
  every referenced object. It also covers `typed.h5` and `paths_latest.h5` (124 s against a 300 s limit).

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
