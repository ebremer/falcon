"""Generates Zarr v2 conformance fixtures with zarr-python (the reference oracle).

Dev-time tool only -- not a Falcon dependency. Install with: pip install zarr numcodecs
Run from the repo root:  python tools/fixtures/gen_zarr_v2_fixtures.py

Each case writes a true Zarr v2 store (.zarray / .zgroup / .zattrs) under
zarr/src/test/resources/fixtures/<name>/ plus a <name>.expected.json sidecar, so
the Java tests stay hermetic. Note: zarr-python 3.x only writes real v2 layout via
create_array(zarr_format=2) with an explicit LocalStore; the high-level open_group
helper writes v3 layout even when asked for v2.
"""
import json, os, shutil
import numpy as np
import numcodecs
import zarr
import zarr.storage

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")
CASES = []


def case(name, shape, chunks, dtype, *, compressor=None, fill_value=0,
         separator=".", attrs=None, partial=False):
    CASES.append(dict(name=name, shape=shape, chunks=chunks, dtype=dtype,
                      compressor=compressor, fill_value=fill_value,
                      separator=separator, attrs=attrs, partial=partial))


case("v2_le_int32", (10,), (4,), "<i4")
case("v2_be_int32", (6,), (4,), ">i4")
case("v2_gzip_float64", (4, 6), (2, 3), "<f8", compressor=numcodecs.GZip(level=5))
case("v2_zstd_int32", (100,), (16,), "<i4", compressor=numcodecs.Zstd(level=3))
case("v2_blosc_float64", (10, 12), (5, 6), "<f8",
     compressor=numcodecs.Blosc(cname="lz4", clevel=5, shuffle=1))
case("v2_uint8", (5,), (2,), "|u1")
case("v2_bool", (6,), (4,), "|b1", fill_value=False)
case("v2_float32_nan", (5,), (2,), "<f4", fill_value=float("nan"))
case("v2_int64", (5,), (2,), "<i8")
case("v2_slash_2d", (4, 4), (2, 2), "<i4", separator="/")
case("v2_partial_fill", (10,), (4,), "<i4", fill_value=7, partial=True)
case("v2_attrs", (4,), (2,), "<i4", attrs={"units": "K", "n": 3})


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
            out.append("NaN" if np.isnan(v) else
                       ("Infinity" if v == np.inf else
                        ("-Infinity" if v == -np.inf else v)))
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
        store=zarr.storage.LocalStore(path), shape=c["shape"], chunks=c["chunks"],
        dtype=dtype, fill_value=c["fill_value"], zarr_format=2,
        compressors=[c["compressor"]] if c["compressor"] is not None else None,
        chunk_key_encoding={"name": "v2", "separator": c["separator"]},
        attributes=c["attrs"] or {})

    n = int(np.prod(c["shape"]))
    values = values_for(dtype, n).reshape(c["shape"]).astype(dtype)
    if c["partial"]:
        expected = np.full(c["shape"], c["fill_value"], dtype=dtype)
        z[0:4] = values.reshape(-1)[0:4]
        expected.reshape(-1)[0:4] = values.reshape(-1)[0:4]
    else:
        z[...] = values
        expected = values

    meta = dict(name=c["name"], shape=list(c["shape"]), dtype=c["dtype"].lstrip("<>|="),
                values=jsonable(expected.reshape(-1), dtype), attributes=c["attrs"] or {})
    with open(os.path.join(OUT, c["name"] + ".expected.json"), "w") as f:
        json.dump(meta, f, indent=1)
    print(f"  {c['name']:22s} {c['dtype']:6s} {tuple(c['shape'])}")


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} (v2 layout) -> {OUT}")
    for c in CASES:
        build(c)
    print(f"{len(CASES)} v2 fixtures written")


if __name__ == "__main__":
    main()
