#!/usr/bin/env python3
"""Records what libhdf5 reports about every dataset's storage in Falcon's HDF5 fixtures, as the oracle
for Dataset.layout(), chunkShape(), filters(), and storageSize():

    python tools/fixtures/gen_storage_metadata.py

writes hdf5/src/test/resources/fixtures/storage_metadata.txt, one line per dataset, tab-separated:

    file  path  layout  chunk-dims|-  storage-size  filter-count  [id  flags  client-data|-  name]...

The layout is libhdf5's H5D_layout_t name (COMPACT, CONTIGUOUS, CHUNKED, VIRTUAL); the storage size is
H5Dget_storage_size; each filter is what H5Pget_filter2 reports. Run it after regenerating fixtures. No
filter plugin is loaded, so filter names come from the file or from libhdf5's own filters, as they would
for any reader.
"""
import os
import sys

import h5py

FIXTURES = os.path.abspath(os.path.join(
    os.path.dirname(__file__), "..", "..", "hdf5", "src", "test", "resources", "fixtures"))
OUT = os.path.join(FIXTURES, "storage_metadata.txt")

LAYOUTS = {
    h5py.h5d.COMPACT: "COMPACT",
    h5py.h5d.CONTIGUOUS: "CONTIGUOUS",
    h5py.h5d.CHUNKED: "CHUNKED",
    h5py.h5d.VIRTUAL: "VIRTUAL",
}


def datasets(f):
    """(path, h5py dataset) for each dataset reachable through hard links, each object once."""
    found = []
    f.visititems(lambda name, obj: found.append(("/" + name, obj)) if isinstance(obj, h5py.Dataset) else None)
    return found


def describe(dataset):
    dcpl = dataset.id.get_create_plist()
    layout = dcpl.get_layout()
    chunks = ",".join(str(c) for c in dcpl.get_chunk()) if layout == h5py.h5d.CHUNKED else "-"
    fields = [LAYOUTS[layout], chunks, str(dataset.id.get_storage_size()), str(dcpl.get_nfilters())]
    for i in range(dcpl.get_nfilters()):
        code, flags, values, name = dcpl.get_filter(i)
        fields += [str(code), str(flags), " ".join(str(v) for v in values) or "-",
                   name.decode("utf-8", "replace")]
    return fields


def main():
    lines = []
    for file in sorted(os.listdir(FIXTURES)):
        if not file.endswith(".h5"):
            continue
        try:
            with h5py.File(os.path.join(FIXTURES, file), "r") as f:
                for path, dataset in datasets(f):
                    lines.append("\t".join([file, path] + describe(dataset)))
        except OSError as e:  # a file libhdf5 itself cannot open is not an oracle
            print(f"skipped {file}: {e}", file=sys.stderr)
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        out.write("".join(line + "\n" for line in lines))
    print(f"wrote {len(lines)} datasets to {OUT}")


if __name__ == "__main__":
    main()
