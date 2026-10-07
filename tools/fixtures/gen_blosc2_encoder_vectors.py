"""Reference vectors for Falcon Core's Blosc2 encoder: the frames hdf5plugin's Blosc2 filter writes.

hdf5-blosc2 (blosc2_filter.c, c-blosc2 3.3.2, as hdf5plugin 7.1.0 builds them) stores every chunk as a
Blosc2 contiguous frame: a one-chunk super-chunk for a chunk of rank 1, a b2nd array of the chunk's shape for
ranks 2 and up. Each case here is a one-chunk dataset written through the filter with h5py, the file closed
and reopened, and the stored chunk read back with read_direct_chunk. Cases cover every internal codec, clevel
0 to 9, no filter, the byte and bit shuffles, delta, and truncated precision (which the filter cannot apply:
it sets no filters_meta, so c-blosc2 refuses, and libhdf5 stores the chunk unfiltered, its filter-mask bit
set); type sizes of 1 to 300 bytes (above 255, c-blosc2 compresses bytes; only at rank 1, as the filter
cannot read back the b2nd array it writes of such a type); ranks 1 to 4 with block shapes
that do and do not divide the chunk; and data that is zeros, one repeated value, smooth, sparse, NaN, or
noise, from a few bytes (stored whole) to several blocks. Dev-time tool (h5py, hdf5plugin, numpy); none is a
Falcon dependency.

Writes core/src/test/resources/fixtures/blosc2_encoder_vectors.txt, one case per line:

    <name> <cd-values> <filter-mask> <input> <chunk>

where <cd-values> are the filter's client data as the file holds them (comma separated), <input> is the
chunk's bytes, zlib-compressed, as hex (or "=" and an earlier case's name, for the same bytes), and <chunk>
the stored chunk (a frame, or the input itself when the
filter-mask is 1) as hex, or for one over 8 KiB, to keep the file small, "sha256:" its length ":" its SHA-256
":" its frame header (the metalayers included) as hex.

Usage (from the repo root):  python tools/fixtures/gen_blosc2_encoder_vectors.py
"""
import hashlib
import os
import tempfile
import zlib

import h5py
import hdf5plugin
import numpy as np

OUT = os.path.join("core", "src", "test", "resources", "fixtures", "blosc2_encoder_vectors.txt")
BLOSC2 = 32026
CNAMES = ["blosclz", "lz4", "lz4hc", "zlib", "zstd"]
rng = np.random.default_rng(32026)


def data(kind, dtype, shape):
    """An array of `kind` values, the same for the same kind, type, and shape."""
    rng = np.random.default_rng([zlib.crc32(f"{kind} {dtype} {shape}".encode())])
    n = int(np.prod(shape))
    dt = np.dtype(dtype)
    if dt.kind == "V" or dt.names:
        raw = np.zeros(n * dt.itemsize, dtype=np.uint8)
        if kind == "zeros":
            pass
        elif kind == "const":
            raw[:] = np.tile(np.arange(dt.itemsize, dtype=np.uint8) + 7, n)
        elif kind == "noise":
            raw[:] = rng.integers(0, 256, raw.size, dtype=np.uint8)
        else:  # a slowly changing record
            base = np.arange(n, dtype=np.uint32)
            for k in range(dt.itemsize):
                raw[k::dt.itemsize] = ((base >> (k % 4)) + k).astype(np.uint8) if k < 8 else k % 256
        return raw.view(dt).reshape(shape)
    if kind == "zeros":
        return np.zeros(shape, dtype=dt)
    if kind == "const":
        return np.full(shape, 3 if dt.kind in "iu" else 2.5, dtype=dt)
    if kind == "arange":
        return (np.arange(n) % (np.iinfo(dt).max if dt.kind in "iu" else 1 << 20)).astype(dt).reshape(shape)
    if kind == "smooth":  # whole numbers, so the vectors file's zlib packs the input well
        x = np.linspace(0, 12, n)
        v = np.round(np.sin(x) * 1000 + x * 30)
        return v.astype(dt).reshape(shape)
    if kind == "sparse":
        a = np.zeros(n, dtype=dt)
        idx = rng.choice(n, size=max(1, n // 50), replace=False)
        a[idx] = (rng.integers(1, 100, idx.size)).astype(dt)
        return a.reshape(shape)
    if kind == "nan":
        return np.full(shape, np.nan, dtype=dt)
    if kind == "steps":  # runs of repeated values: zero runs and nonzero runs in the split streams
        return (np.arange(n) // 97 * 1000003 % 251).astype(dt).reshape(shape)
    if kind == "noise":
        if dt.kind == "f":
            return rng.standard_normal(n).astype(dt).reshape(shape)
        info = np.iinfo(dt)
        return rng.integers(info.min, info.max, n, dtype=dt, endpoint=True).reshape(shape)
    if kind == "lownoise":  # a smooth signal with a few noisy low bits
        base = (np.arange(n) * 3).astype(np.int64)
        noise = rng.integers(0, 4, n) * (rng.random(n) < 0.125)
        v = base + noise
        return (v % (np.iinfo(dt).max if dt.kind in "iu" else 1 << 24)).astype(dt).reshape(shape)
    raise ValueError(kind)


CASES = []


def case(name, dtype, shape, kind, cname="blosclz", clevel=5, filters=1, block=0):
    CASES.append((name, dtype, tuple(shape), kind, cname, clevel, filters, block))


# Every codec, clevel, and filter on a few shapes and kinds.
for cname in CNAMES:
    for clevel in range(10):
        for filters in (0, 1, 2, 3):
            for dtype, shape, kind in (("<f4", (2000,), "smooth"), ("<i2", (40, 50), "lownoise"),
                                       ("<f8", (6, 7, 9), "smooth"), ("<u1", (3000,), "steps")):
                if (clevel + filters + len(shape)) % 3 and clevel not in (0, 1, 5, 9):
                    continue
                case(f"{cname}{clevel}_f{filters}_{np.dtype(dtype).str[1:]}_{'x'.join(map(str, shape))}_{kind}",
                     dtype, shape, kind, cname, clevel, filters)

# Truncated precision: the filter fails and the chunk is stored unfiltered.
for dtype in ("<f4", "<f8"):
    case(f"truncprec_{dtype[1:]}", dtype, (300,), "smooth", "lz4", 5, 4)
    case(f"truncprec2d_{dtype[1:]}", dtype, (20, 30), "smooth", "zstd", 5, 4)

# Type sizes, ranks, and shapes.
SHAPES = [(5,), (8,), (31,), (100,), (4097,), (70000,), (1, 50), (50, 1), (10, 10), (7, 13), (64, 64),
          (3, 300), (100, 300), (4, 5, 6), (16, 16, 16), (2, 1, 37), (30, 40, 50), (3, 4, 5, 6), (8, 8, 8, 8),
          (1, 1, 1, 9), (5, 1, 7, 1)]
DTYPES = ["|i1", "<u2", "<i4", "<f4", "<f8", "<i8", "V3", "V12", "V20", "V300"]
KINDS = ["zeros", "const", "arange", "smooth", "sparse", "steps", "noise", "lownoise"]
i = 0
for shape in SHAPES:
    for dtype in DTYPES:
        n = int(np.prod(shape)) * np.dtype(dtype).itemsize
        if n > 1_200_000:
            continue
        if np.dtype(dtype).itemsize > 255 and len(shape) > 1:
            # c-blosc2 caps the frame's type size at 1, and then cannot read its own b2nd array back
            continue
        i += 1
        kinds = KINDS if np.dtype(dtype).kind != "V" else ["zeros", "const", "arange", "noise"]
        kind = kinds[i % len(kinds)]
        if kind == "noise" and n > 8_000:
            kind = "lownoise" if np.dtype(dtype).kind != "V" else "arange"
        cname = CNAMES[i % len(CNAMES)]
        clevel = [5, 1, 9, 3, 7, 0, 4][i % 7]
        filters = [1, 2, 0, 3][i % 4]
        case(f"t{i}_{np.dtype(dtype).str[1:]}_{'x'.join(map(str, shape))}_{kind}_{cname}{clevel}_f{filters}",
             dtype, shape, kind, cname, clevel, filters)

# Special chunks and runs: zeros (the special zero chunk), NaN, a repeated value, under each filter.
for filters in (0, 1, 2, 3):
    for dtype, kind in (("<f4", "zeros"), ("<f8", "nan"), ("<i4", "const"), ("<u2", "steps")):
        for shape in ((1000,), (40, 40)):
            case(f"special_{kind}_{np.dtype(dtype).str[1:]}_{len(shape)}d_f{filters}", dtype, shape, kind,
                 "lz4", 5, filters)

# Larger chunks: several blocks, the tuner's sizes, split and unsplit streams, a short last block.
for cname in CNAMES:
    for clevel in (1, 5, 9):
        case(f"big_{cname}{clevel}_f4_1d", "<f4", (100003,), "smooth", cname, clevel, 1)
        case(f"big_{cname}{clevel}_i2_2d", "<i2", (300, 401), "lownoise", cname, clevel, 2)
        case(f"big_{cname}{clevel}_f8_3d", "<f8", (20, 30, 41), "smooth", cname, clevel, 3)
    case(f"big_{cname}_u1_1d_noise", "|u1", (20001,), "noise", cname, 5, 0)

# A block size in client data value 1 (hdf5plugin's API never sets one): the plain frame's blocks, and the
# b2nd block shape the filter derives from it.
for block in (64, 1000, 4096, 65536, 1 << 20):
    case(f"block{block}_f4_1d", "<f4", (20000,), "smooth", "lz4", 5, 1, block)
    case(f"block{block}_i2_2d", "<i2", (100, 130), "lownoise", "blosclz", 5, 2, block)
    case(f"block{block}_f8_4d", "<f8", (6, 5, 7, 9), "smooth", "zlib", 3, 3, block)
    case(f"block{block}_u1_3d", "|u1", (20, 30, 40), "steps", "lz4hc", 5, 1, block)

# Random cases.
for k in range(200):
    rank = int(rng.integers(1, 5))
    shape = tuple(int(rng.integers(1, [5000, 80, 24, 10][rank - 1])) for _ in range(rank))
    dtype = DTYPES[int(rng.integers(0, len(DTYPES) - 1))]  # all but V300
    kinds = KINDS if np.dtype(dtype).kind != "V" else ["zeros", "const", "arange", "noise"]
    kind = kinds[int(rng.integers(0, len(kinds)))]
    case(f"r{k}", dtype, shape, kind, CNAMES[int(rng.integers(0, 5))], int(rng.integers(0, 10)),
         int(rng.integers(0, 4)))


def main():
    lines = []
    seen = {}
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "v.h5")
        arrays = {}
        with h5py.File(path, "w") as f:
            for name, dtype, shape, kind, cname, clevel, filters, block in CASES:
                a = data(kind, dtype, shape)
                arrays[name] = a
                options = hdf5plugin.Blosc2(cname=cname, clevel=clevel, filters=filters)
                if block:
                    opts = list(options["compression_opts"])
                    opts[1] = block
                    options = dict(options, compression_opts=tuple(opts))
                f.create_dataset(name, data=a, chunks=shape, **options)
        with h5py.File(path, "r") as f:
            for name, dtype, shape, kind, cname, clevel, filters, block in CASES:
                ds = f[name]
                cd = ds.id.get_create_plist().get_filter_by_id(BLOSC2)[1]
                mask, chunk = ds.id.read_direct_chunk((0,) * len(shape))
                raw = arrays[name].tobytes()
                if mask:
                    assert bytes(chunk) == raw, name
                else:
                    assert np.array_equal(ds[...].view(np.uint8), arrays[name].view(np.uint8)), name
                chunk = bytes(chunk)
                if len(chunk) <= 8192:
                    stored = chunk.hex()
                else:
                    assert not mask, name
                    header = chunk[:int.from_bytes(chunk[11:15], "big")]
                    stored = f"sha256:{len(chunk)}:{hashlib.sha256(chunk).hexdigest()}:{header.hex()}"
                packed = zlib.compress(raw, 9).hex()
                if packed in seen:
                    packed = "=" + seen[packed]
                else:
                    seen[packed] = name
                lines.append(f"{name} {','.join(map(str, cd))} {mask} {packed} {stored}\n")
    with open(OUT, "w", newline="\n") as out:
        out.writelines(lines)
    print(f"{len(lines)} cases -> {OUT} ({os.path.getsize(OUT)} bytes)")


if __name__ == "__main__":
    main()
