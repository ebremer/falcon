"""Generates the Zarr hierarchy and metadata fixtures for P1 with zarr-python (the reference oracle).

Dev-time tool only -- not a Falcon dependency (see requirements.txt; made with zarr-python 3.4.0).
Run from the repo root:  python tools/fixtures/gen_zarr_p1_fixtures.py

Each store goes under zarr/src/test/resources/fixtures/<name>/, and HierarchyFixtureTest checks what
Falcon reads against the values written here:

  p1_hierarchy     a v3 group tree: attributes, nested groups, arrays of three types
  p1_consolidated  the same tree after zarr.consolidate_metadata (consolidated_metadata in zarr.json)
  p1_nan_attrs     NaN / Infinity / -Infinity attributes, which zarr-python writes as bare JSON tokens,
                   in a v3 group, a v3 array, and a v2 array's .zattrs
  p1_v2_cases      v2 arrays: a complex dtype with fill_value null, and dimension_separator null
                   (zarr-python 2 wrote it; zarr-python 3.4 refuses it, so it is patched in afterwards)
  p1_mixed         a v3 group holding readable children next to ones Falcon cannot open: a
                   numpy.datetime64 array (an extension data type) and a v2 "<U8" array
"""
import json, os, shutil
import numpy as np
import zarr
import zarr.storage

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")


def fresh(name):
    path = os.path.join(OUT, name)
    shutil.rmtree(path, ignore_errors=True)
    return path


def tree(root):
    g = zarr.open_group(root, mode="w", attributes={"title": "hierarchy", "version": 1})
    a = g.create_array("a", shape=(4,), chunks=(2,), dtype="int32")
    a[:] = [1, 2, 3, 4]
    sub = g.create_group("g", attributes={"level": 1})
    inner = sub.create_array("inner", shape=(2,), chunks=(2,), dtype="float64")
    inner[:] = [0.5, 1.5]
    deep = sub.create_group("deep")
    leaf = deep.create_array("leaf", shape=(1,), chunks=(1,), dtype="uint8")
    leaf[:] = [7]
    return g


tree(fresh("p1_hierarchy"))

consolidated = fresh("p1_consolidated")
tree(consolidated)
zarr.consolidate_metadata(consolidated)

nan_root = fresh("p1_nan_attrs")
g = zarr.open_group(nan_root, mode="w",
                    attributes={"nan": float("nan"), "inf": float("inf"), "ninf": float("-inf"),
                                "list": [1.0, float("nan")]})
f = g.create_array("f", shape=(3,), chunks=(3,), dtype="float32", fill_value=float("nan"),
                   attributes={"valid_max": float("inf")})
f[:2] = [1.0, 2.0]  # the third element is never written: it reads as the NaN fill
v2 = zarr.create_array(zarr.storage.LocalStore(os.path.join(nan_root, "v2")), shape=(2,), chunks=(2,),
                       dtype="<f8", zarr_format=2, fill_value=float("nan"),
                       attributes={"_FillValue": float("nan"), "valid_min": float("-inf")})
v2[:] = [3.0, 4.0]

v2_root = fresh("p1_v2_cases")
os.makedirs(v2_root)
c16 = zarr.create_array(zarr.storage.LocalStore(os.path.join(v2_root, "c16_null")), shape=(3,),
                        chunks=(2,), dtype="<c16", zarr_format=2, fill_value=None)
c16[:2] = [1 + 2j, 3 - 4j]  # the second chunk is never written: it reads as zeros
sep = zarr.create_array(zarr.storage.LocalStore(os.path.join(v2_root, "sep_null")), shape=(2, 2),
                        chunks=(1, 2), dtype="<i4", zarr_format=2)  # separator "."
sep[:] = [[1, 2], [3, 4]]
zarray = os.path.join(v2_root, "sep_null", ".zarray")
meta = json.load(open(zarray))
meta["dimension_separator"] = None
with open(zarray, "w", newline="\n") as out:  # LF on every platform, as zarr-python writes
    json.dump(meta, out, indent=4)

mixed = fresh("p1_mixed")
g = zarr.open_group(mixed, mode="w")
good = g.create_array("good", shape=(2,), chunks=(2,), dtype="float64")
good[:] = [1.25, 2.5]
when = g.create_array("when", shape=(2,), chunks=(2,), dtype="datetime64[s]")
when[:] = np.array(["2026-01-01T00:00:00", "2026-01-02T00:00:00"], dtype="datetime64[s]")
text = zarr.create_array(zarr.storage.LocalStore(os.path.join(mixed, "text")), shape=(2,), chunks=(2,),
                         dtype="<U8", zarr_format=2)
text[:] = ["alpha", "beta"]
also = g.create_group("zz_group")

for name in ("p1_hierarchy", "p1_consolidated", "p1_nan_attrs", "p1_v2_cases", "p1_mixed"):
    print("wrote", os.path.join(OUT, name))
