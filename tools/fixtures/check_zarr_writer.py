"""Reads every array WriteZarrCases.java wrote with Falcon back with zarr-python, and checks each element.

Falcon's reader is checked against zarr-python's stores (gen_zarr_fixtures.py and friends); this checks the
other direction, that what Falcon *writes* zarr-python reads as intended (P1 T1): every core data type,
every codec Falcon writes, sharding with the index at either end, partial writes, strings, and codec
configurations Falcon must honour in arrays it did not create (for those, the stored bytes are checked to
follow the configuration as well: zstd checksums, Blosc shuffle modes). Dev-time tool; zarr-python is not
a Falcon dependency. Run it before every release:

    mvn -pl zarr -am compile
    java -cp "zarr/target/classes;core/target/classes" tools/fixtures/WriteZarrCases.java OUT_DIR
    python tools/fixtures/check_zarr_writer.py OUT_DIR

Element i of each array (flattened C order) is the formula in value() below, the same one
WriteZarrCases.java writes; anything outside the written region is the fill value.
"""
import json
import math
import os
import sys

import numpy as np
import zarr

POOL = ["alpha", "", "gamma-δ", "中文", "emoji-\U0001f600", "x"]
SHAPE = (13, 7)


def value(dtype, i):
    if dtype == "string":
        return POOL[i % len(POOL)] + str(i)
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
    if dtype in ("complex64", "complex128"):
        return complex(i, -i * 0.5)
    raise ValueError(dtype)


def same(dtype, got, want):
    if dtype == "string":
        return got == want
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
    return None


def main(directory):
    manifest = json.load(open(os.path.join(directory, "manifest.json"), encoding="utf-8"))
    failures = 0
    for case in manifest:
        name, dtype, written = case["name"], case["dtype"], case["written"]
        try:
            a = zarr.open_array(os.path.join(directory, name), mode="r")
            got = a[...].reshape(-1)
            fill = a.fill_value
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
