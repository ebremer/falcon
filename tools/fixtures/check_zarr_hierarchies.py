"""Reads every hierarchy WriteZarrHierarchies.java wrote with Falcon back with zarr-python (P2 F2, F6).

Falcon's consolidated metadata, attribute changes, and deletes must look to zarr-python as they look to
Falcon. For each store, manifest.json holds Falcon's view of every node below the root, opened by default
(answering from consolidated metadata where there is some) and node by node; zarr-python must see the same
with use_consolidated=None and use_consolidated=False. Where Falcon consolidated a group, zarr-python must
also open it with use_consolidated=True, and, unless the snapshot is meant to be stale, re-consolidating a
copy with zarr-python must list the same paths in the same order, with the same attributes and each group
carrying the same empty marker. Dev-time tool; zarr-python is not
a Falcon dependency:

    mvn -pl zarr -am compile
    java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZarrHierarchies.java OUT_DIR
    python tools/fixtures/check_zarr_hierarchies.py OUT_DIR
"""
import json
import os
import shutil
import sys
import tempfile
import warnings

import numpy as np
import zarr

warnings.filterwarnings("ignore")  # zarr-python warns that v3 consolidated metadata is not in the spec yet

DATA = {"a1": [1, 2, 3, 4], "B2": [0.5, 1.5, 2.5], "größe": [-1, 1], "g/x": [7, 8], "g/sub/leaf": [0.25]}


def members(root, use_consolidated):
    g = zarr.open_group(root, mode="r", use_consolidated=use_consolidated)
    out = []
    for path, node in g.members(max_depth=None):
        entry = {"path": path, "kind": "array" if isinstance(node, zarr.Array) else "group",
                 "attributes": dict(node.attrs)}
        if entry["kind"] == "array":
            entry["shape"] = list(node.shape)
            entry["dtype"] = str(node.dtype)
        out.append(entry)
    return sorted(out, key=lambda m: m["path"])


def difference(want, got):
    want = sorted(want, key=lambda m: m["path"])
    if [m["path"] for m in want] != [m["path"] for m in got]:
        return f"paths {[m['path'] for m in got]}, Falcon {[m['path'] for m in want]}"
    for w, g in zip(want, got):
        if w != g:
            return f"{w['path']}: zarr-python {g}, Falcon {w}"
    return None


def consolidated_groups(directory):
    for d, _, files in os.walk(directory):
        if "zarr.json" in files:
            doc = json.load(open(os.path.join(d, "zarr.json"), encoding="utf-8"))
            if doc.get("node_type") == "group" and doc.get("consolidated_metadata"):
                rel = os.path.relpath(d, directory).replace(os.sep, "/")
                yield "" if rel == "." else rel


def layout_problem(directory, group):
    """Falcon's consolidated metadata at group lists what zarr-python's would, in the same order."""
    path = os.path.join(directory, group)
    ours = json.load(open(os.path.join(path, "zarr.json"), encoding="utf-8"))["consolidated_metadata"]
    if not zarr.open_group(directory, mode="r", path=group or None, use_consolidated=True).metadata.consolidated_metadata:
        return "zarr-python found no consolidated metadata with use_consolidated=True"
    copy = tempfile.mkdtemp()
    try:
        shutil.copytree(directory, copy, dirs_exist_ok=True)
        zarr.consolidate_metadata(copy, path=group or None)
        theirs = json.load(open(os.path.join(copy, group, "zarr.json"), encoding="utf-8"))["consolidated_metadata"]
    finally:
        shutil.rmtree(copy, ignore_errors=True)
    if {k: v for k, v in ours.items() if k != "metadata"} != {k: v for k, v in theirs.items() if k != "metadata"}:
        return f"members {sorted(ours)} differ from zarr-python's {sorted(theirs)}"
    if list(ours["metadata"]) != list(theirs["metadata"]):
        return f"paths {list(ours['metadata'])}, zarr-python {list(theirs['metadata'])}"
    for key, entry in ours["metadata"].items():
        if entry.get("node_type") == "group" and entry.get("consolidated_metadata") != \
                theirs["metadata"][key].get("consolidated_metadata"):
            return f"{key}: group marker {entry.get('consolidated_metadata')}"
        if entry.get("attributes", {}) != theirs["metadata"][key].get("attributes", {}):
            return f"{key}: attributes {entry.get('attributes')}, zarr-python {theirs['metadata'][key].get('attributes')}"
    return None


def data_problem(directory):
    g = zarr.open_group(directory, mode="r")
    for path, want in DATA.items():
        try:
            got = g[path][...]
        except KeyError:
            continue  # not in this hierarchy (deleted, or not in the snapshot)
        if not np.array_equal(got, np.array(want, dtype=got.dtype)):
            return f"{path}: {got.tolist()}, wrote {want}"
    return None


def main(directory):
    manifest = json.load(open(os.path.join(directory, "manifest.json"), encoding="utf-8"))
    failures = 0
    checks = 0
    for case in manifest:
        name = case["name"]
        root = os.path.join(directory, name)
        problems = []
        for mode, use in (("consolidated", None), ("per_node", False)):
            checks += 1
            try:
                problem = difference(case[mode], members(root, use))
            except Exception as e:  # noqa: BLE001 -- report every case
                problem = f"{type(e).__name__}: {e}"
            if problem:
                problems.append(f"{mode}: {problem}")
        # a fresh snapshot (one that shows what the store holds) is laid out as zarr-python would lay it out
        fresh = case["consolidated"] == case["per_node"]
        for group in consolidated_groups(root) if fresh else ():
            checks += 1
            problem = layout_problem(root, group)
            if problem:
                problems.append(f"layout at '{group}': {problem}")
        checks += 1
        problem = data_problem(root)
        if problem:
            problems.append(f"data: {problem}")
        if problems:
            failures += 1
            for p in problems:
                print(f"FAIL {name}: {p}")
        else:
            print(f"ok   {name}")
    print(f"{len(manifest) - failures} of {len(manifest)} hierarchies read back as Falcon sees them ({checks} checks)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
