"""Cross-checks Falcon's from-scratch Blosc encoder against c-blosc.

Falcon's decoder is validated against c-blosc buffers (gen_blosc_vectors.py); this
proves the other direction -- that buffers Falcon *writes* are read by c-blosc, so
a Falcon-written blosc chunk is readable by zarr-python. Dev-time tool; numcodecs
is not a Falcon dependency.

Usage (from the repo root, after `mvn -pl core compile`):

    # 1. emit buffers from Java (write EmitBlosc.java once, then):
    javac -cp core/target/classes -d /tmp/xb EmitBlosc.java
    java  -cp "/tmp/xb;core/target/classes" EmitBlosc
    # 2. verify with c-blosc
    python tools/fixtures/check_blosc_encoder.py /tmp/xb/blosc_xcheck

EmitBlosc emits, per case, orig_<k>.bin / buf_<k>.blosc / ts_<k>.txt using
com.ebremer.falcon.core.compress.blosc.BloscEncoder.compress(data, typeSize) over a
mix of shuffled int16/int32/float64 arrays and incompressible bytes.
"""
import glob
import os
import sys
from numcodecs import Blosc


def main(directory):
    z = Blosc()
    ok = failed = 0
    for buf_path in sorted(glob.glob(os.path.join(directory, "buf_*.blosc"))):
        k = os.path.basename(buf_path).split("_")[1].split(".")[0]
        original = open(os.path.join(directory, f"orig_{k}.bin"), "rb").read()
        buffer = open(buf_path, "rb").read()
        try:
            decoded = bytes(z.decode(buffer))
        except Exception as e:  # noqa: BLE001
            print(f"  case {k}: c-blosc FAILED: {e}")
            failed += 1
            continue
        if decoded == original:
            print(f"  case {k}: OK ({len(original)} -> {len(buffer)} bytes)")
            ok += 1
        else:
            print(f"  case {k}: MISMATCH")
            failed += 1
    print(f"\nc-blosc read {ok}/{ok + failed} Falcon-encoded blosc buffers correctly")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
