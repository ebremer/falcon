# Falcon Zarr — benchmarks

A small pure-JDK benchmark lives in `src/test/java/.../Benchmarks.java`. It is **not** a normal test —
it is skipped unless you pass `-Dfalcon.bench=true`, so it never slows CI:

```bash
mvn -pl zarr test -Dtest=Benchmarks -Dfalcon.bench=true
```

It times a warmup phase followed by measured iterations and reports the median (a sink consumes results
so the work is not optimized away). It uses no benchmarking library — JMH would be more rigorous but is
not in `java.base` and is not a Falcon dependency, so the harness is hand-rolled with `System.nanoTime()`.

**Absolute numbers depend heavily on the machine, JVM, and data.** Treat them as *relative* guidance, and
re-run on your own hardware for anything load-bearing.

## Representative results

Below is one run on OpenJDK 25 (a Windows developer machine, 2026-10-06, after P2 F12 made the zstd
encoder multi-level; it runs at its default, level 3), over a 4 MiB chunk of structured numeric `int32`
(a smooth ramp + a periodic component + light noise — roughly what a real array chunk looks like, ~4x
compressible).

### Codec throughput and compression ratio

| codec | encode | decode | ratio |
|---|--:|--:|--:|
| `bytes` (no compression) | pass-through† | pass-through† | 1.00× |
| `gzip` (level 5) | ~90 MB/s | ~355 MB/s | 4.11× |
| `zstd` (Falcon encoder, level 3) | ~195 MB/s | ~1600 MB/s | 4.02× |
| `blosc` (byte-shuffle + zstd) | ~175 MB/s | ~605 MB/s | 4.00× |
| `crc32c` (checksum only) | ~6.9 GB/s | ~6.3 GB/s | 1.00× |

† The `bytes` codec is a length-checked pass-through and `crc32c` only walks the buffer, so both are
memory-bound; their throughput is dominated by timer resolution rather than real work.

**Takeaway:** at essentially the same compression ratio, Falcon's own **`zstd` encoder is ~2× faster to
write than `gzip`** and ~4× faster to read, which is why it is the recommended compressor for new arrays.
`blosc` (byte-shuffle + zstd) is in the same ballpark and interoperates with the c-blosc ecosystem.
Before F12 the encoder had one fast level that found no Huffman, table, or repeat-offset savings; it
wrote this chunk about as small, but noisy floats and text much larger (see `zarr/TODO.md` F12 for the
ratios against libzstd at levels 1–19). Level 1 (`zstd(1)`) is the fastest.

### End-to-end array read/write

Through the public `ZarrArray` API, over a 16 MiB `int32` array (64 × 65536, one chunk per row):

| codec | write | read |
|---|--:|--:|
| `gzip` (level 5) | ~100 MB/s | ~575 MB/s |
| `zstd` | ~385 MB/s | ~2100 MB/s |
| `blosc` | ~150 MB/s | ~535 MB/s |

### Decoded-chunk cache

Ten overlapping selections that all revisit the same chunk, through a handle with a cache
(`array.withChunkCache(...)`) vs. a plain handle, which decodes the chunk on every read:

```
cached 0.81 ms   uncached 6.33 ms   (7.8x)
```

The cache decompresses each chunk once, so overlapping or repeated reads of a compressed array are
several times faster — here **7.8×**.

## Reading the harness

`Benchmarks` measures three things and prints a table: per-codec encode/decode throughput and ratio
(driving `ChunkPipeline` directly), whole-array write/read throughput (through `Zarr.createArray` /
`Zarr.openArray`), and the chunk-cache speedup. Tune `WARMUP`/`RUNS` and the data generators
(`scientificInt32`) for your own experiments.
