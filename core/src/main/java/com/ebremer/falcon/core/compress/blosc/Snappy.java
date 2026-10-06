package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * Snappy block-format decompression, which is what Blosc's {@code snappy} internal codec stores (the raw
 * format of {@code snappy_uncompress}, not the framing format). Modern c-blosc dropped snappy, but older
 * blosc buffers may still use it.
 *
 * <p>The stream is a varint uncompressed length followed by elements. Each element's tag byte has the
 * type in its low two bits: a literal run (the length in the upper six bits, extended by up to four
 * trailing bytes for large runs), or a copy of an earlier run selected by a 1-, 2-, or 4-byte
 * back-reference offset. Copies may overlap the output written so far.
 */
final class Snappy {

    private Snappy() {
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
