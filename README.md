# Falcon

A **pure-JDK 25, zero-runtime-dependency** reader and writer for the
[HDF5](https://www.hdfgroup.org/solutions/hdf5/) file format, implementing the
[HDF5 File Format Specification, Version 4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html)
(HDF5 2.0) — no native `libhdf5`, no third-party libraries.

## Status

Early development. The scaffold, module, and build harness are in place; the format implementation
is being built out per the roadmap in **[PLAN.md](PLAN.md)**. See **[CLAUDE.md](CLAUDE.md)** for
project conventions.

## Requirements

- JDK 25+
- Apache Maven 3.9+

## Build

```bash
mvn verify
```

## Design highlights

- **Zero runtime dependencies** — only `java.base`. `deflate` uses `java.util.zip`; all other filters
  and the Jenkins lookup3 checksum are implemented from scratch.
- **JPMS module** `com.ebremer.falcon`, exporting only the public API.
- **Foreign Function & Memory API** (`MemorySegment`) for memory-mapped access to files beyond 2 GB.

## License

Apache License 2.0 (pending confirmation — see PLAN.md §10).
