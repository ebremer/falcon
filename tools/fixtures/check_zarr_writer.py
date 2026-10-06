"""Reads every array WriteZarrCases.java wrote with Falcon back with zarr-python, and checks each element.

Falcon's reader is checked against zarr-python's stores (gen_zarr_fixtures.py and friends); this checks the
other direction, that what Falcon *writes* zarr-python reads as intended (P1 T1): every core data type,
every codec Falcon writes, sharding with the index at either end, partial writes, strings, and codec
configurations Falcon must honour in arrays it did not create (for those, the stored bytes are checked to
follow the configuration as well: zstd checksums, Blosc shuffle modes). P2 added variable-length bytes
(F5), arrays written with write_empty_chunks (F7: every chunk, and every sub-chunk of a shard, must be
stored), resized arrays (F6: what a shrink cut off must read as fill after growing back, and the chunks
outside the smaller shape must be gone), nested shards (F11), uint64 and complex values written with
the exact writers (F8), the extension data types (F14: numpy.datetime64 and numpy.timedelta64 as int64
counts, fixed_length_utf32, null_terminated_bytes, raw_bytes, and a struct with a nested struct, compared
packed little-endian; the partial arrays' fill values are checked to be what Falcon wrote), and rectilinear
chunk grids (F14: read with zarr-python's array.rectilinear_chunks, the stored grid and chunk files checked
too). Dev-time tool; zarr-python is not a Falcon dependency. Run it before every release:

    mvn -pl zarr -am compile
    java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZarrCases.java OUT_DIR
    python tools/fixtures/check_zarr_writer.py OUT_DIR

Element i of each array (flattened C order) is the formula in value() below, the same one
WriteZarrCases.java writes; anything outside the written region is the fill value.
"""
import json
import math
import os
import struct
import sys
import warnings

import numpy as np
import zarr

POOL = ["alpha", "", "gamma-δ", "中文", "emoji-\U0001f600", "x"]
SHAPE = (13, 7)
UTF = ["a", "", "δ", "中文", "\U0001f600", "x\0y"]
NAT = -2**63
EXTENSION = ("datetime64", "timedelta64", "utf32", "nullbytes", "rawbytes", "struct")

warnings.simplefilter("ignore")  # zarr-python warns that some extension data types are not yet stable specs


def record(a, b, c, d, t, f, x, y):
    """The struct WriteZarrCases.java writes, packed little-endian: int32, float64, S2, U2, datetime64[s],
    bool, and {int16, uint8}."""
    return (struct.pack("<id", a, b) + c.ljust(2, b"\0") + d.ljust(2, "\0").encode("utf-32-le")
            + struct.pack("<q?hB", t, f, x, y))


# The fill value each extension type's _partial array was given (EXTENSION_FILL in WriteZarrCases.java).
EXTENSION_FILL = {"datetime64": NAT, "timedelta64": 5, "utf32": "fill", "nullbytes": b"ab",
                  "rawbytes": b"\x01\x02\x03", "struct": record(7, 1.5, b"q", "z", NAT, True, -1, 255)}


def value(dtype, i):
    if dtype == "string":
        return POOL[i % len(POOL)] + str(i)
    if dtype == "bytes":
        return bytes(((i * 31 + j * 7) & 0xff) for j in range(i % 9))
    if dtype == "bool":
        return i % 3 == 0
    if dtype == "int8":
        return (i * 37 + 11) % 256 - 128
    if dtype == "uint8":
        return (i * 37 + 11) % 256
    if dtype == "int16":
        return (i * 4099 + 7) % 65536 - 32768
    if dtype == "uint16":
        return (i * 4099 + 7) % 65536
    if dtype == "int32":
        v = (i * 2654435761) % 2**32
        return v - 2**32 if v >= 2**31 else v
    if dtype == "uint32":
        return (i * 2654435761) % 2**32
    if dtype == "int64":
        v = (i * 0x9E3779B97F4A7C15) % 2**64
        return v - 2**64 if v >= 2**63 else v
    if dtype == "uint64":
        return i if i % 2 == 0 else 2**63 + i * 2048
    if dtype == "float16":
        return math.nan if i % 17 == 5 else -math.inf if i % 19 == 7 else (i - 45) * 0.5
    if dtype == "float32":
        return math.nan if i % 23 == 3 else float(np.float32(i * 0.1))
    if dtype == "float64":
        return i * 0.1 - 3
    if dtype == "uint64x":  # F8's writeUnsignedLongs: the whole range, most values with no exact double
        return (i * 0x9E3779B97F4A7C15 + 0xFFFF) % 2**64
    if dtype in ("complex64", "complex128"):
        return complex(i, -i * 0.5)
    if dtype == "datetime64":  # F14: counts of milliseconds
        return NAT if i % 11 == 4 else i * 86_400_000 - 1_000_000_000_000
    if dtype == "timedelta64":  # counts of 10 s
        return NAT if i % 13 == 6 else (i - 40) * 7
    if dtype == "utf32":
        return UTF[i % len(UTF)] + str(i)
    if dtype == "nullbytes":
        n = i % 6
        return bytes(0 if j == 1 and n > 2 else (i * 31 + j * 7) % 255 + 1 for j in range(n))
    if dtype == "rawbytes":
        return bytes((i * 31 + k * 7) & 0xff for k in range(3))
    if dtype == "struct":
        a = (i * 2654435761) % 2**32
        c = bytes([65 + i % 26]) * (i % 3)
        d = ("\U0001f600" if i % 5 == 0 else "δ") + str(i % 10)
        return record(a - 2**32 if a >= 2**31 else a, i * 0.25 - 3, c, d, NAT if i % 9 == 2 else i * 1000,
                      i % 2 == 0, i - 500, i % 256)
    raise ValueError(dtype)


def normalized(dtype, values):
    """An extension type's elements as value() gives them: time counts, text, bytes, or little-endian packed."""
    a = np.asarray(values)
    if dtype in ("datetime64", "timedelta64"):
        return [int(v) for v in a.astype(a.dtype.newbyteorder("<")).reshape(-1).view("<i8")]
    if dtype == "utf32":
        return [str(v) for v in a.reshape(-1)]
    if dtype == "nullbytes":
        return [bytes(v) for v in a.reshape(-1)]
    if dtype == "rawbytes":
        return [v.tobytes() for v in a.reshape(-1)]
    return [v.tobytes() for v in a.astype(a.dtype.newbyteorder("<")).reshape(-1)]


def same(dtype, got, want):
    if dtype == "string" or dtype in EXTENSION:
        return got == want
    if dtype == "bytes":
        return bytes(got) == want
    if dtype.startswith("float"):
        if isinstance(want, float) and math.isnan(want):
            return math.isnan(float(got))
        return float(got) == float(np.dtype(dtype).type(want))
    if dtype.startswith("complex"):
        return complex(got) == want
    if dtype == "bool":
        return bool(got) == want
    return int(got) == want


def chunk_files(directory, name):
    root = os.path.join(directory, name, "c")
    for d, _, files in os.walk(root):
        for f in files:
            yield os.path.join(d, f)


def layout_problem(directory, name):
    """For arrays whose codec configuration Falcon must honour (I9), what the stored bytes get wrong."""
    if name == "int32_zstd_checksum":
        for path in chunk_files(directory, name):
            frame = open(path, "rb").read()
            if not (frame[4] >> 2) & 1:
                return f"{os.path.basename(path)}: zstd frame without the configured content checksum"
    if name.startswith("float64_blosc_") or name == "int16_blosc_lz4":
        want = {"noshuffle": 0, "shuffle": 1, "lz4": 1, "bitshuffle": 4}[name.rsplit("_", 1)[1]]
        checked = 0
        for path in chunk_files(directory, name):
            header = open(path, "rb").read(16)
            if header[2] & 0x02:
                continue  # stored as it is (memcpy): no shuffle to see
            checked += 1
            if header[2] & 0x05 != want:
                return f"{os.path.basename(path)}: blosc shuffle flags {header[2] & 0x05}, configured {want}"
        if not checked:
            return "every chunk stored as memcpy; the shuffle could not be checked"
    if "_write_empty" in name and "_rectilinear" not in name:  # a regular 3 x 2 grid
        files = list(chunk_files(directory, name))
        if len(files) != 6:  # a 3 x 2 grid of chunks (or shards)
            return f"{len(files)} chunk files stored, expected all 6"
        if name.endswith("_sharded"):
            for path in files:
                shard = open(path, "rb").read()
                index = shard[len(shard) - 4 - 4 * 16:len(shard) - 4]
                for k in range(4):
                    if index[16 * k:16 * k + 16] == b"\xff" * 16:
                        return f"{os.path.basename(path)}: sub-chunk {k} omitted, though written as fill"
    if "_nested" in name:  # F11: the metadata must really nest a shard in a shard
        meta = json.load(open(os.path.join(directory, name, "zarr.json"), encoding="utf-8"))
        outer = meta["codecs"][0]
        if outer["name"] != "sharding_indexed" or not any(
                c["name"] == "sharding_indexed" for c in outer["configuration"]["codecs"]):
            return "not a shard nested in a shard"
    if "_rectilinear" in name:  # F14: the grid, and the chunks it says are stored
        meta = json.load(open(os.path.join(directory, name, "zarr.json"), encoding="utf-8"))
        if meta["chunk_grid"]["name"] != "rectilinear":
            return f"chunk grid {meta['chunk_grid']['name']}, not rectilinear"
        shapes = meta["chunk_grid"]["configuration"]["chunk_shapes"]
        want = RECTILINEAR_GRIDS.get(name)
        if want is not None and shapes != want:
            return f"chunk_shapes {json.dumps(shapes)}, expected {json.dumps(want)}"
        files = list(chunk_files(directory, name))
        if name.endswith("_write_empty") and len(files) != 9:  # a 3 x 3 grid
            return f"{len(files)} chunk files stored, expected all 9"
        if name.endswith("_past"):  # chunks reaching past the array are stored at their listed length
            sizes = sorted({os.path.getsize(f) for f in files})
            if sizes != [6 * 4 * 4]:
                return f"chunk sizes {sizes}, expected every chunk 6 x 4 int32 = 96 bytes"
    if "_resized" in name:
        left = [f for f in chunk_files(directory, name)
                if os.path.relpath(f, os.path.join(directory, name, "c")).split(os.sep)[0] == "2"]
        if left:  # an empty directory may stay behind, as zarr-python's LocalStore leaves it too
            return f"{len(left)} chunks outside the shrunk shape (row 2 of the grid) are still stored"
    return None


# F14: the chunk_shapes Falcon must write, as zarr-python writes them (compressed when that is shorter).
RECTILINEAR_GRIDS = {
    "int32_rectilinear": [[2, 5, 6], [3, 1, 3]],
    "int16_rectilinear_runs": [[[1, 5], [4, 2]], 3],
    "int32_rectilinear_past": [[[6, 3]], [[4, 2]]],
    "int32_rectilinear_sharded": [[6, 3, 6], [[4, 2]]],
    "int32_rectilinear_grown": [[4, 6, 3], [2, 3, 2]],
    "float64_rectilinear_regrown": [[2, 5, 6], [3, 1, 3]],
}


def main(directory):
    zarr.config.set({"array.rectilinear_chunks": True})  # F14: zarr-python reads rectilinear grids only so
    manifest = json.load(open(os.path.join(directory, "manifest.json"), encoding="utf-8"))
    failures = 0
    for case in manifest:
        name, dtype, written = case["name"], case["dtype"], case["written"]
        try:
            a = zarr.open_array(os.path.join(directory, name), mode="r")
            got = a[...].reshape(-1)
            fill = a.fill_value
            if dtype in EXTENSION:
                got = normalized(dtype, got)
                fill = normalized(dtype, [fill])[0]
                if name.endswith("_partial") and fill != EXTENSION_FILL[dtype]:
                    failures += 1
                    print(f"FAIL {name}: fill value {fill!r}, Falcon wrote {EXTENSION_FILL[dtype]!r}")
                    continue
            bad = []
            for i in range(SHAPE[0] * SHAPE[1]):
                r, c = divmod(i, SHAPE[1])
                inside = written is None or (written[0] <= r < written[1] and written[2] <= c < written[3])
                want = value(dtype, i) if inside else (fill.item() if hasattr(fill, "item") else fill)
                if not same(dtype, got[i], want):
                    bad.append((i, got[i], want))
            problem = layout_problem(directory, name)
            if bad:
                failures += 1
                print(f"FAIL {name}: {len(bad)} elements differ, first {bad[:3]}")
            elif problem:
                failures += 1
                print(f"FAIL {name}: {problem}")
            else:
                print(f"ok   {name}")
        except Exception as e:  # noqa: BLE001 -- report every case
            failures += 1
            print(f"FAIL {name}: {type(e).__name__}: {e}")
    print(f"{len(manifest) - failures} of {len(manifest)} arrays read back as written")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
