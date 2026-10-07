---
title: Zarr how-to
description: Recipes for reading and writing Zarr v3 and v2 with Falcon's zarr module.
---

The `zarr` module (`com.ebremer.falcon.zarr`) reads and writes [Zarr](https://zarr.dev/) v3 and v2: every
core data type and the extension types zarr-python writes, every codec the ecosystem commonly uses, sharding
(nested too), rectilinear chunk grids, and consolidated metadata.

```java
import com.ebremer.falcon.zarr.*;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.*;
import com.ebremer.falcon.zarr.store.*;
import java.nio.file.Path;
```

The [full Zarr user guide](https://github.com/ebremer/falcon/blob/main/zarr/USER_GUIDE.md) covers every
option; this page is the common tasks.

## Opening

### Open a store

`Zarr.open` returns the root node, a `ZarrGroup` or a `ZarrArray`, whether the store is Zarr v3 (`zarr.json`)
or v2 (`.zarray`, `.zgroup`):

```java
ZarrNode root = Zarr.open(Path.of("data.zarr"));          // a directory, read-only
ZarrGroup group = root.asGroup();                          // or root.asArray()

Store store = FileSystemStore.open(Path.of("data.zarr"));  // a directory you may write to
ZarrGroup same = Zarr.openGroup(store);
```

| Store | Opens with |
|---|---|
| A directory | `FileSystemStore.open(path)`, `FileSystemStore.openReadOnly(path)` |
| A ZIP archive | `ZipStore.openReadOnly(path)`, `ZipStore.create(path)`, `ZipStore.open(path)` |
| HTTP(S), read-only | `HttpStore.openReadOnly("https://host/data.zarr")` |
| Memory | `new MemoryStore()` |
| Amazon S3 | `S3Store` in the `s3` module: see [S3 and HTTP](cloud.md) |

Every store may be used from several threads at once.

### Navigate groups

```java
ZarrGroup root = Zarr.openGroup(store);
List<String> names = root.childNames();
ZarrArray temperature = root.array("temperature");
ZarrGroup model = root.group("model");
ZarrArray weights = root.array("model/layers/weights");          // by path, one request
ZarrArray direct = Zarr.openArray(store, "model/layers/weights"); // from the store
JsonValue title = root.attributes().find("title").orElse(null);
```

## Reading arrays

### Read a whole array

```java
long[] shape = array.shape();
DataType type = array.dataType();
double[] all = array.readDoubles();        // bool, integer, and float types
int[] ints = array.readInts();             // integer types that fit an int
long[] longs = array.readLongs();          // bool, integers but uint64, and times
long[] bits = array.readUnsignedLongs();   // unsigned types exactly, uint64 included
String[] text = array.readStrings();       // string arrays
byte[][] blobs = array.readByteArrays();   // variable-length bytes, raw bytes, structs
```

Values come back in row-major (C) order. A whole read must fit one Java array (about 2 GB); read larger
arrays in windows or blocks.

### Read a window

A selection reads only the chunks it overlaps (in a sharded array, only the shard's index and the
sub-chunks it needs):

```java
Selection window = array.select(new long[] {100, 0}, new long[] {100, 50});   // offset, shape
double[] values = window.readDoubles();
```

### Process an array block by block

```java
array.blocks().forEach(block -> {             // one selection per chunk
    double[] chunk = block.readDoubles();
    long[] at = block.offset();
    // ...
});

array.blocks(1024, 1024).forEach(block -> { /* blocks of any shape, one extent per dimension */ });
```

For a sharded array, `array.blocks(array.innerChunkShape())` reads one sub-chunk at a time. For overlapping
or repeated reads, read through a cached handle, which decompresses each chunk once:

```java
ZarrArray cached = array.withChunkCache(64L << 20);   // up to 64 MB of decoded chunks
```

## Writing

### Create an array and write it

```java
Store store = FileSystemStore.open(Path.of("out.zarr"));
ZarrArray a = Zarr.createArray(store, ArraySpec.builder(new long[] {1000, 1000}, DataType.FLOAT32)
        .chunkShape(100, 100)
        .zstd()                       // compress with zstd
        .fillValue(0.0)
        .dimensionNames("y", "x")
        .build());

a.writeFloats(values);                                                     // the whole array
a.select(new long[] {500, 0}, new long[] {100, 1000}).writeFloats(rows);   // or a region
```

Writes are chunk-aligned: a chunk covered completely is stored directly, one covered in part is read,
updated, and stored again. A chunk holding only the fill value is not stored, as Zarr intends.

### Build a hierarchy

```java
ZarrGroup root = Zarr.createGroup(store, JsonObject.builder().put("title", "scan").build());
ZarrGroup model = root.createGroup("model");
ZarrArray weights = model.createArray("weights",
        ArraySpec.builder(new long[] {256, 256}, DataType.FLOAT32).chunkShape(64, 64).build());
weights.writeFloats(new float[256 * 256]);
root.consolidate();     // write consolidated metadata, so a remote reader learns the tree in one request
```

`createArray` and `createGroup` refuse a name that is taken; pass `overwrite = true` to replace what is
there: `root.createArray("x", spec, true)`.

### Compress, checksum, and shard

`ArraySpec.Builder` sets the codecs:

| Builder method | Codec |
|---|---|
| `gzip(level)` | `gzip` |
| `zstd()`, `zstd(level)` | `zstd` (Falcon's own encoder; libzstd reads it) |
| `blosc()`, `blosc(cname, clevel, shuffle)` | `blosc`, with every internal compressor |
| `bz2(level)` | numcodecs' bzip2 |
| `zfpy()`, `zfpyAccuracy(t)`, `zfpyRate(r)`, `zfpyPrecision(p)` | numcodecs' zfp |
| `crc32c()` | a checksum |
| `sharding(subChunkShape...)` | `sharding_indexed`: `chunkShape` is then the shard's shape |
| `castValue(type)`, `reshape(...)` | the zarr-extensions codecs |
| `endian(order)` | the `bytes` codec's byte order |

```java
ArraySpec sharded = ArraySpec.builder(new long[] {20000, 20000}, DataType.UINT8)
        .chunkShape(4096, 4096)       // each shard
        .sharding(512, 512)           // the chunks inside it
        .zstd()
        .build();
```

### Strings, bytes, times, and structs

```java
ZarrArray names = Zarr.createArray(store, ArraySpec.builder(new long[] {3}, DataType.STRING).build());
names.writeStrings(new String[] {"alpha", "", "gamma"});

ZarrArray when = Zarr.createArray(store2, ArraySpec.builder(new long[] {2}, DataType.datetime64("ms", 1)).build());
when.writeLongs(new long[] {1577836800000L, Long.MIN_VALUE});   // 2020-01-01, and NaT

DataType point = DataType.struct(new DataType.Field("x", DataType.INT16), new DataType.Field("y", DataType.FLOAT32));
```

### Resize, change attributes, delete

```java
ZarrArray longer = a.resize(2000, 1000);                        // a new handle on the resized array
ZarrNode tagged = a.updateAttributes(JsonObject.builder().put("units", "K").build());
root.delete("scratch");                                         // a child and everything under it
```

These are several store calls each, not atomic: make them while nothing else writes to that part of the
store.

### Write Zarr v2

```java
ZarrGroup v2 = Zarr.createGroup(store, new JsonObject(Map.of()), false, 2);   // a v2 root group
ZarrArray counts = v2.createArray("counts", ArraySpec.builder(new long[] {1000}, DataType.UINT16)
        .zarrFormat(2)
        .chunkShape(100)
        .compressor(JsonObject.builder().put("id", "zlib").put("level", 1).build())
        .build());
```

Falcon writes the `.zarray` zarr-python 3 writes for the same array.

### Write a ZIP archive

```java
try (ZipStore zip = ZipStore.create(Path.of("image.zarr.zip"))) {
    ZarrGroup root = Zarr.createGroup(zip);
    root.createArray("data", spec).writeDoubles(values);
}   // close() writes the archive's directory: until then nothing can read it
```

## Errors and threads

| Exception | Means |
|---|---|
| `ZarrFormatException` | Malformed metadata, chunks, or compressed data (a failed checksum included). |
| `ZarrUnsupportedException` | A valid store using something Falcon does not implement. |
| `ZarrException` | I/O failures, and a typed read the data type does not support. |
| `IllegalArgumentException`, `IndexOutOfBoundsException`, ... | A mistaken call: a wrong rank, an invalid name, a value the type cannot hold. |

Handles may be shared between threads. Reads run in parallel; writes to different chunks run in parallel;
writes to the same chunk through the same store take turns.
