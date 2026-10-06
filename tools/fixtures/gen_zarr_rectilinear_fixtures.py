"""Generates the rectilinear chunk grid fixtures added with P2 (F14) with zarr-python.

Dev-time tool only, like gen_zarr_nested_fixtures.py (whose sidecar format these share): zarr-python is not a
Falcon dependency. Run from the repo root:  python tools/fixtures/gen_zarr_rectilinear_fixtures.py

zarr-python 3.4 writes rectilinear grids (zarr-extensions chunk-grids/rectilinear) only with
array.rectilinear_chunks set, as here. Each case writes a store under zarr/src/test/resources/fixtures/<name>/
and <name>.expected.json, whose "values" are the elements in C order as zarr-python reads them back and whose
"chunk_sizes" is zarr-python's write_chunk_sizes (each chunk's extent inside the array). Only these cases are
(re)written.

  rect_2d              int32 10 x 7, rows chunked 3, 3, 4 and columns 2, 5 (stored as [[3,2],4] and [2,5])
  rect_3d              uint8 6 x 5 x 4: [1,2,3], a bare 2 (repeating), [3,1]; zstd
  rect_rle             float64 of 100 in [[10,9],[5,2]]; only elements 3 to 96 written
  rect_past            int16 created 12 long in 4, 4, 4 and resized to 10: the last chunk runs past the array
  rect_resized         int32 10 x 7 grown to 12 (one chunk added: [[3,2],4,2]), shrunk to 4, grown to 9; as
                       zarr-python resizes, rows 4 and 5 hold their old values again
  rect_sharded         float32 12 x 8: shards of [4, 8] x [8] rows and columns, sub-chunks 2 x 4, zstd inside
  rect_sharded_partial int16 12 x 8 as rect_sharded, fill 7, only rows 5-7 x columns 2-6 written
  rect_string          strings of 5 in 2, 3
  rect_bytes           variable_length_bytes of 6 in 1, 2, 3
  rect_transposed      uint16 5 x 4 in [2, 3] x [4], transposed before the bytes codec, fill 7
  rect_v2_keys         int8 of 7 in 3, 4, with the v2 chunk key encoding
  rect_example         uint8 6 x 6 x 6 x 6 x 6: the extension's own example, chunk_shapes
                       [4, [1,2,3], [[4,2]], [[1,3],3], [[4,3]]] (made 6 x 6 x 8 x 6 x 12 and shrunk, since
                       zarr-python creates only lengths that fit the array exactly)
  rect_group           a group of two rectilinear arrays, a (int32 3 x 4) and sub/b (float64 of 5), with
                       consolidated metadata; sidecars rect_group_a and rect_group_sub_b
"""
import json, os, shutil
import warnings
import numpy as np
import zarr
from zarr.codecs import BytesCodec, TransposeCodec, ZstdCodec

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")
STRING_POOL = ["alpha", "", "gamma-δ", "中文", "emoji-\U0001f600", "x"]


def fresh(name):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        shutil.rmtree(path)
    return path


def sidecar(name, z, dtype, path=None):
    values = z[...].reshape(-1)
    if dtype == "string":
        values = [str(v) for v in values]
    elif dtype == "bytes":
        values = [bytes(v).hex() for v in values]
    else:
        values = [v.item() for v in values]
    meta = dict(name=name, shape=list(z.shape), dtype=dtype, attributes={}, values=values,
                chunk_sizes=[list(d) for d in z.write_chunk_sizes])
    if path is not None:
        meta["path"] = path
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1, ensure_ascii=False)
    grid = json.load(open(os.path.join(z.store.root, *(z.path.split("/") if z.path else []), "zarr.json"),
                          encoding="utf-8"))["chunk_grid"]
    print(f"  {name:22s} {dtype:8s} {str(tuple(z.shape)):18s} {json.dumps(grid['configuration']['chunk_shapes'])}")


def main():
    os.makedirs(OUT, exist_ok=True)
    zarr.config.set({"array.rectilinear_chunks": True})
    print(f"zarr-python {zarr.__version__} -> {OUT}")
    warnings.simplefilter("ignore")

    z = zarr.create_array(store=fresh("rect_2d"), shape=(10, 7), chunks=[[3, 3, 4], [2, 5]], dtype="int32",
                          fill_value=-1, compressors=None)
    z[...] = (np.arange(70, dtype="int32") * 3 - 100).reshape(10, 7)
    sidecar("rect_2d", z, "int32")

    z = zarr.create_array(store=fresh("rect_3d"), shape=(6, 5, 4), chunks=[[1, 2, 3], 2, [3, 1]], dtype="uint8",
                          fill_value=0, compressors=ZstdCodec(level=3))
    z[...] = (np.arange(120) % 251).astype("uint8").reshape(6, 5, 4)
    sidecar("rect_3d", z, "uint8")

    z = zarr.create_array(store=fresh("rect_rle"), shape=(100,), chunks=[[10] * 9 + [5, 5]], dtype="float64",
                          fill_value=-0.5)
    z[3:97] = np.arange(3, 97, dtype="float64") * 0.25
    sidecar("rect_rle", z, "float64")

    z = zarr.create_array(store=fresh("rect_past"), shape=(12,), chunks=[[4, 4, 4]], dtype="int16",
                          fill_value=0, compressors=None)
    z[...] = np.arange(12, dtype="int16") + 1000
    z.resize((10,))
    sidecar("rect_past", z, "int16")

    z = zarr.create_array(store=fresh("rect_resized"), shape=(10, 7), chunks=[[3, 3, 4], [2, 5]], dtype="int32",
                          fill_value=-1)
    z[...] = np.arange(70, dtype="int32").reshape(10, 7)
    z.resize((12, 7))
    z.resize((4, 7))
    z.resize((9, 7))
    sidecar("rect_resized", z, "int32")

    z = zarr.create_array(store=fresh("rect_sharded"), shape=(12, 8), chunks=(2, 4), shards=[[4, 8], [8]],
                          dtype="float32", fill_value=0, compressors=ZstdCodec(level=1))
    z[...] = (np.arange(96, dtype="float32") * 0.5).reshape(12, 8)
    sidecar("rect_sharded", z, "float32")

    z = zarr.create_array(store=fresh("rect_sharded_partial"), shape=(12, 8), chunks=(2, 4),
                          shards=[[4, 8], [8]], dtype="int16", fill_value=7, compressors=None)
    z[5:8, 2:7] = np.arange(15, dtype="int16").reshape(3, 5) + 500
    sidecar("rect_sharded_partial", z, "int16")

    z = zarr.create_array(store=fresh("rect_string"), shape=(5,), chunks=[[2, 3]], dtype=str, fill_value="")
    z[...] = np.array([STRING_POOL[i % len(STRING_POOL)] + str(i) for i in range(5)], dtype=object)
    sidecar("rect_string", z, "string")

    z = zarr.create_array(store=fresh("rect_bytes"), shape=(6,), chunks=[[1, 2, 3]],
                          dtype=zarr.core.dtype.VariableLengthBytes(), fill_value=b"")
    z[...] = np.array([bytes(range(i, 2 * i)) for i in range(6)], dtype=object)
    sidecar("rect_bytes", z, "bytes")

    z = zarr.create_array(store=fresh("rect_transposed"), shape=(5, 4), chunks=[[2, 3], [4]], dtype="uint16",
                          fill_value=7, filters=[TransposeCodec(order=(1, 0))], compressors=None)
    z[...] = np.arange(20, dtype="uint16").reshape(5, 4) * 11
    sidecar("rect_transposed", z, "uint16")

    z = zarr.create_array(store=fresh("rect_v2_keys"), shape=(7,), chunks=[[3, 4]], dtype="int8", fill_value=0,
                          chunk_key_encoding={"name": "v2", "separator": "."})
    z[...] = np.arange(7, dtype="int8") - 3
    sidecar("rect_v2_keys", z, "int8")

    z = zarr.create_array(store=fresh("rect_example"), shape=(6, 6, 8, 6, 12),
                          chunks=[4, [1, 2, 3], [4, 4], [1, 1, 1, 3], [4, 4, 4]], dtype="uint8", fill_value=0)
    z.resize((6, 6, 6, 6, 6))
    z[...] = (np.arange(6 ** 5) % 253).astype("uint8").reshape(6, 6, 6, 6, 6)
    sidecar("rect_example", z, "uint8")

    root = fresh("rect_group")
    g = zarr.open_group(root, mode="w")
    a = g.create_array("a", shape=(3, 4), chunks=[[1, 2], [3, 1]], dtype="int32", fill_value=0)
    a[...] = np.arange(12, dtype="int32").reshape(3, 4) - 6
    b = g.create_group("sub").create_array("b", shape=(5,), chunks=[[2, 2, 1]], dtype="float64", fill_value=0.0)
    b[...] = np.arange(5, dtype="float64") / 4
    zarr.consolidate_metadata(root)
    sidecar("rect_group_a", a, "int32", "a")
    sidecar("rect_group_sub_b", b, "float64", "sub/b")


if __name__ == "__main__":
    main()
