"""Checks the OME-Zarr images falcon.jar writes against the reference tools.

Dev-time tool only -- not a Falcon dependency. Needs (tools/fixtures/requirements.txt): ome-zarr (ome-zarr-py, the
reference reader), ome-zarr-models (the community's validator), zarr, numpy, and h5py.

    mvn -pl cli -am package -DskipTests
    python tools/fixtures/check_ome.py [work directory]

For each OME-Zarr version (0.4, 0.5, 0.6) and each input -- arrays zarr-python and h5py write, of several data
types and axes -- it runs `falcon ome pyramid`, then:
- validates the image (and its label image) with ome-zarr-models;
- reads every level with zarr-python and compares it with the pyramid numpy makes the same way: each pixel the
  mean of its 2x2 block (rounded half up for integers), partial blocks at the far edges from their pixels;
- reads the image and its labels with ome-zarr-py (which reads 0.4 and 0.5).
It prints one line per check and exits with 1 if any fails.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile
import warnings

import h5py
import numpy as np
import zarr

zarr.config.set({"async.concurrency": 1})
warnings.simplefilter("ignore")
import ome_zarr_models  # noqa: E402
from ome_zarr.io import parse_url  # noqa: E402
from ome_zarr.reader import Reader  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAR = ROOT / "cli" / "target" / "falcon.jar"
failures = 0


def report(ok, what):
    global failures
    failures += 0 if ok else 1
    print(("ok   " if ok else "FAIL ") + what)


def falcon(*args):
    run = subprocess.run(["java", "-jar", str(JAR), *map(str, args)], capture_output=True, text=True)
    if run.returncode != 0:
        raise RuntimeError(f"falcon {' '.join(map(str, args))}: {run.stderr.strip()}")
    return run.stdout


def downsample(a, axes):
    """numpy's pyramid level: 2x2 means over the axes given, rounded half up for integers."""
    out = a.astype(np.float64) if a.dtype.kind == "f" else a.astype(np.int64)
    counts = np.ones_like(out, dtype=np.int64)
    for ax in axes:
        n = out.shape[ax]
        if n % 2:
            pad = [(0, 0)] * out.ndim
            pad[ax] = (0, 1)
            out = np.pad(out, pad)
            counts = np.pad(counts, pad)
        shape = list(out.shape)
        shape[ax:ax + 1] = [shape[ax] // 2, 2]
        out = out.reshape(shape).sum(axis=ax + 1)
        counts = counts.reshape(shape).sum(axis=ax + 1)
    if a.dtype.kind == "f":
        return (out / counts).astype(a.dtype)
    if a.dtype.kind == "b":
        return (2 * out >= counts)
    return np.floor_divide(2 * out + counts, 2 * counts).astype(a.dtype)


def inputs(work):
    """(name, location, path, array, axes, options) for each input."""
    rng = np.random.default_rng(7)
    cases = []
    store = work / "inputs.zarr"
    g = zarr.open_group(str(store), mode="w")
    rgb = rng.integers(0, 256, size=(3, 301, 517), dtype=np.uint8)
    g.create_array("rgb", data=rgb, chunks=(1, 128, 128), dimension_names=["c", "y", "x"])
    cases.append(("uint8 cyx", store, "rgb", rgb, "cyx", ["--pixel-size", "0.25,0.25", "--channel-names", "R,G,B"]))
    signed = rng.integers(-30000, 30000, size=(97, 63), dtype=np.int16)
    g.create_array("signed", data=signed, chunks=(32, 32))
    cases.append(("int16 yx", store, "signed", signed, "yx", ["--levels", "4"]))
    volume = rng.random((2, 2, 5, 40, 33), dtype=np.float32)
    g.create_array("volume", data=volume, chunks=(1, 1, 5, 40, 33))
    cases.append(("float32 tczyx", store, "volume", volume, "tczyx", ["--levels", "3", "--time-unit", "second"]))
    h5 = work / "inputs.h5"
    with h5py.File(h5, "w") as f:
        big = (np.arange(130 * 90, dtype=np.uint16).reshape(130, 90) * 7) % 65535
        f.create_dataset("big_endian", data=big.astype(">u2"), chunks=(50, 50))
    cases.append(("big-endian uint16 yx (HDF5)", h5, "big_endian", big, "yx", ["--levels", "3", "-c", "gzip:4"]))
    return cases


def check(work, version, name, location, path, array, axes, options):
    out = work / f"{version}-{name.split()[0]}-{name.split()[1]}.ome.zarr"
    shutil.rmtree(out, ignore_errors=True)
    extra = list(options)
    if version != "0.4" and name.startswith("uint8"):
        extra += ["--shards", "1,256,256", "--chunks", "1,128,128"]
    falcon("ome", "pyramid", location, path, out, "--ome-version", version, "-q", *extra)
    label = None
    if name.startswith("uint8"):
        labels = (array[:1] > 128).astype(np.uint8)
        lstore = work / "labels.zarr"
        zarr.open_group(str(lstore), mode="w").create_array("l", data=labels, dimension_names=["c", "y", "x"])
        falcon("ome", "pyramid", lstore, "l", out, "--label", "bright", "-q")
        label = labels
    try:
        model = ome_zarr_models.open_ome_zarr(str(out), version=version)
        report(True, f"{version} {name}: ome-zarr-models reads it as {type(model).__name__}")
    except Exception as e:  # noqa: BLE001
        report(False, f"{version} {name}: ome-zarr-models: {str(e)[:600]}")
    if label is not None:
        try:
            m = ome_zarr_models.open_ome_zarr(str(out / "labels" / "bright"), version=version)
            report(True, f"{version} {name}: ome-zarr-models reads its label image as {type(m).__name__}")
        except Exception as e:  # noqa: BLE001
            report(False, f"{version} {name}: ome-zarr-models label: {str(e)[:600]}")
    group = zarr.open_group(str(out), mode="r")
    ome = group.attrs["ome"] if version != "0.4" else dict(group.attrs)
    paths = [d["path"] for d in ome["multiscales"][0]["datasets"]]
    spatial = [i for i, a in enumerate(axes) if a in "yx"]
    level = array
    same = True
    for k, p in enumerate(paths):
        got = group[p][...]
        if got.shape != level.shape or not np.array_equal(got, level):
            same = False
            report(False, f"{version} {name}: level {k} differs from numpy's ({got.shape} vs {level.shape})")
            break
        level = downsample(level, spatial)
    if same:
        report(True, f"{version} {name}: {len(paths)} levels equal numpy's pyramid")
    if label is not None:
        lg = zarr.open_group(str(out / "labels" / "bright"), mode="r")
        ok = np.array_equal(lg["0"][...], label) and lg["1"].shape == group["1"].shape[:0] + (1,) + group["1"].shape[1:]
        report(ok, f"{version} {name}: the label image's levels match the image's")
    if version != "0.6":
        nodes = list(Reader(parse_url(str(out)))())
        image = nodes[0]
        read_axes = [a["name"] for a in image.metadata["axes"]]
        ok = read_axes == list(axes) and len(image.data) == len(paths) and np.array_equal(np.asarray(image.data[0]), array)
        report(ok, f"{version} {name}: ome-zarr-py reads axes {read_axes}, {len(image.data)} levels")
        if label is not None:
            label_nodes = [n for n in nodes if n.metadata.get("name") == "bright" or "labels" in str(n.zarr)]
            report(bool(label_nodes), f"{version} {name}: ome-zarr-py finds the label image")


def main():
    if not JAR.is_file():
        sys.exit(f"no {JAR}: build it first, with mvn -pl cli -am package -DskipTests")
    work = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(tempfile.mkdtemp(prefix="check_ome_"))
    work.mkdir(parents=True, exist_ok=True)
    cases = inputs(work)
    for version in ("0.4", "0.5", "0.6"):
        for case in cases:
            try:
                check(work, version, *case)
            except Exception as e:  # noqa: BLE001
                report(False, f"{version} {case[0]}: {e}")
    print(f"{failures} failures")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
