"""Reference vectors for Falcon Core's SZ 2 decoder, from libSZ (SZ 2.1.12, hdf5plugin's H5Z-SZ filter).

Each case writes one chunk through the HDF5 filter (32017), closes the file, reopens it and reads the stored
bytes (read_direct_chunk) and libSZ's decoding of them -- in a new session, because libhdf5 serves the
writing session's chunks from its cache, unfiltered, and SZ is lossy. Dev-time tool (h5py, hdf5plugin);
neither is a Falcon dependency.

Writes core/src/test/resources/fixtures/sz_vectors.txt:
  name sz-data-type r5 r4 r3 r2 r1 stream-hex decoded-hex
with the dimensions as H5Z-SZ hands them to SZ_decompress (r1 varies fastest) and the decoded values
little-endian.

Usage (from the repo root):  python tools/fixtures/gen_sz_vectors.py
"""
import io
import os
import struct

import h5py
import hdf5plugin  # noqa: F401  (registers the filter)
import numpy as np

OUT = os.path.join("core", "src", "test", "resources", "fixtures")
SZ_TYPES = {"f4": 0, "f8": 1, "u1": 2, "i1": 3, "u2": 4, "i2": 5, "u4": 6, "i4": 7, "u8": 8, "i8": 9}


def opts(mode, absolute=0.0, relative=0.0, pointwise=0.0, psnr=0.0):
    """H5Z-SZ's nine client-data values: the error-bound mode, then four doubles as (high, low) words."""
    words = []
    for value in (absolute, relative, pointwise, psnr):
        words += struct.unpack(">II", struct.pack(">d", value))
    return (mode, *words)


def dims(cd):
    """SZ_cdArrayToMetaData: (r5, r4, r3, r2, r1) from H5Z-SZ's client data."""
    n = cd[0]
    if n == 1:
        return 0, 0, 0, 0, (cd[2] << 32) | cd[3]
    if n == 2:
        return 0, 0, 0, cd[3], cd[2]
    if n == 3:
        return 0, 0, cd[4], cd[3], cd[2]
    if n == 4:
        return 0, cd[5], cd[4], cd[3], cd[2]
    return cd[6], cd[5], cd[4], cd[3], cd[2]


def smooth(shape, scale=3.0):
    idx = np.indices(shape).astype("f8")
    v = np.zeros(shape)
    for k, ax in enumerate(idx):
        v = v + np.sin(ax * (0.11 + 0.07 * k) + k)
    return v * scale + 0.5


def cases():
    rng = np.random.default_rng(2112)
    shapes = {1: (300,), 2: (24, 21), 3: (9, 10, 11), 4: (2, 3, 7, 8)}
    modes = {"abs": opts(0, absolute=1e-3), "rel": opts(1, relative=1e-3), "absrel": opts(2, absolute=1e-2, relative=1e-3),
             "psnr": opts(4, psnr=70.0), "pwr": opts(10, pointwise=1e-2), "pwr_log": opts(10, pointwise=1e-6),
             "abs_pwr": opts(11, absolute=1e-3, pointwise=1e-2), "tight": opts(0, absolute=1e-8)}
    for dt in ("f4", "f8"):
        for nd, shape in shapes.items():
            for mode, o in modes.items():
                if mode in ("pwr", "pwr_log", "abs_pwr"):
                    data = smooth(shape) * 10.0 ** rng.integers(-3, 3, shape)
                    data[rng.random(shape) < 0.1] = 0  # signs and zeros
                elif mode == "tight":
                    data = rng.normal(0, 1, shape)  # stored as it is: SZ cannot shrink it
                else:
                    data = smooth(shape)
                yield f"{dt}_{nd}d_{mode}", data.astype("<" + dt), o
            yield f"{dt}_{nd}d_constant", np.full(shape, -1.5, "<" + dt), modes["abs"]
        # 3-D data around a dense value (zeros; noise about 0): the regression format's mean predictor
        idx = np.indices((9, 10, 11)).astype("f8")
        mixed = sum(np.cos(ax * (0.13 + 0.05 * k)) for k, ax in enumerate(idx)) * 10.0 ** rng.integers(-3, 3, (9, 10, 11))
        mixed[rng.random((9, 10, 11)) < 0.1] = 0
        yield f"{dt}_3d_mean_rel", mixed.astype("<" + dt), modes["rel"]
        sparse = smooth((9, 10, 11)) * 0.01
        sparse[rng.random((9, 10, 11)) < 0.8] = 0
        yield f"{dt}_3d_mean", sparse.astype("<" + dt), opts(0, absolute=1e-3)
    for dt in ("u1", "i1", "u2", "i2", "u4", "i4", "u8", "i8"):
        info = np.iinfo(dt)
        span = min(float(info.max) - float(info.min), 2e9)
        mid = (float(info.min) + float(info.max)) / 2 if dt[0] == "u" else 0.0
        for nd, shape in shapes.items():
            v = smooth(shape)
            v = (v - v.min()) / np.ptp(v)
            data = np.clip(np.round((v - 0.5) * span * 0.6 + mid), float(info.min), float(info.max)).astype("<" + dt)
            yield f"{dt}_{nd}d_abs", data, opts(0, absolute=3)
        yield f"{dt}_2d_rel", data.reshape(42, 8), opts(1, relative=1e-3)
        yield f"{dt}_1d_constant", np.full((50,), 7, "<" + dt), opts(0, absolute=1)


def vector(name, data, o):
    buf = io.BytesIO()
    with h5py.File(buf, "w") as f:
        f.create_dataset("d", data=data, chunks=data.shape, compression=32017, compression_opts=o)
    with h5py.File(buf, "r") as f:  # a new session: libSZ's decoding, not the cached input
        d = f["d"]
        cd = d.id.get_create_plist().get_filter(0)[2]
        mask, raw = d.id.read_direct_chunk((0,) * d.ndim)
        decoded = d[()]
    assert mask == 0, name
    return f"{name} {SZ_TYPES[data.dtype.str[1:]]} {' '.join(map(str, dims(cd)))} {bytes(raw).hex()} {decoded.tobytes().hex()}"


def main():
    lines = ["# name sz-data-type r5 r4 r3 r2 r1 stream-hex decoded-hex",
             "# generated by tools/fixtures/gen_sz_vectors.py (hdf5plugin's H5Z-SZ, SZ 2.1.12)"]
    lines += [vector(*case) for case in cases()]
    with open(os.path.join(OUT, "sz_vectors.txt"), "w", newline="\n") as out:
        out.write("\n".join(lines) + "\n")
    print(len(lines) - 2, "vectors")


if __name__ == "__main__":
    main()
