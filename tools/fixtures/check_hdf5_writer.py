#!/usr/bin/env python3
"""Checks that the reference library reads what Falcon's HDF5 writer writes.

Falcon's own round-trip tests (WriteTest) only prove the writer and reader agree with each other. This
script exports the writer's feature matrix (WriterInteropExport: one file per area plus a manifest of
expected values) and reads every object back with h5py -- HDF5 2.0, and optionally an HDF5 1.14 build
in a second interpreter -- decoding szip chunks with libaec (h5py ships szip disabled).

    python tools/fixtures/check_hdf5_writer.py                      # export via Maven, check with this python
    python tools/fixtures/check_hdf5_writer.py --python114 PATH     # ...and also with an HDF5 1.14 h5py
    python tools/fixtures/check_hdf5_writer.py --dir DIR            # check an existing export

Requirements: tools/fixtures/requirements.txt (h5py, numpy, imagecodecs). Dev-time only, like the
fixture generators; exits non-zero if anything fails.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

import h5py
import numpy as np

try:
    import imagecodecs  # libaec, for szip chunks
except ImportError:  # pragma: no cover - the 1.14 environment may lack it
    imagecodecs = None

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def export(directory):
    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    cmd = [mvn, "-q", "-pl", "hdf5", "test", "-Dtest=WriterInteropExport",
           "-Dsurefire.failIfNoSpecifiedTests=false", f"-Dfalcon.interop.dir={directory}"]
    subprocess.run(cmd, cwd=ROOT, check=True)


def hdf5_version():
    return tuple(int(p) for p in h5py.version.hdf5_version.split(".")[:2])


def szip_values(ds):
    """Reassembles an szip dataset from its raw chunks, decoding each with libaec's SZ layer."""
    plist = ds.id.get_create_plist()
    cd = None
    for i in range(plist.get_nfilters()):
        code, _flags, values, _name = plist.get_filter(i)
        if code == h5py.h5z.FILTER_SZIP:
            cd = values
    assert cd is not None, "no szip filter in the pipeline"
    nfilters = plist.get_nfilters()
    chunks = ds.chunks
    out = np.empty(ds.shape, dtype=ds.dtype)
    for index in np.ndindex(*[-(-n // c) for n, c in zip(ds.shape, chunks)]):
        start = tuple(i * c for i, c in zip(index, chunks))
        mask, raw = ds.id.read_direct_chunk(start)
        data = bytes(raw)
        for f in reversed(range(nfilters)):  # undo the pipeline (szip, possibly after shuffle)
            if mask & (1 << f):
                continue
            code = plist.get_filter(f)[0]
            if code == h5py.h5z.FILTER_SZIP:
                data = bytes(imagecodecs.szip_decode(data, options_mask=cd[0], pixels_per_block=cd[1],
                                                     bits_per_pixel=cd[2], pixels_per_scanline=cd[3],
                                                     header=True))
            elif code == h5py.h5z.FILTER_SHUFFLE:
                size = ds.dtype.itemsize
                data = np.frombuffer(data, np.uint8).reshape(size, -1).T.tobytes()
            else:
                raise AssertionError(f"unexpected filter {code} in an szip dataset")
        block = np.frombuffer(data, dtype=ds.dtype).reshape(chunks)
        region = tuple(slice(s, min(s + c, n)) for s, c, n in zip(start, chunks, ds.shape))
        out[region] = block[tuple(slice(0, r.stop - r.start) for r in region)]
    return out


def as_text(values):
    return [v.decode("utf-8") if isinstance(v, bytes) else str(v) for v in values]


def check_object(f, obj):
    path = obj["path"]
    if path not in f:
        raise AssertionError("missing")
    item = f[path]
    if obj["kind"] == "group":
        assert isinstance(item, h5py.Group), "not a group"
        if "children" in obj:
            assert sorted(item.keys()) == sorted(obj["children"]), "children differ"
    else:
        assert isinstance(item, h5py.Dataset), "not a dataset"
        if "shape" in obj:
            assert list(item.shape) == obj["shape"], f"shape {item.shape} != {obj['shape']}"
        data = szip_values(item) if obj.get("szip") else item[()]
        if "values" in obj:
            expected = obj["values"]
            flat = np.asarray(data).ravel()
            if obj.get("complex"):
                pairs = np.column_stack([flat.real, flat.imag]).ravel()
                assert np.array_equal(pairs, np.asarray(expected, dtype=float)), "complex values differ"
            elif expected and isinstance(expected[0], str):
                assert as_text(flat) == expected, f"strings {as_text(flat)} != {expected}"
            else:
                want = np.asarray(expected, dtype=np.float64 if flat.dtype.kind == "f" else flat.dtype)
                got = flat.astype(np.float64) if flat.dtype.kind == "f" else flat
                assert np.array_equal(got, want), f"values differ: {got[:8]} vs {want[:8]}"
        if "fields" in obj:
            for name, expected in obj["fields"].items():
                assert np.array_equal(data[name].ravel(), np.asarray(expected)), f"field {name} differs"
        if "rows" in obj:
            assert [list(r) for r in data] == obj["rows"], "vlen rows differ"
        if "refs" in obj:
            names = [f[r].name if r else None for r in data]
            assert names == obj["refs"], f"references {names} != {obj['refs']}"
    for name, expected in obj.get("attrs", {}).items():
        actual = np.asarray(item.attrs[name]).ravel()
        assert np.array_equal(actual, np.asarray(expected, dtype=actual.dtype)), f"attribute {name} differs"


def check(directory):
    with open(os.path.join(directory, "manifest.json"), encoding="utf-8") as fh:
        manifest = json.load(fh)
    version = h5py.version.hdf5_version
    failures = 0
    checked = 0
    for entry in manifest["files"]:
        path = os.path.join(directory, entry["file"])
        try:
            f = h5py.File(path, "r")
        except Exception as e:  # noqa: BLE001 - report every kind of failure
            print(f"FAIL  {entry['file']}: cannot open: {e}")
            failures += 1
            continue
        with f:
            visited = []
            try:  # walk every link (names only: some objects may need a newer library to open)
                f.visit(visited.append)
            except Exception as e:  # noqa: BLE001
                print(f"FAIL  {entry['file']}: cannot walk the hierarchy: {e}")
                failures += 1
            for obj in entry["objects"]:
                need = obj.get("min_hdf5")
                if need and hdf5_version() < tuple(int(p) for p in need.split(".")):
                    continue
                if obj.get("szip") and imagecodecs is None:
                    print(f"skip  {entry['file']}:{obj['path']}: imagecodecs (libaec) not installed")
                    continue
                try:
                    check_object(f, obj)
                    checked += 1
                except Exception as e:  # noqa: BLE001
                    print(f"FAIL  {entry['file']}:{obj['path']}: {e}")
                    failures += 1
    status = "OK" if failures == 0 else f"{failures} FAILED"
    print(f"HDF5 {version} (h5py {h5py.__version__}): {checked} objects checked, {status}")
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--dir", help="check an existing export instead of running Maven")
    parser.add_argument("--python114", help="also check with this interpreter (an HDF5 1.14 h5py)")
    parser.add_argument("--check-only", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()

    directory = args.dir or tempfile.mkdtemp(prefix="falcon-interop-")
    if not args.dir:
        export(directory)
    failures = check(directory)
    if args.python114 and not args.check_only:
        result = subprocess.run([args.python114, os.path.abspath(__file__), "--check-only", "--dir", directory])
        failures += result.returncode
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
