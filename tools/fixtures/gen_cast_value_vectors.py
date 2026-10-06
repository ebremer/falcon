"""Reference vectors for Falcon's cast_value codec, straight from cast-value-rs, the Rust backend zarr-python's
zarr.codecs.CastValue calls (cast_value_rs.cast_array).

Dev-time tool only -- cast-value-rs, numpy, and zarr are NOT Falcon dependencies. Install with
pip install -r tools/fixtures/requirements.txt (cast-value-rs==0.4.2). Run from the repo root:

    python tools/fixtures/gen_cast_value_vectors.py

Every source x target pair of the eleven data types Falcon casts (int8 to uint64, float16, float32, float64)
under every rounding mode, every out_of_range mode (absent, clamp, wrap), without and with a scalar_map, over
inputs chosen to hit every rule: signed zeros, NaNs with payloads, the infinities, subnormals, the bounds of
every integer type and the values just past them, ties at .5, the f32 and f16 overflow thresholds and
rounding ties (and the values where cast-value-rs rounds f64 to f16 twice), huge magnitudes, and seeded random
values. Each element is cast alone, so an error on one does not hide the rest; the whole array is cast too
and must agree wherever no element fails. A float to float cast with wrap is left out: cast_value permits
wrap only for an integer target, and zarr-python and Falcon both refuse it in either direction.

Writes zarr/src/test/resources/fixtures/cast_value_vectors.txt, space-separated lines:

    I <src> <bits> <bits> ...                      the inputs for source type <src>, element bits in hex
    M <src> <dst> <json>                           the scalar map for the pair: [[key, value], ...] in the
                                                   Zarr v3 fill value encoding of <src> and <dst>
    C <src> <dst> <rounding> <oor> <map> <out> ... one configuration's result for each input, in order

<rounding> is ne, tz, tp, tn, na (nearest-even, towards-zero, towards-positive, towards-negative,
nearest-away); <oor> is - (absent), c (clamp), w (wrap); <map> is 0 or 1 (the pair's M line). An <out> is
the result's bits in hex, or !n (cast-value-rs's NanOrInf error: a NaN or infinity an integer cannot hold)
or !r (OutOfRange: out of range and no out_of_range mode).

The scalar maps repeat a key with another value: cast-value-rs, given a list of pairs, takes the first,
as the cast_value specification requires (zarr-python's dict keeps the last).
"""
import json
import os
import struct

import numpy as np
from cast_value_rs import cast_array

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures", "cast_value_vectors.txt")

INTS = ["int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64"]
FLOATS = ["float16", "float32", "float64"]
TYPES = INTS + FLOATS
ROUNDING = {"ne": "nearest-even", "tz": "towards-zero", "tp": "towards-positive", "tn": "towards-negative",
            "na": "nearest-away"}
OOR = {"-": None, "c": "clamp", "w": "wrap"}
UINT = {"float16": np.uint16, "float32": np.uint32, "float64": np.uint64}

rng = np.random.default_rng(20261006)


def bits_of(a):
    """Each element's bits as an unsigned integer."""
    a = np.ascontiguousarray(a)
    return [int(v) for v in a.view(np.dtype(f"u{a.dtype.itemsize}"))]


def from_bits(dtype, bits):
    return np.array(bits, dtype=np.dtype(f"u{np.dtype(dtype).itemsize}")).view(dtype)


def f64(x):
    return struct.unpack("<d", struct.pack("<Q", x))[0]


# ---- inputs ------------------------------------------------------------------------------------------

def float_inputs():
    """Edge values as Python floats (f64); each float type takes them through numpy's correctly rounded cast,
    plus its own specials (bit patterns)."""
    v = [0.0, -0.0, 1.0, -1.0, 0.1, -0.1, 1e300, -1e300, 1e20, -1e20, 1e-300, 5e-324, -5e-324,
         f64(0x000FFFFFFFFFFFFF), 2.2250738585072014e-308, 1.7976931348623157e308, -1.7976931348623157e308,
         0.49999999999999994, -0.49999999999999994, 4503599627370495.5, 2.0 ** 52 + 1, 2.0 ** 53 + 2]
    for t in [0.5, 1.5, 2.5, 3.5, 126.5, 127.5, 128.5, 254.5, 255.5, 256.5, 32767.5, 32768.5, 65535.5]:
        v += [t, -t]
    # every integer type's bounds, and just past them
    for name in INTS:
        info = np.iinfo(name)
        for b in (float(info.min), float(info.max)):
            v += [b, b - 1, b + 1, b - 0.4, b + 0.4, b - 0.5, b + 0.5, b - 0.6, b + 0.6]
            v += [np.nextafter(b, np.inf), np.nextafter(b, -np.inf)]
    v += [2.0 ** 31, 2.0 ** 32, 2.0 ** 63, 2.0 ** 64, 2.0 ** 65, -(2.0 ** 63), -(2.0 ** 64), -(2.0 ** 65),
          2.0 ** 63 - 1024, 2.0 ** 64 - 2048, 2.0 ** 31 - 128, 2.0 ** 32 - 256, 3e9, -3e9, 5e18, 1.8e19, 4e19,
          2.0 ** 80, -(2.0 ** 80) + 3 * 2.0 ** 28]
    # float32: its largest value, the overflow threshold (a tie that rounds to infinity), subnormals, ties
    m32 = float(np.finfo(np.float32).max)
    half_ulp = 2.0 ** 103
    v += [m32, m32 + half_ulp, m32 + half_ulp - 2.0 ** 75, m32 + 2.0 ** 75, 2.0 ** 128, -m32, -(m32 + half_ulp),
          2.0 ** -149, 2.0 ** -150, 2.0 ** -150 + 2.0 ** -200, 3 * 2.0 ** -150, 2.0 ** -126, 2.0 ** -126 - 2.0 ** -150,
          1 + 2.0 ** -24, 1 + 3 * 2.0 ** -24, 1 + 2.0 ** -24 + 2.0 ** -50, 1 - 2.0 ** -25, 16777217.0,
          -(2.0 ** -149), -(2.0 ** -150), -(1 + 2.0 ** -24)]
    # float16: 65504, the overflow threshold 65520 (a tie that rounds to infinity), subnormals, ties, and the
    # values where f64 -> f32 -> f16 rounds twice and lands elsewhere than f64 -> f16 would
    v += [65504.0, 65519.0, 65519.99, 65520.0, 65520.0 - 2.0 ** -37, 65536.0, 70000.0, 1e5, -65504.0, -65520.0,
          2.0 ** -24, 2.0 ** -25, 2.0 ** -25 + 2.0 ** -60, 1.5 * 2.0 ** -24, 2.0 ** -14, 2.0 ** -14 - 2.0 ** -24,
          1 + 2.0 ** -11, 1 + 3 * 2.0 ** -11, 1 + 2.0 ** -11 + 2.0 ** -40, 1 + 2.0 ** -11 + 2.0 ** -30,
          1 + 2.0 ** -11 + 2.0 ** -20, -(1 + 2.0 ** -11 + 2.0 ** -40), 2049.0, 2050.0, 2051.0, 4097.0, 32769.0,
          2.0 ** -24 * (1 + 2.0 ** -30) * 0.5, 0.1 + 2.0 ** -40, 1024.5, 2047.5, 2048.5]
    # random: around zero, across magnitudes, and raw bit patterns
    v += list(rng.uniform(-300, 300, 24))
    v += list(rng.choice([-1, 1], 30) * 10.0 ** rng.uniform(-12, 25, 30))
    v += [f64(int(b)) for b in rng.integers(0, 2 ** 63, 12, dtype=np.uint64) | np.uint64(0)]
    return [float(x) for x in v]


def nan_bits(dtype):
    return {
        "float64": [0x7FF8000000000000, 0xFFF8000000000000, 0x7FF0000000000001, 0x7FF4000000000001,
                    0x7FFFFFFFFFFFFFFF, 0x7FF8000020000000, 0xFFF0000000000001],
        "float32": [0x7FC00000, 0xFFC00000, 0x7F800001, 0x7FA00001, 0x7FFFFFFF, 0x7FC02000, 0xFF800001],
        "float16": [0x7E00, 0xFE00, 0x7C01, 0x7D01, 0x7FFF, 0xFC01],
    }[dtype]


def inputs(dtype):
    dt = np.dtype(dtype)
    if dtype in FLOATS:
        base = float_inputs() + [np.inf, -np.inf]
        with np.errstate(all="ignore"):
            values = np.array(base, dtype=np.float64).astype(dt)
        bits = bits_of(values) + nan_bits(dtype)
        if dtype == "float32":
            bits += [0x00000001, 0x80000001, 0x007FFFFF, 0x00800000, 0x7F7FFFFF, 0xFF7FFFFF, 0x4B000001,
                     0x4F000000, 0x4EFFFFFF, 0x4F800000, 0x4F7FFFFF, 0x5F000000, 0x5EFFFFFF, 0x5F800000,
                     0x477FDFFF, 0x477FE000, 0x477FF000, 0x3F801000, 0x3F801001, 0x3F803000, 0x33800000,
                     0x33000000, 0x33000001, 0x33C00000, 0x387FE000, 0x387FF000]
            bits += [int(b) for b in rng.integers(0, 2 ** 32, 24, dtype=np.uint64)]
        if dtype == "float16":
            # every pattern of the smallest subnormals, the subnormal/normal boundary, the values around 1
            # and around the integer bounds, the largest finite values, and random patterns
            ranges = [(0x0000, 0x0012), (0x03F8, 0x0408), (0x3800, 0x3812), (0x3BF8, 0x3C08), (0x3DF8, 0x3E08),
                      (0x57F0, 0x5808), (0x5BF0, 0x5C08), (0x77F8, 0x7808), (0x7BF0, 0x7C01), (0x6400, 0x6404),
                      (0x67FC, 0x6804), (0x7400, 0x7404)]
            for lo, hi in ranges:
                bits += list(range(lo, hi))
                bits += [b | 0x8000 for b in range(lo, hi)]
            bits += [int(b) for b in rng.integers(0, 2 ** 16, 120)]
        seen = set()
        out = []
        for b in bits:
            if b not in seen:
                seen.add(b)
                out.append(b)
        return out
    info = np.iinfo(dt)
    cand = [0, 1, -1, 2, 3, 7, -7, 127, 128, 129, 255, 256, -128, -129, -255, -256, 32767, 32768, 65535, 65536,
            -32768, -32769, -65535, 2 ** 31 - 1, 2 ** 31, 2 ** 32 - 1, 2 ** 32, -(2 ** 31), -(2 ** 31) - 1,
            2049, 2051, 4097, 4099, 65504, 65505, 65519, 65520, 65521, -65504, -65519, -65520, -65521,
            2 ** 24, 2 ** 24 + 1, 2 ** 24 + 2, 2 ** 24 + 3, 2 ** 25 + 2, 2 ** 25 + 6, -(2 ** 24 + 1),
            2 ** 53, 2 ** 53 + 1, 2 ** 53 + 3, -(2 ** 53 + 1), 2 ** 60 + 2 ** 36, 2 ** 60 + 2 ** 36 + 1,
            2 ** 60 + 3 * 2 ** 36, 2 ** 62 + 1, 2 ** 63 - 1, 2 ** 63 - 2 ** 10, 2 ** 63 - 2 ** 9,
            -(2 ** 63), -(2 ** 63) + 1, -(2 ** 60 + 2 ** 36), 2 ** 63, 2 ** 63 + 1, 2 ** 63 + 2 ** 10,
            2 ** 63 + 2 ** 11 - 1, 2 ** 63 + 2 ** 39, 2 ** 64 - 1, 2 ** 64 - 2 ** 10, 2 ** 64 - 2 ** 11,
            9007199254740993, info.min, info.min + 1, info.max, info.max - 1]
    for bitlen in (5, 9, 14, 20, 28, 35, 45, 54, 62, 64):
        for _ in range(3):
            x = int(rng.integers(0, 2 ** 62)) % (2 ** bitlen) if bitlen < 64 else (
                int(rng.integers(0, 2 ** 32)) << 32 | int(rng.integers(0, 2 ** 32)))
            cand += [x, -x]
    out = []
    for x in cand:
        if info.min <= x <= info.max and x not in out:
            out.append(x)
    return [bits_of(np.array([x], dtype=dt))[0] for x in out]


# ---- scalar maps -------------------------------------------------------------------------------------

def json_scalar(dtype, x):
    """x (a Python number) in the Zarr v3 fill value encoding of dtype."""
    if dtype in FLOATS:
        if x != x:
            return "NaN"
        if x == float("inf"):
            return "Infinity"
        if x == float("-inf"):
            return "-Infinity"
        return float(x)
    return int(x)


def scalar_map(src, dst):
    """[(key, value), ...] as Python numbers: a repeated key (whose first value must win), keys at the type's
    extremes, and, for a float source, NaN and the infinities."""
    if src in FLOATS:
        keys = [float("nan"), float("inf"), float("-inf"), 0.0, 1.5, 1.5]
    else:
        info = np.iinfo(src)
        keys = [int(info.min), int(info.max), 0, 7, 7]
    if dst in FLOATS:
        values = [-1.0, float("nan"), float("inf"), 0.25, -0.0, 2.0]
    else:
        info = np.iinfo(dst)
        values = [int(info.max), int(info.min), 42, 1, 5, 6]
    return list(zip(keys, values))


# ---- casting -----------------------------------------------------------------------------------------

def cast_one(src, dst, bits, rounding, oor, entries):
    a = from_bits(src, [bits])
    try:
        out = cast_array(a, target_dtype=dst, rounding_mode=rounding, out_of_range_mode=oor,
                         scalar_map_entries=entries)
    except ValueError as e:
        msg = str(e)
        if "out of range" in msg:
            return "!r"
        if "Cannot cast" in msg:
            return "!n"
        raise
    return format(bits_of(out)[0], "x")


def main():
    lines = []
    total = 0
    inputs_by_type = {t: inputs(t) for t in TYPES}
    for src in TYPES:
        lines.append("I " + src + " " + " ".join(format(b, "x") for b in inputs_by_type[src]))
    for src in TYPES:
        values = inputs_by_type[src]
        for dst in TYPES:
            entries = scalar_map(src, dst)
            pairs = [[json_scalar(src, k), json_scalar(dst, v)] for k, v in entries]
            lines.append(f"M {src} {dst} " + json.dumps(pairs, separators=(",", ":")))
            to_src = float if src in FLOATS else int
            to_dst = float if dst in FLOATS else int
            rust_entries = [(to_src(k), to_dst(v)) for k, v in entries]
            for r_key, rounding in ROUNDING.items():
                for o_key, oor in OOR.items():
                    if oor == "wrap" and src in FLOATS and dst in FLOATS:
                        continue
                    for use_map in (0, 1):
                        m = rust_entries if use_map else None
                        outs = [cast_one(src, dst, b, rounding, oor, m) for b in values]
                        # the whole array, through cast-value-rs' slice kernels (SIMD for some pairs)
                        if not any(o.startswith("!") for o in outs):
                            whole = cast_array(from_bits(src, values), target_dtype=dst, rounding_mode=rounding,
                                               out_of_range_mode=oor, scalar_map_entries=m)
                            assert [format(b, "x") for b in bits_of(whole)] == outs, (src, dst, rounding, oor)
                        lines.append(f"C {src} {dst} {r_key} {o_key} {use_map} " + " ".join(outs))
                        total += len(outs)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{total} vectors over {sum(len(v) for v in inputs_by_type.values())} inputs -> {OUT}")


if __name__ == "__main__":
    main()
