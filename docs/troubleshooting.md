---
title: Troubleshooting
description: Answers to the problems people meet building and using Falcon.
---

## Building

**`release version 25 not supported`, `invalid target release: 25`, or the enforcer's "Java version" rule
fails.** Maven is running on an older JDK. Point `JAVA_HOME` at JDK 25 or later, and check with
`mvn -version`: the Java version it prints is the one that compiles.

**`class file has wrong version 69.0, should be 65.0`** (or similar) in your own project. Falcon's classes are
Java 25 class files; build your project with Java 25 (`<maven.compiler.release>25</maven.compiler.release>`,
or a Gradle toolchain of 25).

**`Could not find artifact com.ebremer:zarr:jar:0.1.0-SNAPSHOT`.** Falcon is not on Maven Central yet. Run
`mvn install -DskipTests` in Falcon's folder, so the jars are in `~/.m2/repository`; with Gradle, add
`mavenLocal()` to `repositories`.

**The build fails with "Only Falcon's own modules, and JUnit 5 for tests".** A dependency was added that the
dependency rules do not allow. Falcon's libraries depend on nothing beyond the JDK; see
[Working on Falcon](development.md#rules-the-code-follows).

## Reading

**`readInts()` refuses a `uint32` (or `readLongs()` a `uint64`) array, though every value is small.** Integer
reads are exact, and decided by the type: some `uint32` values do not fit an `int`. Read with `readLongs()`,
`readUnsignedLongs()` (Zarr), or `read()` (HDF5: a type every value fits).

**`OutOfMemoryError`, or "too large for one Java array".** A whole-array read must fit one Java array (about 2
billion elements). Read a window (`select(offset, shape)`) or stream with `blocks(...)`.

**An HDF5 read fails with `HdfUnsupportedException` naming another file.** The file names another file (an
external link, a virtual dataset's source, external raw data) outside its own directory, which Falcon does not
open by default. Allow it with `ExternalFileAccess` when you open the file; see the
[HDF5 how-to](hdf5.md#follow-external-links-virtual-datasets-and-external-data).

**`HdfUnsupportedException` naming a filter id.** The file uses a compression filter Falcon does not read.
Falcon reads the six built-in filters and LZF, Blosc, Blosc2, LZ4, bitshuffle, Zstandard, bzip2, ZFP, and SZ.

**A Zarr group lists no children over HTTP ("HTTP stores cannot list keys").** HTTP has no directory listing.
Open children by name (`group.array("name")`), consolidate the store's metadata once
(`Zarr.openGroup(store).consolidate()`, or `falcon consolidate`), or, for a static file server that makes
index pages, use `HttpStore.builder(url).directoryListing(true)` (`--http-listing` for the command).

**An S3 or GCS bucket answers 403 for keys that do not exist.** A bucket that may be read but not listed does
that. Build the store with `missingStatuses(404, 403)`.

## S3

**"Unable to load region".** The SDK found no region. Set `AWS_REGION`, give one with
`S3Client.builder().region(...)`, or `--s3-region` for the command.

**Requests fail with the bucket's region named in the message.** The bucket is in another region: name that
region, or build the client with `crossRegionAccessEnabled(true)`.

**A public bucket answers 403, or the SDK finds no credentials.** Read it anonymously:
`credentialsProvider(AnonymousCredentialsProvider.create())`, or `--s3-anonymous` for the command.

**MinIO, R2, or GCS rejects uploads.** Address the bucket by path and compute checksums only where S3 needs
them: `forcePathStyle(true)` and `RequestChecksumCalculation.WHEN_REQUIRED`; see [S3 and HTTP](cloud.md). The
command's `--s3-endpoint` sets both.

**SLF4J prints "Failed to load class org.slf4j.impl.StaticLoggerBinder".** The AWS SDK logs through SLF4J, and
no binding is on your class path. It is harmless; add a binding (`slf4j-simple`, Logback 1.2) to see the SDK's
logs.

## The falcon command

**In Git Bash on Windows, `falcon dump file.h5 /run/temperature` fails, naming a path such as
`C:/Program Files/Git/run/temperature`.** Git Bash turns `/run/...` into a Windows path before Falcon sees it.
Write `run/temperature`, or `export MSYS_NO_PATHCONV=1`.

**`ome validate` takes a long time over HTTP.** It reads every node's metadata below the group, one request at
a time; a large plate has hundreds. Use `--metadata-only` for the group's own metadata.

**`ome pyramid` refuses an RGB image's axes.** OME-Zarr orders axes time, channel, then space, so an array
stored y, x, c (as RGB images often are) cannot be written as it is: transpose it to c, y, x first.

**`convert` warns that something was left out.** One format has things the other cannot hold: Zarr has no
links or references, HDF5 no Zarr-only extension types. The warning names each; the rest is converted.

## OME-Zarr data that fails validation

The validator follows the specification's text, so it finds problems in data that other tools accept:

- **"the label image has N levels but its image has M"**: the specification requires the same number. Some
  public data breaks it.
- **"\"ome\" must hold the \"version\""** on a `labels` group: ome-zarr-py 0.21 writes 0.5 `labels` groups
  without it. Falcon still reads them.
- **"colors, if present, must not be empty"**: older writers wrote empty `colors` lists.

Reading such data still works: the reader is lenient, and the validator only reports.
