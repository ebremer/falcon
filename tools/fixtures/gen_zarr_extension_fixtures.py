"""Generates the fixtures for P2 F14's extension data types with zarr-python.

Dev-time tool only, like gen_zarr_exact_fixtures.py: zarr-python is not a Falcon dependency. Run from the
repo root:  python tools/fixtures/gen_zarr_extension_fixtures.py

Each case writes a store under zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json, whose
"values" hold every element in C order, the unwritten ones as the fill value, in the form Falcon reads
them: a time type's int64 counts (NaT as -2^63), fixed_length_utf32 text, and for the byte-string types
each element in hex (null_terminated_bytes without its trailing NULs; a struct packed little-endian, as
Falcon's readByteArrays gives it).

  ext_datetime64_ms          datetime64[ms], NaT, a chunk left unwritten (fill NaT), zstd
  ext_datetime64_10s_be      datetime64[10s], big-endian, sharded
  ext_timedelta64_ns         timedelta64[ns], negative values, fill 5
  ext_utf32                  U4: non-BMP, an embedded NUL, a fill of "zz" in an unwritten chunk, sharded
  ext_utf32_be               U3, big-endian, blosc
  ext_null_bytes             S5: embedded and trailing NULs, fill b"ab", gzip
  ext_raw_bytes              V3: fill b"\\x01\\x02\\x03", sharded
  ext_struct                 a struct of every kind of field, a nested struct, an explicit fill, 2-D
  ext_struct_be              the same struct, big-endian, sharded, transposed
  ext_structured_legacy      the legacy "structured" name with [name, data_type] field pairs
  ext_struct_b64_fill        a struct whose fill_value is base64 text, as zarr-python also reads it
"""
import base64
import json
import os
import shutil
import warnings

import numpy as np
import zarr
from zarr.codecs import BloscCodec, BytesCodec, GzipCodec, TransposeCodec, ZstdCodec

warnings.simplefilter("ignore")  # zarr-python warns that some of these data types are not yet stable specs
OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")

STRUCT = np.dtype([("a", "<i4"), ("b", "<f8"), ("c", "S2"), ("d", "<U2"), ("t", "<M8[s]"), ("f", "?"),
                   ("p", [("x", "<i2"), ("y", "u1")])])


def fresh(name):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        shutil.rmtree(path)
    return path


def sidecar(name, z, values):
    meta = json.load(open(os.path.join(OUT, name, "zarr.json"), encoding="utf-8"))
    out = dict(name=name, shape=list(z.shape), data_type=meta["data_type"], fill_value=meta["fill_value"],
               values=values)
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
    print(f"  {name:24s} {str(z.dtype):60s} {z.shape}")


def counts(a):
    a = np.asarray(a)
    return [int(v) for v in a.astype(a.dtype.newbyteorder("<")).reshape(-1).view("<i8")]


def hexes(a, strip=False):
    out = []
    for v in np.asarray(a).reshape(-1):
        b = v.tobytes()
        out.append((b.rstrip(b"\0") if strip else b).hex())
    return out


def le(a):
    """A struct array with every field little-endian (Falcon's readByteArrays order)."""
    a = np.asarray(a)
    return a.astype(a.dtype.newbyteorder("<"))


def struct_values(n):
    d = np.zeros(n, dtype=STRUCT)
    i = np.arange(n)
    d["a"] = (i * 2654435761) % 2**32 - 2**31
    d["b"] = i * 0.25 - 3
    d["c"] = [bytes([65 + k % 26]) * (k % 3) for k in range(n)]
    d["d"] = [("\U0001F600" if k % 5 == 0 else "δ") + str(k % 10) for k in range(n)]
    d["t"] = np.where(i % 9 == 2, np.datetime64("NaT"), (i * 1000).astype("M8[s]"))
    d["f"] = i % 2 == 0
    d["p"]["x"] = i - 500
    d["p"]["y"] = i % 256
    return d


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} -> {OUT}")

    # datetime64[ms]: 8 elements in chunks of 3, the last chunk (6, 7) never written: NaT, the default fill.
    v = np.array(["2020-01-01T00:00:00.001", "NaT", "1969-12-31T23:59:59.999", "2262-04-11", "1677-09-22",
                  "2000-02-29T12:00"], dtype="M8[ms]")
    z = zarr.create_array(store=fresh("ext_datetime64_ms"), shape=(8,), chunks=(3,), dtype="M8[ms]",
                          compressors=[ZstdCodec(level=3)])
    z[:6] = v
    sidecar("ext_datetime64_ms", z, counts(z[...]))

    v = (np.arange(12).reshape(3, 4) * 7 - 20).astype("M8[10s]")
    v[1, 2] = np.datetime64("NaT")
    z = zarr.create_array(store=fresh("ext_datetime64_10s_be"), shape=(3, 4), chunks=(1, 2), shards=(3, 4),
                          dtype=">M8[10s]", serializer=BytesCodec(endian="big"))
    z[...] = v
    sidecar("ext_datetime64_10s_be", z, counts(z[...]))

    z = zarr.create_array(store=fresh("ext_timedelta64_ns"), shape=(6,), chunks=(4,), dtype="m8[ns]",
                          fill_value=np.timedelta64(5, "ns"), compressors=None)
    z[:4] = np.array([-(2**62), -1, 0, 2**62], dtype="m8[ns]")
    sidecar("ext_timedelta64_ns", z, counts(z[...]))

    z = zarr.create_array(store=fresh("ext_utf32"), shape=(10,), chunks=(2,), shards=(4,), dtype="<U4",
                          fill_value="zz")
    z[:7] = np.array(["", "a", "abcd", "\U0001F600\U0001F601", "x\0y", "中文字", "δ"], dtype="<U4")
    sidecar("ext_utf32", z, [str(s) for s in z[...]])

    z = zarr.create_array(store=fresh("ext_utf32_be"), shape=(2, 3), chunks=(2, 2), dtype=">U3",
                          serializer=BytesCodec(endian="big"),
                          compressors=[BloscCodec(cname="zstd", clevel=5, shuffle="shuffle")])
    z[...] = np.array([["abc", "\U0001F600", ""], ["ü", "x\0", "ok!"]], dtype=">U3")
    sidecar("ext_utf32_be", z, [str(s) for s in z[...].reshape(-1)])

    z = zarr.create_array(store=fresh("ext_null_bytes"), shape=(7,), chunks=(3,), dtype="S5",
                          fill_value=b"ab", compressors=[GzipCodec(level=5)])
    z[:5] = np.array([b"", b"a\0b", b"abcde", b"\xff\xfe", b"q\0\0"], dtype="S5")
    sidecar("ext_null_bytes", z, hexes(z[...], strip=True))

    z = zarr.create_array(store=fresh("ext_raw_bytes"), shape=(6,), chunks=(2,), shards=(4,), dtype="V3",
                          fill_value=b"\x01\x02\x03")
    z[:3] = np.array([b"\0\0\0", b"abc", b"\xff\0\x7f"], dtype="V3")
    sidecar("ext_raw_bytes", z, hexes(z[...]))

    fill = np.array((7, 1.5, b"q", "z", np.datetime64("NaT"), True, (-1, 255)), dtype=STRUCT)[()]
    z = zarr.create_array(store=fresh("ext_struct"), shape=(4, 5), chunks=(2, 3), dtype=STRUCT,
                          fill_value=fill, compressors=[ZstdCodec(level=1)])
    z[:3, :] = struct_values(15).reshape(3, 5)  # row 3 stays fill
    sidecar("ext_struct", z, hexes(le(z[...])))

    be = STRUCT.newbyteorder(">")
    z = zarr.create_array(store=fresh("ext_struct_be"), shape=(4, 5), chunks=(2, 5), shards=(4, 5),
                          dtype=be, fill_value=fill, serializer=BytesCodec(endian="big"),
                          filters=[TransposeCodec(order=(1, 0))], compressors=None)
    z[...] = struct_values(20).reshape(4, 5).astype(be)
    sidecar("ext_struct_be", z, hexes(le(z[...])))

    legacy = np.dtype([("a", "<i4"), ("b", "<f8")])
    z = zarr.create_array(store=fresh("ext_structured_legacy"), shape=(3,), chunks=(3,), dtype=legacy,
                          fill_value=np.zeros((), legacy)[()], compressors=None)
    z[...] = np.array([(1, 0.5), (-2, 2.5), (3, -1e300)], dtype=legacy)
    path = os.path.join(OUT, "ext_structured_legacy", "zarr.json")
    meta = json.load(open(path, encoding="utf-8"))
    meta["data_type"] = {"name": "structured", "configuration": {"fields": [["a", "int32"], ["b", "float64"]]}}
    json.dump(meta, open(path, "w", encoding="utf-8", newline="\n"), indent=2)
    z = zarr.open_array(store=os.path.join(OUT, "ext_structured_legacy"), mode="r")  # zarr-python reads it
    sidecar("ext_structured_legacy", z, hexes(le(z[...])))

    small = np.dtype([("a", "<i2"), ("s", "S2")])
    z = zarr.create_array(store=fresh("ext_struct_b64_fill"), shape=(4,), chunks=(2,), dtype=small,
                          fill_value=np.array((5, b"hi"), dtype=small)[()], compressors=None)
    z[:2] = np.array([(1, b"a"), (-1, b"")], dtype=small)
    path = os.path.join(OUT, "ext_struct_b64_fill", "zarr.json")
    meta = json.load(open(path, encoding="utf-8"))
    meta["fill_value"] = base64.b64encode(np.array((-3, b"zz"), dtype=small).tobytes()).decode("ascii")
    json.dump(meta, open(path, "w", encoding="utf-8", newline="\n"), indent=2)
    z = zarr.open_array(store=os.path.join(OUT, "ext_struct_b64_fill"), mode="r")
    sidecar("ext_struct_b64_fill", z, hexes(le(z[...])))


if __name__ == "__main__":
    main()
