"""Generates the reference digests Falcon's Blosc and LZ4 encoders must reproduce, with numcodecs.

Dev-time tool only -- numcodecs is NOT a Falcon dependency (requirements.txt pins the version the
fixtures were made with). Run from the repo root:  python tools/fixtures/gen_blosc_encode_vectors.py

Writes core/src/test/resources/fixtures/blosc_encode_vectors.txt. The inputs are not stored: each is a
formula of its byte index (see GENERATORS; BloscEncodeVectorsTest computes the same bytes), so a line
holds only what c-blosc or liblz4 wrote for it -- its length, its header (Blosc), and its SHA-256:

    blosc <gen> <n> <cname> <clevel> <shuffle> <typesize> <blocksize> <length> <header-hex> <sha256>
    lz4   <gen> <n> <acceleration> <length> <sha256>
    lz4hc <gen> <n> <level> <length> <sha256>

Blosc buffers come from c-blosc 1.21 (numcodecs.blosc, one thread, so blocks are written in order) with
a destination of the input's size plus 16, as numcodecs always gives it. LZ4 blocks come from liblz4
1.10.0: LZ4_compress_fast through numcodecs.lz4 (less its 4-byte size), and LZ4_compress_HC from a
one-block, one-byte-type, unshuffled Blosc buffer, whose single stream is the HC block (a case whose
stream c-blosc stored raw is skipped). zstd is left out: Falcon's zstd encoder is its own, not libzstd's.
"""
import hashlib
import os
import struct

import numcodecs
import numcodecs.blosc as blosc
import numcodecs.lz4 as nlz4

OUT = os.path.join("core", "src", "test", "resources", "fixtures", "blosc_encode_vectors.txt")
blosc.set_nthreads(1)
blosc.use_threads = False

M32 = 0xFFFFFFFF


def fmix(x):
    """murmur3's 32-bit finalizer of x * golden ratio: a well-mixed hash of an index."""
    x = (x * 0x9E3779B9) & M32
    x ^= x >> 16
    x = (x * 0x85EBCA6B) & M32
    x ^= x >> 13
    x = (x * 0xC2B2AE35) & M32
    x ^= x >> 16
    return x


WORDS = [b"chunk   ", b"shard   ", b"array   ", b"zarr    ", b"falcon  ", b"blosc   ", b"group\n  ",
         b"metadata", b"attrs   ", b"codec   "]


def gen_ramp(i):  # int32 LE of k // 3, k the element index
    return (((i >> 2) // 3) >> (8 * (i & 3))) & 0xFF


def gen_noise16(i):  # int16 LE of k % 1000 plus two bits of noise
    k = i >> 1
    return (((k % 1000) + (fmix(k) & 3)) >> (8 * (i & 1))) & 0xFF


def gen_f64(i):  # float64 LE of k / 4, exact
    return struct.pack("<d", (i >> 3) * 0.25)[i & 7]


def gen_text(i):  # 8-byte tokens
    return WORDS[fmix(i >> 3) % len(WORDS)][i & 7]


def gen_runs(i):  # runs of 64 equal bytes, four values
    return fmix(i >> 6) & 3


def gen_random(i):
    return fmix(i) >> 24


def gen_pattern3(i):
    return b"abc"[i % 3]


GENERATORS = {"ramp": gen_ramp, "noise16": gen_noise16, "f64": gen_f64, "text": gen_text, "runs": gen_runs,
              "random": gen_random, "pattern3": gen_pattern3}


def data(gen, n):
    f = GENERATORS[gen]
    return bytes(f(i) for i in range(n))


_cache = {}


def cached(gen, n):
    if (gen, n) not in _cache:
        _cache[(gen, n)] = data(gen, n)
    return _cache[(gen, n)]


def sha(b):
    return hashlib.sha256(b).hexdigest()


lines = []


def blosc_case(gen, n, cname, clevel, shuffle, typesize, blocksize=0):
    d = cached(gen, n)
    buf = bytes(blosc.compress(d, cname.encode(), clevel, shuffle, blocksize, typesize))
    lines.append(f"blosc {gen} {n} {cname} {clevel} {shuffle} {typesize} {blocksize} {len(buf)} "
                 f"{buf[:16].hex()} {sha(buf)}")


for cname in ("blosclz", "lz4", "lz4hc", "zlib"):
    for gen in ("ramp", "noise16", "f64", "text", "runs", "random"):
        for n in (5000, 70_000):
            for clevel in (1, 4, 9):
                for shuffle in (0, 1, 2):
                    for typesize in (1, 4, 8):
                        blosc_case(gen, n, cname, clevel, shuffle, typesize)
    # the other type sizes: odd ones, the widest split, the narrowest unsplit, and one past the header
    for typesize in (2, 3, 16, 17, 256):
        for shuffle in (1, 2):
            blosc_case("noise16", 40_000 - 40_000 % typesize, cname, 5, shuffle, typesize)
            blosc_case("ramp", 40_000 - 40_000 % typesize, cname, 5, shuffle, typesize)
    # every clevel once, clevel 0 (stored), forced block sizes, tiny buffers, partial elements
    for clevel in range(10):
        blosc_case("noise16", 20_000, cname, clevel, 1, 2)
    for blocksize in (100, 1000, 4096, 50_000, 300_000):
        blosc_case("ramp", 120_000, cname, 5, 1, 4, blocksize)
        blosc_case("ramp", 1000, cname, 5, 1, 16, blocksize)
    for n in (0, 1, 100, 127, 128, 129, 255, 256, 1000):
        blosc_case("ramp", n, cname, 5, 1, 4)
    blosc_case("ramp", 10_003, cname, 5, 1, 4)  # a partial element, byte-shuffled
    blosc_case("ramp", 10_003, cname, 5, 0, 4)
    # many blocks, and the leftover block
    blosc_case("ramp", 1_100_003, cname, 5, 1, 1)
    blosc_case("noise16", 1_000_000, cname, 9, 2, 2)
    blosc_case("text", 700_001, cname, 3, 0, 8)

for gen in ("ramp", "noise16", "f64", "text", "runs", "random", "pattern3"):
    for n in (0, 1, 12, 13, 100, 5000, 65_546, 65_547, 200_000):
        d = cached(gen, n)
        for accel in (1, 3, 10, 100):
            block = bytes(nlz4.compress(d, accel))[4:]
            lines.append(f"lz4 {gen} {n} {accel} {len(block)} {sha(block)}")
        if n < 128:
            continue  # Blosc stores these whole
        for level in range(1, 10):
            buf = bytes(blosc.compress(d, b"lz4hc", level, 0, n, 1))
            flags = buf[2]
            if flags & 0x02:
                continue  # stored whole
            (length,) = struct.unpack("<i", buf[20:24])
            if length == n:
                continue  # stored raw
            block = buf[24:24 + length]
            lines.append(f"lz4hc {gen} {n} {level} {len(block)} {sha(block)}")

with open(OUT, "w", newline="\n") as f:
    f.write(f"# Generated by tools/fixtures/gen_blosc_encode_vectors.py with numcodecs {numcodecs.__version__} "
            f"(c-blosc {blosc.VERSION_STRING}, lz4 {nlz4.VERSION_STRING}).\n")
    f.write("# blosc <gen> <n> <cname> <clevel> <shuffle> <typesize> <blocksize> <length> <header> <sha256>\n")
    f.write("# lz4 <gen> <n> <acceleration> <length> <sha256>; lz4hc <gen> <n> <level> <length> <sha256>\n")
    for line in lines:
        f.write(line + "\n")
print(f"wrote {len(lines)} vectors to {OUT}")
