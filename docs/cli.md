---
title: The falcon command
description: Inspect, dump, convert, copy, validate, and build OME-Zarr pyramids from the shell with falcon.jar.
---

`falcon` is Falcon's command-line tool: one runnable jar that lists, describes, and prints HDF5 files and Zarr
stores, converts between the two formats, copies and consolidates Zarr, and checks and writes OME-Zarr. It
reads local files, directories, and ZIP archives, `http(s)://` URLs, and `s3://` URLs.

```bash
falcon ls -r scan.h5                                  # every group and dataset, with shapes and types
falcon info scan.zarr images/frame                    # one array in detail
falcon dump scan.h5 images/frame --slice 0,:4,:4      # values, numpy's way
falcon convert scan.h5 scan.zarr                      # HDF5 to Zarr, and Zarr to HDF5
falcon copy s3://bucket/scan.zarr scan.zarr.zip       # a Zarr store, byte for byte or re-encoded
falcon consolidate scan.zarr                          # consolidated metadata
falcon ome validate slide.ome.zarr                    # check OME-Zarr against its specification
falcon ome pyramid scan.h5 images/frame slide.ome.zarr  # an OME-Zarr image pyramid
```

The [full CLI user guide](https://github.com/ebremer/falcon/blob/main/cli/USER_GUIDE.md) lists every option.

## Install

Build it, then run it with Java 25 (see [Getting started](getting-started.md)
for a shell alias):

```bash
mvn -pl cli -am package -DskipTests
java -jar cli/target/falcon.jar --help
```

`falcon --help` lists the commands, `falcon <command> --help` a command's options, and `falcon --version` the
build. The jar is self-contained (about 10 MB): Falcon's modules, JCommander, and the AWS SDK.

> **Windows, Git Bash:** write paths inside a source without the leading `/` (`run/temperature`, not
> `/run/temperature`), or `export MSYS_NO_PATHCONV=1`: Git Bash turns `/run` into a Windows path.

## Sources

A source is told apart by what it holds, not by its name:

| Source | Read as |
|---|---|
| a local file with HDF5's signature | HDF5 |
| a local directory, or a `zarr.json`, `.zarray`, or `.zgroup` in one | a Zarr store |
| a local ZIP archive | a Zarr store in it |
| `s3://bucket/key` of an HDF5 file | HDF5, a byte range at a time |
| `s3://bucket/prefix` | a Zarr store |
| `https://...` of an HDF5 file | HDF5, a byte range at a time |
| `https://...` | a Zarr store, read-only |

Within a source, commands take a path (`run/temperature`); the default is the root.

## ls and info

```
$ falcon ls scan.h5 run
counts       dataset  (10,)   uint16, big-endian  contiguous
temperature  dataset  (4, 6)  float32             chunks (2, 6)  shuffle, deflate(level=4)
```

`ls` lists a group's members with their shapes, types, chunks, and compression; `-r` lists everything below.
`info` describes one group or array in detail: its type, chunks or shards, every filter or codec with its
settings, fill value, storage, and attributes. On OME-Zarr it also gives the image's axes, each level's
shape and pixel size, its channels and labels, or a plate's wells.

## dump

```
$ falcon dump scan.h5 cube --slice 1,::2,-2:
(1, 0, 2): 14, 15
(1, 2, 2): 22, 23
$ falcon dump -f csv scan.h5 run/temperature -s :2,:3
0.0,0.1,0.2
0.6,0.7,0.8
$ falcon dump -f json scan.zarr cube -s :,:2,:2
[[[0, 1],
  [4, 5]],
 [[12, 13],
  [16, 17]]]
```

| Option | |
|---|---|
| `-s`, `--slice` | numpy's indexing: per dimension an index (`5`, `-1`) or `start:stop:step` (`:`, `10:`, `::4`) |
| `-f`, `--format` | `text` (the default), `csv`, or `json` |
| `-a`, `--attribute NAME` | print an attribute instead |

A dump streams, however large the array.

## convert

```
$ falcon convert scan.h5 scan.zarr
...
Wrote 15 arrays and 2 groups to scan.zarr (4 warnings above)
$ falcon convert scan.zarr scan.h5
```

The input decides the direction. A Zarr output is a directory, a `.zip`, or an `s3://` prefix; an HDF5 output
is a local file. Types map as h5py and zarr-python map them, attributes become JSON (and back), and chunks,
fill values, and compression carry over. Links, references, and other things one format cannot hold are left
out with a warning.

| Option | Default | |
|---|---|---|
| `--path P` | `/` | convert only this group or array |
| `--zarr-format 2\|3` | 3 | the Zarr format written |
| `-c`, `--compression` | `auto` | `auto`, `keep`, `none`, `gzip[:level]`, `zstd[:level]`, `blosc[:cname[:clevel[:shuffle]]]`, `bz2[:level]`, `lz4`, `lzf` (HDF5 only) |
| `--chunks keep\|auto` | `keep` | keep the input's chunks, or choose them as h5py or zarr-python would |
| `--consolidate` | | write the Zarr output's consolidated metadata |
| `--overwrite` | | replace the output |
| `-j`, `--threads N` | up to 8 | blocks converted at once |
| `-q`, `--quiet` | | only the summary |

## copy and consolidate

```bash
falcon copy scan.zarr s3://bucket/scan.zarr                    # every key, byte for byte
falcon copy scan.zarr scan-v2.zarr --zarr-format 2             # re-encoded as Zarr v2
falcon copy scan.zarr small.zarr -c blosc:zstd:5:bitshuffle    # re-compressed
falcon consolidate scan.zarr                                   # consolidated metadata
```

With no other option, `copy` copies every key as it is; with `--zarr-format`, `--compression`, or `--chunks`
it re-encodes each array.

## OME-Zarr: validate and pyramid

`ome validate` checks an OME-Zarr image, plate, or scene (0.4, 0.5, or 0.6), and everything below it,
against its specification:

```
$ falcon ome validate https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr --errors-only
error: labels/0: the label image has 4 levels but its image has 3: they must have the same number
https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr: invalid, 1 error and 5 warnings
```

| Option | |
|---|---|
| `--strict` | also require the recommended fields the strict schemas require |
| `--metadata-only` | check the group's attributes alone: quick over HTTP |
| `--attributes FILE` | check a JSON file of one group's attributes |
| `--errors-only` | list no warnings |
| `--json` | print `{"valid": ..., "message": ...}` and exit with 0 either way |

It exits with 0 when there are no errors and 1 when there are.

`ome pyramid` writes an OME-Zarr image from an array (an HDF5 dataset or a Zarr array), building its smaller
levels; with `--label`, it adds a label image to an existing image:

```
$ falcon ome pyramid scan.h5 images/frame slide.ome.zarr --pixel-size 0.25,0.25 --channel-names DAPI,GFP
  level 0: (2, 20000, 30000)
  ...
  level 7: (2, 157, 235)
Wrote OME-Zarr 0.5 image 'frame', 8 levels, to slide.ome.zarr
$ falcon ome pyramid segmentation.h5 cells slide.ome.zarr --label cells
```

| Option | Default | |
|---|---|---|
| `--ome-version` | 0.5 | 0.4, 0.5, or 0.6 |
| `--axes` | the Zarr array's dimension names, else `yx`, `cyx`, `czyx`, or `tczyx` by rank | the axes, as letters or names joined by commas |
| `--pixel-size`, `--unit`, `--time-unit` | 1; micrometer with a pixel size | for each axis, or each space axis |
| `--levels`, `--smallest` | until y and x fit 256 | how many levels |
| `--method` | mean | `mean`, `nearest`, or `mode` |
| `--downsample`, `--factor` | y and x, by 2 | what shrinks, and how much |
| `--chunks`, `--shards` | 512 in y and x; no shards | shards need 0.5 or 0.6 |
| `-c`, `--compression` | zstd | `none`, `gzip`, `zstd`, `blosc`, `bz2` |
| `--channel-names`, `--channel-colors` | none | written as `omero` metadata |
| `--label NAME` | | write a label image of the image at the output |
| `--overwrite`, `-j`, `-q` | | |

The array's dimensions must be in OME-Zarr's order (time, channel, then space): transpose an RGB image stored
y, x, c to c, y, x first.

## S3 and HTTP options

Every command takes these:

| Option | |
|---|---|
| `--s3-region R` | the region requests go to first |
| `--s3-profile P` | the `~/.aws` profile to use |
| `--s3-endpoint URL` | S3-compatible storage (MinIO, R2, GCS) |
| `--s3-anonymous`, `--no-sign-request` | a public bucket |
| `--header 'Name: value'` | an HTTP header, repeatable |
| `--http-listing` | read a static web server's directory pages as listings |
| `-v` | print an error's stack trace |

Without them, S3 credentials come from the AWS SDK's chain (environment variables, `~/.aws` profiles, SSO,
instance roles). See [S3 and HTTP](cloud.md).

## Exit status

| Status | Means |
|---|---|
| 0 | success (for `ome validate`, valid) |
| 1 | failure: a missing file, a malformed store, an S3 error (for `ome validate`, invalid) |
| 2 | the command line was wrong |

An error is one line, `falcon <command>: <what went wrong>`. A warning (`falcon: warning: ...`) does not fail
the command.

## The conformance command

`falcon conformance --array_path=<array>` reads every value of a Zarr array and exits with 0 if it can: it is
the command line the Zarr community's [conformance tests](https://github.com/Bisaloo/zarr-conformance-tests)
call. See [Testing and conformance](testing.md).
