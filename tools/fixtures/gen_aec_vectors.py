#!/usr/bin/env python3
"""Regenerate Falcon's AEC / szip reference vectors from libaec (via imagecodecs) — the same
extended-Rice library HDF5's szip filter uses.

    pip install imagecodecs      # bundles libaec
    python tools/fixtures/gen_aec_vectors.py

Writes two hermetic text files under hdf5/src/test/resources/fixtures/ (parsed by the Java tests
with no JSON dependency):

  aec_vectors.txt   core AEC decoder vectors:  bpp blocksize rsi flags enc-hex values
                    (flags: 4=MSB, 12=MSB|PREPROCESS; values are the big-endian sample interpretation)
  szip_chunks.txt   szip filter vectors (SZ_LSB|SZ_NN, unsigned):
                    mask ppb bpp pps enc-hex raw-le-bytes-hex

This is a dev-time tool (like the h5py fixture generator); the committed vectors make the tests
self-contained, so imagecodecs is not needed to build or run Falcon.
"""
import os
import numpy as np
import imagecodecs

FIX = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..",
                                   "hdf5", "src", "test", "resources", "fixtures"))
MSB, PRE = 4, 8
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


def gen_szip():
    def chunk(arr, bpp, ppb, rsi):
        a = np.ascontiguousarray(arr)
        enc = bytes(imagecodecs.aec_encode(a, bitspersample=bpp, blocksize=ppb, rsi=rsi, flags=PRE))  # LSB+NN
        return f"{SZ_LSB | SZ_NN} {ppb} {bpp} {rsi * ppb} {enc.hex()} {a.tobytes().hex()}"

    lines = ["# szip chunk vectors (SZ_LSB|SZ_NN, unsigned). Fields: mask ppb bpp pps enc-hex raw-le-bytes-hex"]
    lines.append(chunk(np.arange(64, dtype="<u4"), 32, 32, 2))
    lines.append(chunk((np.arange(48, dtype="<u2") * 7).astype("<u2"), 16, 16, 2))
    lines.append(chunk(np.array([5, 5, 6, 4, 5, 7, 3, 5] * 8, dtype="<u4"), 32, 8, 4))
    lines.append(chunk(rng.integers(0, 1000, 100).astype("<u4"), 32, 16, 4))
    lines.append(chunk(np.arange(40, dtype="<u1"), 8, 8, 2))
    return lines


def main():
    os.makedirs(FIX, exist_ok=True)
    for name, lines in (("aec_vectors.txt", gen_core()), ("szip_chunks.txt", gen_szip())):
        with open(os.path.join(FIX, name), "w", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print(f"wrote {name}")
    print("imagecodecs", imagecodecs.__version__)


if __name__ == "__main__":
    main()
