"""Generates the Zarr data-path fixtures added with P1 (T3) with zarr-python, numcodecs, and zstandard.

Dev-time tool only, like gen_zarr_fixtures.py (whose sidecar format these share): none of these is a
Falcon dependency. Run from the repo root:  python tools/fixtures/gen_zarr_data_fixtures.py

Each case writes a store under zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json. Only
these cases are (re)written; the fixtures of gen_zarr_fixtures.py are left alone.

  sharded_string           zarr-python's sharded string array: vlen-utf8 inside the shard (I2)
  sharded_string_partial   ... with sub-chunks never written (they read as the fill value)
  transposed_string        transpose before vlen-utf8, as zarr-python orders them (I4)
  sharded_index_start      a shard index at the start of the shard
  zstd_checksum            zstd frames carrying a content checksum
  zstd_multiframe          a chunk of two zstd frames back to back (libzstd decodes the concatenation)
  blosc_r2400              c-blosc with a 300-byte type size, which it writes as 1 (Z5)
  zstd_large_chunk         one 140 MB uint8 chunk, values i % 251 (more than libzstd's 128 KiB blocks;
                           the sidecar gives the formula rather than the values)
"""
import json, os, shutil
import numpy as np
import zarr
import zstandard
from numcodecs import blosc as cblosc
from zarr.codecs import BytesCodec, TransposeCodec, ZstdCodec

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")
STRING_POOL = ["alpha", "", "gamma-δ", "中文", "emoji-\U0001f600", "x"]


def fresh(name):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        shutil.rmtree(path)
    return path


def sidecar(name, shape, dtype, values=None, **extra):
    meta = dict(name=name, shape=list(shape), dtype=dtype, attributes={})
    if values is not None:
        meta["values"] = values
    meta.update(extra)
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1, ensure_ascii=False)
    print(f"  {name:24s} {dtype:8s} {tuple(shape)}")


def strings(n):
    return np.array([STRING_POOL[i % len(STRING_POOL)] + str(i) for i in range(n)], dtype=object)


def write_v3(path, meta, chunks):
    """A hand-made v3 array: its zarr.json and chunk files (key -> bytes)."""
    os.makedirs(path)
    with open(os.path.join(path, "zarr.json"), "w", newline="\n") as f:
        json.dump(meta, f)
    for key, data in chunks.items():
        p = os.path.join(path, *key.split("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)


def v3_meta(shape, chunk, dtype, codecs, fill=0):
    return {"zarr_format": 3, "node_type": "array", "shape": list(shape), "data_type": dtype,
            "chunk_grid": {"name": "regular", "configuration": {"chunk_shape": list(chunk)}},
            "chunk_key_encoding": {"name": "default", "configuration": {"separator": "/"}},
            "fill_value": fill, "codecs": codecs, "attributes": {}}


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__}, zstandard {zstandard.__version__} -> {OUT}")

    # Sharded strings, written the way zarr-python builds them by default.
    z = zarr.create_array(store=fresh("sharded_string"), shape=(6, 8), chunks=(2, 4), shards=(6, 8),
                          dtype=str, fill_value="", compressors=[ZstdCodec(level=3)])
    values = strings(48).reshape(6, 8)
    z[...] = values
    sidecar("sharded_string", (6, 8), "string", [str(v) for v in values.reshape(-1)])

    z = zarr.create_array(store=fresh("sharded_string_partial"), shape=(8,), chunks=(2,), shards=(8,),
                          dtype=str, fill_value="?")
    z[2:5] = ["b", "c", "d"]
    sidecar("sharded_string_partial", (8,), "string", ["?", "?", "b", "c", "d", "?", "?", "?"])

    z = zarr.create_array(store=fresh("transposed_string"), shape=(3, 4), chunks=(2, 4), dtype=str,
                          fill_value="", filters=[TransposeCodec(order=(1, 0))])
    values = strings(12).reshape(3, 4)
    z[...] = values
    sidecar("transposed_string", (3, 4), "string", [str(v) for v in values.reshape(-1)])

    from zarr.codecs import ShardingCodec, ShardingCodecIndexLocation
    z = zarr.create_array(store=fresh("sharded_index_start"), shape=(16,), chunks=(16,), dtype="int32",
                          fill_value=0, serializer=ShardingCodec(chunk_shape=(4,), codecs=[BytesCodec()],
                                                                 index_location=ShardingCodecIndexLocation.start),
                          compressors=None)
    z[0:6] = np.arange(1, 7, dtype="int32")
    sidecar("sharded_index_start", (16,), "int32", list(range(1, 7)) + [0] * 10)

    z = zarr.create_array(store=fresh("zstd_checksum"), shape=(50,), chunks=(20,), dtype="int32",
                          compressors=[ZstdCodec(level=3, checksum=True)])
    z[...] = np.arange(50, dtype="int32") * 3
    sidecar("zstd_checksum", (50,), "int32", [i * 3 for i in range(50)])

    data = (np.arange(64, dtype="<i4") * 7).tobytes()
    two_frames = zstandard.ZstdCompressor(level=3).compress(data[:100]) \
        + zstandard.ZstdCompressor(level=3).compress(data[100:])
    write_v3(fresh("zstd_multiframe"),
             v3_meta((64,), (64,), "int32", [{"name": "bytes", "configuration": {"endian": "little"}},
                                             {"name": "zstd", "configuration": {"level": 3, "checksum": False}}]),
             {"c/0": two_frames})
    sidecar("zstd_multiframe", (64,), "int32", [i * 7 for i in range(64)])

    element = 300
    raw = bytes((i % element * 3 + i // element) & 0xff for i in range(4 * element))
    write_v3(fresh("blosc_r2400"),
             v3_meta((4,), (4,), "r2400",
                     [{"name": "bytes"},
                      {"name": "blosc", "configuration": {"cname": "zstd", "clevel": 5, "shuffle": "shuffle",
                                                          "typesize": element, "blocksize": 0}}],
                     fill=[0] * element),
             {"c/0": cblosc.compress(raw, b"zstd", 5, cblosc.SHUFFLE, typesize=element)})
    sidecar("blosc_r2400", (4,), "r2400", hex=raw.hex())

    n = 140_000_000
    z = zarr.create_array(store=fresh("zstd_large_chunk"), shape=(n,), chunks=(n,), dtype="uint8",
                          compressors=[ZstdCodec(level=3)])
    z[...] = (np.arange(n, dtype=np.int64) % 251).astype("uint8")
    sidecar("zstd_large_chunk", (n,), "uint8", formula="i % 251")


if __name__ == "__main__":
    main()
