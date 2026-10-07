#!/usr/bin/env python3
"""Checks that the reference library reads what Falcon's HDF5 writer writes.

Falcon's own round-trip tests (WriteTest) only prove the writer and reader agree with each other. This
script exports the writer's feature matrix (WriterInteropExport: one file per area, among them files
Falcon changed in place, plus a manifest of expected values) and reads every object back with h5py --
HDF5 2.0, and optionally an HDF5 1.14 build in a second interpreter -- decoding szip chunks with libaec
(h5py ships szip disabled), and reading the third-party filters (LZF, Blosc, LZ4, bitshuffle, Zstandard, bzip2,
ZFP, Blosc2, SZ) through hdf5plugin, checking each dataset's filters (id, flags, client data, name) as libhdf5
reports them. Each library then changes a copy of every file (an attribute on every object, a dataset in every group, a row on
every dataset that can grow -- written, through the plugins, for the third-party filters -- and the
attributes a file's manifest names deleted) and reads it all back.

    python tools/fixtures/check_hdf5_writer.py                      # export via Maven, check with this python
    python tools/fixtures/check_hdf5_writer.py --python114 PATH     # ...and also with an HDF5 1.14 h5py
    python tools/fixtures/check_hdf5_writer.py --dir DIR            # check an existing export

Requirements: tools/fixtures/requirements.txt (h5py, numpy, imagecodecs, hdf5plugin). Dev-time only, like
the fixture generators; exits non-zero if anything fails. Without imagecodecs the szip datasets are skipped,
and without hdf5plugin the third-party ones.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

# External raw data files are named relative to the HDF5 file that lists them, as Falcon resolves them
# (set before libhdf5 loads: HDF5 2.0 reads it then).
os.environ.setdefault("HDF5_EXTFILE_PREFIX", "${ORIGIN}")

import h5py  # noqa: E402
import numpy as np  # noqa: E402

try:
    import imagecodecs  # libaec, for szip chunks
except ImportError:  # pragma: no cover - the 1.14 environment may lack it
    imagecodecs = None

try:
    import hdf5plugin  # noqa: F401 - importing it registers the third-party filters
except ImportError:  # pragma: no cover - the 1.14 environment may lack it
    hdf5plugin = None


def skipped(obj):
    """Why an object cannot be checked with the libraries this interpreter has, or None."""
    if obj.get("szip") and imagecodecs is None:
        return "imagecodecs (libaec) not installed"
    if obj.get("plugin") and hdf5plugin is None:
        return "hdf5plugin not installed"
    return None

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def export(directory):
    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    cmd = [mvn, "-q", "-pl", "hdf5", "-am", "test", "-Dtest=WriterInteropExport",
           "-Dsurefire.failIfNoSpecifiedTests=false", f"-Dfalcon.interop.dir={directory}"]
    subprocess.run(cmd, cwd=ROOT, check=True)


def hdf5_version():
    return tuple(int(p) for p in h5py.version.hdf5_version.split(".")[:2])


def supported(entry):
    """False for a file (or object) that needs a newer HDF5 than this one."""
    need = entry.get("min_hdf5")
    return not need or hdf5_version() >= tuple(int(p) for p in need.split("."))


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


def szip_exact(ds):
    """Checks every szip chunk is libaec's own coding of its contents: decoded, then encoded again by libaec's
    SZ layer, it comes back byte for byte (Falcon ports libaec's encoder)."""
    plist = ds.id.get_create_plist()
    index = [i for i in range(plist.get_nfilters()) if plist.get_filter(i)[0] == h5py.h5z.FILTER_SZIP][0]
    cd = plist.get_filter(index)[2]
    params = dict(options_mask=cd[0], pixels_per_block=cd[1], bits_per_pixel=cd[2], pixels_per_scanline=cd[3],
                  header=True)
    for chunk in np.ndindex(*[-(-n // c) for n, c in zip(ds.shape, ds.chunks)]):
        start = tuple(i * c for i, c in zip(chunk, ds.chunks))
        mask, raw = ds.id.read_direct_chunk(start)
        if mask & (1 << index):
            continue  # stored unfiltered: szip could not shrink it
        raw = bytes(raw)
        for f in range(plist.get_nfilters() - 1, index, -1):
            raise AssertionError(f"a filter after szip ({plist.get_filter(f)[0]})")
        again = bytes(imagecodecs.szip_encode(imagecodecs.szip_decode(raw, **params), **params))
        assert again == raw, f"chunk at {start}: libaec codes it in {len(again)} bytes, Falcon in {len(raw)}"


def as_text(values):
    return [v.decode("utf-8") if isinstance(v, bytes) else str(v) for v in values]


def region_values(f, ref):
    target = f[ref]
    return target.name, np.asarray(target[ref]).ravel()


def check_regions(f, refs, expected_list, what):
    for ref, expected in zip(refs, expected_list):
        if expected is None:
            assert not ref, f"{what}: a null region reference is not null"
            continue
        name, got = region_values(f, ref)
        assert name == expected["target"], f"{what}: region of {name}, not {expected['target']}"
        assert np.array_equal(got, np.asarray(expected["values"])), f"{what}: region {got} != {expected['values']}"


def ref_names(f, refs):
    return [f[r].name if r else None for r in np.asarray(refs).ravel()]


def check_object(f, obj, changed=False):
    """Checks one object against its manifest entry; after libhdf5 changed the file (changed=True), the
    counts of links and attributes (which it added to) are not checked."""
    path = obj["path"]
    if path not in f:
        raise AssertionError("missing")
    item = f[path]
    for name in obj.get("absent", []):
        assert name not in item, f"{name} should be gone"
    for name in obj.get("attr_absent", []):
        assert name not in item.attrs, f"attribute {name} should be gone"
    if "attr_count" in obj and not changed:
        assert len(item.attrs) == obj["attr_count"], f"{len(item.attrs)} attributes, not {obj['attr_count']}"
    if "refcount" in obj:
        rc = h5py.h5o.get_info(item.id).rc
        assert rc == obj["refcount"], f"reference count {rc} != {obj['refcount']}"
    if "same_as" in obj:  # a hard link: the same object as at another path
        assert item == f[obj["same_as"]], f"not the object at {obj['same_as']}"
    for name, expected in obj.get("attr_refs", {}).items():
        names = ref_names(f, item.attrs[name])
        assert names == expected, f"attribute {name} references {names} != {expected}"
    for name, expected in obj.get("attr_regions", {}).items():
        check_regions(f, np.asarray(item.attrs[name]).ravel(), expected, f"attribute {name}")
    if "attr_order" in obj and not changed:  # creation order, for an object that tracks it
        assert list(item.attrs.keys()) == obj["attr_order"], f"attributes in order {list(item.attrs.keys())}"
    if obj["kind"] == "group":
        assert isinstance(item, h5py.Group), "not a group"
        if "order" in obj and not changed:  # creation order, for a group that tracks it
            assert list(item.keys()) == obj["order"], f"links in order {list(item.keys())}"
        if "children" in obj and not changed:
            assert sorted(item.keys()) == sorted(obj["children"]), "children differ"
        if "count" in obj and not changed:
            assert len(item) == obj["count"], f"{len(item)} links, not {obj['count']}"
        for name, link in obj.get("links", {}).items():
            actual = item.get(name, getlink=True)
            if "soft" in link:
                assert isinstance(actual, h5py.SoftLink) and actual.path == link["soft"], f"link {name}: {actual}"
            else:
                file_name, object_path = link["external"]
                assert isinstance(actual, h5py.ExternalLink), f"link {name}: {actual}"
                assert (actual.filename, actual.path) == (file_name, object_path), f"link {name}: {actual}"
        for name, expected in obj.get("follow", {}).items():
            got = np.asarray(item[name][()]).ravel()
            assert np.array_equal(got, np.asarray(expected)), f"{name} reaches {got}, not {expected}"
    else:
        assert isinstance(item, h5py.Dataset), "not a dataset"
        if "shape" in obj:
            assert list(item.shape) == obj["shape"], f"shape {item.shape} != {obj['shape']}"
        if "maxshape" in obj:
            assert list(item.maxshape) == obj["maxshape"], f"maxshape {item.maxshape} != {obj['maxshape']}"
        if "chunks" in obj:
            assert list(item.chunks) == obj["chunks"], f"chunks {item.chunks} != {obj['chunks']}"
        if "dtype" in obj:
            assert item.dtype.str == obj["dtype"], f"dtype {item.dtype.str} != {obj['dtype']}"
        if "filters" in obj:  # as libhdf5 reads the pipeline message: [id, flags, client data, name]
            plist = item.id.get_create_plist()
            got = []
            for i in range(plist.get_nfilters()):
                code, flags, values, name = plist.get_filter(i)
                got.append([code, flags, [v - 2**32 if v >= 2**31 else v for v in values], name.decode("ascii")])
            assert got == obj["filters"], f"filters {got} != {obj['filters']}"
        if "opaque_hex" in obj or "time" in obj:
            # h5py has no numpy type for a tagged opaque or a time type: read the bytes as stored.
            ftype = item.id.get_type()
            raw = np.empty(item.shape, dtype=np.dtype(f"V{ftype.get_size()}"))
            item.id.read(h5py.h5s.ALL, h5py.h5s.ALL, raw, mtype=ftype)
            if "opaque_hex" in obj:
                assert raw.tobytes().hex() == obj["opaque_hex"], f"opaque bytes {raw.tobytes().hex()}"
            else:
                seconds = np.frombuffer(raw.tobytes(), dtype="<i8").tolist()
                assert seconds == obj["time"], f"time {seconds} != {obj['time']}"
            return
        data = szip_values(item) if obj.get("szip") else item[()]
        if obj.get("szip_exact") and not changed:
            szip_exact(item)
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
        for name, expected in obj.get("field_refs", {}).items():
            names = ref_names(f, data[name])
            assert names == expected, f"field {name} references {names} != {expected}"
        if "rows" in obj:
            assert [list(r) for r in data] == obj["rows"], "vlen rows differ"
        if "refs" in obj:
            names = [f[r].name if r else None for r in data]
            assert names == obj["refs"], f"references {names} != {obj['refs']}"
        if "regions" in obj:
            check_regions(f, data, obj["regions"], "dataset")
        if "fill" in obj:
            assert item.fillvalue == obj["fill"], f"fill value {item.fillvalue} != {obj['fill']}"
        if "enum" in obj:
            mapping = h5py.check_enum_dtype(item.dtype)
            assert mapping == obj["enum"], f"enum members {mapping} != {obj['enum']}"
    for name, expected in obj.get("attrs", {}).items():
        actual = np.asarray(item.attrs[name]).ravel()
        if not isinstance(expected, list) and not hasattr(expected, "__len__"):
            expected = [expected]
        if actual.dtype.kind in "SOU" and expected and isinstance(expected[0], str):
            assert as_text(actual) == expected, f"attribute {name}: {as_text(actual)} != {expected}"
        else:
            assert np.array_equal(actual, np.asarray(expected, dtype=actual.dtype)), f"attribute {name} differs"


def check(directory):
    with open(os.path.join(directory, "manifest.json"), encoding="utf-8") as fh:
        manifest = json.load(fh)
    version = h5py.version.hdf5_version
    failures = 0
    checked = 0
    for entry in manifest["files"]:
        if not supported(entry):
            print(f"skip  {entry['file']}: needs HDF5 {entry['min_hdf5']}")
            continue
        path = os.path.join(directory, entry["file"])
        if entry.get("refused"):  # a change interrupted, its superblock marked as open by a writer
            try:
                h5py.File(path, "r").close()
                print(f"FAIL  {entry['file']}: libhdf5 opened a file marked as open by a writer")
                failures += 1
            except OSError:
                checked += 1
            continue
        try:
            f = h5py.File(path, "r")
        except Exception as e:  # noqa: BLE001 - report every kind of failure
            print(f"FAIL  {entry['file']}: cannot open: {e}")
            failures += 1
            continue
        with f:
            block = entry.get("userblock")
            if block:  # the user block: its size, and the bytes the application gave
                try:
                    assert f.userblock_size == block["size"], f"user block of {f.userblock_size} bytes"
                    with open(path, "rb") as raw:
                        head = raw.read(len(block["hex"]) // 2).hex()
                    assert head == block["hex"], "the user block's bytes differ"
                except Exception as e:  # noqa: BLE001
                    print(f"FAIL  {entry['file']}: {e}")
                    failures += 1
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
                if skipped(obj):
                    print(f"skip  {entry['file']}:{obj['path']}: {skipped(obj)}")
                    continue
                try:
                    check_object(f, obj)
                    checked += 1
                except Exception as e:  # noqa: BLE001
                    print(f"FAIL  {entry['file']}:{obj['path']}: {e}")
                    failures += 1
    status = "OK" if failures == 0 else f"{failures} FAILED"
    print(f"HDF5 {version} (h5py {h5py.__version__}): {checked} objects checked, {status}")
    return failures + change(directory, manifest)


def change(directory, manifest):
    """libhdf5 changes a copy of every file: adds a dataset to each group, an attribute to each object, and
    a row to each dataset that can grow; then reads it all back. This checks that the structures Falcon
    writes (or changed in place) are ones libhdf5 can change, not only read."""
    import shutil
    failures = 0
    changed = 0
    scratch = tempfile.mkdtemp(prefix="falcon-interop-changed-")
    for entry in manifest["files"]:
        if not supported(entry) or entry.get("refused"):
            continue
        objects = [o for o in entry["objects"]
                   if not (o.get("min_hdf5") and hdf5_version() < tuple(int(p) for p in o["min_hdf5"].split(".")))
                   and not skipped(o)]
        path = os.path.join(scratch, entry["file"])
        shutil.copy(os.path.join(directory, entry["file"]), path)
        for companion in entry.get("companions", []):  # its external raw data files
            shutil.copy(os.path.join(directory, companion), os.path.join(scratch, companion))
        grown = {}
        try:
            with h5py.File(path, "r+") as f:
                seen = set()  # an object reached by several hard links is changed once
                for name in dict.fromkeys(o["path"] for o in objects):
                    item = f[name]
                    if item.id in seen:
                        continue
                    seen.add(item.id)
                    item.attrs["_libhdf5"] = np.int32(1)
                    for attr in next(o for o in objects if o["path"] == name).get("libhdf5_delete_attrs", []):
                        del item.attrs[attr]  # through the shared-message index Falcon left, if it is shared
                    if isinstance(item, h5py.Group):
                        item.create_dataset("_libhdf5_data", data=np.arange(3, dtype="i4"))
                    elif (item.chunks is not None and item.maxshape and item.maxshape[0] is None
                          and not next(o for o in objects if o["path"] == name).get("libhdf5_no_grow")):
                        grown[name] = item.shape[0]
                        item.resize(item.shape[0] + 1, axis=0)
                        if next(o for o in objects if o["path"] == name).get("write_row"):
                            item[grown[name]] = item[grown[name] - 1]  # re-encoded through the plugin
            with h5py.File(path, "r") as f:
                for obj in objects:
                    item = f[obj["path"]]
                    assert item.attrs["_libhdf5"] == 1, "libhdf5's attribute is missing"
                    for attr in obj.get("libhdf5_delete_attrs", []):
                        assert attr not in item.attrs, f"libhdf5 deleted attribute {attr}, still there"
                    if obj["kind"] == "group":
                        assert list(item["_libhdf5_data"][()]) == [0, 1, 2], "libhdf5's dataset is missing"
                    if obj["path"] in grown:
                        rows = grown[obj["path"]]
                        assert item.shape[0] == rows + 1, f"grown to {item.shape[0]} rows, not {rows + 1}"
                        if obj.get("write_row"):
                            assert np.array_equal(item[rows], item[rows - 1]), "libhdf5's row differs"
                        if "values" in obj and not obj.get("szip") and item.dtype.kind in "iuf":
                            got = np.asarray(item[:rows]).ravel()
                            assert np.array_equal(got.astype(np.float64) if got.dtype.kind == "f" else got,
                                                  np.asarray(obj["values"], dtype=np.float64 if got.dtype.kind == "f"
                                                             else got.dtype)), "the rows before differ"
                    else:
                        check_object(f, obj, changed=True)
                    changed += 1
        except Exception as e:  # noqa: BLE001
            print(f"FAIL  {entry['file']} changed by libhdf5: {e}")
            failures += 1
    status = "OK" if failures == 0 else f"{failures} FAILED"
    print(f"HDF5 {h5py.version.hdf5_version}: {changed} objects changed by libhdf5 and read back, {status}")
    return failures


def main():
    sys.stdout.reconfigure(errors="backslashreplace")  # object names may not fit the console's code page
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
