# Falcon HDF5 — benchmarks

A small pure-JDK benchmark lives in `src/test/java/.../Benchmarks.java`. It is **not** a normal test:
its name does not match surefire's test patterns, and it skips itself unless you pass
`-Dfalcon.bench=true`, so it never slows CI.

```bash
mvn -pl hdf5 -am test -Dtest=Benchmarks -Dfalcon.bench=true -Dsurefire.failIfNoSpecifiedTests=false
```

Each row is a warmup phase followed by measured runs, reporting the median (a sink consumes results so
the work is not optimized away). It uses no benchmarking library: JMH would be more rigorous, but it is
not in `java.base` and is not a Falcon dependency, so the harness is hand-rolled with `System.nanoTime()`.

**Absolute numbers depend on the machine, JVM, and data.** Compare rows, or two versions on one machine.

## What it measures

The data is a 2048 × 2048 `float64` dataset (32 MiB), written three ways by Falcon's writer: contiguous,
chunked 64 × 64 (1,024 chunks), and chunked with gzip. The rows:
- **Whole reads** of each.
- **Partial reads:** streaming in blocks, and 1,000 random 4 × 4 selections.
- **Virtual datasets:** 100 one-element selections of two fixtures (two source files, and three printf
  sources).
- **Lookups by name:** fresh handles each time, as a caller walking paths gets. The groups are
  `dense_big.h5` (20,000 links in dense storage, an object with 3,000 dense attributes) and
  `oldstyle_big.h5` (5,000 links in an old-style group).
- **Remote reads:** how many bytes a 4 × 4 selection of the gzip dataset fetches through a
  `RangeReader`.

## P2 PF1–PF3: before and after

The same benchmark on one machine (OpenJDK 25, a Windows developer workstation), before the PF items
(snapshot `09-p2-a2-a6`) and after them:

| operation | before | after | |
|---|--:|--:|--:|
| 1,000 random 4 × 4 selections, chunked | 108.6 ms | 8.4 ms | **13×** (PF1) |
| `blocks(16)`: 128 blocks of a chunked dataset | 49–64 ms | 46–48 ms | (PF1) |
| virtual: 100 one-element selections, 2 sources | 62.5 ms | 0.67 ms | **93×** (PF2) |
| virtual: 100 one-element selections, printf | 78.7 ms | 0.62 ms | **127×** (PF2) |
| 100 link lookups, dense group of 20,000 | 1,410 ms | 18.8 ms | **75×** (PF3) |
| 100 attribute lookups among 3,000 dense | 177 ms | 7.2 ms | **25×** (PF3) |
| 100 link lookups, old-style group of 5,000 | 224 ms | 6.7 ms | **33×** (PF3) |
| read whole: contiguous | 9.6 ms | 9.8–10.9 ms | unchanged |
| read whole: chunked (1,024 chunks) | 28–33 ms | 24–37 ms | unchanged |
| read whole: chunked + gzip | 180 ms | 184–198 ms | unchanged |
| list 20,000 links (new handle) | 10–15 ms | 12.5–17 ms | unchanged |

A range spans three runs of that version; a single number is one run. The rows marked unchanged moved by
about as much from run to run as between versions.

Why each improves:
- **PF1 — chunk index cached per dataset.** Each selection used to walk the dataset's whole chunk index.
  It is now read once per `Dataset` and kept compactly, sorted by grid position. Each selection looks up
  only the chunks it covers, so a thousand small selections no longer walk 1,024 chunks each.
  `blocks(16)` gains less, because each of its blocks covers 32 chunks anyway.
- **PF2 — virtual datasets read lazily.**
  - **Before:** every selection assembled the whole virtual dataset, reopening and reading every source.
  - **After:** a selection skips mappings that miss it and pairs virtual elements with source elements
    by arithmetic. It reads only the bounding box of the source elements it needs, and source files stay
    open until the file closes.
- **PF3 — lookups through the name indexes.**
  - **Before:** a lookup on a new handle read every link or attribute.
  - **After:** a dense group or attribute set is searched through its name-hash v2 B-tree, and an
    old-style group by descending its B-tree of names, as libhdf5 does.

## Remote reads

Through a `RangeReader`, a 4 × 4 selection of the 32 MiB gzip dataset (in a 94 MiB file) fetched 176,193
bytes: the metadata pages, the chunk index, and one chunk.
