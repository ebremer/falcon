---
title: HDF5 how-to
description: Recipes for reading and writing HDF5 files with Falcon's hdf5 module.
---

The `hdf5` module (`com.ebremer.falcon.hdf5`) reads and writes HDF5 files: every superblock, object-header,
and group form, every chunk index, the six built-in filters and nine common third-party ones, references,
virtual datasets, and external links. Everything below is in that one package:

```java
import com.ebremer.falcon.hdf5.*;
import java.nio.file.Path;
```

The [full HDF5 user guide](https://github.com/ebremer/falcon/blob/main/hdf5/USER_GUIDE.md) covers every
option; this page is the common tasks.

## Reading

### Open a file and list what it holds

```java
try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
    Group root = h5.root();
    for (String name : root.childNames()) {
        System.out.println(name);
    }
    for (Hdf5Object child : root.children()) {        // Group, Dataset, or CommittedDatatype
        System.out.println(child.path() + " " + child.getClass().getSimpleName());
    }
}
```

A file opened from a path is memory-mapped, so it stays on disk however large it is. Close it when you are
done (`try`-with-resources does); anything read from it afterwards throws `HdfClosedException`.

### Find a group or dataset

Lookups take paths, as libhdf5's do: names separated by `/`, relative to the group, or from the root when
they start with `/`.

```java
Group run = root.group("run");
Dataset temperature = run.dataset("temperature");
Dataset same = root.dataset("/run/temperature");
root.child("maybe").ifPresent(object -> System.out.println("found " + object.path()));
```

### Read a whole dataset

```java
Dataset ds = h5.root().dataset("run/temperature");
long[] shape = ds.dataspace().dimensions();   // the shape: values below are row-major
double[] values = ds.readDoubles();           // any integer or float type, converted
float[] floats = ds.readFloats();
int[] ints = ds.readInts();                   // integers that fit an int (a uint32 does not)
long[] longs = ds.readLongs();
String[] text = ds.readStrings();             // strings, or an enumeration's names
Object natural = ds.read();                   // the most natural Java array for the type
double scalar = h5.root().dataset("scale").readDouble();   // a one-element dataset
```

Integer reads are exact: a value that does not fit the Java type you ask for throws instead of wrapping.
`read()` picks a type every value fits (`int[]`, `long[]`, or `BigInteger[]` for `uint64`).

### Read part of a dataset

Only the chunks holding the selected elements are read and decompressed.

```java
// a hyperslab: rows 100 to 149, columns 0 to 199
double[] slab = ds.select(new long[] {100, 0}, new long[] {50, 200}).readDoubles();

// strided, as H5Sselect_hyperslab: start, stride, count, block (null for 1)
float[] everyOther = ds.select(new long[] {0, 0}, new long[] {2, 2}, new long[] {500, 100}, null).readFloats();

// points, in the order given
double[] three = ds.selectPoints(new long[][] { {0, 0}, {512, 7}, {9000, 3} }).readDoubles();
```

### Process a dataset too large for memory

```java
ds.blocks(10_000).forEach(block -> {      // 10,000 rows at a time, along the first dimension
    double[] rows = block.readDoubles();
    long[] at = block.offset();
    // ...
});
```

An open file may be read from several threads at once, so `ds.blocks(n).parallel()` works too.

### Read attributes

```java
ds.attribute("units").ifPresent(units -> System.out.println(units.readString()));
int scale = ds.attribute("scale").orElseThrow().readInt();
for (Attribute attribute : ds.attributes()) {
    System.out.println(attribute.name() + " = " + attribute.read());
}
```

An attribute has the same readers a dataset has.

### Read compound, enumeration, and complex data

```java
Dataset table = h5.root().dataset("table");          // compound {id int32, pos {x int16, y float32}, color enum}
int[] ids = table.member("id").readInts();
int[] x = table.member("pos").member("x").readInts();
String[] colors = table.member("color").readStrings(); // enumeration names

double[] pairs = h5.root().dataset("signal").readComplexDoubles();   // real, imaginary, real, ...
```

h5py's complex numbers (a compound of `r` and `i`) read with `readComplexDoubles()` too.

### Learn how a dataset is stored

```java
Dataset.Layout layout = ds.layout();            // COMPACT, CONTIGUOUS, CHUNKED, or VIRTUAL
Optional<long[]> chunks = ds.chunkShape();
List<Filter> filters = ds.filters();            // in the order they are applied
long bytes = ds.storageSize();                  // bytes in the file, compressed
Datatype type = ds.datatype();
```

### Open a file from memory, a channel, or anywhere else

```java
Hdf5File fromBytes = Hdf5File.open(bytes);                          // a byte[] in memory
Hdf5File fromChannel = Hdf5File.open(RangeReader.of(channel));      // any SeekableByteChannel
Hdf5File fromAnywhere = Hdf5File.open(myRangeReader);               // your own source
```

A `RangeReader` has two methods, `size()` and `read(position, buffer)`. Falcon reads through it on
demand: metadata in cached pages, data a chunk at a time. That is how files in S3 or behind an HTTP server
are read: see [S3 and HTTP](cloud.md).

### Follow external links, virtual datasets, and external data

An HDF5 file can name other files. By default Falcon opens only files in the HDF5 file's own directory tree,
so an untrusted file cannot make it read anything else. Choose a policy when you open:

```java
Hdf5File.open(path, ExternalFileAccess.sameDirectory().allowDirectory(Path.of("/data/raw")));
Hdf5File.open(path, ExternalFileAccess.unrestricted());   // any name, as libhdf5 (trusted files only)
Hdf5File.open(path, ExternalFileAccess.none());           // never open another file
```

Virtual datasets then read transparently, assembled from their sources.

## Writing

### Create a file

```java
try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
    w.intDataset("counts", new int[] {10, 20, 30}, new long[] {3})
     .intAttribute("scale", new int[] {100}, new long[] {});     // a scalar attribute

    Hdf5Writer.GroupWriter run = w.group("run");
    run.stringAttribute("units", "m/s");
    run.doubleDataset("signal", new double[] {0.5, 1.5, 2.5}, new long[] {3});
}   // the file is complete when the writer closes
```

The file is written beside the target and moved into place on `close()`, so the path holds either the
complete new file or what it held before. If your own code fails part way, call `w.abort()` to discard
everything instead.

### Write a large dataset in pieces

`createDataset` makes a dataset of any datatype, which you then write a piece at a time, so it never has to
fit in memory:

```java
try (Hdf5Writer w = Hdf5Writer.create(Path.of("frames.h5"))) {
    Hdf5Writer.DatasetWriter frames = w.createDataset("frames", Datatype.uint16(), 0, 512, 512)
            .chunked(1, 512, 512)                           // a chunk per frame
            .maxShape(Hdf5Writer.UNLIMITED, 512, 512)       // it can grow along the first dimension
            .deflate(4);
    for (short[] frame : camera) {
        frames.append(frame);                               // grows by a frame, and writes it now
    }

    Hdf5Writer.DatasetWriter grid = w.createDataset("grid", Datatype.float32(), 1000, 1000).chunked(100, 1000);
    grid.write(new long[] {0, 0}, new long[] {100, 1000}, rows);   // a box: offset, count, values
}
```

Configure the chunks, maximum shape, filters, and fill value before the first write. A chunk goes to the file
as soon as all its elements are written, so write whole chunks (or rows of chunks) to keep memory small.

`Datatype` has factories for the common types: `int8()` to `int64()`, `uint8()` to `uint64()`, `float16()`,
`float32()`, `float64()`, `bool()`, `string(size)`, `variableString()`, `complexOf(base)`, `arrayOf(base,
dims...)`, `sequenceOf(base)`, `compound(members)`, and more.

### Compress

Filters apply in the order you add them:

```java
w.intChunkedDataset("big", data, new long[] {100_000}, new long[] {4096}).shuffle().deflate(6);
```

| Built in (every HDF5 reader has them) | Third party (as h5py and hdf5plugin write them) |
|---|---|
| `deflate(level)`, `shuffle()`, `fletcher32()`, `szip()`, `nbit(precision)`, `scaleOffset()` | `lzf()`, `blosc(...)`, `lz4()`, `bitshuffle(...)`, `zstd(level)`, `bzip2()`, `blosc2(...)`, `zfpRate(...)` and the other ZFP modes, `sz()` and the other SZ bounds |

Use the built-in filters when every HDF5 reader must open the file; the third-party ones need hdf5plugin (or
h5py, for LZF) on the reading side.

### Links and references

```java
w.softLink("latest", "/run/frames");                                 // a path, which need not exist yet
w.group("other").externalLink("calibration", "cal.h5", "/gain");     // an object in another file
w.hardLink("frames", "/run/frames");                                 // another name for an object
w.referenceDataset("refs", new long[] {2}, new String[] {"/counts", "/run"});
```

### Change an existing file

`Hdf5Writer.open` changes a file in place, one Falcon wrote or one libhdf5 wrote:

```java
try (Hdf5Writer w = Hdf5Writer.open(Path.of("data.h5"))) {
    Hdf5Writer.GroupWriter run = w.group("run");                // opens a group of the file
    run.stringAttribute("status", "reviewed");                  // added, or replacing the old one
    run.deleteAttribute("draft");
    run.delete("scratch");                                      // deletes a link
    run.dataset("frames").append(lastFrame);                    // writes into a dataset of the file
}
```

The change is journaled: if it is interrupted, retrying `close()`, or the next `Hdf5Writer.open` of the
file, completes it. Do not open the file elsewhere while it is being changed.

### Choose the file format

```java
Hdf5Writer.create(path, Hdf5Writer.Format.LATEST);    // the default: HDF5 1.10 and later read it
Hdf5Writer.create(path, Hdf5Writer.Format.EARLIEST);  // the original format, for the oldest readers
```

## Errors

Every failure is an unchecked `HdfException`:

| Exception | Means |
|---|---|
| `HdfFormatException` | The bytes break the format: a bad checksum, a truncated file, an impossible address. |
| `HdfUnsupportedException` | A valid file using something Falcon does not implement, or another file the access policy refuses. |
| `HdfClosedException` | The file or writer was closed. |

A file read through a `RangeReader` may also fail to be read at all: `java.io.UncheckedIOException`.

## Tips

- **Read selections, not whole datasets,** when you need part of a large one: only the chunks you touch are
  read.
- **Tune the caches** with `OpenOptions` (`chunkCacheSize`, `readerPageSize`, `readerCacheSize`) for large or
  remote files.
- **Write whole chunks** at a time to keep a writer's memory small.
- See the [full guide](https://github.com/ebremer/falcon/blob/main/hdf5/USER_GUIDE.md) for virtual datasets,
  region references, ZFP, SZ, Blosc2, and the details of changing files in place.
