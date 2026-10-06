"""Generates Zarr v3 fixtures with the reshape codec (zarr-extensions codecs/reshape).

Dev-time tool only -- zarr-python and numcodecs are NOT Falcon dependencies. Run from the repo root:
    python tools/fixtures/gen_zarr_reshape_fixtures.py

zarr-python 3.4 does not implement reshape (it registers no codec of that name, and its sharding codec checks a
shard's shape against the array's chunk grid, not the reshaped one), so the oracle is composed. The codec
keeps the elements' C order, so a chunk reshaped is NumPy's reshape of it; each stored chunk here is what
zarr-python stores for an array of the reshaped chunk's shape, one chunk of it, with the codecs that follow
reshape (bytes or vlen-utf8, a shard, compressors). Array->array codecs around reshape (transpose) are
applied with NumPy too, in pipeline order. The shape a chunk reshapes to is worked out by resolve() from the
spec's rules; Falcon's ReshapeCodec is held to the same rules by its unit tests, and both are checked here by
NumPy, whose reshape needs the element counts to agree.

Each case writes zarr/src/test/resources/fixtures/<name>/ (zarr.json and the chunks) and <name>.expected.json
(the shape, dtype, top-level codec names, and every element in C order), in gen_zarr_bz2_zfpy_fixtures.py's
form. Chunks wholly of the fill value are not stored, as zarr-python leaves them out.
"""
import itertools
import json
import os
import shutil
import tempfile
import warnings

import numpy as np
import zarr
import zarr.storage
from zarr.codecs import BytesCodec, Crc32cCodec, VLenUTF8Codec
from zarr.codecs.numcodecs import BZ2, Zlib

warnings.simplefilter("ignore")  # zarr-python warns that numcodecs.* codecs have no stable v3 spec yet

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")

CASES = []


def case(name, shape, dtype, values, steps, *, chunks=None, lengths=None, compressors=None, shards=None,
         fill=0):
    """`steps` are the array->array codecs in order: ("reshape", shape-spec) or ("transpose", order). The grid is
    regular (`chunks`) or rectilinear (`lengths`, each dimension's chunk lengths)."""
    CASES.append(dict(name=name, shape=shape, dtype=dtype, values=values, steps=steps, chunks=chunks,
                      lengths=lengths, compressors=compressors, shards=shards, fill=fill))


def resolve(spec, a_shape):
    """The reshape codec's output shape for an input of `a_shape`, every rule of the spec checked."""
    total = int(np.prod(a_shape, dtype=np.int64))
    out, dims_of, auto, last = [], [], None, -1
    for i, s in enumerate(spec):
        if isinstance(s, list):
            for d in s:
                assert 0 <= d < len(a_shape) and d > last, (spec, a_shape)
                last = d
            out.append(int(np.prod([a_shape[d] for d in s], dtype=np.int64)))
            dims_of.append(s)
        else:
            assert s == -1 or s > 0, spec
            if s == -1:
                assert auto is None, spec
                auto = i
            out.append(s)
            dims_of.append(None)
    if auto is not None:
        known = int(np.prod([v for i, v in enumerate(out) if i != auto], dtype=np.int64))
        assert total % known == 0, (spec, a_shape)
        out[auto] = total // known
    assert int(np.prod(out, dtype=np.int64)) == total, (spec, a_shape)
    for i, d in enumerate(dims_of):
        if d:
            assert np.prod(out[:i], dtype=np.int64) == np.prod(a_shape[:d[0]], dtype=np.int64), (spec, a_shape)
            assert np.prod(out[i + 1:], dtype=np.int64) == np.prod(a_shape[d[-1] + 1:], dtype=np.int64), (spec, a_shape)
    return tuple(out)


def ints(n, dtype, step=3):
    return (np.arange(n) * step + 1).astype(dtype)


# The spec's example, scaled down: chunks of [10, 5, 8, 3] as [[0, 1], [2], 3], that is [50, 8, 3].
case("reshape_spec_example_uint16", (20, 5, 8, 3), "<u2", ints(2400, "<u2", 7).reshape(20, 5, 8, 3),
     [("reshape", [[0, 1], [2], 3])], chunks=(10, 5, 8, 3), compressors=[BZ2(level=2)])
case("reshape_merge_int32", (10, 12), "<i4", ints(120, "<i4").reshape(10, 12), [("reshape", [[0, 1]])],
     chunks=(4, 6), fill=-1)
case("reshape_split_float64", (9, 20), "<f8", (np.arange(180) * 0.25 - 7).reshape(9, 20),
     [("reshape", [[0], 2, -1])], chunks=(3, 10), compressors=[BZ2(level=9)])
case("reshape_sizes_uint8", (8, 6), "|u1", ints(48, "|u1").reshape(8, 6), [("reshape", [3, 8])], chunks=(4, 6),
     compressors=[Zlib(level=6), Crc32cCodec()])
case("reshape_empty_dims_int64", (6, 4), "<i8", ints(24, "<i8", -5).reshape(6, 4),
     [("reshape", [[], [0], [], [1], 1])], chunks=(3, 4))
case("reshape_sharded_int16", (8, 24), "<i2", ints(192, "<i2").reshape(8, 24), [("reshape", [[0, 1]])],
     chunks=(8, 12), shards=(16,), compressors=[BZ2(level=1)])
case("reshape_sharded_3d_to_2d_float32", (8, 6, 5), "<f4", (np.arange(240) / 8).astype("<f4").reshape(8, 6, 5),
     [("reshape", [[0, 1], [2]])], chunks=(4, 6, 5), shards=(8, 5), compressors=[Crc32cCodec()])
case("reshape_after_transpose_int32", (8, 12), "<i4", ints(96, "<i4").reshape(8, 12),
     [("transpose", (1, 0)), ("reshape", [[0, 1]])], chunks=(4, 6))
case("reshape_before_transpose_int32", (8, 12), "<i4", ints(96, "<i4", 5).reshape(8, 12),
     [("reshape", [[0], 2, -1]), ("transpose", (2, 0, 1))], chunks=(4, 6), compressors=[BZ2(level=3)])
case("reshape_rectilinear_int32", (10, 8), "<i4", ints(80, "<i4", 11).reshape(10, 8), [("reshape", [[0, 1]])],
     lengths=([2, 5, 3], [4, 4]), compressors=[BZ2(level=1)])
case("reshape_rectilinear_sharded_int32", (12, 6), "<i4", ints(72, "<i4", 2).reshape(12, 6),
     [("reshape", [[0, 1]])], lengths=([4, 8], [6]), shards=(12,))
case("reshape_strings", (5, 4), "str",
     np.array([f"s{i}" + "δ" * (i % 3) for i in range(20)], dtype=object).reshape(5, 4), [("reshape", [-1])],
     chunks=(2, 4), fill="")


def chunk_boxes(c):
    """(key, slices, declared chunk shape) for every chunk of the grid."""
    if c["chunks"] is not None:
        per_dim = [[(k * n, n) for k in range(-(-s // n))] for s, n in zip(c["shape"], c["chunks"])]
    else:
        per_dim = []
        for lengths in c["lengths"]:
            starts = np.concatenate([[0], np.cumsum(lengths)[:-1]])
            per_dim.append(list(zip(starts.tolist(), lengths)))
    for combo in itertools.product(*[list(enumerate(d)) for d in per_dim]):
        key = "c/" + "/".join(str(k) for k, _ in combo)
        yield key, tuple(slice(o, o + n) for _, (o, n) in combo), tuple(n for _, (_, n) in combo)


def chunk_bytes(c, chunk, tmp):
    """What zarr-python stores for `chunk` (already through the array->array steps) with the codecs after them."""
    path = os.path.join(tmp, "t")
    shutil.rmtree(path, ignore_errors=True)
    kwargs = dict(store=zarr.storage.LocalStore(path), shape=chunk.shape, dtype=c["dtype"], fill_value=c["fill"],
                  compressors=c["compressors"])
    if c["shards"] is not None:
        kwargs.update(shards=chunk.shape, chunks=c["shards"])
    else:
        kwargs["chunks"] = chunk.shape
    if c["dtype"] == "str":
        kwargs["serializer"] = VLenUTF8Codec()
    z = zarr.create_array(**kwargs)
    z[...] = chunk
    meta = json.load(open(os.path.join(path, "zarr.json"), encoding="utf-8"))
    key = os.path.join(path, "c", *(["0"] * chunk.ndim)) if chunk.ndim else os.path.join(path, "c")
    data = open(key, "rb").read() if os.path.exists(key) else None
    return data, meta["codecs"]


def build(c):
    path = os.path.join(OUT, c["name"])
    if os.path.exists(path):
        shutil.rmtree(path)
    os.makedirs(path)
    values = np.asarray(c["values"])
    codecs_after = None
    with tempfile.TemporaryDirectory() as tmp:
        for key, box, declared in chunk_boxes(c):
            chunk = np.full(declared, c["fill"], dtype=object if c["dtype"] == "str" else c["dtype"])
            part = values[box]
            chunk[tuple(slice(0, n) for n in part.shape)] = part
            if np.all(chunk == c["fill"]):
                continue
            for kind, arg in c["steps"]:
                chunk = chunk.reshape(resolve(arg, chunk.shape)) if kind == "reshape" else chunk.transpose(arg)
            data, codecs_after = chunk_bytes(c, chunk, tmp)
            target = os.path.join(path, *key.split("/"))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            open(target, "wb").write(data)
    steps = [{"name": "reshape", "configuration": {"shape": arg}} if kind == "reshape"
             else {"name": "transpose", "configuration": {"order": list(arg)}} for kind, arg in c["steps"]]
    if c["chunks"] is not None:
        grid = {"name": "regular", "configuration": {"chunk_shape": list(c["chunks"])}}
    else:
        grid = {"name": "rectilinear", "configuration": {"kind": "inline",
                                                          "chunk_shapes": [list(x) for x in c["lengths"]]}}
    meta = {"zarr_format": 3, "node_type": "array", "shape": list(c["shape"]),
            "data_type": "string" if c["dtype"] == "str" else np.dtype(c["dtype"]).name,
            "chunk_grid": grid, "chunk_key_encoding": {"name": "default", "configuration": {"separator": "/"}},
            "fill_value": c["fill"], "codecs": steps + codecs_after, "attributes": {}}
    with open(os.path.join(path, "zarr.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
    flat = values.reshape(-1)
    if c["dtype"] == "str":
        out = [str(v) for v in flat]
    elif np.dtype(c["dtype"]).kind == "f":
        out = [float(v) for v in flat.astype("<f8")]
    else:
        out = [int(v) for v in flat]
    expected = dict(name=c["name"], shape=list(c["shape"]), dtype=c["dtype"], codecs=[s["name"] for s in meta["codecs"]],
                    values=out)
    with open(os.path.join(OUT, c["name"] + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(expected, f, indent=1, ensure_ascii=False)
    print(f"  {c['name']:36s} {' -> '.join(expected['codecs'])}")


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} (the codecs after reshape) -> {OUT}")
    for c in CASES:
        build(c)
    print(f"{len(CASES)} fixtures written")


if __name__ == "__main__":
    main()
