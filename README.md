# Falcon

**Falcon** is a multi-module, **pure-JDK 25, zero-runtime-dependency** toolkit for scientific-data
formats — no native libraries, no third-party dependencies.

| Module | Package | What it is | Status |
|---|---|---|---|
| [`hdf5`](hdf5) | `com.ebremer.falcon.hdf5` | HDF5 reader/writer implementing the [HDF5 File Format Specification v4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html) (HDF5 2.0) | Read-complete, write-broad — 1.0-ready |
| [`zarr`](zarr) | `com.ebremer.falcon.zarr` | [Zarr](https://zarr.dev/) reader/writer (v3 core; v2 read) | Built |
| [`core`](core) | `com.ebremer.falcon.core` | Pure-Java compression codecs both formats share (zstd, Blosc, LZ4, LZF, bitshuffle); exported only to Falcon's modules | Built |

See **[PLAN.md](PLAN.md)** for the umbrella roadmap and **[CLAUDE.md](CLAUDE.md)** for conventions. Each
module has its own plan, remaining-work list, and user guide:
[hdf5](hdf5/PLAN.md) ([TODO](hdf5/TODO.md), [guide](hdf5/USER_GUIDE.md)) ·
[zarr](zarr/PLAN.md) ([TODO](zarr/TODO.md), [guide](zarr/USER_GUIDE.md)).

## Requirements

- JDK 25+
- Apache Maven 3.9+

## Build

```bash
mvn verify              # whole reactor (parent + all modules)
mvn -pl hdf5 -am test   # the HDF5 module (and core, which it depends on)
mvn -pl zarr -am test   # the Zarr module (and core)
mvn -pl core test       # just the shared codecs
```

The build is hermetic: conformance fixtures are committed, so no HDF5, h5py, or zarr-python is needed at
build time. (Those are the dev-time reference oracles that *generate* the fixtures — never Falcon
dependencies.)

## HDF5

**Read** — open a file, walk the tree, read datasets (whole, a hyperslab, a strided selection, or
points) and attributes, of every datatype (compound members, enumerations, complex numbers, ...):

```java
import com.ebremer.falcon.hdf5.*;

try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
    for (String name : h5.root().childNames()) { /* ... */ }

    Dataset temps = h5.root().dataset("run/temperature");            // by name or path
    double[] all  = temps.readDoubles();                              // whole dataset, de-filtered
    double[] slab = temps.select(new long[]{0}, new long[]{100})      // a hyperslab
                         .readDoubles();
    double[] tenth = temps.select(new long[]{0}, new long[]{10},      // every 10th value
                                  new long[]{100}, null).readDoubles();
    int[] ids = h5.root().dataset("table").member("id").readInts();   // a compound member
    temps.attribute("units").ifPresent(a -> System.out.println(a.readStrings()[0]));
}
```

Files open from a path (memory-mapped), from a `byte[]`, or through a `RangeReader` (an object store,
HTTP byte ranges, any channel), which Falcon reads on demand: the metadata and only the data asked for.
A resolver opens the other files such a file names (external raw data, virtual-dataset sources, the
files its external links lead to and its references point into).

**Write** — create a file, or change one in place (`Hdf5Writer.open`); add groups, datasets of any
datatype (contiguous or chunked + filters, fixed or growing), attributes, and links; write into datasets,
delete links and attributes. Data is streamed to the file as it is written:

```java
try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
    w.intDataset("counts", new int[]{10, 20, 30}, new long[]{3})
     .intAttribute("scale", new int[]{100}, new long[]{});           // scalar attribute

    Hdf5Writer.GroupWriter run = w.group("run");
    run.stringAttribute("units", "m/s");
    run.doubleDataset("signal", new double[]{0.5, 1.5, 2.5}, new long[]{3});
    Hdf5Writer.DatasetWriter frames = run.createDataset("frames", Datatype.uint16(), 0, 512, 512)
       .chunked(1, 512, 512).maxShape(Hdf5Writer.UNLIMITED, 512, 512).deflate(6);
    for (short[] frame : camera) {
        frames.append(frame);                                        // grows, and goes to the file now
    }
    w.softLink("latest", "/run/frames");
}
```

The reader handles every HDF5 structure (all superblock/header/group forms, chunk indexes, filters,
vlen, references, virtual datasets, external links). The writer covers every datatype, all six filters,
compact, contiguous and chunked storage (growing ones included), dense groups and attributes of any
size, soft and external links, object and region references (in datasets and attributes), files past
2 GB, and both the modern and earliest on-disk formats; it changes existing files in place, its own and
libhdf5's. Full walkthrough in the **[HDF5 User Guide](hdf5/USER_GUIDE.md)**.

## Zarr

Read Zarr v2 and v3; write v3. Reading touches only the chunks a selection overlaps:

```java
import com.ebremer.falcon.zarr.*;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.FileSystemStore;

ZarrArray a = Zarr.open(Path.of("/data/example.zarr")).asArray();    // v2 or v3, directory store
double[] window = a.select(new long[]{100, 0}, new long[]{100, 50}).readDoubles();

// write a compressed v3 array
var store = FileSystemStore.open(Path.of("/data/out.zarr"));
ZarrArray out = Zarr.createArray(store, ArraySpec.builder(new long[]{1000}, DataType.FLOAT64)
        .chunkShape(100).zstd().build());
out.writeDoubles(myData);
```

Every codec the ecosystem commonly uses is supported — `bytes`, `transpose`, `gzip`, `crc32c`,
`sharding_indexed`, `vlen-utf8` strings, and the `zstd` and `blosc` families — with `zstd`/`blosc` read
*and* written by Falcon's own pure-Java encoders (libzstd / c-blosc / zarr-python read the output).
Stores: in-memory, filesystem, ZIP, and read-only HTTP (byte-range). Full walkthrough in the
**[Zarr User Guide](zarr/USER_GUIDE.md)**.

## Design highlights

- **Zero runtime dependencies** — only `java.base` (the format modules depend on Falcon's own `core`,
  which itself needs only `java.base`). `deflate`/`gzip` use `java.util.zip`; everything else is
  hand-written in pure Java: HDF5 `szip` (CCSDS 121.0 extended-Rice), the `zstd` (RFC 8878), `blosc`,
  LZ4, LZF, and bitshuffle codecs in `core` (Zarr codecs and HDF5 filters alike), the Jenkins lookup3 /
  crc32c / fletcher32 checksums, and the shuffle/nbit/scale-offset filters. Every codec is validated
  against its reference implementation (h5py/libaec/hdf5plugin, libzstd, c-blosc).
- **JPMS modules** exporting only their public API.
- **Foreign Function & Memory API** (`MemorySegment`) for memory-mapped access to files beyond 2 GB; HDF5
  files elsewhere are read on demand through a `RangeReader`.
- **Typed exceptions** carrying byte offsets; corrupt input never crashes the JVM or returns wrong data.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 Erich Bremer.
