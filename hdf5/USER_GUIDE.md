# Falcon HDF5 — User Guide

Falcon's `hdf5` module is a pure-JDK 25, zero-dependency reader and writer for the
[HDF5 File Format v4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html) (HDF5 2.0).
Everything is in the exported package `com.ebremer.falcon.hdf5`.

```java
import com.ebremer.falcon.hdf5.*;
import java.nio.file.Path;
```

All read APIs are backed by a memory mapping (the Foreign Function & Memory API), so files stay on disk
and files larger than 2 GB are handled without the `MappedByteBuffer` size limit.

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
    root.child("maybe/missing").ifPresent(o -> { /* Optional lookup */ });
}
```

`Hdf5File` also exposes `superblockVersion()` and, for files that record it, `fileSpaceInfo()` (allocation
strategy, page size, and total free space).

### Read a dataset

```java
int[]    a = ds.readInts();        // integers, each of which must fit in an int
long[]   b = ds.readLongs();       // integers, each of which must fit in a long
float[]  c = ds.readFloats();      // floating point
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

**Non-native layouts.** Integers are read from their bit offset and precision (a 12-bit value packed in 16
bits, a 24-bit integer in 3 bytes). Floats are decoded from their sign, exponent, and mantissa fields as
libhdf5 decodes them, so bfloat16 and x87 80-bit extended precision read correctly. VAX-order floats are
not supported.

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
```

Virtual datasets are read transparently: `ds.readDoubles()` assembles the data from the source files
(resolved relative to the virtual dataset's own file), filling unmapped regions with the fill value.
A source in the other byte order is converted. A source of any other type, such as a `uint32` source
under an `int32` virtual dataset, throws `HdfUnsupportedException`; libhdf5 would convert it.

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
  fractal heaps, chunk indexes) and every `fletcher32` chunk is verified, loops and over-deep nesting in
  the file's structure are detected, and decompression is bounded by the chunk size.
- `HdfUnsupportedException` — a valid but not-yet-implemented structure.

Catch `HdfException` to handle any Falcon read/write failure.

---

## Performance notes

- **Memory-mapped, >2 GB.** Files are mapped through the FFM API with `long` offsets, so multi-gigabyte
  files are read without copying and without the 2 GB `MappedByteBuffer` cap.
- **Touch only what you read.** A hyperslab or `blocks()` read of a chunked dataset reads and de-filters
  only the chunks overlapping the selection; contiguous selections are extracted zero-copy from the map.
- **Decoded-chunk cache.** Repeated or streaming reads reuse the filter-decode result for a chunk
  (~16 MB LRU per file).
- **Concurrent reads.** An open `Hdf5File` and everything obtained from it may be read from many
  threads at once (e.g. `dataset.blocks(n).parallel()`); close it only after those reads finish.
  `Hdf5Writer` is single-threaded.

---

## Not supported (reads throw `HdfUnsupportedException`)

SOHM shared-message deduplication, the revised `H5R_ref_t` reference encoding, unlimited-pattern virtual
datasets, and multi-file drivers (family/multi/split). On the write side, the bitfield/opaque/time
datatype classes and indirect-block dense storage are not yet emitted. See
[`PLAN.md`](PLAN.md) for the full roadmap.
