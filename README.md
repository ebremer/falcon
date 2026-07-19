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

## Design highlights

- **Zero runtime dependencies** — only `java.base`. `deflate` uses `java.util.zip`; `szip` is
  implemented from scratch in pure Java (CCSDS 121.0 extended-Rice); all other filters and the Jenkins
  lookup3 checksum are hand-written.
- **JPMS modules** exporting only their public API.
- **Foreign Function & Memory API** (`MemorySegment`) for memory-mapped access to files beyond 2 GB.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 Erich Bremer.
