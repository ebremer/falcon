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
    f.create_dataset("scaleoffset_f8",  # float decimal-scaling scale-offset (3 digits kept)
                     data=np.array([1.0, 1.5, 2.25, 3.125, 3.14159, 2.71828, 0.5, 10.0], dtype="f8"),
                     chunks=(8,), scaleoffset=3)
    f.create_dataset("scaleoffset_f4", data=np.array([0.5, 1.25, 2.5, 3.75], dtype="f4"),
                     chunks=(4,), scaleoffset=2)


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


def build_compound_nbit(path):
    """A compound n-bit dataset: a 12-bit-precision int16 and a 20-bit-precision uint32 member packed
    into an 8-byte record (via the low-level filter API)."""
    tid = h5py.h5t.create(h5py.h5t.COMPOUND, 8)
    a = h5py.h5t.STD_I16LE.copy(); a.set_precision(12)
    b = h5py.h5t.STD_U32LE.copy(); b.set_precision(20)
    tid.insert(b"a", 0, a)
    tid.insert(b"b", 2, b)
    dc = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dc.set_chunk((4,))
    dc.set_filter(h5py.h5z.FILTER_NBIT, h5py.h5z.FLAG_MANDATORY, ())
    f = h5py.File(path, "w")
    dsid = h5py.h5d.create(f.id, b"c", tid, h5py.h5s.create_simple((4,)), dc)
    data = np.zeros(4, dtype=np.dtype([("a", "<i2"), ("b", "<u4")]))
    data["a"] = [1, -2, 3, -4]; data["b"] = [10, 20, 30, 40]
    dsid.write(h5py.h5s.ALL, h5py.h5s.ALL, data)
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
    ragged = np.empty(3, dtype=object)
    ragged[0] = np.array([1, 2], dtype=np.int32)
    ragged[1] = np.array([], dtype=np.int32)
    ragged[2] = np.array([3, 4, 5], dtype=np.int32)
    d.attrs.create("ragged", data=ragged, dtype=h5py.vlen_dtype(np.int32))


def build_vlen(f):
    """Variable-length datasets whose values live in the global heap: a UTF-8 string dataset, plus
    numeric sequence (ragged array) datasets over int32/int64/float64 base types, each including an
    empty row."""
    f.create_dataset("vstr", data=np.array(["alpha", "beta", "gamma", "delta"],
                                           dtype=h5py.string_dtype("utf-8")))
    di = f.create_dataset("vseq_i4", (4,), dtype=h5py.vlen_dtype(np.int32))
    di[0] = [10]; di[1] = [20, 21]; di[2] = []; di[3] = [30, 31, 32]
    dl = f.create_dataset("vseq_i8", (3,), dtype=h5py.vlen_dtype(np.int64))
    dl[0] = [1]; dl[1] = [2, 3]; dl[2] = [4, 5, 6]
    dd = f.create_dataset("vseq_f8", (3,), dtype=h5py.vlen_dtype(np.float64))
    dd[0] = [1.5, 2.5]; dd[1] = []; dd[2] = [9.25]


def build_dense_links(f):
    """A group with enough links to force dense (fractal-heap + v2 B-tree) storage."""
    g = f.create_group("dense")
    for i in range(20):
        g.create_dataset(f"link{i:02d}", data=np.int32(i))


def build_dense_links_big(f):
    """A group with enough links to push its link fractal heap into indirect blocks and its name index
    into a multi-level (internal-node) v2 B-tree."""
    g = f.create_group("big")
    for i in range(600):
        g.create_dataset(f"link{i:04d}", data=np.int32(i))


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
    f.create_dataset("btree2_gz", data=np.arange(64, dtype="i4").reshape(8, 8),
                     maxshape=(None, None), chunks=(2, 2), compression="gzip")                      # v2 B-tree, filtered
    f.create_dataset("btree2_deep", data=np.arange(1600, dtype="i4").reshape(40, 40),
                     maxshape=(None, None), chunks=(2, 2))                                          # 400 chunks -> BTIN nodes


def build_metadata(f):
    """Object metadata: a comment, a tracked modification time, and a second hard link."""
    plain = f.create_dataset("plain", data=np.arange(3, dtype="i4"))
    f["hardlink"] = plain  # a second hard link -> reference count 2
    f.create_dataset("timed", data=np.arange(3, dtype="i4"), track_times=True)
    commented = f.create_dataset("commented", data=np.arange(3, dtype="i4"))
    h5py.h5o.set_comment(commented.id, b"a helpful comment")


def build_vds(out):
    """A virtual dataset assembling two external source files, plus one with an unmapped (fill) row."""
    for k in range(2):
        with h5py.File(os.path.join(out, f"vds_src{k}.h5"), "w") as f:
            f.create_dataset("data", data=(np.arange(4, dtype="i4") + 10 * k))
    with h5py.File(os.path.join(out, "vds.h5"), "w", libver="latest") as f:
        full = h5py.VirtualLayout(shape=(2, 4), dtype="i4")
        full[0] = h5py.VirtualSource("vds_src0.h5", "data", shape=(4,))
        full[1] = h5py.VirtualSource("vds_src1.h5", "data", shape=(4,))
        f.create_virtual_dataset("vds", full, fillvalue=-1)
        gap = h5py.VirtualLayout(shape=(3, 4), dtype="i4")  # row 1 left unmapped -> fill value
        gap[0] = h5py.VirtualSource("vds_src0.h5", "data", shape=(4,))
        gap[2] = h5py.VirtualSource("vds_src1.h5", "data", shape=(4,))
        f.create_virtual_dataset("vds_gap", gap, fillvalue=-1)
        cols = h5py.VirtualLayout(shape=(4, 2), dtype="i4")  # each source fills a column (a (4,1) block)
        cols[:, 0] = h5py.VirtualSource("vds_src0.h5", "data", shape=(4,))
        cols[:, 1] = h5py.VirtualSource("vds_src1.h5", "data", shape=(4,))
        f.create_virtual_dataset("vds_cols", cols, fillvalue=-1)
        step = h5py.VirtualLayout(shape=(8,), dtype="i4")  # strided: source lands on indices 0,2,4,6
        step[0:8:2] = h5py.VirtualSource("vds_src0.h5", "data", shape=(4,))
        f.create_virtual_dataset("vds_step", step, fillvalue=-1)


def build_references(f):
    """Object references: a dataset and an attribute of references to a dataset, a group, and a nested
    dataset."""
    f.create_dataset("target_a", data=np.arange(5, dtype="i4"))
    g = f.create_group("target_g")
    g.create_dataset("inner", data=np.arange(3, dtype="i4"))
    refs = f.create_dataset("refs", (3,), dtype=h5py.ref_dtype)
    refs[0] = f["target_a"].ref
    refs[1] = f["target_g"].ref
    refs[2] = f["target_g/inner"].ref
    refs.attrs["points_to"] = f["target_a"].ref
    # region references: a 1-D and a 2-D hyperslab selection
    grid = f.create_dataset("grid", data=np.arange(20, dtype="i4").reshape(4, 5))
    rrefs = f.create_dataset("rrefs", (2,), dtype=h5py.regionref_dtype)
    rrefs[0] = f["target_a"].regionref[1:4]      # elements 1,2,3 of arange(5)
    rrefs[1] = grid.regionref[1:3, 1:4]          # rows 1-2, cols 1-3


def build_ea_paged(f):
    """A single-unlimited-dimension dataset with enough chunks (150000) that the extensible-array
    index grows data blocks larger than the page size, forcing paged (checksummed-page) storage."""
    f.create_dataset("d", data=np.arange(150000, dtype="i4"), maxshape=(None,), chunks=(1,))


def build_committed_types(f):
    """A committed (named) datatype shared by two datasets and by an attribute, plus a second
    committed enum type used by a dataset."""
    f["itype"] = np.dtype("i4")                       # committed named datatype at /itype
    itype = f["itype"]
    a = f.create_dataset("a", shape=(4,), dtype=itype)
    a[...] = np.arange(4, dtype="i4")
    b = f.create_dataset("b", shape=(3,), dtype=itype)
    b[...] = np.array([7, 8, 9], dtype="i4")
    a.attrs.create("tag", data=np.int32(42), dtype=itype)  # attribute sharing the committed type
    f["etype"] = h5py.enum_dtype({"RED": 0, "GREEN": 1, "BLUE": 2}, basetype="i4")
    e = f.create_dataset("colors", shape=(3,), dtype=f["etype"])
    e[...] = np.array([2, 0, 1], dtype="i4")


def build_implicit(path):
    """Chunked datasets with early allocation, no filter, and fixed dimensions -> the implicit chunk
    index (version-4/5 layout, index type 2). h5py's high level always emits a fixed array, so this
    uses the low-level dcpl with ALLOC_TIME_EARLY; the 2-D case exercises row-major chunk ordering."""
    fapl = h5py.h5p.create(h5py.h5p.FILE_ACCESS)
    fapl.set_libver_bounds(h5py.h5f.LIBVER_LATEST, h5py.h5f.LIBVER_LATEST)
    fid = h5py.h5f.create(path.encode(), h5py.h5f.ACC_TRUNC, h5py.h5p.DEFAULT, fapl)
    f = h5py.File(fid)
    tid = h5py.h5t.py_create(np.dtype("<i4"))
    dc = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dc.set_chunk((5,))
    dc.set_alloc_time(h5py.h5d.ALLOC_TIME_EARLY)
    d = h5py.h5d.create(f.id, b"impl_1d", tid, h5py.h5s.create_simple((20,)), dc)
    d.write(h5py.h5s.ALL, h5py.h5s.ALL, np.arange(20, dtype="<i4"))
    d.close()
    dc2 = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dc2.set_chunk((2, 3))
    dc2.set_alloc_time(h5py.h5d.ALLOC_TIME_EARLY)
    d2 = h5py.h5d.create(f.id, b"impl_2d", tid, h5py.h5s.create_simple((4, 6)), dc2)
    d2.write(h5py.h5s.ALL, h5py.h5s.ALL, np.arange(24, dtype="<i4").reshape(4, 6))
    d2.close()
    f.close()


def build_freespace(out):
    """A file using the free-space-manager strategy with persisted free space: a File Space Info
    message (type 23) in the superblock extension plus FSHD free-space managers. Deleting a dataset
    leaves persisted free-space sections."""
    with h5py.File(os.path.join(out, "free_space.h5"), "w", libver="latest",
                   fs_strategy="fsm", fs_persist=True, fs_threshold=1) as f:
        f.create_dataset("keep", data=np.arange(50, dtype="i4"))
        f.create_dataset("scratch", data=np.arange(100, dtype="f8"))
        f.create_dataset("more", data=np.arange(40, dtype="i4"))
        del f["scratch"]  # frees space -> persisted free-space section(s)


def build_external(out):
    """A contiguous dataset whose raw data lives in two external raw files (External File List, msg 7).
    h5py writes the data through, creating the .bin files next to the .h5; the second slot starts at a
    non-zero offset within its file."""
    old = os.getcwd()
    os.chdir(out)  # relative external names resolve next to the .h5 file
    try:
        with h5py.File("external.h5", "w", libver="latest") as f:
            dcpl = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
            dcpl.set_external(b"external_a.bin", 0, 24)   # 6 int32 from the file start
            dcpl.set_external(b"external_b.bin", 16, 24)  # 6 int32, 16 bytes into the file
            space = h5py.h5s.create_simple((12,))
            tid = h5py.h5t.py_create(np.dtype("<i4"))
            dsid = h5py.h5d.create(f.id, b"ext", tid, space, dcpl)
            dsid.write(h5py.h5s.ALL, h5py.h5s.ALL, np.arange(12, dtype="<i4"))
            dsid.close()
    finally:
        os.chdir(old)


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
    build_compound_nbit(os.path.join(OUT, "compound_nbit.h5"))
    with h5py.File(os.path.join(OUT, "attributes.h5"), "w") as f:
        build_attributes(f)
    with h5py.File(os.path.join(OUT, "vlen_data.h5"), "w") as f:
        build_vlen(f)
    with h5py.File(os.path.join(OUT, "dense_links.h5"), "w", libver="latest") as f:
        build_dense_links(f)
    with h5py.File(os.path.join(OUT, "dense_links_big.h5"), "w", libver="latest") as f:
        build_dense_links_big(f)
    with h5py.File(os.path.join(OUT, "dense_attrs.h5"), "w", libver="latest") as f:
        build_dense_attrs(f)
    with h5py.File(os.path.join(OUT, "chunk_indexes.h5"), "w", libver="latest") as f:
        build_chunk_indexes(f)
    with h5py.File(os.path.join(OUT, "ea_paged.h5"), "w", libver="latest") as f:
        build_ea_paged(f)
    with h5py.File(os.path.join(OUT, "committed_types.h5"), "w", libver="latest") as f:
        build_committed_types(f)
    with h5py.File(os.path.join(OUT, "references.h5"), "w", libver="latest") as f:
        build_references(f)
    build_vds(OUT)
    with h5py.File(os.path.join(OUT, "metadata.h5"), "w", libver="latest") as f:
        build_metadata(f)
    with h5py.File(os.path.join(OUT, "metadata_old.h5"), "w", libver="earliest") as f:
        build_metadata(f)
    with h5py.File(os.path.join(OUT, "committed_types_old.h5"), "w", libver="earliest") as f:
        build_committed_types(f)  # v0 superblock + symbol-table groups + v1 object headers
    build_external(OUT)
    build_freespace(OUT)
    build_implicit(os.path.join(OUT, "implicit.h5"))
    print("wrote fixtures to", OUT)
    print("h5py", h5py.__version__, "| bundled HDF5", h5py.version.hdf5_version)


if __name__ == "__main__":
    main()
