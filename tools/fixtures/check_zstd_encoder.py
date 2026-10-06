"""Cross-checks Falcon's from-scratch Zstandard encoder against libzstd.

Falcon's decoder is validated against libzstd frames (gen_zstd_vectors.py); this
proves the other direction -- that frames Falcon *writes* are read by libzstd, so
a Falcon-written zstd chunk is readable by zarr-python -- and compares Falcon's
ratios with libzstd's at the same levels. Dev-time tool; numcodecs and zstandard
are not Falcon dependencies.

Usage (from the repo root, after `mvn -pl core compile`):

    java -cp core/target/classes tools/fixtures/WriteZstdCases.java OUT_DIR [--huge]
    python tools/fixtures/check_zstd_encoder.py OUT_DIR

WriteZstdCases writes orig_<input>.bin, frame_<k>.zst, and manifest.tsv (frame,
input, level, checksum): every level from -5 to 22 on text, and levels -5, 1, 3,
9, 19, and 22 on the edge sizes (empty, 1 byte, tiny, exactly 128 KiB, multi-block),
all-equal and random bytes, noisy and smooth floats, records, and a sharded chunk.
--huge adds a 150 MB frame.

Each frame is decoded through libzstd's one-shot API (numcodecs) and, when the
zstandard package is installed, its streaming API, which refuses a window over
128 MiB: a frame larger than its level's window must declare that window rather
than be single-segment (window = whole content), and a frame with a content
checksum has it verified. With zstandard, a table of Falcon's ratios beside
libzstd's at levels 1, 3, 9, and 19 follows.

Note: numcodecs.Zstd cannot decode a zero-length frame (it rejects libzstd's own
empty frame too), so the empty input is decoded only by the streaming API.
"""
import os
import sys
from numcodecs import Zstd

try:
    import zstandard
except ImportError:  # the streaming check and the ratio table are skipped without it
    zstandard = None


def main(directory):
    z = Zstd()
    ok = failed = 0
    sizes = {}
    for line in open(os.path.join(directory, "manifest.tsv"), encoding="utf-8"):
        k, name, level, checksum = line.rstrip("\n").split("\t")
        original = open(os.path.join(directory, f"orig_{name}.bin"), "rb").read()
        frame = open(os.path.join(directory, f"frame_{k}.zst"), "rb").read()
        sizes[(name, int(level))] = len(frame)
        problems = []
        if original:
            try:
                if bytes(z.decode(frame)) != original:
                    problems.append("one-shot MISMATCH")
            except Exception as e:  # noqa: BLE001
                problems.append(f"one-shot FAILED: {e}")
        if zstandard is not None:
            try:
                if zstandard.ZstdDecompressor().decompressobj().decompress(frame) != original:
                    problems.append("streaming MISMATCH")
            except zstandard.ZstdError as e:
                problems.append(f"streaming FAILED: {e}")
        if problems:
            print(f"  frame {k} ({name}, level {level}, checksum {checksum}): {'; '.join(problems)}")
            failed += 1
        else:
            ok += 1
    print(f"libzstd read {ok}/{ok + failed} Falcon-encoded frames correctly")

    if zstandard is not None:
        print(f"\nratio (frame / input), Falcon / libzstd {'.'.join(map(str, zstandard.ZSTD_VERSION))}:")
        for name in sorted({name for name, _ in sizes}):
            data = open(os.path.join(directory, f"orig_{name}.bin"), "rb").read()
            if len(data) < 1000 or len(data) > 50_000_000:
                continue
            row = f"  {name:18s}"
            for level in (1, 3, 9, 19):
                ours = sizes.get((name, level))
                if ours is not None:
                    theirs = len(zstandard.ZstdCompressor(level=level).compress(data))
                    row += f"  L{level} {ours / len(data):.3f}/{theirs / len(data):.3f}"
            print(row)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
