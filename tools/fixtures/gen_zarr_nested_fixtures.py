"""Generates the nested-sharding fixtures added with P2 (F11) with zarr-python.

Dev-time tool only, like gen_zarr_data_fixtures.py (whose sidecar format these share): zarr-python is not a
Falcon dependency. Run from the repo root:  python tools/fixtures/gen_zarr_nested_fixtures.py

Each case writes a store under zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json, whose
"values" are the elements in C order as zarr-python reads them back. Only these cases are (re)written.

  nested_2d            int32 16 x 12: one shard of 8 x 6 sub-chunks, each a shard of 4 x 3 (bytes only)
  nested_partial       int16 16 x 16, fill 7: only a 3 x 5 region written, so most inner shards and
                       inner sub-chunks are absent
  nested_compressed    float64 12 x 8, two shards: inner sub-chunks zstd-compressed, and a crc32c after the
                       inner shard (so an inner shard is read whole)
  nested_three         int8 of 32: three levels of shards (16, 8, then 4 elements)
  nested_string        strings 6 x 4: a shard of 3 x 2 sub-chunks, each a shard of vlen-utf8 1 x 2
"""
import json, os, shutil
import warnings
import numpy as np
import zarr
from zarr.codecs import BytesCodec, Crc32cCodec, ShardingCodec, VLenUTF8Codec, ZstdCodec

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")
STRING_POOL = ["alpha", "", "gamma-δ", "中文", "emoji-\U0001f600", "x"]


def fresh(name):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        shutil.rmtree(path)
    return path


def sidecar(name, z, dtype):
    values = z[...].reshape(-1)
    meta = dict(name=name, shape=list(z.shape), dtype=dtype, attributes={},
                values=[str(v) if dtype == "string" else v.item() for v in values])
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1, ensure_ascii=False)
    print(f"  {name:20s} {dtype:8s} {tuple(z.shape)}")


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} -> {OUT}")
    warnings.simplefilter("ignore")

    inner = ShardingCodec(chunk_shape=(4, 3), codecs=[BytesCodec()])
    z = zarr.create_array(store=fresh("nested_2d"), shape=(16, 12), chunks=(16, 12), dtype="int32",
                          fill_value=-1, serializer=ShardingCodec(chunk_shape=(8, 6), codecs=[inner]),
                          compressors=None)
    z[...] = (np.arange(16 * 12, dtype="int32") * 3 - 100).reshape(16, 12)
    sidecar("nested_2d", z, "int32")

    inner = ShardingCodec(chunk_shape=(4, 4), codecs=[BytesCodec()])
    z = zarr.create_array(store=fresh("nested_partial"), shape=(16, 16), chunks=(16, 16), dtype="int16",
                          fill_value=7, serializer=ShardingCodec(chunk_shape=(8, 8), codecs=[inner]),
                          compressors=None)
    z[5:8, 2:7] = np.arange(15, dtype="int16").reshape(3, 5) + 1000
    sidecar("nested_partial", z, "int16")

    inner = ShardingCodec(chunk_shape=(2, 2), codecs=[BytesCodec(), ZstdCodec(level=3)])
    z = zarr.create_array(store=fresh("nested_compressed"), shape=(12, 8), chunks=(6, 8), dtype="float64",
                          fill_value=0.0,
                          serializer=ShardingCodec(chunk_shape=(6, 4), codecs=[inner, Crc32cCodec()]),
                          compressors=None)
    z[...] = (np.arange(96, dtype="float64") * 0.25).reshape(12, 8)
    sidecar("nested_compressed", z, "float64")

    level3 = ShardingCodec(chunk_shape=(4,), codecs=[BytesCodec()])
    level2 = ShardingCodec(chunk_shape=(8,), codecs=[level3])
    z = zarr.create_array(store=fresh("nested_three"), shape=(32,), chunks=(32,), dtype="int8",
                          fill_value=0, serializer=ShardingCodec(chunk_shape=(16,), codecs=[level2]),
                          compressors=None)
    z[3:29] = np.arange(26, dtype="int8") - 13
    sidecar("nested_three", z, "int8")

    inner = ShardingCodec(chunk_shape=(1, 2), codecs=[VLenUTF8Codec()])
    z = zarr.create_array(store=fresh("nested_string"), shape=(6, 4), chunks=(6, 4), dtype=str,
                          fill_value="", serializer=ShardingCodec(chunk_shape=(3, 2), codecs=[inner]),
                          compressors=None)
    z[...] = np.array([STRING_POOL[i % len(STRING_POOL)] + str(i) for i in range(24)], dtype=object).reshape(6, 4)
    z[1, 1] = ""
    sidecar("nested_string", z, "string")


if __name__ == "__main__":
    main()
