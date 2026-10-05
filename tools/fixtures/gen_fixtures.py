#!/usr/bin/env python3
"""Regenerate Falcon's HDF5 test fixtures with h5py (bundling HDF5 2.0).

    python tools/fixtures/gen_fixtures.py              # every fixture
    python tools/fixtures/gen_fixtures.py szip unwritten  # just the named ones (see FIXTURES)

Requirements: tools/fixtures/requirements.txt (h5py 3.16 / HDF5 2.0, numpy, and imagecodecs for the
real-format szip chunks).

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
import ctypes
import os
import sys
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


# --------------------------------------------------------------------------------------------------
# Fixtures added with the 2026-10 review fixes. Each reproduces a real-world file shape the reader once
# got wrong. Datasets whose correct values are not simply an arange carry an "expected" attribute
# holding h5py's own read-back, so tests compare against the oracle directly.


def _expect(ds):
    """Stores h5py's own read-back of ``ds`` as its 'expected' attribute (the oracle value)."""
    ds.attrs["expected"] = ds[...]


def build_userblock(out):
    """Files that begin with a user block, as MATLAB v7.3 .mat files do: the superblock, and the base
    address every other file address is relative to, sit at offset 512 / 1024 instead of 0."""
    for name, libver, size in (("userblock_v0.h5", "earliest", 512), ("userblock_v3.h5", "latest", 1024)):
        path = os.path.join(out, name)
        with h5py.File(path, "w", libver=libver, userblock_size=size) as f:
            f.create_group("grp").create_dataset("data", data=np.arange(6, dtype="i4"))
            f.create_dataset("chunked", data=np.arange(40, dtype="f8"), chunks=(8,), compression="gzip")
            f.create_dataset("strings", data=np.array(["alpha", "beta"], dtype=object),
                             dtype=h5py.string_dtype())
            f.attrs["note"] = "user block"
        with open(path, "r+b") as fh:  # the user block is opaque to HDF5; give it a .mat-like header
            fh.write(b"MATLAB 7.3 MAT-file, Platform: falcon fixture".ljust(116, b" "))


def build_chunk_maxshape(f):
    """Chunk indexes whose linear chunk numbering runs over the *maximum* chunk grid: fixed arrays (and
    the implicit index) with a fixed maximum larger than the current shape, and extensible arrays whose
    unlimited dimension is not the first (libhdf5 swizzles it to the slowest position)."""
    def mk(name, shape, maxshape, chunks, **kw):
        d = f.create_dataset(name, shape=shape, maxshape=maxshape, chunks=chunks, dtype="i4", **kw)
        d[...] = np.arange(int(np.prod(shape)), dtype="i4").reshape(shape)
    mk("fa_wider_max", (4, 4), (4, 8), (2, 2))                # fixed array, gap along the last dim
    mk("fa_max_3d", (2, 3, 4), (4, 6, 8), (1, 2, 3))          # every dimension below its maximum
    mk("fa_wider_max_gz", (4, 4), (4, 8), (2, 2), compression="gzip")
    mk("ea_unlim_last", (3, 8), (3, None), (1, 4))            # extensible array, unlimited dim swizzled
    mk("ea_unlim_mid", (2, 3, 4), (2, None, 4), (1, 2, 2))
    mk("ea_unlim_first", (5, 6), (None, 6), (2, 3))
    mk("ea_unlim_last_gz", (3, 8), (3, None), (1, 4), compression="gzip")
    mk("ea_unlim_last_wide", (3, 8), (6, None), (1, 4))       # ...and the fixed dim below its maximum
    mk("bt2_two_unlim", (5, 7), (None, None), (2, 3))
    # implicit index (early allocation, no filters, fixed maximum) with a wider maximum
    dc = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dc.set_chunk((2, 2))
    dc.set_alloc_time(h5py.h5d.ALLOC_TIME_EARLY)
    space = h5py.h5s.create_simple((4, 4), (4, 8))
    d = h5py.h5d.create(f.id, b"implicit_wider_max", h5py.h5t.py_create(np.dtype("<i4")), space, dc)
    d.write(h5py.h5s.ALL, h5py.h5s.ALL, np.arange(16, dtype="<i4").reshape(4, 4))
    d.close()


def build_layout_v4(f):
    """The HDF5 1.10-1.14 file format (layout message version 4): a filtered fixed- or extensible-array
    entry stores the chunk size in 1 + ceil-ish(log2(chunk bytes)) bytes, not "size of lengths"."""
    f.create_dataset("fa_gzip", data=np.arange(4000, dtype="i4"), chunks=(1000,), compression="gzip")
    f.create_dataset("fa_big_chunk", data=np.arange(60000, dtype="f8"), chunks=(20000,),
                     compression="gzip", shuffle=True)
    f.create_dataset("fa_fletcher", data=np.arange(30, dtype="i2"), chunks=(7,), fletcher32=True)
    f.create_dataset("ea_gzip", data=np.arange(300, dtype="i4"), maxshape=(None,), chunks=(10,),
                     compression="gzip")
    f.create_dataset("bt2_gzip", data=np.arange(64, dtype="i4").reshape(8, 8), maxshape=(None, None),
                     chunks=(3, 3), compression="gzip")
    f.create_dataset("single_gzip", data=np.arange(500, dtype="i4"), chunks=(500,), compression="gzip")
    f.create_dataset("fa_plain", data=np.arange(50, dtype="i4"), chunks=(10,))


def build_paged_sparse(f):
    """Paged chunk-index blocks: a fully written paged fixed array, and sparse fixed/extensible arrays
    whose never-written pages have a clear page-init bit (their bytes on disk are not entries)."""
    ea = f.create_dataset("ea_sparse", shape=(300000,), maxshape=(None,), chunks=(1,), dtype="i4",
                          fillvalue=-7)
    for i in (0, 5000, 123456, 299999):
        ea[i] = i
    ea[2000:2010] = np.arange(2000, 2010, dtype="i4")
    f.create_dataset("fa_paged", data=np.arange(5000, dtype="i4"), chunks=(2,))       # 2500 entries
    f.create_dataset("fa_paged_gz", data=np.arange(5000, dtype="i4"), chunks=(2,), compression="gzip")
    fs = f.create_dataset("fa_paged_sparse", shape=(5000,), chunks=(2,), dtype="i4", fillvalue=-7)
    fs[0:10] = np.arange(10, dtype="i4")
    fs[4990:5000] = np.arange(4990, 5000, dtype="i4")


def build_filtered_single(f):
    """Single-chunk index (one chunk covers the dataset) with filters: the layout message itself
    records the chunk's filtered size and filter mask."""
    f.create_dataset("single_gzip", data=np.arange(1000, dtype="i4"), chunks=(1000,), compression="gzip")
    f.create_dataset("single_fletcher", data=np.arange(100, dtype="i4"), chunks=(100,), fletcher32=True)
    f.create_dataset("single_shuffle_gzip", data=np.arange(64, dtype="f8").reshape(8, 8), chunks=(8, 8),
                     shuffle=True, compression="gzip")
    m = f.create_dataset("single_masked", shape=(16,), dtype="i4", chunks=(16,), compression="gzip")
    m.id.write_direct_chunk((0,), np.arange(16, dtype="<i4").tobytes(), filter_mask=1)  # gzip skipped


def build_unwritten(out):
    """Chunked datasets that were created but never written (no chunk index is allocated, so the index
    address is undefined) and variable-length strings with unwritten (null) elements."""
    with h5py.File(os.path.join(out, "unwritten_earliest.h5"), "w") as f:  # v1 B-tree index
        f.create_dataset("chunked", shape=(10,), dtype="i4", chunks=(5,), fillvalue=7)
        f.create_dataset("chunked_gz", shape=(4, 4), dtype="f8", chunks=(2, 2), fillvalue=1.5,
                         compression="gzip")
        v = f.create_dataset("vlen", shape=(3,), dtype=h5py.string_dtype())
        v[0] = "only"
    with h5py.File(os.path.join(out, "unwritten_latest.h5"), "w", libver="latest") as f:
        f.create_dataset("single", shape=(10,), dtype="i4", chunks=(10,), fillvalue=7)
        f.create_dataset("fixed", shape=(10,), dtype="i4", chunks=(5,), fillvalue=7)
        f.create_dataset("fixed_gz", shape=(10,), dtype="i4", chunks=(5,), fillvalue=7, compression="gzip")
        f.create_dataset("extensible", shape=(10,), maxshape=(None,), dtype="i4", chunks=(5,), fillvalue=7)
        f.create_dataset("btree_v2", shape=(4, 4), maxshape=(None, None), dtype="i4", chunks=(2, 2),
                         fillvalue=7)
        v = f.create_dataset("vlen_chunked", shape=(6,), maxshape=(None,), chunks=(2,),
                             dtype=h5py.string_dtype())
        v[1] = "one"
        v[4] = "four"


def build_scaleoffset(path):
    """Scale-offset (filter 6) cases exercising libhdf5's exact layout: power-of-two chunks (whose packed
    buffer carries a spare byte), defined fill values, big-endian data, full-precision (raw-copy)
    chunks, and decimal-scaled floats with a fill value. Float decimal scaling is lossy, so the
    'expected' values are read back only after the file is closed and reopened -- straight after a
    write, libhdf5 would serve the still-cached unfiltered chunks."""
    with h5py.File(path, "w", libver="latest") as f:
        _build_scaleoffset(f)
    with h5py.File(path, "r+") as f:
        for ds in f.values():
            _expect(ds)
    _scaleoffset_vectors(path, os.path.join(os.path.dirname(path), "scaleoffset_chunks.txt"))


def _scaleoffset_vectors(path, out):
    """libhdf5's exact scale-offset chunk bytes for the little-endian integer datasets, with the chunk
    input (edge chunks padded with the fill value, as libhdf5 does) and the client data -- so Falcon's
    encoder can be checked byte for byte."""
    lines = ["# scale-offset chunks written by libhdf5. Fields: size signed cd-values(comma) input-hex output-hex"]
    with h5py.File(path, "r") as f:
        for ds in f.values():
            dt = ds.dtype
            if dt.kind not in "iu" or dt.byteorder == ">":
                continue
            cd = ds.id.get_create_plist().get_filter(0)[2]
            data = ds[...]
            fill = ds.fillvalue
            for index in np.ndindex(*[-(-n // c) for n, c in zip(ds.shape, ds.chunks)]):
                start = tuple(i * c for i, c in zip(index, ds.chunks))
                part = data[tuple(slice(s, min(s + c, n)) for s, c, n in zip(start, ds.chunks, ds.shape))]
                block = np.full(ds.chunks, fill, dtype=dt)
                block[tuple(slice(0, p) for p in part.shape)] = part
                mask, raw = ds.id.read_direct_chunk(start)
                assert mask == 0
                lines.append(f"{dt.itemsize} {int(dt.kind == 'i')} {','.join(map(str, cd))} "
                             f"{block.astype(dt.newbyteorder('<')).tobytes().hex()} {bytes(raw).hex()}")
    with open(out, "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


def _build_scaleoffset(f):
    def so(name, data, chunks, factor=0, **kw):
        f.create_dataset(name, data=data, chunks=chunks, scaleoffset=factor, **kw)
    so("i4_256", np.arange(256, dtype="i4"), (256,))
    so("i4_offset", np.arange(1000, 1016, dtype="i4"), (16,))
    so("u1_full", np.arange(256, dtype="u1"), (256,))
    so("be_i4", np.arange(0, 300, 3, dtype=">i4"), (64,))
    so("i4_fill", np.array([-1, 5, 6, -1, 100, 7, 8, -1, 3, -1], dtype="i4"), (5,), fillvalue=-1)
    so("i4_full_range", np.array([-2000000000, 2000000000, 0, 5], dtype="i4"), (4,))
    so("i4_const", np.full(16, 7, dtype="i4"), (16,))
    so("i2_neg", np.arange(-50, 50, 3, dtype="i2"), (8,))
    so("u8_big", np.arange(20, dtype="u8") + 2 ** 40, (8,))
    so("i8_neg", np.arange(-10, 10, dtype="i8") * 1000003, (6,))
    so("f4_d2", np.array([25.5, 1.25, -3.75, 0.0, 7.125, 100.0, -0.5, 3.0], dtype="f4"), (4,), factor=2)
    so("f8_d3_fill", np.array([1.5, -999.0, 2.25, 3.125, -999.0, 0.001, 9.999], dtype="f8"), (4,),
       factor=3, fillvalue=-999.0)
    so("be_f8_d2", np.array([1.5, 2.25, -3.75, 10.0], dtype=">f8"), (4,), factor=2)


# szip option-mask bits (szlib.h) and the libhdf5 limits H5Z__set_local_szip applies
SZ_K13, SZ_EC, SZ_LSB, SZ_MSB, SZ_NN, SZ_RAW = 1, 4, 8, 16, 32, 128
SZ_MAX_BLOCKS_PER_SCANLINE, SZ_MAX_PIXELS_PER_SCANLINE = 128, 4096


def _szip_cd(dtype, chunks, nn, ppb):
    """The four szip client-data values libhdf5 computes for a chunk shape (H5Pset_szip adds K13 and
    RAW; H5Z__set_local_szip adds the byte order and derives bits-per-pixel and the scanline)."""
    mask = SZ_K13 | SZ_RAW | (SZ_NN if nn else SZ_EC) | (SZ_MSB if dtype.byteorder == ">" else SZ_LSB)
    bpp = dtype.itemsize * 8
    if bpp > 24:
        bpp = 32 if bpp <= 32 else 64
    scanline = chunks[-1]
    if scanline < ppb:
        scanline = min(ppb * SZ_MAX_BLOCKS_PER_SCANLINE, int(np.prod(chunks)))
    elif scanline <= SZ_MAX_PIXELS_PER_SCANLINE:
        scanline = min(ppb * SZ_MAX_BLOCKS_PER_SCANLINE, scanline)
    else:
        scanline = ppb * SZ_MAX_BLOCKS_PER_SCANLINE
    return (mask, ppb, bpp, scanline)


def _szip_dataset(f, name, data, chunks, nn, ppb):
    """Writes real-format szip chunks. h5py ships szip disabled, so the chunks come from libaec's szip
    compatibility layer via imagecodecs.szip_encode(header=True) -- byte-identical to what libhdf5 +
    libaec write (checked against HDF5 1.14.4 output) -- and are stored with write_direct_chunk under
    an (optional, unregistered) szip filter carrying libhdf5's client data."""
    import imagecodecs
    data = np.ascontiguousarray(data)
    cd = _szip_cd(data.dtype, chunks, nn, ppb)
    dcpl = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dcpl.set_chunk(chunks)
    dcpl.set_filter(h5py.h5z.FILTER_SZIP, h5py.h5z.FLAG_OPTIONAL, cd)
    dsid = h5py.h5d.create(f.id, name.encode(), h5py.h5t.py_create(data.dtype),
                           h5py.h5s.create_simple(data.shape), dcpl)
    grid = [-(-n // c) for n, c in zip(data.shape, chunks)]
    for idx in np.ndindex(*grid):
        start = tuple(i * c for i, c in zip(idx, chunks))
        part = data[tuple(slice(s, min(s + c, n)) for s, c, n in zip(start, chunks, data.shape))]
        block = np.zeros(chunks, dtype=data.dtype)  # edge chunks are padded with the fill value (0)
        block[tuple(slice(0, p) for p in part.shape)] = part
        raw = block.tobytes()
        enc = bytes(imagecodecs.szip_encode(raw, options_mask=cd[0], pixels_per_block=cd[1],
                                            bits_per_pixel=cd[2], pixels_per_scanline=cd[3], header=True))
        if len(enc) - 4 > len(raw):  # libhdf5: an szip overflow fails the optional filter -> stored raw
            dsid.write_direct_chunk(start, raw, filter_mask=1)
        else:
            dsid.write_direct_chunk(start, enc, filter_mask=0)
    h5py.Dataset(dsid).attrs["expected"] = data


def build_szip(f):
    """szip (filter 4) chunks in the exact on-disk form libhdf5 writes: a 4-byte uncompressed-size
    header, 32/64-bit pixels byte-interleaved and coded as 8-bit samples, scanlines padded to whole
    blocks, both EC and NN coding, both byte orders."""
    _szip_dataset(f, "i16_ec", np.array([(i * 37) % 500 for i in range(256)], "<i2"), (256,), False, 16)
    _szip_dataset(f, "u16_pad_nn", (np.arange(1000) % 1000).astype("<u2").reshape(10, 100), (10, 100), True, 32)
    _szip_dataset(f, "f32_nn", (np.arange(1024) * 0.5).astype("<f4"), (1024,), True, 32)
    _szip_dataset(f, "i32_nn_multi", (np.arange(1000) * 3 - 700).astype("<i4"), (256,), True, 8)
    _szip_dataset(f, "f64_nn", np.sin(np.arange(512) / 20.0).astype("<f8"), (512,), True, 16)
    _szip_dataset(f, "u8_ec_pad", (np.arange(300) % 251).astype("u1"), (300,), False, 8)
    _szip_dataset(f, "be_i16_nn", (np.arange(200) * 5 - 300).astype(">i2"), (200,), True, 8)
    _szip_dataset(f, "i32_2d", np.arange(600, dtype="<i4").reshape(20, 30), (5, 30), True, 8)
    _szip_dataset(f, "i16_small_scanline", np.arange(160, dtype="<i2").reshape(40, 4), (40, 4), False, 8)


def _hdf5_library():
    """h5py's bundled libhdf5 (for the few properties h5py does not wrap), or None if not found."""
    import ctypes
    import glob
    base = os.path.dirname(h5py.__file__)
    for pattern in ("hdf5.dll", os.path.join("..", "h5py.libs", "libhdf5-*.so*"),
                    os.path.join(".dylibs", "libhdf5*.dylib")):
        hits = glob.glob(os.path.join(base, pattern))
        if hits:
            return ctypes.CDLL(hits[0])
    return None


def build_filter_edge(f):
    """Filter edge cases: partial edge chunks stored unfiltered (H5Pset_chunk_opts with
    H5D_CHUNK_DONT_FILTER_PARTIAL_CHUNKS, reached through h5py's bundled libhdf5) and n-bit at full
    precision (the filter stores the chunk untouched and flags 'no compression needed')."""
    import ctypes
    lib = _hdf5_library()
    if lib is None:
        raise RuntimeError("cannot locate h5py's libhdf5 for H5Pset_chunk_opts")
    dc = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dc.set_chunk((4,))
    dc.set_deflate(4)
    if lib.H5Pset_chunk_opts(ctypes.c_int64(dc.id), ctypes.c_uint(0x0002)) < 0:
        raise RuntimeError("H5Pset_chunk_opts failed")
    d = h5py.h5d.create(f.id, b"dont_filter_partial", h5py.h5t.py_create(np.dtype("<i4")),
                        h5py.h5s.create_simple((10,)), dc)
    d.write(h5py.h5s.ALL, h5py.h5s.ALL, np.arange(10, dtype="<i4"))
    d.close()
    for name, dt in ((b"nbit_full_le", "<i4"), (b"nbit_full_be", ">i4")):
        nb = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
        nb.set_chunk((5,))
        nb.set_filter(h5py.h5z.FILTER_NBIT, h5py.h5z.FLAG_MANDATORY, ())
        d = h5py.h5d.create(f.id, name, h5py.h5t.py_create(np.dtype(dt)), h5py.h5s.create_simple((12,)), nb)
        d.write(h5py.h5s.ALL, h5py.h5s.ALL, (np.arange(12) * 1000 - 3000).astype(dt))
        d.close()


def build_vds_loop(out):
    """A virtual dataset whose only source is itself (by file name): reading it must fail cleanly rather
    than recurse until the stack overflows."""
    path = os.path.join(out, "vds_loop.h5")
    with h5py.File(path, "w", libver="latest") as f:
        layout = h5py.VirtualLayout(shape=(4,), dtype="i4")
        layout[:] = h5py.VirtualSource("vds_loop.h5", "v", shape=(4,))
        f.create_virtual_dataset("v", layout, fillvalue=-1)


def _typed_dataset(f, name, tid, values, mem_dtype):
    """A dataset of the (non-native) file type ``tid``; libhdf5 converts ``values`` into it, and its own
    conversion back to float64 is stored as the 'expected' attribute (the oracle for Falcon's decoding)."""
    d = h5py.h5d.create(f.id, name.encode(), tid, h5py.h5s.create_simple((len(values),)))
    d.write(h5py.h5s.ALL, h5py.h5s.ALL, np.asarray(values, dtype=mem_dtype))
    back = np.empty(len(values), dtype="<f8")
    d.read(h5py.h5s.ALL, h5py.h5s.ALL, back, mtype=h5py.h5t.NATIVE_DOUBLE)
    d.close()
    f[name].attrs["expected"] = back


def build_numeric(f):
    """Integer and floating-point layouts beyond the native ones: integers with a bit offset and reduced
    precision, bfloat16, a float32 inside a wider element, x87 80-bit extended precision (explicit
    leading mantissa bit), and unsigned values that do not fit Java's signed types."""
    t = h5py.h5t.STD_I16LE.copy()                    # 12-bit signed at bit offset 4
    t.set_precision(12)
    t.set_offset(4)
    _typed_dataset(f, "i12_off4", t, [-5, 100, 2047, -2048], "<i2")
    t = h5py.h5t.STD_U16BE.copy()                    # 12-bit unsigned, big-endian, at bit offset 3
    t.set_precision(12)
    t.set_offset(3)
    _typed_dataset(f, "u12be_off3", t, [0, 4095, 1234, 7], "<u2")
    t = h5py.h5t.STD_I32BE.copy()                    # 24-bit signed in a 3-byte big-endian element
    t.set_precision(24)
    t.set_size(3)
    _typed_dataset(f, "i24be", t, [-8388608, 8388607, -1, 12345], "<i4")
    t = h5py.h5t.STD_I64LE.copy()                    # int64 with 40 significant bits at offset 20
    t.set_precision(40)
    t.set_offset(20)
    _typed_dataset(f, "i40_off20", t, [-(2 ** 39), 2 ** 39 - 1, -1, 3], "<i8")
    b = h5py.h5t.IEEE_F32LE.copy()                   # bfloat16: float32's sign/exponent, 7-bit mantissa
    b.set_fields(15, 7, 8, 0, 7)
    b.set_precision(16)
    b.set_size(2)
    b.set_ebias(127)
    _typed_dataset(f, "bf16", b, [1.5, -2.0, 3.140625, 1e-40], "<f8")
    t = h5py.h5t.IEEE_F32BE.copy()                   # float32 in 6 bytes with bit offset 8 (unused)
    t.set_size(6)
    t.set_offset(8)
    _typed_dataset(f, "f32_in6", t, [1.5, -2.0, 3.25, float("inf")], "<f8")
    x = h5py.h5t.IEEE_F64LE.copy()                   # x87 extended: 80 bits in 16 bytes, no implied bit
    x.set_size(16)
    x.set_precision(80)
    x.set_fields(79, 64, 15, 0, 64)
    x.set_ebias(16383)
    x.set_norm(h5py.h5t.NORM_NONE)
    _typed_dataset(f, "x87", x, [1.5, -2.0e-300, 3.25e300, float("-inf")], "<f8")
    f.create_dataset("u32", data=np.array([0, 4000000000, 2 ** 31, 7], dtype="<u4"))
    f.create_dataset("u32_small", data=np.array([0, 1, 2 ** 31 - 1, 7], dtype=">u4"))
    f.create_dataset("u64", data=np.array([0, 2 ** 64 - 1, 2 ** 63, 7], dtype="<u8"))
    f.create_dataset("u64_small", data=np.array([0, 5, 2 ** 62, 7], dtype="<u8"))
    f.create_dataset("i64_small", data=np.array([0, -5, 2 ** 31 - 1, -(2 ** 31)], dtype="<i8"))
    f.create_dataset("i64_big", data=np.array([0, 2 ** 40], dtype="<i8"))
    f.attrs["u32_attr"] = np.array([4000000000, 1], dtype="<u4")
    f.attrs["u16_attr"] = np.array([65535], dtype="<u2")


def build_conversions(f):
    """Integers read as floating point (readDoubles, readFloats): each dataset holds values of a stored
    integer type, and libhdf5's own conversions to H5T_NATIVE_DOUBLE and H5T_NATIVE_FLOAT are its
    'expected_f8' and 'expected_f4' attributes. The values include ones that need rounding (more than 53
    or 24 significant bits), ties, and ones that converting through double first would round wrongly."""
    i64 = [0, -1, 2 ** 53 + 1, -(2 ** 53) - 3, 2 ** 63 - 1, -(2 ** 63), 2 ** 62 + 2 ** 38 + 1,
           2 ** 60 + 2 ** 36 + 1, 16777217, -16777219, 2 ** 24 + 3]
    u64 = [0, 1, 2 ** 64 - 1, 2 ** 63 + 1025, 2 ** 63 + 1024, 2 ** 53 + 1, 16777217, 2 ** 64 - 2 ** 39 - 1,
           2 ** 63 + 2 ** 39 + 1]
    specs = [
        ("i8", h5py.h5t.STD_I8LE, [-128, 127, 0, -1], "<i1"),
        ("u8", h5py.h5t.STD_U8LE, [0, 255, 128], "<u1"),
        ("i16be", h5py.h5t.STD_I16BE, [-32768, 32767, -2], "<i2"),
        ("u16", h5py.h5t.STD_U16LE, [65535, 0, 40000], "<u2"),
        ("i32", h5py.h5t.STD_I32LE, [16777217, -16777219, 2 ** 31 - 1, -(2 ** 31)], "<i4"),
        ("u32be", h5py.h5t.STD_U32BE, [16777217, 2 ** 32 - 1, 2 ** 31 + 129], "<u4"),
        ("i64", h5py.h5t.STD_I64LE, i64, "<i8"),
        ("i64be", h5py.h5t.STD_I64BE, i64, "<i8"),
        ("u64", h5py.h5t.STD_U64LE, u64, "<u8"),
        ("u64be", h5py.h5t.STD_U64BE, u64, "<u8"),
    ]
    for name, tid, values, mem_dtype in specs:
        d = h5py.h5d.create(f.id, name.encode(), tid, h5py.h5s.create_simple((len(values),)))
        d.write(h5py.h5s.ALL, h5py.h5s.ALL, np.asarray(values, dtype=mem_dtype))
        for attr, mtype, dtype in (("expected_f8", h5py.h5t.NATIVE_DOUBLE, "<f8"),
                                   ("expected_f4", h5py.h5t.NATIVE_FLOAT, "<f4")):
            back = np.empty(len(values), dtype=dtype)
            d.read(h5py.h5s.ALL, h5py.h5s.ALL, back, mtype=mtype)
            f[name].attrs[attr] = back
        d.close()
    f.attrs["u64_attr"] = np.array(u64, dtype="<u8")
    a = h5py.h5a.open(f.id, b"u64_attr")
    for attr, mtype, dtype in (("u64_attr_f8", h5py.h5t.NATIVE_DOUBLE, "<f8"),
                               ("u64_attr_f4", h5py.h5t.NATIVE_FLOAT, "<f4")):
        back = np.empty(len(u64), dtype=dtype)
        a.read(back, mtype=mtype)
        f.attrs[attr] = back


def build_vds_byteorder(out):
    """Virtual datasets whose sources hold big-endian data under a little-endian virtual type (libhdf5
    converts), and one whose source has a different type of the same size (uint32 under int32)."""
    src = os.path.join(out, "vds_byteorder_src.h5")
    with h5py.File(src, "w", libver="latest") as f:
        f.create_dataset("i4_be", data=np.array([1, -2, 300000, -400000], dtype=">i4"))
        f.create_dataset("f8_be", data=np.array([1.5, -2.25, 1e300, -0.0], dtype=">f8"))
        f.create_dataset("u4", data=np.array([1, 2, 3, 4000000000], dtype="<u4"))
    with h5py.File(os.path.join(out, "vds_byteorder.h5"), "w", libver="latest") as f:
        for name, source, dtype in (("i4", "i4_be", "<i4"), ("f8", "f8_be", "<f8"), ("u4_as_i4", "u4", "<i4")):
            layout = h5py.VirtualLayout(shape=(4,), dtype=dtype)
            layout[:] = h5py.VirtualSource("vds_byteorder_src.h5", source, shape=(4,))
            f.create_virtual_dataset(name, layout, fillvalue=0)


def _links(f, dense):
    """Hard, soft (absolute, relative, chained, dangling, cyclic, to a group) and external links."""
    data = f.create_group("data")
    data.create_dataset("x", data=np.array([1, 2, 3], dtype="i4"))
    links = f.create_group("links")
    links.create_group("sub").create_dataset("y", data=np.array([7], dtype="i4"))
    links["hard"] = data["x"]
    links["soft_abs"] = h5py.SoftLink("/data/x")
    links["soft_rel"] = h5py.SoftLink("sub/y")
    links["soft_group"] = h5py.SoftLink("/data")
    links["chain"] = h5py.SoftLink("/links/soft_abs")
    links["dangling"] = h5py.SoftLink("/nowhere")
    links["loop_a"] = h5py.SoftLink("/links/loop_b")
    links["loop_b"] = h5py.SoftLink("/links/loop_a")
    if f.libver[0] != "earliest":
        links["ext"] = h5py.ExternalLink("links_ext.h5", "/y")
    if dense:
        big = f.create_group("dense")
        for i in range(12):
            big[f"h{i:02d}"] = data["x"]
        big["soft"] = h5py.SoftLink("/data/x")
        big["ext"] = h5py.ExternalLink("links_ext.h5", "/y")


def build_links(out):
    """Every link kind in new-style (compact and dense) and old-style (symbol-table) groups, plus the
    external file the external links name."""
    with h5py.File(os.path.join(out, "links_ext.h5"), "w") as f:
        f.create_dataset("y", data=np.array([42], dtype="i4"))
    with h5py.File(os.path.join(out, "links.h5"), "w", libver="latest") as f:
        _links(f, dense=True)
    with h5py.File(os.path.join(out, "links_old.h5"), "w", libver="earliest") as f:
        _links(f, dense=False)  # old-style groups store soft links as symbol-table cache type 2


def build_heap_limits(f):
    """Fractal-heap structures beyond a single block: dense attributes stored as 'huge' heap objects
    (larger than the 4 KiB managed-object limit, or than 64 KiB), and a group whose link heap needs
    nested indirect blocks (over 520 KB of link messages)."""
    d = f.create_dataset("huge_attr", data=np.int32(1))
    for i in range(9):
        d.attrs[f"small{i}"] = np.int32(i)
    d.attrs["wide"] = np.arange(1000, dtype="<f8") * 0.5          # 8000 bytes: a huge object
    e = f.create_dataset("huge_alone", data=np.int32(2))
    e.attrs["vast"] = np.arange(10000, dtype="<f8")              # 80000 bytes: forces dense storage
    target = f.create_dataset("target", data=np.int32(3))
    g = f.create_group("nested")
    for i in range(2100):
        g[f"{i:05d}" + "n" * 250] = target


def _vds_sources(out, names):
    for n in names:
        with h5py.File(os.path.join(out, n), "w") as f:
            f.create_dataset("dataset_aaaaaaaaaa", data=np.arange(4, dtype="i4"))
            f.create_dataset("dataset_bbbbbbbbbb", data=np.arange(4, dtype="i4") + 10)


def _vds_same_file(f):
    f.create_dataset("src", data=np.arange(12, dtype="i4").reshape(3, 4))
    layout = h5py.VirtualLayout(shape=(3, 4), dtype="i4")
    layout[0:2, 0:2] = h5py.VirtualSource(".", "src", shape=(3, 4))[1:3, 2:4]
    layout[2, :] = h5py.VirtualSource(".", "src", shape=(3, 4))[0, :]
    f.create_virtual_dataset("same_file", layout, fillvalue=-1)
    layout = h5py.VirtualLayout(shape=(8,), dtype="i4")
    layout[0:8:2] = h5py.VirtualSource(".", "src", shape=(3, 4))[1, :]
    f.create_virtual_dataset("strided", layout, fillvalue=-1)


def build_vds_encodings(out):
    """Virtual datasets in each mapping encoding: the default libver (heap block v0, hyperslab selection
    v1 block lists, "." for a same-file source) and libver latest (heap block v1, whose entries flag a
    same-file source or point back at an earlier entry's file or dataset name)."""
    names = ["vds_external_source_0.h5", "vds_external_source_1.h5"]
    _vds_sources(out, names)
    for libver, name in (("earliest", "vds_default.h5"), ("latest", "vds_latest.h5")):
        with h5py.File(os.path.join(out, name), "w", libver=libver) as f:
            _vds_same_file(f)
            layout = h5py.VirtualLayout(shape=(4, 4), dtype="i4")
            layout[0] = h5py.VirtualSource(names[0], "dataset_aaaaaaaaaa", shape=(4,))
            layout[1] = h5py.VirtualSource(names[1], "dataset_aaaaaaaaaa", shape=(4,))
            layout[2] = h5py.VirtualSource(names[0], "dataset_bbbbbbbbbb", shape=(4,))
            layout[3] = h5py.VirtualSource(names[1], "dataset_bbbbbbbbbb", shape=(4,))
            f.create_virtual_dataset("shared_names", layout, fillvalue=-1)


def _region_refs(f):
    src = f.create_dataset("src", data=np.arange(12, dtype="i4").reshape(3, 4))
    refs = f.create_dataset("refs", shape=(7,), dtype=h5py.regionref_dtype)
    refs[0] = src.regionref[0:2, 1:3]                         # one block
    refs[1] = src.regionref[...]                              # all
    space = src.id.get_space()
    space.select_elements(np.array([[0, 1], [2, 3], [1, 0]], dtype="u8"))
    refs[2] = h5py.h5r.create(f.id, b"src", h5py.h5r.DATASET_REGION, space)    # points, in list order
    space = src.id.get_space()
    space.select_hyperslab((2, 2), (1, 1), block=(1, 2))
    space.select_hyperslab((0, 0), (1, 1), block=(1, 2), op=h5py.h5s.SELECT_OR)
    refs[3] = h5py.h5r.create(f.id, b"src", h5py.h5r.DATASET_REGION, space)    # two blocks
    space = src.id.get_space()
    space.select_hyperslab((0, 0), (2, 2), stride=(2, 2), block=(1, 1))
    refs[4] = h5py.h5r.create(f.id, b"src", h5py.h5r.DATASET_REGION, space)    # strided
    space = src.id.get_space()
    space.select_none()
    refs[5] = h5py.h5r.create(f.id, b"src", h5py.h5r.DATASET_REGION, space)    # none
    # refs[6] stays a null reference.
    expected = [src[r].ravel().tolist() if r else [] for r in refs[:6]]
    for i, values in enumerate(expected):
        refs.attrs[f"expected{i}"] = np.array(values, dtype="i4")


def build_region_refs(out):
    """Region references in each selection encoding: the default libver (hyperslab v1 block lists,
    points v1) and libver latest (hyperslab v3 regular and irregular, points v2), with all/none/null."""
    for libver, name in (("earliest", "regionrefs_default.h5"), ("latest", "regionrefs_latest.h5")):
        with h5py.File(os.path.join(out, name), "w", libver=libver) as f:
            _region_refs(f)


def build_plugin_filters(f):
    """The third-party filters most common in the wild, each dataset beside an unfiltered copy under
    /expected: LZF (h5py's own), and through hdf5plugin Blosc (every internal codec and shuffle), LZ4 (one
    and many blocks, incompressible blocks stored raw), bitshuffle (alone, with LZ4 and zstd, element
    counts that are not a multiple of 8) and Zstandard. Chunks LZF cannot shrink are stored unfiltered
    (it is optional), with the filter-mask bit set."""
    import hdf5plugin
    rng = np.random.default_rng(11)
    ramp = np.arange(1000, dtype="<i4")
    smooth = np.cumsum(rng.normal(size=(40, 30)), axis=1).astype("<f8")
    noise = rng.integers(-2**62, 2**62, size=500).astype("<i8")
    text = np.frombuffer((b"falcon reads plugin filters " * 80)[:2006], dtype="u1")
    cases = {
        "lzf_i4": (np.repeat(np.arange(100, dtype="<i4"), 10), (250,), dict(compression="lzf")),
        "lzf_shuffle_f8": (smooth, (16, 16), dict(compression="lzf", shuffle=True)),
        "lzf_noise_i8": (noise, (100,), dict(compression="lzf")),
        "lz4_i4": (ramp, (250,), hdf5plugin.LZ4()),
        "lz4_blocks_f8": (smooth, (16, 16), hdf5plugin.LZ4(nbytes=512)),
        "lz4_noise_i8": (noise, (100,), hdf5plugin.LZ4(nbytes=256)),
        "zstd_i4": (ramp, (250,), hdf5plugin.Zstd(clevel=3)),
        "zstd_f8": (smooth, (16, 16), hdf5plugin.Zstd(clevel=19)),
        "bitshuffle_i4": (ramp, (250,), hdf5plugin.Bitshuffle(cname="none")),
        "bitshuffle_lz4_f8": (smooth, (16, 16), hdf5plugin.Bitshuffle(cname="lz4")),
        "bitshuffle_zstd_i8": (noise, (100,), hdf5plugin.Bitshuffle(cname="zstd")),
        "bitshuffle_lz4_u1": (text, (1003,), hdf5plugin.Bitshuffle(nelems=64, cname="lz4")),
    }
    for cname in ("blosclz", "lz4", "lz4hc", "zlib", "zstd", "snappy"):
        cases[f"blosc_{cname}_i4"] = (ramp, (250,), hdf5plugin.Blosc(cname=cname, clevel=5,
                                                                  shuffle=hdf5plugin.Blosc.SHUFFLE))
    cases["blosc_noshuffle_f8"] = (smooth, (16, 16), hdf5plugin.Blosc(cname="lz4", shuffle=hdf5plugin.Blosc.NOSHUFFLE))
    cases["blosc_bitshuffle_f8"] = (smooth, (16, 16), hdf5plugin.Blosc(cname="zstd",
                                                                     shuffle=hdf5plugin.Blosc.BITSHUFFLE))
    for name, (data, chunks, filters) in cases.items():
        f.create_dataset(name, data=data, chunks=chunks, **filters)
        f.create_dataset("expected/" + name, data=data)


def _lookup3(data, initval=0):
    """Bob Jenkins' lookup3 hashlittle, as libhdf5's H5_checksum_lookup3 computes metadata checksums."""
    m = 0xFFFFFFFF
    rot = lambda x, k: ((x << k) | (x >> (32 - k))) & m
    length = len(data)
    a = b = c = (0xDEADBEEF + length + initval) & m
    i = 0
    while length > 12:
        a = (a + int.from_bytes(data[i:i + 4], "little")) & m
        b = (b + int.from_bytes(data[i + 4:i + 8], "little")) & m
        c = (c + int.from_bytes(data[i + 8:i + 12], "little")) & m
        a = (a - c) & m; a ^= rot(c, 4); c = (c + b) & m
        b = (b - a) & m; b ^= rot(a, 6); a = (a + c) & m
        c = (c - b) & m; c ^= rot(b, 8); b = (b + a) & m
        a = (a - c) & m; a ^= rot(c, 16); c = (c + b) & m
        b = (b - a) & m; b ^= rot(a, 19); a = (a + c) & m
        c = (c - b) & m; c ^= rot(b, 4); b = (b + a) & m
        length -= 12
        i += 12
    if length == 0:
        return c
    tail = bytes(data[i:]) + bytes(12 - length)
    a = (a + int.from_bytes(tail[0:4], "little")) & m
    b = (b + int.from_bytes(tail[4:8], "little")) & m
    c = (c + int.from_bytes(tail[8:12], "little")) & m
    c ^= b; c = (c - rot(b, 14)) & m
    a ^= c; a = (a - rot(c, 11)) & m
    b ^= a; b = (b - rot(a, 25)) & m
    c ^= b; c = (c - rot(b, 16)) & m
    a ^= c; a = (a - rot(c, 4)) & m
    b ^= a; b = (b - rot(a, 14)) & m
    c ^= b; c = (c - rot(b, 24)) & m
    return c


def _rewrite_v1_message(data, header, msg_type, rewrite):
    """Replaces the body of message ``msg_type`` in the first chunk of the version-1 object header at
    ``header`` with ``rewrite(body)``, repacking that chunk's messages and its trailing NIL space."""
    if data[header] != 1:
        raise RuntimeError("not a version-1 object header")
    count = int.from_bytes(data[header + 2:header + 4], "little")
    size = int.from_bytes(data[header + 8:header + 12], "little")
    start, end = header + 16, header + 16 + size
    messages, nils, p = [], 0, start
    while p < end:
        t = int.from_bytes(data[p:p + 2], "little")
        n = int.from_bytes(data[p + 2:p + 4], "little")
        body = bytes(data[p + 8:p + 8 + n])
        if t == 0:
            nils += 1
        else:
            if t == msg_type:
                body = rewrite(body)
                body += bytes(-len(body) % 8)
            messages.append((t, data[p + 4], body))
        p += 8 + n
    out = b"".join(t.to_bytes(2, "little") + len(b).to_bytes(2, "little") + bytes([fl, 0, 0, 0]) + b
                   for t, fl, b in messages)
    free = size - len(out)
    if free < 0:
        raise RuntimeError("the rewritten message does not fit its object header")
    if free:
        out += (0).to_bytes(2, "little") + (free - 8).to_bytes(2, "little") + bytes(4 + free - 8)
    data[start:end] = out
    data[header + 2:header + 4] = (count - nils + (1 if free else 0)).to_bytes(2, "little")


def _layout_v1v2(version, rank_dims, element_size):
    """A rewrite turning a version-3 layout body into version 1 or 2 (H5O__layout_decode's old form)."""
    def rewrite(body):
        if body[0] != 3:
            raise RuntimeError("expected a version-3 layout")
        layout_class = body[1]
        reserved = bytes(5)
        if layout_class == 0:  # compact: dims, then size(4) and data
            size = int.from_bytes(body[2:4], "little")
            dims = list(rank_dims) + [element_size]
            return (bytes([version, len(dims), 0]) + reserved + b"".join(d.to_bytes(4, "little") for d in dims)
                    + size.to_bytes(4, "little") + body[4:4 + size])
        if layout_class == 1:  # contiguous: address, then the dataset dims and element size (unused)
            dims = list(rank_dims) + [element_size]
            return (bytes([version, len(dims), 1]) + reserved + body[2:10]
                    + b"".join(d.to_bytes(4, "little") for d in dims))
        ndims = body[2]       # chunked: B-tree address, then chunk dims and element size
        return bytes([version, ndims, 2]) + reserved + body[3:11] + body[11:11 + 4 * ndims]
    return rewrite


def build_legacy_layouts(out):
    """Data layout messages of versions 1 and 2 (HDF5 1.6.2 and earlier), which no current libhdf5
    writes: libhdf5 writes the earliest format (layout version 3), then the chunked, contiguous and
    compact layouts of the 'v1_*'/'v2_*' datasets are rewritten into the old form. HDF5 2.0 and 1.14 read
    the result; /expected keeps the values."""
    path = os.path.join(out, "legacy_layouts.h5")
    data2d = np.arange(48, dtype="<i4").reshape(8, 6)
    cases = {
        "v1_chunked": (data2d, dict(chunks=(3, 4), compression="gzip")),
        "v2_chunked": (np.linspace(0, 1, 10).astype("<f8"), dict(chunks=(4,))),
        "v1_contiguous": (np.arange(5, dtype="<i2"), {}),
        "v2_compact": (np.arange(4, dtype="<i8") * 3, dict(compact=True)),
    }
    with h5py.File(path, "w", libver="earliest") as f:
        for name, (values, kw) in cases.items():
            if kw.pop("compact", False):
                space = h5py.h5s.create_simple(values.shape)
                dcpl = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
                dcpl.set_layout(h5py.h5d.COMPACT)
                h5py.h5d.create(f.id, name.encode(), h5py.h5t.py_create(values.dtype), space, dcpl=dcpl).write(
                    h5py.h5s.ALL, h5py.h5s.ALL, values)
            else:
                f.create_dataset(name, data=values, **kw)
            f.create_dataset("expected/" + name, data=values)
        headers = {name: h5py.h5o.get_info(f[name].id).addr for name in cases}
    data = bytearray(open(path, "rb").read())
    for name, (values, _) in cases.items():
        version = int(name[1])
        _rewrite_v1_message(data, headers[name], 8, _layout_v1v2(version, values.shape, values.itemsize))
    open(path, "wb").write(data)
    with h5py.File(path, "r") as f:
        for name in cases:
            if not np.array_equal(f[name][...], f["expected/" + name][...]):
                raise RuntimeError("libhdf5 misreads the rewritten " + name)


def build_vax(f):
    """VAX-order floats: libhdf5's own H5T_VAX_F32 and H5T_VAX_F64, which h5py does not name, converted
    from doubles by libhdf5; its conversion back is the 'expected' attribute."""
    lib = _hdf5_library()
    lib.H5open()
    values = [0.0, 1.0, -1.5, 3.141592653589793, 1e-30, -2.5e30, 0.1, 123456.789, 2.9e-39, 1.0e38]
    for name in ("H5T_VAX_F32_g", "H5T_VAX_F64_g"):
        tid = h5py.h5t.typewrap(ctypes.c_int64.in_dll(lib, name).value).copy()
        _typed_dataset(f, "vax_f32" if "F32" in name else "vax_f64", tid, values, "<f8")


def _extension_message(data, header, msg_type):
    """(body offset, body size, checksum offset or None) of message ``msg_type`` in the first chunk of the
    object header at ``header``: version 2 ("OHDR", checksummed) or version 1."""
    if data[header:header + 4] == b"OHDR":
        flags = data[header + 5]
        p = header + 6 + (16 if flags & 0x20 else 0) + (4 if flags & 0x10 else 0)
        width = 1 << (flags & 3)
        end = p + width + int.from_bytes(data[p:p + width], "little")
        p += width
        while p < end:
            t, n = data[p], int.from_bytes(data[p + 1:p + 3], "little")
            body = p + 4 + (2 if flags & 0x04 else 0)
            if t == msg_type:
                return body, n, end
            p = body + n
    elif data[header] == 1:
        p = header + 16
        end = p + int.from_bytes(data[header + 8:header + 12], "little")
        while p < end:
            t, n = int.from_bytes(data[p:p + 2], "little"), int.from_bytes(data[p + 2:p + 4], "little")
            if t == msg_type:
                return p + 8, n, None
            p += 8 + n
    raise RuntimeError(f"no message {msg_type} in the object header at {header}")


def build_fsinfo_v0(out):
    """File Space Info messages of version 0 (HDF5 1.10.0), which libhdf5 now maps onto version 1: files
    written with a non-default strategy, whose version-1 message is rewritten as version 0 in place and
    the superblock extension's checksum recomputed."""
    strategies = {"persist": (h5py.h5f.FSPACE_STRATEGY_FSM_AGGR, True, 1, 1),
                  "aggr": (h5py.h5f.FSPACE_STRATEGY_AGGR, False, 1, 3)}
    for label, (strategy, persist, threshold, old_strategy) in strategies.items():
        path = os.path.join(out, f"fsinfo_v0_{label}.h5")
        fcpl = h5py.h5p.create(h5py.h5p.FILE_CREATE)
        fcpl.set_file_space_strategy(strategy, persist, threshold)
        fapl = h5py.h5p.create(h5py.h5p.FILE_ACCESS)
        fapl.set_libver_bounds(h5py.h5f.LIBVER_LATEST, h5py.h5f.LIBVER_LATEST)  # a checksummed extension
        fid = h5py.h5f.create(path.encode(), h5py.h5f.ACC_TRUNC, fcpl=fcpl, fapl=fapl)
        with h5py.File(fid) as f:
            f.create_dataset("kept", data=np.arange(100, dtype="<i4"))
            f.create_dataset("dropped", data=np.arange(5000, dtype="<f8"))
        fapl = h5py.h5p.create(h5py.h5p.FILE_ACCESS)
        fapl.set_libver_bounds(h5py.h5f.LIBVER_LATEST, h5py.h5f.LIBVER_LATEST)
        with h5py.File(h5py.h5f.open(path.encode(), h5py.h5f.ACC_RDWR, fapl=fapl)) as f:
            del f["dropped"]  # leaves free space for the managers to track
        data = bytearray(open(path, "rb").read())
        if data[8] not in (2, 3):
            raise RuntimeError("expected a version 2-3 superblock")
        extension = int.from_bytes(data[20:28], "little")
        body, size, checksummed = _extension_message(data, extension, 0x17)
        v1 = bytes(data[body:body + size])
        v0 = bytes([0, old_strategy]) + v1[3:11]  # version, old strategy, threshold
        if old_strategy == 1:  # the six small-section managers, which version 1 lists first
            v0 += v1[3 + 8 + 8 + 2 + 8:3 + 8 + 8 + 2 + 8 + 6 * 8]
        data[body:body + size] = v0 + bytes(size - len(v0))
        if checksummed is not None:
            data[checksummed:checksummed + 4] = _lookup3(bytes(data[extension:checksummed])).to_bytes(4, "little")
        open(path, "wb").write(data)
        with h5py.File(path, "r") as f:
            got = f.id.get_create_plist().get_file_space_strategy()
            if (got[0], bool(got[1])) != (strategy, persist):
                raise RuntimeError(f"libhdf5 reads {got} from the rewritten {label} file")
            if not np.array_equal(f["kept"][...], np.arange(100)):
                raise RuntimeError("data lost")


def build_driver_and_k(out):
    """Superblock fields Falcon now reports: non-default B-tree 'K' values (in a version-1 superblock,
    and in the extension's message 19 of a version-3 one), and family-driver information (a version-0
    superblock's driver information block, and message 20 of a version-3 superblock). The family members
    are 1 MiB, so all the data stays in member 0, the fixture."""
    lib = _hdf5_library()
    for label, libver in (("earliest", h5py.h5f.LIBVER_EARLIEST), ("latest", h5py.h5f.LIBVER_LATEST)):
        fcpl = h5py.h5p.create(h5py.h5p.FILE_CREATE)
        if lib.H5Pset_sym_k(ctypes.c_int64(fcpl.id), ctypes.c_uint(8), ctypes.c_uint(6)) < 0:
            raise RuntimeError("H5Pset_sym_k failed")
        if lib.H5Pset_istore_k(ctypes.c_int64(fcpl.id), ctypes.c_uint(64)) < 0:
            raise RuntimeError("H5Pset_istore_k failed")
        fapl = h5py.h5p.create(h5py.h5p.FILE_ACCESS)
        fapl.set_libver_bounds(libver, h5py.h5f.LIBVER_LATEST)
        fid = h5py.h5f.create(os.path.join(out, f"btree_k_{label}.h5").encode(), h5py.h5f.ACC_TRUNC,
                              fcpl=fcpl, fapl=fapl)
        with h5py.File(fid) as f:
            f.create_dataset("d", data=np.arange(10, dtype="<i4"), chunks=(5,))
        with h5py.File(os.path.join(out, f"family_{label}_%d.h5"), "w", driver="family",
                       memb_size=1 << 20, libver=(label, "latest")) as f:
            f.create_dataset("d", data=np.arange(10, dtype="<i4"))
        if os.path.exists(os.path.join(out, f"family_{label}_1.h5")):
            raise RuntimeError("the family spilled into a second member")


def _virtual(f, name, shape, maxshape, mappings, fill=-1):
    """A virtual dataset through the low-level API: mappings are (vselect, file, dataset, sshape, smax,
    sselect), where the select functions set a selection on a dataspace (or None for all)."""
    U = h5py.h5s.UNLIMITED
    vspace = h5py.h5s.create_simple(shape, maxshape)
    dcpl = h5py.h5p.create(h5py.h5p.DATASET_CREATE)
    dcpl.set_fill_value(np.array(fill, dtype="i4"))
    for vselect, file, dataset, sshape, smax, sselect in mappings:
        v = h5py.h5s.create_simple(shape, maxshape)
        vselect(v)
        src = h5py.h5s.create_simple(sshape, smax)
        if sselect:
            sselect(src)
        dcpl.set_virtual(v, file.encode(), dataset.encode(), src)
    h5py.h5d.create(f.id, name.encode(), h5py.h5t.NATIVE_INT32, vspace, dcpl=dcpl)


def build_vds_views(out):
    """Virtual datasets whose extent depends on libhdf5's view (last available or first missing) and
    printf gap: two unlimited mappings of different lengths, printf sources 0, 1 and 3 (2 missing) with a
    stride beyond the block, and a printf mapping beside a shorter unlimited one, which the first-missing
    view cuts mid-block. libhdf5's reading under each view and gap is stored as 'expected:<view>:<gap>'
    and 'expected_shape:<view>:<gap>'."""
    U = h5py.h5s.UNLIMITED
    for i in (0, 1, 3):
        with h5py.File(os.path.join(out, "vds_views_%d.h5" % i), "w") as f:
            f.create_dataset("data", data=np.full((2,), 10 + i, dtype="i4"))
    for i in range(3):
        with h5py.File(os.path.join(out, "vds_views_blk_%d.h5" % i), "w") as f:
            f.create_dataset("data", data=np.full((2, 2), 20 + i, dtype="i4"))
    path = os.path.join(out, "vds_views.h5")
    with h5py.File(path, "w", libver="latest") as f:
        f.create_dataset("a5", data=np.arange(5, dtype="i4"), maxshape=(None,))
        f.create_dataset("b3", data=100 + np.arange(3, dtype="i4"), maxshape=(None,))
        f.create_dataset("c5", data=200 + np.arange(5, dtype="i4").reshape(1, 5), maxshape=(1, None))
        column = lambda c: lambda s: s.select_hyperslab((0, c), (U, 1), (1, 1), (1, 1))
        whole = lambda s: s.select_hyperslab((0,), (1,), (1,), (U,))
        _virtual(f, "two_lengths", (0, 2), (U, 2), [(column(0), ".", "a5", (0,), (U,), whole),
                                                   (column(1), ".", "b3", (0,), (U,), whole)])
        strided = lambda s: s.select_hyperslab((0,), (U,), (3,), (2,))
        _virtual(f, "printf_gap", (0,), (U,), [(strided, "vds_views_%b.h5", "data", (2,), None, None)])
        blocks = lambda s: s.select_hyperslab((0, 0), (1, U), (1, 2), (2, 2))
        last_row = lambda s: s.select_hyperslab((2, 0), (1, U), (1, 1), (1, 1))
        row = lambda s: s.select_hyperslab((0, 0), (1, U), (1, 1), (1, 1))
        _virtual(f, "partial_block", (3, 0), (3, U),
                 [(blocks, "vds_views_blk_%b.h5", "data", (2, 2), None, None),
                  (last_row, ".", "c5", (1, 0), (1, U), row)])
    names = ("two_lengths", "printf_gap", "partial_block")
    expected = {}
    for view, code in (("last", h5py.h5d.VDS_LAST_AVAILABLE), ("first", h5py.h5d.VDS_FIRST_MISSING)):
        for gap in (0, 1):
            # A fresh open each time: libhdf5 shares an open dataset, with the view it was opened with.
            fid = h5py.h5f.open(path.encode(), h5py.h5f.ACC_RDONLY)
            for name in names:
                dapl = h5py.h5p.create(h5py.h5p.DATASET_ACCESS)
                dapl.set_virtual_view(code)
                dapl.set_virtual_printf_gap(gap)
                d = h5py.h5d.open(fid, name.encode(), dapl=dapl)
                values = np.empty(d.shape, dtype="i4")
                if values.size:
                    d.read(h5py.h5s.ALL, h5py.h5s.ALL, values)
                expected[(name, view, gap)] = (np.array(d.shape, dtype="i8"), values.reshape(-1))
                d.close()
            fid.close()
    with h5py.File(path, "a") as f:
        for (name, view, gap), (shape, values) in expected.items():
            f[name].attrs[f"expected_shape:{view}:{gap}"] = shape
            f[name].attrs[f"expected:{view}:{gap}"] = values


def build_vds_unlimited(out):
    """Virtual datasets whose extent their sources set (libhdf5's defaults: the last available view and
    printf gap 0): unlimited mappings along rows and interleaved along columns, printf-style ones (%b in the
    file or dataset name, %% for a literal %, sources missing after the second), one floored by a fixed
    mapping, and sources found as libhdf5 finds them (an absolute name by its file name alone; a relative
    name with a directory is not; one found nowhere is filled). libhdf5's own reading is stored as
    'expected' and 'expected_shape'."""
    U = h5py.h5s.UNLIMITED
    with h5py.File(os.path.join(out, "vds_unlim_src.h5"), "w", libver="latest") as f:
        f.create_dataset("data", data=np.arange(15, dtype="i4").reshape(5, 3), maxshape=(None, 3), chunks=(2, 3))
        f.create_dataset("empty", shape=(0,), maxshape=(None,), dtype="i4")
    for i in range(3):
        with h5py.File(os.path.join(out, "vds_printf_%d.h5" % i), "w") as f:
            f.create_dataset("data", data=np.full((2, 3), 10 * (i + 1), dtype="i4"))
    for i in (0, 1, 3):
        with h5py.File(os.path.join(out, "vds_pct%%_%d.h5" % i), "w") as f:
            f.create_dataset("data", data=np.full((2,), 10 + i, dtype="i4"))
    path = os.path.join(out, "vds_unlimited.h5")
    with h5py.File(path, "w", libver="latest") as f:
        rows = lambda s: s.select_hyperslab((0, 0), (U, 1), (1, 1), (1, 3))
        _virtual(f, "rows", (2, 3), (U, 3), [(rows, "vds_unlim_src.h5", "data", (5, 3), (U, 3), rows)])
        f.create_dataset("a", data=np.arange(6, dtype="i4").reshape(2, 3), maxshape=(2, None))
        f.create_dataset("b", data=100 + np.arange(4, dtype="i4").reshape(2, 2), maxshape=(2, None))
        cols = lambda start: lambda s: s.select_hyperslab((0, start), (1, U), (1, 2), (2, 1))
        whole = lambda s: s.select_hyperslab((0, 0), (1, U), (1, 1), (2, 1))
        _virtual(f, "cols", (2, 0), (2, U), [(cols(0), ".", "a", (2, 0), (2, U), whole),
                                            (cols(1), ".", "b", (2, 0), (2, U), whole)])
        blocks = lambda s: s.select_hyperslab((0, 0), (U, 1), (2, 1), (2, 3))
        _virtual(f, "printf", (0, 3), (U, 3), [(blocks, "vds_printf_%b.h5", "data", (2, 3), None, None)])
        _virtual(f, "printf_moved", (0, 3), (U, 3),
                 [(blocks, "/nonexistent/elsewhere/vds_printf_%b.h5", "data", (2, 3), None, None)])
        pairs = lambda s: s.select_hyperslab((0,), (U,), (2,), (2,))
        _virtual(f, "printf_gap", (0,), (U,), [(pairs, "vds_pct%%_%b.h5", "data", (2,), None, None)])
        for i in range(2):
            f.create_dataset("part%d" % i, data=np.full((3,), 20 + i, dtype="i4"))
        spaced = lambda s: s.select_hyperslab((1,), (U,), (4,), (3,))
        _virtual(f, "printf_names", (0,), (U,), [(spaced, ".", "part%b", (3,), None, None)])
        _virtual(f, "printf_none", (0,), (U,), [(pairs, "vds_absent_%b.h5", "data", (2,), None, None)])
        first4 = lambda s: s.select_hyperslab((0,), (1,), (1,), (4,))
        rest = lambda s: s.select_hyperslab((4,), (1,), (1,), (U,))
        all_of = lambda s: s.select_hyperslab((0,), (1,), (1,), (U,))
        f.create_dataset("four", data=np.arange(4, dtype="i4"))
        _virtual(f, "floored", (4,), (U,), [(first4, ".", "four", (4,), None, None),
                                           (rest, "vds_unlim_src.h5", "empty", (0,), (U,), all_of)])
        _virtual(f, "moved", (2,), None, [(lambda s: s.select_all(), "/nonexistent/elsewhere/vds_printf_0.h5",
                                           "data", (2, 3), None, lambda s: s.select_hyperslab((0, 0), (1, 2)))])
        _virtual(f, "reldir", (2,), None, [(lambda s: s.select_all(), "sub/vds_printf_0.h5", "data", (2, 3), None,
                                            lambda s: s.select_hyperslab((0, 0), (1, 2)))])
        _virtual(f, "lost", (2,), None, [(lambda s: s.select_all(), "/nonexistent/elsewhere/vds_lost.h5", "data",
                                          (2,), None, None)])
    with h5py.File(path, "a") as f:
        for name in ("rows", "cols", "printf", "printf_moved", "printf_gap", "printf_names", "printf_none",
                     "floored", "moved", "reldir", "lost"):
            d = f[name]
            d.attrs["expected_shape"] = np.array(d.shape, dtype="i8")
            d.attrs["expected"] = d[...].reshape(-1) if d.size else np.zeros(0, dtype="i4")


class _RefT(ctypes.Structure):
    """H5R_ref_t: the opaque 64-byte revised reference (H5R_REF_BUF_SIZE)."""
    _fields_ = [("data", ctypes.c_uint8 * 64)]


def _revised_refs(lib, f, ext):
    """Builds the revised references; returns (name -> list of H5R_ref_t or None) to write."""
    fid = ctypes.c_int64(f.id.id)
    space = lambda: h5py.h5s.create_simple((4, 5))

    def obj(loc, name):
        ref = _RefT()
        if lib.H5Rcreate_object(ctypes.c_int64(loc), name.encode(), ctypes.c_int64(0), ctypes.byref(ref)) < 0:
            raise RuntimeError("H5Rcreate_object failed")
        return ref

    def region(sel):
        ref = _RefT()
        if lib.H5Rcreate_region(fid, b"data", ctypes.c_int64(sel.id), ctypes.c_int64(0), ctypes.byref(ref)) < 0:
            raise RuntimeError("H5Rcreate_region failed")
        return ref

    def attr(name, attr_name):
        ref = _RefT()
        if lib.H5Rcreate_attr(fid, name.encode(), attr_name.encode(), ctypes.c_int64(0), ctypes.byref(ref)) < 0:
            raise RuntimeError("H5Rcreate_attr failed")
        return ref

    block = space()
    block.select_hyperslab((1, 1), (2, 3))
    points = space()
    points.select_elements(np.array([(0, 0), (3, 4), (2, 2)], dtype="u8"))
    every = space()
    every.select_all()
    blocks = space()
    blocks.select_hyperslab((0, 0), (1, 2))
    blocks.select_hyperslab((2, 3), (2, 2), op=h5py.h5s.SELECT_OR)
    return {
        "objects": [obj(f.id.id, "data"), obj(f.id.id, "grp"), None, obj(f.id.id, "/")],
        "regions": [region(block), region(points), None, region(every), region(blocks)],
        "attributes": [attr("data", "note"), attr("grp", "title"), None],
        "mixed": [obj(f.id.id, "grp"), region(block), attr("data", "note"), None],
        "external": [obj(ext.id.id, "data")],
    }


def build_revised_refs(out):
    """Revised references (H5R_ref_t, HDF5 1.12+) through h5py's bundled libhdf5, which h5py does not
    wrap: datasets of H5T_STD_REF holding object, region (block, points, all, several blocks) and attribute
    references, null ones, a mix, one into another file, and an attribute of them. libhdf5 stores the
    datatype as an object reference whatever an element holds, so each element carries its own kind."""
    lib = _hdf5_library()
    lib.H5open()
    std_ref = ctypes.c_int64.in_dll(lib, "H5T_STD_REF_g").value
    for fn in ("H5Dcreate2", "H5Acreate2", "H5Screate_simple"):
        getattr(lib, fn).restype = ctypes.c_int64
    cwd = os.getcwd()
    os.chdir(out)  # an external reference stores the other file's name as it was opened
    try:
        with h5py.File("refs_revised_ext.h5", "w", libver="latest") as ext:
            ext.create_dataset("data", data=np.arange(3, dtype="i4"))
            with h5py.File("refs_revised.h5", "w", libver="latest") as f:
                data = f.create_dataset("data", data=np.arange(20, dtype="i4").reshape(4, 5))
                data.attrs["note"] = np.int32(42)
                f.create_group("grp").attrs["title"] = "group"
                f.attrs["old_regions"] = np.array([data.regionref[1:3, 1:4], data.regionref[0, :]],
                                                  dtype=h5py.regionref_dtype)
                refs = _revised_refs(lib, f, ext)
                refs["on_root"] = [refs["regions"][0], refs["objects"][1]]
                for name, values in refs.items():
                    buf = (_RefT * len(values))(*[v if v is not None else _RefT() for v in values])
                    dims = (ctypes.c_uint64 * 1)(len(values))
                    sid = lib.H5Screate_simple(1, dims, None)
                    if name == "on_root":
                        aid = lib.H5Acreate2(ctypes.c_int64(f.id.id), b"refs", ctypes.c_int64(std_ref),
                                             ctypes.c_int64(sid), ctypes.c_int64(0), ctypes.c_int64(0))
                        ok = aid >= 0 and lib.H5Awrite(ctypes.c_int64(aid), ctypes.c_int64(std_ref), buf) >= 0
                        lib.H5Aclose(ctypes.c_int64(aid))
                    else:
                        did = lib.H5Dcreate2(ctypes.c_int64(f.id.id), name.encode(), ctypes.c_int64(std_ref),
                                             ctypes.c_int64(sid), ctypes.c_int64(0), ctypes.c_int64(0),
                                             ctypes.c_int64(0))
                        ok = did >= 0 and lib.H5Dwrite(ctypes.c_int64(did), ctypes.c_int64(std_ref),
                                                       ctypes.c_int64(0), ctypes.c_int64(0), ctypes.c_int64(0),
                                                       buf) >= 0
                        lib.H5Dclose(ctypes.c_int64(did))
                    lib.H5Sclose(ctypes.c_int64(sid))
                    if not ok:
                        raise RuntimeError("writing revised references to " + name + " failed")
                for name, values in refs.items():
                    for v in values if name != "on_root" else ():  # on_root shares the others' refs
                        if v is not None:
                            lib.H5Rdestroy(ctypes.byref(v))
    finally:
        os.chdir(cwd)


# H5O_SHMESG_*_FLAG: bit (1 << message type) of a shared-message index's type flags.
SHMESG_SDSPACE, SHMESG_DTYPE, SHMESG_FILL, SHMESG_PLINE, SHMESG_ATTR = 1 << 1, 1 << 3, 1 << 5, 1 << 11, 1 << 12


def build_sohm(path, libver):
    """Shared object header messages (SOHM), in two indexes: dataspace, datatype and fill value in one,
    filter pipeline and attribute in the other, with no minimum size. libhdf5 keeps the first copy of a
    message in its object header and moves it to the shared-message heap once a second object shares it,
    so "b" and "s2" reference the heap; attributes always go to the heap. The latest format's 4-byte scalar
    dataspace and 2-byte fill value are tiny heap objects, held in their heap IDs; "big" is a huge one."""
    lib = _hdf5_library()
    fcpl = h5py.h5p.create(h5py.h5p.FILE_CREATE)
    if lib.H5Pset_shared_mesg_nindexes(ctypes.c_int64(fcpl.id), ctypes.c_uint(2)) < 0:
        raise RuntimeError("H5Pset_shared_mesg_nindexes failed")
    for index, flags in enumerate((SHMESG_SDSPACE | SHMESG_DTYPE | SHMESG_FILL, SHMESG_PLINE | SHMESG_ATTR)):
        if lib.H5Pset_shared_mesg_index(ctypes.c_int64(fcpl.id), ctypes.c_uint(index), ctypes.c_uint(flags),
                                        ctypes.c_uint(0)) < 0:
            raise RuntimeError("H5Pset_shared_mesg_index failed")
    fapl = h5py.h5p.create(h5py.h5p.FILE_ACCESS)
    fapl.set_libver_bounds(*libver)
    fid = h5py.h5f.create(path.encode(), h5py.h5f.ACC_TRUNC, fcpl=fcpl, fapl=fapl)
    with h5py.File(fid) as f:
        f.create_group("group").attrs["title"] = "shared"
        for name in ("a", "b"):
            d = f.create_dataset(name, data=np.arange(6, dtype="i4"), chunks=(3,), compression="gzip",
                                 fillvalue=-7)
            d.attrs["units"] = np.int32(7)
        for name in ("s1", "s2"):
            f.create_dataset(name, data=np.int32(42))
        for name in ("pair1", "pair2"):
            f.create_dataset(name, data=np.array([(1, 1.5), (2, 2.5)], dtype=[("x", "<i4"), ("y", "<f8")]))
        many = f.create_dataset("many", data=np.arange(4, dtype="f8"))
        for i in range(12):                                        # dense attribute storage
            many.attrs["attr%02d" % i] = np.int32(i)
        many.attrs["big"] = np.arange(1000, dtype="f8")            # 8000 bytes: a huge heap object


def build_external_paths(out):
    """External raw data (External File List) with an unlimited final slot, and names that leave the
    HDF5 file's directory (a '..' path and an absolute path): Falcon refuses those by default."""
    raw = os.path.join(out, "external_unlimited.raw")
    np.arange(6, dtype="<i4").tofile(raw)
    with h5py.File(os.path.join(out, "external_paths.h5"), "w") as f:
        f.create_dataset("unlimited", shape=(6,), dtype="<i4",
                         external=[("external_unlimited.raw", 0, h5py.h5f.UNLIMITED)])
        f.create_dataset("parent", shape=(2,), dtype="<i4", external=[("../outside.raw", 0, 8)])
        f.create_dataset("absolute", shape=(2,), dtype="<i4", external=[("/nonexistent/abs.raw", 0, 8)])
    with h5py.File(os.path.join(out, "vds_outside.h5"), "w", libver="latest") as f:
        layout = h5py.VirtualLayout(shape=(4,), dtype="i4")
        layout[:] = h5py.VirtualSource("../outside_source.h5", "data", shape=(4,))
        f.create_virtual_dataset("v", layout, fillvalue=-1)


# name -> builder; `python gen_fixtures.py NAME ...` regenerates just those fixtures.
FIXTURES = {
    "vds_loop": lambda: build_vds_loop(OUT),
    "links": lambda: build_links(OUT),
    "heap_limits": lambda: _with_file("heap_limits.h5", build_heap_limits, libver="latest"),
    "vds_encodings": lambda: build_vds_encodings(OUT),
    "region_refs": lambda: build_region_refs(OUT),
    "revised_refs": lambda: build_revised_refs(OUT),
    "plugin_filters": lambda: _with_file("plugin_filters.h5", build_plugin_filters, libver="latest"),
    "vds_unlimited": lambda: build_vds_unlimited(OUT),
    "vds_views": lambda: build_vds_views(OUT),
    "legacy_layouts": lambda: build_legacy_layouts(OUT),
    "vax": lambda: _with_file("vax.h5", build_vax),
    "fsinfo_v0": lambda: build_fsinfo_v0(OUT),
    "driver_and_k": lambda: build_driver_and_k(OUT),
    "sohm": lambda: (build_sohm(os.path.join(OUT, "sohm.h5"), (h5py.h5f.LIBVER_EARLIEST, h5py.h5f.LIBVER_LATEST)),
                     build_sohm(os.path.join(OUT, "sohm_latest.h5"), (h5py.h5f.LIBVER_LATEST, h5py.h5f.LIBVER_LATEST))),
    "external_paths": lambda: build_external_paths(OUT),
    "numeric": lambda: _with_file("numeric.h5", build_numeric, libver="latest"),
    "conversions": lambda: _with_file("conversions.h5", build_conversions),
    "vds_byteorder": lambda: build_vds_byteorder(OUT),
    "userblock": lambda: build_userblock(OUT),
    "chunk_maxshape": lambda: _with_file("chunk_maxshape.h5", build_chunk_maxshape, libver="latest"),
    "layout_v4": lambda: _with_file("layout_v4.h5", build_layout_v4, libver=("v110", "v110")),
    "paged_sparse": lambda: _with_file("paged_sparse.h5", build_paged_sparse, libver="latest"),
    "filtered_single": lambda: _with_file("filtered_single.h5", build_filtered_single, libver="latest"),
    "unwritten": lambda: build_unwritten(OUT),
    "scaleoffset": lambda: build_scaleoffset(os.path.join(OUT, "scaleoffset.h5")),
    "szip": lambda: _with_file("szip.h5", build_szip, libver="latest"),
    "filter_edge": lambda: _with_file("filter_edge.h5", build_filter_edge, libver="latest"),
}


def _with_file(name, builder, **kwargs):
    with h5py.File(os.path.join(OUT, name), "w", **kwargs) as f:
        builder(f)


def main():
    os.makedirs(OUT, exist_ok=True)
    selected = sys.argv[1:]
    if selected:  # regenerate only the named fixtures (see FIXTURES)
        for name in selected:
            FIXTURES[name]()
        print("wrote", ", ".join(selected), "to", OUT)
        print("h5py", h5py.__version__, "| bundled HDF5", h5py.version.hdf5_version)
        return
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
    for build in FIXTURES.values():
        build()
    print("wrote fixtures to", OUT)
    print("h5py", h5py.__version__, "| bundled HDF5", h5py.version.hdf5_version)


if __name__ == "__main__":
    main()
