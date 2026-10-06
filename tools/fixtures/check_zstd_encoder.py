"""Cross-checks Falcon's from-scratch Zstandard encoder against libzstd.

Falcon's decoder is validated against libzstd frames (gen_zstd_vectors.py); this
proves the other direction -- that frames Falcon *writes* are read by libzstd, so
a Falcon-written zstd chunk is readable by zarr-python. Dev-time tool; numcodecs
is not a Falcon dependency.

Usage (from the repo root, after `mvn -pl core compile`):

    # 1. emit frames from Java
    javac -cp core/target/classes -d /tmp/xz EmitZstd.java   # see the class below
    java  -cp "/tmp/xz;core/target/classes" EmitZstd /tmp/xz

    # 2. verify with libzstd
    python tools/fixtures/check_zstd_encoder.py /tmp/xz

The EmitZstd helper (write once to /tmp/xz/EmitZstd.java):

    import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
    import java.nio.file.*; import java.util.*;
    public class EmitZstd {
      public static void main(String[] a) throws Exception {
        Path d = Paths.get(a[0]); Files.createDirectories(d);
        Random r = new Random(99);
        List<byte[]> cs = new ArrayList<>();
        cs.add("hello world".getBytes());
        cs.add("abcdefgh".repeat(4000).getBytes());
        byte[] big = new byte[200000];
        for (int i=0;i<big.length;i++) big[i]=(byte)((i*31+i/97)&0xff);
        cs.add(big);
        byte[] rnd = new byte[3000]; r.nextBytes(rnd); cs.add(rnd);
        byte[] huge = new byte[150_000_000];  // over libzstd's 128 MiB streaming window limit
        for (int i=0;i<huge.length;i++) huge[i]=(byte)(i/1000);
        cs.add(huge);
        for (int i=0;i<cs.size();i++){
          Files.write(d.resolve("orig_"+i+".bin"), cs.get(i));
          // odd cases carry the XXH64 content checksum (the zstd codec's "checksum": true)
          Files.write(d.resolve("frame_"+i+".zst"), ZstdEncoder.compress(cs.get(i), i % 2 == 1));
        }
      }
    }

When the zstandard package is installed, each frame is also decoded through libzstd's
streaming API, which refuses a window over 128 MiB: a frame larger than 128 KiB must
declare a 128 KiB window rather than be single-segment (window = whole content), and a
frame with a content checksum has it verified.

Note: numcodecs.Zstd cannot decode a zero-length frame (it rejects libzstd's own
empty frame too), so the empty case is skipped here; Falcon never stores an empty
chunk anyway.
"""
import glob
import os
import sys
from numcodecs import Zstd

try:
    import zstandard
except ImportError:  # the streaming check is skipped without it
    zstandard = None


def main(directory):
    z = Zstd()
    ok = failed = 0
    for frame_path in sorted(glob.glob(os.path.join(directory, "frame_*.zst"))):
        index = os.path.basename(frame_path).split("_")[1].split(".")[0]
        original = open(os.path.join(directory, f"orig_{index}.bin"), "rb").read()
        frame = open(frame_path, "rb").read()
        if not original:
            continue
        try:
            decoded = bytes(z.decode(frame))
        except Exception as e:  # noqa: BLE001
            print(f"  case {index}: libzstd FAILED: {e}")
            failed += 1
            continue
        if decoded != original:
            print(f"  case {index}: MISMATCH")
            failed += 1
            continue
        if zstandard is not None:
            try:
                streamed = zstandard.ZstdDecompressor().decompressobj().decompress(frame)
            except zstandard.ZstdError as e:
                print(f"  case {index}: libzstd streaming FAILED: {e}")
                failed += 1
                continue
            if streamed != original:
                print(f"  case {index}: streaming MISMATCH")
                failed += 1
                continue
        print(f"  case {index}: OK ({len(original)} -> {len(frame)} bytes)")
        ok += 1
    print(f"\nlibzstd read {ok}/{ok + failed} Falcon-encoded frames correctly")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
