# Falcon OME-Zarr — User Guide

Falcon OME-Zarr reads, validates, and writes [OME-Zarr](https://ngff.openmicroscopy.org/) (OME-NGFF), the
bioimaging community's conventions for images in Zarr, versions **0.4**, **0.5**, and **0.6**:

- **multiscale images**: resolution pyramids, with their axes and the coordinate transformations that place
  them in physical space, and their `omero` rendering metadata;
- **label images**: segmentations stored beside an image, at the same resolutions;
- **high-content screening plates** and their wells;
- **bioformats2raw collections** of images;
- **scenes** (0.6): images placed relative to each other by transformations between coordinate systems.

It is the `ome` module, `com.ebremer.falcon.ome`, on Falcon's `zarr` module; like it, it depends on nothing beyond
`java.base`. Everything it reads, it reads from any Zarr store: a directory, a ZIP archive, HTTP, or Amazon S3
(through the `s3` module). From the shell, the `falcon` command (the `cli` module, see
[`../cli/USER_GUIDE.md`](../cli/USER_GUIDE.md)) describes OME-Zarr in `ls` and `info`, checks it with
`falcon ome validate`, and writes images with `falcon ome pyramid`.

- [Versions](#versions)
- [Reading](#reading)
- [Transformations and scenes](#transformations-and-scenes)
- [Validating](#validating)
- [Writing images](#writing-images)
- [Writing labels, plates, and scenes](#writing-labels-plates-and-scenes)
- [How it was checked](#how-it-was-checked)
- [What is and isn't supported](#what-is-and-isnt-supported)

## Versions

| | Zarr | Where the metadata is | What it adds |
|---|---|---|---|
| **0.4** | v2 | at the top of the group's attributes; each part has its own `version` | axes with types and units, scale and translation per level |
| **0.5** | v3 | under the attributes' `ome` key, with one `version` | arrays name their dimensions (`dimension_names`) |
| **0.6** | v3 | under `ome`, as 0.5 | named coordinate systems; affine, rotation, axis maps and projections, sequences, by-dimension, bijections, displacement and coordinate fields; scenes |

`OmeVersion` names them. Falcon reads all three, and writes all three; by default it writes **0.5**, which the
most tools read today. (`0.6rc0`, which 0.6's schemas also accept, reads as 0.6.) Versions before 0.4 are not
supported (a `ZarrUnsupportedException`), though 0.3's axes given as plain names are read.

## Reading

`OmeZarr.open` opens a group and tells what it is; `as…` views it as one kind:

```java
import com.ebremer.falcon.ome.*;
import com.ebremer.falcon.zarr.store.FileSystemStore;

OmeZarr.OmeGroup root = OmeZarr.open(FileSystemStore.openReadOnly(Path.of("slide.ome.zarr")));
root.version();          // OmeVersion.V0_5
root.kind();             // "image", "plate", "bioformats2raw collection", "scene", ...

MultiscaleImage image = root.asImage();
image.axes();                       // [c (channel), y (space, micrometer), x (space, micrometer)]
image.levelCount();                 // 6
ZarrArray full = image.level(0);    // the levels are Zarr arrays: read them with the zarr module
double[] pixel = image.scale(0);    // [1.0, 0.25, 0.25]: the pixel size along each axis
double[] origin = image.translation(2); // the physical position of level 2's first pixel's center
image.omero();                      // Optional<Omero>: channel names, colors, display windows
```

`scale(k)` and `translation(k)` reduce a level's transformations to `x * scale + translation`; in 0.4 and 0.5
they include the image's own transformations, which apply to every level. `levelTransformation(k)` gives the
transformation itself, to apply to points. Where the metadata lists several `multiscales`, the first is the
image, as the specification recommends; `multiscales()` lists them all, and `multiscale(name)` finds one.

**Labels.** `image.labelNames()` lists the image's label images (from its `labels` group), `image.label(name)`
opens one (a `MultiscaleImage` with `isLabel()` and `imageLabel()`: its colors, properties, and source), and a
label image's `sourceImage()` opens the image it labels.

**Plates.** `root.asPlate()` gives the rows, columns, wells, and acquisitions (`metadata()`), and
`plate.well("B", "3").image(0)` the first field of view of well B3. **Collections.** `root.asCollection()`
lists a bioformats2raw collection's images in its order (`imagePaths()`, from `OME/series` or the groups
`0`, `1`, ...), opens them (`image(i)`), and gives the OME-XML (`omeXml()`). **Metadata alone.**
`OmeMetadata.read(attributes)` reads any group's attributes into the records of
`com.ebremer.falcon.ome.metadata`; `OmeZarr.metadata(node)` does it for a node.

Reading is lenient: it needs only what it reads, and a part that cannot be read is an `OmeFormatException`
naming its place as a JSON pointer (such as `/ome/multiscales/0/datasets/1`). Whether the metadata is right is
the [validator's](#validating) to say.

## Transformations and scenes

Every transformation is a `Transformation`: `Identity`, `Scale`, `Translation`, `MapAxis`, `ProjectAxis`,
`Affine`, `Rotation`, `Sequence`, `ByDimension`, `Bijection`, `Displacements`, and `Coordinates` (and `Unknown`,
for a type Falcon does not know, kept as its JSON). Each `apply`s to points as the 0.6 specification defines,
and gives its `inverse()` where it has a closed form (scales, translations, rotations, invertible affines,
axis maps, sequences and by-dimension of invertible parts, bijections):

```java
Transformation t = image.levelTransformation(0);
double[] physical = t.apply(new double[] {0, 100, 200});     // array indices to micrometers
double[] index = t.inverse().orElseThrow().apply(physical);
```

Parameters stored in Zarr arrays (`path`) are loaded when an image or scene is opened: an affine's or
rotation's matrix, and a displacement or coordinate field, which `apply` interpolates linearly between its
samples (or takes the nearest, if its `interpolation` says so; cubic is done linearly), points beyond it
taking its edge's values.

A **scene** (`root.asScene()`) joins its images' coordinate systems and its own. `scene.transformation(from,
to)` finds the shortest path between two coordinate systems, along transformations and against those with an
inverse, through the images' own transformations too, and composes it:

```java
Scene scene = OmeZarr.open(store).asScene();
CoordinateSystemRef tile = new CoordinateSystemRef("physical", "tile_1");  // a system of the image tile_1
Transformation toWorld = scene.transformation(tile, CoordinateSystemRef.named("world")).orElseThrow();
scene.transformation("tile_1", 0, CoordinateSystemRef.named("world"));     // from level 0's array indices
```

## Validating

`OmeValidator` checks metadata against its version's specification: what its JSON schemas check, and the
rules they cannot express. Among them: axis names unique, at most one time and one channel axis, and the axes
in order (time, channel, space); one scale per level (0.4, 0.5), of one value per axis; 0.6's level
transformations (a scale, an identity, or a scale then a translation, from the level's own path to one
coordinate system for every level); each transformation's parameters fitting its coordinate systems (a
rotation orthonormal with determinant 1, an affine of M rows of N+1, every output axis of a byDimension in one
part, ...); the coordinate systems all joined by transformations; a well's path naming its row and column; label
values unique and integers.

```java
ValidationReport report = new OmeValidator().validate(store);     // the root group and everything below
report.isValid();      // no errors
report.errors();       // MUST rules broken: "error: labels/0#/image-label/colors: ..."
report.warnings();     // SHOULD rules: a missing name, an axis with no unit, ...
```

A group is checked with the **hierarchy below it**: each level's array (stored, its number of dimensions, its
data type the same as the others' (0.6), its `dimension_names` matching the axes (0.5), the levels shrinking),
the Zarr format matching the version, the version the same throughout, label images (integer values, as many
levels as their image), a plate's wells and their fields (each field's acquisition one of the plate's), a
collection's images, a scene's images and the coordinate systems it names in them. `metadataOnly(true)` checks
the group's attributes alone; `validate(JsonObject)` checks an attributes document.

`strict(true)` makes errors of the recommended fields the specification's strict schemas require: an image's
`name`, `type`, and `metadata`; a label image's `colors`; a plate's `name`; an acquisition's `name` and
`maximumfieldcount`; and in 0.4, each part's `version`.

Over HTTP or S3, the hierarchy is read node by node: a plate of hundreds of fields takes a while, where
`metadataOnly` takes a moment.

## Writing images

`MultiscaleImageWriter` writes an image from its full-resolution pixels, making each smaller level from the
one before:

```java
List<Axis> axes = List.of(Axis.channel("c"), Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));
MultiscaleImageWriter writer = MultiscaleImageWriter.builder(OmeVersion.V0_5, axes)
        .name("slide")
        .pixelSize(1, 0.25, 0.25)
        .omero(Omero.of(List.of(Omero.Channel.of("DAPI", "0000FF", new Omero.Window(0, 65535, 0, 4000)))))
        .build();
MultiscaleImage image = writer.write(FileSystemStore.open(Path.of("slide.ome.zarr")), PixelSource.of(array));
```

The pixels come from a `PixelSource`: `PixelSource.of(zarrArray)` reads a Zarr array of any byte order, and any
other source implements `shape()`, `dataType()` (boolean, integer, or floating point), and `read(offset, shape)`,
a box at a time in little-endian bytes, so that no image needs to fit in memory. (`falcon ome pyramid` reads HDF5
datasets this way.)

| Builder | Default | |
|---|---|---|
| `pixelSize(...)`, `origin(...)` | 1, and 0 | level 0's pixel size, and its first pixel's center |
| `downsample(names...)` | the last two space axes | the axes that shrink, by `factor(n)` (2) |
| `levels(n)`, `smallest(size)` | until the downsampled axes are at most 256 | how many levels |
| `method(...)` | `MEAN` | `MEAN` (rounded half up for integers), `NEAREST` (each block's first pixel), or `MODE` (its most frequent value) |
| `chunks(...)`, `shards(...)` | 512 along the downsampled axes (128 for three of them), 1 along the others; no shards | shards are Zarr v3 (0.5, 0.6) |
| `codec(b -> ...)` | zstd | sets each level's codecs on its `ArraySpec.Builder`, such as `b -> b.blosc()` |
| `threads(n)` | the processors | blocks made at once |
| `progress(shape -> ...)` | none | called as each level is written |

The levels are named `0`, `1`, ...; each is `ceil(n / 2)` of the one before along the downsampled axes, so no
pixel is dropped (a partial block at the far edge is made from the pixels it has). Each level's `scale` is its
pixel size, and its `translation` the position of its first pixel's center: half a block in, for `MEAN` and
`MODE`, whose pixels sit at their blocks' centers, as the 0.6 specification describes for binning. The
multiscale records the method (`type`) and the factors (`metadata`). Writes are parallel, block by block (whole
chunks, or shards); the group's metadata is written last, so a write that fails part way leaves nothing a
reader would take for a complete image. `write(store, source, true)` replaces what the store holds.

## Writing labels, plates, and scenes

**Labels.** `writer.writeLabel(image, "cells", PixelSource.of(segmentation))` writes integer labels as a label
image of `image`: the same number of levels, of the same shapes (a dimension of extent 1 stays 1), with the
image's transformations, in its `labels` group (made, or added to); `imageLabel(...)` on the builder gives it
colors and properties. Labels are never averaged: `MEAN` is `NEAREST` for them, and `MODE` may be asked for.

**Plates.** `OmeZarr.createPlate(store, version, plateMetadata)` writes the plate's metadata and its row
groups; `OmeZarr.createWell(plate, "B/3", wellMetadata)` each well; and a `MultiscaleImageWriter` each field
of view into `well.group()`. **Scenes.** `OmeZarr.createScene(store, sceneMetadata)` writes a 0.6 scene, and a
writer its images into `scene.group()`, at the paths its transformations name. Bioformats2raw collections,
being transitional, are read but not written.

Metadata alone is written by `OmeMetadata.toAttributes(attributes)`, which lays it out as its version does and
keeps the attributes' other members: `node.setAttributes(metadata.toAttributes(node.attributes()))`.

## How it was checked

- **The specification's own conformance tests**: 0.6's ([ngff-spec](https://github.com/ome/ngff-spec), through
  its `ome_zarr_conformance.py`, in both its modes) and the 0.4 and 0.5 JSON schema test suites
  ([ngff](https://github.com/ome/ngff) 0.5.2), run by `tools/conformance/run_ome_conformance.py` against
  `falcon ome validate --json`, and in CI (`.github/workflows/ome-conformance.yml`). Of 403 tests, Falcon agrees
  with 368. The other 35 are the suites' own errors: test data marked valid that breaks a rule of the
  specification's text which the schemas do not check, which ome-zarr-models (the community's Python validator)
  rejects too:
  - plates whose rows and columns are swapped, so a well's path does not name its row and column (20 tests: 5
    in each of 0.4 and 0.5, and in each of 0.6's two modes);
  - a scale of 2 values for 3 axes (3: in 0.4, 0.5, and 0.6's attributes mode);
  - a level whose path is `1` and whose transformation's input is `s1`, and a byDimension that leaves an output
    axis out (2, in 0.6's attributes mode);
  - 0.6's image tests in its hierarchy mode, which still give the draft's string `input` and `output`; 0.6's own
    schemas reject them (10, two of which also break one of the rules above).
- **ome-zarr-py's own output**: images, labels, plates, and a bioformats2raw collection it writes in 0.4 and 0.5
  are the module's test fixtures (`tools/fixtures/gen_ome_fixtures.py`), with a 0.6 scene made from the
  specification's examples (with a rotation stored in an array, and a displacement field). One finding:
  ome-zarr-py 0.21 writes a 0.5 `labels` group without its `version`, which Falcon (and ome-zarr-models) report.
- **Falcon's output**: `tools/fixtures/check_ome.py` runs `falcon ome pyramid` in each version on arrays of
  several types and axes (zarr-python's, and h5py's big-endian ones), validates every image and label image
  with ome-zarr-models, reads them with ome-zarr-py (0.4 and 0.5), and compares every level with the pyramid
  numpy makes: all equal.
- **Hostile metadata**: every value of documents of each kind and version replaced, in turn, by every kind of
  JSON value (`RobustnessTest`): the validator reports, and never throws; the reader fails only with
  `OmeFormatException`.
- **Public data**: 0.4 and 0.5 images and a 0.4 plate from the Image Data Resource, read over HTTP. Validating
  them finds what the specification forbids and its schemas cannot see: a label image with one level more than
  its image (which ome-zarr-models, checking each group alone, accepts), and empty `colors` lists.

## What is and isn't supported

**Supported:** reading, validating, and writing 0.4, 0.5, and 0.6 images, label images, plates, wells, and 0.6
scenes, from and to any Zarr store; reading bioformats2raw collections; every 0.6 transformation, applied to
points, inverted where it can be, and with parameters stored in arrays; pyramids of boolean, integer, and
floating-point images of 2 to 5 dimensions.

**Not supported:**
- versions before 0.4, and writing bioformats2raw collections;
- applying transformations to images (resampling): they apply to points;
- writing an image from an array whose dimensions are out of OME-Zarr's order (an RGB image stored y, x, c
  must be transposed to c, y, x first);
- converting an image from one version to another (copy the arrays and write the metadata anew).
