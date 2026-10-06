"""Generates Zarr fixtures that use numcodecs' BZ2 and ZFPY codecs, with zarr-python (the reference oracle).

Dev-time tool only -- zarr-python, numcodecs, and zfpy are NOT Falcon dependencies (zfpy 1.0.1 is needed for
numcodecs.ZFPY). Run from the repo root:  python tools/fixtures/gen_zarr_bz2_zfpy_fixtures.py

zarr-python 3 writes numcodecs' BZ2 into Zarr v3 metadata as "numcodecs.bz2", a bytes->bytes codec, and ZFPY
as "numcodecs.zfpy", an array->bytes codec (the chunk array is compressed whole, zfp's header giving its type
and shape back); a Zarr v2 array names them "bz2" and "zfpy" as its compressor (or among its filters). Each case
writes zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json: the shape, the dtype, the codec names
Falcon's pipeline has (a v2 array's as Falcon translates its .zarray), and what zarr-python reads back, every
element in C order (zfp is lossy: the values are zarr-python's decoded ones, which Falcon must match bit for
bit). A large array's sidecar holds the SHA-256 of its elements, little-endian, instead of the elements.

The bz2 cases cover the levels, chunks of several bzip2 blocks, bz2 under a shard, among v2 filters, and chunks
rewritten as zarr-python's numcodecs reads them though it never writes them: several bzip2 streams one after
another, and a stream followed by bytes that are not one (Python's bz2.decompress joins the streams and drops
the junk). The zfpy cases cover int32, int64, float32, and float64, 1 to 4 dimensions, and every mode
numcodecs reaches: fixed rate, precision, and accuracy, a tolerance of 0 (zfp's expert mode), and numcodecs'
defaults (zfp's reversible mode); after a transpose, under a shard, before crc32c, and in v2 after a delta
filter (which hands zfpy a flat array, not the chunk's shape).
"""
import bz2
import hashlib
import json
import os
import shutil
import warnings

import numcodecs
import numpy as np
import zarr
import zarr.storage
import zfpy
from zarr.codecs import Crc32cCodec, TransposeCodec
from zarr.codecs.numcodecs import BZ2, ZFPY

warnings.simplefilter("ignore")  # zarr-python warns that numcodecs.* codecs have no stable v3 spec yet

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")
HASH_OVER = 20000  # arrays with more elements than this get a SHA-256 in their sidecar

CASES = []


def case(name, shape, chunks, dtype, values, *, v2=False, compressor=None, serializer=None, filters=None,
         shards=None, rewrite=None):
    CASES.append(dict(name=name, shape=shape, chunks=chunks, dtype=np.dtype(dtype), values=values, v2=v2,
                      compressor=compressor, serializer=serializer, filters=filters, shards=shards, rewrite=rewrite))


def smooth(shape, dtype, scale=1.0):
    """A smooth field over `shape` (zfp's kind of data), as `dtype`."""
    grids = np.meshgrid(*[np.linspace(0, 3, n) for n in shape], indexing="ij")
    field = sum(np.sin(g * (k + 1)) for k, g in enumerate(grids)) * scale
    return field.astype(dtype)


def ints(n, dtype, step=3):
    return (np.arange(n) * step - n // 3).astype(dtype)


def concatenated(stored):
    """A chunk's bzip2 stream rewritten as two streams one after another, at other levels."""
    raw = bz2.decompress(stored)
    cut = len(raw) // 3
    return bz2.compress(raw[:cut], 9) + bz2.compress(raw[cut:], 2)


def with_junk(stored):
    """A chunk's bzip2 stream with bytes after it that are not a stream (Python ignores them)."""
    return stored + b"\0junk\0"


def rewrite_chunks(fn, keys):
    def apply(path):
        for key in keys:
            p = os.path.join(path, *key.split("/"))
            data = open(p, "rb").read()
            open(p, "wb").write(fn(data))
    return apply


# ---- bz2, Zarr v3 ----
case("numcodecs_bz2_default_int32", (2000,), (700,), "<i4", ints(2000, "<i4"), compressor=BZ2())
case("numcodecs_bz2_l9_float64", (30, 40), (16, 16), "<f8", smooth((30, 40), "<f8"), compressor=BZ2(level=9))
case("numcodecs_bz2_l5_int16_2d", (50, 21), (20, 21), "<i2", ints(1050, "<i2").reshape(50, 21),
     compressor=BZ2(level=5))
case("numcodecs_bz2_three_blocks_uint8", (250000,), (250000,), "|u1",
     ((np.arange(250000) % 7) * 37 % 256).astype("|u1"), compressor=BZ2(level=1))
case("numcodecs_bz2_sharded_crc32c_int32", (64, 6), (16, 6), "<i4", ints(384, "<i4").reshape(64, 6),
     compressor=[BZ2(level=3), Crc32cCodec()], shards=(32, 6))
case("numcodecs_bz2_concatenated_int32", (900,), (300,), "<i4", ints(900, "<i4", 7), compressor=BZ2(level=4),
     rewrite=[rewrite_chunks(concatenated, ["c/0"]), rewrite_chunks(with_junk, ["c/2"])])
# ---- bz2, Zarr v2 ----
case("v2x_nc_bz2", (1000,), (300,), "<i4", ints(1000, "<i4", 5), v2=True, compressor=numcodecs.BZ2(level=9))
case("v2x_nc_bz2_default_f4", (12, 25), (5, 10), "<f4", smooth((12, 25), "<f4"), v2=True,
     compressor=numcodecs.BZ2())
case("v2x_nc_bz2_filter_zlib", (40,), (16,), "<i8", ints(40, "<i8", 11), v2=True,
     filters=[numcodecs.BZ2(level=3)], compressor=numcodecs.Zlib(level=1))
case("v2x_nc_delta_bz2", (500,), (128,), "<i8", np.cumsum(ints(500, "<i8")), v2=True,
     filters=[numcodecs.Delta(dtype="<i8")], compressor=numcodecs.BZ2(level=2))
case("v2x_nc_bz2_concatenated", (20,), (8,), "<i4", ints(20, "<i4", 13), v2=True,
     compressor=numcodecs.BZ2(level=1), rewrite=[rewrite_chunks(concatenated, ["0", "2"]),
                                                 rewrite_chunks(with_junk, ["1"])])
# ---- zfpy, Zarr v3 ----
case("numcodecs_zfpy_f8_reversible_2d", (37, 23), (16, 10), "<f8", smooth((37, 23), "<f8"), serializer=ZFPY())
case("numcodecs_zfpy_f4_rate_1d", (1000,), (300,), "<f4", smooth((1000,), "<f4", 7.5),
     serializer=ZFPY(mode=zfpy.mode_fixed_rate, rate=12))
case("numcodecs_zfpy_f8_precision_3d", (9, 10, 11), (5, 4, 6), "<f8", smooth((9, 10, 11), "<f8", 1e3),
     serializer=ZFPY(mode=zfpy.mode_fixed_precision, precision=20))
case("numcodecs_zfpy_f8_accuracy_4d", (6, 5, 7, 9), (4, 3, 5, 4), "<f8", smooth((6, 5, 7, 9), "<f8"),
     serializer=ZFPY(mode=zfpy.mode_fixed_accuracy, tolerance=1e-3))
case("numcodecs_zfpy_f4_expert_2d", (20, 30), (8, 16), "<f4", smooth((20, 30), "<f4", 100),
     serializer=ZFPY(mode=zfpy.mode_fixed_accuracy, tolerance=0))
case("numcodecs_zfpy_i4_rate_2d", (25, 30), (10, 12), "<i4", smooth((25, 30), "<f8", 1e6).astype("<i4"),
     serializer=ZFPY(mode=zfpy.mode_fixed_rate, rate=16))
case("numcodecs_zfpy_i8_reversible_3d", (7, 8, 9), (4, 4, 4), "<i8", ints(504, "<i8", 1 << 33).reshape(7, 8, 9),
     serializer=ZFPY())
case("numcodecs_zfpy_i4_precision_1d", (100,), (64,), "<i4", ints(100, "<i4", 1001),
     serializer=ZFPY(mode=zfpy.mode_fixed_precision, precision=10))
case("numcodecs_zfpy_i8_accuracy_4d", (5, 4, 6, 7), (3, 4, 4, 4), "<i8",
     smooth((5, 4, 6, 7), "<f8", 1e12).astype("<i8"),
     serializer=ZFPY(mode=zfpy.mode_fixed_accuracy, tolerance=1e4))
case("numcodecs_zfpy_crc32c_f8", (50,), (20,), "<f8", smooth((50,), "<f8"), serializer=ZFPY(),
     compressor=[Crc32cCodec()])
case("numcodecs_zfpy_transpose_f4", (6, 10), (4, 6), "<f4", smooth((6, 10), "<f4"),
     filters=[TransposeCodec(order=(1, 0))], serializer=ZFPY(mode=zfpy.mode_fixed_rate, rate=20))
case("numcodecs_zfpy_sharded_f8", (10, 7), (4, 5), "<f8", smooth((10, 7), "<f8"), serializer=ZFPY(),
     shards=(8, 5))
# ---- zfpy, Zarr v2 ----
case("v2x_nc_zfpy_f8_accuracy", (30, 40), (16, 16), "<f8", smooth((30, 40), "<f8"), v2=True,
     compressor=numcodecs.ZFPY(mode=zfpy.mode_fixed_accuracy, tolerance=1e-4))
case("v2x_nc_zfpy_f4_rate", (500,), (128,), "<f4", smooth((500,), "<f4", 3), v2=True,
     compressor=numcodecs.ZFPY(mode=zfpy.mode_fixed_rate, rate=8))
case("v2x_nc_zfpy_i8_reversible_3d", (5, 6, 7), (3, 4, 4), "<i8", ints(210, "<i8", -77).reshape(5, 6, 7), v2=True,
     compressor=numcodecs.ZFPY())
case("v2x_nc_zfpy_i4_precision_4d", (4, 5, 6, 3), (2, 4, 4, 3), "<i4", ints(360, "<i4", 12345).reshape(4, 5, 6, 3),
     v2=True, compressor=numcodecs.ZFPY(mode=zfpy.mode_fixed_precision, precision=16))
case("v2x_nc_zfpy_f8_expert", (9, 9), (4, 4), "<f8", smooth((9, 9), "<f8", 1e-3), v2=True,
     compressor=numcodecs.ZFPY(mode=zfpy.mode_fixed_accuracy, tolerance=0))
case("v2x_nc_delta_zfpy_i4", (6, 50), (3, 20), "<i8", np.cumsum(ints(300, "<i8")).reshape(6, 50), v2=True,
     filters=[numcodecs.Delta(dtype="<i8", astype="<i4")], compressor=numcodecs.ZFPY())


def falcon_values(a, dtype):
    flat = np.asarray(a).reshape(-1)
    if dtype.kind == "f":
        return [float(v) for v in flat.astype("<f8").tolist()]
    return [int(v) for v in flat.tolist()]


def falcon_codecs(path, v2):
    """The pipeline's codec names, as Falcon reads the metadata (a .zarray translated)."""
    if not v2:
        return [c["name"] for c in json.load(open(os.path.join(path, "zarr.json")))["codecs"]]
    zarray = json.load(open(os.path.join(path, ".zarray")))
    names = ["transpose"] if zarray["order"] == "F" and len(zarray["shape"]) > 1 else []
    names.append("bytes")
    for c in list(zarray["filters"] or []) + ([zarray["compressor"]] if zarray["compressor"] else []):
        names.append(c["id"] if c["id"] in ("gzip", "zstd", "blosc") else "numcodecs." + c["id"])
    return names


def build(c):
    path = os.path.join(OUT, c["name"])
    if os.path.exists(path):
        shutil.rmtree(path)
    kwargs = dict(store=zarr.storage.LocalStore(path), shape=c["shape"], chunks=c["chunks"], dtype=c["dtype"],
                  fill_value=0)
    compressor = c["compressor"]
    if c["v2"]:
        kwargs.update(zarr_format=2, compressors=[compressor] if compressor is not None else None)
    else:
        kwargs["compressors"] = compressor if isinstance(compressor, list) else (
            [compressor] if compressor is not None else None)
        if c["serializer"] is not None:
            kwargs["serializer"] = c["serializer"]
        if c["shards"] is not None:
            kwargs["shards"] = c["shards"]
    if c["filters"] is not None:
        kwargs["filters"] = c["filters"]
    z = zarr.create_array(**kwargs)
    z[...] = c["values"]
    for fn in c["rewrite"] or []:
        fn(path)
    got = zarr.open_array(zarr.storage.LocalStore(path), mode="r")[...]
    meta = dict(name=c["name"], shape=list(c["shape"]), dtype=c["dtype"].str, codecs=falcon_codecs(path, c["v2"]))
    if got.size > HASH_OVER:
        meta["count"] = int(got.size)
        meta["sha256"] = hashlib.sha256(got.astype(c["dtype"].newbyteorder("<")).tobytes()).hexdigest()
    else:
        meta["values"] = falcon_values(got, c["dtype"])
    with open(os.path.join(OUT, c["name"] + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1)
    lossless = np.array_equal(got, np.asarray(c["values"]).astype(c["dtype"]))
    print(f"  {c['name']:38s} {c['dtype'].str:4s} {' -> '.join(meta['codecs'])}{'' if lossless else '  (lossy)'}")


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__}, numcodecs {numcodecs.__version__} -> {OUT}")
    for c in CASES:
        build(c)
    print(f"{len(CASES)} fixtures written")


if __name__ == "__main__":
    main()
