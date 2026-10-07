---
title: Falcon
description: Java readers and writers for HDF5, Zarr, and OME-Zarr, with minimal dependencies and no native code.
---

# Falcon

<p class="lead">Read and write HDF5, Zarr, and OME-Zarr in Java, with minimal dependencies: no native
libraries, no JNI, and format libraries that need nothing beyond the JDK.</p>

Falcon is a set of Java 25 libraries, and a command-line tool, for the scientific-data formats that hold
images, volumes, and arrays:

- **HDF5**: the [HDF5 file format](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html)
  (version 4.0, HDF5 2.0), read and written, with every built-in filter and the common third-party ones.
- **Zarr**: [Zarr](https://zarr.dev/) v3 and v2, read and written, with every codec the ecosystem commonly
  uses, sharding, and consolidated metadata.
- **OME-Zarr**: the bioimaging conventions of [OME-Zarr](https://ngff.openmicroscopy.org/) 0.4, 0.5, and
  0.6: multiscale images, labels, plates, and scenes, read, validated, and written with their pyramids.
- **Anywhere**: local files and directories, ZIP archives, HTTP, and Amazon S3 (and S3-compatible storage).

<div class="cards">
  <a class="card" href="{{ '/getting-started.html' | relative_url }}"><strong>Getting started</strong><span>Build Falcon, add it to a project, and read your first file.</span></a>
  <a class="card" href="{{ '/hdf5.html' | relative_url }}"><strong>HDF5 how-to</strong><span>Open, read, select, write, compress, and change HDF5 files.</span></a>
  <a class="card" href="{{ '/zarr.html' | relative_url }}"><strong>Zarr how-to</strong><span>Open stores, read windows and blocks, create arrays and groups.</span></a>
  <a class="card" href="{{ '/ome-zarr.html' | relative_url }}"><strong>OME-Zarr how-to</strong><span>Read images and labels, validate, and write pyramids.</span></a>
  <a class="card" href="{{ '/cloud.html' | relative_url }}"><strong>S3 and HTTP</strong><span>Read and write remote data, one byte range at a time.</span></a>
  <a class="card" href="{{ '/cli.html' | relative_url }}"><strong>The falcon command</strong><span>Inspect, dump, convert, copy, validate, and build pyramids from the shell.</span></a>
</div>

## A taste

```java
import com.ebremer.falcon.hdf5.*;
import com.ebremer.falcon.zarr.*;
import java.nio.file.Path;

try (Hdf5File h5 = Hdf5File.open(Path.of("scan.h5"))) {
    Dataset frames = h5.root().dataset("images/frames");
    double[] first = frames.select(new long[] {0, 0, 0}, new long[] {1, 512, 512}).readDoubles();
}

ZarrArray image = Zarr.open(Path.of("scan.zarr")).asArray();
double[] window = image.select(new long[] {100, 0}, new long[] {100, 50}).readDoubles();
```

```
$ falcon ls -r scan.h5
$ falcon convert scan.h5 s3://bucket/scan.zarr
$ falcon ome pyramid scan.h5 /images/frame slide.ome.zarr --pixel-size 0.25,0.25
```

## The modules

| Module | Java module | What it is |
|---|---|---|
| `hdf5` | `com.ebremer.falcon.hdf5` | HDF5 reader and writer |
| `zarr` | `com.ebremer.falcon.zarr` | Zarr v3 and v2 reader and writer, with stores for memory, directories, ZIP, and HTTP |
| `ome` | `com.ebremer.falcon.ome` | OME-Zarr 0.4, 0.5, and 0.6 on the `zarr` module |
| `core` | `com.ebremer.falcon.core` | The pure-Java compression codecs the formats share (internal) |
| `s3` | `com.ebremer.falcon.s3` | Amazon S3 for both formats, over the AWS SDK (optional) |
| `cli` | (an application) | The `falcon` command: one runnable jar |

`hdf5`, `zarr`, `ome`, and `core` depend on nothing beyond `java.base`. Only the optional `s3` module (the AWS
SDK) and the `cli` application (JCommander, and the SDK through `s3`) have dependencies, and only a project
that uses them takes those dependencies on.

## Why Falcon

- **Minimal dependencies.** Reading and writing HDF5, Zarr, and OME-Zarr adds Falcon's own jars and nothing
  else; S3 support adds the AWS SDK, and only when you ask for it.
- **Any platform, no native code.** No HDF5 C library, no native codecs, nothing to install but a JDK: zstd,
  Blosc, LZ4, bzip2, szip, ZFP, SZ, and the rest are written in Java and checked against their reference
  implementations.
- **Large and remote data.** Files are memory-mapped through the Foreign Function & Memory API, so they may
  be far larger than 2 GB; remote files and stores are read a byte range at a time, fetching only the
  metadata and chunks a read needs.
- **Checked against the reference tools.** h5py and libhdf5, zarr-python and numcodecs, ome-zarr-py and
  ome-zarr-models write the test data Falcon reads, and read back what Falcon writes; the Zarr and OME-Zarr
  communities' conformance tests run in CI, and Falcon and h5py read the HDF5 library's own test files alike.
  See [Testing and conformance](testing.md).
- **Fails safely.** Corrupt input fails with a typed exception, never a crash or a hang, and every checksum
  a file stores is verified.

## Status

Falcon is pre-1.0 (`0.1.0-SNAPSHOT`) and not yet published to Maven Central: build it from source and
install it into your local Maven repository, as [Getting started](getting-started.md) shows. The source,
issues, and the full user guide of each module are on [GitHub](https://github.com/ebremer/falcon).
