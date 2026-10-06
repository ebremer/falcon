"""Generates the Zarr v2 fixtures for P2 F4 with zarr-python (the reference oracle).

Dev-time tool only, like gen_zarr_v2_fixtures.py: zarr-python and numcodecs are not Falcon dependencies.
Run from the repo root:  python tools/fixtures/gen_zarr_v2_ext_fixtures.py

Each case writes a true Zarr v2 array (.zarray, chunks) with zarr.create_array(zarr_format=2) under
zarr/src/test/resources/fixtures/<name>/, and <name>.expected.json with what zarr-python reads back, every
element in C order (unwritten ones as the fill value), in the form Falcon reads it: numbers, booleans, text,
a time type's int64 counts (NaT as -2^63), and for the byte-string types each element in hex
(null_terminated_bytes without its trailing NULs; a struct packed little-endian, as Falcon's
readByteArrays gives it). "codecs" is the pipeline Falcon translates the metadata to.

The cases cover the v2 dtypes (U, S, V, M8/m8, structured, |O with vlen-utf8 and vlen-bytes), Fortran
order, every numcodecs filter Falcon reads (delta, fixedscaleoffset, quantize, bitround, astype, packbits,
shuffle, and the checksums), the zlib and lz4 compressors, blosc and zstd configurations, and chains of
them. The "v2x_nc_" ones need the numcodecs.* codecs.
"""
import json
import os
import shutil
import warnings

import numcodecs
import numpy as np
import zarr
import zarr.storage
from zarr.dtype import VariableLengthBytes, VariableLengthUTF8

warnings.simplefilter("ignore")  # zarr-python warns that v2 string and struct types are not stable specs
OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")

STRUCT = np.dtype([("a", "<i4"), ("b", "<f8"), ("c", "S2"), ("d", "<U2"), ("t", "<M8[s]"), ("f", "?")])
STRUCT_BE = np.dtype([("a", ">i4"), ("b", ">f8")])

CASES = []


def case(name, shape, chunks, dtype, values, *, zdtype=None, region=None, order="C", filters=None,
         compressor=None, fill=None):
    CASES.append(dict(name=name, shape=shape, chunks=chunks, dtype=dtype, values=values, zdtype=zdtype,
                      region=region, order=order, filters=filters, compressor=compressor, fill=fill))


def ints(n, dtype="<i4", scale=1):
    return (np.arange(n) * scale - n // 3).astype(dtype)


def floats(n, dtype="<f8"):
    return (np.arange(n) * 0.37 - 4.5).astype(dtype)


def structs(n, dtype):
    d = np.zeros(n, dtype=dtype)
    i = np.arange(n)
    d["a"] = i * 1000003 - 7
    d["b"] = i * 0.25 - 3
    if "c" in dtype.names:
        d["c"] = [bytes([65 + k % 26]) * (k % 3) for k in range(n)]
        d["d"] = [("\U0001F600" if k % 5 == 0 else "δ") + str(k % 10) for k in range(n)]
        d["t"] = np.where(i % 4 == 2, np.datetime64("NaT"), (i * 1000).astype("M8[s]"))
        d["f"] = i % 2 == 0
    return d


B_LZ4 = numcodecs.Blosc(cname="lz4", clevel=5, shuffle=numcodecs.Blosc.AUTOSHUFFLE)

# ---- dtypes ----
case("v2x_utf32", (7,), (3,), "<U4", np.array(["", "abc", "\U0001F600\U0001F601x", "a\0b", "δδδδ"], "<U4"),
     region=slice(0, 5), fill="zz", compressor=numcodecs.Zstd(level=1))
case("v2x_utf32_be", (4,), (3,), ">U3", np.array(["ab", "xyz", "", "q"], ">U3"), compressor=numcodecs.GZip(level=4))
case("v2x_bytes_s", (6,), (4,), "|S5", np.array([b"a\0b", b"", b"hello", b"x\0"], "|S5"), region=slice(0, 4),
     fill=b"ab", compressor=numcodecs.Blosc(cname="zlib", clevel=3, shuffle=numcodecs.Blosc.SHUFFLE))
case("v2x_raw_v", (5,), (2,), "|V3", np.array([b"\0\1\2", b"abc", b"\xff\0\0"], "|V3"), region=slice(0, 3),
     fill=b"\x01\x02\x03")
case("v2x_datetime_ms", (8,), (3,), "<M8[ms]",
     np.array(["2020-01-01T00:00:00.001", "NaT", "1969-12-31T23:59:59.999", "2262-04-11", "1677-09-22",
               "2000-02-29T12:00"], "<M8[ms]"), region=slice(0, 6))
case("v2x_datetime_10s_be", (5,), (2,), ">M8[10s]", np.array([0, -3, 12345], ">M8[10s]"), region=slice(0, 3),
     fill=np.datetime64(5, "10s"))
case("v2x_timedelta_ns", (5,), (5,), "<m8[ns]", np.array([-1, 0, 7, 2**62, -(2**62)], "<m8[ns]"),
     compressor=numcodecs.Zstd(level=3))
case("v2x_struct", (6,), (4,), STRUCT, structs(4, STRUCT), region=slice(0, 4),
     fill=np.array((9, 1.5, b"zz", "yy", 86400, True), STRUCT)[()])
case("v2x_struct_be", (5,), (2,), STRUCT_BE, structs(3, STRUCT_BE), region=slice(0, 3),
     fill=np.array((7, 1.5), STRUCT_BE)[()], compressor=numcodecs.Blosc(cname="lz4", clevel=5, shuffle=1))
case("v2x_vlen_utf8", (5,), (2,), "|O", np.array(["", "abc", "\U0001F600"], object), zdtype=VariableLengthUTF8(),
     region=slice(0, 3), fill="zz", compressor=numcodecs.Zstd(level=1))
case("v2x_vlen_bytes", (5,), (2,), "|O", np.array([b"", b"a\0c", b"\xff" * 5], object),
     zdtype=VariableLengthBytes(), region=slice(0, 3), fill=b"xy", compressor=numcodecs.GZip(level=1))
# ---- Fortran order ----
case("v2x_order_f_2d", (5, 7), (2, 3), "<i4", ints(35).reshape(5, 7), order="F",
     compressor=numcodecs.Blosc(cname="lz4", clevel=5, shuffle=1))
case("v2x_order_f_3d", (3, 4, 5), (2, 3, 2), "<f8", floats(2 * 2 * 5).reshape(2, 2, 5),
     region=(slice(0, 2), slice(1, 3), slice(None)), order="F", fill=-1.0, compressor=numcodecs.GZip(level=5))
case("v2x_order_f_str", (3, 4), (2, 3), "|O",
     np.array([["a", "bb", "ccc", "dddd"], ["e", "", "δ", "\U0001F600"], ["x", "y", "z", "w"]], object),
     zdtype=VariableLengthUTF8(), order="F")
case("v2x_order_f_utf32", (3, 3), (2, 2), "<U3", np.array([["a", "bc", "def"], ["g", "", "hi"], ["jkl", "m", "n"]],
                                                           "<U3"), order="F")
# ---- compressor configurations Falcon already has codecs for ----
case("v2x_zstd_checksum", (20,), (8,), "<i4", ints(20), compressor=numcodecs.Zstd(level=5, checksum=True))
case("v2x_blosc_auto_u1", (2000,), (500,), "|u1", (np.arange(2000) % 7).astype("|u1"), compressor=B_LZ4)
case("v2x_blosc_zstd_bitshuffle", (1200,), (400,), "<f4", floats(1200, "<f4"),
     compressor=numcodecs.Blosc(cname="zstd", clevel=7, shuffle=numcodecs.Blosc.BITSHUFFLE))
case("v2x_blosc_utf32", (600,), (200,), "<U3", np.array([("ab", "δ", "xyz", "")[k % 4] for k in range(600)], "<U3"),
     compressor=B_LZ4)
case("v2x_blosc_struct_be", (400,), (150,), STRUCT_BE, structs(400, STRUCT_BE),
     compressor=numcodecs.Blosc(cname="zstd", clevel=5, shuffle=numcodecs.Blosc.SHUFFLE))
# ---- the numcodecs codecs ----
case("v2x_nc_zlib", (20,), (8,), "<i4", ints(20, scale=3), compressor=numcodecs.Zlib(level=6))
case("v2x_nc_lz4", (20,), (8,), "<f8", floats(20), compressor=numcodecs.LZ4(acceleration=3))
case("v2x_nc_delta_astype", (1000,), (256,), "<i4", ints(1000, scale=5),
     filters=[numcodecs.Delta(dtype="<i4", astype="<i2")], compressor=B_LZ4)
case("v2x_nc_delta_i8", (12,), (5,), "<i8", np.array([5, 3, -9, 2**40, -(2**40), 0, 1, 1, 1, 7, -7, 100], "<i8"),
     filters=[numcodecs.Delta(dtype="<i8")])
case("v2x_nc_fixedscaleoffset_u1", (10,), (4,), "<f8", np.linspace(1000, 1001, 10),
     filters=[numcodecs.FixedScaleOffset(offset=1000, scale=10, dtype="<f8", astype="u1")],
     compressor=numcodecs.Zlib(level=1))
case("v2x_nc_fixedscaleoffset_u2", (12,), (5,), "<f4", floats(12, "<f4") + 10,
     filters=[numcodecs.FixedScaleOffset(offset=0, scale=100, dtype="<f4", astype="<u2")])
case("v2x_nc_quantize", (16,), (6,), "<f8", np.linspace(0, 1, 16),
     filters=[numcodecs.Quantize(digits=2, dtype="<f8", astype="<f4")], compressor=numcodecs.Zstd(level=1))
case("v2x_nc_bitround", (16,), (6,), "<f4", floats(16, "<f4") * 3.3, filters=[numcodecs.BitRound(keepbits=5)],
     compressor=numcodecs.Zstd(level=1))
case("v2x_nc_astype", (9,), (4,), "<f8", floats(9) / 7, filters=[numcodecs.AsType(encode_dtype="<f4", decode_dtype="<f8")])
case("v2x_nc_packbits", (13,), (10,), "|b1", np.arange(13) % 3 == 0, filters=[numcodecs.PackBits()])
case("v2x_nc_shuffle", (20,), (8,), "<i4", ints(20, scale=7), filters=[numcodecs.Shuffle(elementsize=4)],
     compressor=numcodecs.Zlib(level=1))
case("v2x_nc_crc32", (9,), (4,), "<i2", ints(9, "<i2"), filters=[numcodecs.CRC32()])
case("v2x_nc_crc32c", (9,), (4,), "<i2", ints(9, "<i2"), filters=[numcodecs.CRC32C()])
case("v2x_nc_adler32", (9,), (4,), "<u4", np.arange(9, dtype="<u4") * 99, filters=[numcodecs.Adler32()],
     compressor=numcodecs.Zstd(level=1))
case("v2x_nc_fletcher32", (9,), (4,), "<f4", floats(9, "<f4"), filters=[numcodecs.Fletcher32()])
case("v2x_nc_jenkins", (9,), (4,), "<i8", ints(9, "<i8"), filters=[numcodecs.JenkinsLookup3(initval=7)])
case("v2x_nc_crc32_compressor", (9,), (4,), "<i4", ints(9), compressor=numcodecs.CRC32())
case("v2x_nc_chain", (600,), (256,), "<f8", np.linspace(-2, 2, 600),
     filters=[numcodecs.FixedScaleOffset(offset=0, scale=1000, dtype="<f8", astype="<i4"),
              numcodecs.Delta(dtype="<i4", astype="<i2")],
     compressor=numcodecs.Blosc(cname="zstd", clevel=3, shuffle=numcodecs.Blosc.AUTOSHUFFLE))
case("v2x_nc_order_f_delta", (4, 6), (3, 4), "<i4", ints(24, scale=11).reshape(4, 6), order="F",
     filters=[numcodecs.Delta(dtype="<i4")], compressor=numcodecs.Zlib(level=1))
case("v2x_nc_vlen_utf8_lz4", (4,), (3,), "|O", np.array(["a", "bb", "", "ccc"], object), zdtype=VariableLengthUTF8(),
     compressor=numcodecs.LZ4())


def falcon_values(a, dtype):
    """Every element, flattened in C order, in the form the Java test compares."""
    flat = np.asarray(a).reshape(-1)
    if dtype.kind == "O":
        return [v if isinstance(v, str) else bytes(v).hex() for v in flat.tolist()]
    if dtype.kind == "U":
        return [str(v) for v in flat.tolist()]
    if dtype.kind == "S":
        return [bytes(v).rstrip(b"\0").hex() for v in flat]
    if dtype.kind == "V" and dtype.names is None:
        return [flat[i].tobytes().hex() for i in range(flat.size)]
    if dtype.names is not None:
        le = flat.astype(dtype.newbyteorder("<"))
        return [le[i].tobytes().hex() for i in range(le.size)]
    if dtype.kind in "Mm":
        return [int(v) for v in flat.astype(dtype.newbyteorder("<")).view("<i8")]
    if dtype.kind == "f":
        return ["NaN" if np.isnan(v) else v for v in flat.astype("<f8").tolist()]
    if dtype.kind == "b":
        return [bool(v) for v in flat.tolist()]
    return [int(v) for v in flat.tolist()]


def falcon_codecs(zarray):
    """The pipeline Falcon translates a .zarray to (codec names)."""
    names = []
    if zarray["order"] == "F" and len(zarray["shape"]) > 1:
        names.append("transpose")
    filters = list(zarray["filters"] or [])
    if zarray["dtype"] == "|O":
        names.append(filters.pop(0)["id"])
    else:
        names.append("bytes")
    for c in filters + ([zarray["compressor"]] if zarray["compressor"] else []):
        names.append(c["id"] if c["id"] in ("gzip", "zstd", "blosc") else "numcodecs." + c["id"])
    return names


def build(c):
    path = os.path.join(OUT, c["name"])
    if os.path.exists(path):
        shutil.rmtree(path)
    dtype = np.dtype(c["dtype"])
    kwargs = dict(store=zarr.storage.LocalStore(path), shape=c["shape"], chunks=c["chunks"],
                  dtype=c["zdtype"] if c["zdtype"] is not None else dtype, zarr_format=2, order=c["order"],
                  compressors=[c["compressor"]] if c["compressor"] is not None else None)
    if c["filters"] is not None:
        kwargs["filters"] = c["filters"]  # left out, a |O array gets its object codec
    if c["fill"] is not None:
        kwargs["fill_value"] = c["fill"]
    z = zarr.create_array(**kwargs)
    if c["region"] is None:
        z[...] = c["values"]
    else:
        z[c["region"]] = c["values"]
    got = zarr.open_array(zarr.storage.LocalStore(path), mode="r")[...]
    zarray = json.load(open(os.path.join(path, ".zarray"), encoding="utf-8"))
    meta = dict(name=c["name"], shape=list(c["shape"]), dtype=zarray["dtype"], codecs=falcon_codecs(zarray),
                values=falcon_values(got, dtype))
    with open(os.path.join(OUT, c["name"] + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1, ensure_ascii=False)
    print(f"  {c['name']:30s} {str(zarray['dtype']):12.12s} {' -> '.join(meta['codecs'])}")


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__}, numcodecs {numcodecs.__version__} (v2 layout) -> {OUT}")
    for c in CASES:
        build(c)
    print(f"{len(CASES)} v2 fixtures written")


if __name__ == "__main__":
    main()
