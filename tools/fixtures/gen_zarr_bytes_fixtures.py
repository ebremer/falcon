"""Generates the variable_length_bytes fixtures added with P2 (F5) with zarr-python.

Dev-time tool only, like gen_zarr_data_fixtures.py (whose sidecar format these share): zarr-python is not a
Falcon dependency. Run from the repo root:  python tools/fixtures/gen_zarr_bytes_fixtures.py

Each case writes a store under zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json, whose
"values" are the elements in C order as hex strings. Only these cases are (re)written.

  bytes_plain        variable_length_bytes, 2-D, several chunks, zstd (zarr-python's defaults otherwise)
  bytes_sharded      ... sharded, a fill value of three bytes, only part written
  bytes_transposed   transpose before vlen-bytes, as zarr-python orders them
"""
import json, os, shutil
import warnings
import numpy as np
import zarr
from zarr.codecs import TransposeCodec, ZstdCodec
from zarr.core.dtype import VariableLengthBytes

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")


def fresh(name):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        shutil.rmtree(path)
    return path


def sidecar(name, shape, values, fill):
    meta = dict(name=name, shape=list(shape), dtype="variable_length_bytes", attributes={},
                fill=fill.hex(), values=[v.hex() for v in values])
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1)
    print(f"  {name:20s} {tuple(shape)}")


def value(i):
    """Byte strings of 0 to 8 bytes, zeros and high bytes included (BytesArrayTest.value)."""
    return bytes(((i * 31 + j * 7) & 0xff) for j in range(i % 9))


def values(n, shape):
    out = np.empty(n, dtype=object)
    for i in range(n):
        out[i] = value(i)
    return out.reshape(shape)


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} -> {OUT}")
    warnings.simplefilter("ignore")  # zarr-python warns that the v3 bytes data type is not yet in the spec

    z = zarr.create_array(store=fresh("bytes_plain"), shape=(7, 5), chunks=(3, 2),
                          dtype=VariableLengthBytes(), compressors=[ZstdCodec(level=3)])
    v = values(35, (7, 5))
    z[...] = v
    sidecar("bytes_plain", (7, 5), list(v.reshape(-1)), b"")

    fill = b"\x00\xff?"
    z = zarr.create_array(store=fresh("bytes_sharded"), shape=(8, 6), chunks=(2, 3), shards=(4, 6),
                          dtype=VariableLengthBytes(), fill_value=fill)
    v = values(48, (8, 6))
    z[1:6, 2:5] = v[1:6, 2:5]
    want = [v[r, c] if 1 <= r < 6 and 2 <= c < 5 else fill for r in range(8) for c in range(6)]
    sidecar("bytes_sharded", (8, 6), want, fill)

    z = zarr.create_array(store=fresh("bytes_transposed"), shape=(3, 4), chunks=(2, 4),
                          dtype=VariableLengthBytes(), filters=[TransposeCodec(order=(1, 0))])
    v = values(12, (3, 4))
    z[...] = v
    sidecar("bytes_transposed", (3, 4), list(v.reshape(-1)), b"")


if __name__ == "__main__":
    main()
