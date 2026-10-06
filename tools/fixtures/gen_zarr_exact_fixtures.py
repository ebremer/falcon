"""Generates the fixtures for P2 F8's exact accessors (readUnsignedLongs, readComplex) with zarr-python.

Dev-time tool only, like gen_zarr_data_fixtures.py (whose sidecar format these share): zarr-python is not a
Falcon dependency. Run from the repo root:  python tools/fixtures/gen_zarr_exact_fixtures.py

Each case writes a store under zarr/src/test/resources/fixtures/<name>/ and <name>.expected.json. Values
that a double cannot hold exactly are written as text: uint64 values in decimal, complex parts as
float.hex() strings ("nan", "inf", and "-inf" for the non-finite ones).

  uint64_full        uint64 values up to 2^64 - 1, most with no exact double, chunked, zstd
  complex64_parts    complex64, NaN and infinite parts included, sharded
  complex128_parts   complex128, big-endian, two chunks
"""
import json, os, shutil
import numpy as np
import zarr
from zarr.codecs import BytesCodec, ZstdCodec

OUT = os.path.join("zarr", "src", "test", "resources", "fixtures")


def fresh(name):
    path = os.path.join(OUT, name)
    if os.path.exists(path):
        shutil.rmtree(path)
    return path


def sidecar(name, shape, dtype, values):
    meta = dict(name=name, shape=list(shape), dtype=dtype, attributes={}, values=values)
    with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=1)
    print(f"  {name:18s} {dtype:10s} {tuple(shape)}")


def parts(z):
    return [[float(c.real).hex(), float(c.imag).hex()] for c in z]


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"zarr-python {zarr.__version__} -> {OUT}")

    values = [0, 1, 2**63 - 1, 2**63, 2**63 + 1025, 2**64 - 1, 12345678901234567891, 2**53 + 1]
    z = zarr.create_array(store=fresh("uint64_full"), shape=(len(values),), chunks=(3,), dtype="uint64",
                          compressors=[ZstdCodec(level=3)])
    z[...] = np.array(values, dtype="uint64")
    sidecar("uint64_full", (len(values),), "uint64", [str(v) for v in values])

    c = np.array([1.5 - 2.25j, 0.1 + 0.2j, complex(np.nan, 1), complex(np.inf, -np.inf), -0.0 + 3e-30j,
                  65504 + 1j], dtype="complex64")
    z = zarr.create_array(store=fresh("complex64_parts"), shape=(6,), chunks=(2,), shards=(6,),
                          dtype="complex64", fill_value=0)
    z[...] = c
    sidecar("complex64_parts", (6,), "complex64", parts(c))

    c = np.array([[1e300 + 1e-300j, -0.5 + 0.25j, 0.1 + 0.7j], [complex(0, np.nan), 2 ** 60 + 0j, -1 - 1j]],
                 dtype="complex128")
    z = zarr.create_array(store=fresh("complex128_parts"), shape=(2, 3), chunks=(1, 3), dtype="complex128",
                          fill_value=0, serializer=BytesCodec(endian="big"), compressors=None)
    z[...] = c
    sidecar("complex128_parts", (2, 3), "complex128", parts(c.reshape(-1)))


if __name__ == "__main__":
    main()
