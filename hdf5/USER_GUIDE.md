# Falcon HDF5 — User Guide

Falcon's `hdf5` module is a pure-JDK 25, zero-dependency reader and writer for the
[HDF5 File Format v4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html) (HDF5 2.0).
Everything is in the exported package `com.ebremer.falcon.hdf5`.

```java
import com.ebremer.falcon.hdf5.*;
import java.nio.file.Path;
```

A file opened from a path is memory-mapped (the Foreign Function & Memory API), so it stays on disk and
files larger than 2 GB are handled without the `MappedByteBuffer` size limit. A path on a file system
that cannot map its files (a zip or in-memory file system) is read on demand through a channel instead.
A file can also be opened from bytes in memory, or read on demand through a `RangeReader` (see *Other
sources*).

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
- **Metadata** is read in pages, which are cached: 64 KiB pages and 16 MiB per file by default. Larger
  pages suit a high-latency store, as fewer requests each fetch more.
- **Data** is read only where a read asks for it: a chunk or contiguous run of a page or more in one
  call, a smaller one through the pages. Reading a small selection of a large remote file fetches the
  metadata and the pages or chunks around that selection, not the file.

```java
Hdf5File.open(reader, OpenOptions.defaults()
        .readerPageSize(1 << 20)        // 1 MiB pages
        .readerCacheSize(256L << 20)    // keep up to 256 MiB of them
        .chunkCacheSize(512L << 20)     // and up to 512 MiB of decoded chunks
        .objectCacheSize(64L << 20));   // and up to 64 MiB of objects' metadata (see Performance notes)
```

A reader failure surfaces from the read that needed the bytes as `java.io.UncheckedIOException`, or as
the `IOException` of `open` itself while the file is being opened. Falcon does not close the reader:
close it after the `Hdf5File`. Such a file has no path (`path()` is `null`) and no directory of its
own, so by default it opens no other file. A resolver (`ExternalFileAccess.resolvedBy`) opens its
external raw data and virtual-dataset sources wherever they are, such as next to it in the same store;
`allowDirectory(...)` or `unrestricted()` let it open local ones (see *Files outside the HDF5 file*).

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
hard, soft, and external links:
- An object reached through a soft link takes the link's path (e.g. `/links/soft`).
- **External links are followed as libhdf5 follows them.** The link's file is looked for, and opened, as
  the `ExternalFileAccess` policy allows (see *Files outside the HDF5 file*): by default, only in the
  HDF5 file's own directory tree. It stays open until the HDF5 file closes. The object reached is named
  by its path in that file, as libhdf5 names it (`/y`, for a link to `other.h5:/y`), and paths continue
  across the link (`group("ext_group/sub")`).
- A link whose file the policy refuses fails `child`, `group` and `dataset` with
  `HdfUnsupportedException` naming the file, and is left out of `children()`.
- A soft or external link whose target or file is missing, or which loops (more than 16 soft and
  external links on one path, across files, as in libhdf5), reaches nothing.

### Files outside the HDF5 file

External raw data (an External File List), virtual-dataset sources, the files external links lead to,
and the files references point into are other files named inside the HDF5 file. An untrusted file could
otherwise point Falcon at any local file, or at a network share. So by default Falcon opens only files
in the HDF5 file's own directory tree; any other name fails the read with `HdfUnsupportedException`.
Choose a different policy when opening:

```java
Hdf5File.open(path);                                                         // = ExternalFileAccess.sameDirectory()
Hdf5File.open(path, ExternalFileAccess.sameDirectory().allowDirectory(raw)); // also files under raw/
Hdf5File.open(path, ExternalFileAccess.unrestricted());                      // any name, as libhdf5 (trusted files)
Hdf5File.open(path, ExternalFileAccess.none());                              // never open another file
Hdf5File.open(path, ExternalFileAccess.resolvedBy(resolver));                // your resolver decides
```

A **resolver** is given every name the file holds (and why: `RAW_DATA`, `VIRTUAL_SOURCE`, `EXTERNAL_LINK`,
or `REFERENCE`) and returns a `RangeReader` for it, `null` if there is no such file, or throws
`HdfUnsupportedException` to refuse it.
It is the policy for a file read through a `RangeReader`, whose other files are not local paths:

```java
ExternalFileAccess sources = ExternalFileAccess.resolvedBy((name, purpose) ->
        name.startsWith("/") || name.contains("..") ? null : store.reader(prefix + name));
Hdf5File.open(store.reader(prefix + "data.h5"), OpenOptions.defaults().externalFileAccess(sources));
```

Names in the files a resolver opens (a virtual source's own sources, the links in a linked file) come to
it too. A reader it returns that is `AutoCloseable` is closed once Falcon is done with it: after a read
of external raw data, and for another HDF5 file when the file closes. A file it does not find, or cannot
open (`IOException`), is missing: a virtual source reads as the fill value, an external link reaches
nothing, and external raw data or a reference fails the read.

A name is read as a path of the HDF5 file's own file system, so the files next to a file inside a zip
file system are found in it.

Another HDF5 file (a virtual-dataset source, an external link's file, a reference's file) is looked for
where libhdf5 looks for a virtual source or an external link's file, among the places the policy
allows:
- **An absolute name** is tried as written. If that fails, its file name alone is tried, as libhdf5 does
  for a file moved together with the files it names.
- **A relative name** is tried in the HDF5 file's directory.
- **Then, for both,** each allowed directory, and under `unrestricted()` the working directory.

A file found nowhere is missing: a virtual source reads as the fill value, and an external link reaches
nothing. There is one exception: when the policy refuses the name's own location and no allowed
candidate exists, the read fails, because the refused file may be the real one. (libhdf5 opens a
reference's file by the name as written, from the working directory; Falcon looks in the HDF5 file's
directory first.)

### Read a dataset

```java
int[]    a = ds.readInts();        // integers, each of which must fit in an int
long[]   b = ds.readLongs();       // integers, each of which must fit in a long
float[]  c = ds.readFloats();      // floating point, or integers converted
double[] d = ds.readDoubles();
double[] z = ds.readComplexDoubles(); // complex numbers as (real, imaginary) pairs
String[] s = ds.readStrings();     // fixed- or variable-length strings, or enumeration names
Object natural = ds.read();        // most natural Java value for the datatype
byte[]  raw    = ds.readRawBytes(); // element bytes as stored (after the filters are undone)

int    scalar = ds.readInt();      // single-element datasets: readInt/readLong/readDouble/readString
double[] col = ds.member("temperature").readDoubles(); // one member of a compound dataset
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

### Selections and streaming

Read part of a dataset without materializing the whole of it: only the chunks that hold selected
elements are read and de-filtered, and only the selected runs of contiguous data.

```java
double[] slab = ds.select(new long[]{100, 0}, new long[]{50, 200}).readDoubles(); // rows 100..149

// A regular hyperslab, as H5Sselect_hyperslab: start, stride, count, block (null stride or block = 1).
float[] everyOther = ds.select(new long[]{0, 0}, new long[]{2, 2}, new long[]{500, 100}, null).readFloats();

// Points, as H5Sselect_elements, read in the order given.
double[] three = ds.selectPoints(new long[][]{{0, 0}, {512, 7}, {9000, 3}}).readDoubles();

// Process a large dataset block-by-block along the first dimension:
ds.blocks(10_000).forEach(block -> process(block.readDoubles()));
```

A selection has every reader a dataset has: numbers, strings, variable-length sequences
(`readVlenInts()` and the rest), references, `readRawBytes()`, and `read()`. A block, or a regular
hyperslab, reads flattened row-major in its own shape (`shape()`: `count[d] * block[d]` in each
dimension); points read as a flat array. `member(name)` narrows a selection of a compound dataset to one
member.

Decoded (filtered) chunks are cached per file, so streaming reads that revisit a boundary chunk reuse the
decode.

### Attributes

```java
for (Attribute attr : ds.attributes()) { Object v = attr.read(); }

ds.attribute("units").ifPresent(a -> System.out.println(a.readString()));
int scale = ds.attribute("scale").orElseThrow().readInt();
```

An attribute has the readers a dataset has, and `member(name)` for a compound attribute.

### References and virtual datasets

```java
Hdf5Object[] targets = ds.readObjectReferences();      // object references -> the objects they name
Selection[]  regions = ds.readRegionReferences();      // region references -> dataset selections
double[] slice = regions[0].readDoubles();
Attribute[]  attrs   = ds.readAttributeReferences();   // revised attribute references -> attributes
```

Attributes and selections holding references have the same three methods.

An object reached through a reference has the path libhdf5's `H5Iget_name` gives it: found when first
asked for, by walking the file's hard links depth first, each group's in its native order (name order in
an old-style group, the order the links were made in a compact one, name-hash order in a dense one), so
an object with several paths is named as libhdf5 names it. The walk reads every group up to the object,
so for a large file, ask for `path()` only when it is needed; `objectHeaderAddress()` identifies the
object without it. An object no path reaches has the path `""`.

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
- A reference into another file is followed into it: the file is looked for and opened as the
  `ExternalFileAccess` policy allows, and stays open until the HDF5 file closes. If the policy refuses
  it, or it is not found, `readObjectReferences()` and `readAttributeReferences()` fail
  (`HdfUnsupportedException` or `HdfException`), and `readRegionReferences()` gives that element a
  selection that throws when used.

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

The reader decodes every HDF5 datatype class (`ds.datatype()` describes it), and reads the data of every
class:

| Datatype | Readers | `read()` returns |
|---|---|---|
| integer | `readInts`, `readLongs`, `readFloats`, `readDoubles` | `int[]`, `long[]` or `BigInteger[]` (see above) |
| float | `readFloats`, `readDoubles` | `double[]` |
| string, variable-length string | `readStrings` | `String[]` |
| enumeration | `readStrings` (member names; `null` for a value no member has), and the integer readers | `String[]` of names |
| compound | `member(name)`, which reads like a dataset of the member's type | `Map<String, Object>` of each member's values, in member order |
| array | the readers of its base type, every element's values in turn | its base type's, flattened |
| complex (HDF5 2.0) | `readComplexDoubles`, `readComplexFloats`; `readDoubles` and `readFloats` give the real part, as libhdf5 converts | `double[]` of (real, imaginary) pairs |
| bit field | the integer readers, as unsigned integers (as h5py reads them) | as for an unsigned integer |
| opaque | `readRawBytes` | `byte[][]`, one array per element |
| time | the integer readers: signed seconds since 1970 (HDF5's time types are Unix `time_t`) | `java.time.Instant[]` |
| reference | `readObjectReferences`, `readRegionReferences`, `readAttributeReferences` | `Hdf5Object[]` or `Selection[]` |
| variable-length sequence | `readVlenInts`, `readVlenLongs`, `readVlenFloats`, `readVlenDoubles` | `int[][]`, `long[][]`, `double[][]`, or `Object[]` of rows |

h5py writes complex numbers as a compound of two floats named `r` and `i`; `readComplexDoubles()` reads
that too, and a real number as a complex one with no imaginary part, as libhdf5 converts it.

```java
Dataset table = h5.root().dataset("table");   // compound {id int32, pos {x int16, y float32}, color enum}
int[]    ids    = table.member("id").readInts();
int[]    x      = table.member("pos").member("x").readInts();
String[] colors = table.member("color").readStrings();
Map<String, Object> columns = (Map<String, Object>) table.read();
```

---

## Writing

```java
try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
    // ... build the file ...
}   // completed on close()
```

The file is written to a temporary file beside the target, which `close()` moves into place, so the
path holds either the complete new file or what it held before. Raw data goes to the temporary file as
it is written, at 64-bit offsets, so a file may be far larger than memory or 2 GB; `close()` adds the
metadata (object headers, chunk indexes, groups) after it. To change a file that exists, see
[Changing an existing file](#changing-an-existing-file).

A file may start with a user block, as MATLAB v7.3 files do: bytes of the application's own, which HDF5
readers skip (libhdf5's `H5Pset_userblock`). Give them to `create`; the block is zero-padded to 512, 1024,
2048, ... bytes, and the HDF5 data follows:

```java
byte[] header = Arrays.copyOf("MATLAB 7.3 MAT-file ...".getBytes(StandardCharsets.US_ASCII), 128);
try (Hdf5Writer w = Hdf5Writer.create(path, Hdf5Writer.Format.LATEST, header)) { ... }
```

- **When `close()` fails,** for example on a reference to an object never added, the target is not
  touched and the writer stays open, the data written so far kept in the temporary file: fix the cause
  and call `close()` again.
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
  throws `HdfClosedException`. `abort()` deletes the temporary file.
- **An I/O error while data is written** (a full disk) surfaces as `java.io.UncheckedIOException` from
  the call that wrote it; `close()` throws the `IOException` itself.

### Any datatype, written as it comes

`createDataset(name, datatype, shape...)` makes a dataset of any `Datatype`. Configure it, then write
its data in pieces, in any order:

```java
DatasetWriter frames = w.createDataset("frames", Datatype.uint16(), 0, 512, 512)
        .chunked(1, 512, 512)                               // a chunk per frame
        .maxShape(Hdf5Writer.UNLIMITED, 512, 512)           // it grows along the first dimension
        .deflate(4);
for (short[] frame : camera) {
    frames.append(frame);                                   // grows by one frame, and writes it
}

DatasetWriter grid = w.createDataset("grid", Datatype.float32(), 1000, 1000).chunked(100, 1000);
grid.write(new long[]{0, 0}, new long[]{100, 1000}, rows);  // a box: offset, count, values
grid.write(everything);                                     // or every element at once
```

- **Datatypes.** `Datatype` has factories for the common types: `int8()` to `int64()`, `uint8()` to
  `uint64()`, `float16()`, `float32()`, `float64()`, `bool()` (h5py's), `string(size)`,
  `variableString()`, `sequenceOf(base)`, `arrayOf(base, dims...)`, `complexOf(base)`,
  `compound(members)`, `opaque(size, tag)`, `bitField(size)`, `unixTime(size)`, `objectReference()`
  and `regionReference()`. `withByteOrder(ByteOrder.BIG_ENDIAN)` gives an integer, float, bit field or
  time type in the other byte order, and any `Datatype` record (such as an enumeration's) can be built
  directly.
- **Values** are converted to the datatype exactly, and refused if they do not fit: integers in range,
  whole numbers for an integer type, strings that fit a fixed-length type, enumeration names that are
  members. A number written to a floating-point type is rounded to the nearest, as libhdf5 converts it.
  `write` takes the Java values `read()` gives back:

  | Datatype | Values |
  |---|---|
  | integer, bit field | `byte[]`, `short[]`, `int[]`, `long[]`, `BigInteger[]`, or whole `double[]` values |
  | float | any numeric array |
  | enumeration | member names (`String[]`) or values; `boolean[]` for `bool()` |
  | time | `Instant[]`, or seconds |
  | string | `String[]` |
  | complex | `double[]` or `float[]` (real, imaginary) pairs |
  | compound | a `Map` of each member's values |
  | array | the base type's values, flattened |
  | opaque | `byte[][]`, one array per element |
  | sequence | rows: `int[][]`, `double[][]`, ..., or `Object[]` |
  | object reference | absolute paths (`String[]`, `null` for none) |
  | region reference | `Hdf5Writer.Region[]` |

  `writeRaw(offset, count, bytes)` writes elements' bytes as stored, for types without variable-length
  data or references.
- **Where the data goes.** Contiguous data goes to its place in the file at once. A chunk goes to the
  file, filtered, as soon as all of its elements are written; until then it is kept in memory, so write
  whole chunks, or whole rows of chunks, to keep memory small. Writing elements again replaces them (a
  chunk already in the file is read back and written anew). Elements never written read as the fill
  value.
- **Growing.** A dataset with a `maxShape` larger than its shape (`Hdf5Writer.UNLIMITED` for no limit)
  must be chunked; `extend(shape...)` grows it, and `append(values)` grows the first dimension by the
  rows the values fill and writes them. Such a dataset's chunks are indexed by a version-1 B-tree, which
  every HDF5 version reads.
- **Configure first.** The chunk shape, maximum shape, filters, fill value and compact layout must be
  set before the first write.
- **Memory.** Besides chunks partly written:
  - the data of the last dataset given its data whole (below), until the next dataset or group is added;
  - the current global-heap collection of variable-length data (at most about 1 MiB; full ones go to the
    file);
  - each chunked dataset's index entries;
  - the chunks of a chunked dataset of object references, and attributes holding them, whose targets'
    addresses are known only at the end.

### Groups, datasets, and attributes

```java
w.intDataset("counts", new int[]{10, 20, 30}, new long[]{3})
 .intAttribute("scale", new int[]{100}, new long[]{});     // scalar attribute (empty shape)

Hdf5Writer.GroupWriter run = w.group("run");                // nested groups
run.doubleDataset("signal", new double[]{0.5, 1.5}, new long[]{2});
run.group("nested").intDataset("inner", new int[]{7}, new long[]{1});
```

The per-type methods take the whole data at once, and their configuration (filters, layout, fill)
follows: `byteDataset` (int8), `shortDataset` (int16), `intDataset` (int32), `longDataset` (int64),
`floatDataset` (float32), `doubleDataset` (float64), `stringDataset` (variable-length UTF-8),
`fixedStringDataset` (fixed-length, UTF-8), and those below. Their data is written when the next dataset
or group is added (or on `close()`), so only one such dataset's data is held. Configure each before
adding the next; afterwards its configuration methods throw `IllegalStateException` (attributes may
still be added).

Attributes of any datatype, and string attributes:

```java
run.stringAttribute("units", "m/s");                        // a fixed-length UTF-8 string
run.attribute("valid_range", Datatype.uint8(), new long[]{2}, new int[]{0, 250});
run.attribute("tags", Datatype.variableString(), new long[]{2}, new String[]{"raw", "v2"});
```

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

// also: .fletcher32(), .scaleOffset(), .nbit(precision), .szip(), .szip(SzipCoding.NEAREST_NEIGHBOUR, 16)
```

Filters form a pipeline applied to each chunk in the order they are added, exactly as libhdf5 does, and
each may be added once. `scaleOffset()` and `nbit(precision)` work on integer data and must come first.
`nbit(precision)` stores unsigned `precision`-bit values, so a negative or too-wide value is rejected.
`szip(coding, pixelsPerBlock)` works on integer or floating-point data and may only follow `shuffle()`:
entropy coding alone (`SzipCoding.ENTROPY`, h5py's `"ec"`) or after nearest-neighbour preprocessing
(`NEAREST_NEIGHBOUR`, `"nn"`, which suits smooth data), with an even block of 2 to 32 elements; `szip()`
is entropy coding with blocks of 8. Every filter writes the on-disk form libhdf5 reads, and the chunk
libhdf5 itself would write: scale-offset and n-bit chunks are libhdf5's byte for byte, and szip chunks
libaec's (Falcon ports libaec's encoder: zero-block runs, the second extension, its choice of each block's
coding). A chunk szip cannot shrink is stored unfiltered, as libhdf5 does.

### Links and references

```java
w.softLink("latest", "/run/frames");                        // a path in this file (need not exist)
w.group("other").externalLink("calibration", "cal.h5", "/gain"); // an object in another file
w.hardLink("frames", "/run/frames");                        // another name for an object (it must exist)
w.move("old_name", "/archive/new_name");                    // a link moved (or renamed: a name in its group)
w.regionReferenceDataset("roi", new long[]{2}, new Hdf5Writer.Region[]{
    Hdf5Writer.Region.block("/image", new long[]{10, 20}, new long[]{64, 64}),
    Hdf5Writer.Region.points("/image", new long[][]{{0, 0}, {5, 7}})});
```

A region is all of a dataset, a block, a regular hyperslab, or points. References' targets are named by
path and may be added before or after the reference; they are resolved on `close()`. References may be
written anywhere a value goes: contiguous, chunked (and filtered) and compact datasets, compound members,
and attributes (`attribute(name, Datatype.objectReference(), shape, paths)`). A chunked dataset of object
references keeps its chunks until `close()`.

- **Hard links** (`hardLink(name, targetPath)`): another name for an object added or of the file, by an
  absolute path through hard links, which must exist when the link is made (as libhdf5's
  `H5Lcreate_hard` requires). The object's hard-link count counts its names; deleting one name leaves the
  object to the others. Hard links may form cycles (a group reaching an ancestor).
- **Moves and renames** (`move(name, newPath)`, libhdf5's `H5Lmove`): the link `name` (or a link at a
  path) moves to `newPath`, absolute or relative to the group; a group takes what it holds with it. Paths
  are resolved as the session leaves the file: a reference names an object's place after the moves.

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
(fractal heap + version-2 B-tree) automatically, of any size: laid out as libhdf5 lays it out, with
indirect heap blocks and B-tree levels as needed (a group of 100,000 links is checked against libhdf5).

### On-disk format

```java
Hdf5Writer.create(path, Hdf5Writer.Format.LATEST);    // modern: v3 superblock, v2 headers (default)
Hdf5Writer.create(path, Hdf5Writer.Format.EARLIEST);  // original: v0 superblock, symbol-table groups
```

In `EARLIEST`:
- every attribute goes in the version-1 object header (there is no dense storage);
- chunked datasets are indexed by version-1 B-trees, and their filter pipeline is message version 1;
- groups of any size are indexed by version-1 B-trees of as many levels as they need;
- complex numbers are refused when added;
- a group with an external link, which a symbol table cannot hold, is written in the new format (link
  messages in its version-1 header), as libhdf5 converts such a group; HDF5 1.8 and later read it.

`EARLIEST` uses the message versions libhdf5 writes for its own earliest setting:
- dataspace v1;
- compound and enum datatypes v1, array datatype v2;
- fill value v2;
- attribute v1.

Files Falcon writes are readable by HDF5 1.10 and later (native complex datasets, a type introduced by
HDF5 2.0, need HDF5 2.0). `tools/fixtures/check_hdf5_writer.py` verifies this against h5py's HDF5 2.0
and, optionally, an HDF5 1.14 build; each library then changes every file and reads it back.

### Changing an existing file

`Hdf5Writer.open(path)` opens a file to change it in place: one Falcon wrote or one libhdf5 wrote, in
either format.

```java
try (Hdf5Writer w = Hdf5Writer.open(Path.of("data.h5"))) {
    Hdf5Writer.GroupWriter run = w.group("run");             // a group of the file: opened
    run.stringAttribute("status", "reviewed");               // added, or replacing the file's
    run.deleteAttribute("draft");
    run.delete("scratch");                                   // a link of the file, deleted
    run.dataset("frames").append(lastFrame);                 // a dataset of the file, written into
    run.createDataset("notes", Datatype.variableString(), 2).write(new String[]{"a", "b"});
}
```

- **What changes:**
  - Everything a new file can hold can be added anywhere in the file.
  - `group(name)` opens a group the file holds, and `dataset(name)` a dataset. Each follows a hard link
    only: open a soft or external link's target where it is. An object reached by several hard links is
    opened once, however it is reached.
  - A dataset of the file keeps its datatype, chunks, filters, and fill value, and grows only within
    its maximum shape. Its chunks are written through its filters with the parameters libhdf5 stored for
    them, as libhdf5 would encode them: deflate, shuffle, fletcher32, szip (either coding), n-bit (any
    type), and scale-offset (integers, and decimal-scaled floats); partial edge chunks it keeps
    unfiltered stay so (in a dataset that can grow, whose index Falcon writes as a version-1 B-tree, every
    chunk is filtered). Data in external raw files is written into those files.
  - Setting an attribute an object already has replaces it.
  - `delete(name)` deletes a link. The object it led to stays in the file, unreachable unless another
    link leads to it (its hard-link count is lowered).
  - `hardLink` and `move` work on the file's links as on new ones. An external link added to (or moved
    into) a group of the original format converts that group to the new format, as libhdf5 does.
  - A virtual dataset of the file is written into its sources, as libhdf5's `H5Dwrite` writes one: each
    element into the source element its mapping pairs it with (in the source's byte order), through every
    kind of mapping (regular, strided, unlimited, printf-style). A source in the same file is written in
    the session; one in another file, in a session of its own (`Hdf5Writer.open` of that file), completed
    when this one closes and aborted with it. As libhdf5 does, a write that includes an element no mapping
    covers, or whose source is missing, or that two mappings cover, is refused (`IllegalArgumentException`)
    before anything is written. Its extent is its sources': `extend` and `append` are refused.
  - Attributes kept in the file's shared-message table (SOHM) change like others: one deleted or
    replaced is released there (its count lowered, as libhdf5 lowers it, and dropped from the index at
    0); a dataset that grows gets its own dataspace message, releasing the shared one. Their copies stay
    in the shared-message heap, as unused space.
  - A group or object whose links or attributes drop below its minimum for dense storage (6) goes back to
    compact messages, as libhdf5 moves them.
- **In place:**
  - New data and metadata go after the file's end. `close()` writes them, with a journal of every write it
    will make over the file's own structures (the superblock, changed object headers and indexes), and
    flushes it all to disk; then it makes those writes, and cuts the journal off.
  - A change interrupted while those writes are made (a crash, a full disk) is redone by retrying
    `close()`, or by the next `Hdf5Writer.open` of the file. Meanwhile a version-3 superblock is marked as
    open by a writer, as libhdf5 marks it, so libhdf5 refuses the file until the change is whole.
  - Space freed by a deletion or a replaced attribute is not reused, as libhdf5 does not reuse it between
    sessions: a session never writes over what the file holds before `close()`, so that `abort()` and a
    crash before then leave the file as it was. `h5repack` reclaims it.
  - Data written into a contiguous dataset of the file (or its external raw files) goes there at once.
    `abort()` undoes everything else: it cuts what was added after the file's end.
  - Do not open the file elsewhere while it is changed.
- **New objects** take the file's format: the earliest one if its superblock is version 0–1. Groups and
  attributes keep their storage style (original or new, compact or dense, creation order tracked).
- **Refused** (`HdfUnsupportedException`):
  - files with 4-byte addresses, of a non-default driver (family, multi), that track their free space
    persistently or in pages, or that are marked as open by a writer (with no journal of Falcon's to redo);
  - writing into datasets filtered by a third-party filter (see S8 in `TODO.md`);
  - writing through a virtual dataset of variable-length or reference data (as reading one is), into a
    source of another type (other than the other byte order), or into a source file read through a
    resolver;
  - external raw data files the access policy refuses (by default, outside the HDF5 file's directory).

---

## Error handling

Every failure Falcon raises is an unchecked `HdfException`:

- `HdfFormatException` — bytes on disk violate the spec (bad signature/checksum, out-of-range address,
  truncated or corrupt input). Corrupt input fails this way rather than as a raw runtime exception, a
  JVM crash, or an infinite loop: every checksummed metadata structure (object headers, B-trees,
  fractal heaps, chunk indexes), every `fletcher32` chunk, and every zstd frame's checksum is verified,
  loops and over-deep nesting in the file's structure are detected, and decompression is bounded by the
  chunk size.

  Bytes that no checksum covers cannot be checked: raw data without `fletcher32`, the global heap
  (variable-length data), the direct blocks of a fractal heap that does not checksum them, and the
  earliest format's metadata (version-0 and 1 superblocks, version-1 object headers and B-trees,
  symbol-table nodes, local heaps). Corrupted, they read as whatever they now hold, unless that breaks
  the file's structure.
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
- **Chunks looked up, then the index kept.** A small read of a chunked dataset looks its chunks up in the
  file's chunk index, as libhdf5 does: an array index's entry, or a B-tree's path down to the chunk. So
  the first small read of a dataset of millions of chunks, or of a remote one, reads a few entries or
  nodes, not the whole index. A read that covers an eighth of the chunk grid or more reads the whole index
  instead (as does any read once the lookups made add up to the grid's cells), which is then kept: every
  later read, `blocks()` among them, finds its chunks there by coordinate.
- **Handles share what they read.** What an object has read of itself (its header, attributes, and links;
  a dataset's datatype, shape, layout, chunk index, and virtual mappings) is kept per file, by object, for
  every handle of it, however it was reached: `group.dataset("x")` asked again, or through another path
  or a reference, reads none of it again. The cache keeps up to 16 MiB of it per file (as estimated: a few
  hundred bytes an object, plus some 32 bytes a chunk for a chunk index read whole), least recently used
  first out, set by `OpenOptions.objectCacheSize(bytes)`; 0 makes each handle keep its own. A handle keeps
  what it has read either way.
- **Virtual datasets read lazily.** A selection skips mappings that do not reach it, and reads from each
  source only the elements it maps to: the box that bounds them when they fill a quarter of it or more,
  else the elements themselves, so a strided source, a source of another shape, or a strided or point
  selection of the virtual dataset reads only the chunks that hold what it needs. Source files are opened
  once and stay open until the virtual dataset's file is closed.
- **Lookups by name read the index.** `attribute(name)`, `link(name)`, and path lookups search a large
  group's or object's name index (as libhdf5 does) instead of reading every link or attribute.
- **Selections read their chunks.** A strided selection reads only the chunks in the grid cells its
  indices fall in, so one every 100th row of a dataset chunked by rows reads one chunk in 100; points
  read the chunks they fall in, each once. Elements come out of each chunk a run at a time: a block along
  the last dimension in one copy, so every other row of a chunked dataset reads in less time than the
  whole of it.
- **Decoded-chunk cache.** Repeated or streaming reads reuse the filter-decode result for a chunk: 16 MiB
  per file, least recently used first out, set by `OpenOptions.chunkCacheSize(bytes)` (0 turns it off).
  A file read through a `RangeReader` also caches its pages, set by `readerPageSize` and
  `readerCacheSize`.
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

On the write side, the third-party filters are not written yet, and changing a file refuses what
*Changing an existing file* lists. See [`TODO.md`](TODO.md).
