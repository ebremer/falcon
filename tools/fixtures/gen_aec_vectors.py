#!/usr/bin/env python3
"""Regenerate Falcon's AEC / szip reference vectors from libaec (via imagecodecs) — the same
extended-Rice library HDF5's szip filter uses.

    pip install imagecodecs      # bundles libaec
    python tools/fixtures/gen_aec_vectors.py

Writes two hermetic text files under hdf5/src/test/resources/fixtures/ (parsed by the Java tests
with no JSON dependency):

  aec_vectors.txt     core AEC decoder vectors:  bpp blocksize rsi flags enc-hex values
                      (flags: 4=MSB, 12=MSB|PREPROCESS; values are the big-endian sample interpretation)
  aec_ros_vectors.txt zero-block "remainder of segment" vectors with rsi > 64 (same fields)
  szip_chunks.txt     real-format szip filter chunks, exactly as libhdf5 + libaec store them
                      (imagecodecs.szip_encode with header=True: a 4-byte size, then libaec's SZ
                      layer -- byte-interleaving for 32/64-bit pixels, padded scanlines):
                      mask ppb bpp pps enc-hex raw-bytes-hex

    python tools/fixtures/gen_aec_vectors.py szip ros   # regenerate only the named files

This is a dev-time tool (like the h5py fixture generator); the committed vectors make the tests
self-contained, so imagecodecs is not needed to build or run Falcon.
"""
import os
import sys
import numpy as np
import imagecodecs

FIX = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                   "hdf5", "src", "test", "resources", "fixtures"))
SIGNED, MSB, PRE = 1, 4, 8
SZ_LSB, SZ_NN = 8, 32
rng = np.random.default_rng(7)


def gen_core():
    configs = [("u1", 8, 8, 2), ("u1", 8, 16, 4), ("u1", 8, 32, 2),
               ("<u2", 16, 8, 2), ("<u2", 16, 16, 2),
               ("<u4", 32, 16, 2), ("<u4", 32, 32, 2)]
    patterns = {
        "zeros": lambda dt, m: np.zeros(40, dt),
        "const": lambda dt, m: np.full(40, min(7, m), dt),
        "ramp": lambda dt, m: (np.arange(40) % (m + 1)).astype(dt),
        "small": lambda dt, m: rng.integers(0, min(4, m + 1), 40).astype(dt),
        "mid": lambda dt, m: rng.integers(0, min(64, m + 1), 48).astype(dt),
        "rand": lambda dt, m: rng.integers(0, m + 1, 56, dtype="uint64").astype(dt),
    }
    lines = ["# AEC/CCSDS-121.0 reference vectors from libaec (imagecodecs).",
             "# Fields: bpp blocksize rsi flags enc-hex values  (flags 4=MSB, 12=MSB|PREPROCESS)"]
    for dt, bpp, bs, rsi in configs:
        m = (1 << bpp) - 1
        for gen in patterns.values():
            for flags in (MSB, MSB | PRE):
                arr = np.ascontiguousarray(gen(dt, m))
                enc = bytes(imagecodecs.aec_encode(arr, bitspersample=bpp, blocksize=bs, rsi=rsi, flags=flags))
                be = {8: ">u1", 16: ">u2", 32: ">u4"}[bpp]
                vals = np.frombuffer(arr.tobytes(), dtype=be).astype("int64").tolist()
                lines.append(f"{bpp} {bs} {rsi} {flags} {enc.hex()} {','.join(map(str, vals))}")
    return lines


def gen_signed():
    """Signed (libaec DATA_SIGNED) vectors: signed integer types, with and without preprocessing."""
    configs = [("<i1", 8, 8, 2), ("<i2", 16, 8, 2), ("<i2", 16, 16, 2), ("<i4", 32, 16, 2)]
    lines = ["# signed AEC vectors (DATA_SIGNED). Fields: bpp blocksize rsi flags enc-hex values"]
    for dt, bpp, bs, rsi in configs:
        m = 1 << (bpp - 1)
        patterns = [
            np.arange(-min(18, m), min(18, m)).astype(dt),
            rng.integers(-4, 5, 48).astype(dt),
            rng.integers(-min(60, m), min(60, m), 48).astype(dt),
            rng.integers(-m, m, 56, dtype="int64").astype(dt),
        ]
        be = {8: ">i1", 16: ">i2", 32: ">i4"}[bpp]
        for arr in patterns:
            arr = np.ascontiguousarray(arr)
            for flags in (SIGNED | MSB, SIGNED | MSB | PRE):
                enc = bytes(imagecodecs.aec_encode(arr, bitspersample=bpp, blocksize=bs, rsi=rsi, flags=flags))
                vals = np.frombuffer(arr.tobytes(), dtype=be).astype("int64").tolist()
                lines.append(f"{bpp} {bs} {rsi} {flags} {enc.hex()} {','.join(map(str, vals))}")
    return lines


# szip option-mask bits (szlib.h): libhdf5 always adds K13 and RAW, plus LSB/MSB for the byte order
SZ_K13, SZ_EC, SZ_MSB_ORDER, SZ_RAW = 1, 4, 16, 128


def gen_szip():
    def chunk(arr, ppb, pps, nn=True):
        a = np.ascontiguousarray(arr)
        bpp = a.dtype.itemsize * 8
        mask = SZ_K13 | SZ_RAW | (SZ_NN if nn else SZ_EC) | (SZ_MSB_ORDER if a.dtype.byteorder == ">" else SZ_LSB)
        enc = bytes(imagecodecs.szip_encode(a.tobytes(), options_mask=mask, pixels_per_block=ppb,
                                            bits_per_pixel=bpp, pixels_per_scanline=pps, header=True))
        return f"{mask} {ppb} {bpp} {pps} {enc.hex()} {a.tobytes().hex()}"

    lines = ["# real-format szip chunks (libaec SZ layer, 4-byte size header).",
             "# Fields: mask ppb bpp pps enc-hex raw-bytes-hex"]
    lines.append(chunk(np.arange(64, dtype="<u4"), 32, 64))                        # 32-bit: interleaved
    lines.append(chunk((np.arange(48, dtype="<u2") * 7).astype("<u2"), 16, 32))
    lines.append(chunk(np.array([5, 5, 6, 4, 5, 7, 3, 5] * 8, dtype="<u4"), 8, 32, nn=False))
    lines.append(chunk(rng.integers(0, 1000, 100).astype("<u4"), 16, 64))
    lines.append(chunk(np.arange(40, dtype="<u1"), 8, 16))
    lines.append(chunk((np.arange(100) * 3 % 997).astype("<u2"), 8, 20, nn=False))   # padded scanlines
    lines.append(chunk(np.cos(np.arange(256) / 9.0).astype("<f8"), 16, 128))       # 64-bit: interleaved
    lines.append(chunk((np.arange(64) * 11 - 300).astype(">i2"), 8, 16))           # big-endian (MSB)
    return lines


def gen_ros():
    """Zero runs that end on a 64-block segment boundary inside a longer reference-sample interval:
    libaec codes them as "remainder of segment", which stops at the segment, not the RSI."""
    lines = ["# zero-block remainder-of-segment vectors (rsi > 64). Fields: bpp blocksize rsi flags enc-hex values"]
    for bs, rsi in ((8, 128), (8, 100), (16, 128)):
        for flags in (MSB, MSB | PRE):
            n = rsi * bs * 2
            arr = rng.integers(1, 200, n).astype("u1")
            for start, stop in ((10, 64), (70, 128), (130, 192)):  # runs ending on 64-block boundaries
                fill = 0 if not flags & PRE else arr[start * bs - 1]  # a constant run under preprocessing
                arr[start * bs:min(stop * bs, n)] = fill
            enc = bytes(imagecodecs.aec_encode(arr, bitspersample=8, blocksize=bs, rsi=rsi, flags=flags))
            lines.append(f"8 {bs} {rsi} {flags} {enc.hex()} {','.join(map(str, arr.astype('int64').tolist()))}")
    return lines


def main():
    os.makedirs(FIX, exist_ok=True)
    files = {"core": ("aec_vectors.txt", lambda: gen_core() + gen_signed()),
             "szip": ("szip_chunks.txt", gen_szip),
             "ros": ("aec_ros_vectors.txt", gen_ros)}
    # The generators share one seeded RNG, so they always run in this order; only the selected
    # files (all by default) are written.
    selected = set(sys.argv[1:]) or set(files)
    for key, (name, gen) in files.items():
        lines = gen()
        if key not in selected:
            continue
        with open(os.path.join(FIX, name), "w", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print(f"wrote {name}")
    print("imagecodecs", imagecodecs.__version__)


if __name__ == "__main__":
    main()
