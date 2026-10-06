"""Generates Zarr v3 fixtures that use numcodecs' Zlib and LZ4 compressors, with zarr-python (P2 F3).

Dev-time tool only -- zarr-python is NOT a Falcon dependency (tools/fixtures/requirements.txt pins it).
Run from the repo root:  python tools/fixtures/gen_zarr_numcodecs_fixtures.py

zarr-python 3 writes numcodecs' codecs into v3 metadata as "numcodecs.<id>" (zarr.codecs.numcodecs):
numcodecs.zlib ({"level"}) and numcodecs.lz4 ({"acceleration"}), both bytes->bytes, and no configuration
at all for a default. Each case writes zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json
(the flattened values), in gen_zarr_fixtures.py's format, which ConformanceTest reads. A Blosc array with
the lz4hc compressor, which no other fixture uses, comes along.
"""
import json
import os
import shutil
import warnings

import numpy as np
import zarr
from zarr.codecs import BloscCodec, BloscShuffle, Crc32cCodec
from zarr.codecs.numcodecs import LZ4, Zlib

warnings.simplefilter("ignore")  # zarr-python warns that numcodecs.* codecs have no stable v3 spec yet

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")

CASES = [
    # name, shape, chunks, dtype, compressors, shards
    ("numcodecs_zlib_int32", (200,), (64,), "int32", [Zlib(level=5)], None),
    ("numcodecs_zlib_default_float64", (10, 12), (5, 6), "float64", [Zlib()], None),
    ("numcodecs_zlib_crc32c_uint16", (90,), (32,), "uint16", [Zlib(level=9), Crc32cCodec()], None),
    ("numcodecs_lz4_int16", (300,), (128,), "int16", [LZ4()], None),
    ("numcodecs_lz4_accel_uint8", (500,), (256,), "uint8", [LZ4(acceleration=8)], None),
    ("numcodecs_lz4_sharded_int32", (64,), (8,), "int32", [LZ4()], (32,)),
    ("blosc_lz4hc_float32", (40, 30), (20, 16), "float32",
     [BloscCodec(cname="lz4hc", clevel=7, shuffle=BloscShuffle.bitshuffle)], None),
]


def values_for(dtype, n):
    if dtype.kind == "f":
        return np.arange(n, dtype=dtype) + 0.5
    return (np.arange(n) % (np.iinfo(dtype).max // 2)).astype(dtype)


def jsonable(flat, dtype):
    if dtype.kind == "f":
        return [float(v) for v in flat.tolist()]
    return [int(v) for v in flat.tolist()]


def main():
    print(f"zarr-python {zarr.__version__} -> {OUT}")
    for name, shape, chunks, dtype_name, compressors, shards in CASES:
        path = os.path.join(OUT, name)
        if os.path.exists(path):
            shutil.rmtree(path)
        dtype = np.dtype(dtype_name)
        z = zarr.create_array(store=path, shape=shape, chunks=chunks, dtype=dtype, fill_value=0,
                              compressors=compressors, shards=shards)
        values = values_for(dtype, int(np.prod(shape))).reshape(shape)
        z[...] = values
        meta = dict(name=name, shape=list(shape), dtype=dtype_name, values=jsonable(values.reshape(-1), dtype),
                    attributes={})
        with open(os.path.join(OUT, name + ".expected.json"), "w") as f:
            json.dump(meta, f, indent=1)
        codecs = json.load(open(os.path.join(path, "zarr.json")))["codecs"]
        print(f"  {name:32s} {[c['name'] for c in codecs]}")
    print(f"{len(CASES)} fixtures written")


if __name__ == "__main__":
    main()
