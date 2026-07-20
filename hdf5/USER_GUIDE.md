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
int[]    a = ds.readInts();        // fixed-point up to 4 bytes
long[]   b = ds.readLongs();       // fixed-point up to 8 bytes
float[]  c = ds.readFloats();      // floating point
double[] d = ds.readDoubles();
String[] s = ds.readStrings();     // fixed- or variable-length strings
Object natural = ds.read();        // most natural Java array for the datatype
byte[]  raw    = ds.readRawBytes(); // undecoded element bytes

int    scalar = ds.readInt();      // single-element datasets: readInt/readLong/readDouble/readString
```

Multidimensional data is returned flattened row-major; `ds.dataspace().dimensions()` gives the shape and
`ds.datatype()` the element type.

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
and `fixedStringDataset` (fixed-length).

### Chunking and filters

```java
w.intChunkedDataset("big", data, new long[]{100_000}, new long[]{4096})
 .shuffle().deflate(6);                                     // filters apply in call order

// also: .fletcher32(), .scaleOffset(), .nbit(precision), .szip()
```

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

Groups with more than 8 links, and objects with more than 8 attributes, switch to dense storage
(fractal heap + version-2 B-tree) automatically.

### On-disk format

```java
Hdf5Writer.create(path, Hdf5Writer.Format.LATEST);    // modern: v3 superblock, v2 headers (default)
Hdf5Writer.create(path, Hdf5Writer.Format.EARLIEST);  // original: v0 superblock, symbol-table groups
```

---

## Error handling

Every failure Falcon raises is an unchecked `HdfException`:

- `HdfFormatException` — bytes on disk violate the spec (bad signature/checksum, out-of-range address,
  truncated or corrupt input). Corrupt input always fails this way — never a raw runtime exception, JVM
  crash, or infinite loop.
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

---

## Not supported (reads throw `HdfUnsupportedException`)

SOHM shared-message deduplication, the revised `H5R_ref_t` reference encoding, unlimited-pattern virtual
datasets, and multi-file drivers (family/multi/split). On the write side, the bitfield/opaque/time
datatype classes and indirect-block dense storage are not yet emitted. See
[`PLAN.md`](../PLAN.md) for the full roadmap.
