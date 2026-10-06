package com.ebremer.falcon.core.compress.zfp;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * zfp's bit stream, read: bits are taken least significant first from each byte in turn
 * ({@code bitstream.inl}). Little-endian streams of zfp's wider words hold their bits in the same order, so
 * this reads a stream written with any word size; H5Z-ZFP requires 8-bit words. Unlike libzfp, it never
 * reads past the end: a stream that ends early is reported as truncated.
 */
final class ZfpBitReader {

    private final byte[] data;
    private final int start;
    private final long limit;
    private long position;

    ZfpBitReader(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.limit = 8L * length;
    }

    /** The number of bits read (or skipped) so far ({@code stream_rtell}). */
    long position() {
        return position;
    }

    /** One bit ({@code stream_read_bit}). */
    int readBit() {
        if (position >= limit) {
            throw truncated();
        }
        long p = position++;
        return (data[start + (int) (p >>> 3)] >>> (int) (p & 7)) & 1;
    }

    /** The next {@code n} bits, 0 to 64, the first read the least significant ({@code stream_read_bits}). */
    long readBits(int n) {
        if (n == 0) {
            return 0;
        }
        if (limit - position < n) {
            throw truncated();
        }
        long value = 0;
        int got = 0;
        while (got < n) {
            int at = start + (int) (position >>> 3);
            int shift = (int) (position & 7);
            int take = Math.min(8 - shift, n - got);
            value |= (long) (((data[at] & 0xff) >>> shift) & ((1 << take) - 1)) << got;
            got += take;
            position += take;
        }
        return value;
    }

    /** Skips {@code n} bits ({@code stream_skip}): the padding a block of fewer than its minimum bits carries. */
    void skip(long n) {
        if (limit - position < n) {
            throw truncated();
        }
        position += n;
    }

    private static CompressionFormatException truncated() {
        return new CompressionFormatException("zfp stream is truncated");
    }
}
