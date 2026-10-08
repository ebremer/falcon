"""Compares what Falcon reads from the HDF5 library's own test files with what h5py reads from them.

    mvn -pl cli -am package -DskipTests
    pip install -r tools/fixtures/requirements.txt         # h5py and hdf5plugin
    python tools/conformance/run_hdf5_conformance.py       # every file; -v lists every difference
    python tools/conformance/run_hdf5_conformance.py tools/test/testfiles/tall.h5   # some of them

The files: the HDF5 files in the test directories of https://github.com/HDFGroup/hdf5 (tools/test, test, and
hl/test) at the tag of the release h5py's HDF5 is (hdf5_2.0.0 for h5py 3.16), fetched with git, as a sparse
checkout of those directories, into cli/target/hdf5-conformance: dev-time data, not Falcon's. They are the
library's regression files, written by many of its releases: layouts, datatypes, and links of every kind, in
both byte orders, with the corrupt and odd files its tests refuse.

For each file, `java -jar falcon.jar conformance --hdf5=<file>` prints Falcon's manifest of it: every path
reachable from the root group, the link or object there (the hard links to an object already reached, soft,
external, and user-defined links not followed), each dataset's shape, type class and size, layout, chunks,
filter ids, and values (as `falcon dump -f json` prints them), and every attribute. This script builds h5py's
manifest of the same file, in a process of its own (`--h5py <file>`, so a crash in libhdf5 fails one file), and
compares them. h5py's values are written as Falcon writes them (an enumeration by its names, a reference by its
object's path, an opaque value in base 64), and a fixed-length string as Falcon reads one: ending at its first
NUL (as h5dump prints it), and, space-padded, without its trailing spaces. A dataset of more than 65,536 elements
is compared in part. What lies below a group h5py cannot list, Falcon "reads more".

Each file's outcome:
  agree             both read the same
  both refuse       neither opens it, or both refuse the same parts
  Falcon reads more h5py (libhdf5) cannot read what Falcon reads; reported, not failed
  differs           they read different things, or Falcon cannot read what h5py reads
  known             a difference listed in KNOWN, with its reason; reported, not failed
and, whatever else: a Falcon failure that is not a typed exception (HdfException, IOException) is a bug.

Exits with 1 if any file differs outside KNOWN or Falcon fails untyped, and 0 otherwise. Needs Python 3.11+,
git, java 25, and h5py (hdf5plugin too, for the files with plugin filters).
"""
import argparse
import base64
import collections
import concurrent.futures
import json
import math
import os
import pathlib
import subprocess
import sys

HDF5_REPOSITORY = "HDFGroup/hdf5"
HDF5_TAG = "hdf5_2.0.0"
# the directories of the checkout holding test files (git sparse-checkout patterns)
SPARSE = ["/tools/test/**/testfiles/**", "/test/testfiles/**", "/hl/test/**"]
SIGNATURE = b"\x89HDF\r\n\x1a\n"

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORK = ROOT / "cli" / "target" / "hdf5-conformance"
JAR = ROOT / "cli" / "target" / "falcon.jar"

# mirrors Hdf5Manifest: a dataset with more elements is read in part
MAX_ELEMENTS = 65_536
CAP = 16
CAP_LAST = 4096
TIMEOUT = 300

# Falcon's typed failures: what its API documents that a read may throw
TYPED = {"HdfException", "HdfFormatException", "HdfUnsupportedException", "HdfClosedException", "IOException",
         "EOFException", "FileNotFoundException", "NoSuchFileException", "AccessDeniedException",
         "FileSystemException", "ClosedChannelException"}

# Differences understood and accepted: the file's path in the checkout, and why.
KNOWN = {
    "tools/test/testfiles/h5clear_mdc_image.h5":
        "written with a metadata cache image (H5Pset_mdc_image_config), so its metadata is in the cache image "
        "block (format specification III.J), not at its own addresses: Falcon does not read that block yet",
    "tools/test/testfiles/tcomplex.h5":
        "h5py 3.16 on Linux returns its long double complex numbers (16 bytes, a 112-bit mantissa) unconverted, "
        "their bytes taken for x87 values (0.0 for 10.0); libhdf5's own conversion to native long double complex "
        "gives Falcon's values, the file's DatasetDoubleComplex's (h5py on Windows cannot read them)",
    "tools/test/testfiles/tcomplex_be.h5":
        "h5py 3.16 returns a sequence of big-endian complex numbers unswapped (1.157e-41 for 10.0); h5dump's "
        "expected output (tools/test/h5dump/expected/tcomplex_be.ddl) gives Falcon's values",
}


# ---- h5py's manifest (run in a process of its own) ------------------------------------------------------------

class F32(float):
    """A float32 (or float16) value: Falcon prints its shortest float32 form, so it compares as a float32."""


def marked(v):
    """h5py's manifest for JSON: each float32 as {"f32": value}, so it reaches the comparison a float32."""
    if isinstance(v, F32):
        return {"f32": float(v)}
    if isinstance(v, dict):
        return {k: marked(x) for k, x in v.items()}
    if isinstance(v, list):
        return [marked(x) for x in v]
    return v


def h5py_manifest(name):
    import warnings
    warnings.filterwarnings("ignore")
    try:
        import hdf5plugin  # noqa: F401 -- registers the plugin filters
    except ImportError:
        pass
    import h5py
    try:
        f = h5py.File(name, "r")
    except Exception as e:  # noqa: BLE001 -- any failure to open is the outcome
        return {"error": message(e)}
    with f:
        return {"objects": H5pyWalk(f).run()}


def message(e):
    return f"{type(e).__name__}: {e}"


class H5pyWalk:
    def __init__(self, f):
        import h5py
        import numpy
        self.h5py, self.np, self.f = h5py, numpy, f
        self.objects, self.seen = {}, {}

    def run(self):
        h5o = self.h5py.h5o
        try:
            root = self.f["/"]
            info = h5o.get_info(root.id)
        except Exception as e:  # noqa: BLE001
            return {"/": {"kind": "group", "error": message(e)}}
        self.seen[(info.fileno, info.addr)] = "/"
        self.visit(root.id, "/", info.type)
        return self.objects

    def visit(self, oid, path, otype):
        h5py, h5o = self.h5py, self.h5py.h5o
        self.objects[path] = None  # keeps the object ahead of its members
        entry = {}
        if otype == h5o.TYPE_GROUP:
            entry["kind"] = "group"
        elif otype == h5o.TYPE_DATASET:
            entry["kind"] = "dataset"
            self.dataset(h5py.Dataset(oid), entry)
        elif otype == h5o.TYPE_NAMED_DATATYPE:
            entry["kind"] = "datatype"
            attempt(entry, "type", lambda: type_class(h5py, oid))
        else:
            entry["kind"] = "unknown"
        self.attributes(oid, entry)
        if otype == h5o.TYPE_GROUP:
            self.members(oid, path, entry)
        self.objects[path] = entry

    def members(self, gid, path, entry):
        h5l, h5o = self.h5py.h5l, self.h5py.h5o
        names = []
        try:
            gid.links.iterate(lambda n: names.append(n), idx_type=self.h5py.h5.INDEX_NAME)
        except Exception as e:  # noqa: BLE001
            entry["links_error"] = message(e)
            return
        for raw in sorted(names):
            member = ("" if path == "/" else path) + "/" + raw.decode("utf-8", "replace")
            try:
                link = gid.links.get_info(raw)
            except Exception as e:  # noqa: BLE001
                self.objects[member] = {"kind": "unknown", "error": message(e)}
                continue
            if link.type == h5l.TYPE_HARD:
                try:
                    oid = h5o.open(gid, raw)
                    info = h5o.get_info(oid)
                except Exception as e:  # noqa: BLE001
                    self.objects[member] = {"kind": "hard", "error": message(e)}
                    continue
                first = self.seen.setdefault((info.fileno, info.addr), member)
                if first != member:
                    self.objects[member] = {"kind": "hard", "same_as": first}
                    continue
                self.visit(oid, member, info.type)
            elif link.type == h5l.TYPE_SOFT:
                self.objects[member] = {"kind": "soft", "target": text(gid.links.get_val(raw))}
            elif link.type == h5l.TYPE_EXTERNAL:
                file_name, object_path = gid.links.get_val(raw)
                self.objects[member] = {"kind": "external", "file": text(file_name), "target": text(object_path)}
            else:
                self.objects[member] = {"kind": "user", "type": link.type}

    def dataset(self, dset, entry):
        h5py, h5s, h5d = self.h5py, self.h5py.h5s, self.h5py.h5d
        try:
            space = dset.id.get_space()
            tid = dset.id.get_type()
        except Exception as e:  # noqa: BLE001
            entry["error"] = message(e)
            return
        null = space.get_simple_extent_type() == h5s.NULL
        shape = None if null else list(space.shape)
        entry["shape"] = shape
        entry["type"] = type_class(h5py, tid)
        entry["size"] = tid.get_size() if fixed_size(h5py, tid) else None
        try:
            dcpl = dset.id.get_create_plist()
            layout = dcpl.get_layout()
            entry["layout"] = {h5d.COMPACT: "compact", h5d.CONTIGUOUS: "contiguous", h5d.CHUNKED: "chunked",
                               h5d.VIRTUAL: "virtual"}.get(layout, str(layout))
            entry["chunks"] = list(dcpl.get_chunk()) if layout == h5d.CHUNKED else None
            entry["filters"] = [dcpl.get_filter(i)[0] for i in range(dcpl.get_nfilters())]
        except Exception as e:  # noqa: BLE001
            entry["layout_error"] = message(e)
        if shape is None:
            return
        if math.prod(shape) > MAX_ELEMENTS:
            part = [min(d, CAP_LAST if i == len(shape) - 1 else CAP) for i, d in enumerate(shape)]
            entry["truncated"] = True
            attempt(entry, "values", lambda: self.nested(dset[tuple(slice(0, c) for c in part)], part, tid))
        else:
            attempt(entry, "values", lambda: self.nested(dset[()], shape, tid))

    def attributes(self, oid, entry):
        h5py, h5a, h5s = self.h5py, self.h5py.h5a, self.h5py.h5s
        names = []
        try:
            h5a.iterate(oid, lambda n: names.append(n) and None, index_type=h5py.h5.INDEX_NAME)
        except Exception as e:  # noqa: BLE001
            entry["attributes_error"] = message(e)
            return
        manager = h5py.AttributeManager(h5py.Group(oid) if isinstance(oid, h5py.h5g.GroupID)
                                        else h5py.Dataset(oid) if isinstance(oid, h5py.h5d.DatasetID)
                                        else h5py.Datatype(oid))
        result = {}
        for raw in sorted(names):
            one = {}
            try:
                aid = h5a.open(oid, raw)
                space = aid.get_space()
                tid = aid.get_type()
                null = space.get_simple_extent_type() == h5s.NULL
                shape = None if null else list(space.shape)
                one["shape"] = shape
                one["type"] = type_class(h5py, tid)
                one["size"] = tid.get_size() if fixed_size(h5py, tid) else None
                attempt(one, "value", lambda: None if shape is None else self.nested(manager[raw], shape, tid))
            except Exception as e:  # noqa: BLE001
                one["error"] = message(e)
            result[raw.decode("utf-8", "replace")] = one
        entry["attributes"] = result

    def nested(self, data, shape, tid):
        np = self.np
        if not shape:
            if isinstance(data, np.ndarray) and data.shape == ():
                data = data[()]
            return self.convert(data, tid)
        return [self.nested(data[i], shape[1:], tid) for i in range(shape[0])]

    def convert(self, v, tid):
        h5py, h5t, np = self.h5py, self.h5py.h5t, self.np
        cls = tid.get_class()
        if cls == h5t.INTEGER:
            return int(v)
        if cls == h5t.FLOAT:
            return F32(v) if tid.get_size() <= 4 else float(v)
        if cls == h5t.STRING:
            if v is None:
                return None
            b = v if isinstance(v, bytes) else v.encode("utf-8", "surrogateescape") if isinstance(v, str) else bytes(v)
            if not tid.is_variable_str():
                if tid.get_strpad() == h5t.STR_SPACEPAD:
                    b = b.rstrip(b" ")
                else:
                    b = b.split(b"\x00", 1)[0]
            return b.decode("utf-8" if tid.get_cset() == h5t.CSET_UTF8 else "ascii", "replace")
        if cls == h5t.BITFIELD:
            if isinstance(v, (bytes, np.void)):
                return int.from_bytes(bytes(v), "little" if tid.get_order() == h5t.ORDER_LE else "big")
            return int(v)
        if cls == h5t.OPAQUE:
            return base64.b64encode(v.tobytes() if hasattr(v, "tobytes") else bytes(v)).decode("ascii")
        if cls == h5t.COMPOUND:
            if isinstance(v, (complex, np.complexfloating)):
                part = tid.get_member_type(0)
                return [self.convert(v.real, part), self.convert(v.imag, part)]
            return {tid.get_member_name(i).decode("utf-8", "replace"): self.convert(v[i], tid.get_member_type(i))
                    for i in range(tid.get_nmembers())}
        if cls == h5t.REFERENCE:
            return self.reference(v, tid)
        if cls == h5t.ENUM:
            if isinstance(v, (bool, np.bool_)):
                return bool(v)
            try:
                return tid.enum_nameof(int(v)).decode("utf-8", "replace")
            except Exception:  # noqa: BLE001 -- a value with no name
                return None
        if cls == h5t.VLEN:
            base = tid.get_super()
            return [] if v is None else [self.convert(x, base) for x in v]
        if cls == h5t.ARRAY:
            return self.nested(np.asarray(v), list(tid.get_array_dims()), tid.get_super())
        if cls == getattr(h5t, "COMPLEX", -1):
            part_f32 = tid.get_size() <= 8
            return [F32(v.real) if part_f32 else float(v.real), F32(v.imag) if part_f32 else float(v.imag)]
        raise TypeError(f"no conversion for type class {cls}")

    def reference(self, ref, tid):
        h5py, h5s = self.h5py, self.h5py.h5s
        if not ref:
            return None
        target = self.f[ref]
        path = target.name if target.name is not None else "(anonymous object)"
        if not isinstance(ref, h5py.RegionReference):
            return path
        space = h5py.h5r.get_region(ref, self.f.id)
        kind = space.get_select_type()
        if kind == h5s.SEL_ALL:
            box = [(0, d) for d in space.shape]
        elif kind == h5s.SEL_HYPERSLABS and space.get_select_hyper_nblocks() == 1:
            start, end = space.get_select_bounds()
            box = [(s, e + 1) for s, e in zip(start, end)]
        else:
            return f"{path} ({space.get_select_npoints()} elements)"
        return path + "[" + ", ".join(f"{s}:{e}" for s, e in box) + "]"


def attempt(entry, key, value):
    try:
        entry[key] = value()
    except Exception as e:  # noqa: BLE001
        entry[key + "_error"] = message(e)


def text(b):
    return b.decode("utf-8", "replace") if isinstance(b, bytes) else str(b)


def type_class(h5py, tid):
    h5t = h5py.h5t
    names = {h5t.INTEGER: "integer", h5t.FLOAT: "float", h5t.TIME: "time", h5t.STRING: "string",
             h5t.BITFIELD: "bitfield", h5t.OPAQUE: "opaque", h5t.COMPOUND: "compound", h5t.REFERENCE: "reference",
             h5t.ENUM: "enum", h5t.VLEN: "vlen", h5t.ARRAY: "array", getattr(h5t, "COMPLEX", -1): "complex"}
    return names.get(tid.get_class(), f"class {tid.get_class()}")


def fixed_size(h5py, tid):
    """Whether libhdf5 reports the type's size as stored: not for one holding variable-length data or
    references, which it reports at their size in memory."""
    h5t = h5py.h5t
    cls = tid.get_class()
    if cls in (h5t.VLEN, h5t.REFERENCE) or (cls == h5t.STRING and tid.is_variable_str()):
        return False
    if cls == h5t.COMPOUND:
        return all(fixed_size(h5py, tid.get_member_type(i)) for i in range(tid.get_nmembers()))
    if cls in (h5t.ARRAY, h5t.ENUM):
        return fixed_size(h5py, tid.get_super())
    return True


# ---- comparing manifests --------------------------------------------------------------------------------------

def same(f, h):
    """Falcon's JSON value against h5py's: None if they agree, else where and how they differ."""
    if isinstance(h, F32):
        if isinstance(f, (int, float)) and not isinstance(f, bool):
            import numpy as np
            if (math.isnan(h) and math.isnan(f)) or np.float32(f) == np.float32(h):
                return None
        return f"Falcon {f!r}, h5py {float(h)!r}"
    if isinstance(h, float):
        if isinstance(f, (int, float)) and not isinstance(f, bool) and (f == h or (math.isnan(h) and math.isnan(f))):
            return None
        return f"Falcon {f!r}, h5py {h!r}"
    if isinstance(h, bool) or isinstance(f, bool):
        return None if type(f) is type(h) and f == h else f"Falcon {f!r}, h5py {h!r}"
    if isinstance(h, list):
        if not isinstance(f, list) or len(f) != len(h):
            return f"Falcon {short(f)}, h5py {short(h)}"
        for i, (x, y) in enumerate(zip(f, h)):
            d = same(x, y)
            if d:
                return f"[{i}] {d}"
        return None
    if isinstance(h, dict):
        if not isinstance(f, dict) or set(f) != set(h):
            return f"Falcon {short(f)}, h5py {short(h)}"
        for k in h:
            d = same(f[k], h[k])
            if d:
                return f"[{k!r}] {d}"
        return None
    return None if f == h else f"Falcon {short(f)}, h5py {short(h)}"


def short(v):
    s = json.dumps(v) if not isinstance(v, str) else repr(v)
    return s if len(s) <= 120 else s[:117] + "..."


def errors(manifest):
    """Every error a manifest records."""
    found = []

    def scan(v):
        if isinstance(v, dict):
            for k, x in v.items():
                if (k == "error" or k.endswith("_error")) and isinstance(x, str):
                    found.append(x)
                else:
                    scan(x)
    scan(manifest)
    return found


def untyped(manifest):
    return [e for e in errors(manifest) if e.split(":", 1)[0] not in TYPED]


class Comparison:
    def __init__(self):
        self.differences, self.falcon_more, self.both_refuse = [], [], []

    def fields(self, where, f, h, keys):
        for key in keys:
            fe, he = f.get(key + "_error"), h.get(key + "_error")
            if fe and he:
                self.both_refuse.append(f"{where}: {key}")
            elif fe and key in h:
                self.differences.append(f"{where}: Falcon cannot read the {key}: {fe}")
            elif he and key in f:
                self.falcon_more.append(f"{where}: h5py cannot read the {key}: {he}")
            elif key in f or key in h:
                if key == "size" and h.get(key) is None:
                    continue
                d = same(f.get(key), h.get(key))
                if d:
                    self.differences.append(f"{where}: {key}: {d}")

    def entry(self, path, f, h):
        if f is None or h is None:
            self.differences.append(f"{path}: only {'h5py' if f is None else 'Falcon'} reaches it "
                                    f"({(h or f).get('kind')})")
            return
        if f.get("error") and h.get("error"):
            self.both_refuse.append(path)
            return
        if f.get("error"):
            self.differences.append(f"{path}: Falcon cannot read it: {f['error']}")
            return
        if h.get("error"):
            self.falcon_more.append(f"{path}: h5py cannot read it: {h['error']}")
            return
        if f.get("kind") != h.get("kind"):
            self.differences.append(f"{path}: Falcon finds a {f.get('kind')}, h5py a {h.get('kind')}")
            return
        self.fields(path, f, h, ["same_as", "target", "file", "type", "shape", "size", "layout", "chunks",
                                 "filters", "truncated", "values"])
        fa, ha = f.get("attributes"), h.get("attributes")
        if f.get("attributes_error") or h.get("attributes_error"):
            self.fields(path, f, h, ["attributes"])
            return
        for name in sorted(set(fa or {}) | set(ha or {})):
            where = f"{path} @{name}"
            x, y = (fa or {}).get(name), (ha or {}).get(name)
            if x is None or y is None:
                self.differences.append(f"{where}: only {'h5py' if x is None else 'Falcon'} finds the attribute")
            elif x.get("error") and y.get("error"):
                self.both_refuse.append(where)
            elif x.get("error"):
                self.differences.append(f"{where}: Falcon cannot read it: {x['error']}")
            elif y.get("error"):
                self.falcon_more.append(f"{where}: h5py cannot read it: {y['error']}")
            else:
                self.fields(where, x, y, ["type", "shape", "size", "value"])


def compare(falcon, h5py_side):
    """{return the outcome, the differences, and what Falcon reads that h5py cannot}"""
    c = Comparison()
    if falcon.get("error") and h5py_side.get("error"):
        return "both refuse", [], []
    if falcon.get("error"):
        return "differs", [f"Falcon cannot open it: {falcon['error']}"], []
    if h5py_side.get("error"):
        return "Falcon reads more", [], [f"h5py cannot open it: {h5py_side['error']}"]
    fo, ho = falcon["objects"], h5py_side["objects"]
    # groups whose members h5py could not list: what Falcon finds below them, h5py cannot reach
    unlisted = {p for p, e in ho.items() if e and (e.get("error") or e.get("links_error"))}
    for path in list(fo) + [p for p in ho if p not in fo]:
        if path not in ho and any(path.startswith(g.rstrip("/") + "/") for g in unlisted):
            c.falcon_more.append(f"{path}: h5py cannot reach it")
            continue
        c.entry(path, fo.get(path), ho.get(path))
        if path in fo and path in ho:
            f_error, h_error = fo[path].get("links_error"), ho[path].get("links_error") or ho[path].get("error")
            if f_error and h_error:
                c.both_refuse.append(f"{path}: its links")
            elif f_error:
                c.differences.append(f"{path}: Falcon cannot list its links: {f_error}")
            elif h_error and ho[path].get("links_error"):
                c.falcon_more.append(f"{path}: h5py cannot list its links: {h_error}")
    if c.differences:
        return "differs", c.differences, c.falcon_more
    if c.falcon_more:
        return "Falcon reads more", [], c.falcon_more
    return ("both refuse" if c.both_refuse else "agree"), [], []


# ---- running --------------------------------------------------------------------------------------------------

def fetch():
    directory = WORK / f"hdf5-{HDF5_TAG}"
    if not (directory / ".git").exists():
        WORK.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1", "--branch",
                        HDF5_TAG, "--filter=blob:none", "--sparse", f"https://github.com/{HDF5_REPOSITORY}.git",
                        str(directory)], check=True)
        subprocess.run(["git", "-C", str(directory), "sparse-checkout", "set", "--no-cone", *SPARSE], check=True)
    return directory


def hdf5_files(checkout):
    """Every file of the checkout with HDF5's signature (at 0, or after a user block), or named .h5."""
    found = []
    for directory, subdirectories, files in os.walk(checkout):
        subdirectories[:] = sorted(d for d in subdirectories if d != ".git")
        for name in sorted(files):
            path = pathlib.Path(directory) / name
            with open(path, "rb") as f:
                head = f.read(1 << 20)
            offsets = [0] + [512 << k for k in range(12)]
            if name.endswith(".h5") or any(head[o:o + 8] == SIGNATURE for o in offsets):
                found.append(path)
    return found


def run(command, cwd):
    """{return the JSON line a manifest command printed, or what went wrong}"""
    try:
        done = subprocess.run(command, cwd=cwd, capture_output=True, timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return {"error": f"Timeout: no manifest in {TIMEOUT} s", "timeout": True}
    lines = done.stdout.decode("utf-8", "replace").strip().splitlines()
    try:
        return json.loads(lines[-1], object_hook=lambda o: F32(o["f32"]) if list(o) == ["f32"] else o)
    except (IndexError, ValueError):
        tail = done.stderr.decode("utf-8", "replace").strip().splitlines()[-3:]
        return {"error": f"Crash: status {done.returncode}, no manifest: {' | '.join(tail)}", "crash": True}


def check(path, checkout):
    falcon = run(["java", "-jar", str(JAR), "conformance", f"--hdf5={path.name}"], path.parent)
    h5py_side = run([sys.executable, str(pathlib.Path(__file__).resolve()), "--h5py", path.name], path.parent)
    outcome, differences, falcon_more = compare(falcon, h5py_side)
    bugs = untyped(falcon)
    relative = path.relative_to(checkout).as_posix()
    if outcome == "differs" and relative in KNOWN:
        outcome = "known"
    return {"file": relative, "outcome": outcome, "differences": differences, "falcon_more": falcon_more,
            "untyped": bugs}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("files", nargs="*", help="files of the checkout to check (default: all)")
    parser.add_argument("--jobs", "-j", type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument("--verbose", "-v", action="store_true", help="list every difference")
    parser.add_argument("--h5py", metavar="FILE", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.h5py:
        sys.stdout.write(json.dumps(marked(h5py_manifest(args.h5py))) + "\n")
        return 0
    if not JAR.exists():
        sys.exit(f"{JAR} is missing: run mvn -pl cli -am package -DskipTests first")
    import h5py
    if h5py.version.hdf5_version != HDF5_TAG.removeprefix("hdf5_"):
        print(f"warning: h5py's HDF5 is {h5py.version.hdf5_version}; the files are {HDF5_TAG}'s", file=sys.stderr)
    checkout = fetch()
    files = [checkout / f for f in args.files] if args.files else hdf5_files(checkout)
    with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
        results = list(pool.map(lambda p: check(p, checkout), files))
    (WORK / "report.json").write_text(json.dumps(results, indent=1), encoding="utf-8")

    counts = collections.Counter(r["outcome"] for r in results)
    failed = [r for r in results if r["outcome"] == "differs" or r["untyped"]]
    for r in results:
        if r["outcome"] == "known":
            print(f"known     {r['file']}: {KNOWN[r['file']]}")
        if args.verbose and r["outcome"] == "Falcon reads more":
            print(f"more      {r['file']}: {r['falcon_more'][0]}")
    for r in failed:
        print(f"FAIL      {r['file']}")
        for line in (r["untyped"] and [f"untyped failure: {e}" for e in r["untyped"]]) + r["differences"][
                :None if args.verbose else 5]:
            print(f"            {line}")
        if not args.verbose and len(r["differences"]) > 5:
            print(f"            ... and {len(r['differences']) - 5} more (-v lists them)")
    # only a file that now agrees: one h5py cannot read (as on another platform's h5py) cannot show its difference
    for name in sorted(r["file"] for r in results if r["file"] in KNOWN and r["outcome"] == "agree"):
        print(f"stale     {name}: listed in KNOWN but no longer differs")
    print(f"\n{len(results)} files from {HDF5_REPOSITORY} {HDF5_TAG}, against h5py {h5py.version.version} "
          f"(HDF5 {h5py.version.hdf5_version}): " + ", ".join(f"{counts[k]} {k}" for k in
          ("agree", "both refuse", "Falcon reads more", "known", "differs") if counts[k])
          + (f"; {sum(1 for r in results if r['untyped'])} with untyped Falcon failures" if any(
              r["untyped"] for r in results) else ""))
    print(f"report: {WORK / 'report.json'}")
    summary = {"files": len(results), "subset": bool(args.files), "unexpected": len(failed),
               "untyped": sum(1 for r in results if r["untyped"]), "tag": HDF5_TAG,
               "h5py": h5py.version.version, "hdf5": h5py.version.hdf5_version,
               **{k: counts[k] for k in ("agree", "both refuse", "Falcon reads more", "known", "differs")}}
    (WORK / "summary.json").write_text(json.dumps(summary), encoding="utf-8")  # for run_all.py's report
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
