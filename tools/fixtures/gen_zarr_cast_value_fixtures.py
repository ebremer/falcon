"""Generates Zarr v3 fixtures that use the cast_value codec, with zarr-python 3.4 and cast-value-rs.

Dev-time tool only -- zarr-python and cast-value-rs are NOT Falcon dependencies (tools/fixtures/requirements.txt
pins them). Run from the repo root:  python tools/fixtures/gen_zarr_cast_value_fixtures.py

zarr-python writes cast_value (zarr.codecs.CastValue, the zarr-extensions codec) as an array->array filter,
inside the shard when sharding; the arrays whose codecs it does not build (cast_value before sharding_indexed,
or in both places, or after a transpose) are written by hand and then filled through zarr-python. Each case
writes zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json: the shape, the data type, every
element as zarr-python reads it back -- an integer in decimal, a float as float.hex() ("nan", "inf", "-inf"
for the non-finite) -- which CastValueFixtureTest checks Falcon reads exactly, and the write that made it
("written": the region's origin and shape and the values written, in the same form), which Falcon repeats to
check that it stores the chunks zarr-python stored.

  cast_f64_u8              float64 -> uint8, clamp, edge chunks
  cast_f64_i16_away_blosc  float64 -> int16, nearest-away, clamp, Blosc (lz4, shuffle; typesize 2) after
  cast_f32_f16_tz_zstd     float32 -> float16, towards-zero (overflow stops at 65504), clamp, zstd after
  cast_i32_u8_wrap_gzip    int32 -> uint8, wrap, gzip after
  cast_numpy_compat        float64 -> uint8, towards-zero, wrap, NaN and infinities mapped to 0 (the
                           specification's NumPy example)
  cast_nan_fill_map        float32 -> int8, NaN fill mapped to -128 and back, partly written
  cast_sharded_inner       float64 -> int8 inside the shards, fill 3.7 (cast to 4), partly written
  cast_before_sharding     float64 -> int16 (towards-negative) before sharding_indexed, fill 2.5 (cast to 2:
                           a shard's absent sub-chunks read 2.0), partly written
  cast_twice               float64 -> float32 before the shard, -> int16 inside it, Blosc
  cast_u64_f32_up_big      uint64 -> float32, towards-positive, big-endian
  cast_i64_f16_overflow    int64 -> float16 (no out_of_range: an integer never overflows; 70000 becomes
                           infinity, which reads back as int64's maximum)
  cast_transpose           transpose, then float32 -> uint16 with clamp, big-endian, edge chunks
  cast_f16_i32_tz          float16 -> int32, towards-zero (an infinity is in int32's float16 range)
"""
import json
import math
import os
import shutil
import warnings

import numpy as np
import zarr
from zarr.codecs import BloscCodec, BloscShuffle, BytesCodec, CastValue, GzipCodec, ZstdCodec

warnings.simplefilter("ignore")  # zarr-python warns that cast_value has no stable v3 spec yet

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")
SHAPE = (13, 7)
BYTES_LE = {"name": "bytes", "configuration": {"endian": "little"}}
INDEX = [BYTES_LE, {"name": "crc32c"}]


def fresh(name):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        shutil.rmtree(path)
    return path


def jsonable(flat):
    if flat.dtype.kind == "f":
        out = []
        for v in flat.tolist():
            if math.isnan(v):
                out.append("nan")
            elif math.isinf(v):
                out.append("inf" if v > 0 else "-inf")
            else:
                out.append(float(v).hex())
        return out
    return [str(int(v)) for v in flat.tolist()]


WRITTEN = {}


def write(z, values, origin=(0, 0)):
    """z[origin:origin + values.shape] = values, remembered for the sidecar."""
    values = np.asarray(values).astype(z.dtype)
    z[origin[0]:origin[0] + values.shape[0], origin[1]:origin[1] + values.shape[1]] = values
    WRITTEN["last"] = dict(origin=list(origin), shape=list(values.shape), values=jsonable(values.reshape(-1)))


def sidecar(name, z):
    got = z[...]
    meta = dict(name=name, shape=list(z.shape), dtype=str(z.dtype), values=jsonable(got.reshape(-1)), attributes={},
                written=WRITTEN.pop("last"))
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1)
    codecs = json.load(open(os.path.join(OUT, name, "zarr.json"), encoding="utf-8"))["codecs"]
    print(f"  {name:26s} {z.dtype!s:8s} {[c['name'] for c in codecs]}")


def by_hand(name, dtype, fill, codecs, chunks=(6, 4)):
    """An array whose codecs zarr-python does not build, opened through zarr-python to be written."""
    path = fresh(name)
    os.makedirs(path)
    meta = {"zarr_format": 3, "node_type": "array", "shape": list(SHAPE), "data_type": dtype,
            "chunk_grid": {"name": "regular", "configuration": {"chunk_shape": list(chunks)}},
            "chunk_key_encoding": {"name": "default", "configuration": {"separator": "/"}},
            "fill_value": fill, "codecs": codecs, "attributes": {}}
    with open(os.path.join(path, "zarr.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=2)
    return zarr.open_array(path, mode="r+")


def cast(**configuration):
    return {"name": "cast_value", "configuration": configuration}


def shard(chunk_shape, codecs):
    return {"name": "sharding_indexed", "configuration": {"chunk_shape": list(chunk_shape), "codecs": codecs,
                                                          "index_codecs": INDEX, "index_location": "end"}}


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} -> {OUT}")
    n = SHAPE[0] * SHAPE[1]
    i = np.arange(n)

    z = zarr.create_array(store=fresh("cast_f64_u8"), shape=SHAPE, chunks=(6, 4), dtype="float64", fill_value=0,
                          filters=[CastValue(data_type="uint8", out_of_range="clamp")], compressors=None)
    write(z, (i * 3.25 - 20).reshape(SHAPE))  # -20 .. 272.5: clamped at both ends, ties at .5 and .25s
    sidecar("cast_f64_u8", z)

    z = zarr.create_array(store=fresh("cast_f64_i16_away_blosc"), shape=SHAPE, chunks=(6, 4), dtype="float64",
                          fill_value=0, filters=[CastValue(data_type="int16", rounding="nearest-away",
                                                           out_of_range="clamp")],
                          compressors=[BloscCodec(cname="lz4", clevel=5, shuffle=BloscShuffle.shuffle)])
    write(z, ((i - 45) * 899.5).reshape(SHAPE))  # ties, and past +-32767 at the ends
    sidecar("cast_f64_i16_away_blosc", z)

    v = np.linspace(-3, 3, n).astype("float32")
    v[:12] = [1e5, -1e5, np.nan, np.inf, -np.inf, 1e-8, -1e-8, 65519.0, 65520.0, 0.1, -0.0, 3.0e-5]
    z = zarr.create_array(store=fresh("cast_f32_f16_tz_zstd"), shape=SHAPE, chunks=(6, 4), dtype="float32",
                          fill_value=0, filters=[CastValue(data_type="float16", rounding="towards-zero",
                                                           out_of_range="clamp")],
                          compressors=[ZstdCodec(level=3)])
    write(z, v.reshape(SHAPE))
    sidecar("cast_f32_f16_tz_zstd", z)

    z = zarr.create_array(store=fresh("cast_i32_u8_wrap_gzip"), shape=SHAPE, chunks=(6, 4), dtype="int32",
                          fill_value=0, filters=[CastValue(data_type="uint8", out_of_range="wrap")],
                          compressors=[GzipCodec(level=5)])
    write(z, ((i - 45) * 37).reshape(SHAPE).astype("int32"))
    sidecar("cast_i32_u8_wrap_gzip", z)

    v = (i * 7.3 - 300).astype("float64")
    v[[3, 17, 40, 77]] = [np.nan, np.inf, -np.inf, np.nan]
    z = zarr.create_array(store=fresh("cast_numpy_compat"), shape=SHAPE, chunks=(6, 4), dtype="float64",
                          fill_value=0, filters=[CastValue(data_type="uint8", rounding="towards-zero",
                                                           out_of_range="wrap",
                                                           scalar_map={"encode": [("NaN", 0), ("Infinity", 0),
                                                                                  ("-Infinity", 0)]})],
                          compressors=None)
    write(z, v.reshape(SHAPE))
    sidecar("cast_numpy_compat", z)

    z = zarr.create_array(store=fresh("cast_nan_fill_map"), shape=SHAPE, chunks=(4, 3), dtype="float32",
                          fill_value=float("nan"),
                          filters=[CastValue(data_type="int8", out_of_range="clamp",
                                             scalar_map={"encode": [("NaN", -128)], "decode": [(-128, "NaN")]})],
                          compressors=None)
    write(z, (np.arange(28).reshape(7, 4) * 9.6 - 130).astype("float32"), (2, 1))  # -130 clamps to -128: NaN
    sidecar("cast_nan_fill_map", z)

    z = zarr.create_array(store=fresh("cast_sharded_inner"), shape=SHAPE, chunks=(3, 2), shards=(6, 4),
                          dtype="float64", fill_value=3.7,
                          filters=[CastValue(data_type="int8", out_of_range="clamp")], compressors=None)
    write(z, (np.arange(28).reshape(7, 4) * 10.5 - 140), (2, 1))
    sidecar("cast_sharded_inner", z)

    z = by_hand("cast_before_sharding", "float64", 2.5,
                [cast(data_type="int16", rounding="towards-negative", out_of_range="clamp"),
                 shard((3, 2), [BYTES_LE])])
    write(z, (np.arange(28).reshape(7, 4) * 1234.56 - 17000), (2, 1))
    sidecar("cast_before_sharding", z)

    z = by_hand("cast_twice", "float64", 1.25,
                [cast(data_type="float32"),
                 shard((3, 2), [cast(data_type="int16", out_of_range="clamp"), BYTES_LE,
                                {"name": "blosc", "configuration": {"cname": "lz4", "clevel": 5, "shuffle": "shuffle",
                                                                    "typesize": 2, "blocksize": 0}}])])
    write(z, (np.arange(66).reshape(11, 6) * 1001.7 - 33000), (1, 0))
    sidecar("cast_twice", z)

    z = zarr.create_array(store=fresh("cast_u64_f32_up_big"), shape=SHAPE, chunks=(6, 4), dtype="uint64",
                          fill_value=0, filters=[CastValue(data_type="float32", rounding="towards-positive")],
                          serializer=BytesCodec(endian="big"), compressors=None)
    write(z, ((i.astype("uint64") * np.uint64(0x9E3779B97F4A7C15)) >> np.uint64(i % 64)).reshape(SHAPE))
    sidecar("cast_u64_f32_up_big", z)

    v = ((i - 45) * 1601).astype("int64")
    v[[0, 1, 2]] = [70000, -70000, 65519]
    z = zarr.create_array(store=fresh("cast_i64_f16_overflow"), shape=SHAPE, chunks=(6, 4), dtype="int64",
                          fill_value=0, filters=[CastValue(data_type="float16")], compressors=None)
    write(z, v.reshape(SHAPE))
    sidecar("cast_i64_f16_overflow", z)

    z = by_hand("cast_transpose", "float32", 0,
                [{"name": "transpose", "configuration": {"order": [1, 0]}},
                 cast(data_type="uint16", out_of_range="clamp"), {"name": "bytes", "configuration": {"endian": "big"}}])
    write(z, (i * 911.3 - 2000).reshape(SHAPE).astype("float32"))
    sidecar("cast_transpose", z)

    v = ((i - 45) * 21.37).astype("float16")
    v[[5, 6]] = [np.inf, -np.inf]
    z = zarr.create_array(store=fresh("cast_f16_i32_tz"), shape=SHAPE, chunks=(6, 4), dtype="float16",
                          fill_value=0, filters=[CastValue(data_type="int32", rounding="towards-zero")],
                          compressors=None)
    write(z, v.reshape(SHAPE))
    sidecar("cast_f16_i32_tz", z)


if __name__ == "__main__":
    main()
