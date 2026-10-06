"""Generates ZIP archives written by zarr-python's ZipStore, for Falcon's ZipStore to read (P2 F13).

Dev-time tool only, like the other gen_zarr_*_fixtures.py: zarr-python is not a Falcon dependency. Run from
the repo root:  python tools/fixtures/gen_zarr_zip_fixtures.py

zarr-python's ZipStore writes a key again by adding a second entry of the same name (Python's zipfile warns
"Duplicate name"), and reads the last one; Falcon must too. Each archive below writes keys again, and
<name>.expected.json records what zarr-python reads back, and which names the archive holds more than once:

  zip_w.zip   mode "w": a group (attributes changed after), an int32 array whose first chunk is written
              twice, a sharded float32 array written twice, a string array, and a subgroup
  zip_a.zip   zip_w.zip opened again in mode "a": an array added, a chunk and attributes written again

Each expected.json is {"duplicates": [...], "children": {group: [names]}, "attributes": {path: {...}},
"arrays": {path: {"dtype", "shape", "values" (C order)}}}.
"""
import json
import os
import shutil
import warnings
import zipfile
from collections import Counter

import numpy as np
import zarr
from zarr.codecs import ZstdCodec
from zarr.storage import ZipStore

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")


def record(name):
    path = os.path.join(OUT, name + ".zip")
    with zipfile.ZipFile(path) as z:
        counts = Counter(z.namelist())
        assert z.testzip() is None
    store = ZipStore(path, mode="r")
    root = zarr.open_group(store, mode="r")
    children, attributes, arrays = {}, {}, {}

    def walk(group, prefix):
        attributes[prefix.rstrip("/")] = dict(group.attrs)
        children[prefix.rstrip("/")] = sorted(n for n, _ in group.members())
        for n, node in sorted(group.members()):
            if isinstance(node, zarr.Group):
                walk(node, prefix + n + "/")
            else:
                values = node[...]
                arrays[prefix + n] = dict(dtype=str(node.dtype), shape=list(node.shape),
                                          values=[str(v) if node.dtype.kind in "OUT" else v.item()
                                                  for v in np.asarray(values).ravel()])
                attributes[prefix + n] = dict(node.attrs)

    walk(root, "")
    store.close()
    duplicates = sorted(k for k, c in counts.items() if c > 1)
    assert duplicates, f"{name}: no key was written twice"
    meta = dict(duplicates=duplicates, children=children, attributes=attributes, arrays=arrays)
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1, ensure_ascii=False)
    print(f"  {name}.zip  {sum(counts.values())} entries, {len(duplicates)} names twice or more")


def main():
    warnings.filterwarnings("ignore", message="Duplicate name")
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} -> {OUT}")

    w = os.path.join(OUT, "zip_w.zip")
    if os.path.exists(w):
        os.remove(w)
    store = ZipStore(w, mode="w")
    root = zarr.create_group(store, attributes={"title": "zarr-python zip", "version": 1})
    a = root.create_array("a", shape=(10,), chunks=(4,), dtype="int32", compressors=[ZstdCodec(level=3)])
    a[...] = np.arange(10, dtype="int32")
    a[0:4] = np.array([-1, -2, -3, -4], dtype="int32")         # c/0 written again
    s = root.create_array("sharded", shape=(8, 8), chunks=(4, 4), shards=(8, 8), dtype="float32")
    s[...] = np.arange(64, dtype="float32").reshape(8, 8)
    s[2:6, 2:6] = 100                                            # the shard written again
    t = root.create_array("text", shape=(3,), chunks=(2,), dtype=str)
    t[...] = np.array(["zip", "größe", ""], dtype=object)
    g = root.create_group("g", attributes={"level": 1})
    g.create_array("x", shape=(2,), chunks=(2,), dtype="uint8")[...] = np.array([7, 8], dtype="uint8")
    root.attrs.update({"version": 2})                            # zarr.json written again
    store.close()
    record("zip_w")

    appended = os.path.join(OUT, "zip_a.zip")
    shutil.copyfile(w, appended)
    store = ZipStore(appended, mode="a")
    root = zarr.open_group(store, mode="a")
    root.create_array("b", shape=(3,), chunks=(3,), dtype="float64")[...] = np.array([0.5, 1.5, 2.5])
    root["a"][8:10] = np.array([80, 90], dtype="int32")          # c/2 written again
    root["g"].attrs.update({"level": 2})                         # g/zarr.json written again
    store.close()
    record("zip_a")


if __name__ == "__main__":
    main()
