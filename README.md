# Falcon

**Falcon** is a multi-module, **pure-JDK 25, zero-runtime-dependency** toolkit for scientific-data
formats — no native libraries, no third-party dependencies.

| Module | Package | What it is | Status |
|---|---|---|---|
| [`hdf5`](hdf5) | `com.ebremer.falcon.hdf5` | HDF5 reader/writer implementing the [HDF5 File Format Specification v4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html) (HDF5 2.0) | In development |
| `zarr` | `com.ebremer.falcon.zarr` | [Zarr](https://zarr.dev/) reader/writer | Planned (Falcon Phase 2) |

See **[PLAN.md](PLAN.md)** for the full roadmap and **[CLAUDE.md](CLAUDE.md)** for conventions.

## Status

Early development. The reactor, `hdf5` module, and build harness are in place; the HDF5 format
implementation is being built out per the roadmap (stages H0–H9).

## Requirements

- JDK 25+
- Apache Maven 3.9+

## Build

```bash
mvn verify            # whole reactor
mvn -pl hdf5 test     # just the HDF5 module
```

## Usage

**Read** — open a file, walk the tree, read datasets (whole or by hyperslab) and attributes:

```java
import com.ebremer.falcon.hdf5.*;

try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
    for (String name : h5.root().childNames()) { /* ... */ }

    Dataset temps = h5.root().group("run").dataset("temperature");
    double[] all  = temps.readDoubles();                              // whole dataset, de-filtered
    double[] slab = temps.select(new long[]{0}, new long[]{100})      // a hyperslab
                         .readDoubles();
    temps.attribute("units").ifPresent(a -> System.out.println(a.readStrings()[0]));
}
```

**Write** — create a file, add groups, datasets (contiguous or chunked + filters), and attributes:

```java
try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
    w.intDataset("counts", new int[]{10, 20, 30}, new long[]{3})
     .intAttribute("scale", new int[]{100}, new long[]{});           // scalar attribute

    Hdf5Writer.GroupWriter run = w.group("run");
    run.doubleDataset("signal", new double[]{0.5, 1.5, 2.5}, new long[]{3});
    run.intChunkedDataset("big", data, new long[]{100_000}, new long[]{4096})
       .shuffle().deflate(6);                                        // chunked + filters
}
```

The reader handles every HDF5 structure (all superblock/header/group forms, chunk indexes, filters,
vlen, references, virtual datasets); the writer covers the common datatypes, all six filters, compact
and dense storage, and both the modern and earliest on-disk formats. See
[`Hdf5File`](hdf5/src/main/java/com/ebremer/falcon/hdf5/Hdf5File.java) and
[`Hdf5Writer`](hdf5/src/main/java/com/ebremer/falcon/hdf5/Hdf5Writer.java) for the full API.

## Design highlights

- **Zero runtime dependencies** — only `java.base`. `deflate` uses `java.util.zip`; `szip` is
  implemented from scratch in pure Java (CCSDS 121.0 extended-Rice); all other filters and the Jenkins
  lookup3 checksum are hand-written.
- **JPMS modules** exporting only their public API.
- **Foreign Function & Memory API** (`MemorySegment`) for memory-mapped access to files beyond 2 GB.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 Erich Bremer.
