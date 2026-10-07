---
title: OME-Zarr how-to
description: Read, validate, and write OME-Zarr 0.4, 0.5, and 0.6 images, labels, plates, and scenes with Falcon's ome module.
---

[OME-Zarr](https://ngff.openmicroscopy.org/) (OME-NGFF) is Zarr with the bioimaging community's metadata: an
image stored as a resolution pyramid, its axes and the transformations that place it in physical space,
label images, high-content screening plates, and (in 0.6) scenes. The `ome` module
(`com.ebremer.falcon.ome`) reads, validates, and writes versions **0.4**, **0.5**, and **0.6**, from any Zarr
store.

```java
import com.ebremer.falcon.ome.*;
import com.ebremer.falcon.ome.metadata.*;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.store.*;
import java.nio.file.Path;
import java.util.List;
```

The [full OME-Zarr user guide](https://github.com/ebremer/falcon/blob/main/ome/USER_GUIDE.md) covers every
option.

## The versions

| Version | Zarr | Where the metadata is |
|---|---|---|
| 0.4 | v2 | at the top of the group's attributes, each part with its own `version` |
| 0.5 | v3 | under the attributes' `ome` key |
| 0.6 | v3 | under `ome`, with named coordinate systems, more transformations, and scenes |

Falcon reads all three and writes all three; it writes 0.5 unless you choose another.

## Reading

### Open an image

```java
OmeZarr.OmeGroup root = OmeZarr.open(FileSystemStore.openReadOnly(Path.of("slide.ome.zarr")));
System.out.println(root.version() + " " + root.kind());   // e.g. "0.5 image"

MultiscaleImage image = root.asImage();
List<Axis> axes = image.axes();            // e.g. c (channel), y and x (space, micrometer)
int levels = image.levelCount();
ZarrArray full = image.level(0);           // each level is a Zarr array
ZarrArray smallest = image.level(levels - 1);
double[] pixelSize = image.scale(0);       // along each axis, in the axes' units
double[] origin = image.translation(0);    // the first pixel's center
image.omero().ifPresent(o -> o.channels().forEach(c -> System.out.println(c.label() + " #" + c.color())));
```

Read the levels with the [Zarr module](zarr.md): `full.select(offset, shape).readInts()`, and so on.

### Open its label images

```java
for (String name : image.labelNames()) {
    MultiscaleImage label = image.label(name);
    ImageLabel colors = label.imageLabel().orElseThrow();
    ZarrArray mask = label.level(0);
}
```

### Open a plate, a collection, or a scene

```java
Plate plate = OmeZarr.open(store).asPlate();
MultiscaleImage field = plate.well("B", "3").image(0);       // the first field of view of well B3

ImageCollection collection = OmeZarr.open(store).asCollection();   // bioformats2raw
MultiscaleImage second = collection.image(1);
String omeXml = collection.omeXml().orElse(null);

Scene scene = OmeZarr.open(store).asScene();                 // 0.6
```

### Map pixels to physical space, and between images

```java
Transformation toPhysical = image.levelTransformation(0);
double[] micrometers = toPhysical.apply(new double[] {0, 100, 200});        // array indices to physical
double[] indices = toPhysical.inverse().orElseThrow().apply(micrometers);

// 0.6 scenes compose the transformations between any two coordinate systems
Transformation tileToWorld = scene.transformation(new CoordinateSystemRef("physical", "tile_1"),
        CoordinateSystemRef.named("world")).orElseThrow();
```

Every 0.6 transformation (scale, translation, affine, rotation, mapAxis, projectAxis, sequence, byDimension,
bijection, displacement and coordinate fields) applies to points, and inverts where it can. Parameters stored
in Zarr arrays are loaded when the image or scene is opened.

### Read images over HTTP or from S3

Any store works, so a public image opens straight from its URL:

```java
MultiscaleImage idr = OmeZarr.open(
        HttpStore.openReadOnly("https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001240.zarr")).asImage();
```

## Validating

```java
ValidationReport report = new OmeValidator().validate(store);   // the root group, and everything below
if (!report.isValid()) {
    report.errors().forEach(System.out::println);   // e.g. "error: labels/0: the label image has 4 levels ..."
}
report.warnings().forEach(System.out::println);
```

The validator checks what each version's JSON schemas check, and the rules of the specification's text they
cannot: axis order, scales of one value per axis, 0.6's level transformations, a well's path naming its row
and column, a rotation matrix that is a rotation, the levels' arrays matching the axes, label images with as
many levels as their image, and more. Errors are broken MUST rules, warnings SHOULD rules.

| Option | |
|---|---|
| `strict(true)` | also require the recommended fields the specification's strict schemas require |
| `metadataOnly(true)` | check the group's attributes alone, without opening the arrays and groups they name (quick over HTTP) |
| `validate(JsonObject)` | check one group's attributes, as a JSON document |

## Writing

### Write an image and its pyramid

```java
List<Axis> axes = List.of(Axis.channel("c"), Axis.space("y", "micrometer"), Axis.space("x", "micrometer"));
MultiscaleImageWriter writer = MultiscaleImageWriter.builder(OmeVersion.V0_5, axes)
        .name("slide")
        .pixelSize(1, 0.25, 0.25)
        .omero(Omero.of(List.of(Omero.Channel.of("DAPI", "0000FF", new Omero.Window(0, 65535, 0, 4000)))))
        .build();
MultiscaleImage written = writer.write(FileSystemStore.open(Path.of("slide.ome.zarr")), PixelSource.of(array));
```

The writer copies the full-resolution pixels from a `PixelSource` a block at a time, then makes each smaller
level from the one before: by default each pixel is the mean of a 2&times;2 block in y and x, until the
smallest level fits in 256 &times; 256. Each level's scale and translation are set so the levels line up. The
group's metadata is written last, so a failed write leaves nothing that looks complete.

| Builder | Default | |
|---|---|---|
| `pixelSize(...)`, `origin(...)` | 1, and 0 | the full-resolution pixel size, and the first pixel's center |
| `levels(n)`, `smallest(size)` | until the downsampled axes fit 256 | how many levels |
| `method(...)` | `Downsampling.MEAN` | `MEAN`, `NEAREST` (each block's first pixel), or `MODE` (its most frequent value) |
| `downsample(names...)`, `factor(n)` | the last two space axes, by 2 | what shrinks, and by how much |
| `chunks(...)`, `shards(...)` | 512 in y and x, 1 elsewhere; no shards | shards need 0.5 or 0.6 |
| `codec(b -> ...)` | zstd | each level's codecs, such as `b -> b.blosc()` |
| `threads(n)` | the processors | blocks made at once |

`PixelSource.of(zarrArray)` reads a Zarr array; for anything else, implement `PixelSource` (`shape()`,
`dataType()`, and `read(offset, shape)` returning little-endian bytes).

### Add a label image

```java
MultiscaleImageWriter labeller = MultiscaleImageWriter.builder(OmeVersion.V0_5, axes)
        .imageLabel(ImageLabel.of(List.of(new ImageLabel.LabelColor(1, new int[] {255, 0, 0, 128}))))
        .build();
labeller.writeLabel(written, "cells", PixelSource.of(segmentation));
```

The label image gets the image's levels, axes, and transformations, in its `labels` group. Labels are never
averaged: they take the first pixel of each block (or, with `MODE`, the most frequent).

### Create a plate or a scene

```java
Plate plate = OmeZarr.createPlate(store, OmeVersion.V0_5, plateMetadata);
Well well = OmeZarr.createWell(plate, "A/1", new WellMetadata(null,
        List.of(new WellMetadata.FieldOfView("0", null))));
writer.write(well.group(), "0", PixelSource.of(fieldOfView));

Scene created = OmeZarr.createScene(store, sceneMetadata);   // 0.6
```

## From the shell

```bash
falcon info slide.ome.zarr                          # axes, levels, pixel sizes, channels, labels
falcon ome validate slide.ome.zarr                  # errors and warnings; exit status 1 if invalid
falcon ome pyramid scan.h5 /image slide.ome.zarr --pixel-size 0.25,0.25 --channel-names DAPI,GFP
falcon ome pyramid segmentation.zarr / slide.ome.zarr --label cells
```

See [The falcon command](cli.md#ome-zarr-validate-and-pyramid).

## What it does not do

- versions before 0.4, and writing bioformats2raw collections (they are read);
- resampling images through transformations (they apply to points);
- writing an image from an array whose dimensions are out of OME-Zarr's order: an RGB image stored y, x, c must
  be transposed to c, y, x first;
- converting an image from one version to another.
