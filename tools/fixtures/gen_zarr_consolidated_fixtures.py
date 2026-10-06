"""Generates the consolidated-metadata fixtures (P2 F2) with zarr-python (the reference oracle).

Dev-time tool only -- not a Falcon dependency (made with zarr-python 3.4.0). Run from the repo root:

    python tools/fixtures/gen_zarr_consolidated_fixtures.py

Each store goes under zarr/src/test/resources/fixtures/<name>/, with a <name>.members.json sidecar holding
what zarr-python sees in it: every node below the root (path, kind, attributes, and for an array its
shape and data type), once as zarr-python opens it by default, answering from consolidated metadata
wherever there is some (use_consolidated=None: "consolidated"), and once reading each node's own metadata
(use_consolidated=False: "per_node"). ConsolidatedFixtureTest checks Falcon sees the same.

  consolidated_v3        a v3 tree after zarr.consolidate_metadata: nested groups, an empty group, arrays
                         of three types, a non-ASCII name, and an array Falcon cannot open (datetime64)
  consolidated_v2        a v2 tree after zarr.consolidate_metadata: the .zmetadata key
  consolidated_subgroup  a v3 tree consolidated at group "g" only, not at the root
  consolidated_stale     consolidated_v3's tree changed after consolidation without consolidating again:
                         an array added, a group's attributes changed, an array's directory removed. The
                         snapshot still describes the tree as it was, for zarr-python as for Falcon.
"""
import json
import os
import shutil
import warnings

import numpy as np
import zarr
import zarr.storage

warnings.filterwarnings("ignore")  # zarr-python warns that v3 consolidated metadata is not in the spec yet

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")


def fresh(name):
    path = os.path.join(OUT, name)
    shutil.rmtree(path, ignore_errors=True)
    return path


def members(root, use_consolidated):
    g = zarr.open_group(root, mode="r", use_consolidated=use_consolidated)
    out = []
    for path, node in sorted(g.members(max_depth=None), key=lambda m: m[0]):
        entry = {"path": path, "kind": "array" if isinstance(node, zarr.Array) else "group",
                 "attributes": dict(node.attrs)}
        if entry["kind"] == "array":
            entry["shape"] = list(node.shape)
            entry["dtype"] = str(node.dtype)
        out.append(entry)
    return out


def sidecar(name):
    root = os.path.join(OUT, name)
    doc = {"consolidated": members(root, None), "per_node": members(root, False)}
    with open(os.path.join(OUT, name + ".members.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
        f.write("\n")


def tree(root, zarr_format=3):
    g = zarr.open_group(root, mode="w", zarr_format=zarr_format,
                        attributes={"title": "consolidated", "nested": {"list": [1, 2.5, "x"], "flag": True}})
    a = g.create_array("a", shape=(4,), chunks=(2,), dtype="int32")
    a[:] = [1, 2, 3, 4]
    sub = g.create_group("g", attributes={"level": 1})
    inner = sub.create_array("inner", shape=(2, 3), chunks=(2, 2), dtype="float64",
                             attributes={"units": "m"})
    inner[:] = [[0.5, 1.5, 2.5], [3.5, 4.5, 5.5]]
    deep = sub.create_group("deep")
    leaf = deep.create_array("leaf", shape=(1,), chunks=(1,), dtype="uint8")
    leaf[:] = [7]
    g.create_group("empty")
    if zarr_format == 3:
        named = g.create_array("größe", shape=(2,), chunks=(2,), dtype="int16", attributes={"ä": "ö"})
        named[:] = [-1, 1]
        when = g.create_array("when", shape=(2,), chunks=(2,), dtype="datetime64[s]")
        when[:] = np.array(["2026-01-01T00:00:00", "2026-01-02T00:00:00"], dtype="datetime64[s]")
    return g


v3 = fresh("consolidated_v3")
tree(v3)
zarr.consolidate_metadata(v3)
sidecar("consolidated_v3")

v2 = fresh("consolidated_v2")
tree(v2, zarr_format=2)
zarr.consolidate_metadata(v2)
sidecar("consolidated_v2")

sub = fresh("consolidated_subgroup")
tree(sub)
zarr.consolidate_metadata(sub, path="g")
sidecar("consolidated_subgroup")

stale = fresh("consolidated_stale")
tree(stale)
zarr.consolidate_metadata(stale)
root = zarr.open_group(stale, mode="r+", use_consolidated=False)
late = root.create_array("late", shape=(3,), chunks=(3,), dtype="int8")
late[:] = [1, 2, 3]
root["g"].attrs.put({"level": 2})
shutil.rmtree(os.path.join(stale, "a"))  # gone from the store, still in the snapshot
sidecar("consolidated_stale")

for name in ("consolidated_v3", "consolidated_v2", "consolidated_subgroup", "consolidated_stale"):
    print("wrote", os.path.join(OUT, name))
