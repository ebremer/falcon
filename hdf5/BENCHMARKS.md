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
- **Strided and point selections** (P2 PF8): every other element (stride 2 × 2) of the chunked and the
  contiguous dataset, every other row of the chunked one, 4 × 4 blocks every 8 × 8, and 100,000 random
  points.
- **Virtual datasets:** 100 one-element selections of two fixtures (two source files, and three printf
  sources).
- **Lookups by name:** fresh handles each time, as a caller walking paths gets. The groups are
  `dense_big.h5` (20,000 links in dense storage, an object with 3,000 dense attributes) and
  `oldstyle_big.h5` (5,000 links in an old-style group). Since P2 PF6, a group's links once listed are kept
  for every handle of it, so the listing row opens the file with `objectCacheSize(0)` to time the listing
  itself.
- **Remote reads:** how many bytes a 4 × 4 selection of the gzip dataset fetches through a
  `RangeReader`.
- **Threads** (P2 PF9, PF10): the whole read of the gzip dataset, and 1,000 random 4 × 4 selections of it
  with no chunk cache, with its chunks decoded on every thread and on the reading thread alone
  (`decodeThreads(1)`).
- **Writes** (P2 PF10): a file of one dataset (create, write, close: the flush to disk and the move into
  place included), contiguous, chunked, and chunked with gzip, that one with its chunks filtered on every
  thread and on the writer's alone (`encodeThreads(1)`).

## P2 PF9, PF10: chunks filtered on several threads

One build, with and without the threads (`decodeThreads(1)` and `encodeThreads(1)` are the code as it was),
on one machine: OpenJDK 25 on a Windows developer workstation with 32 processors. Two runs each.

| operation | one thread | every thread | |
|---|--:|--:|--:|
| read whole: chunked + gzip (1,024 chunks) | 134–142 ms | 25.0–27.8 ms | **5×** (PF9) |
| write: chunked + gzip (1,024 chunks, level 4) | 969–996 ms | 118–134 ms | **7–8×** (PF10) |
| 1,000 random 4 × 4 selections, gzip, no chunk cache | 114–136 ms | 124–128 ms | unchanged |
| read whole: chunked, no filter | | 15.9–17.0 ms | no filter to run |
| write: chunked, no filter | | 56–68 ms | no filter to run |
| write: contiguous | | 45–49 ms | |

Why each improves, and what bounds it:
- **A gzip read was inflate.** Timed apart, inflating the 1,024 chunks takes some 117 ms on one thread and
  6 ms across the processors. The rest of a read stays on the reading thread: reading the chunks' stored
  bytes from the file, a chunk after another, and turning the bytes into `double`s. That is most of the
  25 ms.
- **A gzip write was deflate:** timed apart, some 900 ms on one thread and 52 ms across the processors. The
  writer's thread still puts each chunk in the file in turn, and the file is flushed to disk as before; a
  chunked write with no filter takes 56–68 ms.
- **Small reads are as they were.** Nine in ten of the 4 × 4 selections fall in one chunk, which the reading
  thread decodes itself; the rest decode two or four at once.

What does not change: the bytes a read returns, the exception a damaged file fails it with, the requests a
`RangeReader` sees, and every byte of a file written (`ParallelChunksTest`).

**Small chunks.** Handing a chunk to another thread costs some microseconds, which is more than a chunk of a
few hundred bytes takes to decode. So chunks smaller than 32 KiB are filtered several to a piece of work, and
a read or write of less than 64 KiB of chunks stays on its own thread. Measured with a throwaway harness on
the same machine (1,000 reads each, no chunk cache; a write of the 32 MiB dataset):

| operation | one thread | every thread |
|---|--:|--:|
| read 4 chunks of 32 KiB, gzip | 497 ms | 193 ms |
| read 25 chunks of 32 KiB, gzip | 2,846 ms | 482 ms |
| read 16 chunks of 512 bytes, gzip (under 64 KiB: on one thread either way) | 21 ms | 26 ms |
| read 256 chunks of 512 bytes, gzip | 285 ms | 241 ms |
| write 65,536 chunks of 512 bytes, fletcher32 | 836 ms | 588 ms |
| write 65,536 chunks of 512 bytes, gzip | 2,662 ms | 892 ms |

Before chunks were grouped so, the reads of 512-byte chunks took up to 2.8 times as long on every thread as
on one (4 of them: 19 ms against 7; 16: 41 against 25; 256: 458 against 308).

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

## P2 PF8: strided and point selections

Copying elements out of each chunk a run at a time, not one by one. Measured on the same machine, before
PF8 (commit `e985257`) and after it, three runs each:

| operation | before | after | |
|---|--:|--:|--:|
| every other row (stride 2 × 1), chunked | 31.7–35.3 ms | 14.9–16.3 ms | **2.2×** |
| 4 × 4 blocks every 8 × 8, chunked | 16.8–18.1 ms | 9.9–11.1 ms | **1.7×** |
| every other element (stride 2 × 2), chunked | 17.8–20.0 ms | 9.9–11.9 ms | **1.7×** |
| 100,000 random points, chunked | 52.9–57.0 ms | 18.6–21.3 ms | **2.7×** |
| read whole: chunked (1,024 chunks) | 19.5–20.9 ms | 18.9–20.4 ms | unchanged |
| every other element (stride 2 × 2), contiguous | 24.1–26.3 ms | 17.1–29.5 ms | unchanged |

Why each improves:
- **Runs.** A regular hyperslab's elements along its last dimension, within a chunk, are blocks of
  consecutive indices. Each block is now one copy, and touching blocks (stride = block) are one copy
  together. Every other row now takes less time than the whole read; before, it took some 1.7 times as long.
- **Strided single elements** (blocks of one) are one loop per row of a chunk, each element one move of a
  `long`, `int`, or `short` rather than a call to `System.arraycopy`.
- **Points** are ordered by chunk with one sort of numbers, each a chunk's row-major number and the
  point's index, rather than a sort of boxed indices comparing coordinate arrays. Points listed one after
  another along a row are copied together.

Contiguous data already copied runs. Its strided single elements are still copied one by one, and that
row varies from run to run more than it changed.

## P2 PF5–PF7: what a small read reads

These were measured with a throwaway harness rather than `Benchmarks`. Each read opens the fixture fresh
through a counting `RangeReader` and reads nothing else. The time is the best of seven runs and includes
opening the file.

**PF5: one element of a dataset of thousands of chunks** (4 KiB reader pages). Before PF5, the first read
read the whole chunk index. That is the "whole index" column: the same read, after `storageSize()`.

| dataset (index) | one element | with the whole index |
|---|---|---|
| `ea_paged.h5` (extensible array, 150,000 chunks, paged) | 24,598 bytes, 0.30 ms | 2,915,500 bytes, 19.3 ms |
| `big_index.h5` `fa` (fixed array, 16,384 chunks, paged) | 28,676 bytes, 0.19 ms | 274,496 bytes, 2.33 ms |
| `big_index.h5` `bt2` (v2 B-tree, 4,096 chunks) | 12,288 bytes, 0.16 ms | 126,976 bytes, 1.37 ms |
| `big_index_old.h5` `bt1` (v1 B-tree, 4,096 chunks) | 12,288 bytes, 0.11 ms | 217,088 bytes, 1.02 ms |
| `paged_sparse.h5` `ea_sparse` (extensible array, sparse) | 4,096 bytes, 0.12 ms | 49,178 bytes, 0.33 ms |

The lookup reads one entry of an array index, with the block or page it is in, verified once. A B-tree
lookup reads one node per level.

**PF7: a virtual dataset whose elements scatter over their source** (`vds_scatter.h5`, 512-byte reader
pages, the size of one source chunk; no chunk cache). The source is a flat array of 65,536 elements,
chunked 128 elements a chunk. Before PF7, every read below read the 64,513-element box that bounds its
source elements: 267,794 bytes.

| read | bytes |
|---|---|
| `strided`, whole (source elements 1,024 apart) | 43,026 |
| a column of `reshaped` (a 128 × 512 grid over the flat source) | 76,306 |
| one element in 1,024 of `same` (a strided selection) | 43,026 |

When chunks are smaller than a reader page, the pages decide: with 4 KiB pages, these reads touch every
page, as the box does.

## Remote reads

Through a `RangeReader`, a 4 × 4 selection of the 32 MiB gzip dataset (in a 94 MiB file) fetched 176,193
bytes: the metadata pages, the chunk index, and one chunk. PF5 leaves that number unchanged. The
dataset's 1,024-entry fixed array is a single data block, which a lookup verifies whole, and it fits in
one 64 KiB page anyway.
