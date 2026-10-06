"""Cross-checks Falcon's from-scratch Blosc and LZ4 encoders against c-blosc and liblz4.

Falcon's decoder is validated against c-blosc buffers (gen_blosc_vectors.py); this proves the other
direction -- that buffers Falcon *writes* are read by c-blosc, so a Falcon-written blosc chunk is
readable by zarr-python -- and that they are c-blosc's own: the same header (flags, type size, block
size) and, for every internal compressor but zstd (Falcon's zstd encoder is its own), the same bytes.
Dev-time tool; numcodecs is not a Falcon dependency.

Usage (from the repo root, after `mvn -pl core compile`):

    python tools/fixtures/check_blosc_encoder.py OUT_DIR [--quick]

It writes the inputs and OUT_DIR/cases.txt, runs tools/fixtures/WriteBloscCases.java (the JDK's source
launcher) for Falcon's output, and compares each case with numcodecs 0.17 (c-blosc 1.21, one thread so
blocks are written in order; liblz4 1.10.0):

  * a header matrix: every cname (blosclz, lz4, lz4hc, zlib, zstd) x clevel 0-9 x shuffle 0/1/2 x type
    size 1, 2, 3, 4, 8, 16, 17, 255, 256, on numeric, image-like, text, random, and constant inputs; forced
    block sizes; tiny buffers (0 to 129 bytes) and buffers either side of 32 KiB, 64 KiB, and the LZ4
    16-bit table limit; a buffer of 8 MiB;
  * LZ4 blocks (numcodecs.lz4: acceleration 1, 2, 5, 9, 100) and LZ4HC blocks at levels 1 to 9, decoded by
    liblz4.

  * snappy, which numcodecs' c-blosc is built without: when imagecodecs is installed (its c-blosc 1.21.6
    has snappy), Falcon's snappy buffers are decoded with it. (Their bytes are compared with hdf5plugin's
    c-blosc, which builds snappy 1.2.2, through HDF5 chunks: hdf5's WriteFilterConformanceTest.)

Every Falcon buffer must decode with c-blosc to its input. One difference is by design: Falcon byte-shuffles
data whose length is not a whole number of elements when bit-shuffling was asked for (c-blosc before 1.18
cannot restore such a block's partial element), so those headers and bytes differ and are counted apart.
zstd buffers may also differ in the memcpy flag, where a compressed buffer lands within 16 bytes of the
input's size. --quick runs a smaller matrix.

Note: numcodecs cannot decode an empty Blosc buffer or LZ4 block, its own included; those are compared
byte for byte only.
"""
import os
import struct
import subprocess
import sys
from collections import Counter, defaultdict

import numpy as np
import numcodecs.blosc as blosc
import numcodecs.lz4 as nlz4

try:
    import imagecodecs  # optional: a c-blosc with snappy, for the snappy cases
except ImportError:
    imagecodecs = None

BLOSC_MAX_BLOCKSIZE = (2**31 - 1 - 255 * 4) // 3
blosc.set_nthreads(1)
blosc.use_threads = False


def inputs(work, rng):
    found = {}

    def add(name, data):
        with open(os.path.join(work, name + ".bin"), "wb") as f:
            f.write(data)
        found[name] = data

    add("i4_smooth", (np.arange(300_000, dtype="<i4") // 3).tobytes())
    add("f8_walk", np.cumsum(rng.normal(size=150_000)).astype("<f8").tobytes())
    add("f4_noisy", (np.sin(np.arange(200_000) / 50.0) + rng.normal(scale=0.01, size=200_000)).astype("<f4").tobytes())
    add("u2_image", ((np.add.outer(np.arange(600), np.arange(500)) * 37) % 4096
                     + rng.integers(0, 4, (600, 500))).astype("<u2").tobytes())
    add("u1_labels", np.repeat(rng.integers(0, 6, 20_000), rng.integers(1, 40, 20_000))[:400_000].astype("u1").tobytes())
    words = [b"chunk", b"shard", b"array", b"zarr", b"falcon", b"blosc", b"group", b" ", b"\n", b"metadata"]
    add("text", b"".join(words[i] for i in rng.integers(0, len(words), 120_000))[:500_000])
    add("random", rng.integers(0, 256, 300_000, dtype="u1").tobytes())
    add("zeros", bytes(200_000))
    add("halfrand", (rng.integers(0, 256, 100_000, dtype="u1") & 0x0F).tobytes() + bytes(50_000))
    add("pattern1", b"a" * 70_000 + b"b" * 3 + b"a" * 1000)
    add("pattern2", b"ab" * 40_000 + b"xyz" + b"ab" * 500)
    add("pattern3", b"abc" * 30_000)
    add("pattern4", b"abcd" * 30_000 + b"q" + b"abcd" * 900)
    add("big", (np.arange(1 << 20, dtype="<i8") % 1000).tobytes() + b"tail")
    for n in [0, 1, 2, 12, 13, 15, 16, 17, 31, 32, 52, 64, 65, 66, 100, 127, 128, 129, 200, 255, 256, 1000, 4095,
              4096, 4097, 32767, 32768, 32769, 65535, 65536, 65546, 65547, 65548, 100_000]:
        add(f"tiny{n}", ((np.arange(n) // 5) % 7).astype("u1").tobytes())
        add(f"rtiny{n}", rng.integers(0, 256, n, dtype="u1").tobytes())
    return found


def cases_for(data, quick):
    cases = []

    def case(kind, name, *params):
        cases.append((len(cases), kind, name, params))

    cnames = ["blosclz", "lz4", "lz4hc", "zlib", "zstd"]
    main = ["i4_smooth", "f8_walk", "f4_noisy", "u2_image", "u1_labels", "text", "random", "zeros", "halfrand"]
    if quick:
        main = ["i4_smooth", "text", "random"]
    for name in main:
        for cname in cnames:
            for clevel in range(10):
                for shuffle in (0, 1, 2):
                    for ts in (1, 2, 3, 4, 8, 16, 17, 255, 256):
                        if not quick or ts in (1, 4, 17):
                            case("blosc", name, ts, shuffle, 0, clevel, cname)
            for clevel in (1, 5, 9):
                for ts in (1, 4, 16):
                    for forced in (100, 1000, 4096, 50_000, 300_000):
                        case("blosc", name, ts, 1, forced, clevel, cname)
    for name in main:
        for clevel in (1, 5, 9):
            for shuffle in (0, 1, 2):
                for ts in (1, 4, 8, 17):
                    case("blosc", name, ts, shuffle, 0, clevel, "snappy")
    for name in data:
        if name.startswith(("tiny", "rtiny", "pattern")) or name == "big":
            for cname in cnames:
                for clevel in (1, 5, 9):
                    for ts in (1, 4):
                        case("blosc", name, ts, 1, 0, clevel, cname)
                        case("blosc", name, ts, 0, 0, clevel, cname)
    for name in data:
        for accel in (1, 2, 5, 9, 100):
            case("lz4", name, accel)
        for level in range(1, 10):
            case("lz4hc", name, level)
    return cases


def lz4_decode(data, block):
    try:
        return bytes(nlz4.decompress(struct.pack("<I", len(data)) + block))
    except RuntimeError as e:
        return str(e)


def main(work, quick):
    os.makedirs(work, exist_ok=True)
    data = inputs(work, np.random.default_rng(7))
    cases = cases_for(data, quick)
    with open(os.path.join(work, "cases.txt"), "w") as f:
        for cid, kind, name, params in cases:
            f.write(" ".join([kind, str(cid), name + ".bin"] + [str(p) for p in params]) + "\n")
    launcher = os.path.join("tools", "fixtures", "WriteBloscCases.java")
    subprocess.run(["java", "-cp", os.path.join("core", "target", "classes"), launcher, work], check=True)
    falcon = {}
    with open(os.path.join(work, "falcon.out"), "rb") as f:
        raw = f.read()
    p = 0
    while p < len(raw):
        cid, n = struct.unpack(">ii", raw[p:p + 8])
        falcon[cid] = raw[p + 8:p + 8 + n]
        p += 8 + n

    stats = Counter()
    failures = defaultdict(list)
    ratio = defaultdict(lambda: [0, 0, 0])
    for cid, kind, name, params in cases:
        d = data[name]
        got = falcon[cid]
        if kind == "blosc" and params[4] == "snappy":
            if imagecodecs is None or not d:
                stats["snappy buffers not checked (no imagecodecs)"] += 1
            else:
                try:
                    decoded = bytes(imagecodecs.blosc_decode(got))
                except Exception as e:  # noqa: BLE001
                    decoded = str(e)
                if decoded == d:
                    stats["snappy buffers imagecodecs' c-blosc decodes"] += 1
                    if not got[2] & 0x02:
                        stats["  of them compressed (not stored whole)"] += 1
                else:
                    failures["imagecodecs' c-blosc does not decode a snappy buffer"].append(cases[cid])
            continue
        if kind == "blosc":
            ts, shuffle, forced, clevel, cname = params
            ref = bytes(blosc.compress(d, cname.encode(), clevel, shuffle, forced, ts))
            if len(got) >= 16 and struct.unpack("<i", got[8:12])[0] > BLOSC_MAX_BLOCKSIZE:
                failures["block size over c-blosc's limit"].append(cases[cid])
                continue
            # by design: a partial element is byte-shuffled, not bit-shuffled
            by_design = shuffle == 2 and ts <= 255 and len(d) % ts != 0
            if d:
                try:
                    if bytes(blosc.decompress(got)) != d:
                        failures["c-blosc decodes other bytes"].append(cases[cid])
                        continue
                except RuntimeError as e:
                    failures["c-blosc cannot decode"].append((cases[cid], str(e)))
                    continue
            stats["blosc buffers c-blosc decodes" if d else "empty buffers"] += 1
            same_header = got[:4] == ref[:4] and got[8:12] == ref[8:12]
            if by_design:
                stats["partial element, byte-shuffled by design"] += 1
            elif same_header:
                stats["same header"] += 1
            elif cname == "zstd" and (got[2] ^ ref[2]) == 0x02:
                stats["zstd: memcpy flag differs (Falcon's zstd sizes)"] += 1
            else:
                failures["header differs"].append((cases[cid], got[:16].hex(), ref[:16].hex()))
            if cname != "zstd" and not by_design:
                if got == ref:
                    stats["same bytes (not zstd)"] += 1
                else:
                    failures["bytes differ"].append(cases[cid])
            if name in ("i4_smooth", "f8_walk", "f4_noisy", "u2_image", "u1_labels", "text", "halfrand") \
                    and forced == 0 and ts in (1, 2, 4, 8) and clevel in (1, 5, 9) and not by_design:
                r = ratio[(cname, clevel)]
                r[0] += len(got)
                r[1] += len(ref)
                r[2] += len(d)
        elif kind == "lz4":
            ref = bytes(nlz4.compress(d, params[0]))[4:]
            stats["lz4 blocks"] += 1
            if got == ref:
                stats["lz4 blocks liblz4's"] += 1
            else:
                failures["lz4 block differs"].append(cases[cid])
            if d and lz4_decode(d, got) != d:
                failures["liblz4 cannot decode"].append(cases[cid])
        else:
            stats["lz4hc blocks"] += 1
            if d and lz4_decode(d, got) != d:
                failures["liblz4 cannot decode an HC block"].append(cases[cid])
            elif d:
                stats["lz4hc blocks liblz4 decodes"] += 1

    for k, v in sorted(stats.items()):
        print(f"  {k}: {v}")
    print("\ncompressed size / input size, type sizes 1-8, clevel 1, 5, 9 (Falcon, c-blosc):")
    for (cname, clevel), (fb, cb, raw_bytes) in sorted(ratio.items()):
        print(f"  {cname:8s} clevel {clevel}: {fb / raw_bytes:.4f}  {cb / raw_bytes:.4f}  ({fb / cb:.4f})")
    for k, v in failures.items():
        print(f"\nFAILED -- {k}: {len(v)}")
        for x in v[:10]:
            print("   ", x)
    return 1 if failures else 0


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--quick"]
    sys.exit(main(args[0] if args else "blosc_xcheck", "--quick" in sys.argv))
