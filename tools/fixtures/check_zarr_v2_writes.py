"""Reads every Zarr v2 array WriteZarrV2Cases.java wrote with Falcon back with zarr-python (P2 F4).

Falcon's reading of v2 is checked against zarr-python's own arrays (gen_zarr_v2_ext_fixtures.py); this
checks the other direction: Falcon writes through the pipeline it translates a .zarray to (the v2 dtypes,
Fortran order, the numcodecs filters and compressors, blosc and zstd configurations), and zarr-python must
read every element as written. Where a chunk is blosc-compressed, its header must also carry the type size
and shuffle numcodecs would have given c-blosc for that array, and the compressor its cname names; a chunk
compressed with bz2 must be the bytes numcodecs' BZ2 writes.

A zfpy array (lossy) must read as Falcon reads it back, and zarr-python, writing its "inputs" into a copy of
its metadata, must store the same chunks, byte for byte.

The arrays Falcon created from scratch (created_NAME, "like" NAME in the manifest) must also hold the .zarray
and .zattrs zarr-python wrote for NAME, and the v2 hierarchy Falcon created and consolidated (hierarchy_v2)
must open through its .zmetadata with every group's attributes and every array's elements. Dev-time tool;
zarr-python is not a Falcon dependency:

    mvn -pl zarr -am compile
    java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZarrV2Cases.java OUT_DIR
    python tools/fixtures/check_zarr_v2_writes.py OUT_DIR
"""
import json
import math
import os
import sys
import tempfile
import warnings

import numcodecs
import numpy as np
import zarr
import zarr.storage

warnings.simplefilter("ignore")

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "zarr", "src", "test", "resources",
                        "fixtures")

BLOSC_CODES = {"blosclz": 0, "lz4": 1, "lz4hc": 1, "snappy": 2, "zlib": 3, "zstd": 4}


def falcon_values(a, dtype):
    """Every element in C order, in the form the manifest holds (as gen_zarr_v2_ext_fixtures.py)."""
    flat = np.asarray(a).reshape(-1)
    if dtype.kind == "O" or dtype.kind == "T":
        return [v if isinstance(v, str) else bytes(v).hex() for v in flat.tolist()]
    if dtype.kind == "U":
        return [str(v) for v in flat.tolist()]
    if dtype.kind == "S":
        return [bytes(v).rstrip(b"\0").hex() for v in flat]
    if dtype.names is not None:
        le = flat.astype(dtype.newbyteorder("<"))
        return [le[i].tobytes().hex() for i in range(le.size)]
    if dtype.kind == "V":
        return [flat[i].tobytes().hex() for i in range(flat.size)]
    if dtype.kind in "Mm":
        return [int(v) for v in flat.astype(dtype.newbyteorder("<")).view("<i8")]
    if dtype.kind == "f":
        return ["NaN" if np.isnan(v) else v for v in flat.astype("<f8").tolist()]
    if dtype.kind == "b":
        return [bool(v) for v in flat.tolist()]
    return [int(v) for v in flat.tolist()]


def same(a, b):
    if isinstance(a, float) or isinstance(b, float):
        return a == b or (isinstance(a, float) and isinstance(b, float) and math.isnan(a) and math.isnan(b))
    return a == b


def blosc_problems(path, zarray):
    """The c-blosc headers of the stored chunks against what numcodecs would have written."""
    c = zarray.get("compressor") or {}
    if c.get("id") != "blosc":
        return []
    problems = []
    for key in sorted(os.listdir(path)):
        if key.startswith("."):
            continue
        b = open(os.path.join(path, key), "rb").read()
        flags, typesize = b[2], b[3]
        if flags & 0x02:  # memcpy'ed: no compressor or filter applies
            continue
        if (flags >> 5) != BLOSC_CODES[c.get("cname", "lz4")]:
            problems.append(f"chunk {key}: compressor code {flags >> 5}, cname is {c.get('cname')}")
        problems.extend(f"chunk {key}: {p}" for p in expect_header(zarray, c, flags, typesize))
    return problems


def bz2_problems(path, zarray):
    """Each stored chunk against the stream numcodecs' BZ2 writes for its bytes, at the configured level."""
    c = zarray.get("compressor") or {}
    if c.get("id") != "bz2":
        return []
    codec = numcodecs.get_codec(c)
    problems = []
    for key in sorted(os.listdir(path)):
        if key.startswith("."):
            continue
        stored = open(os.path.join(path, key), "rb").read()
        if bytes(codec.encode(codec.decode(stored))) != stored:
            problems.append(f"chunk {key}: not the stream numcodecs writes at level {c.get('level', 1)}")
    return problems


def expect_header(zarray, c, flags, typesize):
    want_ts = itemsize(zarray)
    out = []
    if typesize != min(want_ts, 255) and not (want_ts > 255 and typesize == 1):
        out.append(f"type size {typesize}, numcodecs gives {want_ts}")
    shuffle = c.get("shuffle", 1)
    if shuffle == -1:
        shuffle = 2 if want_ts == 1 else 1
    got = 2 if flags & 0x04 else (1 if flags & 0x01 else 0)
    if want_ts > 1 or shuffle == 2:
        if got != shuffle and not (shuffle == 2 and got == 1):  # c-blosc byte-shuffles a ragged bit-shuffle
            out.append(f"shuffle {got}, configured {shuffle}")
    return out


def itemsize(zarray):
    filters = list(zarray.get("filters") or [])
    if zarray["dtype"] == "|O":
        size = 1
        filters = filters[1:]
    else:
        size = np.dtype([tuple(f) for f in zarray["dtype"]] if isinstance(zarray["dtype"], list)
                        else zarray["dtype"]).itemsize
    for f in filters:
        if f["id"] in ("delta", "fixedscaleoffset", "quantize"):
            size = np.dtype(f.get("astype") or f["dtype"]).itemsize
        elif f["id"] == "astype":
            size = np.dtype(f["encode_dtype"]).itemsize
        elif f["id"] != "bitround":
            size = 1
    return size


def rewrite_problems(path, zarray, inputs):
    """zarr-python writing the inputs into a copy of the array's metadata against the chunks Falcon wrote."""
    import shutil
    copy = tempfile.mkdtemp(prefix="zarr-rewrite-")
    try:
        for key in (".zarray", ".zattrs"):
            if os.path.exists(os.path.join(path, key)):
                shutil.copy(os.path.join(path, key), os.path.join(copy, key))
        a = zarr.open_array(zarr.storage.LocalStore(copy), mode="r+")
        a[...] = np.asarray(inputs, dtype=a.dtype).reshape(a.shape)
        problems = []
        for root, _dirs, files in os.walk(path):
            for f in files:
                if f.startswith("."):
                    continue
                key = os.path.relpath(os.path.join(root, f), path)
                theirs = os.path.join(copy, key)
                if not os.path.exists(theirs):
                    problems.append(f"chunk {key}: zarr-python wrote none")
                elif open(theirs, "rb").read() != open(os.path.join(root, f), "rb").read():
                    problems.append(f"chunk {key}: not the bytes zarr-python writes")
        return problems
    finally:
        shutil.rmtree(copy, ignore_errors=True)


def metadata_problems(path, like):
    """The created array's .zarray and .zattrs against those zarr-python wrote for the fixture it recreates."""
    problems = []
    for key in (".zarray", ".zattrs"):
        want_path = os.path.join(FIXTURES, like, key)
        want = json.load(open(want_path, encoding="utf-8")) if os.path.exists(want_path) else {}
        got = json.load(open(os.path.join(path, key), encoding="utf-8"))
        if got != want:
            problems.append(f"{key} is {json.dumps(got)}, zarr-python wrote {json.dumps(want)}")
    if list(json.load(open(os.path.join(path, ".zarray"), encoding="utf-8"))) != list(
            json.load(open(os.path.join(FIXTURES, like, ".zarray"), encoding="utf-8"))):
        problems.append(".zarray members not in zarr-python's order")
    return problems


def group_problems(path, entry):
    """A created v2 hierarchy, opened through its consolidated metadata as zarr-python opens it."""
    root = zarr.open_group(zarr.storage.LocalStore(path), mode="r", zarr_format=2, use_consolidated=True)
    problems = []
    if root.metadata.consolidated_metadata is None:
        problems.append("not opened through .zmetadata")
    members = dict(root.members(max_depth=None))
    want_groups = {k for k in entry["attributes"] if k}
    got_groups = {k for k, v in members.items() if isinstance(v, zarr.Group)}
    got_arrays = {k for k, v in members.items() if isinstance(v, zarr.Array)}
    if got_groups != want_groups or got_arrays != set(entry["arrays"]):
        problems.append(f"members {sorted(members)}, wrote groups {sorted(want_groups)}"
                        f" and arrays {sorted(entry['arrays'])}")
    for name, want in entry["attributes"].items():
        node = root if not name else members.get(name)
        if node is not None and dict(node.attrs) != want:
            problems.append(f"{name or '/'} attributes {dict(node.attrs)}, wrote {want}")
    for name, want in entry["arrays"].items():
        a = members.get(name)
        if a is None:
            continue
        got = falcon_values(a[...], a.dtype)
        if len(got) != len(want) or any(not same(g, w) for g, w in zip(got, want)):
            problems.append(f"{name} reads {got}, wrote {want}")
    return problems


def main(directory):
    manifest = json.load(open(os.path.join(directory, "manifest.json"), encoding="utf-8"))
    failures = 0
    for entry in manifest:
        name = entry["name"]
        path = os.path.join(directory, name)
        if entry.get("kind") == "group":
            try:
                problems = group_problems(path, entry)
            except Exception as e:  # noqa: BLE001 - report and go on
                problems = [f"{type(e).__name__}: {e}"]
            failures += report(name, problems)
            continue
        zarray = json.load(open(os.path.join(path, ".zarray"), encoding="utf-8"))
        try:
            a = zarr.open_array(zarr.storage.LocalStore(path), mode="r")
            got = falcon_values(a[...], a.dtype if not hasattr(a.dtype, "to_native_dtype") else a.dtype)
            want = entry["values"]
            bad = [i for i, (g, w) in enumerate(zip(got, want)) if not same(g, w)]
            problems = []
            if len(got) != len(want):
                problems.append(f"{len(got)} elements, wrote {len(want)}")
            if bad:
                i = bad[0]
                problems.append(f"{len(bad)} elements differ, first [{i}]: read {got[i]!r}, wrote {want[i]!r}")
            problems += blosc_problems(path, zarray)
            problems += bz2_problems(path, zarray)
            if "inputs" in entry:
                problems += rewrite_problems(path, zarray, entry["inputs"])
            if "like" in entry:
                problems += metadata_problems(path, entry["like"])
        except Exception as e:  # noqa: BLE001 - report and go on
            problems = [f"{type(e).__name__}: {e}"]
        failures += report(name, problems)
    created = sum(1 for e in manifest if "like" in e or e.get("kind") == "group")
    print(f"{len(manifest) - failures} of {len(manifest)} Falcon-written v2 nodes read back by zarr-python"
          f" {zarr.__version__} ({created} of them created by Falcon)")
    return 1 if failures else 0


def report(name, problems):
    if problems:
        print(f"FAIL {name}: " + "; ".join(problems))
        return 1
    print(f"ok   {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
