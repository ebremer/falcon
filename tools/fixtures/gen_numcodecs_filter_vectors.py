"""Generates reference vectors for the numcodecs filters and checksums Falcon runs (numcodecs.delta,
fixedscaleoffset, quantize, bitround, astype, packbits, shuffle, crc32, crc32c, adler32, fletcher32,
jenkins_lookup3), straight from numcodecs and NumPy 2.

Dev-time tool only -- zarr, numcodecs, and numpy are NOT Falcon dependencies. Install with
pip install -r tools/fixtures/requirements.txt (zarr brings google-crc32c, which numcodecs' crc32c needs).
Run from the repo root:  python tools/fixtures/gen_numcodecs_filter_vectors.py

Writes zarr/src/test/resources/fixtures/numcodecs_filter_vectors.txt, one JSON object per line:

    {"config": {...numcodecs get_config()...}, "input": "<i4", "data": hex,
     "encoded": hex | null, "decoded": hex | null}

- "config" is the codec's numcodecs configuration, with its "id"; Falcon runs it as numcodecs.<id> with the
  rest as its configuration. A few configurations are written by hand where the JSON literal matters
  (an int or a float offset/scale, as Python's json reads them back).
- "input" is the dtype of the elements the codec is given when encoding (zarr-python hands each filter the
  previous stage's array), or null for plain bytes.
- "data" is those elements' bytes. "encoded" is numcodecs' encode(data), or null where numcodecs raises
  (then Falcon's encode must fail too).
- "decoded" is numcodecs' decode(encoded) as bytes (decode is given bytes, as zarr-python's pipeline gives
  it the decompressed buffer), absent when it is "data" again, or null where numcodecs raises. A
  decode-only case has "data": null and "encoded" chosen here.

Cases where NumPy's result is undefined -- a NaN or out-of-range float cast to an integer, which NumPy
reports with "invalid value encountered in cast" -- are left out.

Two tables ride along: {"promote": [a, b], "result": c} (np.promote_types, which np.cumsum accumulates
in), and {"quantize_digits": d, "scale": s} (quantize.py's power-of-two scale for d digits).

It also writes one Zarr v3 array with zarr-python 3 (zarr/src/test/resources/fixtures/numcodecs_v3_bytes,
with numcodecs_v3_bytes.expected.json): int32 data through numcodecs.shuffle and every checksum, the
bytes->bytes numcodecs codecs zarr-python 3 writes in v3 metadata, so Falcon is checked reading them and
writing the same chunk bytes.
"""
import json
import math
import os
import warnings

import shutil

import numcodecs
import numpy as np
import zarr
from numcodecs.compat import ensure_bytes
from zarr.codecs import numcodecs as zarr_numcodecs

FIXTURES = os.path.join("zarr", "src", "test", "resources", "fixtures")
OUT = os.path.join(FIXTURES, "numcodecs_filter_vectors.txt")

rng = np.random.default_rng(20261006)
LINES = []


class Undefined(Exception):
    pass


def run(fn):
    """fn() with NumPy's "invalid value encountered in cast" turned into Undefined (a NaN or out-of-range float
    cast to an integer); every other warning (overflow to infinity, NaN from inf * 0) is IEEE 754 and kept."""
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        warnings.filterwarnings("error", message="invalid value encountered in cast", category=RuntimeWarning)
        try:
            return fn()
        except RuntimeWarning as e:
            raise Undefined(str(e)) from e


def case(config, data=None, encoded=None):
    """One vector: config is a numcodecs config dict (with id), data a NumPy array or None."""
    codec = numcodecs.get_codec(dict(config))
    entry = {"config": config}
    try:
        if data is not None:
            entry["input"] = data.dtype.str
            entry["data"] = data.tobytes().hex()
            try:
                enc = run(lambda: ensure_bytes(codec.encode(data.copy())))
            except Undefined:
                return
            except Exception:  # noqa: BLE001 - numcodecs raises ValueError, TypeError, OverflowError, ...
                enc = None
        else:
            entry["input"] = None
            entry["data"] = None
            enc = encoded
        entry["encoded"] = None if enc is None else enc.hex()
        if enc is not None:
            try:
                dec = run(lambda: ensure_bytes(np.ascontiguousarray(codec.decode(bytes(enc)))))
            except Undefined:
                return
            except Exception:  # noqa: BLE001
                dec = None
            if dec is None:
                entry["decoded"] = None
            elif data is None or dec != data.tobytes():
                entry["decoded"] = dec.hex()  # absent: the decoded bytes are "data"
        else:
            entry["decoded"] = None
    except Undefined:
        return
    LINES.append(json.dumps(entry, separators=(",", ":")))


def values(dtype, n=24):
    """Edge values and random ones for a dtype."""
    dt = np.dtype(dtype)
    if dt.kind == "b":
        return rng.integers(0, 2, n).astype(dt)
    if dt.kind in "iu":
        info = np.iinfo(dt)
        edge = [0, 1, info.max, info.min, info.max - 1, info.min + 1]
        rand = rng.integers(info.min, info.max, max(n - len(edge), 0), dtype=dt.newbyteorder("="), endpoint=True)
        return np.concatenate([np.array(edge, dtype=dt), rand]).astype(dt)
    info = np.finfo(dt)
    edge = [0.0, -0.0, 1.0, -1.5, 2.5, 0.5, np.nan, np.inf, -np.inf, float(info.max), float(info.tiny),
            float(info.smallest_subnormal)]
    rand = (rng.standard_normal(max(n - len(edge), 0)) * 1000).astype(dt)
    return np.concatenate([np.array(edge, dtype=dt), rand])[:max(n, len(edge))].astype(dt)


def small(dtype, lo, hi, n=24):
    """Values within [lo, hi] of a dtype (floats with fractions, ties included)."""
    dt = np.dtype(dtype)
    if dt.kind in "iu":
        return rng.integers(lo, hi, n, endpoint=True).astype(dt)
    ties = np.array([0.5, 1.5, 2.5, -0.5, -1.5, -2.5], dtype="f8")
    ties = ties[(ties >= lo) & (ties <= hi)][:n]
    rand = rng.uniform(lo, hi, n - len(ties))
    return np.concatenate([ties, rand]).astype(dt)


NUMERIC = ["|b1", "|i1", "|u1", "<i2", "<u2", "<i4", "<u4", "<i8", "<u8", "<f2", "<f4", "<f8"]


def tables():
    for a in NUMERIC:
        for b in NUMERIC:
            LINES.append(json.dumps({"promote": [a, b], "result": np.promote_types(a, b).str}))
    for d in range(-30, 31):
        # quantize.py computes its scale inside encode; this is the same computation
        precision = 10.0 ** -d
        exp = math.log10(precision)
        exp = math.floor(exp) if exp < 0 else math.ceil(exp)
        bits = math.ceil(math.log2(10.0 ** -exp))
        LINES.append(json.dumps({"quantize_digits": d, "scale": repr(2.0 ** bits)}))


def delta():
    pairs = [("<i4", "<i4"), ("<i4", "<i2"), ("<i2", "<i1"), ("<u2", "<u1"), ("|i1", "<i2"), ("|u1", "<i2"),
             (">i4", ">i4"), ("<i4", ">i2"), ("<i8", "<i8"), ("<u8", "<u8"), ("<u8", "<i8"), ("<i8", "<u8"),
             ("<u4", "<i4"), ("<f4", "<f4"), ("<f8", "<f8"), ("<f8", "<f4"), ("<f4", "<f8"), ("<f2", "<f2"),
             ("<f2", "<f4"), (">f8", ">f8"), ("<f8", "<i2"), ("<i2", "<f4"), ("<i4", "<f2"), ("<f4", "<i2"),
             ("<u2", "<f8"), ("<i8", "<f8")]
    for dtype, astype in pairs:
        config = {"id": "delta", "dtype": dtype, "astype": astype}
        case(config, values(dtype))                                  # extremes: wraps, NaN, infinities
        base = np.cumsum(small("<i8", -3, 3, 32)).astype("f8") / (4 if np.dtype(dtype).kind == "f" else 1)
        case(config, (base + 50).astype(dtype))                      # a smooth run, the usual use
        case(config, np.array([], dtype=dtype))
        case(config, small(dtype, 0, 100, 1))
        # decode-only: stored differences that overflow the sum
        enc = values(astype, 16)
        case(config, None, enc.tobytes())
    # no astype: the dtype's own
    case({"id": "delta", "dtype": "<i4"}, values("<i4"))
    # a first element that does not fit astype: numcodecs refuses it
    case({"id": "delta", "dtype": "<i4", "astype": "|i1"}, np.array([1000, 1001, 1003], dtype="<i4"))
    case({"id": "delta", "dtype": "<i4", "astype": "|i1"}, np.array([100, 1001, 1003], dtype="<i4"))
    case({"id": "delta", "dtype": "<u8", "astype": "<i8"}, np.array([2**63 + 3, 5], dtype="<u8"))
    case({"id": "delta", "dtype": "<f8", "astype": "<i2"}, np.array([1.5, 2.75, -3.25, 1e6], dtype="<f8"))


def fixedscaleoffset():
    configs = [
        (1000, 10, "<f8", "|u1", (1000, 1025)),
        (1000, 100, "<f8", "|u1", (1000, 1002.5)),
        (1000, 1000, "<f8", "<u2", (1000, 1060)),
        (1000.5, 10.0, "<f8", "<i2", (0, 3000)),
        (0.1, 3.0, "<f4", "<i2", (-1000, 1000)),
        (-5, 0.5, "<f4", "<i4", (-1000, 1000)),
        (0, 1, "<f8", "<i4", (-10, 10)),
        (0, 1, "<f4", "<i4", (-10, 10)),
        (0.0, 1.0, "<f8", "<i4", (-10, 10)),
        (100, 2, "<i4", "<i2", (-40000, 40000)),
        (100.5, 2, "<i4", "<i4", (-1000, 1000)),
        (3, 7, "<u2", "<u2", (0, 65535)),
        (-3, 2.5, ">i2", ">i4", (-30000, 30000)),
        (1, 10, "<f2", "<i2", (-100, 100)),
        (0.1, 100.0, "<f2", "<i4", (-100, 100)),
        (0, 1, "<f8", "<f4", (-1e6, 1e6)),
        (2.5, 0.3, ">f8", ">f4", (-1e3, 1e3)),
        (1000, 1, "|i1", "|i1", (-100, 100)),     # an offset beyond int8: numcodecs refuses to encode
        (7, 3, "|u1", "<u2", (0, 255)),
        (1, 3, "<i8", "<i8", (-2**40, 2**40)),
        (1, 3.0, "<u8", "<f8", (0, 2**40)),
        (12, 1e-3, "<f8", "<i8", (-1e3, 1e3)),
    ]
    for offset, scale, dtype, astype, (lo, hi) in configs:
        config = {"id": "fixedscaleoffset", "offset": offset, "scale": scale, "dtype": dtype, "astype": astype}
        case(config, small(dtype, lo, hi, 40))
        case(config, np.array([], dtype=dtype))
        case(config, None, small(astype, 0, 100, 16).tobytes())  # decode-only
    # floats with NaN and infinities stored as float
    case({"id": "fixedscaleoffset", "offset": 1.5, "scale": 4, "dtype": "<f8", "astype": "<f4"}, values("<f8"))
    case({"id": "fixedscaleoffset", "offset": 1, "scale": 4, "dtype": "<f2", "astype": "<f2"}, values("<f2"))
    # no astype
    case({"id": "fixedscaleoffset", "offset": 10, "scale": 2, "dtype": "<f4"}, small("<f4", -50, 50))


def quantize():
    for dtype in ["<f2", "<f4", "<f8", ">f4", ">f8"]:
        for digits in [0, 1, 3, 6, -1]:
            for astype in sorted({dtype, "<f8", "<f2"}):
                if digits >= 5 and astype == "<f2":
                    continue
                config = {"id": "quantize", "digits": digits, "dtype": dtype, "astype": astype}
                data = values(dtype, 20)
                if np.dtype(dtype).kind == "f" and np.dtype(dtype).itemsize > 2:
                    data = np.concatenate([data, (rng.uniform(-1, 1, 12)).astype(dtype)]).astype(dtype)
                case(config, data)
    case({"id": "quantize", "digits": 3, "dtype": "<f8"}, values("<f8"))
    case({"id": "quantize", "digits": 2, "dtype": "<f4", "astype": "<f2"}, None, values("<f2", 12).tobytes())


def bitround():
    for dtype, bits in [("<f2", 10), ("<f4", 23), ("<f8", 52), (">f4", 23), (">f8", 52)]:
        for keepbits in sorted({0, 1, 2, 5, bits // 2, bits - 1, bits, bits + 1}):
            config = {"id": "bitround", "keepbits": keepbits}
            data = values(dtype, 20)
            data = np.concatenate([data, rng.standard_normal(12).astype(dtype)]).astype(dtype)
            case(config, data)
    case({"id": "bitround", "keepbits": 3}, values("<i4"))  # not floats: numcodecs refuses
    case({"id": "bitround", "keepbits": 3}, None, values("<f4", 8).tobytes())


def astype():
    pairs = [("<i2", "<i4"), ("|i1", "<i8"), ("|u1", "<i4"), ("<u2", "<i2"), ("<f4", "<f8"), ("<f2", "<f8"),
             ("<f2", "<f4"), ("<i4", "<f8"), ("<i2", "<f4"), ("|u1", "<f4"), ("|b1", "<i4"), ("|b1", "<f8"),
             ("<i4", "|b1"), ("<f8", "<f2"), ("<f8", "<f4"), ("<i8", "<f8"), ("<u8", "<f8"), ("<f4", "<i8"),
             ("<f8", "<u8"), ("<f4", "<u8"), ("<f2", "<i4"), (">i4", "<i4"), ("<i4", ">i4"), (">f8", "<f4"),
             ("<i8", "<f4"), ("<u8", "<f4"), ("<i4", "<f2"), ("<u4", "<u2"), ("<f4", "<f2")]
    for encode_dtype, decode_dtype in pairs:
        config = {"id": "astype", "encode_dtype": encode_dtype, "decode_dtype": decode_dtype}
        case(config, values(decode_dtype))
        case(config, None, values(encode_dtype, 16).tobytes())
        case(config, np.array([], dtype=decode_dtype))
    big = np.array([2.0**63, 2.0**64 - 2048, 1.0, 0.0, 9007199254740993.0], dtype="<f8")
    case({"id": "astype", "encode_dtype": "<u8", "decode_dtype": "<f8"}, big)
    ints = np.array([2**63 + 1, 2**64 - 1, 2**53 + 1, 16777217, 3], dtype="<u8")
    case({"id": "astype", "encode_dtype": "<f4", "decode_dtype": "<u8"}, ints)
    case({"id": "astype", "encode_dtype": "<f8", "decode_dtype": "<u8"}, ints)
    case({"id": "astype", "encode_dtype": "<f2", "decode_dtype": "<f8"},
         np.array([1 + 2.0**-11 + 2.0**-40, 65519.99, 65520.0, 6e-8, 2.0**-25, 2.0**-25 + 2.0**-60], dtype="<f8"))


def packbits():
    for n in [0, 1, 7, 8, 9, 17, 64, 100]:
        case({"id": "packbits"}, rng.integers(0, 2, n).astype(bool))
    for enc in [b"\x00", b"\x03\xff", b"\x07\x80", b"\x00\xa5\x5a", b"\x09\xff", b"\x10\xff\x00", b"\xff\x12"]:
        case({"id": "packbits"}, None, enc)
    case({"id": "packbits"}, None, b"")


def shuffle():
    for size in [1, 2, 3, 4, 8, 16]:
        for n in [0, 1, 5, 32]:
            case({"id": "shuffle", "elementsize": size}, rng.integers(0, 256, n * size).astype("u1"))
    case({"id": "shuffle", "elementsize": 4}, rng.integers(0, 256, 10).astype("u1"))  # not whole elements
    case({"id": "shuffle", "elementsize": 4}, values("<f4"))
    case({"id": "shuffle"}, rng.integers(0, 256, 40).astype("u1"))
    case({"id": "shuffle", "elementsize": 0}, rng.integers(0, 256, 7).astype("u1"))


def checksums():
    lengths = [0, 1, 2, 3, 4, 5, 11, 12, 13, 23, 24, 25, 100]
    configs = [{"id": "crc32"}, {"id": "crc32", "location": "end"}, {"id": "adler32"},
               {"id": "adler32", "location": "end"}, {"id": "crc32c"}, {"id": "crc32c", "location": "start"},
               {"id": "fletcher32"}, {"id": "jenkins_lookup3", "initval": 0, "prefix": None},
               {"id": "jenkins_lookup3", "initval": 12345, "prefix": None},
               {"id": "jenkins_lookup3", "initval": 4294967295, "prefix": None}]
    for config in configs:
        for n in lengths:
            case(config, rng.integers(0, 256, n).astype("u1"))
        # corrupt: a flipped bit fails the check
        enc = ensure_bytes(numcodecs.get_codec(dict(config)).encode(rng.integers(0, 256, 9).astype("u1")))
        bad = bytearray(enc)
        bad[len(bad) // 2] ^= 0x10
        case(config, None, bytes(bad))
        case(config, None, b"\x01\x02")  # shorter than a checksum
    case({"id": "crc32"}, values("<i4"))
    # Fletcher-32 sums 360 words before folding; all-ones data makes its sums wrap
    for n in [719, 720, 721, 1441, 3000]:
        data = rng.integers(0, 256, n).astype("u1")
        if n == 3000:
            data[:] = 0xff
        case({"id": "fletcher32"}, data)


def v3_fixture():
    name = "numcodecs_v3_bytes"
    path = os.path.join(FIXTURES, name)
    shutil.rmtree(path, ignore_errors=True)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")  # zarr-python warns that numcodecs codecs are not yet stable in v3
        array = zarr.create_array(path, shape=(5, 4), chunks=(3, 4), dtype="<i4", fill_value=-1, compressors=[
            zarr_numcodecs.Shuffle(elementsize=4), zarr_numcodecs.CRC32(location="end"), zarr_numcodecs.Adler32(),
            zarr_numcodecs.Fletcher32(), zarr_numcodecs.JenkinsLookup3(initval=7), zarr_numcodecs.CRC32C()])
        values = (np.arange(20, dtype="<i4") * 1000 - 7).reshape(5, 4)
        array[:] = values
        assert np.array_equal(zarr.open_array(path)[:], values)
    with open(os.path.join(FIXTURES, name + ".expected.json"), "w", newline="\n") as f:
        json.dump({"name": name, "shape": [5, 4], "dtype": "int32", "values": values.ravel().tolist()}, f, indent=1)


def main():
    tables()
    delta()
    fixedscaleoffset()
    quantize()
    bitround()
    astype()
    packbits()
    shuffle()
    checksums()
    v3_fixture()
    with open(OUT, "w", newline="\n") as f:
        f.write("\n".join(LINES) + "\n")
    print(f"wrote {len(LINES)} lines, {os.path.getsize(OUT)} bytes to {OUT}")


if __name__ == "__main__":
    main()
