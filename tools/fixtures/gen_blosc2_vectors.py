"""Generates c-blosc2 reference chunks (Blosc format versions 3 to 6) with imagecodecs, which bundles c-blosc2.

Dev-time tool only -- imagecodecs is NOT a Falcon dependency, exactly like numcodecs for
gen_blosc_vectors.py. Install with:  pip install imagecodecs numpy
Run from the repo root:  python tools/fixtures/gen_blosc2_vectors.py

Writes core/src/test/resources/fixtures/blosc2_vectors.txt with one case per line:

    <name> <cname> <clevel> <filter> <typesize> <original-hex> <chunk-hex>

Two kinds of case, both checked against c-blosc2 before they are written:

- encoded: chunks c-blosc2 compressed (imagecodecs.blosc2_encode): every internal codec under
  every filter imagecodecs can ask for (none, byte shuffle, bit shuffle, delta), type sizes 1 to
  255, the split modes, small, odd, and clamped block sizes, empty, one-byte, multi-block and
  ragged sizes, incompressible data (memcpy'ed chunks), and all-zero data (a special-value chunk).
  imagecodecs takes the type size from the array's dtype (an int8 array for 1; a uint8 array gets
  8, which gives sizes that are not a multiple of the type size).
- crafted: chunks c-blosc2 writes but imagecodecs cannot ask for -- special NaN and repeated-value
  chunks, several filters in the pipeline, filters_meta, truncated precision, headers without the
  extended part, the older format versions 3 and 4, a version-6 chunk without variable-length
  blocks, a block size larger than the data, blocks out of order, and zero, run, raw, and zlib
  streams -- assembled here byte by byte following c-blosc2's README_CHUNK_FORMAT.rst and blosc2.c.

Every chunk is decoded with c-blosc2 (imagecodecs.blosc2_decode) and must give its original: c-blosc2
is the oracle for both kinds, so a crafted chunk that c-blosc2 reads differently fails here.
"""
import os
import struct
import zlib

import imagecodecs
import numpy as np

OUT = os.path.join("core", "src", "test", "resources", "fixtures", "blosc2_vectors.txt")

FILTER_NAMES = {0: "noshuffle", 1: "shuffle", 2: "bitshuffle", 3: "delta"}
CASES = []
rng = np.random.default_rng(20261006)


def check(name, chunk, original):
    decoded = bytes(imagecodecs.blosc2_decode(chunk)) if len(original) else b""
    if len(original) == 0:
        # c-blosc2 decodes an empty chunk to nothing; the header must say so.
        assert struct.unpack_from("<i", chunk, 4)[0] == 0, name
    elif decoded != original:
        raise AssertionError(f"{name}: c-blosc2 decodes the chunk to something else")
    assert chunk[0] >= 3, f"{name}: version {chunk[0]} is not a Blosc2 chunk"


def encoded(name, array, cname="lz4", level=5, shuffle=1, **kwargs):
    original = array.tobytes()
    chunk = bytes(imagecodecs.blosc2_encode(array, level=level, compressor=cname, shuffle=shuffle, **kwargs))
    check(name, chunk, original)
    CASES.append((name, cname, level, FILTER_NAMES[shuffle], chunk[3], original, chunk))


# ---------------------------------------------------------------------------------------------------
# Encoded by c-blosc2.

def int32_ramp(n):
    return (np.arange(n, dtype=np.int64) % 1000).astype("<i4")


def int32_steps(n):
    """Runs of 8 equal values: long repeats, which every codec compresses with or without a filter."""
    return (np.arange(n, dtype=np.int64) // 8 % 1000).astype("<i4")


def smooth_f64(n):
    return np.cumsum(rng.normal(0, 1, n)).astype("<f8")


def small_values(n, dtype):
    """Values that compress but are not trivial: random bytes below 32, viewed as `dtype`."""
    itemsize = np.dtype(dtype).itemsize
    return rng.integers(0, 32, n * itemsize, dtype=np.uint8).view(dtype)


TEXT = np.frombuffer(b"the quick brown fox jumps over the lazy dog. " * 300, dtype="i1")

# Every internal codec under every filter, on 4 blocks of int32, and on text.
for cname in ("blosclz", "lz4", "lz4hc", "zlib", "zstd"):
    for shuffle in (0, 1, 2, 3):
        encoded(f"{cname}_{FILTER_NAMES[shuffle]}_int32", int32_steps(4000), cname=cname, shuffle=shuffle,
                blocksize=4096)
    encoded(f"{cname}_text", TEXT, cname=cname, shuffle=0)

# Type sizes 1 to 255 under the byte shuffle, the bit shuffle, and delta.
for dtype in ("i1", "<i2", "<i4", "<i8", "V3", "V5", "V7", "V16", "V24", "V32", "V255"):
    tag = dtype.lstrip("<")
    for shuffle in (1, 2, 3):
        encoded(f"typesize_{tag}_{FILTER_NAMES[shuffle]}", small_values(max(96, 9600 // np.dtype(dtype).itemsize),
                                                                        dtype), cname="zstd",
                shuffle=shuffle)
encoded("float64_smooth_shuffle", smooth_f64(5000), cname="zstd", shuffle=1)
encoded("float64_smooth_bitshuffle", smooth_f64(5000), cname="lz4", shuffle=2)

# Split modes (imagecodecs: ALWAYS, NEVER, AUTO, FORWARD_COMPAT).
for mode in imagecodecs.BLOSC2.SPLIT:
    encoded(f"split_{mode.name.lower()}_int32", int32_ramp(6000), cname="zstd", splitmode=mode)
    encoded(f"split_{mode.name.lower()}_float64", smooth_f64(3000), cname="lz4", splitmode=mode)

# Block sizes: small, odd (a multiple of the type size, as c-blosc2 needs), and larger than the data
# (clamped). Blocks that end short of a group of 8 elements exercise the bit shuffle's copied tail.
encoded("blocksize_64", int32_ramp(3000), cname="lz4", blocksize=64)
encoded("blocksize_odd_v5", small_values(3000, "V5"), cname="zstd", blocksize=4095)
encoded("blocksize_odd_i2", small_values(3000, "<i2"), cname="zstd", blocksize=4094)
encoded("blocksize_clamped", int32_ramp(1000), cname="zstd", blocksize=1 << 20)
encoded("bitshuffle_partial_groups", int32_ramp(5001), cname="lz4", shuffle=2, blocksize=4 * 1004)
encoded("bitshuffle_partial_groups_v3", small_values(4001, "V3"), cname="zstd", shuffle=2, blocksize=3 * 1003)
encoded("delta_many_blocks_i2", small_values(5000, "<i2"), cname="zstd", shuffle=3, blocksize=1000)
encoded("delta_many_blocks_v3", small_values(3001, "V3"), cname="zstd", shuffle=3, blocksize=999)
encoded("delta_many_blocks_v24", small_values(1001, "V24"), cname="zstd", shuffle=3, blocksize=2400)

# Sizes: empty, one byte, below a block, many blocks, and not a whole number of 8-byte values.
encoded("empty", np.zeros(0, dtype="i1"))
encoded("one_byte", np.frombuffer(b"Z", dtype="i1"))
encoded("below_a_block", int32_ramp(25), cname="zstd")
encoded("multiblock_float64", smooth_f64(8000), cname="zstd", blocksize=16384)
encoded("multiblock_int32_lz4", int32_ramp(30000), cname="lz4", blocksize=16384)
encoded("ragged_typesize8", small_values(1001, "u1"), cname="zstd")  # uint8: imagecodecs uses type size 8
encoded("ragged_typesize8_multiblock", (np.arange(40003) % 251).astype("u1"), cname="zstd", blocksize=8192)

# Special and per-stream encodings c-blosc2 chooses itself: all zeros (a header-only chunk), byte
# planes that are zero or one repeated byte (csize 0 and run streams), NaN and constant arrays.
encoded("zeros", np.zeros(4000, dtype="<i4"))
encoded("zeros_ragged", np.zeros(1001, dtype="u1"))
encoded("constant_int32", np.full(5000, 7, dtype="<i4"))
encoded("nan_float32", np.full(3000, np.nan, dtype="<f4"))
encoded("constant_planes_multiblock", np.full(20000, 0x01020304, dtype="<i4"), blocksize=16384)

# Incompressible data: c-blosc2 stores it memcpy'ed.
encoded("random_small", rng.integers(0, 256, 64, dtype=np.uint8).view("i1"))
encoded("random_large", rng.integers(0, 256, 20000, dtype=np.uint8).view("i1"), cname="zstd")
encoded("random_int64", rng.integers(-2**62, 2**62, 2500).astype("<i8"), cname="lz4")

# Levels.
for level in (0, 1, 9):
    encoded(f"level{level}_int32", int32_ramp(5000), cname="zstd", level=level)


# ---------------------------------------------------------------------------------------------------
# Crafted, following c-blosc2's chunk format, and checked by c-blosc2's decoder.

def shuffle_bytes(block, group):
    """c-blosc2's byte shuffle: byte b of every element, element by element; a ragged tail is copied."""
    if group <= 1:
        return bytes(block)
    n = len(block) // group
    body = np.frombuffer(block[:n * group], dtype=np.uint8).reshape(n, group).T.tobytes()
    return body + bytes(block[n * group:])


def bitshuffle_bytes(block, typesize):
    """c-blosc2's bit shuffle (format 3 on): whole groups of 8 elements transposed bit by bit -- row
    8 * byte + bit holds that bit of every element, least significant bit first -- the rest copied."""
    n = len(block) // typesize
    n -= n % 8
    if n == 0:
        return bytes(block)
    elements = np.frombuffer(block[:n * typesize], dtype=np.uint8).reshape(n, typesize)
    bits = np.unpackbits(elements, axis=1, bitorder="little")  # n x (8 * typesize), byte-major
    rows = np.packbits(bits.T, axis=1, bitorder="little")       # (8 * typesize) x (n / 8)
    return rows.tobytes() + bytes(block[n * typesize:])


def delta_bytes(data, offset, length, typesize):
    """c-blosc2's delta_encoder, XOR against the chunk's first block (the reference block against itself,
    shifted one element)."""
    width = typesize if typesize in (1, 2, 4, 8) else (8 if typesize % 8 == 0 else 1)
    block = bytearray(data[offset:offset + length])
    end = length // width * width
    for i in range(end):
        if offset == 0:
            if i >= width:
                block[i] = data[i] ^ data[i - width]
        else:
            block[i] = data[offset + i] ^ data[i]
    return bytes(block)


def forward(data, offset, length, filters, meta, typesize):
    """Applies the filter slots 0 to 5 to one block, as pipeline_forward does."""
    block = bytes(data[offset:offset + length])
    for slot, f in enumerate(filters):
        if f == 1:
            block = shuffle_bytes(block, meta[slot] or typesize)
        elif f == 2:
            block = bitshuffle_bytes(block, typesize)
        elif f == 3:
            assert slot == min(i for i, g in enumerate(filters) if g not in (0, 4)), \
                "c-blosc2's delta round-trips only as the first filter"
            block = delta_bytes(data, offset, length, typesize)
        # 4 (truncated precision) is lossy; the data here is taken as already truncated
    return block


def stream(payload, how):
    """One stream: 'raw' (csize = its size), 'zlib', or what the bytes allow ('auto': zeros, a run, raw)."""
    if how == "auto":
        if not any(payload):
            return struct.pack("<i", 0)
        if len(set(payload)) == 1:
            return struct.pack("<iB", -payload[0], 0x01)
        how = "raw"
    if how == "raw":
        return struct.pack("<i", len(payload)) + payload
    if how == "zlib":
        packed = zlib.compress(payload, 6)
        assert len(packed) != len(payload)
        return struct.pack("<i", len(packed)) + packed
    raise ValueError(how)


def crafted(name, data, typesize, *, filters=(0, 0, 0, 0, 0, 0), meta=(0,) * 6, blocksize=None, split=True,
            version=5, extended=True, flags_extra=0, how="auto", codec=3, header_blocksize=None,
            reverse_blocks=False, filter_label="crafted", alpha_garbage=False):
    """A chunk of `data` in blocks of `blocksize`, each split into `typesize` streams when `split` (the
    last, short block never is), its filters applied, its streams encoded as `how` says."""
    data = bytes(data)
    nbytes = len(data)
    blocksize = blocksize or nbytes
    nblocks = -(-nbytes // blocksize)
    overhead = 32 if extended else 16
    flags = (codec << 5) | (0 if split else 0x10) | flags_extra
    if extended:
        flags |= 0x05
    elif filters[5] == 1:
        flags |= 0x01
    elif filters[5] == 2:
        flags |= 0x04
    if not extended and filters[4] == 3:
        flags |= 0x08
    if flags_extra & 0x02:  # memcpy'ed: the data right after the header, no offsets, no filters
        header = struct.pack("<BBBBiii", version, 1, flags, typesize, nbytes, blocksize, overhead + nbytes)
        if extended:
            header += bytes(filters) + bytes([codec_id(codec), 0]) + bytes(meta) + bytes([0, 0])
        chunk = header + data
        check(name, chunk, data)
        CASES.append((name, "memcpy", 0, filter_label, typesize, data, chunk))
        return
    blocks = []
    order = list(range(nblocks))
    for b in order:
        length = min(blocksize, nbytes - b * blocksize)
        block = forward(data, b * blocksize, length, filters, meta, typesize)
        streams = typesize if split and length == blocksize else 1
        size = length // streams
        blocks.append(b"".join(stream(block[s * size:(s + 1) * size], how) for s in range(streams)))
    start = overhead + 4 * nblocks
    bstarts = [0] * nblocks
    body = b""
    for b in (reversed(order) if reverse_blocks else order):
        bstarts[b] = start + len(body)
        body += blocks[b]
    cbytes = start + len(body)
    header = struct.pack("<BBBBiii", version, 1, flags, typesize, nbytes, header_blocksize or blocksize, cbytes)
    if extended:
        stored_filters = list(filters)
        stored_meta = list(meta)
        if alpha_garbage:  # the alpha format left slot 5 uninitialised; readers ignore it
            stored_filters[5], stored_meta[5] = 0x2a, 0x17
        header += bytes(stored_filters) + bytes([codec_id(codec), 0]) + bytes(stored_meta) + bytes([0, 0])
    chunk = header + struct.pack(f"<{nblocks}i", *bstarts) + body
    check(name, chunk, data)
    CASES.append((name, {0: "blosclz", 1: "lz4", 3: "zlib", 4: "zstd"}[codec], 0, filter_label, typesize, data,
                  chunk))


def codec_id(codec):
    return {0: 0, 1: 1, 3: 4, 4: 5}[codec]  # the compressor ID c-blosc2 stores in udcodec


def special(name, nbytes, typesize, kind, blocksize, value=b""):
    """A header-only chunk: kind 1 zeros, 2 NaN, 3 a repeated value (its bytes follow the header)."""
    cbytes = 32 + len(value)
    header = struct.pack("<BBBBiii", 5, 1, 0x05, typesize & 0xff, nbytes, blocksize, cbytes)
    chunk = header + bytes(14) + bytes([0, kind << 4]) + value
    original = bytes(imagecodecs.blosc2_decode(chunk))
    if kind == 1:
        assert original == bytes(nbytes), name
    elif kind == 3:
        assert original == value * (nbytes // len(value)), name
    check(name, chunk, original)
    CASES.append((name, "special", 0, ["", "zeros", "nan", "value"][kind], typesize & 0xff, original, chunk))


ints = int32_ramp(2048).tobytes()
f64 = smooth_f64(1024).tobytes()
v3 = small_values(1365, "V3").tobytes()

# Special chunks: NaN as float32 and float64, values of 1 to 300 bytes, zeros of a ragged size.
special("special_nan_float32", 4096, 4, 2, 1024)
special("special_nan_float64", 8192, 8, 2, 8192)
special("special_value_1", 1000, 1, 3, 1000, b"\x5a")
special("special_value_int32", 4096, 4, 3, 512, struct.pack("<i", -123456))
special("special_value_3", 3000, 3, 3, 600, b"abc")
special("special_value_float64", 8000, 8, 3, 8000, struct.pack("<d", 3.25))
special("special_value_300", 3000, 300, 3, 1500, bytes(range(256)) + bytes(range(44)))
special("special_zeros_ragged", 1001, 8, 1, 1001)

# Several filters: delta then shuffle (what python-blosc2 writes for shuffle + delta), filters_meta,
# truncated precision, the bit shuffle after the byte shuffle, and odd slots.
crafted("pipeline_delta_shuffle", ints, 4, filters=(0, 0, 0, 0, 3, 1), blocksize=2048, how="zlib",
        filter_label="delta+shuffle")
crafted("pipeline_delta_shuffle_auto", ints, 4, filters=(0, 0, 0, 0, 3, 1), blocksize=1024,
        filter_label="delta+shuffle")
crafted("pipeline_delta_bitshuffle_v3", v3, 3, filters=(3, 0, 0, 0, 0, 2), blocksize=819, how="zlib",
        filter_label="delta+bitshuffle")
crafted("pipeline_shuffle_meta2", f64, 8, filters=(0, 0, 0, 0, 0, 1), meta=(0, 0, 0, 0, 0, 2),
        blocksize=4096, how="zlib", filter_label="shuffle/meta2")
crafted("pipeline_trunc_shuffle", f64, 8, filters=(0, 0, 0, 0, 4, 1), meta=(0, 0, 0, 0, 20, 0),
        blocksize=4096, how="zlib", filter_label="trunc+shuffle")
crafted("pipeline_shuffle_bitshuffle", ints, 4, filters=(0, 0, 0, 1, 2, 0), blocksize=2048, how="zlib",
        filter_label="shuffle+bitshuffle")
crafted("pipeline_bitshuffle_shuffle", f64, 8, filters=(2, 1, 0, 0, 0, 0), blocksize=2048, how="raw",
        filter_label="bitshuffle+shuffle")
crafted("pipeline_slot0_shuffle", f64, 8, filters=(1, 0, 0, 0, 0, 0), blocksize=8192, how="raw",
        filter_label="shuffle")

# Headers without the extended part: the flags carry the filters (byte shuffle 0x01, bit shuffle
# 0x04, delta 0x08), and the blocks start after 16 bytes.
crafted("short_header_v5_shuffle_delta", ints, 4, filters=(0, 0, 0, 0, 3, 1), blocksize=2048, extended=False,
        how="zlib", filter_label="delta+shuffle")
crafted("short_header_v4_bitshuffle", ints, 4, filters=(0, 0, 0, 0, 0, 2), blocksize=4096, extended=False,
        version=4, how="zlib", filter_label="bitshuffle")
crafted("short_header_v3_none", ints, 4, blocksize=3000, extended=False, version=3, split=False, how="zlib",
        filter_label="noshuffle")
crafted("short_header_memcpy_v4", ints[:1000], 4, extended=False, version=4, flags_extra=0x02, how="raw",
        filter_label="memcpy")

# Format versions and header details: the alpha format's garbage slot 5, a version-6 chunk without
# variable-length blocks, a block size larger than the data, blocks stored out of order, a ragged tail
# under the split, and an extended-header memcpy chunk with an odd type size.
crafted("alpha_v3_garbage_slot5", ints, 4, filters=(0, 0, 0, 0, 1, 0), blocksize=2048, version=3,
        alpha_garbage=True, how="zlib", filter_label="shuffle")
crafted("version6_regular", ints, 4, filters=(0, 0, 0, 0, 0, 1), blocksize=2048, version=6, how="zlib",
        filter_label="shuffle")
crafted("blocksize_over_nbytes", ints[:1000], 4, filters=(0, 0, 0, 0, 0, 1), header_blocksize=1 << 20,
        how="zlib", filter_label="shuffle")
crafted("blocks_out_of_order", ints, 4, filters=(0, 0, 0, 0, 0, 1), blocksize=1024, reverse_blocks=True,
        how="zlib", filter_label="shuffle")
crafted("ragged_split_tail", ints[:1003 * 2 + 1], 2, filters=(0, 0, 0, 0, 0, 1), blocksize=512, how="raw",
        filter_label="shuffle")
crafted("memcpy_extended_v5", v3[:999], 3, flags_extra=0x02, how="raw", filter_label="memcpy")
crafted("runs_and_raw_unsplit", bytes([7]) * 600 + bytes(500) + bytes(range(256)) * 2, 1, blocksize=600,
        split=False, filter_label="noshuffle")


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        f.write("# name cname clevel filter typesize original-hex chunk-hex\n")
        f.write(f"# generated by tools/fixtures/gen_blosc2_vectors.py "
                f"(imagecodecs {imagecodecs.__version__}, {imagecodecs.blosc2_version()})\n")
        for name, cname, level, filt, typesize, original, chunk in CASES:
            f.write(f"{name} {cname} {level} {filt} {typesize} {original.hex()} {chunk.hex()}\n")
            print(f"  {name:34s} {cname:8s} {filt:18s} ts={typesize:<3d} {len(original):>8d} -> {len(chunk):>8d} "
                  f"v{chunk[0]} flags=0x{chunk[2]:02x}")
    print(f"{len(CASES)} vectors -> {OUT}")


if __name__ == "__main__":
    main()
