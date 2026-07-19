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


def build_types(f):
    """One dataset per datatype class, plus a variety of dataspaces (H2 fixture)."""
    specs = [
        ("i1", np.int8), ("i2", np.int16), ("i4", np.int32), ("i8", np.int64),
        ("u1", np.uint8), ("u2", np.uint16), ("u4", np.uint32), ("u8", np.uint64),
        ("be_i4", np.dtype(">i4")), ("f2", np.float16), ("f4", np.float32),
        ("f8", np.float64), ("be_f8", np.dtype(">f8")),
        ("fixed_str", np.dtype("S10")), ("vlen_str", h5py.string_dtype("utf-8")),
        ("vlen_i4", h5py.vlen_dtype(np.int32)),
        ("compound", np.dtype([("a", np.int32), ("b", np.float64)])),
        ("comp_arr", np.dtype([("x", np.int16), ("arr", np.float32, (2, 3))])),
        ("enum", h5py.enum_dtype({"RED": 0, "GREEN": 1, "BLUE": 2}, basetype="i4")),
        ("boolean", np.bool_), ("objref", h5py.ref_dtype),
        ("opaque", np.dtype("V8")), ("c8", np.complex64), ("c16", np.complex128),
    ]
    for name, dt in specs:
        f.create_dataset(name, shape=(3,), dtype=dt)
    f.create_dataset("scalar", shape=(), dtype="i4")
    f.create_dataset("twod", shape=(2, 3), dtype="i4")
    f.create_dataset("unlimited", shape=(3,), maxshape=(None,), dtype="i4", chunks=(3,))
    f.create_dataset("array_std", shape=(4,), dtype=np.dtype(("f4", (2, 3))))


def _compact(f, name, arr):
    space = h5py.h5s.create_simple(arr.shape)
    tid = h5py.h5t.py_create(arr.dtype, logical=True)
    dcpl = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dcpl.set_layout(h5py.h5d.COMPACT)
    dsid = h5py.h5d.create(f.id, name.encode(), tid, space, dcpl)
    dsid.write(h5py.h5s.ALL, h5py.h5s.ALL, np.ascontiguousarray(arr))


def build_data(f):
    """Contiguous, compact, and unallocated datasets for the H3 read path."""
    f.create_dataset("c_i4", data=np.arange(5, dtype="i4"))
    f.create_dataset("c_f8", data=np.array([1.5, 2.5, 3.5, -4.25], dtype="f8"))
    f.create_dataset("c_be_i4", data=np.arange(5, dtype=">i4"))
    f.create_dataset("c_f4", data=np.array([0.5, 1.5, 2.5], dtype="f4"))
    f.create_dataset("c_u1", data=np.array([1, 2, 255], dtype="u1"))
    f.create_dataset("c_2d", data=np.arange(6, dtype="i4").reshape(2, 3))
    f.create_dataset("c_str", data=np.array([b"abc", b"de", b"fghij", b""], dtype="S5"))
    _compact(f, "compact_i4", np.array([10, 20, 30], dtype="i4"))
    f.create_dataset("unwritten", shape=(4,), dtype="i4", fillvalue=7)


def main():
    os.makedirs(OUT, exist_ok=True)
    with h5py.File(os.path.join(OUT, "old_style_groups.h5"), "w") as f:
        build(f)
    with h5py.File(os.path.join(OUT, "new_style_groups.h5"), "w", libver="latest") as f:
        build(f)
    with h5py.File(os.path.join(OUT, "datatypes.h5"), "w") as f:
        build_types(f)
    with h5py.File(os.path.join(OUT, "data_contiguous.h5"), "w") as f:
        build_data(f)
    print("wrote fixtures to", OUT)
    print("h5py", h5py.__version__, "| bundled HDF5", h5py.version.hdf5_version)


if __name__ == "__main__":
    main()
