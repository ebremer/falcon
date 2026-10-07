---
title: API at a glance
description: Falcon's modules, packages, main classes, and exceptions, and where to find the Javadoc and full guides.
---

## Modules and packages

| Module | Maven artifact | Java module | Exported packages |
|---|---|---|---|
| HDF5 | `com.ebremer:hdf5` | `com.ebremer.falcon.hdf5` | `com.ebremer.falcon.hdf5` (and `...hdf5.datatype`) |
| Zarr | `com.ebremer:zarr` | `com.ebremer.falcon.zarr` | `com.ebremer.falcon.zarr`, `...zarr.datatype`, `...zarr.json`, `...zarr.store` |
| OME-Zarr | `com.ebremer:ome` | `com.ebremer.falcon.ome` | `com.ebremer.falcon.ome`, `...ome.metadata` |
| S3 | `com.ebremer:s3` | `com.ebremer.falcon.s3` | `com.ebremer.falcon.s3` |
| Codecs | `com.ebremer:core` | `com.ebremer.falcon.core` | internal: exported to Falcon's modules only |
| Command | `com.ebremer:cli` | (an application) | `falcon.jar` |

All are version `0.1.0-SNAPSHOT`, and need Java 25.

## Main classes

### HDF5

| Class | For |
|---|---|
| `Hdf5File` | an open file: `open(path)`, `open(bytes)`, `open(rangeReader)`; `root()` |
| `Group`, `Dataset`, `Attribute`, `Hdf5Object` | the file's objects: navigate, read |
| `Selection` | part of a dataset: `select(...)`, `selectPoints(...)`, `blocks(...)` |
| `Datatype`, `Dataspace`, `Filter`, `Link` | what a dataset holds and how it is stored |
| `OpenOptions`, `ExternalFileAccess` | caches, and which other files a file may open |
| `RangeReader` | a file's bytes from anywhere: implement it for your own source |
| `Hdf5Writer` (`GroupWriter`, `DatasetWriter`) | create a file, or change one in place |

### Zarr

| Class | For |
|---|---|
| `Zarr` | open and create: `open(store)`, `openArray`, `openGroup`, `createArray`, `createGroup` |
| `ZarrGroup`, `ZarrArray`, `ZarrNode` | the hierarchy: navigate, read, write, resize, consolidate |
| `Selection` | a window of an array: `select(offset, shape)`, `blocks(...)` |
| `ArraySpec` (`Builder`) | a new array: shape, type, chunks, codecs, sharding, fill value |
| `DataType` | element types: the core ones, strings, bytes, times, structs |
| `Json`, `JsonObject`, `JsonValue`, ... | attributes and metadata |
| `Store`, `FileSystemStore`, `ZipStore`, `HttpStore`, `MemoryStore` | where the keys are |

### OME-Zarr

| Class | For |
|---|---|
| `OmeZarr` | open a group (`open(store)`), and create plates, wells, and scenes |
| `MultiscaleImage`, `Plate`, `Well`, `ImageCollection`, `Scene` | views of each kind of group |
| `OmeMetadata`, `OmeVersion` | one group's metadata, as each version lays it out |
| `OmeValidator`, `ValidationReport` | check against the specification |
| `MultiscaleImageWriter`, `PixelSource`, `Downsampling` | write images and label images with their pyramids |
| `metadata.*`: `Axis`, `Multiscale`, `Dataset`, `Transformation`, `Omero`, `ImageLabel`, ... | the metadata model |

### S3

| Class | For |
|---|---|
| `S3Store` | a Zarr store in a bucket: read, list, write |
| `S3RangeReader` | an HDF5 file in a bucket, a byte range at a time; `siblings()` for the files it names |

## Exceptions

| Module | Exception | Means |
|---|---|---|
| HDF5 | `HdfFormatException` | the file breaks the format (a bad checksum, a truncated file, ...) |
| | `HdfUnsupportedException` | valid, but not implemented; or a file the access policy refuses |
| | `HdfClosedException` | the file or writer was closed |
| Zarr | `ZarrFormatException` | malformed metadata, chunks, or compressed data |
| | `ZarrUnsupportedException` | valid, but not implemented |
| | `ZarrException` | I/O failures; the base of the two above |
| OME-Zarr | `OmeFormatException` | metadata that cannot be read (a `ZarrFormatException`) |
| all | `IllegalArgumentException`, `IndexOutOfBoundsException`, `NoSuchElementException`, ... | a mistaken call |

All of Falcon's own exceptions are unchecked. Corrupt input fails with one of them, never a crash or a hang.

## Javadoc

Every public class and method is documented. Build the Javadoc of a module with:

```bash
mvn -pl zarr javadoc:javadoc          # zarr/target/reports/apidocs/index.html
```

`mvn package` also builds each module's `-javadoc.jar`, which IDEs show beside the code.

## The full guides

Each module has a complete user guide in the repository:

- [HDF5 user guide](https://github.com/ebremer/falcon/blob/main/hdf5/USER_GUIDE.md)
- [Zarr user guide](https://github.com/ebremer/falcon/blob/main/zarr/USER_GUIDE.md)
- [OME-Zarr user guide](https://github.com/ebremer/falcon/blob/main/ome/USER_GUIDE.md)
- [S3 user guide](https://github.com/ebremer/falcon/blob/main/s3/USER_GUIDE.md)
- [CLI user guide](https://github.com/ebremer/falcon/blob/main/cli/USER_GUIDE.md)
