package com.ebremer.falcon.core.compress.sz;

import java.util.Arrays;

/**
 * A growing byte array for the encoder, as SZ's {@code DynamicByteArray} is used: big-endian fields where SZ
 * writes them with its {@code *ToBytes_bigEndian} helpers, little-endian where it {@code memcpy}s a native
 * (x86-64) value.
 */
final class SzBytes {

    private byte[] buf;
    private int size;

    SzBytes(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    int size() {
        return size;
    }

    byte[] toArray() {
        return Arrays.copyOf(buf, size);
    }

    private void ensure(int more) {
        if (size + more > buf.length) {
            long grown = Math.max((long) buf.length * 2, (long) size + more);
            if (grown > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("SZ stream would exceed " + (Integer.MAX_VALUE - 8) + " bytes");
            }
            buf = Arrays.copyOf(buf, (int) grown);
        }
    }

    void u8(int v) {
        ensure(1);
        buf[size++] = (byte) v;
    }

    void bytes(byte[] b) {
        bytes(b, 0, b.length);
    }

    void bytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, size, len);
        size += len;
    }

    void be16(int v) {
        u8(v >>> 8);
        u8(v);
    }

    void be32(int v) {
        ensure(4);
        buf[size++] = (byte) (v >>> 24);
        buf[size++] = (byte) (v >>> 16);
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) v;
    }

    void be64(long v) {
        be32((int) (v >>> 32));
        be32((int) v);
    }

    void le32(int v) {
        ensure(4);
        buf[size++] = (byte) v;
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) (v >>> 16);
        buf[size++] = (byte) (v >>> 24);
    }

    void le64(long v) {
        le32((int) v);
        le32((int) (v >>> 32));
    }

    /** {@code floatToBytes}: big-endian. */
    void beFloat(float v) {
        be32(Float.floatToRawIntBits(v));
    }

    /** {@code doubleToBytes}: big-endian. */
    void beDouble(double v) {
        be64(Double.doubleToRawLongBits(v));
    }

    /** A native float, as {@code memcpy} copies it on x86-64. */
    void leFloat(float v) {
        le32(Float.floatToRawIntBits(v));
    }

    /** A native double. */
    void leDouble(double v) {
        le64(Double.doubleToRawLongBits(v));
    }

    /** Overwrites four bytes, big-endian, at {@code at}. */
    void setBe32(int at, int v) {
        buf[at] = (byte) (v >>> 24);
        buf[at + 1] = (byte) (v >>> 16);
        buf[at + 2] = (byte) (v >>> 8);
        buf[at + 3] = (byte) v;
    }

    /** Overwrites eight bytes, big-endian, at {@code at}. */
    void setBe64(int at, long v) {
        setBe32(at, (int) (v >>> 32));
        setBe32(at + 4, (int) v);
    }
}
