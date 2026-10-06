package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * A bounds-checked view of an SZ stream, with the byte conversions of SZ's {@code ByteToolkit.c}: its
 * header fields are big-endian, while what it copies straight from memory (Huffman tables, the regression
 * format's unpredictable values) is the writer's byte order, little-endian.
 */
final class SzBuffer {

    private final byte[] bytes;
    private final int start;
    private final int length;

    SzBuffer(byte[] bytes, int start, int length) {
        this.bytes = bytes;
        this.start = start;
        this.length = length;
    }

    int length() {
        return length;
    }

    /** Fails unless {@code count} bytes from {@code at} are in the stream. */
    void require(long at, long count) {
        if (at < 0 || count < 0 || at + count > length) {
            throw new CompressionFormatException("SZ stream of " + length + " bytes is truncated (needs " + count
                    + " bytes at " + at + ")");
        }
    }

    int u8(long at) {
        require(at, 1);
        return bytes[start + (int) at] & 0xFF;
    }

    int be16(long at) {
        require(at, 2);
        int p = start + (int) at;
        return (bytes[p] & 0xFF) << 8 | bytes[p + 1] & 0xFF;
    }

    int le16(long at) {
        require(at, 2);
        int p = start + (int) at;
        return (bytes[p + 1] & 0xFF) << 8 | bytes[p] & 0xFF;
    }

    int be32(long at) {
        require(at, 4);
        int p = start + (int) at;
        return (bytes[p] & 0xFF) << 24 | (bytes[p + 1] & 0xFF) << 16 | (bytes[p + 2] & 0xFF) << 8 | bytes[p + 3] & 0xFF;
    }

    int le32(long at) {
        require(at, 4);
        int p = start + (int) at;
        return (bytes[p + 3] & 0xFF) << 24 | (bytes[p + 2] & 0xFF) << 16 | (bytes[p + 1] & 0xFF) << 8 | bytes[p] & 0xFF;
    }

    long be64(long at) {
        return (long) be32(at) << 32 | be32(at + 4) & 0xFFFFFFFFL;
    }

    long le64(long at) {
        return (long) le32(at + 4) << 32 | le32(at) & 0xFFFFFFFFL;
    }

    /** {@code bytesToFloat}: a big-endian float. */
    float beFloat(long at) {
        return Float.intBitsToFloat(be32(at));
    }

    /** {@code bytesToDouble}: a big-endian double. */
    double beDouble(long at) {
        return Double.longBitsToDouble(be64(at));
    }

    /** A float copied from memory ({@code memcpy}), little-endian. */
    float leFloat(long at) {
        return Float.intBitsToFloat(le32(at));
    }

    /** A double copied from memory, little-endian. */
    double leDouble(long at) {
        return Double.longBitsToDouble(le64(at));
    }

    /**
     * {@code bytesToSize}: a big-endian size of {@code sizeType} (4 or 8) bytes, refused past
     * {@link Integer#MAX_VALUE} (no SZ stream Falcon reads is that large).
     */
    long size(long at, int sizeType) {
        long v = sizeType == 4 ? be32(at) : be64(at);
        if (v < 0 || v > Integer.MAX_VALUE) {
            throw new CompressionFormatException("SZ size field " + v + " is out of range");
        }
        return v;
    }

    /**
     * {@code convertByteArray2IntArray_fast_2b}: {@code count} two-bit values, high bits first, from
     * {@code byteLength} bytes at {@code at}.
     */
    byte[] twoBit(long at, long byteLength, long count) {
        if (count > byteLength * 4) {
            throw new CompressionFormatException("SZ stream holds " + byteLength + " bytes for " + count + " two-bit values");
        }
        require(at, (count + 3) / 4);
        byte[] out = new byte[(int) count];
        for (int n = 0; n < count; n++) {
            out[n] = (byte) ((bytes[start + (int) at + (n >>> 2)] >>> (6 - 2 * (n & 3))) & 3);
        }
        return out;
    }

    /** {@code convertByteArray2IntArray_fast_1b}: {@code count} bits, high bit first, from {@code at}. */
    boolean[] oneBit(long at, long count) {
        require(at, (count + 7) / 8);
        boolean[] out = new boolean[(int) count];
        for (int n = 0; n < count; n++) {
            out[n] = ((bytes[start + (int) at + (n >>> 3)] >>> (7 - (n & 7))) & 1) != 0;
        }
        return out;
    }
}
