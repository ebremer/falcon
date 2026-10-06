package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * The Snappy block format, which is what Blosc's {@code snappy} internal codec stores (the raw format of
 * {@code snappy_compress}/{@code snappy_uncompress}, not the framing format). c-blosc builds without
 * snappy by default (numcodecs' does), but other builds and older blosc buffers may use it.
 *
 * <p>The stream is a varint uncompressed length followed by elements. Each element's tag byte has the
 * type in its low two bits: a literal run (the length in the upper six bits, extended by up to four
 * trailing bytes for large runs), or a copy of an earlier run selected by a 1-, 2-, or 4-byte
 * back-reference offset. Copies may overlap the output written so far.
 *
 * <p>The compressor follows Google's snappy 1.2.2 ({@code CompressFragment}, its default level 1, with its
 * portable multiplicative hash, as x86 builds without SSE 4.2 hash): 64 KiB fragments, each with a fresh
 * hash table sized to it (up to 2^15 entries), a scan that skips faster the longer it finds no match, and
 * copies of at most 64 bytes. Its output is byte for byte that of the c-blosc in hdf5plugin 7.1, which
 * builds snappy 1.2.2 (snappy 1.1 hashed into at most 2^14 entries, so its streams differ).
 */
final class Snappy {

    private static final int BLOCK_SIZE = 1 << 16;
    private static final int MIN_HASH_TABLE_SIZE = 1 << 8;
    private static final int MAX_HASH_TABLE_BITS = 15; // kMaxHashTableBits: 15 since snappy 1.2
    private static final int INPUT_MARGIN = 15;

    private Snappy() {
    }

    /** {@code snappy_max_compressed_length}: {@code 32 + n + n / 6}. */
    static int maxCompressedLength(int length) {
        return 32 + length + length / 6;
    }

    /**
     * Compresses one block into {@code dst}, as {@code snappy_compress}: 0 if {@code maxOut} is below
     * {@link #maxCompressedLength} (snappy refuses a smaller buffer, whatever the data), else the stream's
     * length, which for incompressible data can exceed the input's.
     */
    static int compress(byte[] src, int off, int length, byte[] dst, int dstOff, int maxOut) {
        if (maxOut < maxCompressedLength(length)) {
            return 0;
        }
        int op = dstOff;
        for (int n = length; ; n >>>= 7) { // the uncompressed length, a varint
            if (n < 0x80) {
                dst[op++] = (byte) n;
                break;
            }
            dst[op++] = (byte) (n | 0x80);
        }
        int[] table = new int[1 << MAX_HASH_TABLE_BITS];
        for (int start = 0; start < length; start += BLOCK_SIZE) {
            int fragment = Math.min(BLOCK_SIZE, length - start);
            int tableSize = Math.max(MIN_HASH_TABLE_SIZE,
                    Math.min(1 << MAX_HASH_TABLE_BITS, Integer.highestOneBit(Math.max(fragment - 1, 1)) << 1));
            java.util.Arrays.fill(table, 0, tableSize, 0);
            op = compressFragment(src, off + start, fragment, dst, op, table, tableSize - 1);
        }
        return op - dstOff;
    }

    /** {@code CompressFragment}: one fragment of at most 64 KiB; returns the new output position. */
    private static int compressFragment(byte[] src, int input, int length, byte[] dst, int op, int[] table,
                                        int mask) {
        int ip = input;
        int ipEnd = input + length;
        if (length >= INPUT_MARGIN) {
            int ipLimit = ipEnd - INPUT_MARGIN;
            fragment:
            while (true) {
                int nextEmit = ip++;
                int skip = 32;
                int candidate;
                while (true) { // look for a 4-byte match, stepping further the longer none turns up
                    int data = read32(src, ip);
                    int entry = hash(data, mask);
                    int bytesBetweenHashLookups = skip >>> 5;
                    skip += bytesBetweenHashLookups;
                    int nextIp = ip + bytesBetweenHashLookups;
                    if (nextIp > ipLimit) {
                        ip = nextEmit;
                        break fragment;
                    }
                    candidate = input + table[entry];
                    table[entry] = ip - input;
                    if (data == read32(src, candidate)) {
                        break;
                    }
                    ip = nextIp;
                }
                op = emitLiteral(src, nextEmit, ip - nextEmit, dst, op);
                do { // copies, for as long as the bytes after one start another
                    int matched = 4;
                    while (ip + matched < ipEnd && src[candidate + matched] == src[ip + matched]) {
                        matched++;
                    }
                    int offset = ip - candidate;
                    ip += matched;
                    op = emitCopy(dst, op, offset, matched);
                    if (ip >= ipLimit) {
                        break fragment;
                    }
                    table[hash(read32(src, ip - 1), mask)] = ip - input - 1;
                    int entry = hash(read32(src, ip), mask);
                    candidate = input + table[entry];
                    table[entry] = ip - input;
                } while (read32(src, ip) == read32(src, candidate));
            }
        }
        if (ip < ipEnd) {
            op = emitLiteral(src, ip, ipEnd - ip, dst, op);
        }
        return op;
    }

    private static int emitLiteral(byte[] src, int from, int length, byte[] dst, int op) {
        int n = length - 1;
        if (n < 60) {
            dst[op++] = (byte) (n << 2);
        } else {
            int count = ((31 - Integer.numberOfLeadingZeros(n)) >>> 3) + 1;
            dst[op++] = (byte) ((59 + count) << 2);
            for (int i = 0; i < count; i++) {
                dst[op++] = (byte) (n >>> (8 * i));
            }
        }
        System.arraycopy(src, from, dst, op, length);
        return op + length;
    }

    /** {@code EmitCopy}: 64-byte copies while at least 68 remain, then one or two to finish. */
    private static int emitCopy(byte[] dst, int op, int offset, int length) {
        while (length >= 68) {
            op = emitCopyAtMost64(dst, op, offset, 64);
            length -= 64;
        }
        if (length > 64) {
            op = emitCopyAtMost64(dst, op, offset, 60);
            length -= 60;
        }
        return emitCopyAtMost64(dst, op, offset, length);
    }

    private static int emitCopyAtMost64(byte[] dst, int op, int offset, int length) {
        if (length < 12 && offset < 2048) { // a 1-byte offset: 3 bits of it in the tag
            dst[op++] = (byte) (1 + ((length - 4) << 2) + ((offset >>> 8) << 5));
            dst[op++] = (byte) offset;
        } else { // a 2-byte offset
            dst[op++] = (byte) (2 + ((length - 1) << 2));
            dst[op++] = (byte) offset;
            dst[op++] = (byte) (offset >>> 8);
        }
        return op;
    }

    /** The table entry for 4 bytes: snappy's portable hash ({@code 0x1e35a7bd}), masked to the table. */
    private static int hash(int bytes, int mask) {
        return ((bytes * 0x1e35a7bd) >>> (31 - MAX_HASH_TABLE_BITS + 1)) & mask;
    }

    private static int read32(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
    }

    /** Decompresses one Snappy block, which must produce exactly {@code outLength} bytes. */
    static void decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int outLength) {
        int ip = srcOff;
        int ipEnd = srcOff + srcLen;

        long declared = 0;
        int shift = 0;
        while (true) {
            if (ip >= ipEnd) {
                throw new CompressionFormatException("snappy block is truncated (length varint)");
            }
            int b = src[ip++] & 0xff;
            declared |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 35) {
                throw new CompressionFormatException("snappy length varint is too long");
            }
        }
        if (declared != outLength) {
            throw new CompressionFormatException(
                    "snappy declares " + declared + " bytes, expected " + outLength);
        }

        int op = dstOff;
        int opEnd = dstOff + outLength;
        while (ip < ipEnd) {
            int tag = src[ip++] & 0xff;
            int type = tag & 3;
            if (type == 0) { // literal
                int header = tag >>> 2;
                int length;
                if (header < 60) {
                    length = header + 1;
                } else {
                    int extra = header - 59; // 1..4 trailing little-endian bytes hold (length - 1)
                    if (ip + extra > ipEnd) {
                        throw new CompressionFormatException("snappy literal length is truncated");
                    }
                    int value = 0;
                    for (int i = 0; i < extra; i++) {
                        value |= (src[ip++] & 0xff) << (8 * i);
                    }
                    length = value + 1;
                }
                if (length < 0 || length > ipEnd - ip || length > opEnd - op) {
                    throw new CompressionFormatException("snappy literal run overruns the block");
                }
                System.arraycopy(src, ip, dst, op, length);
                ip += length;
                op += length;
            } else { // copy
                int length;
                int offset;
                if (type == 1) {
                    if (ip >= ipEnd) {
                        throw new CompressionFormatException("snappy copy is truncated");
                    }
                    length = ((tag >>> 2) & 0x7) + 4;
                    offset = ((tag >>> 5) << 8) | (src[ip++] & 0xff);
                } else if (type == 2) {
                    if (ip + 2 > ipEnd) {
                        throw new CompressionFormatException("snappy copy is truncated");
                    }
                    length = (tag >>> 2) + 1;
                    offset = (src[ip] & 0xff) | ((src[ip + 1] & 0xff) << 8);
                    ip += 2;
                } else {
                    if (ip + 4 > ipEnd) {
                        throw new CompressionFormatException("snappy copy is truncated");
                    }
                    length = (tag >>> 2) + 1;
                    offset = (src[ip] & 0xff) | ((src[ip + 1] & 0xff) << 8)
                            | ((src[ip + 2] & 0xff) << 16) | ((src[ip + 3] & 0xff) << 24);
                    ip += 4;
                }
                int from = op - offset;
                if (offset <= 0 || from < dstOff || length > opEnd - op) {
                    throw new CompressionFormatException("snappy copy is out of range");
                }
                if (offset >= length) {
                    System.arraycopy(dst, from, dst, op, length);
                } else {
                    // Overlapping copies are legal and must be applied byte by byte.
                    for (int i = 0; i < length; i++) {
                        dst[op + i] = dst[from + i];
                    }
                }
                op += length;
            }
        }
        if (op != opEnd) {
            throw new CompressionFormatException(
                    "snappy produced " + (op - dstOff) + " bytes, expected " + outLength);
        }
    }
}
