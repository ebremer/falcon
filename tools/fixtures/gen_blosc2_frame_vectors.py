"""Generates Blosc2 contiguous frames (cframes), plain and b2nd, for Falcon Core's frame reader.

Dev-time tool only -- h5py, hdf5plugin and imagecodecs are NOT Falcon dependencies. Install with
tools/fixtures/requirements.txt. Run from the repo root:  python tools/fixtures/gen_blosc2_frame_vectors.py

Writes core/src/test/resources/fixtures/blosc2_frame_vectors.txt with one case per line:

    <name> <kind> <frame-hex> <expected-hex>

where <kind> is "b2nd" (the expected bytes are the whole array, in C order) or "plain" (the first chunk,
decoded). Two kinds of case, each read back through hdf5plugin's Blosc2 filter (hdf5-blosc2 with c-blosc2
3.3.2), which is the oracle:

- plugin: frames the filter wrote for one-chunk datasets -- plain frames for rank-1 chunks, b2nd frames for
  ranks 2 to 4, with block shapes that do and do not divide the chunk shape (the filter's block size in
  client data slot 1), every internal codec, clevel 0 to 9, no filter, shuffle, bitshuffle, and delta (the
  filter cannot ask for truncated precision: it sets no filters_meta, and c-blosc2 then refuses), and chunks
  of zeros, NaNs, or one repeated value.
- crafted: frames c-blosc2 can write but the filter does not -- a b2nd array of several chunks smaller than
  its shape (padding at the array's and the chunk's edges), chunks the index marks as zeros, NaNs, or
  uninitialised, the older "caterva" metalayer, other metalayers and variable-length metalayers, and
  variable-size chunks. Each is assembled here as c-blosc2's frame.c writes it (new_header_frame,
  frame_update_trailer; README_CFRAME_FORMAT.rst, README_B2ND_METALAYER.rst) from chunks c-blosc2
  compressed (imagecodecs), then written into a dataset with write_direct_chunk and read back.

gen_fixtures.py's blosc2.h5 imports crafted_frames() from here for its direct-chunk datasets.
"""
import os
import struct
import tempfile

import numpy as np

OUT = os.path.join("core", "src", "test", "resources", "fixtures", "blosc2_frame_vectors.txt")
BLOSC2 = 32026
COMPCODES = {"blosclz": 0, "lz4": 1, "lz4hc": 2, "zlib": 4, "zstd": 5}
SPECIAL = {"zeros": 1, "nan": 2, "uninit": 4}


# ---------------------------------------------------------------------------------------------------
# A cframe, byte by byte (c-blosc2 3.3.2 frame.c).

def _meta_block(entries, counted):
    """The metalayer index after its fixarray(3) marker, in the header or the trailer: uint16 index size (from
    the marker in the header, after it in the trailer: `counted` bytes before the index), map16 of name ->
    int32 offset; and the start of the array16 of bin32 contents."""
    index = bytearray([0xcd, 0, 0, 0xde]) + struct.pack(">H", len(entries))
    slots = []
    for name, _ in entries:
        raw = name.encode("ascii")
        assert len(raw) < 32
        index += bytes([0xa0 + len(raw)]) + raw + b"\xd2"
        slots.append(len(index))
        index += b"\0\0\0\0"
    struct.pack_into(">H", index, 1, counted + len(index))
    values = bytearray([0xdc]) + struct.pack(">H", len(entries))
    return index, slots, values


def _header(nbytes, cbytes, typesize, blocksize, chunksize, metalayers, vlmeta, clevel, compcode, filters):
    h = bytearray(87)
    h[0], h[1] = 0x9e, 0xa8
    h[2:10] = b"b2frame\0"
    h[10], h[15], h[24] = 0xd2, 0xcf, 0xa4
    h[25] = (3 | 0x10 | 0x40) if chunksize == 0 else (2 | 0x10)  # version, 64-bit offsets, variable chunks
    h[26] = 0                                                     # contiguous
    h[27] = compcode | (clevel << 4)
    h[28] = 3                                                     # BLOSC_FORWARD_COMPAT_SPLIT - 1
    h[29] = 0xd3
    struct.pack_into(">q", h, 30, nbytes)
    h[38] = 0xd3
    struct.pack_into(">q", h, 39, cbytes)
    h[47] = 0xd2
    struct.pack_into(">i", h, 48, typesize)
    h[52] = 0xd2
    struct.pack_into(">i", h, 53, blocksize)
    h[57] = 0xd2
    struct.pack_into(">i", h, 58, chunksize)
    h[62], h[65] = 0xd1, 0xd1
    struct.pack_into(">h", h, 66, 1)
    h[68] = 0xc3 if vlmeta else 0xc2
    h[69], h[70] = 0xd8, 6
    h[71:77] = bytes(filters)
    h[77] = compcode
    index, slots, values = _meta_block(metalayers, 1)
    h += b"\x93" + index
    base = 87 + 1
    for (name, content), slot in zip(metalayers, slots):
        struct.pack_into(">i", h, base + slot, len(h) + len(values))
        values += b"\xc6" + struct.pack(">i", len(content)) + content
    h += values
    struct.pack_into(">i", h, 11, len(h))
    return h


def _trailer(vlmeta):
    t = bytearray([0x94, 1, 0x93])
    index, slots, values = _meta_block(vlmeta, 0)
    t += index
    for (name, content), slot in zip(vlmeta, slots):
        struct.pack_into(">i", t, 3 + slot, len(t) + len(values))
        values += b"\xc6" + struct.pack(">i", len(content)) + content
    t += values
    length = len(t) + 23
    t += b"\xce" + struct.pack(">I", length) + b"\xd8\x00" + bytes(16)
    return t


def cframe(chunks, typesize, chunksize, nbytes, blocksize=0, metalayers=(), vlmeta=(), clevel=5,
           compcode=0, filters=(0, 0, 0, 0, 0, 1)):
    """A frame of `chunks`: each a Blosc2 chunk (bytes), or a special value's name for an index entry."""
    import imagecodecs
    section = bytearray()
    offsets = []
    for chunk in chunks:
        if isinstance(chunk, str):
            offsets.append(struct.unpack("<q", struct.pack("<Q", (0x80 | SPECIAL[chunk]) << 56))[0])
        else:
            offsets.append(len(section))
            section += chunk
    index = bytes(imagecodecs.blosc2_encode(np.array(offsets, dtype="<i8"), level=5, compressor="zstd",
                                            shuffle=1)) if chunks else b""
    header = _header(nbytes, len(section), typesize, blocksize, chunksize, list(metalayers), list(vlmeta),
                     clevel, compcode, filters)
    frame = header + section + index + _trailer(list(vlmeta))
    struct.pack_into(">q", frame, 16, len(frame))
    return bytes(frame)


def b2nd_meta(shape, chunkshape, blockshape, dtype):
    n = len(shape)
    out = bytearray([0x97, 0, n, 0x90 + n])
    for s in shape:
        out += b"\xd3" + struct.pack(">q", s)
    out += bytes([0x90 + n])
    for c in chunkshape:
        out += b"\xd2" + struct.pack(">i", c)
    out += bytes([0x90 + n])
    for b in blockshape:
        out += b"\xd2" + struct.pack(">i", b)
    out += b"\x00\xdb" + struct.pack(">i", len(dtype)) + dtype.encode("ascii")
    return bytes(out)


def b2nd_chunks(array, chunkshape, blockshape):
    """The array cut into b2nd chunks: each padded to whole blocks, its blocks in C order, each block's items
    in C order, padding zero (b2nd.c get_set_slice)."""
    shape = array.shape
    ext_chunk = [c if c % b == 0 else c + b - c % b for c, b in zip(chunkshape, blockshape)]
    grid = [-(-s // c) for s, c in zip(shape, chunkshape)]
    blocks_in = [e // b for e, b in zip(ext_chunk, blockshape)]
    out = []
    for cidx in np.ndindex(*grid):
        buf = []
        for bidx in np.ndindex(*blocks_in):
            block = np.zeros(blockshape, dtype=array.dtype)
            src, dst = [], []
            for d in range(len(shape)):
                c0 = cidx[d] * chunkshape[d]
                cstop = min(c0 + chunkshape[d], shape[d])
                b0 = min(c0 + bidx[d] * blockshape[d], cstop)
                b1 = min(b0 + blockshape[d], cstop)
                src.append(slice(b0, b1))
                dst.append(slice(0, b1 - b0))
            block[tuple(dst)] = array[tuple(src)]
            buf.append(block.tobytes())
        out.append(b"".join(buf))
    return out


def _encode(raw, typesize, cname="lz4", level=5, shuffle=1, blocksize=0):
    import imagecodecs
    view = np.frombuffer(raw, dtype=f"V{typesize}" if typesize > 1 else "i1")
    return bytes(imagecodecs.blosc2_encode(view, level=level, compressor=cname, shuffle=shuffle,
                                           blocksize=blocksize))


def crafted_frames():
    """(name, cd chunk shape or None, dtype, frame, expected bytes, defined) for frames c-blosc2 can write but
    the filter does not. A b2nd frame's expected bytes are its array; a plain frame's, its first chunk.
    `defined` masks the bytes c-blosc2 defines: it leaves an uninitialised chunk's items as its buffer held
    them, where Falcon reads zeros."""
    rng = np.random.default_rng(32026)
    cases = []

    def b2nd(name, array, chunkshape, blockshape, specials=None, meta_name="b2nd", extra=(), vlmeta=(),
             cname="lz4", shuffle=1):
        ts = array.dtype.itemsize
        raw_chunks = b2nd_chunks(array, chunkshape, blockshape)
        block_bytes = int(np.prod(blockshape)) * ts
        chunks = []
        expected = array.copy()
        defined = np.ones(array.shape, dtype=bool)
        for i, raw in enumerate(raw_chunks):
            kind = (specials or {}).get(i)
            chunks.append(kind if kind else _encode(raw, ts, cname, shuffle=shuffle, blocksize=block_bytes))
        if specials:  # the expected array holds what each special chunk decodes to
            grid = [-(-s // c) for s, c in zip(array.shape, chunkshape)]
            for i, cidx in enumerate(np.ndindex(*grid)):
                kind = specials.get(i)
                if kind:
                    sl = tuple(slice(c * k, min(c * k + k, s)) for c, k, s in zip(cidx, chunkshape, array.shape))
                    expected[sl] = np.nan if kind == "nan" else 0
                    defined[sl] = kind != "uninit"
        meta = [(meta_name, b2nd_meta(array.shape, chunkshape, blockshape, f"|V{ts}"))] + list(extra)
        chunk_bytes = len(raw_chunks[0])
        frame = cframe(chunks, ts, chunk_bytes, chunk_bytes * len(chunks), block_bytes, metalayers=meta,
                       vlmeta=vlmeta, compcode=COMPCODES[cname])
        cases.append((name, list(array.shape), array.dtype.str, frame, expected.tobytes(),
                      np.repeat(defined.ravel(), ts)))

    smooth = np.cumsum(rng.normal(size=(10, 7)), axis=1).astype("<f4")
    b2nd("multichunk_padded_f4", smooth, (4, 4), (3, 2))
    b2nd("multichunk_exact_i2", np.arange(96, dtype="<i2").reshape(8, 12), (4, 6), (2, 3), cname="zstd")
    cube = rng.integers(0, 50, size=(5, 6, 7)).astype("<i4")
    b2nd("multichunk_3d_i4", cube, (2, 4, 3), (2, 3, 2), cname="blosclz", shuffle=2)
    b2nd("special_offsets_f8", np.cumsum(rng.normal(size=(9, 8)), axis=0).astype("<f8"), (4, 4), (2, 4),
         specials={1: "zeros", 2: "nan", 4: "uninit"})
    b2nd("special_nan_f4", np.ones((6, 6), dtype="<f4"), (3, 6), (3, 3), specials={0: "nan"})
    b2nd("caterva_metalayer_u1", rng.integers(0, 4, size=(7, 9)).astype("u1"), (7, 9), (4, 4),
         meta_name="caterva", cname="zlib")
    b2nd("other_metalayers_f4", smooth, (10, 7), (5, 7),
         extra=[("units", b"\xa6metres"), ("history", b"\x91\xa4made")],
         vlmeta=[("comment", _encode(b"crafted for Falcon", 1)), ("x", _encode(b"\0" * 64, 1))])

    # Plain frames: the first chunk is the data; later ones are ignored by the filter.
    ramp = np.arange(256, dtype="<i4")
    first = _encode(ramp.tobytes(), 4, "zstd")
    plain = [("plain_two_chunks_i4", "<i4", cframe([first, _encode(ramp[::-1].tobytes(), 4)], 4, 1024, 2048,
                                                   compcode=5), ramp.tobytes()),
             ("plain_special_zeros_f4", "<f4", cframe(["zeros"], 4, 400, 400), bytes(400)),
             ("plain_special_nan_f8", "<f8", cframe(["nan"], 8, 160, 160), np.full(20, np.nan).tobytes()),
             ("plain_variable_chunks_i4", "<i4", cframe([first, _encode(ramp[:100].tobytes(), 4)], 4, 0, 1424),
              ramp.tobytes())]
    for name, dtype, frame, expected in plain:
        cases.append((name, None, dtype, frame, expected, np.ones(len(expected), dtype=bool)))
    return cases


# ---------------------------------------------------------------------------------------------------
# The oracle: hdf5plugin's Blosc2 filter.

def plugin_decode(frame, shape, dtype, cd_shape):
    """Reads `frame` back through the filter as the only chunk of a dataset of `shape`."""
    import h5py
    import hdf5plugin  # noqa: F401  (registers the filter)
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "f.h5")
        with h5py.File(path, "w") as f:
            ds = f.create_dataset("d", shape=shape, dtype=dtype, chunks=tuple(shape), compression=BLOSC2,
                                  compression_opts=(0, 0, 0, 0, 5, 1, 0))
            ds.id.write_direct_chunk((0,) * len(shape), frame)
        with h5py.File(path, "r") as f:
            cd = f["d"].id.get_create_plist().get_filter(0)[2]
            assert (cd_shape is None and len(cd) == 7) or list(cd[8:]) == list(cd_shape), cd
            return f["d"][()].tobytes()


def plugin_frame(data, chunks, **filter_options):
    """The frame the filter writes for `data` as one chunk, and what the filter reads back from it."""
    import h5py
    import hdf5plugin  # noqa: F401
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "f.h5")
        with h5py.File(path, "w") as f:
            f.create_dataset("d", data=data, chunks=chunks, **filter_options)
        with h5py.File(path, "r") as f:
            ds = f["d"]
            mask, raw = ds.id.read_direct_chunk((0,) * ds.ndim)
            assert mask == 0
            return bytes(raw), ds[()].tobytes()


def blosc2_options(cname="blosclz", clevel=5, shuffle=1, blocksize=0):
    return dict(compression=BLOSC2, compression_opts=(0, blocksize, 0, 0, clevel, shuffle, COMPCODES[cname]))


def plugin_cases():
    rng = np.random.default_rng(26)
    cases = []

    def add(name, data, **options):
        frame, decoded = plugin_frame(data, data.shape, **options)
        assert decoded == data.tobytes(), name
        kind = "b2nd" if data.ndim > 1 else "plain"
        assert (b"b2nd" in frame[:200]) == (kind == "b2nd"), name
        cases.append((name, kind, frame, decoded))

    smooth = np.cumsum(rng.normal(size=(12, 10)), axis=1)
    add("r1_blosclz_i4", np.arange(300, dtype="<i4"), **blosc2_options())
    add("r1_zstd_bitshuffle_f8", smooth.ravel(), **blosc2_options("zstd", 9, 2))
    add("r1_zeros_f4", np.zeros(64, dtype="<f4"), **blosc2_options("lz4"))
    add("r2_lz4_padded_f4", smooth.astype("<f4")[:7, :5], **blosc2_options("lz4", 5, 1, 32))
    add("r2_lz4hc_exact_i2", np.arange(256, dtype="<i2").reshape(16, 16), **blosc2_options("lz4hc", 1, 1, 128))
    add("r2_zlib_clevel0_f8", smooth[:5, :9], **blosc2_options("zlib", 0, 0, 40))
    add("r2_delta_i4", np.arange(120, dtype="<i4").reshape(10, 12) * 3, **blosc2_options("zstd", 5, 3, 64))
    add("r3_zstd_noshuffle_u1", rng.integers(0, 9, size=(5, 6, 7)).astype("u1"), **blosc2_options("zstd", 1, 0, 24))
    add("r4_blosclz_bitshuffle_f4", rng.normal(size=(3, 4, 5, 6)).astype("<f4"), **blosc2_options("blosclz", 9, 2, 48))
    add("r2_nan_f8", np.full((6, 5), np.nan), **blosc2_options("lz4", 5, 1, 64))
    add("r2_value_i2", np.full((9, 8), 7, dtype="<i2"), **blosc2_options("lz4", 5, 1))
    add("r2_zeros_compound", np.zeros((4, 3), dtype=[("a", "<i2"), ("b", "<f8")]), **blosc2_options("zstd"))
    add("r2_noise_u1", rng.integers(0, 256, size=(20, 30)).astype("u1"), **blosc2_options("lz4", 9, 1, 128))
    return cases


def main():
    lines = ["# generated by tools/fixtures/gen_blosc2_frame_vectors.py (hdf5plugin 7.1's Blosc2 filter, c-blosc2 3.3.2)",
             "# <name> <kind> <frame-hex> <expected-hex>"]
    count = 0
    for name, kind, frame, decoded in plugin_cases():
        lines.append(f"{name} {kind} {frame.hex()} {decoded.hex()}")
        count += 1
    for name, cd_shape, dtype, frame, expected, defined in crafted_frames():
        kind = "b2nd" if cd_shape else "plain"
        n = np.dtype(dtype).itemsize
        shape = cd_shape if cd_shape else [len(expected) // n]
        decoded = np.frombuffer(plugin_decode(frame, shape, dtype, cd_shape), dtype="u1")
        assert np.array_equal(decoded[defined], np.frombuffer(expected, dtype="u1")[defined]), name
        lines.append(f"crafted_{name} {kind} {frame.hex()} {expected.hex()}")
        count += 1
    with open(OUT, "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", count, "frames to", OUT)


if __name__ == "__main__":
    main()
