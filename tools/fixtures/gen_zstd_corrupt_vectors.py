#!/usr/bin/env python3
"""Generates corrupt and multi-frame zstd vectors with python-zstandard (which wraps libzstd), the oracle
for core's ZstdDecoder never returning data libzstd would not.

Dev-time tool only -- zstandard is NOT a Falcon dependency (see requirements.txt). Run from the repo root:

    python tools/fixtures/gen_zstd_corrupt_vectors.py

Writes core/src/test/resources/fixtures/zstd_corrupt_vectors.txt, one case per line:

    <label> <expected> <input-hex>

where <expected> is
    ERR          libzstd rejects the input, so the decoder must;
    OK:<sha1>    libzstd decodes it to the original data, so the decoder must return exactly that;
    ANY:<sha1>   libzstd decodes it to something other than the original. That is either a valid frame
                 with other content or a corrupt one libzstd's fast Huffman decoder lets through (it checks
                 how many literals a stream gives, not that the stream ends there), so the decoder must
                 either reject it or return exactly libzstd's output -- never anything else.

The cases are one- and two-bit mutations of frames with and without a content checksum, at several levels
(so Huffman literals, FSE tables, sequences, and checksums are all hit), plus whole inputs that test the
framing: several frames, skippable frames, and what may not follow the last frame.
"""
import hashlib
import io
import os
import random

import zstandard as zs

OUT = os.path.join("core", "src", "test", "resources", "fixtures", "zstd_corrupt_vectors.txt")
rng = random.Random(20261005)
words = ["".join(rng.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(rng.randint(2, 9))) for _ in range(200)]


def original(i):
    n = rng.randint(30, 1200)
    if i % 3 == 0:
        return " ".join(rng.choice(words) for _ in range(n // 4)).encode()[:n]
    if i % 3 == 1:  # skewed bytes: Huffman-coded literals
        return bytes(rng.choices(range(256), weights=[60 if j < 24 else 1 for j in range(256)], k=n))
    return bytes(rng.randrange(8) * 17 for _ in range(n))  # few symbols, many matches


def sha1(data):
    return hashlib.sha1(data).hexdigest()


def one_shot(data):
    """libzstd's verdict on one frame: its output, or None if it rejects the input. A header declaring a
    content size Python cannot allocate raises MemoryError; such a case is left out."""
    try:
        return zs.ZstdDecompressor().decompress(data, max_output_size=1 << 20, allow_extra_data=False)
    except zs.ZstdError:
        return None


def across_frames(data):
    with zs.ZstdDecompressor().stream_reader(io.BytesIO(data), read_across_frames=True) as reader:
        return reader.read()


def skippable(payload, nibble=3):
    return (0x184D2A50 | nibble).to_bytes(4, "little") + len(payload).to_bytes(4, "little") + payload


lines = []

# Mutations: each base frame mutated in one or two random bits past the magic number.
bases = []
for i in range(48):
    data = original(i)
    level = rng.choice([1, 3, 9, 19])
    checksum = i % 2 == 0
    bases.append((data, zs.ZstdCompressor(level=level, write_checksum=checksum).compress(data), checksum))
counts = {}
while sum(counts.values()) < 420:
    data, frame, checksum = rng.choice(bases)
    mutated = bytearray(frame)
    for _ in range(rng.randint(1, 2)):
        mutated[rng.randrange(4, len(mutated))] ^= 1 << rng.randrange(8)
    if bytes(mutated) == frame:
        continue
    try:
        out = one_shot(bytes(mutated))
    except MemoryError:
        continue
    if out is None:
        expected = "ERR"
    elif out == data:
        expected = "OK:" + sha1(out)
    else:
        expected = "ANY:" + sha1(out)
    kind = ("ck" if checksum else "nock") + "-" + expected.split(":")[0].lower()
    if counts.get(kind, 0) >= 120:  # keep the classes balanced
        continue
    counts[kind] = counts.get(kind, 0) + 1
    lines.append(f"mut-{kind} {expected} {bytes(mutated).hex()}")

# Framing: several frames and skippable frames decode to the concatenation, as ZSTD_decompress does.
# python-zstandard's one-shot decompress reads only one frame (and takes a skippable frame's size for a
# content size), so these expectations come from its streaming reader, which reads across frames.
a_data, b_data, c_data = original(0), original(1), original(2)
a = zs.ZstdCompressor(level=3, write_checksum=True).compress(a_data)
b = zs.ZstdCompressor(level=19).compress(b_data)
c = zs.ZstdCompressor(level=1, write_content_size=False).compress(c_data)  # no content size in the header
empty = zs.ZstdCompressor(write_checksum=True).compress(b"")
skip = skippable(b"falcon")
valid = {
    "two-frames": a + b,
    "three-frames": a + b + c,
    "frame-then-empty-frame": a + empty,
    "skippable-first": skip + a,
    "skippable-between": a + skippable(b"", 0) + b,
    "skippable-last": a + b + skippable(bytes(100), 15),
    "only-skippable": skip + skippable(b"x", 7),
}
for label, data in valid.items():
    lines.append(f"{label} OK:{sha1(across_frames(data))} {data.hex()}")

# What may not follow, or precede, a frame. ZSTD_decompress rejects each: bytes after the last frame
# that are not a frame, and any frame (skippable ones too) cut short. python-zstandard's streaming reader
# stops quietly at a truncation instead, so it is no oracle here; its one-shot decompress rejects them all.
invalid = {
    "trailing-1-byte": a + b"\x00",
    "trailing-3-bytes": a + b"\x28\xb5\x2f",
    "trailing-garbage": a + bytes(range(16)),
    "truncated-second-frame": a + b[:-3],
    "truncated-skippable": a + skippable(b"falcon")[:-2],
    "skippable-size-past-end": skippable(b"ab")[:4] + (1000).to_bytes(4, "little") + b"ab",
    "truncated-checksum": a[:-2],
    "garbage-before-frame": b"\x00" + a,
}
for label, data in invalid.items():
    if one_shot(data) is not None:
        raise RuntimeError(f"libzstd accepts {label}")
    lines.append(f"{label} ERR {data.hex()}")
# No frame at all: libzstd returns nothing; Falcon (like numcodecs and the HDF5 plugin) rejects it.
lines.append("empty-input ERR -")

with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("".join(line + "\n" for line in lines))
print(f"wrote {len(lines)} cases to {OUT}: {sorted(counts.items())}")
