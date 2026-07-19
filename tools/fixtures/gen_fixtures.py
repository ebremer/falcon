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


def build_chunked(f):
    """Chunked + filtered datasets (earliest libver -> v1 B-tree chunk index) for H4."""
    f.create_dataset("chunk_i4", data=np.arange(10, dtype="i4"), chunks=(3,))
    f.create_dataset("chunk_2d", data=np.arange(24, dtype="i4").reshape(4, 6), chunks=(2, 3))
    f.create_dataset("gzip_i4", data=np.arange(20, dtype="i4"), chunks=(5,),
                     compression="gzip", compression_opts=4)
    f.create_dataset("gzip_f8", data=(np.arange(12, dtype="f8") * 0.25), chunks=(4,),
                     compression="gzip")
    f.create_dataset("shuffle_i4", data=np.arange(20, dtype="i4"), chunks=(5,),
                     shuffle=True, compression="gzip")
    f.create_dataset("fletcher_i4", data=np.arange(20, dtype="i4"), chunks=(5,), fletcher32=True)
    f.create_dataset("scaleoffset_i4", data=np.arange(20, dtype="i4"), chunks=(5,), scaleoffset=0)


def build_nbit(path):
    """An n-bit dataset: 16-bit-precision unsigned stored in 4 bytes (via the generic filter API,
    since h5py's high level does not expose n-bit)."""
    tid = h5py.h5t.py_create(np.dtype("<u4")).copy()
    tid.set_precision(16)
    space = h5py.h5s.create_simple((20,))
    dc = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dc.set_chunk((5,))
    dc.set_filter(h5py.h5z.FILTER_NBIT, h5py.h5z.FLAG_MANDATORY, ())
    f = h5py.File(path, "w")
    dsid = h5py.h5d.create(f.id, b"nbit_u", tid, space, dc)
    dsid.write(h5py.h5s.ALL, h5py.h5s.ALL, np.arange(20, dtype="<u4"))
    dsid.close()
    f.close()


def build_attributes(f):
    """Attributes on the root, a group, and a dataset (string, scalar int/long, float array)."""
    f.attrs["title"] = "hello"
    f.attrs["version"] = np.int32(3)
    g = f.create_group("grp")
    g.attrs["count"] = np.int64(42)
    d = f.create_dataset("data", data=np.arange(6, dtype="i4"))
    d.attrs["units"] = "meters"
    d.attrs["scale"] = np.array([1.5, 2.5, 3.5], dtype="f8")


def build_vlen(f):
    """A variable-length UTF-8 string dataset (values live in the global heap)."""
    f.create_dataset("vstr", data=np.array(["alpha", "beta", "gamma", "delta"],
                                           dtype=h5py.string_dtype("utf-8")))


def build_dense_links(f):
    """A group with enough links to force dense (fractal-heap + v2 B-tree) storage."""
    g = f.create_group("dense")
    for i in range(20):
        g.create_dataset(f"link{i:02d}", data=np.int32(i))


def build_dense_attrs(f):
    """A dataset with enough attributes to force dense (fractal-heap + v2 B-tree) storage."""
    d = f.create_dataset("d", data=np.arange(3, dtype="i4"))
    for i in range(20):
        d.attrs[f"attr{i:02d}"] = np.int32(i * 10)


def build_chunk_indexes(f):
    """Chunked datasets triggering the version-4/5 chunk index types (single/fixed/extensible/v2btree)."""
    f.create_dataset("single", data=np.arange(5, dtype="i4"), chunks=(5,))                        # single chunk
    f.create_dataset("implicit", data=np.arange(10, dtype="i4"), chunks=(5,))                     # -> fixed array
    f.create_dataset("fixed", data=np.arange(20, dtype="i4"), chunks=(5,), compression="gzip")    # fixed array
    f.create_dataset("extensible", data=np.arange(12, dtype="i4"), maxshape=(None,), chunks=(4,)) # extensible array
    # larger extensible arrays: exercise data blocks + a secondary block, and filtered entries
    f.create_dataset("extensible_big", data=np.arange(1200, dtype="i4"), maxshape=(None,), chunks=(4,))
    f.create_dataset("extensible_gz", data=np.arange(200, dtype="i4"), maxshape=(None,),
                     chunks=(5,), compression="gzip")
    f.create_dataset("btree2", data=np.arange(16, dtype="i4").reshape(4, 4),
                     maxshape=(None, None), chunks=(2, 2))                                          # v2 B-tree


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
    with h5py.File(os.path.join(OUT, "chunked_data.h5"), "w") as f:
        build_chunked(f)
    build_nbit(os.path.join(OUT, "nbit_data.h5"))
    with h5py.File(os.path.join(OUT, "attributes.h5"), "w") as f:
        build_attributes(f)
    with h5py.File(os.path.join(OUT, "vlen_data.h5"), "w") as f:
        build_vlen(f)
    with h5py.File(os.path.join(OUT, "dense_links.h5"), "w", libver="latest") as f:
        build_dense_links(f)
    with h5py.File(os.path.join(OUT, "dense_attrs.h5"), "w", libver="latest") as f:
        build_dense_attrs(f)
    with h5py.File(os.path.join(OUT, "chunk_indexes.h5"), "w", libver="latest") as f:
        build_chunk_indexes(f)
    print("wrote fixtures to", OUT)
    print("h5py", h5py.__version__, "| bundled HDF5", h5py.version.hdf5_version)


if __name__ == "__main__":
    main()
