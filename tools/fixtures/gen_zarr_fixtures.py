"""Generates Zarr v3 conformance fixtures with zarr-python (the reference oracle).

Dev-time tool only -- like h5py for the hdf5 module, zarr-python is NOT a Falcon
dependency. Install with:  pip install zarr numcodecs
Run from the repo root:    python tools/fixtures/gen_zarr_fixtures.py

Each case writes a Zarr v3 store under zarr/src/test/resources/fixtures/<name>/
plus <name>.expected.json holding the flattened expected values, so the Java
tests stay hermetic (no Python at build time).

zarr-python 3.x splits the codec chain across arguments:
  filters      -> array->array codecs (transpose)
  serializer   -> the array->bytes codec (bytes)
  compressors  -> bytes->bytes codecs (gzip, crc32c, zstd, blosc)
  shards       -> outer shard shape; `chunks` is then the sub-chunk shape
"""
import json, os, shutil
import numpy as np
import zarr
from zarr.codecs import BytesCodec, GzipCodec, Crc32cCodec, TransposeCodec, ZstdCodec

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")

CASES = []


def case(name, shape, chunks, dtype, *, filters=None, serializer=BytesCodec(),
         compressors=None, shards=None, fill_value=0, attrs=None, partial=False):
    CASES.append(dict(name=name, shape=shape, chunks=chunks, dtype=dtype,
                      filters=filters, serializer=serializer, compressors=compressors,
                      shards=shards, fill_value=fill_value, attrs=attrs, partial=partial))


# --- codecs Falcon implements today ------------------------------------------------
case("plain_int32", (10,), (4,), "int32")
case("gzip_float64", (4, 6), (2, 3), "float64", compressors=[GzipCodec(level=5)])
case("crc32c_int16", (7,), (3,), "int16", compressors=[Crc32cCodec()])
case("gzip_crc32c_uint8", (5, 5), (2, 2), "uint8",
     compressors=[GzipCodec(level=1), Crc32cCodec()])
case("bigendian_int32", (6,), (4,), "int32", serializer=BytesCodec(endian="big"))
case("transpose_int32", (3, 4), (3, 4), "int32", filters=[TransposeCodec(order=(1, 0))])

# --- data types --------------------------------------------------------------------
case("float32_nan_fill", (5,), (2,), "float32", fill_value=float("nan"))
case("bool_array", (6,), (4,), "bool", fill_value=False)
case("int64_array", (5,), (2,), "int64")
case("uint64_array", (4,), (2,), "uint64")
case("float16_array", (5,), (2,), "float16")
case("uint16_2d", (3, 5), (2, 2), "uint16")

# --- sharding ----------------------------------------------------------------------
case("sharded_int32", (16,), (4,), "int32", shards=(8,))
case("sharded_2d_gzip", (4, 4), (2, 2), "int32", shards=(4, 4),
     compressors=[GzipCodec(level=3)])

# --- fill values and attributes ----------------------------------------------------
case("partial_fill", (10,), (4,), "int32", fill_value=7, partial=True)
case("attrs_int32", (4,), (2,), "int32", attrs={"units": "K", "n": 3})

# --- zstd, which is what zarr-python compresses with by default --------------------
case("zstd_int32", (100,), (16,), "int32", compressors=[ZstdCodec(level=3)])
case("zstd_float64_2d", (12, 10), (5, 4), "float64", compressors=[ZstdCodec(level=9)])
case("zstd_crc32c_int16", (40,), (7,), "int16", compressors=[ZstdCodec(level=1), Crc32cCodec()])
case("zstd_sharded", (32,), (4,), "int32", shards=(16,), compressors=[ZstdCodec(level=5)])
# zarr-python's out-of-the-box defaults, whatever they may be
case("zarr_python_defaults", (64,), (16,), "int32", serializer="auto", compressors="auto")

# --- blosc, the other compressor Zarr stores commonly use --------------------------
from zarr.codecs import BloscCodec, BloscShuffle  # noqa: E402

case("blosc_lz4_int32", (200,), (32,), "int32",
     compressors=[BloscCodec(cname="lz4", clevel=5, shuffle=BloscShuffle.shuffle)])
case("blosc_zstd_float64", (10, 12), (5, 6), "float64",
     compressors=[BloscCodec(cname="zstd", clevel=3, shuffle=BloscShuffle.shuffle)])
case("blosc_noshuffle_int16", (150,), (64,), "int16",
     compressors=[BloscCodec(cname="lz4", clevel=1, shuffle=BloscShuffle.noshuffle)])
case("blosc_zlib_uint8", (300,), (128,), "uint8",
     compressors=[BloscCodec(cname="zlib", clevel=6, shuffle=BloscShuffle.noshuffle)])
case("blosc_blosclz_int32", (256,), (64,), "int32",
     compressors=[BloscCodec(cname="blosclz", clevel=5, shuffle=BloscShuffle.shuffle)])
case("blosc_bitshuffle_int32", (256,), (128,), "int32",
     compressors=[BloscCodec(cname="lz4", clevel=5, shuffle=BloscShuffle.bitshuffle)])
case("blosc_bitshuffle_float64", (100,), (64,), "float64",
     compressors=[BloscCodec(cname="zstd", clevel=5, shuffle=BloscShuffle.bitshuffle)])


def values_for(dtype, n):
    if dtype.kind == "b":
        return np.arange(n) % 2 == 0
    if dtype.kind == "f":
        return np.arange(n, dtype=dtype) + 0.5
    return np.arange(n, dtype=dtype)


def jsonable(flat, dtype):
    if dtype.kind == "f":
        out = []
        for v in flat.tolist():
            if np.isnan(v):
                out.append("NaN")
            elif v == np.inf:
                out.append("Infinity")
            elif v == -np.inf:
                out.append("-Infinity")
            else:
                out.append(v)
        return out
    if dtype.kind == "b":
        return [bool(v) for v in flat.tolist()]
    return [int(v) for v in flat.tolist()]


def build(c):
    path = os.path.join(OUT, c["name"])
    if os.path.exists(path):
        shutil.rmtree(path)
    dtype = np.dtype(c["dtype"])
    z = zarr.create_array(
        store=path, shape=c["shape"], chunks=c["chunks"], dtype=dtype,
        fill_value=c["fill_value"], filters=c["filters"], serializer=c["serializer"],
        compressors=c["compressors"], shards=c["shards"], attributes=c["attrs"] or {})

    n = int(np.prod(c["shape"]))
    values = values_for(dtype, n).reshape(c["shape"]).astype(dtype)

    if c["partial"]:
        # leave the tail unwritten so it reads back as the fill value
        expected = np.full(c["shape"], c["fill_value"], dtype=dtype)
        z[0:4] = values.reshape(-1)[0:4]
        expected.reshape(-1)[0:4] = values.reshape(-1)[0:4]
    else:
        z[...] = values
        expected = values

    meta = dict(name=c["name"], shape=list(c["shape"]), dtype=c["dtype"],
                values=jsonable(expected.reshape(-1), dtype),
                attributes=c["attrs"] or {})
    with open(os.path.join(OUT, c["name"] + ".expected.json"), "w") as f:
        json.dump(meta, f, indent=1)
    print(f"  {c['name']:22s} {c['dtype']:8s} {tuple(c['shape'])}")


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} -> {OUT}")
    for c in CASES:
        build(c)
    print(f"{len(CASES)} fixtures written")


if __name__ == "__main__":
    main()
