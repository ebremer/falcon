# Falcon CLI — User Guide

The `falcon` command reads HDF5 files and Zarr stores, converts one format to the other, and copies and
consolidates Zarr: from local files and directories, ZIP archives, HTTP(S), and Amazon S3. It is Falcon's
`cli` module, built on the format modules, the `s3` module, and [JCommander](https://jcommander.org) for
its command line.

```
falcon ls -r scan.h5                                   # every group and dataset, with shapes and types
falcon info scan.zarr /images/frame                    # one array in detail
falcon dump scan.h5 /images/frame --slice 0,:4,:4      # values, numpy's way
falcon convert scan.h5 scan.zarr                       # HDF5 to Zarr v3 (and back the other way)
falcon copy s3://bucket/scan.zarr scan.zarr.zip        # a Zarr store, byte for byte
falcon consolidate scan.zarr                           # consolidated metadata
```

- [Building and running](#building-and-running)
- [Sources](#sources)
- [ls and info](#ls-and-info)
- [dump](#dump)
- [convert](#convert)
- [copy and consolidate](#copy-and-consolidate)
- [conformance](#conformance)
- [S3 and HTTP options](#s3-and-http-options)
- [Errors and exit status](#errors-and-exit-status)
- [What is and isn't supported](#what-is-and-isnt-supported)

## Building and running

```bash
mvn -pl cli -am package -DskipTests      # builds cli/target/falcon.jar
java -jar cli/target/falcon.jar --help
```

`falcon.jar` is the command and everything it needs in one runnable jar (about 10 MB): Falcon's modules,
JCommander, and the AWS SDK for Java 2.x with the libraries it brings. It needs Java 25. An alias makes it
`falcon`:

```bash
alias falcon='java -jar /path/to/falcon.jar'                       # bash, zsh
function falcon { java -jar C:\path\to\falcon.jar @args }           # PowerShell
```

`falcon --help` lists the commands, `falcon <command> --help` a command's options, and `falcon --version`
the build. The jar's `META-INF/LICENSE` and `META-INF/THIRD-PARTY.txt` give the licenses of what it bundles
(Apache 2.0, and SLF4J's MIT and Reactive Streams' MIT-0), and `META-INF/NOTICE` their notices. The same
sources build the same jar, byte for byte.

## Sources

A source is told apart by what it holds, not by its name:

| Source | Read as |
|---|---|
| A local file with HDF5's signature (at its start, or after a user block) | HDF5, memory-mapped |
| A local directory | a Zarr store (v2 or v3) |
| A local ZIP archive | a Zarr store in it (`ZipStore`) |
| A local `zarr.json`, `.zarray`, `.zgroup`, ... | the Zarr store of its directory |
| `s3://bucket/key` of an object with HDF5's signature | HDF5, a byte range at a time (`S3RangeReader`); the files it links to are read from beside it |
| `s3://bucket/prefix` (anything else) | a Zarr store (`S3Store`) |
| `http(s)://` of a file served in byte ranges with HDF5's signature | HDF5, a byte range at a time |
| `http(s)://` (anything else) | a Zarr store (`HttpStore`), read-only |

Within a source, `ls`, `info`, and `dump` take a path: `/run/temperature`, or `run/temperature`; `/` (the
default) is the root. In Git Bash on Windows, write paths without the leading `/` (or set
`MSYS_NO_PATHCONV=1`): the shell turns `/run` into a Windows path.

## ls and info

`ls` lists a group's members, by name, with each one's shape, type, chunks, and compression; `-r` lists
everything below the group, with full paths. On a dataset or array, it lists that one.

```
$ falcon ls scan.h5 run
counts       dataset  (10,)   uint16, big-endian  contiguous
temperature  dataset  (4, 6)  float32             chunks (2, 6)  shuffle, deflate(level=4)
```

HDF5's soft, external, and user-defined links are listed as links (`-> /run/temperature`). A group reached by
two paths is walked once.

`info` describes one group or array: for a dataset its shape (and maximum shape), type, layout and chunks,
filters with their settings, fill value, the bytes it stores against its elements' size, and its attributes,
each with its type; for a Zarr array its chunks (shards, or a rectilinear grid's lengths), every codec with
its configuration (a shard's inside it, indented), fill value, dimension names, and chunk key encoding; for a
group its members, attributes, and (Zarr) whether it is consolidated; for an HDF5 file's root, its superblock
version and driver.

```
$ falcon info scan.zarr /run/temperature
/run/temperature
  object      array (Zarr v3)
  shape       (4, 6)
  type        float32
  chunks      (2, 6), 2 in all
  codecs      bytes(endian=little)
              gzip(level=4)
  fill value  "NaN"
  chunk keys  default (separator '/')
  attributes  1
  units  =  "K"
```

Types are numpy's names where numpy has one (`float32`, `uint16, big-endian`, `datetime64[D]`), else
HDF5's or Zarr's (`string(5 bytes, ascii)`, `vlen string (utf-8)`, `compound {id: int32, v: float64}`,
`null_terminated_bytes[2]`).

## dump

`dump` prints an array's values, or with `--attribute NAME` an attribute's:

```
$ falcon dump scan.h5 /cube --slice 1,::2,-2:
(1, 0, 2): 14, 15
(1, 2, 2): 22, 23
$ falcon dump -f csv scan.h5 /run/temperature -s :2,:3
0.0,0.1,0.2
0.6,0.7,0.8
$ falcon dump -f json scan.h5 /cube -s :,:2,:2
[[[0, 1],
  [4, 5]],
 [[12, 13],
  [16, 17]]]
```

- **`--slice`** selects as numpy indexes: per dimension an index (`5`, `-1` for the last), which drops the
  dimension, or a range `start:stop:step` whose parts may be left out (`:`, `10:`, `::4`) and whose start and
  stop may count from the end. Dimensions not given are taken whole; steps are positive.
- **`--format text`** (the default) prints rows, each line starting with the coordinates of its first value,
  wrapped at 80 columns. **`csv`** prints a value per line for one dimension and a row per line for two; for
  more, each row starts with its indices. **`json`** prints nested arrays, a row per line. A scalar, or a
  selection that indexes every dimension, prints its one value.
- **Values:** numbers as their shortest exact decimal (a float32's `0.1` as `0.1`), `NaN`, `Infinity`,
  `-Infinity`; unsigned 64-bit integers exactly; complex numbers as `1.0+2.0j` (JSON `[1.0, 2.0]`); strings
  quoted; byte strings as `b"..."` (JSON: the text if it is UTF-8, else base64); opaque bytes as `0x...`;
  numpy times as their date and time (`2020-01-01`), HDF5 times as instants; HDF5 enumerations as their names;
  compounds and structs as JSON objects; references as the paths they point at.

A dump streams: it reads about a million elements at a time, however large the array.

## convert

`convert <input> <output>` writes an HDF5 file as a Zarr store, or a Zarr store as an HDF5 file; the input
decides which. A Zarr output is a directory, a `.zip` archive, or an `s3://` prefix; an HDF5 output is a
local file, written to a temporary file and moved into place when it is complete. Each array is copied in
blocks of whole output chunks, several at once (`--threads`, by default up to 8), so a conversion needs
memory for a few blocks, not for the data.

```
$ falcon convert scan.h5 scan.zarr
/run/temperature  float32 (4, 6)  chunks (2, 6)  gzip:4
falcon: warning: left out /soft: a soft link (to /run/temperature); Zarr has no links
...
Wrote 15 arrays and 2 groups to scan.zarr (4 warnings above)
```

| Option | Default | |
|---|---|---|
| `--path P` | `/` | convert only this group or array; an array becomes the output's root array (or, to HDF5, a dataset named after it) |
| `--zarr-format 2\|3` | 3 | the Zarr format written |
| `-c`, `--compression` | `auto` | see below |
| `--chunks keep\|auto` | `keep` | keep the input's chunks where it has them, else choose them as h5py (HDF5) or zarr-python (Zarr) does; `auto` chooses them everywhere |
| `--consolidate` | | write the Zarr output's consolidated metadata |
| `--overwrite` | | replace the output if it exists (for a Zarr store, everything in it) |
| `-j`, `--threads N` | up to 8 | blocks converted at once |
| `-q`, `--quiet` | | list no arrays, only the summary |

**HDF5 to Zarr.** Groups become groups and datasets arrays, with their attributes as JSON (numbers,
strings, nested arrays by shape; compounds as objects; references as paths). Element types:

| HDF5 | Zarr |
|---|---|
| integers, IEEE floats (either byte order) | the same type and byte order, bytes as they are |
| h5py's booleans (an enum of `FALSE`, `TRUE`) | `bool` |
| h5py's complex (a compound of `r` and `i`), HDF5 2.0 complex | `complex64`, `complex128` |
| enumerations | their integer values (the names are not kept) |
| bit fields | unsigned integers |
| fixed and variable-length strings | `string` (variable-length UTF-8) |
| variable-length sequences of bytes | `variable_length_bytes` |
| opaque | `raw_bytes` |
| h5py's numpy times (opaque, tagged `NUMPY:<M8[ns]`) | `numpy.datetime64` / `numpy.timedelta64` |
| HDF5 times (Unix seconds) | `numpy.datetime64[s]` |
| compounds | `struct` (packed, little-endian; fixed strings as `null_terminated_bytes`) |
| an array element type, `int16[2]` of shape `(n,)` | an array of shape `(n, 2)` |
| other layouts: 12 bits of 16, bfloat16, x87 extended | the plain type their values fit (`int16`, `float32`, `float64`) |
| a scalar | an array of shape `()` |

Left out, each with a warning: soft, external, and user-defined links; a second link to an object already
written; committed datatypes; references; sequences of anything but bytes; null dataspaces; names Zarr
refuses (`__x`, `a.`). Fill values carry over where the bytes do.

**Zarr to HDF5.** Groups become groups and arrays datasets, chunked as the array's chunks (a sharded
array's sub-chunks), each the type h5py writes for the same numpy dtype, so h5py reads the file back as
zarr-python reads the store:

| Zarr | HDF5 |
|---|---|
| `bool` | h5py's boolean |
| integers, floats | the same type and byte order |
| `complex64`, `complex128` | h5py's compound of `r` and `i` |
| `string`, `fixed_length_utf32` | variable-length UTF-8 strings |
| `variable_length_bytes` | variable-length sequences of `uint8` |
| `null_terminated_bytes` (`S`) | fixed-length ASCII strings |
| `raw_bytes` (`V`), `r<N>` | opaque |
| `numpy.datetime64`, `numpy.timedelta64` | h5py's tagged opaque (`NUMPY:<M8[ns]`), which h5py reads as the dtype |
| `struct` | a compound of the same layout |

Attributes are JSON in Zarr. Each becomes the attribute h5py gives its value: a string a variable-length UTF-8
string, an integer an `int64` (`uint64` past it), another number a `float64`, a boolean h5py's boolean, a
rectangular array of one of these an attribute of its shape. Anything else (an object, `null`, a mixed or
ragged array, an empty one) is kept as its JSON text in a string attribute. A number's fill value carries
over, and blocks holding only the fill value are not written, so a sparse array stays sparse.

**Compression** (`-c`, `--compression`):

| Value | Zarr output | HDF5 output |
|---|---|---|
| `auto` (default) | the input's compressor where Zarr has it (gzip, zstd, Blosc, bzip2, LZ4 as Blosc's), else zstd | deflate, at the input's gzip level or 4: every HDF5 reader reads it, without plugins |
| `keep` | as `auto`, but an uncompressed input stays uncompressed | the input's compressor where HDF5 has it, as h5py and hdf5plugin write it (zstd, Blosc, bzip2, LZ4, LZF), else deflate |
| `none` | uncompressed | uncompressed |
| `gzip[:level]` (`deflate`, `zlib`) | gzip, level 0–9 (4) | deflate |
| `zstd[:level]` | zstd, level 1–22 (its default) | the zstd filter |
| `blosc[:cname[:clevel[:shuffle]]]` | Blosc: `blosclz`, `lz4`, `lz4hc`, `zlib`, `zstd`; clevel 0–9 (5); `noshuffle`, `shuffle`, `bitshuffle` (`lz4:5:shuffle`) | the Blosc filter |
| `bz2[:level]` (`bzip2`) | numcodecs' bz2, 1–9 (9) | the bzip2 filter |
| `lz4` | numcodecs' lz4 (v2), Blosc's LZ4 (v3) | the LZ4 filter |
| `lzf` | refused | h5py's LZF filter |

A lossy input (ZFP, SZ, scale-offset) is written losslessly: its values as they read, compressed as above.
Filters only HDF5 has (szip, n-bit) give way to the default.

## copy and consolidate

`copy <source> <target>` copies a Zarr store, or with `--path` one group or array of it, to another place:

- **As it is** (no other option): every key, byte for byte, so the copy is the source exactly, whatever its
  codecs and extensions: between directories, ZIP archives, S3, and from HTTP. A store that cannot list its
  keys (HTTP without `--http-listing`) is walked node by node.
- **Re-encoded**, with `--zarr-format`, `--compression` (as for `convert`; `keep` by default here), or
  `--chunks`: each group and array is created anew and its elements copied. Data type, fill value, byte order,
  attributes, and (v3) dimension names, shards, and rectilinear grids carry over; Zarr v2 has no shards, so a
  sharded array's sub-chunks become its chunks.

`--consolidate` writes the copy's consolidated metadata, `--overwrite` replaces what the target holds, and
`--threads` sets how many keys or blocks are copied at once.

`consolidate <store> [<path>]` writes a group's consolidated metadata: inside its `zarr.json` (v3), or a
v2 group's `.zmetadata`, as zarr-python writes them.

## conformance

`conformance --array_path=<array>` is the command line the Zarr community's conformance tests
([zarr-conformance-tests](https://github.com/Bisaloo/zarr-conformance-tests), the suite zarr-java runs too)
call: it reads every value of the Zarr array there and prints them, as `dump` does, and exits with 0 if it
could. The suite runs it as `java -jar falcon.jar conformance` on each of its arrays, in CI
(`.github/workflows/conformance.yml`) and locally:

```bash
mvn -pl cli -am package -DskipTests
bash tools/conformance/run_conformance.sh    # fetches the suite and bats into cli/target/conformance
python -I tools/conformance/check_values.py  # and compares every value with zarr-python's
```

The suite checks only the exit status; `check_values.py` reads each of its arrays with zarr-python too.

## S3 and HTTP options

Every command takes these:

| Option | |
|---|---|
| `--s3-region R` | the region requests first go to (default: `AWS_REGION`, then the profile's, then us-east-1); a bucket elsewhere is found anyway |
| `--s3-profile P` | the profile whose credentials and region to use (default: `AWS_PROFILE`, else `default`) |
| `--s3-endpoint URL` | S3-compatible storage (MinIO, Cloudflare R2, Google Cloud Storage's XML API): buckets addressed by path, checksums only where S3 requires them |
| `--s3-anonymous`, `--no-sign-request` | read a public bucket without credentials |
| `--header 'Name: value'` | an HTTP header for `http(s)://` sources, repeatable (`Authorization: Bearer ...`) |
| `--http-listing` | list an `http(s)://` Zarr store's groups from a static file server's directory pages |

Without these, S3 credentials come from the SDK's chain: system properties, environment variables
(`AWS_ACCESS_KEY_ID`, ...), `~/.aws` profiles, SSO, and container and instance roles. Writing to S3 (a
`convert` or `copy` target) needs credentials allowed to write. An HDF5 output is written locally; upload it
afterwards.

## Errors and exit status

The command exits with 0 when it succeeded, 1 when it failed (a missing file, a malformed store, an S3 error),
and 2 when the command line was wrong. An error is one line, `falcon <command>: <what went wrong>`; `-v`
adds its stack trace. Something left out of a conversion is a warning (`falcon: warning: ...`), and the
summary counts them; the command still succeeds.

## What is and isn't supported

**Checked:** `tools/fixtures/check_cli.py` converts files h5py 3.16 and zarr-python 3.4 write, in both
directions and through Zarr v2, v3, and ZIP, and reads every result back with them: values, types,
attributes, and fill values agree. The Zarr community's conformance tests pass (see
[conformance](#conformance)), with the values zarr-python reads.

**Not supported:**
- writing an HDF5 file to S3 or HTTP (write it locally, and upload it), and reading a ZIP archive from S3 or
  HTTP (copy it locally);
- in Zarr: links, references, sequences of anything but bytes, and enumeration names (see above);
- re-encoding lossy data (ZFP, SZ) in the same lossy codec: it is written losslessly;
- creating sharded arrays from HDF5 (shards carry over in a Zarr copy), and HDF5 dimension scales as Zarr
  dimension names.
