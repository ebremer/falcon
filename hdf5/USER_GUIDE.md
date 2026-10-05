# Falcon HDF5 — User Guide

Falcon's `hdf5` module is a pure-JDK 25, zero-dependency reader and writer for the
[HDF5 File Format v4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html) (HDF5 2.0).
Everything is in the exported package `com.ebremer.falcon.hdf5`.

```java
import com.ebremer.falcon.hdf5.*;
import java.nio.file.Path;
```

A file opened from a path is memory-mapped (the Foreign Function & Memory API), so it stays on disk and
files larger than 2 GB are handled without the `MappedByteBuffer` size limit. A file can also be opened
from bytes in memory, or read on demand through a `RangeReader` (see *Other sources*).

---

## Reading

### Open a file and walk the tree

```java
try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {   // AutoCloseable; unmaps on close
    Group root = h5.root();
    for (String name : root.childNames()) { /* ... */ }
    for (Hdf5Object child : root.children()) { /* Group | Dataset | CommittedDatatype */ }

    Group   run  = root.group("run");                 // navigate by name
    Dataset temp = run.dataset("temperature");
    Dataset same = root.dataset("run/temperature");   // or by path
    root.child("maybe").ifPresent(o -> { /* Optional lookup */ });
}
```

Every lookup (`child`, `link`, `group`, `dataset`, `committedType`) takes a path, as libhdf5 does: link
names separated by `/`, relative to the group or, starting with `/`, to the root. Repeated and trailing
slashes are ignored, `.` is the group itself, and soft links along the way are followed. An object is
named by the path it was reached through. When a path reaches nothing, `group`, `dataset` and
`committedType` say which component failed and why.

`Hdf5File` also describes the file itself:
- `superblockVersion()`;
- `fileSpaceInfo()`, for files that record it: allocation strategy, page size, and total free space.
  The version 0 written by HDF5 1.10.0 is read too, as libhdf5 maps it.
- `btreeKValues()`: the B-tree split values the file was created with, or libhdf5's defaults.
- `driverInfo()`: the file driver, if not the default. For example, `NCSAfami` means the file is one
  member of a family, and Falcon reads only the member it opened.

`close()` may be called more than once; after it, reading anything obtained from the file throws
`HdfClosedException`, and `isOpen()` returns false.

`Hdf5File.open(path, options)` takes `OpenOptions`, which hold the external-file policy below and how
virtual datasets with unlimited mappings are read (see *References and virtual datasets*):

```java
Hdf5File.open(path, OpenOptions.defaults()
        .externalFileAccess(ExternalFileAccess.unrestricted())
        .virtualView(OpenOptions.VirtualView.FIRST_MISSING)
        .virtualPrintfGap(2));
```

### Other sources

A file need not be a local path:

```java
Hdf5File.open(bytes);                                    // a byte[] already in memory (read in place)
Hdf5File.open(RangeReader.of(channel));                  // any SeekableByteChannel
Hdf5File.open(reader, options);                          // your own RangeReader: an object store, HTTP ranges, ...
```

A `RangeReader` has two methods, `size()` and `read(position, buffer)`, and must allow calls from
several threads. Falcon reads through it on demand:
- **Metadata** is read in 64 KiB pages, which are cached (16 MiB per file).
- **Data** is read only where a read asks for it: a chunk or contiguous run of 64 KiB or more in one
  call, a smaller one through the pages. Reading a small selection of a large remote file fetches the
  metadata and the pages or chunks around that selection, not the file.

A reader failure surfaces from the read that needed the bytes as `java.io.UncheckedIOException`, or as
the `IOException` of `open` itself while the file is being opened. Falcon does not close the reader:
close it after the `Hdf5File`. Such a file has no path (`path()` is `null`) and no directory of its
own, so by default it opens no other file. `allowDirectory(...)` or `unrestricted()` let it open
external raw data and virtual-dataset sources (see *Files outside the HDF5 file*).

### Links

A group holds links. `links()` lists every one, and each `Link` is `Hard`, `Soft`, `External`, or
`UserDefined`:

```java
for (Link link : group.links()) {
    switch (link) {
        case Link.Soft s     -> System.out.println(s.name() + " -> " + s.targetPath());
        case Link.External e -> System.out.println(e.name() + " -> " + e.fileName() + ":" + e.objectPath());
        default -> { }
    }
}
```

`childNames()` names every link. `children()`, `child(name)`, `group(name)` and `dataset(name)` follow
hard links and soft links:
- An object reached through a soft link takes the link's path (e.g. `/links/soft`).
- **External links are not followed.** `dataset(name)` on one throws `HdfUnsupportedException` naming
  the target file; open that file yourself.
- A soft link whose target is missing, or which loops (more than 16 soft links on one path, as in
  libhdf5), reaches nothing.

### Files outside the HDF5 file

External raw data (an External File List) and virtual-dataset sources are other files named inside the
HDF5 file. An untrusted file could otherwise point Falcon at any local file, or at a network share. So
by default Falcon opens only files in the HDF5 file's own directory tree; any other name fails the read
with `HdfUnsupportedException`. Choose a different policy when opening:

```java
Hdf5File.open(path);                                                         // = ExternalFileAccess.sameDirectory()
Hdf5File.open(path, ExternalFileAccess.sameDirectory().allowDirectory(raw)); // also files under raw/
Hdf5File.open(path, ExternalFileAccess.unrestricted());                      // any name, as libhdf5 (trusted files)
Hdf5File.open(path, ExternalFileAccess.none());                              // never open another file
```

A virtual-dataset source is looked for where libhdf5 looks, among the places the policy allows:
- **An absolute name** is tried as written. If that fails, its file name alone is tried, as libhdf5 does
  for a file moved together with its sources.
- **A relative name** is tried in the HDF5 file's directory.
- **Then, for both,** each allowed directory, and under `unrestricted()` the working directory.

A source found nowhere is missing and reads as the fill value. There is one exception: when the
policy refuses the name's own location and no allowed candidate exists, the read fails, because the
refused file may be the real source.

### Read a dataset

```java
int[]    a = ds.readInts();        // integers, each of which must fit in an int
long[]   b = ds.readLongs();       // integers, each of which must fit in a long
float[]  c = ds.readFloats();      // floating point, or integers converted
double[] d = ds.readDoubles();
String[] s = ds.readStrings();     // fixed- or variable-length strings
Object natural = ds.read();        // most natural Java array for the datatype
byte[]  raw    = ds.readRawBytes(); // element bytes as stored (after the filters are undone)

int    scalar = ds.readInt();      // single-element datasets: readInt/readLong/readDouble/readString
```

Multidimensional data is returned flattened row-major; `ds.dataspace().dimensions()` gives the shape and
`ds.datatype()` the element type.

**Integers are exact.** `readInts()` and `readLongs()` never wrap a value: one that does not fit throws
`HdfUnsupportedException`. A `uint32` above 2³¹−1 does not fit an `int`, and a `uint64` of 2⁶³ or more
does not fit a `long`. `read()` picks an array every value of the type fits in:

| Datatype | `read()` returns |
|---|---|
| `int8`–`int32`, `uint8`, `uint16` | `int[]` |
| `uint32`, `int64` | `long[]` |
| `uint64` | `java.math.BigInteger[]` |

**Integers as floating point.** `readDoubles()` and `readFloats()` also read integer data, converted as
libhdf5 converts it to `H5T_NATIVE_DOUBLE` or `H5T_NATIVE_FLOAT`. That is exact up to 53 (or 24)
significant bits; wider values are rounded once to the nearest, ties to even. The same holds for
attributes, selections, and variable-length sequences (`readVlenDoubles()`).

**Non-native layouts.** Integers are read from their bit offset and precision (a 12-bit value packed in 16
bits, a 24-bit integer in 3 bytes). Floats are decoded from their sign, exponent, and mantissa fields as
libhdf5 decodes them, so bfloat16, x87 80-bit extended precision, and VAX floats (in their own byte
order, as libhdf5's `H5T_VAX_F32` and `H5T_VAX_F64` store them) read correctly.

### Compression filters

Chunked data is decoded through any of HDF5's built-in filters:
- `deflate`, `shuffle`, `fletcher32` (verified), `szip`, `nbit`, and `scaleoffset`.

It is also decoded through the third-party filters most common in the wild:
- LZF (32000, h5py's built-in filter);
- Blosc (32001), with every internal codec (BloscLZ, LZ4, LZ4HC, Snappy, zlib, zstd) and both shuffles;
- LZ4 (32004);
- bitshuffle (32008), alone or with LZ4 or zstd;
- Zstandard (32015), verifying each frame's content checksum when it has one.

These are pure-Java codecs that Falcon's Zarr module shares (see `../core`). A chunk that an optional
filter skipped is read as stored. Any other filter throws `HdfUnsupportedException` naming its id.
Older storage reads too: the chunked layouts of HDF5 1.6.2 and earlier (layout message versions 1 and 2).

### Storage

A dataset reports how it is stored, as libhdf5's dataset-creation properties and `H5Dget_storage_size`
do:

```java
Dataset.Layout layout = ds.layout();          // COMPACT, CONTIGUOUS, CHUNKED, or VIRTUAL
Optional<long[]> chunk = ds.chunkShape();     // elements per dimension, if chunked
List<Filter> filters = ds.filters();          // id, name, optional, client data; in the order applied
long bytes = ds.storageSize();                // bytes stored in the file (compressed chunks summed)
```

`Filter` has constants for the filters Falcon decodes (`Filter.DEFLATE`, `Filter.ZSTD`, ...). Each
filter's name is the one the file stores, or libhdf5's name for its built-in filters. `storageSize()`
reads the whole chunk index of a chunked dataset; it is 0 for a virtual dataset and for contiguous data
never written.

### Hyperslabs and streaming

Read a rectangular sub-region without materializing the whole dataset — only the chunks it overlaps are
read and de-filtered:

```java
double[] slab = ds.select(new long[]{100, 0}, new long[]{50, 200}).readDoubles(); // rows 100..149

// Process a large dataset block-by-block along the first dimension:
ds.blocks(10_000).forEach(block -> process(block.readDoubles()));
```

Decoded (filtered) chunks are cached per file, so streaming reads that revisit a boundary chunk reuse the
decode.

### Attributes

```java
for (Attribute attr : ds.attributes()) { Object v = attr.read(); }

ds.attribute("units").ifPresent(a -> System.out.println(a.readString()));
int scale = ds.attribute("scale").orElseThrow().readInt();
```

### References and virtual datasets

```java
Hdf5Object[] targets = ds.readObjectReferences();      // object references -> the objects they name
Selection[]  regions = ds.readRegionReferences();      // region references -> dataset selections
double[] slice = regions[0].readDoubles();
Attribute[]  attrs   = ds.readAttributeReferences();   // revised attribute references -> attributes
```

Attributes holding references have the same three methods.

A region reference may select one block, points, several blocks, everything, or nothing; every
encoding libhdf5 writes is read. A selection that is not one block (`isRectangular()` is false) reads
as a flat array of its elements, in the order libhdf5 visits them (points as listed, blocks in row-major
order). A null reference reads as `null`. An element that cannot be resolved, such as a reference to a
deleted dataset, throws only when that selection is used.

**Revised references** (`H5R_ref_t`, written by HDF5 1.12 and later) are read by the same methods.
libhdf5 gives every revised-reference datatype the same code, so each element carries its own kind
(object, region, or attribute):
- `readObjectReferences()` returns, for each element, the object it points at or into. For a region
  reference that is its dataset; for an attribute reference, the attribute's object.
- `readRegionReferences()` gives an element that is not a region a selection that throws when used.
- `readAttributeReferences()` fails if an element is not an attribute reference.
- `read()` returns the objects, as `readObjectReferences()` does.
- A reference into another file names that file, but Falcon does not follow it:
  - `readObjectReferences()` and `readAttributeReferences()` throw `HdfUnsupportedException`.
  - `readRegionReferences()` gives that element a selection that throws when used.

Virtual datasets are read transparently: `ds.readDoubles()` assembles the data from the source files
(resolved relative to the virtual dataset's own file), filling unmapped regions with the fill value.
Same-file sources (`"."`) and every mapping encoding libhdf5 writes are read. A source file the
`ExternalFileAccess` policy refuses fails the read; a missing one leaves the fill value, as in libhdf5.
A source in the other byte order is converted. A source of any other type, such as a `uint32` source
under an `int32` virtual dataset, throws `HdfUnsupportedException`; libhdf5 would convert it. A
missing source dataset in an existing file also reads as the fill value, as in libhdf5.

A mapping may be **unlimited**, so that the virtual dataset grows with its sources. `dataspace()` then
takes the extent from the sources, as libhdf5 does when it opens the dataset, so it may open the source
files. Two kinds of unlimited mapping are read:
- **Both selections unlimited:** the mapping covers as much as its source currently holds.
- **printf-style:** `%b` in the source file or dataset name stands for 0, 1, 2, and so on. Source *b*
  fills block *b* of the virtual selection, and the sources found set the extent. `%%` in a name stands
  for `%`.

Two `OpenOptions` settings work as libhdf5's dataset access properties do (`H5Pset_virtual_view` and
`H5Pset_virtual_printf_gap`):
- **`virtualView`:**
  - `LAST_AVAILABLE`, the default, extends to the furthest any mapping reaches. Shorter mappings leave
    the fill value at the end.
  - `FIRST_MISSING` stops where the shortest mapping ends, or at a printf mapping's first missing
    source, cutting longer mappings there.
- **`virtualPrintfGap`:** how many missing printf sources in a row the search skips before it stops.
  The default is 0. Skipped sources read as the fill value.

### Datatypes

The reader decodes every HDF5 datatype class. Atomic types (integer, float, string, bitfield, opaque,
time, enum, reference, complex) map to Java arrays; compound and array classes are exposed structurally
via `ds.datatype()` and their bytes via `readRawBytes()`. Variable-length string and numeric sequence
data is read with `readStrings()` / `readVlenInts()` / `readVlenDoubles()` (and the `long`/`float`
variants).

---

## Writing

```java
try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
    // ... build the file ...
}   // written on close()
```

The file is built in memory and written by `close()`. It goes to a temporary file beside the target,
which is then moved into place, so the path holds either the complete new file or what it held before.

- **When `close()` fails,** for example on a reference to an object never added, nothing is written
  and the writer stays open: fix the cause and call `close()` again.
- **`close()` cannot tell that your own code failed.** To discard what was added so far, call
  `abort()`:

  ```java
  Hdf5Writer w = Hdf5Writer.create(path);
  try {
      build(w);
      w.close();
  } catch (RuntimeException | IOException e) {
      w.abort();  // leaves any existing file untouched
      throw e;
  }
  ```

- **After `close()` or `abort()`,** adding to the writer, or to any group or dataset handle it gave out,
  throws `HdfClosedException`.

### Groups, datasets, and attributes

```java
w.intDataset("counts", new int[]{10, 20, 30}, new long[]{3})
 .intAttribute("scale", new int[]{100}, new long[]{});     // scalar attribute (empty shape)

Hdf5Writer.GroupWriter run = w.group("run");                // nested groups
run.doubleDataset("signal", new double[]{0.5, 1.5}, new long[]{2});
run.group("nested").intDataset("inner", new int[]{7}, new long[]{1});
```

Atomic datatypes: `byteDataset` (int8), `shortDataset` (int16), `intDataset` (int32), `longDataset`
(int64), `floatDataset` (float32), `doubleDataset` (float64), `stringDataset` (variable-length UTF-8),
and `fixedStringDataset` (fixed-length, UTF-8).

**Names and limits.** Each rule is checked when the object is added, so a bad name or size throws at
that call rather than at `close()`.
- **Link names** (groups and datasets): must be non-empty, unique within their group, not `"."`, and
  free of `'/'` and NUL.
- **Attribute names**: must be non-empty and unique on their object.
- **Compound field names and enum members**: must be unique.
- **Empty datasets** (a zero in the shape) are written with no storage, as libhdf5 writes them.
- **Size limits**: each object-header message stays under 64 KiB. A compact dataset holds at most
  65,524 bytes, and an attribute message at most 65,514 bytes; store larger values in a dataset.
- **Fixed-length strings**: stored as UTF-8. The datatype is marked UTF-8 when any string is non-ASCII,
  and a string too long for an explicit length is cut at a character boundary.

### Chunking and filters

```java
w.intChunkedDataset("big", data, new long[]{100_000}, new long[]{4096})
 .shuffle().deflate(6);                                     // filters apply in call order
// the chunk shape needs the dataset's rank, dimensions >= 1, and at most 2 GiB per chunk

// also: .fletcher32(), .scaleOffset(), .nbit(precision), .szip()
```

Filters form a pipeline applied to each chunk in the order they are added, exactly as libhdf5 does, and
each may be added once. `scaleOffset()` and `nbit(precision)` work on integer data and must come first.
`nbit(precision)` stores unsigned `precision`-bit values, so a negative or too-wide value is rejected.
`szip()` works on integer or floating-point data and may only follow `shuffle()`. Every filter writes
the on-disk form libhdf5 reads: scale-offset chunks are byte-identical to libhdf5's, and szip chunks use
libhdf5's framing (a chunk szip cannot shrink is stored unfiltered, as libhdf5 does).

### Compound, enum, reference, array, sequence, complex

```java
w.compoundDataset("records", new long[]{3},
    Hdf5Writer.CompoundField.int32("a", new int[]{1, 2, 3}),
    Hdf5Writer.CompoundField.float64("b", new double[]{1.5, 2.5, 3.5}));

w.enumDataset("colors", new long[]{3},
    Hdf5Writer.enumType().add("RED", 0).add("GREEN", 1).add("BLUE", 2), new int[]{2, 0, 1});

w.referenceDataset("refs", new long[]{2}, new String[]{"/counts", "/run"}); // targets by path
w.float32ArrayDataset("grid", new long[]{2}, new int[]{2, 3}, gridData);     // each element a 2x3 array
w.complexDataset("cx", new long[]{2}, new double[]{1, 3}, new double[]{2, -4});
w.intSequenceDataset("ragged", new long[]{3}, new int[][]{{1}, {2, 3}, {}}); // ragged rows
```

### Layout, fill value, and dense storage

```java
w.intDataset("small", data, shape).compact();               // store inline in the header
w.intDataset("grid",  data, shape).fillValue(7);            // custom fill for unwritten elements
```

The fill value is converted to the dataset's type: `fillValue(5)` on a `float64` dataset stores 5.0, and
an integer dataset accepts only a whole number in range. String, reference, compound, array, and complex
datasets take no fill value.

Groups with more than 8 links, and objects with more than 8 attributes, switch to dense storage
(fractal heap + version-2 B-tree) automatically.

### On-disk format

```java
Hdf5Writer.create(path, Hdf5Writer.Format.LATEST);    // modern: v3 superblock, v2 headers (default)
Hdf5Writer.create(path, Hdf5Writer.Format.EARLIEST);  // original: v0 superblock, symbol-table groups
```

In `EARLIEST`:
- every attribute goes in the version-1 object header (there is no dense storage);
- chunked datasets, and groups with more than 256 children, are refused when added.

`EARLIEST` uses the message versions libhdf5 writes for its own earliest setting:
- dataspace v1;
- compound and enum datatypes v1, array datatype v2;
- fill value v2;
- attribute v1.

Files Falcon writes are readable by HDF5 1.10 and later (native complex datasets, a type introduced by
HDF5 2.0, need HDF5 2.0). `tools/fixtures/check_hdf5_writer.py` verifies this against h5py's HDF5 2.0
and, optionally, an HDF5 1.14 build.

---

## Error handling

Every failure Falcon raises is an unchecked `HdfException`:

- `HdfFormatException` — bytes on disk violate the spec (bad signature/checksum, out-of-range address,
  truncated or corrupt input). Corrupt input fails this way rather than as a raw runtime exception, a
  JVM crash, or an infinite loop: every checksummed metadata structure (object headers, B-trees,
  fractal heaps, chunk indexes), every `fletcher32` chunk, and every zstd frame's checksum is verified, loops and over-deep nesting in
  the file's structure are detected, and decompression is bounded by the chunk size.
- `HdfUnsupportedException` — a valid but not-yet-implemented structure, or another file the
  `ExternalFileAccess` policy refuses.
- `HdfClosedException` — a closed `Hdf5File` or `Hdf5Writer` was used.

Catch `HdfException` to handle any Falcon read/write failure. A file read through a `RangeReader` can
also fail to be read at all: that is `java.io.UncheckedIOException`, wrapping the reader's `IOException`.

---

## Performance notes

- **Memory-mapped, >2 GB.** Files are mapped through the FFM API with `long` offsets, so multi-gigabyte
  files are read without copying and without the 2 GB `MappedByteBuffer` cap.
- **Touch only what you read.** A hyperslab or `blocks()` read of a chunked dataset reads and de-filters
  only the chunks overlapping the selection. One of contiguous data reads only the selected runs (zero-copy
  from a mapping), so a selection of a contiguous dataset can be read even when the dataset has more than
  2³¹ elements.
- **Remote files.** Through a `RangeReader`, metadata is read in cached 64 KiB pages and data a chunk or
  run at a time, so a reader pays for what it reads, not for the file's size.
- **Keep the handle.** A `Dataset` reads its chunk index once, on its first read, and then looks each
  selection's chunks up by coordinate; `blocks()` and repeated selections reuse it. A virtual dataset
  keeps its mappings and the sources it found. So reuse a handle for many reads, rather than looking the
  dataset up again each time. An object's attribute list is likewise read once per handle.
- **Virtual datasets read lazily.** A selection skips mappings that do not reach it, and reads from each
  source only the part it maps to. Source files are opened once and stay open until the virtual
  dataset's file is closed.
- **Lookups by name read the index.** `attribute(name)`, `link(name)`, and path lookups search a large
  group's or object's name index (as libhdf5 does) instead of reading every link or attribute.
- **Decoded-chunk cache.** Repeated or streaming reads reuse the filter-decode result for a chunk
  (~16 MB LRU per file).
- **Measuring.** `Benchmarks` (opt-in) times the common read paths; see [`BENCHMARKS.md`](BENCHMARKS.md).
- **Concurrent reads.** An open `Hdf5File` and everything obtained from it may be read from many
  threads at once (e.g. `dataset.blocks(n).parallel()`); close it only after those reads finish.
  `Hdf5Writer` is single-threaded.

---

## Not supported (reads throw `HdfUnsupportedException`)

The following are not supported:
- **Filtered fractal heaps.**
- **Filters other than the built-in six and the five third-party ones above.**
- **Multi-file drivers** (family, multi, split). `driverInfo()` reports them, and Falcon reads only the
  file it opened.
- **Following external links, and references into other files.**

On the write side, the bitfield/opaque/time datatype classes and indirect-block dense storage are not
yet emitted. See [`PLAN.md`](PLAN.md) for the full roadmap.
