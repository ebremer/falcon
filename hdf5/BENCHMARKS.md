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

The data is a 2048 × 2048 `float64` dataset (32 MiB), written four ways by Falcon's writer: contiguous,
chunked 64 × 64 (1,024 chunks), chunked with gzip, and chunked with the byte shuffle before the gzip. The
rows:
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
  `RangeReader`; and (P2 PF11) through a reader that answers each request after 20 ms, the whole read of
  the gzip dataset and 20 random 128 × 128 selections of it, with one request at a time
  (`readerRequests(1)`) and eight at once, and the requests each takes.
- **Threads** (P2 PF9, PF10): the whole read of the gzip dataset, and 1,000 random 4 × 4 selections of it
  with no chunk cache, with its chunks decoded on every thread and on the reading thread alone
  (`decodeThreads(1)`); and (P2 PF13) the whole read of the shuffled dataset, on every thread and on one.
- **Writes** (P2 PF10): a file of one dataset (create, write, close: the flush to disk and the move into
  place included), contiguous, chunked, and chunked with gzip, that one with its chunks filtered on every
  thread and on the writer's alone (`encodeThreads(1)`); the same with the shuffle before the gzip (P2 PF13);
  and a contiguous `int32` dataset of the same shape (P2 PF14).

## P2 PF12–PF14: a chunk index read ahead, the byte shuffle, values copied in one move

Three of the things measured with PF9–PF11 and left. The machine is the one below: OpenJDK 25 on a Windows
workstation with 32 processors. "Before" is the commit before, built beside this one and run by the same
harness.

### PF12: a remote file's chunk index

The index of a file libhdf5 writes by default is a version 1 B-tree, whose nodes lie among the chunks they
were written with: through a `RangeReader` each is a page, and a request, and a walk of the tree made them
one after another. Now a read says which nodes it is about to read, and their pages are asked for together,
up to eight requests at once (`OpenOptions.readerRequests`).

**A real store.** The public NetCDF-4 file in Amazon S3 of PF11 below (its `CMI` variable: 10,848 × 10,848
`int16`, 2,304 deflated chunks under a tree of two levels), over HTTPS, five and more runs each over an
afternoon (the first run of a JVM left out):

| read | before | | after | |
|---|--:|--:|--:|--:|
| | time | requests | time | requests |
| whole (235 MB of elements, 31 MB stored) | 2.5–2.7 s | 40 | **1.0–1.8 s** | 38 |
| a 1,000 × 1,000 box (25 chunks) | 300–350 ms | 7 | **170–230 ms** | 6 |

The bytes read are the same (compared by hash). One whole read of 1.6 s, traced: 0.34 s before its first
request, 0.22 s for the index (34 requests of a page each, eight at once, where one after another they took
1.5 s), and a second for the chunks (4 requests of up to 8 MiB, sent at once) and their decoding. The 0.34 s
is the output being filled with the dataset's fill value, an element at a time: measured, and not done (see
`TODO.md`).

**A reader that answers each request after 20 ms** (a throwaway harness over the file's channel; two files
h5py 3.16 wrote with `libver="earliest"`, each opened anew for each read, no chunk cache; the median of 3):

| read | before | | after | |
|---|--:|--:|--:|--:|
| | time | requests | time | requests |
| 2,304 chunks (4,800 × 4,800 `int16`, 40 MB; two levels): whole | 1,113 ms | 49 | **339 ms** | 46 |
| a box of a tenth of each side | 149 ms | 11 | **67 ms** | 11 |
| every 7th element of 96 rows | 88 ms | 4 | 66 ms | 4 |
| 16,384 chunks (8,192 × 8,192 `int16`, 117 MB; three levels): whole | 6,480 ms | 314 | **1,135 ms** | 303 |
| a box of a tenth of each side | 440 ms | 33 | **134 ms** | 33 |
| every 7th element of 163 rows | 252 ms | 12 | **90 ms** | 11 |

The requests are about as many as before: what changed is that they wait together. A file Falcon wrote,
whose index lies in one place, is read as it was (the rows of PF11's first table, again: 145, 108, 103, 108,
499, 487 and 68 ms, against 144, 104, 104, 110, 500, 488 and 69 before).

What does not change: what a read returns, and what a damaged tree fails it with (`ChunkBTreeV1Test`,
`ChunkIndexReadAheadTest`). The indexes of the latest file format are still read a page at a time.

### PF13: the byte shuffle

`core`'s `ByteShuffle`, timed alone: 32 MiB in 1,024 chunks of 32 KiB, on one thread, the best of 40 passes.

| element | shuffle, before | after | unshuffle, before | after |
|---|--:|--:|--:|--:|
| 2 bytes | 26.1 ms | 10.9 ms | 25.6 ms | **1.8 ms** |
| 4 bytes | 23.7 ms | 12.2 ms | 21.9 ms | **1.6 ms** |
| 8 bytes | 25.4 ms | 12.0 ms | 25.0 ms | **2.5 ms** |
| 3 bytes | 23.8 ms | 10.5 ms | 23.2 ms | 10.1 ms |
| 16 bytes | 26.9 ms | 12.0 ms | 25.2 ms | 10.7 ms |

An element of 2, 4, or 8 bytes is put together from its streams and written whole; taken apart, its bytes go
to eight streams a byte each, which is why a shuffle gains less than its undoing. Other sizes move a byte
position at a time, each stream from end to end.

`Benchmarks`' rows, four runs each:

| operation | before | after | |
|---|--:|--:|--:|
| read whole: chunked + shuffle + gzip, one thread | 92–105 ms | 77–88 ms | the unshuffle's 20 ms |
| read whole: chunked + shuffle + gzip | 20–23 ms | 20–29 ms | unchanged |
| write: chunked + shuffle + gzip, one thread | 637–766 ms | 609–785 ms | unchanged |
| write: chunked + shuffle + gzip | 90–103 ms | 88–95 ms | unchanged |

On every thread the unshuffle was already spread over the processors, and a write is its deflate. So what
is saved is processor time, and a read's time where one thread decodes; it would show more behind a codec
quicker than gzip, which was not measured.

### PF14: values copied in one move

**The typed API's encoder** (`ValueEncoder`), timed alone: 4 million values to an array of their bytes, the
best of 25.

| values, and the type they are written to | before | after |
|---|--:|--:|
| `double[]` to `float64` | 12.7 ms | **4.1 ms** |
| `double[]` to `float64`, big-endian | 11.9 ms | **3.9 ms** |
| `float[]` to `float32` | 10.8 ms | **1.9 ms** |
| `int[]` to `int32` | 14.6 ms | **1.9 ms** |
| `int[]` to `int32`, big-endian | 18.9 ms | **1.9 ms** |
| `short[]` to `uint16` (none negative) | 13.1 ms | **1.8 ms** |
| `long[]` to `int64` | 21.5 ms | **4.1 ms** |
| `long[]` to `int32` (another kind) | 18.1 ms | 14.8 ms |
| `float[]` to `float64` (another kind) | 17.7 ms | 12.1 ms |

**The per-type methods** (`doubleDataset(name, values, shape)` and the rest), a throwaway harness: the call
that takes the 4 million values, the median of 41, two to four runs.

| method | before | after |
|---|--:|--:|
| `doubleDataset` (32 MiB) | 10.0–12.2 ms | **5.2–5.6 ms** |
| `longDataset` (32 MiB) | 9.3–12.3 ms | **4.7–4.8 ms** |
| `floatDataset` (16 MiB) | 6.7–9.2 ms | 4.5–5.5 ms |
| `intDataset` (16 MiB) | 5.0–6.9 ms | **2.4–2.8 ms** |
| `shortDataset` (8 MiB) | 3.3–3.8 ms | **1.1–1.7 ms** |

Integers are one copy. Floats still go an element at a time, a whole element now and not a byte: these
methods write every NaN as the one canonical NaN, which a copy would not. What is left of the step is mostly
the array it fills: 4.5 ms for 32 MiB with nothing but a copy into it.

**A file written whole does not show it.** Create, the dataset, close, the flush to disk and the move into
place: 36–64 ms for the 32 MiB of `double`s on this machine from one run to the next, before and after
alike, and `Benchmarks`' `write: contiguous` 38–57 ms over eight runs. The 5 ms saved is less than the
flush varies by. The step is a larger part of a write that is not flushed dataset by dataset (many
datasets to a file), which was not measured.

## P2 PF11: a remote file's chunks read together

Through a `RangeReader` a request is a round trip to a store, and a read asked for its chunks one at a time:
chunks smaller than a page a page at a time, larger ones a chunk at a time. Now the chunks a read needs
are put into as few requests as their places in the file allow (those next to each other, or no more than a
page apart, in one request of at most 8 MiB), and up to eight requests are made at once
(`OpenOptions.readerRequests`).

**A reader that answers each request after 20 ms** (a throwaway harness over the file's channel, on the
machine below; a file of three 32 MiB datasets, opened anew for each read, no chunk cache; the commit before
and this one):

| read | before | | after | |
|---|--:|--:|--:|--:|
| | time | requests | time | requests |
| open the file and find the dataset | 43 ms | 2 | 43 ms | 2 |
| whole: 1,024 gzip chunks of 32 KiB | 10,040 ms | 481 | **136 ms** | 7 |
| whole: 64 gzip chunks of 512 KiB | 1,394 ms | 66 | **97 ms** | 6 |
| whole: 1,024 unfiltered chunks of 32 KiB | 10,684 ms | 515 | **93 ms** | 6 |
| a 512 × 512 box of the gzip chunks (81 chunks) | 1,102 ms | 53 | **106 ms** | 12 |
| 20 random 128 × 128 boxes (4 to 9 chunks each) | 2,745 ms | 132 | **493 ms** | 62 |
| 20 random 4 × 4 boxes (one chunk, mostly) | 628 ms | 30 | 489 ms | 23 |
| 4 chunks of 512 KiB, apart from each other | 128 ms | 6 | 69 ms | 6 |

The times include opening the file (two requests). A request of the reader is counted whether it is a page
of metadata or 8 MiB of chunks; the bytes asked for are the same to within 1 % for the whole reads, and
fewer for the boxes (2.6 MB against 3.4 for the 512 × 512 box), since chunks are asked for as they are,
not as the pages they lie in.

**A real store.** A public NetCDF-4 file in Amazon S3 (GOES-16 `ABI-L2-CMIPF`, 31 MB), read over HTTPS with a
`RangeReader` on the JDK's `HttpClient`, from a workstation, two runs each: its `CMI` variable is 10,848 ×
10,848 `int16` in 2,304 deflated chunks.

| read | before | | after | |
|---|--:|--:|--:|--:|
| | time | requests | time | requests |
| whole (235 MB of elements, 31 MB stored) | 23.9–24.5 s | 497 | **2.8–3.1 s** | 40 |
| a 1,000 × 1,000 box (25 chunks, of little data) | 330–437 ms | 8 | 341–588 ms | 7 |

The bytes read are the same (compared by hash). Of the 40 requests, 4 are the chunks (8.3, 8.4, 8.3, and
6.3 MB, sent at once), and 36 are pages of the chunk index, a version 1 B-tree whose nodes lie among the
chunks: they are still read one after another, 1.5 of the 2.8 seconds. That is the next thing to gain (see
`TODO.md`). The box reads few bytes; its time is the index's pages.

**`Benchmarks`' rows** (each request answered after 20 ms; the file already open, its chunk index read):

| operation | one request at a time | eight at once | requests |
|---|--:|--:|--:|
| read whole: 1,024 gzip chunks | 117 ms | 48 ms | 4 |
| 20 random 128 × 128 selections | 1,231 ms | 428 ms | 59 |

One request at a time already has the chunks together: the whole read is 4 requests either way, and it is
the putting together that takes it from 481. Eight at once then overlap their waits.

What does not change: the bytes a read returns; where a read fails (`ChunksReadTogetherTest`); and a mapped
file, which is read as before (the rows above this section's are within their run-to-run spread).

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
`RangeReader` sees (one at a time: PF11, above, has since put them together), and every byte of a file
written (`ParallelChunksTest`).

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
