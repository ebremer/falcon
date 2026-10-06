package com.ebremer.falcon.hdf5.write;

import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.core.checksum.Lookup3;
import java.util.Arrays;

/**
 * A growable little-endian byte buffer for serializing HDF5 structures. Values are appended at the
 * write position (the buffer grows as needed); already-written positions can be patched in place to
 * fill forward references (e.g. a fractal heap's header address, resolved after its block is laid out).
 * All multi-byte integers are little-endian, matching HDF5 metadata.
 *
 * <p>A buffer may start at a file offset, its <i>base</i>: positions are then the file offsets the
 * bytes will have, so the addresses a structure records are its own positions, wherever in a file
 * larger than 2&nbsp;GB the buffer is written. The buffer itself is bounded by the largest Java array;
 * growing past that fails with {@link HdfUnsupportedException} rather than overflowing.
 */
public final class GrowBuffer {

    private final long base;
    private byte[] data = new byte[256];
    private int length;

    /** A buffer whose first byte is at position 0. */
    public GrowBuffer() {
        this(0);
    }

    /** A buffer whose first byte will be written at file offset {@code base}: its positions are file offsets. */
    public GrowBuffer(long base) {
        this.base = base;
    }

    /** The current write position: the base plus the number of bytes written so far. */
    public long position() {
        return base + length;
    }

    /** The number of bytes written so far. */
    public int size() {
        return length;
    }

    public void u8(int value) {
        ensure((long) length + 1);
        data[length++] = (byte) value;
    }

    public void u16(int value) {
        ensure((long) length + 2);
        data[length++] = (byte) value;
        data[length++] = (byte) (value >>> 8);
    }

    public void u32(long value) {
        ensure((long) length + 4);
        for (int i = 0; i < 4; i++) {
            data[length++] = (byte) (value >>> (8 * i));
        }
    }

    public void u64(long value) {
        ensure((long) length + 8);
        for (int i = 0; i < 8; i++) {
            data[length++] = (byte) (value >>> (8 * i));
        }
    }

    /** Appends {@code count} bytes of an unsigned little-endian value. */
    public void uvar(long value, int count) {
        ensure((long) length + count);
        for (int i = 0; i < count; i++) {
            data[length++] = (byte) (value >>> (8 * i));
        }
    }

    public void bytes(byte[] value) {
        ensure((long) length + value.length);
        System.arraycopy(value, 0, data, length, value.length);
        length += value.length;
    }

    /** Appends {@code count} zero bytes and returns the position they start at (for later patching). */
    public long reserve(int count) {
        long at = position();
        ensure((long) length + count);
        length += count;
        return at;
    }

    /** Pads with zero bytes up to the next position that is a multiple of {@code alignment}. */
    public void align(int alignment) {
        while (position() % alignment != 0) {
            u8(0);
        }
    }

    public void patchU32(long at, long value) {
        int i0 = index(at, 4);
        for (int i = 0; i < 4; i++) {
            data[i0 + i] = (byte) (value >>> (8 * i));
        }
    }

    public void patchU64(long at, long value) {
        int i0 = index(at, 8);
        for (int i = 0; i < 8; i++) {
            data[i0 + i] = (byte) (value >>> (8 * i));
        }
    }

    public void patchBytes(long at, byte[] value) {
        System.arraycopy(value, 0, data, index(at, value.length), value.length);
    }

    /** The Jenkins lookup3 checksum HDF5 uses, over the written bytes {@code [from, to)}. */
    public int checksum(long from, long to) {
        int start = index(from, 0);
        return Lookup3.hashLittle(data, start, index(to, 0) - start, 0);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, length);
    }

    /** The index in {@link #data} of position {@code at}, whose {@code count} bytes must be written already. */
    private int index(long at, int count) {
        long i = at - base;
        if (i < 0 || i + count > length) {
            throw new IndexOutOfBoundsException("position " + at + " (+" + count + ") is outside the buffer ["
                    + base + ", " + position() + ")");
        }
        return (int) i;
    }

    private static final int MAX_SIZE = Integer.MAX_VALUE - 8; // the largest array the JVM reliably allocates

    private void ensure(long capacity) {
        if (capacity > data.length) {
            if (capacity > MAX_SIZE) {
                throw new HdfUnsupportedException("a structure being written exceeds " + MAX_SIZE
                        + " bytes, the largest Hdf5Writer builds in memory");
            }
            long grown = Math.max(capacity, Math.min(MAX_SIZE, (long) data.length * 2));
            data = Arrays.copyOf(data, (int) grown);
        }
    }
}
