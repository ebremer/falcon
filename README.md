# Falcon

**Falcon** is a multi-module Java 25 toolkit for scientific-data formats with **minimal dependencies**
and no native libraries. The format libraries — `hdf5`, `zarr`, `ome`, and the `core` codecs they share —
depend on nothing beyond `java.base`. Two modules bring dependencies, and both are optional: the `s3`
module reads both formats from Amazon S3 through the AWS SDK for Java, and the `cli` module, the `falcon`
command, parses its command line with JCommander (and brings the SDK through `s3`).

| Module | Package | What it is | Status |
|---|---|---|---|
| [`hdf5`](hdf5) | `com.ebremer.falcon.hdf5` | HDF5 reader/writer implementing the [HDF5 File Format Specification v4.0](https://support.hdfgroup.org/documentation/hdf5/latest/_f_m_t4.html) (HDF5 2.0) | Read-complete, write-broad; pre-1.0 |
| [`zarr`](zarr) | `com.ebremer.falcon.zarr` | [Zarr](https://zarr.dev/) reader/writer (v3 core; v2 read) | Built |
| [`core`](core) | `com.ebremer.falcon.core` | Pure-Java compression codecs both formats share (zstd, Blosc and Blosc2, LZ4, LZF, bitshuffle, bzip2, ZFP, SZ, zlib, byte shuffle) and their checksums (Fletcher-32, lookup3); exported only to Falcon's modules | Built |
| [`ome`](ome) | `com.ebremer.falcon.ome` | [OME-Zarr](https://ngff.openmicroscopy.org/) 0.4, 0.5, and 0.6 on the Zarr module: multiscale images, labels, plates, collections, and scenes, read, validated against the specification, and written with their pyramids | Built |
| [`s3`](s3) | `com.ebremer.falcon.s3` | Amazon S3 (and S3-compatible storage) for both formats, over the AWS SDK for Java 2.x: a Zarr store and an HDF5 range reader. Optional, and with the `cli`, Falcon's only modules with dependencies | Built |
| [`cli`](cli) | `com.ebremer.falcon.cli` | The `falcon` command, one runnable jar: `ls`, `info`, and `dump` of HDF5 files and Zarr stores, `convert` between them, `copy` and `consolidate` Zarr, `ome validate` and `ome pyramid`; local, HTTP, and S3 | Built |

See **[PLAN.md](PLAN.md)** for the umbrella roadmap and **[CLAUDE.md](CLAUDE.md)** for conventions. Each
module has its own plan, remaining-work list, and user guide:
[hdf5](hdf5/PLAN.md) ([TODO](hdf5/TODO.md), [guide](hdf5/USER_GUIDE.md)) ·
[zarr](zarr/PLAN.md) ([TODO](zarr/TODO.md), [guide](zarr/USER_GUIDE.md)) ·
[ome](ome/USER_GUIDE.md) (guide) · [s3](s3/USER_GUIDE.md) (guide) · [cli](cli/USER_GUIDE.md) (guide).

**Documentation site:** how-to guides for every module, the command, and testing, in [`docs/`](docs/index.md),
published by GitHub Pages at <https://ebremer.github.io/falcon/> (see [`docs/README.md`](docs/README.md)).

## Requirements

- JDK 25+
- Apache Maven 3.9+

## Build

```bash
mvn verify              # whole reactor (parent + all modules)
mvn -pl hdf5 -am test   # the HDF5 module (and core, which it depends on)
mvn -pl zarr -am test   # the Zarr module (and core)
mvn -pl core test       # just the shared codecs
mvn -pl ome -am test    # the OME-Zarr module (and zarr and core)
mvn -pl s3 -am test     # the S3 module (and the formats and core)
mvn -pl cli -am package # the falcon command: cli/target/falcon.jar
mvn verify -Pcoverage   # ...with a coverage report per module, in target/site/jacoco/
```

Each module builds its jar with a sources jar and a Javadoc jar beside it. Builds are reproducible: the
same sources give byte-identical jars. The build checks its own preconditions (JDK 25, Maven 3.9) and
fails on any dependency beyond Falcon's own modules and JUnit 5 for tests, but for the AWS SDK and the
three libraries it brings, at their approved versions, in the `s3` and `cli` modules, and JCommander in the
`cli` module.

The build is hermetic: conformance fixtures are committed, so no HDF5, h5py, or zarr-python is needed at
build time. (Those are the dev-time reference oracles that *generate* the fixtures — never Falcon
dependencies.) The Zarr community's [conformance tests](https://github.com/Bisaloo/zarr-conformance-tests)
run against the `falcon` command in CI as well, and `bash tools/conformance/run_conformance.sh` runs them
locally ([how](cli/USER_GUIDE.md#conformance)). So do two HDF5 checks on the HDF Group's own files:
`python tools/conformance/run_hdf5_conformance.py` reads the HDF5 library's ~400 test files with Falcon and
with h5py and compares them, and `bash tools/conformance/run_hdf5_cve.sh` has Falcon read the malformed files
behind the HDF5 library's CVEs, each of which must fail with a typed exception or read
([how](docs/testing.md#hdf5-the-hdf5-librarys-own-test-files)).

## HDF5

**Read** — open a file, walk the tree, read datasets (whole, a hyperslab, a strided selection, or
points) and attributes, of every datatype (compound members, enumerations, complex numbers, ...):

```java
import com.ebremer.falcon.hdf5.*;

try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
    for (String name : h5.root().childNames()) { /* ... */ }

    Dataset temps = h5.root().dataset("run/temperature");            // by name or path
    double[] all  = temps.readDoubles();                              // whole dataset, de-filtered
    double[] slab = temps.select(new long[]{0}, new long[]{100})      // a hyperslab
                         .readDoubles();
    double[] tenth = temps.select(new long[]{0}, new long[]{10},      // every 10th value
                                  new long[]{100}, null).readDoubles();
    int[] ids = h5.root().dataset("table").member("id").readInts();   // a compound member
    temps.attribute("units").ifPresent(a -> System.out.println(a.readStrings()[0]));
}
```

Files open from a path (memory-mapped), from a `byte[]`, or through a `RangeReader` (an object store,
HTTP byte ranges, any channel), which Falcon reads on demand: the metadata and only the data asked for.
The `s3` module's `S3RangeReader` reads an HDF5 file from Amazon S3 this way.
A resolver opens the other files such a file names (external raw data, virtual-dataset sources, the
files its external links lead to and its references point into).

**Write** — create a file, or change one in place (`Hdf5Writer.open`); add groups, datasets of any
datatype (contiguous or chunked + filters, fixed or growing), attributes, and links; write into datasets,
delete links and attributes. Data is streamed to the file as it is written:

```java
try (Hdf5Writer w = Hdf5Writer.create(Path.of("out.h5"))) {
    w.intDataset("counts", new int[]{10, 20, 30}, new long[]{3})
     .intAttribute("scale", new int[]{100}, new long[]{});           // scalar attribute

    Hdf5Writer.GroupWriter run = w.group("run");
    run.stringAttribute("units", "m/s");
    run.doubleDataset("signal", new double[]{0.5, 1.5, 2.5}, new long[]{3});
    Hdf5Writer.DatasetWriter frames = run.createDataset("frames", Datatype.uint16(), 0, 512, 512)
       .chunked(1, 512, 512).maxShape(Hdf5Writer.UNLIMITED, 512, 512).deflate(6);
    for (short[] frame : camera) {
        frames.append(frame);                                        // grows, and goes to the file now
    }
    w.softLink("latest", "/run/frames");
}
```

The reader handles every superblock, object-header, and group form, every chunk index, the built-in and
the common third-party filters, vlen data, references, virtual datasets, and external links (not files
split across several by the family, multi, or split drivers). The writer covers every datatype, all six
built-in filters (szip in both codings, byte for byte libaec's) and every third-party filter it reads
(LZF, Blosc, LZ4, bitshuffle, Zstandard, bzip2, ZFP, Blosc2, and SZ, as hdf5plugin writes them), compact, contiguous and chunked storage
(growing ones included), dense groups and attributes of any size, hard, soft and external links, object and
region references (in datasets and attributes), user blocks, files past 2 GB, and both the modern and
earliest on-disk formats; it changes existing files in place, its own and libhdf5's (writing into their
datasets through their filters, and through virtual datasets into their sources; moving and deleting
links; changing shared attributes), through a journal that redoes an interrupted change. Full walkthrough in
the **[HDF5 User Guide](hdf5/USER_GUIDE.md)**.

## Zarr

Read and write Zarr v2 and v3. Reading touches only the chunks a selection overlaps:

```java
import com.ebremer.falcon.zarr.*;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.FileSystemStore;

ZarrArray a = Zarr.open(Path.of("/data/example.zarr")).asArray();    // v2 or v3, directory store
double[] window = a.select(new long[]{100, 0}, new long[]{100, 50}).readDoubles();

// write a compressed v3 array
var store = FileSystemStore.open(Path.of("/data/out.zarr"));
ZarrArray out = Zarr.createArray(store, ArraySpec.builder(new long[]{1000}, DataType.FLOAT64)
        .chunkShape(100).zstd().build());
out.writeDoubles(myData);
```

Every codec the ecosystem commonly uses is supported — `bytes`, `transpose`, `gzip`, `crc32c`,
`sharding_indexed`, `vlen-utf8` strings and `vlen-bytes` byte strings, the `zstd` and `blosc` families, and
numcodecs' `zlib`, `lz4`, `bz2`, `zfpy`, filters, and checksums, the zarr-extensions `cast_value` and
`reshape` — with `zstd`/`blosc` read *and* written by Falcon's own
pure-Java encoders (libzstd / c-blosc / zarr-python read the output; Blosc with every internal compressor,
byte for byte as c-blosc writes it, zstd aside). Zarr v2 arrays open with their numcodecs filters,
Fortran order, and NumPy string, byte, time, structured, and object dtypes, and Falcon creates them too
(`ArraySpec.Builder.zarrFormat(2)`), with the `.zarray` zarr-python writes. Consolidated metadata is read
and written, so a remote hierarchy opens in one request; rectilinear chunk grids and the extension data
types zarr-python writes (datetimes, fixed-size strings and bytes, structs) are read and written too.
Stores: in-memory, filesystem, ZIP (read
and written), and read-only HTTP (byte-range; listing from directory index pages); Amazon S3 and
S3-compatible storage (Google Cloud Storage, MinIO, R2) through the `s3` module's `S3Store`, over the AWS
SDK. Full walkthrough in the **[Zarr User Guide](zarr/USER_GUIDE.md)**, and the
**[S3 User Guide](s3/USER_GUIDE.md)**.

## OME-Zarr

The `ome` module reads, validates, and writes [OME-Zarr](https://ngff.openmicroscopy.org/) 0.4, 0.5, and
0.6 — images as resolution pyramids, label images, plates, bioformats2raw collections, and scenes — from any
Zarr store:

```java
import com.ebremer.falcon.ome.*;

MultiscaleImage image = OmeZarr.open(store).asImage();
ZarrArray full = image.level(0);                   // the levels are Zarr arrays
double[] pixel = image.scale(0);                   // the pixel size along each axis
ValidationReport report = new OmeValidator().validate(store);   // against the specification

// a pyramid, from any array: each level the mean of 2x2 blocks of the one before
MultiscaleImageWriter.builder(OmeVersion.V0_5, List.of(Axis.channel("c"), Axis.space("y", "micrometer"),
        Axis.space("x", "micrometer"))).pixelSize(1, 0.25, 0.25).build().write(target, PixelSource.of(array));
```

The validator passes the specification's own conformance tests, but for test data that breaks rules of
the specification's text (which ome-zarr-models rejects too); what Falcon writes, ome-zarr-models validates and
ome-zarr-py reads. Full walkthrough in the **[OME-Zarr User Guide](ome/USER_GUIDE.md)**.

## The falcon command

The `cli` module builds `falcon.jar`, one runnable jar for the shell:

```
java -jar cli/target/falcon.jar ls -r scan.h5                         # the tree, with shapes, types, chunks
java -jar cli/target/falcon.jar dump scan.h5 /frames --slice 0,:4,:4  # values, as text, CSV, or JSON
java -jar cli/target/falcon.jar convert scan.h5 s3://bucket/scan.zarr # HDF5 to Zarr, and Zarr to HDF5
java -jar cli/target/falcon.jar copy scan.zarr scan.zarr.zip          # Zarr, as it is or re-encoded
java -jar cli/target/falcon.jar ome pyramid scan.h5 /frames out.ome.zarr # an OME-Zarr image pyramid
java -jar cli/target/falcon.jar ome validate out.ome.zarr             # check OME-Zarr against its spec
```

Sources are local files, directories, and ZIP archives, `http(s)://` URLs, and `s3://` URLs, of either
format. Conversions map each type to the other format's (h5py's conventions on the HDF5 side) and carry
attributes, chunks, fill values, and compression over; h5py and zarr-python read the results back. Full
walkthrough in the **[CLI User Guide](cli/USER_GUIDE.md)**.

## Design highlights

- **Minimal dependencies** — the format modules need only `java.base` (they depend on Falcon's own
  `core`, which itself needs only `java.base`). Dependencies come only with the optional modules, at fixed,
  approved versions: `s3` uses the AWS SDK, so that the format modules need not, and `cli` JCommander.
- **No native code** — `deflate`/`gzip` use `java.util.zip`; everything else is hand-written in Java: HDF5 `szip` (CCSDS 121.0 extended-Rice), the `zstd` (RFC 8878), `blosc`
  and Blosc2, LZ4, LZF, bitshuffle, bzip2, ZFP, and SZ codecs in `core` (Zarr codecs and HDF5 filters
  alike), the Jenkins lookup3 /
  crc32c / fletcher32 checksums, and the shuffle/nbit/scale-offset filters. Every codec is validated
  against its reference implementation (h5py/libaec/hdf5plugin, libzstd, c-blosc, c-blosc2, libbzip2,
  libzfp, libSZ).
- **JPMS modules** exporting only their public API.
- **Foreign Function & Memory API** (`MemorySegment`) for memory-mapped access to files beyond 2 GB; HDF5
  files elsewhere are read on demand through a `RangeReader`.
- **Typed exceptions** carrying byte offsets. Corrupt input fails with one, never crashing the JVM or
  hanging, and every checksum a file stores is verified; bytes that no checksum covers (HDF5 raw data
  without `fletcher32`, say) can be corrupted without Falcon noticing.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 Erich Bremer.
