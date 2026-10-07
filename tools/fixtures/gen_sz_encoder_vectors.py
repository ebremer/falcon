"""Reference vectors for Falcon Core's SZ 2 encoder, from libSZ (SZ 2.1.12, hdf5plugin's H5Z-SZ filter).

Each case writes one chunk through the HDF5 filter (32017) from a fresh dataset (so H5Z-SZ's set_local has just
run SZ_Init, as for any dataset written once), closes the file, reopens it and reads the stored bytes
(read_direct_chunk). SZ's own bytes are what libSZ wrote before its lossless stage: the chunk undone by
zstd, or the chunk itself where SZ skips that stage (constant data; 20 values or fewer, stored as they are).
Falcon's zstd frames are its own, so the oracle is the bytes beneath them. Dev-time tool (h5py, hdf5plugin,
imagecodecs); none is a Falcon dependency.

libSZ ends the process (exit) on some inputs, so the cases run in worker processes; a case that kills its
worker is reported and left out.

Writes core/src/test/resources/fixtures/sz_encoder_vectors.txt, one case per line:
  name sz-data-type r5 r4 r3 r2 r1 mode abs-hex rel-hex pwr-hex input-hex sz-hex
with the dimensions as H5Z-SZ hands them to SZ_compress_args (r1 varies fastest), the error bounds as
big-endian doubles, the input little-endian, and sz-hex libSZ's bytes before its lossless stage.

Usage (from the repo root):  python tools/fixtures/gen_sz_encoder_vectors.py
"""
import io
import os
import struct
import subprocess
import sys

import h5py
import hdf5plugin  # noqa: F401  (registers the filter)
import imagecodecs
import numpy as np

OUT = os.path.join("core", "src", "test", "resources", "fixtures", "sz_encoder_vectors.txt")
SZ_TYPES = {"f4": 0, "f8": 1, "u1": 2, "i1": 3, "u2": 4, "i2": 5, "u4": 6, "i4": 7, "u8": 8, "i8": 9}
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


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


def smooth(shape, scale=3.0, offset=0.5, seed=0):
    idx = np.indices(shape).astype("f8")
    v = np.zeros(shape)
    for k, ax in enumerate(idx):
        v = v + np.sin(ax * (0.11 + 0.07 * k + 0.013 * seed) + k + seed)
    return v * scale + offset


def data_for(kind, shape, dt, rng):
    """Values of a kind: smooth fields (predictable), noise (SZ stores them), constants, sparse fields (the
    regression format's mean predictor), wide-ranging fields with zeros and signs (point-wise bounds)."""
    n = int(np.prod(shape))
    seed = int(rng.integers(0, 50))
    if kind == "smooth":
        v = smooth(shape, scale=float(rng.choice([0.5, 3.0, 100.0])), offset=float(rng.normal(0, 5)), seed=seed)
    elif kind == "ramp":
        v = np.arange(n, dtype="f8").reshape(shape) * float(rng.choice([0.01, 1.0, 3.5])) - n / 3
    elif kind == "noisy":
        v = smooth(shape, seed=seed) + rng.normal(0, float(rng.choice([1e-3, 1e-2, 0.3])), shape)
    elif kind == "noise":
        v = rng.normal(0, 1, shape)
    elif kind == "constant":
        v = np.full(shape, float(rng.choice([-1.5, 0.0, 3.25e7, 1e-30])))
    elif kind == "nearconst":
        v = 7.0 + rng.normal(0, 1e-9, shape)
    elif kind == "sparse":
        v = smooth(shape, seed=seed) * 0.01
        v[rng.random(shape) < 0.75] = 0
    elif kind == "dense":
        v = smooth(shape, seed=seed)
        v[rng.random(shape) < 0.4] = 2.5
    elif kind == "wide":
        v = smooth(shape, seed=seed) * 10.0 ** rng.integers(-3, 4, shape)
        v[rng.random(shape) < 0.1] = 0
    elif kind == "positive":
        v = np.abs(smooth(shape, seed=seed)) + 0.1
    elif kind == "steps":
        v = np.floor(smooth(shape, scale=20.0, seed=seed))
    else:
        raise ValueError(kind)
    if dt[0] == "f":
        return v.astype("<" + dt)
    info = np.iinfo(dt)
    span = min(float(info.max) - float(info.min), 4e9)
    mid = (float(info.min) + float(info.max)) / 2 if dt[0] == "u" else 0.0
    if kind == "constant":
        return np.full(shape, int(min(max(mid + 7, info.min), info.max)), "<" + dt)
    lo, hi = float(v.min()), float(v.max())
    w = (v - lo) / (hi - lo) if hi > lo else np.zeros(shape)
    frac = float(rng.choice([0.6, 0.05, 1.0]))
    q = np.round((w - 0.5) * span * frac + mid)
    return np.clip(q, float(info.min), float(info.max)).astype("<" + dt)


SHAPES = {
    1: [(21,), (25,), (64,), (100,), (257,), (1000,)],
    2: [(6, 6), (8, 8), (10, 12), (16, 16), (33, 17), (5, 60), (60, 5), (2, 50), (3, 7)],
    3: [(6, 6, 6), (5, 7, 9), (7, 13, 8), (6, 7, 13), (3, 24, 5), (2, 2, 20), (13, 6, 7)],
    4: [(3, 5, 6, 7), (2, 3, 7, 8), (3, 3, 3, 10), (2, 2, 6, 7)],
}


def cases():
    """(name, data, client data) for every case, deterministically."""
    out = []
    rng = np.random.default_rng(20171)
    float_modes = [
        ("abs3", opts(0, absolute=1e-3)), ("abs1", opts(0, absolute=0.1)), ("abs7", opts(0, absolute=1e-7)),
        ("rel3", opts(1, relative=1e-3)), ("rel5", opts(1, relative=1e-5)),
        ("absrel", opts(2, absolute=1e-2, relative=1e-3)), ("absorrel", opts(3, absolute=1e-4, relative=1e-3)),
        ("pwr2", opts(10, pointwise=1e-2)), ("pwr4", opts(10, pointwise=1e-4)), ("pwr6", opts(10, pointwise=1e-6)),
    ]
    # Every float path: each dimensionality, mode, and kind of data (the larger shapes in fewer modes).
    for dt in ("f4", "f8"):
        for nd, shapes in SHAPES.items():
            for i, shape in enumerate(shapes):
                for mname, o in float_modes:
                    if np.prod(shape) > 250 and mname in ("abs1", "rel5", "pwr4", "absrel"):
                        continue
                    kinds = ["wide", "positive", "smooth"] if mname.startswith("pwr") else ["smooth", "noisy"]
                    kind = kinds[(i + len(mname)) % len(kinds)]
                    out.append((f"{dt}_{nd}d_{'x'.join(map(str, shape))}_{mname}_{kind}", data_for(kind, shape, dt, rng), o))
            shape = shapes[len(shapes) // 2]
            tag = "x".join(map(str, shape))
            for kind in ("noise", "constant", "nearconst", "sparse", "dense", "ramp", "steps"):
                out.append((f"{dt}_{nd}d_{tag}_abs3_{kind}", data_for(kind, shape, dt, rng), opts(0, absolute=1e-3)))
            out.append((f"{dt}_{nd}d_{tag}_rel3_sparse", data_for("sparse", shape, dt, rng), opts(1, relative=1e-3)))
            out.append((f"{dt}_{nd}d_{tag}_pwr3_noise", data_for("noise", shape, dt, rng), opts(10, pointwise=1e-3)))
        # exactly 20 and 21 values; special values
        out.append((f"{dt}_1d_20_abs3", data_for("smooth", (20,), dt, rng), opts(0, absolute=1e-3)))
        out.append((f"{dt}_1d_21_abs3", data_for("smooth", (21,), dt, rng), opts(0, absolute=1e-3)))
        v = data_for("smooth", (12, 12), dt, rng)
        v[3, 4] = np.nan
        out.append((f"{dt}_2d_nan_abs3", v, opts(0, absolute=1e-3)))
        v = data_for("smooth", (300,), dt, rng)
        v[[7, 150]] = np.nan
        out.append((f"{dt}_1d_nan_abs3", v, opts(0, absolute=1e-3)))
        v = data_for("smooth", (6, 7, 8), dt, rng)
        v[2, 3, 4] = np.inf
        out.append((f"{dt}_3d_inf_abs3", v, opts(0, absolute=1e-3)))
        v = data_for("positive", (200,), dt, rng)
        v[0] = -v[0]
        out.append((f"{dt}_1d_firstneg_pwr2", v, opts(10, pointwise=1e-2)))
        v = data_for("smooth", (9, 9), dt, rng) * 1e-38
        out.append((f"{dt}_2d_tiny_pwr3", v, opts(10, pointwise=1e-3)))
    # Integers: the classic format, each dimensionality, absolute and relative bounds.
    for dt in ("u1", "i1", "u2", "i2", "u4", "i4", "u8", "i8"):
        for nd, shapes in SHAPES.items():
            for i, shape in enumerate(shapes[:4]):
                kind = ("smooth", "noisy", "steps", "ramp")[i]
                out.append((f"{dt}_{nd}d_{'x'.join(map(str, shape))}_abs3_{kind}", data_for(kind, shape, dt, rng), opts(0, absolute=3)))
            shape = shapes[2]
            tag = "x".join(map(str, shape))
            out.append((f"{dt}_{nd}d_{tag}_abs0_smooth", data_for("smooth", shape, dt, rng), opts(0, absolute=0.5)))
            out.append((f"{dt}_{nd}d_{tag}_rel3_smooth", data_for("smooth", shape, dt, rng), opts(1, relative=1e-3)))
            out.append((f"{dt}_{nd}d_{tag}_rel1_noise", data_for("noise", shape, dt, rng), opts(1, relative=0.1)))
            out.append((f"{dt}_{nd}d_{tag}_absrel_smooth", data_for("smooth", shape, dt, rng), opts(2, absolute=2, relative=1e-3)))
            out.append((f"{dt}_{nd}d_{tag}_absorrel_smooth", data_for("smooth", shape, dt, rng), opts(3, absolute=2, relative=1e-3)))
            out.append((f"{dt}_{nd}d_{tag}_abs3_constant", data_for("constant", shape, dt, rng), opts(0, absolute=3)))
            out.append((f"{dt}_{nd}d_{tag}_abs1_noise", data_for("noise", shape, dt, rng), opts(0, absolute=1)))
        info = np.iinfo(dt)
        v = np.array([info.min, info.max] * 15, dtype="<" + dt)
        out.append((f"{dt}_1d_extremes_abs3", v, opts(0, absolute=3)))
    # Random cases across everything.
    types = ["f4", "f8", "f4", "f8", "u1", "i1", "u2", "i2", "u4", "i4", "u8", "i8"]
    kinds_f = ["smooth", "noisy", "noise", "sparse", "dense", "ramp", "steps", "wide", "positive", "nearconst"]
    kinds_i = ["smooth", "noisy", "noise", "ramp", "steps"]
    for k in range(160):
        dt = types[k % len(types)]
        nd = int(rng.integers(1, 5))
        if nd == 1:
            shape = (int(rng.integers(21, 1200)),)
        elif nd == 2:
            shape = tuple(int(rng.integers(2, 36)) for _ in range(2))
        elif nd == 3:
            shape = tuple(int(rng.integers(2, 12)) for _ in range(3))
        else:
            shape = tuple(int(rng.integers(2, 7)) for _ in range(4))
        if int(np.prod(shape)) < 21:
            shape = (int(np.prod(shape)) + 30,)
        floating = dt[0] == "f"
        kind = str(rng.choice(kinds_f if floating else kinds_i))
        mode = int(rng.choice([0, 1, 2, 3, 10] if floating else [0, 1, 2, 3]))
        a = float(10.0 ** rng.uniform(-6, 0)) if floating else float(rng.choice([0.5, 1, 2, 7, 100]))
        r = float(10.0 ** rng.uniform(-6, -1))
        p = float(10.0 ** rng.uniform(-6.5, -1)) if mode == 10 else 0.0
        if mode == 10 and kind not in ("wide", "positive", "smooth", "noisy"):
            kind = "wide"
        name = f"rnd{k:03d}_{dt}_{nd}d_{'x'.join(map(str, shape))}_m{mode}_{kind}"
        out.append((name, data_for(kind, shape, dt, rng), opts(mode, absolute=a if mode in (0, 2, 3) else 0.0,
                                                               relative=r if mode in (1, 2, 3) else 0.0, pointwise=p)))
    return out


def vector(name, data, o):
    buf = io.BytesIO()
    with h5py.File(buf, "w") as f:
        f.create_dataset("d", data=data, chunks=data.shape, compression=32017, compression_opts=o)
    with h5py.File(buf, "r") as f:  # a new session: the stored chunk, not the cached input
        d = f["d"]
        cd = d.id.get_create_plist().get_filter(0)[2]
        mask, raw = d.id.read_direct_chunk((0,) * d.ndim)
    raw = bytes(raw)
    assert mask == 0, name
    sz = imagecodecs.zstd_decode(raw) if raw[:4] == ZSTD_MAGIC else raw
    r = dims(cd)
    bounds = " ".join(struct.pack(">II", o[1 + 2 * i], o[2 + 2 * i]).hex() for i in range(3))
    return (f"{name} {SZ_TYPES[data.dtype.str[1:]]} {' '.join(map(str, r))} {o[0]} {bounds} "
            f"{data.tobytes().hex()} {bytes(sz).hex()}")


def worker(start, path):
    all_cases = cases()
    with open(path, "a", newline="\n") as out:
        for i in range(start, len(all_cases)):
            out.write(f"#case {i}\n")
            out.flush()
            out.write(vector(*all_cases[i]) + "\n")
            out.flush()


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "--worker":
        worker(int(sys.argv[2]), sys.argv[3])
        return
    all_cases = cases()
    scratch = OUT + ".partial"
    if os.path.exists(scratch):
        os.remove(scratch)
    start, crashed = 0, []
    while start < len(all_cases):
        subprocess.run([sys.executable, __file__, "--worker", str(start), scratch], check=False)
        with open(scratch) as f:
            lines = f.read().splitlines()
        markers = [int(line.split()[1]) for line in lines if line.startswith("#case")]
        last = markers[-1] if markers else start
        done = lines and not lines[-1].startswith("#case")
        if done and last == len(all_cases) - 1:
            break
        crashed.append(all_cases[last][0])  # the case libSZ ended the worker on
        start = last + 1
    with open(scratch) as f:
        vectors = [line for line in f.read().splitlines() if not line.startswith("#")]
    os.remove(scratch)
    head = ["# name sz-data-type r5 r4 r3 r2 r1 mode abs-hex rel-hex pwr-hex input-hex sz-hex",
            "# generated by tools/fixtures/gen_sz_encoder_vectors.py (hdf5plugin's H5Z-SZ, SZ 2.1.12)"]
    with open(OUT, "w", newline="\n") as out:
        out.write("\n".join(head + vectors) + "\n")
    print(len(vectors), "vectors;", len(crashed), "cases libSZ ended:", " ".join(crashed))


if __name__ == "__main__":
    main()
