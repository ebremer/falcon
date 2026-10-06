# Falcon Zarr — User Guide

Falcon Zarr is a **pure-JDK 25, zero-runtime-dependency** reader and writer for the
[Zarr v3 core specification](https://zarr-specs.readthedocs.io/en/latest/v3/core/index.html), and a
reader for Zarr v2 stores. Everything below is the public API in `com.ebremer.falcon.zarr`.

- [Opening a store](#opening-a-store)
- [Reading arrays](#reading-arrays)
- [Selections and streaming](#selections-and-streaming)
- [Rectilinear chunk grids](#rectilinear-chunk-grids)
- [Writing](#writing)
- [Groups and hierarchy](#groups-and-hierarchy)
- [Data types and fill values](#data-types-and-fill-values)
- [Codecs and compression](#codecs-and-compression)
- [Stores](#stores)
- [Errors and threads](#errors-and-threads)
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
read-only directory. `Zarr.openArray(store)` / `Zarr.openGroup(store)` assert the root's kind. A root
group with [consolidated metadata](#consolidated-metadata) is opened from it; `Zarr.open(store, false)`
reads every node's own metadata instead.

## Reading arrays

A `ZarrArray` describes itself and reads its data. The whole-array readers widen every numeric type to
the Java type you ask for:

```java
long[] shape      = array.shape();        // e.g. [1000, 1000]
long[] chunkShape = array.chunkShape();   // regular grids; chunkSizes() describes any grid
DataType type     = array.dataType();     // e.g. DataType.FLOAT64
long   elements   = array.size();

double[] all = array.readDoubles();       // bool, integer, and float types -> double[]
int[]    ints = array.readInts();         // integer types that fit an int
long[]   longs = array.readLongs();       // bool, integers but uint64, and the time types
float[]  floats = array.readFloats();     // float types
long[]   bits  = array.readUnsignedLongs(); // unsigned types exactly, uint64 included
double[] cplx  = array.readComplex();     // complex types: real, imaginary, real, imaginary, ...
byte[]   raw   = array.readRawBytes();    // C-order element bytes, for raw types or manual decoding
String[] text  = array.readStrings();     // string and fixed_length_utf32
byte[][] blobs = array.readByteArrays();  // variable_length_bytes, null_terminated_bytes, raw_bytes, struct, r*
```

Values are returned in **C (row-major) order**. A whole-array read must fit in one Java array (about
2&nbsp;GB); for larger arrays, read [in blocks](#selections-and-streaming).

Whether a type fits a reader is decided by the type, not by the values stored: `readInts` refuses every
uint32 array, and `readLongs` every uint64 one, since some values could not fit. `readUnsignedLongs` reads
any unsigned type exactly: uint8 to uint32 as their values, and uint64 as its 64 bits, so a value of
2<sup>63</sup> or more comes back as a negative `long` that `Long.toUnsignedString`, `Long.compareUnsigned`,
and `Long.divideUnsigned` read correctly (`readDoubles` would round it). `readComplex` returns a complex
array's parts interleaved, as numpy lays them out: element `i` is `(v[2i], v[2i + 1])`, and a complex64's
float parts widen exactly. The writers `writeUnsignedLongs` and `writeComplex` take the same forms back.

## Selections and streaming

A **selection** is a rectangular region `[offset, offset+shape)`. Reading one touches only the chunks it
overlaps, so a small window into a large array is cheap. In a sharded array it fetches only the shard's
index and the sub-chunks it overlaps, and allocates no more than the window (the index at the end of a
shard is read with one suffix request, without asking the shard's size).

Shards may nest, as zarr-python writes them: a sub-chunk can itself be a shard, to any depth. A read of
part of a nested shard fetches the outer index, the inner index, and only the inner sub-chunks it needs,
as byte ranges, unless a codec such as `crc32c` follows the inner shard, which is then read whole. A write
to part of one re-encodes only the inner sub-chunks it touches, and `withWriteEmptyChunks` applies at
every level.

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

`blocks(blockShape...)` tiles the array in blocks of any shape instead, in C order. In a sharded array a
chunk is a whole shard, which can be hundreds of megabytes; `innerChunkShape()` is the shape of the
sub-chunks the shards hold (zarr-python's `chunks`, where `chunkShape()` is its `shards`), and
`blocks(array.innerChunkShape())` reads one sub-chunk at a time, fetching nothing else but the shard's
index. Read through a cached handle (below), so each shard's index is fetched once rather than once per
block. For an array that is not sharded, `innerChunkShape()` is the chunk shape.

Every read goes to the store, so it sees every write made before it, through any handle. For overlapping
or repeated selections of a compressed array, ask for a handle with a **decoded-chunk cache**, which
decompresses each chunk once:

```java
ZarrArray cached = array.withChunkCache(64L << 20);   // up to 64 MB of decoded chunks, LRU
```

A cached handle sees its own writes, but not writes made through other handles or by other processes
while a chunk stays cached; `cached.clearChunkCache()` empties it. It also keeps shards' indexes, so many
small reads of one shard fetch its index once. It may be shared between threads.

## Rectilinear chunk grids

Most arrays use the core `regular` chunk grid: every chunk has `chunkShape()`. The `rectilinear` grid
(zarr-extensions `chunk-grids/rectilinear`) lets each dimension list its own chunk lengths instead, so
chunks differ in shape: rows chunked 3, 3, 4 and columns 2, 5, say. Falcon reads and writes it.
zarr-python 3.4 does too, but only with `zarr.config.set({"array.rectilinear_chunks": True})`.

```java
ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {10, 7}, DataType.INT32)
        .chunkLengths(0, 3, 3, 4)      // rows
        .chunkLengths(1, 2, 5)         // columns
        .build());

a.isRectilinear();                     // true
long[][] sizes = a.chunkSizes();       // [[3, 3, 4], [2, 5]]: each chunk's extent inside the array
a.blocks().forEach(block -> ...);      // one selection per chunk, of that chunk's shape
```

- **Dimensions without lengths.** A dimension without listed lengths repeats its `chunkShape` entry, or
  is one chunk covering it, so `.chunkShape(1, 4).chunkLengths(0, 2, 8)` lists the rows and chunks the
  columns by 4.
- **Lengths past the array.** The lengths must reach the array's extent and may run past it. A chunk that
  runs past the array is stored at its full listed length, its tail holding the fill value, as
  zarr-python stores it. zarr-python creates only lengths that sum to the extent exactly, but reads
  either.
- **`chunkShape()`.** A rectilinear array has no single chunk shape, so `chunkShape()` throws
  `UnsupportedOperationException`, as zarr-python's `chunks` and `shards` raise there. `chunkSizes()`
  (zarr-python's `write_chunk_sizes`, dask's `chunks`) describes regular and rectilinear grids alike.
- **Sharding.** A rectilinear grid is sharded as zarr-python shards it: the shards follow the grid, and
  the sub-chunks inside them share one shape, `sharding(...)`'s. Every listed length must then be a
  multiple of the sub-chunk's extent along its dimension, which `build()` checks. `innerChunkShape()` is
  the sub-chunk shape, so `blocks(a.innerChunkShape())` reads sub-chunk by sub-chunk across shards of
  different shapes. On an unsharded rectilinear array, `innerChunkShape()` throws, as `chunkShape()`
  does.
- **Resizing** keeps the lengths, as zarr-python does: a dimension that grows past the lengths it lists
  gains one chunk covering the rest, and one that shrinks keeps them all. Otherwise resizing works as on
  a regular grid ([below](#writing)).
- **Large run counts.** Falcon keeps the metadata's `[length, count]` runs as runs, so a run of a million
  chunks costs nothing to open.

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

Writes are chunk-aligned: a chunk the write covers completely (for an edge chunk, every element inside
the array) is stored directly; a partially covered one is read back, updated, and re-encoded. In a shard
covered in part, only the sub-chunks the write touches are decoded and re-encoded; the others keep their
stored bytes. A chunk that ends up holding only the fill value is **not stored** — its absence *is* the
fill, which is how Zarr represents empty chunks.

**Storing empty chunks.** zarr-python's `write_empty_chunks` is a handle option:
`a.withWriteEmptyChunks(true)` returns a handle that stores an all-fill chunk like any other (and, in a
shard, each sub-chunk the write touches). Reads are the same either way; stored empty chunks cost space,
but every chunk written shows up in a listing of the store. The option is not recorded in the metadata:
it belongs to the handle, which passes it on to the handles it makes (`withChunkCache`, `resize`).

**Resizing.** `a.resize(newShape...)` changes the shape and returns a handle on the resized array; the
chunk shape, data, and every other field of the stored metadata stay as they were (a v2 array's `.zarray`
is rewritten in place). On a [rectilinear grid](#rectilinear-chunk-grids) the chunk lengths stay too: a
dimension growing past the lengths it lists gains one chunk covering the rest, and only then is
`chunk_grid` rewritten.

```java
ZarrArray longer = a.resize(2000);   // grow: the new elements read as the fill value
ZarrArray shorter = a.resize(500);   // shrink: chunks wholly past 500 are deleted
```

Shrinking deletes every chunk wholly outside the new shape (one store delete per chunk in the removed part
of the grid). Growing sets to the fill value the part of each old edge chunk that comes inside the array,
which rewrites the stored edge chunks when the old shape was not a multiple of the chunk shape. So values a
shrink cut off never come back, whoever shrank the array; zarr-python's own resize skips that step, and
after shrinking and growing it reads the old values again. A resize is not atomic: let no other writer
touch the array meanwhile, and note that handles opened earlier keep the old shape.

`build()` checks the whole spec as opening the array would (shapes, fill value, dimension names, chunk
key encoding, codecs, and that a chunk fits one Java array), so a bad spec fails with
`IllegalArgumentException` before anything is stored.

**Creating where something exists.** `createArray` and `createGroup` refuse a path that already holds a
node, and `createArray` also refuses one with any key under it (an array would read stray keys as its
chunks); both throw `IllegalArgumentException`. To replace, pass `overwrite = true`, which first deletes
everything under the path, an old array's chunks or an old group's whole subtree:

```java
ZarrArray fresh = root.createArray("x", spec, true);          // child: delete x/..., then create
Zarr.createGroup(store, attributes, true);                    // root: delete every key in the store
```

**Numeric conversion.** The typed writers never wrap, saturate, or truncate a value:

- an integer type takes only a whole number in its range: 200 into `int8`, `2.9` or `NaN` into `int32`,
  and `-1` into `uint64` all throw `IllegalArgumentException`, naming the value and its index (and
  nothing is written);
- `uint64` takes doubles below 2<sup>64</sup>, so `writeDoubles` can store values above `Long.MAX_VALUE`;
- a float type rounds to nearest (ties to even, once, even for `float16`), and refuses a finite value
  beyond its range (`1e40` into `float32`); NaN and the infinities are stored as they are;
- `bool` stores any nonzero value, NaN included, as true, as numpy does.

`ArraySpec.builder` also offers `endian`, `chunkLengths(dimension, lengths...)` (a
[rectilinear grid](#rectilinear-chunk-grids)), `gzip(level)`, `zstd()` / `zstd(level)`, `blosc()`,
`crc32c()`, `sharding(subChunkShape)`, `dimensionNames(...)`, and `chunkKeyEncoding("default"|"v2")`. The `zstd` and
`blosc` compressors are written by Falcon's own pure-Java encoders (libzstd / c-blosc read the output).

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

Navigation reads the store on demand, so a group reflects the store's current contents, unless it answers
from consolidated metadata (below). Opening a node fetches its metadata directly, without probing first:
one request for a v3 node. Creating a node where one exists needs `overwrite` (see [Writing](#writing)).

A node deeper down is reached by its path, from a group or from the store:

```java
ZarrArray w = root.array("model/layers/weights");          // or root.child(...), root.group(...)
ZarrArray same = Zarr.openArray(store, "model/layers/weights");
ZarrGroup sub = Zarr.openGroup(store, "/model");           // a leading '/' is allowed here
```

The node's own metadata is fetched directly, one request whatever the depth, as zarr-python does: the
groups along the path are not opened. A consolidated group finds the node in its snapshot instead, with no
request. `Zarr.open(store, path)` opens a group with its own consolidated metadata, if it has any, and a
path to nothing throws `NoSuchElementException`.

`children()`, `arrays()`, and `groups()` leave out a child Falcon cannot open (malformed metadata, or a
feature it does not implement); `childNames()` still lists it, and `child(name)` throws the reason. A new
node's name must not be empty, contain `/`, consist only of periods, start with `__` (reserved by the
specification), be a metadata key name (`zarr.json`, `.zarray`, …), or end in `.` or a space.

Metadata is read as the v3 specification says: a `zarr.json` member Falcon does not know fails to open
unless it is an object with `"must_understand": false` (zarr-python's `consolidated_metadata` is accepted,
and used as below). The specification's shorthand is accepted too: a codec, chunk grid, or chunk key
encoding given by its bare name (`"codecs": ["bytes"]`), and a core data type as `{"name": "int32"}`.

### Consolidated metadata

A group can store a snapshot of the metadata of every node below it, so a reader learns the whole
hierarchy from one fetch, which matters over a network. `Zarr.open(store)` uses it when present, as
zarr-python 3 does: for v3 it is inside the group's `zarr.json`, and for a v2 root it is `.zmetadata` (one
more request). Walking a consolidated tree (`childNames`, `children`, `child`, `group`, `array`) then reads
nothing more; arrays still read their chunks from the store. `group.isConsolidated()` says whether a group
answers from a snapshot, and `Zarr.open(store, false)` reads every node's own metadata.

```java
ZarrGroup root = Zarr.openGroup(store).consolidate();   // write the snapshot (v3 only)
ZarrGroup remote = Zarr.openGroup(s3Store);             // one GET, then the whole tree from the snapshot
```

`consolidate()` walks every node below the group by its own metadata and writes the snapshot into the
group's `zarr.json` in zarr-python 3.4's layout, which zarr-python reads with `use_consolidated=True`. It
refuses a v2 hierarchy (Falcon writes v3 only) and fails, before writing anything, if a node's metadata is
malformed. A malformed snapshot fails to open; use `Zarr.open(store, false)` to read around it.

The snapshot is what the hierarchy was when it was consolidated: nodes created, resized, or given new
attributes later look as they were until `consolidate()` runs again. Deleting a child (below) is the one
change that also updates the group's own snapshot, as in zarr-python.

### Changing attributes and deleting nodes

`node.setAttributes(attrs)` replaces a node's attributes; `node.updateAttributes(changes)` merges top-level
members, as zarr-python's `attrs.update` does. Both reread the node's stored metadata and rewrite only its
attributes (for a v2 node, `.zattrs`), and return a new handle; the old one keeps what it was opened with.
`group.delete(name)` deletes the child and everything under it, its metadata first, so a delete cut short
leaves no node behind, and removes it from the group's consolidated metadata. Deleting needs a store that
can list its keys (not `HttpStore`). None of these is atomic: two processes changing one node race, and
the last write wins.

## Data types and fill values

Data types are modeled by `DataType`. The core types are constants: `bool`, `int8/16/32/64`,
`uint8/16/32/64`, `float16/32/64`, `complex64/128`, the raw `r<N>` family, and the variable-length
`string` and `variable_length_bytes` types. The [extension types](#extension-data-types) zarr-python
writes (`numpy.datetime64`, `numpy.timedelta64`, `fixed_length_utf32`, `null_terminated_bytes`,
`raw_bytes`, `struct`) are made by factories. Byte order is **not** part of the data type — it lives in
the `bytes` codec (`ArraySpec.endian`, or the v2 dtype string when reading v2).

A fill value is stored as JSON; `array.fillValue()` returns it, and `array.fillValueBytes(order)` decodes
it to element bytes. Non-finite floats use the strings `"NaN"`, `"Infinity"`, `"-Infinity"`; a NaN other
than the canonical quiet NaN is written as a hex string of its bits (`"0x7fc00001"`), so it survives.
`ArraySpec.Builder.fillValue(long)` and `fillValue(double)` convert the number as the writers do and write
it in the type's own form (`1` for an integer type, `true` for `bool`); `fillValue(JsonValue)` writes the
JSON as given.

Attributes may hold the bare `NaN`, `Infinity`, and `-Infinity` that Python's `json` writes (zarr-python
writes them in v2 and v3 attributes alike); they read as `JsonNumber`s whose `isFinite()` is false, and
are written back unchanged. Metadata Falcon generates itself stays strict JSON.

### Variable-length strings

`DataType.STRING` is a variable-length UTF-8 string type, serialized by the `vlen-utf8` array→bytes codec
(in place of `bytes`); its fill value is a JSON string (default `""`). Read and write it as `String[]`
rather than a numeric array:

```java
ZarrArray a = Zarr.createArray(store,
        ArraySpec.builder(new long[] {3}, DataType.STRING).chunkShape(3).zstd().build());
a.writeStrings(new String[] {"alpha", "", "gamma-δ"});
String[] back = Zarr.openArray(store).readStrings();   // and Selection.readStrings()/writeStrings()
```

The numeric accessors (`readDoubles`/`writeInts`/…) reject a string array, and `readStrings`/`writeStrings`
reject a numeric one. Strings compress with the `bytes→bytes` codecs (`gzip`, `zstd`, `blosc`), can be
sharded (`vlen-utf8` inside the shard, as zarr-python writes sharded string arrays), and can be
transposed (`transpose` before `vlen-utf8`, zarr-python's order). A `null` element is written as `""`, as
numcodecs writes Python's `None`.

### Variable-length bytes

`DataType.BYTES` is zarr-python's `variable_length_bytes` (what `VariableLengthBytes()` creates; the
shorter name `"bytes"` opens too): byte strings of any length, serialized by the `vlen-bytes` codec, which
lays them out as `vlen-utf8` does. Its fill value is the bytes in base64 (default `""`, no bytes). Read and
write it as `byte[][]`:

```java
ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {3}, DataType.BYTES)
        .fillValue(new JsonString(Base64.getEncoder().encodeToString(new byte[] {0, -1})))
        .build());
a.writeByteArrays(new byte[][] {{1, 2, 3}, {}, null});    // null is written as no bytes
byte[][] back = a.readByteArrays();                       // and Selection.readByteArrays()/writeByteArrays()
```

Each `byte[]` returned is the caller's own. Everything said of strings above holds: compression,
sharding, `transpose` before the codec, and the other accessors refusing the type. zarr-python marks the
type as not yet in the v3 specification, and so may other implementations.

### Extension data types

Falcon reads and writes the extension data types zarr-python 3.4 writes, as zarr-python writes them:

| `DataType` | zarr.json name | numpy | Read and write as | Fill value JSON (default) |
|---|---|---|---|---|
| `datetime64(unit, scale)` | `numpy.datetime64` | `datetime64[10s]` = `("s", 10)` | `long[]`: counts of the unit since 1970, `Long.MIN_VALUE` for NaT | an integer or `"NaT"` (NaT) |
| `timedelta64(unit, scale)` | `numpy.timedelta64` | `timedelta64[ns]` | `long[]`: counts of the unit, `Long.MIN_VALUE` for NaT | an integer or `"NaT"` (NaT) |
| `fixedLengthUtf32(n)` | `fixed_length_utf32` | `U<n>` | `String[]` (`readStrings`/`writeStrings`) | a string (`""`) |
| `nullTerminatedBytes(n)` | `null_terminated_bytes` | `S<n>` | `byte[][]` (`readByteArrays`/`writeByteArrays`) | base64 (`""`) |
| `rawBytes(n)` | `raw_bytes` | `V<n>` | `byte[][]` | base64 (zero bytes) |
| `struct(fields...)` | `struct` (legacy `structured` read too) | structured dtype | `byte[][]`: whole elements, numbers little-endian | an object of field values (each field's default) |

The units are numpy's: `Y`, `M`, `W`, `D`, `h`, `m`, `s`, `ms`, `us` (or `μs`), `ns`, `ps`, `fs`, `as`,
and `generic`. A time is its exact int64 count; `dataType().unit()` and `scaleFactor()` give its meaning,
so `java.time` conversion is yours (`Instant.ofEpochMilli(v)` for `("ms", 1)`).

```java
ZarrArray when = Zarr.createArray(store, ArraySpec.builder(new long[] {3}, DataType.datetime64("ms", 1)).build());
when.writeLongs(new long[] {1577836800000L, Long.MIN_VALUE, 0});   // 2020-01-01, NaT, 1970-01-01

DataType point = DataType.struct(new DataType.Field("x", DataType.INT16), new DataType.Field("y", DataType.FLOAT32));
ZarrArray pts = Zarr.createArray(store2, ArraySpec.builder(new long[] {100}, point)
        .fillValue(Json.parse("{\"x\": -1, \"y\": \"NaN\"}")).build());
byte[] first = pts.readByteArrays()[0];                             // 6 bytes, little-endian
short x = ByteBuffer.wrap(first).order(ByteOrder.LITTLE_ENDIAN).getShort(point.fieldOffset("x"));
```

- **Text and byte strings, as numpy has them.**
  - A `fixed_length_utf32` element holds up to `n` code points, as UTF-32 code units in the `bytes`
    codec's order. Writing a longer string is refused (`IllegalArgumentException`, nothing written).
  - `null_terminated_bytes` writes up to `n` bytes; `raw_bytes`, `struct`, and `r<N>` write exactly
    their size.
  - Trailing NULs are padding: `readStrings` and a `null_terminated_bytes` `readByteArrays` return values
    without them, so `"ab\0"` reads as `"ab"`. NULs inside a value are kept.
  - A `null` element is written as empty, or zero bytes.
- **Structs.** A struct is packed without padding: `fields()` lists the fields in order, and
  `fieldOffset(name)` says where each starts. Fields may be any fixed-size type, structs included.
  - Every number inside a struct follows the `bytes` codec's `endian` when stored. That covers integers,
    floats, times, UTF-32 units, and nested structs; bytes, bools, and `S`/`V` fields have no byte order.
  - `readByteArrays` and `writeByteArrays` always use little-endian elements, so a struct reads the same
    from any array of its type.
  - A struct fill value is an object of field values in each field's own JSON form; a field left out takes
    its type's default. zarr-python's base64 form is read too.
- **Defaults.** Without a fill value, `ArraySpec` writes `DataType.defaultFillValue()`: NaT for times, and
  for a struct each field's default. zarr-python's own struct default instead casts 0, so its `S`/`U`
  fields default to `"0"` and its time fields to the epoch. Both read back as written.
- **Endian.** `ArraySpec` writes the `bytes` codec's `endian` only for a type with a byte order
  (`DataType.hasByteOrder()`). Byte-string types, and a struct of single-byte fields, get
  `{"name": "bytes"}`, as zarr-python writes them.

All of them go through sharding, `transpose`, every compressor, the chunk cache, resizing,
`write_empty_chunks`, and consolidated metadata. zarr-python marks most of them as not yet stable in the
v3 specification (only `struct` is), so other implementations may not read them. Registry types
zarr-python does not write (`bfloat16`, `float8_*`, `int4`, …) and v2's dtype strings for these types
(`<M8[ns]`, `<U8`, `|S5`, structured lists) are not supported.

## Codecs and compression

Falcon implements the Zarr v3 codec pipeline `(array→array)* (array→bytes) (bytes→bytes)*`:

| Codec | Read | Write |
|---|---|---|
| `bytes` (endianness) | ✅ | ✅ |
| `vlen-utf8` (variable-length strings) | ✅ | ✅ |
| `vlen-bytes` (variable-length byte strings) | ✅ | ✅ |
| `transpose` (axis order) | ✅ | ✅ |
| `gzip` | ✅ | ✅ |
| `crc32c` (checksum) | ✅ | ✅ |
| `sharding_indexed` | ✅ (byte-range; nested to any depth) | ✅ (nested too) |
| `zstd` | ✅ | ✅ (pure Java, levels 1–22, the content checksum when configured; libzstd reads it) |
| `blosc` (blosclz/lz4/lz4hc/zlib/zstd/snappy + byte/bit shuffle; c-blosc2 chunks too) | ✅ | ✅ (no, byte, or bit shuffle, the configured block size, type size, and clevel, c-blosc-sized blocks; zstd internally; c-blosc reads it) |

`zstd` and `blosc` are read *and* written by pure-Java implementations (zarr-python compresses with zstd
by default; libzstd reads Falcon's zstd frames). All compression codecs are hand-written in pure Java.
All of blosc's internal codecs (blosclz/lz4/lz4hc/zlib/zstd/snappy) and both shuffle filters are
supported on the read side, and so are chunks written by c-blosc2 (Blosc format versions 3 to 6), which
c-blosc 1.x and so zarr-python cannot read: the extended header, the filter pipeline (byte and bit
shuffle, delta, truncated precision), split and unsplit blocks, zero and run streams, and the
header-only chunks of zeros, NaN, or one repeated value. Falcon refuses (`ZarrUnsupportedException`)
c-blosc2's variable-length blocks, dictionaries, lazy chunks, and plugin codecs and filters (bytedelta,
zfp, …). Falcon writes c-blosc's format, which every Blosc reader reads.

Writing into an array follows its codecs' configuration, whoever created it: a zstd `level` (libzstd's
scale, 1 to 22; 0 or none means the default, 3) and `checksum`, and a blosc `shuffle`, `typesize`,
`blocksize`, and `clevel`, which also sets the zstd level inside Blosc as c-blosc does (`2 × clevel − 1`,
and 22 at 9). `ArraySpec.Builder.zstd(level)` picks the level. One setting has no effect: a blosc `cname`
other than `zstd` (Falcon compresses with zstd inside Blosc whatever the name; the result is valid Blosc,
which every reader decodes from its own header, but not the compressor the metadata names).

Falcon's zstd encoder works as libzstd's lazy strategies do: Huffman-coded literals, FSE tables fitted to
each block, repeat offsets, matches across blocks within the level's window (512 KiB at level 1 to 8 MiB
at 17 and up), and blocks split where the data's statistics change. At levels 1–9 its ratios are close
to libzstd 1.5.7's, better on some inputs and worse on others by up to about 10% (at level 3: noisy
float32 0.883, as libzstd; Java source text 0.215 against 0.228). At 16 and up it trails slightly, having
no optimal parsing. It is slower than libzstd: about 30–90 MB/s at levels 1–3 depending on the data,
15–40 MB/s at 9, and 3–11 MB/s at 19.

**Storage transformers.** No storage transformer is registered with Zarr, and Falcon implements none. An
array whose `storage_transformers` lists one that says `"must_understand": false` opens, as the v3
specification allows, and the transformer is ignored; any other fails to open with
`ZarrUnsupportedException`, naming it. Falcon's metadata rewrites (attributes, resizing, consolidation)
keep the list as stored. zarr-python 3.4 refuses every non-empty list. An unknown codec is refused even
with `"must_understand": false`, since skipping it would decode the wrong bytes.

## Stores

A `Store` is a key→value map with byte-range reads; the module ships five:

```java
import com.ebremer.falcon.zarr.store.*;

new MemoryStore();                                  // in-memory, read/write
FileSystemStore.open(root);                         // directory, read/write
FileSystemStore.openReadOnly(root);
ZipStore.openReadOnly(archive);                     // a .zip archive, read-only
ZipStore.create(archive);                           // a new .zip archive, written in place
ZipStore.open(archive);                             // add to a .zip archive (or start one)
ZipStore.pack(sourceStore, archivePath);            // copy any store into a new .zip
HttpStore.openReadOnly("https://host/data/store");  // read-only over HTTP(S)
S3Store.fromUrl("s3://bucket/data.zarr").build();   // S3-compatible object storage
```

`HttpStore` uses HTTP `Range` requests, so a remote sharded array reads only the bytes it needs. HTTP has
no way to list keys, so by default a group's children cannot be *enumerated* over HTTP (a named child still
opens fine, and a consolidated group lists its children from its snapshot). A static file server's
directory listings can stand in, as fsspec reads them: `HttpStore.builder(url).directoryListing(true)`
reads the HTML index page a server makes for a directory (Python's `http.server`, nginx's `autoindex`,
Apache's `mod_autoindex`) and takes its links to names directly below as keys and child prefixes. It is off
by default because any other page a server answers with (a 200 error page, an application's start page)
would list links that are not keys. Listing a whole tree costs a request per directory. Implement `Store`
yourself for other backends (databases, other object stores); `getSuffix`, which reads the last bytes of a
value, has a default you can override with a single request.

`HttpStore` percent-encodes keys, keeps a base URL's query (presigned or SAS URLs) on every request, and
follows redirects (http to https, never back). Only 404 means a key is absent. For an S3 or GCS bucket
that answers 403 for absent keys, build the store with
`HttpStore.builder(url).missingStatuses(404, 403).build()`; a denied key then reads as fill, so do this
only for public buckets. Bodies are bounded: a response longer than asked for fails rather than being
buffered.

`HttpStore` can also authenticate: `builder(url).header("Authorization", "Bearer …")` adds a fixed header,
and `requestHeaders((method, uri) -> Map.of(...))` computes headers for each request, for tokens that
expire. Both go only to the base URL's origin (scheme, host, and port); a redirect elsewhere gets none of
them, as curl drops `Authorization`. Headers the store or the JDK owns (`Range`, `Host`, `Content-Length`,
…) and values with control characters are refused.

### Cloud object stores

`S3Store` reads, lists, and writes S3-compatible object storage: Amazon S3, Google Cloud Storage (its XML
API with HMAC keys, region `auto`), MinIO, and Cloudflare R2.

```java
Store store = S3Store.fromUrl("s3://my-bucket/data/image.zarr")
        .region("eu-west-1")
        .fromEnvironment()   // AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN, AWS_REGION
        .build();
ZarrGroup root = Zarr.openGroup(store);
```

- Requests are signed with AWS Signature Version 4 (`credentials(id, secret[, token])`, or
  `fromEnvironment()`). Without credentials, the default, they go unsigned, for public buckets, and the
  store is read-only (`readOnly()` makes a signed one read-only too).
- `endpoint("http://localhost:9000")` names another service and addresses the bucket in the path;
  `pathStyle(false)` puts it in the host name. `prefix(...)`, or the path of an `s3://` URL, roots the store
  inside the bucket.
- Listings use ListObjectsV2 with the `/` delimiter, so `childNames()` works, a page of 1,000 keys per
  request. Writes are single PUTs (a value is at most 2 GB).
- A bucket that may be read but not listed answers 403 for an absent key: pass `missingStatuses(404, 403)`.
- Server errors (500, 502, 503, 504) and failed connections are retried 3 times (`maxRetries`). A bucket
  in another region is reported with its region; redirects are not followed.
- Not supported: `~/.aws` profiles, instance roles, and SSO; Azure's Shared Key (read Azure through a SAS
  URL on `HttpStore`); GCS OAuth (use HMAC keys, or a bearer token on `HttpStore` for reads).

`ZipStore` reads a range of an uncompressed (STORED) entry directly, so sharded arrays in a ZIP read only
what they need. It also writes, as zarr-python's `ZipStore` does in modes `"w"` and `"a"`: `create` starts
a new archive and `open` adds to one, appending each value as a STORED entry, and `close()` writes the
archive's central directory (with Zip64 records past 65,535 entries or 4 GiB). Close the store: until then
the file has no directory and nothing can read it, and adding to an archive writes over its old one. A
value written again, or deleted, leaves its old bytes in the file (the directory names only the newest
entry); `ZipStore.pack(zip, newPath)` copies the live entries into a compact archive. Where zarr-python
wrote a key twice, its archive holds the name twice; Falcon reads the last, as Python does.

```java
try (ZipStore zip = ZipStore.create(Path.of("image.zarr.zip"))) {
    ZarrGroup root = Zarr.createGroup(zip);
    root.createArray("data", spec).writeDoubles(values);
}   // close() writes the central directory
```

`FileSystemStore` writes each value to a temporary file beside it and renames it into place, so a reader
never sees part of a write, and a process that dies mid-write leaves the old value. It does not force the
bytes to disk, so a value written just before a power loss may be lost. On Windows, where a file cannot be
replaced while another handle has it open, a write waits briefly for readers to close it. It refuses key
segments that end in `.` or a space or contain `\` (Windows would alias them), and its listings read only
the directory asked for.

Every shipped store may be used from several threads at once.

## Errors and threads

What is wrong with the store and what is wrong with the call are told apart. Anything wrong with what is
stored is a `ZarrException`: `ZarrFormatException` for malformed metadata, chunks, or compressed data
(including a failed checksum, and a chunk that decodes to more than it should hold);
`ZarrUnsupportedException` for a valid store using a feature Falcon does not implement; and a plain
`ZarrException` for I/O failures (an object store's error code and message included), a selection too
large for one Java array, or a typed read or write the array's data type does not support. A mistaken
call gets the JDK's own exceptions: `IllegalArgumentException` (a wrong rank or number of values, an
invalid name or spec, a value the type cannot hold, a node that exists), `IndexOutOfBoundsException`
(outside the array), `NoSuchElementException` (a missing child), `IllegalStateException` (`asArray()` of
a group), `UnsupportedOperationException` (writing to a read-only store).

Handles hold no mutable state except a cached handle's cache and a consolidated group's snapshot (which a
`delete` through it updates in one swap), and every shipped store is safe for concurrent use, so one
handle can serve several threads. Reads run in parallel with each other and with writes; writes to
different chunks run in parallel. Writes that touch the same chunk, or the same shard, through the same
`Store` object take turns, so neither update is lost. Writes to one chunk through different `Store`
objects or processes are not coordinated: the last to store the chunk wins. Changes to metadata
(attributes, resizing, deleting, consolidating) are several store calls, not atomic: make them while
nothing else writes to that part of the hierarchy.

## What is and isn't supported

**Supported:** Zarr v3 read *and* write; Zarr v2 read; all core data types plus variable-length `string`
and `variable_length_bytes`, with exact uint64 and complex accessors; the extension data types
zarr-python writes (`numpy.datetime64`, `numpy.timedelta64`, `fixed_length_utf32`,
`null_terminated_bytes`, `raw_bytes`, `struct`); the regular chunk grid and the rectilinear one
(zarr-python's `array.rectilinear_chunks`); both chunk key encodings; every codec in the table above
(including `zstd` and `blosc` written by Falcon's own encoders, and all of blosc's internal codecs + both
shuffle filters on read, c-blosc2's chunk format too); sharding, nested too, with efficient byte-range
reads and writes, for strings too; selections, navigation by path, and block streaming (by chunk,
sub-chunk, or any block shape); resizing, and zarr-python's `write_empty_chunks`; storage transformers
that need not be understood (`must_understand: false`, read past); consolidated metadata, read and
written; changing attributes and deleting nodes; the memory, filesystem, ZIP (read and written), HTTP
(listing from directory index pages, when asked), and S3-compatible stores.

**Not supported** (see [`TODO.md`](TODO.md)): Zarr v2 *writing*, Fortran
(`"F"`) order, and v2 filters; extension metadata that must be understood, other extension data types
(`bfloat16`, the `float8`/`int4` families, and other registry types zarr-python does not write), v2 dtype
strings for the extension types, and storage transformers that must be understood (none is registered);
consolidating a v2 hierarchy; Azure Shared Key and GCS OAuth (implement the `Store` SPI yourself, or use
SAS URLs and bearer tokens on `HttpStore`); zstd dictionaries and optimal parsing (so Falcon's highest
zstd levels trail libzstd's slightly); a blosc `cname` other than `zstd` on write; c-blosc2 chunks with
variable-length blocks, dictionaries, or plugin codecs and filters; and compression in a ZIP Falcon
writes (entries are STORED, as zarr-python writes them).

Corrupt input (bad metadata, truncated or damaged chunks, malformed compressed streams) fails with a typed
exception — `ZarrFormatException`, `ZarrUnsupportedException`, or `ZarrException`. Decompression is bounded
by what the chunk can hold, so a few bytes claiming gigabytes fail at once. A variable-length chunk
(strings or bytes) is the exception: its decoded size is not known in advance, so only the 2 GB a Java
array holds bounds it.
