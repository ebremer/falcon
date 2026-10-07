"""Generates the OME-Zarr fixtures the ome module's tests read: images, labels, plates, and a bioformats2raw
collection written by ome-zarr-py (the reference implementation) in 0.4 and 0.5, and a 0.6 scene written
with zarr-python from the 0.6 specification's examples (ome-zarr-py does not write 0.6).

Dev-time tool only -- not a Falcon dependency. Install with: pip install -r tools/fixtures/requirements.txt
Run from the repo root:  python tools/fixtures/gen_ome_fixtures.py

The fixtures go to ome/src/test/resources/fixtures/<name>.ome.zarr. Their values follow simple formulas the
tests compute again (see each writer below), so no sidecar is needed.
"""
import json
import os
import shutil

import numpy as np
import zarr
from ome_zarr.format import FormatV04, FormatV05
from ome_zarr.writer import add_metadata, write_image, write_labels, write_plate_metadata, write_well_metadata

zarr.config.set({"async.concurrency": 1})

OUT = os.path.join("ome", "src", "test", "resources", "fixtures")
AXES = [{"name": "c", "type": "channel"}, {"name": "y", "type": "space", "unit": "micrometer"},
        {"name": "x", "type": "space", "unit": "micrometer"}]
UNITS = {"y": "micrometer", "x": "micrometer"}  # ome-zarr-py takes the units from here, not from AXES
OMERO = {"channels": [
    {"label": "DAPI", "color": "0000FF", "active": True, "window": {"min": 0, "max": 65535, "start": 0, "end": 4000}},
    {"label": "GFP", "color": "00FF00", "active": True, "window": {"min": 0, "max": 65535, "start": 0, "end": 4000}}],
    "rdefs": {"model": "color"}}


def fresh(name):
    path = os.path.join(OUT, name)
    shutil.rmtree(path, ignore_errors=True)
    return path


def image_values(c, h, w):
    """value = c*1000 + y*50 + x."""
    return (np.arange(c).reshape(c, 1, 1) * 1000 + np.arange(h).reshape(1, h, 1) * 50
            + np.arange(w).reshape(1, 1, w)).astype(np.uint16)


def label_values(h, w):
    """1 inside the rectangle y in [10, 30), x in [10, 40); 2 at y in [0, 5), x in [0, 5); 0 elsewhere."""
    a = np.zeros((1, h, w), dtype=np.uint8)
    a[0, 10:30, 10:40] = 1
    a[0, 0:5, 0:5] = 2
    return a


def image(fmt):
    """A cyx uint16 image, 2 x 40 x 50, two levels, pixels 0.5 micrometer, with omero and a label image."""
    g = zarr.open_group(fresh(f"v{fmt.version.replace('.', '')}_image.ome.zarr"), mode="w",
                        zarr_format=fmt.zarr_format)
    write_image(image_values(2, 40, 50), g, axes=AXES, fmt=fmt, scale_factors=(2,), name="cells",
                scale={"c": 1.0, "y": 0.5, "x": 0.5}, axes_units=UNITS)
    add_metadata(g, {"omero": OMERO}, fmt=fmt)
    write_labels(label_values(40, 50), g, name="cells", axes=AXES, fmt=fmt, scale_factors=(2,),
                 scale={"c": 1.0, "y": 0.5, "x": 0.5}, axes_units=UNITS,
                 label_metadata={"colors": [{"label-value": 1, "rgba": [255, 0, 0, 255]},
                                            {"label-value": 2, "rgba": [0, 255, 0, 255]}],
                                 "properties": [{"label-value": 1, "class": "cell"}]})


def plate(fmt):
    """A plate of one row (A) and two columns (1, 2), each well one field, a yx uint8 image 16 x 20:
    value = (well * 100 + y * 20 + x) % 256, well 0 for A/1 and 1 for A/2."""
    g = zarr.open_group(fresh(f"v{fmt.version.replace('.', '')}_plate.ome.zarr"), mode="w",
                        zarr_format=fmt.zarr_format)
    write_plate_metadata(g, ["A"], ["1", "2"], ["A/1", "A/2"], name="screen", field_count=1, fmt=fmt,
                         acquisitions=[{"id": 0, "name": "run", "maximumfieldcount": 1}])
    for i, path in enumerate(["A/1", "A/2"]):
        well = g.require_group(path)
        write_well_metadata(well, [{"path": "0", "acquisition": 0}], fmt=fmt)
        values = ((i * 100 + np.arange(16).reshape(16, 1) * 20 + np.arange(20).reshape(1, 20)) % 256).astype(np.uint8)
        write_image(values, well.require_group("0"), axes="yx", fmt=fmt, scale_factors=(), name=f"field {path}")


def collection():
    """A bioformats2raw collection (0.4): images 0 (yx uint8 16 x 20, value y + x) and 1 (8 x 10, value
    y * 10 + x), the OME group's series, and an OME-XML file."""
    fmt = FormatV04()
    root = fresh("v04_bf2raw.ome.zarr")
    g = zarr.open_group(root, mode="w", zarr_format=2)
    g.attrs["bioformats2raw.layout"] = 3
    write_image((np.arange(16).reshape(16, 1) + np.arange(20).reshape(1, 20)).astype(np.uint8), g.require_group("0"),
                axes="yx", fmt=fmt, scale_factors=(), name="first")
    write_image((np.arange(8).reshape(8, 1) * 10 + np.arange(10).reshape(1, 10)).astype(np.uint8),
                g.require_group("1"), axes="yx", fmt=fmt, scale_factors=(), name="second")
    ome = g.require_group("OME")
    ome.attrs["series"] = ["0", "1"]
    with open(os.path.join(root, "OME", "METADATA.ome.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n'
                '<OME xmlns="http://www.openmicroscopy.org/Schemas/OME/2016-06">'
                '<Image ID="Image:0" Name="first"><Pixels ID="Pixels:0" DimensionOrder="XYZCT" Type="uint8" '
                'SizeX="20" SizeY="16" SizeZ="1" SizeC="1" SizeT="1"><MetadataOnly/></Pixels></Image>'
                '<Image ID="Image:1" Name="second"><Pixels ID="Pixels:1" DimensionOrder="XYZCT" Type="uint8" '
                'SizeX="10" SizeY="8" SizeZ="1" SizeC="1" SizeT="1"><MetadataOnly/></Pixels></Image></OME>\n')


def v06_image(group, name, values, scale):
    """A 0.6 yx image with one level, s0, mapped to its 'physical' coordinate system by a scale."""
    group.create_array("s0", data=values, chunks=values.shape, dimension_names=["y", "x"])
    group.attrs["ome"] = {"version": "0.6", "multiscales": [{
        "name": name,
        "coordinateSystems": [{"name": "physical", "axes": [
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"}]}],
        "datasets": [{"path": "s0", "coordinateTransformations": [
            {"type": "scale", "scale": scale, "input": {"path": "s0"}, "output": {"name": "physical"}}]}]}]}


def scene():
    """A 0.6 scene of two tiles stitched into 'world', as the specification's tile-stitching example:
    tile_0 (yx uint8 10 x 20, value y * 20 + x, pixels 0.5) at the origin and tile_1 (same, value 200 - y * 20 - x)
    10 micrometers along x. tile_1 also maps to 'rotated' by a rotation stored in an array, and tile_0 to
    'warped' by a displacement field: a multiscale image (d, y, x) = (2, 3, 4), pixels 5 micrometers, whose
    displacement at grid point (i, j) is (i, 10 * j)."""
    root = fresh("v06_scene.ome.zarr")
    g = zarr.open_group(root, mode="w", zarr_format=3)
    g.attrs["ome"] = {"version": "0.6", "scene": {
        "coordinateSystems": [{"name": "world", "axes": [
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"}]}],
        "coordinateTransformations": [
            {"type": "translation", "translation": [0, 0], "name": "tile_0 to world",
             "input": {"path": "tile_0", "name": "physical"}, "output": {"name": "world"}},
            {"type": "translation", "translation": [0, 10], "name": "tile_1 to world",
             "input": {"path": "tile_1", "name": "physical"}, "output": {"name": "world"}}]}}
    tile0 = g.require_group("tile_0")
    v06_image(tile0, "tile_0", (np.arange(10).reshape(10, 1) * 20 + np.arange(20).reshape(1, 20)).astype(np.uint8),
              [0.5, 0.5])
    tile1 = g.require_group("tile_1")
    v06_image(tile1, "tile_1", (200 - np.arange(10).reshape(10, 1) * 20 - np.arange(20).reshape(1, 20)).astype(np.uint8),
              [0.5, 0.5])
    # tile_1: a rotation, stored in an array, from physical to rotated
    tile1.create_array("rotationParams", data=np.array([[0.0, 1.0], [-1.0, 0.0]]), chunks=(2, 2))
    attrs = tile1.attrs["ome"]
    ms = attrs["multiscales"][0]
    ms["coordinateSystems"].append({"name": "rotated", "axes": [
        {"name": "y", "type": "space", "unit": "micrometer"}, {"name": "x", "type": "space", "unit": "micrometer"}]})
    ms["coordinateTransformations"] = [{"type": "rotation", "path": "rotationParams", "name": "rotate",
                                        "input": {"name": "physical"}, "output": {"name": "rotated"}}]
    tile1.attrs["ome"] = attrs
    # tile_0: a displacement field from physical to warped
    field = tile0.require_group("coordinateTransformations").require_group("field")
    i = np.arange(3).reshape(3, 1)
    j = np.arange(4).reshape(1, 4)
    vectors = np.stack([np.broadcast_to(i, (3, 4)), np.broadcast_to(10 * j, (3, 4))]).astype(np.float64)
    field.create_array("s0", data=vectors, chunks=vectors.shape, dimension_names=["d", "y", "x"])
    field.attrs["ome"] = {"version": "0.6", "multiscales": [{
        "name": "field",
        "coordinateSystems": [{"name": "physical", "axes": [
            {"name": "d", "type": "displacement", "discrete": True},
            {"name": "y", "type": "space", "unit": "micrometer"},
            {"name": "x", "type": "space", "unit": "micrometer"}]}],
        "datasets": [{"path": "s0", "coordinateTransformations": [
            {"type": "scale", "scale": [1, 5, 5], "input": {"path": "s0"}, "output": {"name": "physical"}}]}]}]}
    attrs = tile0.attrs["ome"]
    ms = attrs["multiscales"][0]
    ms["coordinateSystems"].append({"name": "warped", "axes": [
        {"name": "y", "type": "space", "unit": "micrometer"}, {"name": "x", "type": "space", "unit": "micrometer"}]})
    ms["coordinateTransformations"] = [{"type": "displacements", "path": "coordinateTransformations/field",
                                        "interpolation": "linear", "name": "warp",
                                        "input": {"name": "physical"}, "output": {"name": "warped"}}]
    tile0.attrs["ome"] = attrs


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for fmt in (FormatV04(), FormatV05()):
        image(fmt)
        plate(fmt)
    collection()
    scene()
    for name in sorted(os.listdir(OUT)):
        print(name)
