# Falcon Zarr — User Guide

Falcon Zarr is a **pure-JDK 25, zero-runtime-dependency** reader and writer for the
[Zarr v3 core specification](https://zarr-specs.readthedocs.io/en/latest/v3/core/index.html), and a
reader for Zarr v2 stores. Everything below is the public API in `com.ebremer.falcon.zarr`.

- [Opening a store](#opening-a-store)
- [Reading arrays](#reading-arrays)
- [Selections and streaming](#selections-and-streaming)
- [Writing](#writing)
- [Groups and hierarchy](#groups-and-hierarchy)
- [Data types and fill values](#data-types-and-fill-values)
- [Codecs and compression](#codecs-and-compression)
- [Stores](#stores)
- [What is and isn't supported](#what-is-and-isnt-supported)

## Opening a store

`Zarr.open` returns the root node — a `ZarrGroup` or a `ZarrArray`. Both Zarr v3 (`zarr.json`) and v2
(`.zarray`/`.zgroup`/`.zattrs`) layouts are recognized; v2 is translated to the v3 model on open.

```java
import com.ebremer.falcon.zarr.*;
import java.nio.file.Path;

ZarrNode root = Zarr.open(Path.of("/data/example.zarr"));   // a directory store
ZarrArray array = root.asArray();                            // or root.asGroup()
```

`Zarr.open(Store)` opens any store (see [Stores](#stores)); `Zarr.open(Path)` is the convenience for a
read-only directory. `Zarr.openArray(store)` / `Zarr.openGroup(store)` assert the root's kind.

## Reading arrays

A `ZarrArray` describes itself and reads its data. The whole-array readers widen every numeric type to
the Java type you ask for:

```java
long[] shape      = array.shape();        // e.g. [1000, 1000]
long[] chunkShape = array.chunkShape();
DataType type     = array.dataType();     // e.g. DataType.FLOAT64
long   elements   = array.size();

double[] all = array.readDoubles();       // any numeric type -> double[]
int[]    ints = array.readInts();         // integer types that fit an int
long[]   longs = array.readLongs();
float[]  floats = array.readFloats();     // float types
byte[]   raw   = array.readRawBytes();    // C-order element bytes, for complex/raw or manual decoding
```

Values are returned in **C (row-major) order**. A whole-array read must fit in one Java array (about
2&nbsp;GB); for larger arrays, read [in blocks](#selections-and-streaming).

## Selections and streaming

A **selection** is a rectangular region `[offset, offset+shape)`. Reading one touches only the chunks it
overlaps, so a small window into a large array is cheap.

```java
// rows 100..199, columns 0..49
Selection window = array.select(new long[] {100, 0}, new long[] {100, 50});
double[] values = window.readDoubles();   // 100*50 elements, row-major
```

To process an array larger than memory, iterate its **blocks** — one selection per chunk, each clamped
to the array bound:

```java
array.blocks().forEach(block -> {
    double[] chunk = block.readDoubles();
    long[]   at    = block.offset();
    // ... process this tile ...
});
```

Overlapping or repeated selections reuse a per-array **decoded-chunk cache** (LRU, ~16&nbsp;MB), so each
chunk is decompressed once. Call `array.clearChunkCache()` to release it.

## Writing

Create an array from an `ArraySpec`, then write into it. `ArraySpec.builder` defaults to one chunk
covering the array, a zero fill value, and a little-endian `bytes` codec.

```java
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.FileSystemStore;

var store = FileSystemStore.open(Path.of("/data/out.zarr"));
ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {1000}, DataType.FLOAT64)
        .chunkShape(100)
        .gzip(5)                 // optional gzip compression
        .fillValue(0.0)
        .attributes(...)         // optional JSON attributes
        .build());

a.writeDoubles(myData);                                  // whole array
a.select(new long[] {500}, new long[] {100})             // or a region
        .writeInts(...);
```

Writes are chunk-aligned: a chunk the write covers completely is stored directly; a partially covered one
(including every edge chunk) is read back, updated, and re-encoded. A chunk that ends up holding only the
fill value is **not stored** — its absence *is* the fill, which is how Zarr represents empty chunks.

`ArraySpec.builder` also offers `endian`, `crc32c()`, `sharding(subChunkShape)`, `dimensionNames(...)`,
and `chunkKeyEncoding("default"|"v2")`.

## Groups and hierarchy

A `ZarrGroup` is a named collection of child arrays and groups.

```java
ZarrGroup root = Zarr.open(store).asGroup();
List<String> children = root.childNames();
ZarrArray temperature = root.array("temperature");
ZarrGroup nested       = root.group("model");
JsonValue title        = root.attributes().find("title").orElse(null);

// create a hierarchy
ZarrGroup g = Zarr.createGroup(store);
g.createGroup("model").createArray("weights", spec).writeFloats(...);
```

Navigation reads the store on demand, so a group reflects the store's current contents.

## Data types and fill values

Core data types are modeled by `DataType`: `bool`, `int8/16/32/64`, `uint8/16/32/64`, `float16/32/64`,
`complex64/128`, and the raw `r<N>` family. Byte order is **not** part of the data type — it lives in the
`bytes` codec (`ArraySpec.endian`, or the v2 dtype string when reading v2).

A fill value is stored as JSON; `array.fillValue()` returns it, and `array.fillValueBytes(order)` decodes
it to element bytes. Non-finite floats use the strings `"NaN"`, `"Infinity"`, `"-Infinity"`.

## Codecs and compression

Falcon implements the Zarr v3 codec pipeline `(array→array)* (array→bytes) (bytes→bytes)*`:

| Codec | Read | Write |
|---|---|---|
| `bytes` (endianness) | ✅ | ✅ |
| `transpose` (axis order) | ✅ | ✅ |
| `gzip` | ✅ | ✅ |
| `crc32c` (checksum) | ✅ | ✅ |
| `sharding_indexed` | ✅ (byte-range) | ✅ |
| `zstd` | ✅ | ✅ (pure-Java LZ77+FSE; libzstd reads it) |
| `blosc` (blosclz/lz4/lz4hc/zlib/zstd + byte/bit shuffle) | ✅ | ✅ (byte shuffle + zstd; c-blosc reads it) |

`zstd` and `blosc` are read *and* written by pure-Java implementations (zarr-python compresses with zstd by default; libzstd reads Falcon's
zstd frames). All compression codecs are hand-written in pure Java. The only blosc internal codec not implemented is
`snappy` (dropped from modern c-blosc); it is reported clearly rather than mis-decoded.

## Stores

A `Store` is a key→value map with byte-range reads; the module ships four:

```java
import com.ebremer.falcon.zarr.store.*;

new MemoryStore();                                  // in-memory, read/write
FileSystemStore.open(root);                         // directory, read/write
FileSystemStore.openReadOnly(root);
ZipStore.openReadOnly(archive);                     // a .zip archive, read-only
ZipStore.pack(sourceStore, archivePath);            // build a .zip from any store
HttpStore.openReadOnly("https://host/data/store");  // read-only over HTTP(S)
```

`HttpStore` uses HTTP `Range` requests, so a remote sharded array reads only the bytes it needs. Plain
HTTP has no directory listing, so a group's children cannot be *enumerated* over HTTP (a named child still
opens fine). Implement `Store` yourself for other backends (object stores, databases).

## What is and isn't supported

**Supported:** Zarr v3 read and write; Zarr v2 read; all core data types; the regular chunk grid; both
chunk key encodings; the codecs above; sharding with efficient byte-range reads; selections; the memory,
filesystem, ZIP, and HTTP stores.

**Not supported** (see [`TODO.md`](TODO.md)): writing `zstd`/`blosc`; `blosc`'s `blosclz`/`snappy`/
bit-shuffle; Zarr v2 *writing*, Fortran order, and v2 filters; consolidated metadata and other registered
extensions.

Corrupt input (bad metadata, truncated or damaged chunks, malformed compressed streams) fails with a typed
exception — `ZarrFormatException`, `ZarrUnsupportedException`, or `ZarrException` — never a JVM crash or an
out-of-memory from a bogus declared size.
