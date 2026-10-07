"""Checks the falcon command's conversions and copies against h5py and zarr-python.

Writes an HDF5 file with h5py, and Zarr stores (v3 and v2) with zarr-python, of many types and codecs; runs
falcon.jar on them:
  - convert the HDF5 file to Zarr v3, Zarr v2, and a ZIP archive, and each of those back to HDF5;
  - convert the Zarr stores to HDF5;
  - copy the Zarr stores as they are, and re-encoded (v3 to v2, v2 to v3, another codec and chunks);
then reads every result with h5py and zarr-python and compares it with what it came from: each node, its
values, its data type where the formats share one, its attributes, and its fill value. What the command
leaves out of Zarr (links, references, ragged int sequences) is left out of the comparison too.

Dev-time tool; h5py, hdf5plugin, and zarr-python are not Falcon dependencies (h5py 3.16 and zarr-python 3.4
checked):

    mvn -pl cli -am package -DskipTests
    python tools/fixtures/check_cli.py                      # writes into a temporary directory
    python tools/fixtures/check_cli.py --dir OUT_DIR --jar cli/target/falcon.jar
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import warnings

import h5py
import hdf5plugin  # noqa: F401 -- h5py reads the plugin filters (zstd, Blosc, bzip2, LZ4) of `-c keep` outputs
import numcodecs
import numcodecs.zarr3
import numpy as np
import zarr
from zarr.codecs import BloscCodec, GzipCodec

warnings.filterwarnings("ignore")
# zarr-python's concurrent reads sometimes hang on Windows; one at a time is enough here
zarr.config.set({"async.concurrency": 1})

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ---- inputs -----------------------------------------------------------------------------------------------

def write_hdf5(path):
    with h5py.File(path, "w") as f:
        f.attrs["title"] = "sample"
        f.attrs["version"] = 3
        f.attrs["scale"] = np.float32(0.1)
        f.attrs["ranges"] = np.array([[0, 1], [2, 3]], dtype="i2")
        g = f.create_group("run")
        g.attrs["started"] = "yes"
        t = g.create_dataset("temperature", data=np.arange(24, dtype="<f4").reshape(4, 6) / 10, chunks=(2, 6),
                             compression="gzip", compression_opts=4, shuffle=True, fillvalue=np.nan)
        t.attrs["units"] = "K"
        g.create_dataset("counts", data=np.arange(10, dtype=">u2"))
        g.create_dataset("big", data=np.array([0, 2**64 - 1, 2**63], dtype=np.uint64))
        f.create_dataset("flags", data=np.array([True, False, True]))
        f.create_dataset("names", data=np.array([b"alpha", b"b", b""], dtype="S5"))
        f.create_dataset("words", data=["x", "yy", "zzz"], dtype=h5py.string_dtype())
        f.create_dataset("cplx", data=np.array([1 + 2j, -3.5 - 0.25j], dtype="c8"))
        rec = np.array([(1, 2.5, b"ab"), (2, -1.0, b"c")], dtype=[("id", "<i4"), ("v", ">f8"), ("tag", "S2")])
        f.create_dataset("table", data=rec)
        f.create_dataset("scalar", data=42.0)
        f.create_dataset("cube", data=np.arange(24, dtype="i8").reshape(2, 3, 4), chunks=(1, 3, 4))
        f.create_dataset("empty", shape=(0, 3), dtype="f8")
        f.create_dataset("colors", data=np.array([2, 0, 1], dtype="u1"),
                         dtype=h5py.enum_dtype({"RED": 0, "GREEN": 1, "BLUE": 2}, basetype="u1"))
        when = np.array(["2020-01-01", "1999-12-31"], dtype="M8[D]")
        f.create_dataset("when", data=when.astype(h5py.opaque_dtype(when.dtype)))
        r = f.create_dataset("ragged", shape=(2,), dtype=h5py.vlen_dtype(np.int32))
        r[0] = np.array([1, 2, 3], dtype=np.int32)
        r[1] = np.array([], dtype=np.int32)
        f["soft"] = h5py.SoftLink("/run/temperature")
        f["ext"] = h5py.ExternalLink("other.h5", "/x")
        f["ref"] = f["run"].ref


def fill_group(g):
    g.attrs.update({"title": "zarr sample", "n": 3, "pi": 3.25, "flag": True, "list": [1, 2, 3],
                    "grid": [[1.5, 2], [3, 4]], "names": ["a", "b"], "nested": {"k": [1, "x"]}, "none": None,
                    "nan": float("nan"), "big": 2**64 - 1})
    rng = np.random.default_rng(1)
    g.create_array("f16", data=rng.standard_normal((5, 7)).astype("f2"), chunks=(2, 3))
    g.create_array("i8be", data=np.arange(-5, 5, dtype=">i8"), chunks=(4,))
    g.create_array("u64", data=np.array([0, 2**64 - 1, 7], dtype="u8"))
    g.create_array("b", data=np.array([True, False, True, True]))
    g.create_array("c128", data=np.array([1 + 2j, -3j, np.nan + 1j]), chunks=(2,))
    a = g.create_array("sparse", shape=(100, 100), chunks=(10, 10), dtype="f4", fill_value=np.nan)
    a[5:7, 50:52] = 1.0
    a = g.create_array("ints_fill", shape=(20,), chunks=(5,), dtype="i4", fill_value=-1)
    a[3:6] = [1, 2, 3]
    g.create_array("scalar", data=np.array(4.5))
    g.create_array("empty", shape=(0, 4), dtype="i2")
    g.create_array("dt", data=np.array(["2020-01-01T12:00", "NaT", "1969-07-20T20:17"], dtype="M8[m]"))
    g.create_array("td", data=np.array([1, -2, 3], dtype="m8[10s]"))
    g.create_array("strings", data=np.array(["α", "beta", ""], dtype=np.dtypes.StringDType()))
    sub = g.create_group("sub")
    sub.attrs["units"] = "mm"
    s = sub.create_array("s3", data=np.array([b"ab", b"", b"xyz"], dtype="S3"))
    s.attrs["note"] = "bytes"
    sub.create_array("v4", data=np.array([b"\x00\x01\x02\x03", b"abcd"], dtype="V4"))
    sub.create_array("u5", data=np.array(["héllo", "", "x"], dtype="U5"))
    rec = np.array([(1, 2.5, b"ab", True), (-2, np.nan, b"c", False)],
                   dtype=[("id", "<i4"), ("v", "<f8"), ("tag", "S2"), ("ok", "?")])
    sub.create_array("rec", data=rec)


def write_zarr(v3, v2):
    g = zarr.open_group(v3, mode="w", zarr_format=3)
    fill_group(g)
    g.create_array("blosc", data=np.arange(1000, dtype="i4").reshape(10, 100), chunks=(5, 50),
                   compressors=BloscCodec(cname="lz4", clevel=7, shuffle="bitshuffle"))
    g.create_array("gz", data=np.linspace(0, 1, 64).reshape(8, 8), chunks=(4, 4), compressors=GzipCodec(level=6))
    g.create_array("sharded", data=np.arange(64 * 64, dtype="u2").reshape(64, 64), chunks=(8, 8), shards=(32, 32))
    g.create_array("bz", data=np.arange(50, dtype="f8"), compressors=numcodecs.zarr3.BZ2(level=3))
    g.create_array("nocomp", data=np.arange(6, dtype="i1"), compressors=None)
    zarr.consolidate_metadata(v3)
    g = zarr.open_group(v2, mode="w", zarr_format=2)
    fill_group(g)
    g.create_array("forder", data=np.arange(12, dtype="i4").reshape(3, 4), chunks=(2, 2), order="F")
    g.create_array("delta", data=np.arange(100, dtype="i8"), chunks=(30,), filters=[numcodecs.Delta(dtype="i8")],
                   compressors=numcodecs.Zlib(level=3))
    g.create_array("lz4", data=np.arange(40, dtype="f4"), chunks=(16,), compressors=numcodecs.LZ4())
    zarr.consolidate_metadata(v2)


# ---- reading ----------------------------------------------------------------------------------------------

class Node:
    """A group or array of either format, as both libraries read it."""

    def __init__(self, path, attrs, data=None, dtype=None, fill=None, array=False):
        self.path, self.attrs, self.data, self.dtype, self.fill, self.array = path, attrs, data, dtype, fill, array


def read_hdf5(path):
    nodes = {}
    with h5py.File(path, "r") as f:
        names = [""]
        f.visit(names.append)  # names first: zarr-python must not read while visit holds h5py's lock
        for name in names:
            link = f.get(name, getlink=True) if name else None
            if isinstance(link, (h5py.SoftLink, h5py.ExternalLink)):
                continue
            obj = f[name] if name else f
            attrs = {k: obj.attrs[k] for k in obj.attrs}
            if isinstance(obj, h5py.Dataset):
                if obj.dtype.kind == "O" and h5py.check_vlen_dtype(obj.dtype) not in (str, bytes, None) \
                        and h5py.check_vlen_dtype(obj.dtype) != np.uint8:
                    continue  # ragged sequences: left out of Zarr
                if h5py.check_ref_dtype(obj.dtype) is not None:
                    continue  # references: left out of Zarr
                data = obj[()]
                nodes["/" + name] = Node("/" + name, attrs, data, obj.dtype, obj.fillvalue, True)
            else:
                nodes["/" + name if name else "/"] = Node("/" + name if name else "/", attrs)
    return nodes


def read_zarr(path):
    store = zarr.storage.ZipStore(path, mode="r") if path.endswith(".zip") else path
    root = zarr.open_group(store, mode="r")
    nodes = {"/": Node("/", dict(root.attrs))}

    def walk(group, prefix):
        for name, node in group.members():
            p = prefix + name
            if isinstance(node, zarr.Group):
                nodes[p] = Node(p, dict(node.attrs))
                walk(node, p + "/")
            else:
                data = node[()] if node.shape == () else node[...]
                nodes[p] = Node(p, dict(node.attrs), data, node.dtype, node.fill_value, True)
    walk(root, "/")
    return nodes


# ---- comparing --------------------------------------------------------------------------------------------

def plain(a):
    """Values as both formats agree on them: complex from h5py's compound, text from bytes, enums as ints."""
    a = np.asarray(a)
    if a.dtype.names and set(a.dtype.names) == {"r", "i"}:
        return a["r"] + 1j * a["i"]
    if a.dtype.names:
        return a
    if h5py.check_enum_dtype(a.dtype) is not None and a.dtype != np.dtype("bool"):
        return a.astype("i8")
    if a.dtype.kind in "OSTU":
        out = []
        for x in a.ravel():
            if isinstance(x, (bytes, np.bytes_)):
                x = bytes(x).decode()
            elif isinstance(x, np.ndarray):  # a sequence of bytes
                x = bytes(x.astype("u1")).decode("latin-1")
            out.append(str(x))
        return np.array(out, dtype=object).reshape(a.shape)
    return a


def same_values(x, y):
    x, y = plain(x), plain(y)
    if x.shape != y.shape:
        return f"shape {x.shape} vs {y.shape}"
    if x.dtype.names or y.dtype.names:
        if not (x.dtype.names and y.dtype.names) or list(x.dtype.names) != list(y.dtype.names):
            return f"fields {x.dtype.names} vs {y.dtype.names}"
        for n in x.dtype.names:
            r = same_values(x[n], y[n])
            if r is not True:
                return f"{n}: {r}"
        return True
    if x.dtype.kind in "Mm" or y.dtype.kind in "Mm":
        if x.dtype != y.dtype:
            return f"dtype {x.dtype} vs {y.dtype}"
        return True if np.array_equal(x.view("i8"), y.view("i8")) else f"{x} vs {y}"
    if x.dtype.kind in "fc" or y.dtype.kind in "fc":
        t = complex if "c" in x.dtype.kind + y.dtype.kind else float
        return True if np.array_equal(x.astype(t), y.astype(t), equal_nan=True) else f"{x.ravel()[:4]} vs {y.ravel()[:4]}"
    if x.dtype.kind == "V" or y.dtype.kind == "V":
        return True if [bytes(v) for v in x.ravel()] == [bytes(v) for v in y.ravel()] else "bytes differ"
    return True if x.ravel().tolist() == y.ravel().tolist() else f"{x.ravel()[:6]} vs {y.ravel()[:6]}"


def jsonable(v):
    """An attribute value as JSON, as Falcon carries it between the formats."""
    if isinstance(v, (bytes, np.bytes_)):
        return bytes(v).decode()
    if v is None or isinstance(v, (str, bool, int, float, dict, list)):
        return v
    a = np.asarray(v)
    if a.dtype.kind in "SO":
        return [jsonable(x) for x in a.ravel()] if a.shape else jsonable(a.item())
    return a.tolist()


def same_attr(x, y):
    """An attribute's value on each side; a JSON value HDF5 cannot hold is a JSON string on the HDF5 side. A
    float32 travels as its shortest decimal, so it compares as a float32."""
    if np.asarray(x).dtype == np.float32 or np.asarray(y).dtype == np.float32:
        try:
            return True if np.array_equal(np.asarray(x, dtype=np.float32), np.asarray(y, dtype=np.float32),
                                          equal_nan=True) else f"{x!r} vs {y!r}"
        except (TypeError, ValueError):
            pass
    a, b = jsonable(x), jsonable(y)
    if isinstance(a, str) and not isinstance(b, str):
        a, b = b, a
    if isinstance(b, str) and not isinstance(a, str):
        try:
            b = json.loads(b)
        except ValueError:
            return f"{x!r} vs {y!r}"
    try:
        if np.array_equal(np.asarray(a, dtype=float), np.asarray(b, dtype=float), equal_nan=True):
            return True
    except (TypeError, ValueError):
        pass
    return True if json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True) else f"{x!r} vs {y!r}"


def compare(label, original, result, expect_missing=()):
    failures = 0
    for path, node in original.items():
        if path not in result:
            if path not in expect_missing:
                print(f"FAIL {label} {path}: missing")
                failures += 1
            continue
        other = result[path]
        for k, v in node.attrs.items():
            if k not in other.attrs:
                print(f"FAIL {label} {path}: attribute {k} missing")
                failures += 1
                continue
            r = same_attr(v, other.attrs[k])
            if r is not True:
                print(f"FAIL {label} {path}: attribute {k}: {r}")
                failures += 1
        if node.array:
            r = same_values(node.data, other.data)
            if r is not True:
                print(f"FAIL {label} {path}: {r}")
                failures += 1
            if node.dtype is not None and node.dtype.kind in "iuf" and node.fill is not None and other.fill is not None:
                if not np.array_equal(np.asarray(node.fill, dtype=float), np.asarray(other.fill, dtype=float),
                                      equal_nan=True):
                    print(f"FAIL {label} {path}: fill {node.fill} vs {other.fill}")
                    failures += 1
    arrays = sum(1 for n in original.values() if n.array and n.path in result)
    print(f"{label}: {arrays} arrays, {len(original)} nodes compared, {failures} failures")
    return failures


# ---- the command ------------------------------------------------------------------------------------------

def falcon(jar, *args):
    result = subprocess.run(["java", "-jar", jar, *args], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"falcon {' '.join(args)} failed ({result.returncode}):\n{result.stdout}{result.stderr}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--jar", default=os.path.join(REPO, "cli", "target", "falcon.jar"))
    parser.add_argument("--dir", help="where to write (default: a temporary directory)")
    args = parser.parse_args()
    out = args.dir or tempfile.mkdtemp(prefix="falcon-cli-")
    os.makedirs(out, exist_ok=True)
    p = lambda name: os.path.join(out, name)
    for name in os.listdir(out):
        target = p(name)
        shutil.rmtree(target) if os.path.isdir(target) else os.remove(target)

    write_hdf5(p("sample.h5"))
    write_zarr(p("v3.zarr"), p("v2.zarr"))
    left_out = {"/soft", "/ext", "/ragged", "/ref"}
    falcon(args.jar, "convert", "-q", p("sample.h5"), p("h5.zarr"))
    falcon(args.jar, "convert", "-q", "--zarr-format", "2", p("sample.h5"), p("h5v2.zarr"))
    falcon(args.jar, "convert", "-q", "-c", "blosc:zstd:5:bitshuffle", "--chunks", "auto", p("sample.h5"), p("h5.zip"))
    for z in ("h5.zarr", "h5v2.zarr", "h5.zip"):
        falcon(args.jar, "convert", "-q", p(z), p(z + ".h5"))
    for z in ("v3.zarr", "v2.zarr"):
        falcon(args.jar, "convert", "-q", "-c", "keep", p(z), p(z + ".h5"))
    falcon(args.jar, "copy", p("v3.zarr"), p("v3copy.zip"))
    falcon(args.jar, "copy", "-q", "--zarr-format", "2", p("v3.zarr"), p("v3to2.zarr"))
    falcon(args.jar, "copy", "-q", "--zarr-format", "3", "--consolidate", p("v2.zarr"), p("v2to3.zarr"))
    falcon(args.jar, "copy", "-q", "-c", "gzip:6", "--chunks", "auto", p("v3.zarr"), p("v3gz.zarr"))

    h5 = read_hdf5(p("sample.h5"))
    failures = 0
    for z in ("h5.zarr", "h5v2.zarr", "h5.zip"):
        failures += compare(f"h5 -> {z} (zarr-python)", h5, read_zarr(p(z)), left_out)
        failures += compare(f"h5 -> {z} -> h5 (h5py)", h5, read_hdf5(p(z + ".h5")), left_out)
    for z in ("v3.zarr", "v2.zarr"):
        failures += compare(f"{z} -> h5 (h5py)", read_zarr(p(z)), read_hdf5(p(z + ".h5")))
    for original, copy in (("v3.zarr", "v3copy.zip"), ("v3.zarr", "v3to2.zarr"), ("v2.zarr", "v2to3.zarr"),
                           ("v3.zarr", "v3gz.zarr")):
        failures += compare(f"{original} -> {copy} (zarr-python)", read_zarr(p(original)), read_zarr(p(copy)))
    print(f"{failures} failures in all; files in {out}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
