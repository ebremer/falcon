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
too). P2 F3 added Blosc's other internal compressors and numcodecs' Zlib and LZ4 (numcodecs.zlib and
numcodecs.lz4): each of those chunks must be the bytes numcodecs itself writes for the same data and
configuration (Blosc's zstd excepted: Falcon's zstd encoder is its own); numcodecs' BZ2 (numcodecs.bz2) is
checked the same way. The cast_value arrays (cast_*; reading them needs cast-value-rs) must read as zarr-python
reads its own write of the same data into the same metadata, each written element its value cast and cast back
by cast-value-rs, and their chunks (a shard's sub-chunk by sub-chunk) must be zarr-python's byte for byte unless
zstd or gzip compresses them. The numcodecs.zfpy arrays (zfpy_*; zfp is lossy) must read as zarr-python reads
its own write of the same data into the same metadata, and their chunks must be zfpy's byte for byte (P2 F18).
Dev-time tool; zarr-python is not a Falcon dependency. Run it before every release:

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
import numcodecs
import numcodecs.blosc
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


BLOSC_FORMATS = {"blosclz": 0, "lz4": 1, "lz4hc": 1, "zlib": 3}  # the header's compressor format, by cname
SHUFFLES = {"noshuffle": 0, "shuffle": 1, "bitshuffle": 2}


def numcodecs_problem(directory, name):
    """F3: a Blosc chunk must be c-blosc's, and a numcodecs.zlib/lz4/bz2 chunk numcodecs' own, byte for byte."""
    meta = json.load(open(os.path.join(directory, name, "zarr.json"), encoding="utf-8"))
    codec = meta["codecs"][-1]
    conf = codec.get("configuration", {})
    for path in chunk_files(directory, name):
        stored = open(path, "rb").read()
        if codec["name"] == "blosc":
            if stored[2] >> 5 != BLOSC_FORMATS[conf["cname"]]:
                return f"{os.path.basename(path)}: blosc compressor format {stored[2] >> 5}, cname {conf['cname']}"
            raw = bytes(numcodecs.blosc.decompress(stored))
            ref = bytes(numcodecs.blosc.compress(raw, conf["cname"].encode(), conf["clevel"],
                                                 SHUFFLES[conf["shuffle"]], conf["blocksize"], conf["typesize"]))
        elif codec["name"] == "numcodecs.zlib":
            raw = numcodecs.Zlib().decode(stored)
            ref = bytes(numcodecs.Zlib(**conf).encode(raw))
        elif codec["name"] == "numcodecs.bz2":
            raw = numcodecs.BZ2().decode(stored)
            ref = bytes(numcodecs.BZ2(**conf).encode(raw))
        else:
            raw = numcodecs.LZ4().decode(stored)
            ref = bytes(numcodecs.LZ4(**conf).encode(raw))
        if ref != stored:
            return f"{os.path.basename(path)}: {len(stored)} bytes, not numcodecs' {len(ref)}"
    return None


def layout_problem(directory, name):
    """For arrays whose codec configuration Falcon must honour (I9), what the stored bytes get wrong."""
    blosc_cname = name.split("_blosc_")[1].split("_")[0] if "_blosc_" in name else None
    if blosc_cname in BLOSC_FORMATS or ("_numcodecs_" in name and not name.endswith("_sharded")):
        problem = numcodecs_problem(directory, name)
        if problem:
            return problem
    if name == "int32_zstd_checksum":
        for path in chunk_files(directory, name):
            frame = open(path, "rb").read()
            if not (frame[4] >> 2) & 1:
                return f"{os.path.basename(path)}: zstd frame without the configured content checksum"
    if name.startswith("float64_blosc_") and blosc_cname not in BLOSC_FORMATS:  # zstd: no byte check above
        want = {"noshuffle": 0, "shuffle": 1, "bitshuffle": 4}[name.rsplit("_", 1)[1]]
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


def cast_codecs(codecs):
    """The cast_value configurations of a codec list, outermost first (into a shard's own codecs)."""
    out = []
    for c in codecs:
        if c["name"] == "cast_value":
            out.append(c["configuration"])
        elif c["name"] == "sharding_indexed":
            out += cast_codecs(c["configuration"]["codecs"])
    return out


def cast_through(values, configurations):
    """values cast by each configuration and back again, as cast-value-rs (zarr-python's backend) casts them."""
    from cast_value_rs import cast_array

    def run(a, conf, target, side):
        entries = None
        if conf.get("scalar_map") and conf["scalar_map"].get(side):
            to_src = int if a.dtype.kind in "iu" else float
            to_tgt = int if np.dtype(target).kind in "iu" else float
            entries = {to_src(k): to_tgt(v) for k, v in conf["scalar_map"][side]}
        return cast_array(np.ascontiguousarray(a), target_dtype=str(np.dtype(target)),
                          rounding_mode=conf.get("rounding", "nearest-even"),
                          out_of_range_mode=conf.get("out_of_range"), scalar_map_entries=entries)

    types = [values.dtype]
    a = values
    for conf in configurations:
        a = run(a, conf, conf["data_type"], "encode")
        types.append(a.dtype)
    for conf, back in zip(reversed(configurations), reversed(types[:-1])):
        a = run(a, conf, back, "decode")
    return a


def sub_chunks(shard, count):
    """A shard's sub-chunks (None for an absent one), by its index at the end (little-endian, crc32c)."""
    index = shard[len(shard) - 4 - 16 * count:len(shard) - 4]
    out = []
    for k in range(count):
        offset, length = struct.unpack("<QQ", index[16 * k:16 * k + 16])
        out.append(None if offset == length == 2 ** 64 - 1 else shard[offset:offset + length])
    return out


def zfpy_problem(directory, name, dtype, written):
    """A numcodecs.zfpy array Falcon wrote (zfp is lossy): every element must read as zarr-python reads its own
    write of the same values into the same metadata, and the chunks must be zarr-python's (zfpy's) byte for
    byte, a shard's sub-chunks one by one."""
    import shutil
    import tempfile
    path = os.path.join(directory, name)
    meta = json.load(open(os.path.join(path, "zarr.json"), encoding="utf-8"))
    got = zarr.open_array(path, mode="r")[...]
    if written is None:
        rows, cols = (0, SHAPE[0]), (0, SHAPE[1])
    else:
        rows, cols = (written[0], written[1]), (written[2], written[3])
    values = np.array([[value(dtype, r * SHAPE[1] + c) for c in range(*cols)] for r in range(*rows)], dtype=dtype)
    with tempfile.TemporaryDirectory() as tmp:
        own = os.path.join(tmp, name)
        os.makedirs(own)
        shutil.copy(os.path.join(path, "zarr.json"), own)
        z = zarr.open_array(own, mode="r+")
        z[rows[0]:rows[1], cols[0]:cols[1]] = values
        theirs = z[...]
        unsigned = np.dtype(f"u{got.dtype.itemsize}")
        if not np.array_equal(got.view(unsigned), theirs.view(unsigned)):
            bad = tuple(np.argwhere(got.view(unsigned) != theirs.view(unsigned))[0])
            return f"element {bad} reads {got[bad]!r}; zarr-python's own write reads {theirs[bad]!r}"
        ours = {os.path.relpath(f, path): open(f, "rb").read() for f in chunk_files(directory, name)}
        their = {os.path.relpath(f, own): open(f, "rb").read() for f in chunk_files(tmp, name)}
        if sorted(ours) != sorted(their):
            return f"chunks {sorted(ours)}, zarr-python stores {sorted(their)}"
        outer = meta["codecs"][-1]
        count = 0
        if outer["name"] == "sharding_indexed":
            count = 1
            for whole, sub in zip(meta["chunk_grid"]["configuration"]["chunk_shape"],
                                  outer["configuration"]["chunk_shape"]):
                count *= whole // sub
        for key, data in ours.items():
            same = sub_chunks(data, count) == sub_chunks(their[key], count) if count else data == their[key]
            if not same:
                return f"chunk {key} differs from zarr-python's"
    return None


def cast_problem(directory, name, dtype, written):
    """A cast_value array Falcon wrote: each written element must read as its value cast and cast back by
    cast-value-rs, every element as zarr-python reads its own write of the same data into the same metadata,
    and, where the codecs are deterministic (no zstd, no gzip), the chunks must be zarr-python's byte for byte
    (a shard's sub-chunks one by one: zarr-python lays them out in Morton order, Falcon in C order)."""
    import shutil
    import tempfile
    path = os.path.join(directory, name)
    meta = json.load(open(os.path.join(path, "zarr.json"), encoding="utf-8"))
    a = zarr.open_array(path, mode="r")
    got = a[...]
    np_dtype = np.dtype("uint64" if dtype == "uint64x" else dtype)
    if written is None:
        rows, cols = (0, SHAPE[0]), (0, SHAPE[1])
    else:
        rows, cols = (written[0], written[1]), (written[2], written[3])
    values = np.array([[value(dtype, r * SHAPE[1] + c) for c in range(*cols)] for r in range(*rows)], dtype=np_dtype)
    unsigned = np.dtype(f"u{np_dtype.itemsize}")

    # A written element equal to the fill value, in a (sub-)chunk of nothing else, is not stored (zarr-python
    # compares a chunk with the fill value before encoding it), and reads as the fill value itself.
    want = cast_through(values.reshape(-1), cast_codecs(meta["codecs"])).reshape(values.shape).view(unsigned)
    region = got[rows[0]:rows[1], cols[0]:cols[1]].view(unsigned)
    fill = np.array(a.fill_value, dtype=np_dtype).view(unsigned)
    ok = (region == want) | ((values.view(unsigned) == fill) & (region == fill))
    if not ok.all():
        bad = tuple(np.argwhere(~ok)[0])
        return (f"written element {bad} reads {got[rows[0] + bad[0], cols[0] + bad[1]]!r}, cast-value-rs gives "
                f"{want.view(np_dtype)[bad]!r}")

    with tempfile.TemporaryDirectory() as tmp:
        own = os.path.join(tmp, name)
        os.makedirs(own)
        shutil.copy(os.path.join(path, "zarr.json"), own)
        z = zarr.open_array(own, mode="r+")
        z[rows[0]:rows[1], cols[0]:cols[1]] = values
        theirs = z[...]
        if not np.array_equal(got.view(unsigned), theirs.view(unsigned)):
            bad = tuple(np.argwhere(got.view(unsigned) != theirs.view(unsigned))[0])
            return f"element {bad} reads {got[bad]!r}; zarr-python's own write reads {theirs[bad]!r}"
        names = json.dumps(meta["codecs"])
        if '"zstd"' in names or '"gzip"' in names:
            return None
        ours = {os.path.relpath(f, path): open(f, "rb").read() for f in chunk_files(directory, name)}
        their = {os.path.relpath(f, own): open(f, "rb").read() for f in chunk_files(tmp, name)}
        if sorted(ours) != sorted(their):
            return f"chunks {sorted(ours)}, zarr-python stores {sorted(their)}"
        outer = meta["codecs"][-1]
        count = 0
        if outer["name"] == "sharding_indexed":
            count = 1
            for whole, sub in zip(meta["chunk_grid"]["configuration"]["chunk_shape"],
                                  outer["configuration"]["chunk_shape"]):
                count *= whole // sub
        for key, data in ours.items():
            same = sub_chunks(data, count) == sub_chunks(their[key], count) if count else data == their[key]
            if not same:
                return f"chunk {key} differs from zarr-python's"
    return None


def main(directory):
    numcodecs.blosc.set_nthreads(1)  # blocks in order, as Falcon writes them
    zarr.config.set({"array.rectilinear_chunks": True})  # F14: zarr-python reads rectilinear grids only so
    manifest = json.load(open(os.path.join(directory, "manifest.json"), encoding="utf-8"))
    failures = 0
    for case in manifest:
        name, dtype, written = case["name"], case["dtype"], case["written"]
        if name.startswith("cast_") or name.startswith("zfpy_"):
            try:
                check = cast_problem if name.startswith("cast_") else zfpy_problem
                problem = check(directory, name, dtype, written)
            except Exception as e:  # noqa: BLE001 -- report every case
                problem = f"{type(e).__name__}: {e}"
            failures += problem is not None
            print(f"FAIL {name}: {problem}" if problem else f"ok   {name}")
            continue
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
