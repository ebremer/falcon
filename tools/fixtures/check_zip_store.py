"""Reads every ZIP archive WriteZipCases.java wrote with Falcon's ZipStore back with zarr-python (P2 F13).

For each archive in manifest.json:
  * Python's zipfile must find it sound (testzip(): every entry's CRC-32), with each name once in its central
    directory (Falcon names only the newest entry of a key written again), and every entry STORED;
  * the Zip64 end records must be there exactly when the manifest expects them;
  * zarr-python's ZipStore (mode "r") must see the nodes, attributes, and values Falcon recorded, opened with
    use_consolidated=False and, where Falcon consolidated the root, with use_consolidated=True too.

Dev-time tool; zarr-python is not a Falcon dependency:

    mvn -pl zarr -am compile
    java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZipCases.java OUT_DIR
    python tools/fixtures/check_zip_store.py OUT_DIR
"""
import json
import os
import sys
import warnings
import zipfile
from collections import Counter

import numpy as np
import zarr
from zarr.storage import ZipStore

warnings.filterwarnings("ignore")  # zarr-python warns that v3 consolidated metadata is not in the spec yet

ZIP64_END = b"PK\x06\x06"
ZIP64_LOCATOR = b"PK\x06\x07"


def check_zipfile(path, want_zip64):
    problems = []
    with zipfile.ZipFile(path) as z:
        bad = z.testzip()
        if bad is not None:
            problems.append(f"CRC-32 fails for {bad}")
        twice = [n for n, c in Counter(z.namelist()).items() if c > 1]
        if twice:
            problems.append(f"names held twice: {twice[:5]}")
        compressed = [i.filename for i in z.infolist() if i.compress_type != zipfile.ZIP_STORED]
        if compressed:
            problems.append(f"entries not STORED: {compressed[:5]}")
        count = len(z.infolist())
    with open(path, "rb") as f:
        f.seek(max(0, os.path.getsize(path) - 22 - 20 - 56))
        tail = f.read()
    has_zip64 = tail.startswith(ZIP64_END) and ZIP64_LOCATOR in tail
    if has_zip64 != want_zip64:
        problems.append(f"Zip64 end records {'present' if has_zip64 else 'absent'}, expected the opposite")
    return problems, count


def nodes(root):
    return sorted(path for path, _ in root.members(max_depth=None))


def check_zarr(path, case, use_consolidated):
    problems = []
    store = ZipStore(path, mode="r")
    try:
        root = zarr.open_group(store, mode="r", use_consolidated=use_consolidated)
        got = nodes(root)
        if got != sorted(case["nodes"]):
            problems.append(f"nodes {got}, Falcon {sorted(case['nodes'])}")
        for node_path, attributes in case["attributes"].items():
            node = root if node_path == "" else root[node_path]
            if dict(node.attrs) != attributes:
                problems.append(f"attributes of '{node_path}': {dict(node.attrs)}, Falcon {attributes}")
        for array_path, want in case["arrays"].items():
            values = np.asarray(root[array_path][...]).ravel()
            if want["dtype"] == "string":
                ok = [str(v) for v in values] == want["values"]
            else:
                ok = np.array_equal(values.astype("float64"), np.array(want["values"], dtype="float64"))
            if not ok:
                problems.append(f"values of '{array_path}' differ")
    finally:
        store.close()
    return problems


def main(directory):
    manifest = json.load(open(os.path.join(directory, "manifest.json"), encoding="utf-8"))
    ok = failed = 0
    for case in manifest:
        path = os.path.join(directory, case["name"] + ".zip")
        problems, count = check_zipfile(path, case["zip64"])
        for use_consolidated in ([False, True] if case["consolidated"] else [False]):
            label = "(consolidated) " if use_consolidated else ""
            try:
                problems += [label + p for p in check_zarr(path, case, use_consolidated)]
            except Exception as e:  # noqa: BLE001 -- a damaged archive fails to read; report it
                problems.append(f"{label}zarr-python failed: {e}")
        arrays = sum(len(a["values"]) for a in case["arrays"].values())
        if problems:
            failed += 1
            print(f"  {case['name']}: " + "; ".join(problems))
        else:
            ok += 1
            print(f"  {case['name']}: {count} entries, {len(case['nodes'])} nodes, {arrays} values"
                  + (", Zip64" if case["zip64"] else "") + (", consolidated" if case["consolidated"] else "")
                  + ": ok")
    print(f"zarr-python {zarr.__version__} and zipfile read {ok}/{ok + failed} Falcon-written archives correctly")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
