#!/usr/bin/env python3
"""Regenerate Falcon's HDF5 test fixtures with h5py (bundling HDF5 2.0).

    python tools/fixtures/gen_fixtures.py

Both fixtures hold the same logical tree; they differ only in on-disk format:

  old_style_groups.h5  default libver     -> v0 superblock, symbol-table groups, v1 object headers
  new_style_groups.h5  libver='latest'    -> v3 superblock, link-message groups, v2 object headers

Logical tree:
  /alpha            (group)
  /alpha/beta       (group)
  /alpha/beta/gamma (dataset, int32[6])
  /alpha/delta      (dataset, float64[3])
  /empty            (group)
  /root_ds          (dataset, int16[4])
"""
import os
import h5py
import numpy as np

OUT = os.path.abspath(os.path.join(
    os.path.dirname(__file__), "..", "..", "hdf5", "src", "test", "resources", "fixtures"))


def build(f):
    alpha = f.create_group("alpha")
    beta = alpha.create_group("beta")
    beta.create_dataset("gamma", data=np.arange(6, dtype="int32"))
    alpha.create_dataset("delta", data=np.array([1.5, 2.5, 3.5], dtype="float64"))
    f.create_group("empty")
    f.create_dataset("root_ds", data=np.arange(4, dtype="int16"))


def main():
    os.makedirs(OUT, exist_ok=True)
    with h5py.File(os.path.join(OUT, "old_style_groups.h5"), "w") as f:
        build(f)
    with h5py.File(os.path.join(OUT, "new_style_groups.h5"), "w", libver="latest") as f:
        build(f)
    print("wrote fixtures to", OUT)
    print("h5py", h5py.__version__, "| bundled HDF5", h5py.version.hdf5_version)


if __name__ == "__main__":
    main()
