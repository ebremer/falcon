#!/usr/bin/env python3
"""Writes Falcon Core's bzip2 reference vectors: libbzip2's own output (Python's bz2 module, which calls
BZ2_bzCompressInit(.., level, 0, 0) as the HDF5 bzip2 filter does) for inputs made by small deterministic
recipes, each at several block sizes.

    python tools/fixtures/gen_bzip2_vectors.py

Each line is `name kind size seed blocksize compressed-length sha256 hex`: the recipe (`kind`, `size`,
`seed`; Bzip2VectorsTest makes the same bytes), libbzip2's stream length and SHA-256, and the stream itself
in hex when it is short (else `-`). The recipes cover empty and 1-byte input, runs of 4 to 255 and longer
(crossing run-length and block boundaries), random and text-like bytes, periodic blocks (whose equal
rotations make the sort's tie order show in the stream), and blocks repetitive enough that the main sort
exhausts its budget and libbzip2 falls back to its other sort. Dev-time tool (Python's stdlib); not a
Falcon dependency.
"""
import bz2
import hashlib
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "core", "src", "test", "resources", "fixtures",
                   "bzip2_vectors.txt")
HEX_LIMIT = 6200  # streams up to this many bytes are stored whole, for the decoder

WORDS = [b"falcon", b"reads", b"writes", b"bzip2", b"blocks", b"of", b"the", b"chunk", b"hdf5", b"filter",
         b"burrows", b"wheeler", b"huffman", b"tables", b"and", b"a", b"run", b"zarr"]


def xorshift(seed):
    """Marsaglia's xorshift32, as Bzip2VectorsTest.Recipes steps it."""
    x = seed & 0xFFFFFFFF or 0x9E3779B9
    while True:
        x ^= (x << 13) & 0xFFFFFFFF
        x ^= x >> 17
        x ^= (x << 5) & 0xFFFFFFFF
        yield x


def make(kind, n, seed):
    rng = xorshift(seed)
    if kind == "zeros":
        return bytes(n)
    if kind == "random":
        return bytes(next(rng) >> 24 for _ in range(n))
    if kind == "dna":  # four symbols: a skewed alphabet, long Huffman tables
        return bytes(b"ACGT"[next(rng) >> 30] for _ in range(n))
    if kind == "text":
        out = bytearray()
        while len(out) < n:
            r = next(rng)
            out += WORDS[r % len(WORDS)] + (b"\n" if (r >> 8) % 11 == 0 else b" ")
        return bytes(out[:n])
    if kind == "runs":  # byte runs of 1 to 600: run-length counts, 255-byte limits, runs across blocks
        out = bytearray()
        while len(out) < n:
            r = next(rng)
            out += bytes([r >> 24]) * (1 + (r & 0xFFFF) % 600)
        return bytes(out[:n])
    if kind == "shortruns":  # runs of 1 to 6 over 3 values: every run length near the 4-byte threshold
        out = bytearray()
        while len(out) < n:
            r = next(rng)
            out += bytes([r >> 30]) * (1 + (r & 0xFF) % 6)
        return bytes(out[:n])
    if kind == "periodic":  # period `seed`: equal rotations everywhere
        return bytes((i % seed) * 37 & 0xFF for i in range(n))
    if kind == "repeat":  # a random stretch of `seed` bytes, repeated
        unit = bytes(next(rng) >> 24 for _ in range(seed))
        return (unit * (n // seed + 1))[:n]
    if kind == "ints":  # little-endian int32s rising slowly with a little noise, as array data often is
        return b"".join((i // 3 + (next(rng) >> 29)).to_bytes(4, "little") for i in range(n // 4)) + bytes(n % 4)
    raise ValueError(kind)


CASES = [
    # name, kind, size, seed, block sizes
    ("empty", "zeros", 0, 0, (1, 9)),
    ("one", "random", 1, 7, (1, 9)),
    ("two", "random", 2, 8, (9,)),
    ("zeros3", "zeros", 3, 0, (9,)),
    ("zeros4", "zeros", 4, 0, (9,)),
    ("zeros5", "zeros", 5, 0, (9,)),
    ("zeros255", "zeros", 255, 0, (9,)),
    ("zeros256", "zeros", 256, 0, (9,)),
    ("zeros259", "zeros", 259, 0, (9,)),
    ("zeros_100k", "zeros", 100000, 0, (1, 9)),
    ("zeros_1m", "zeros", 1000000, 0, (1, 5, 9)),
    ("random_100", "random", 100, 1, (9,)),
    ("random_9999", "random", 9999, 2, (9,)),
    ("random_10000", "random", 10000, 3, (9,)),
    ("random_250k", "random", 250000, 4, (1, 2, 9)),
    ("text_1k", "text", 1000, 5, (1, 9)),
    ("text_50k", "text", 50000, 6, (1, 9)),
    ("text_400k", "text", 400000, 7, (1, 3, 9)),
    ("dna_30k", "dna", 30000, 8, (9,)),
    ("dna_220k", "dna", 220000, 9, (1, 9)),
    ("runs_20k", "runs", 20000, 10, (9,)),
    ("runs_350k", "runs", 350000, 11, (1, 2, 9)),
    ("shortruns_5k", "shortruns", 5000, 12, (9,)),
    ("shortruns_150k", "shortruns", 150000, 13, (1, 9)),
    ("periodic3_500", "periodic", 500, 3, (9,)),
    ("periodic7_9000", "periodic", 9000, 7, (9,)),
    ("periodic7_60k", "periodic", 60000, 7, (9,)),
    ("periodic256_300k", "periodic", 300000, 256, (1, 9)),
    ("repeat1000_80k", "repeat", 80000, 1000, (9,)),
    ("repeat5000_150k", "repeat", 150000, 5000, (1, 9)),
    ("repeat17_120k", "repeat", 120000, 17, (1, 9)),
    ("ints_40k", "ints", 40000, 0, (9,)),
    ("ints_400k", "ints", 400000, 0, (1, 4, 9)),
]


def main():
    lines = ["# name kind size seed blocksize compressed-length sha256 hex",
             "# generated by tools/fixtures/gen_bzip2_vectors.py (libbzip2, through Python's bz2 module)"]
    for name, kind, n, seed, levels in CASES:
        data = make(kind, n, seed)
        assert len(data) == n, name
        for level in levels:
            stream = bz2.compress(data, level)
            assert bz2.decompress(stream) == data
            lines.append(" ".join([name, kind, str(n), str(seed), str(level), str(len(stream)),
                                   hashlib.sha256(stream).hexdigest(),
                                   stream.hex() if len(stream) <= HEX_LIMIT else "-"]))
    with open(OUT, "w", encoding="utf-8", newline="\n") as out:
        out.write("\n".join(lines) + "\n")
    print(f"wrote {len(lines) - 2} vectors to {OUT}")


if __name__ == "__main__":
    main()
