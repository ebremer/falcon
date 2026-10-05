package com.ebremer.falcon.hdf5.write;

import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.checksum.Lookup3;
import java.util.Arrays;

/**
 * A growable little-endian byte buffer for serializing HDF5 structures. Values are appended at the
 * write position (the buffer grows as needed); already-written positions can be patched in place to
 * fill forward references (e.g. the superblock's root-group address, resolved after the root header is
 * laid out). All multi-byte integers are little-endian, matching HDF5 metadata.
 *
 * <p>The whole file is assembled in memory, so it is bounded by the largest Java array; growing past
 * that fails with {@link HdfUnsupportedException} rather than overflowing.
 */
public final class GrowBuffer {

    private byte[] data = new byte[256];
    private int length;

    /** The current write position, i.e. the number of bytes written so far. */
    public int position() {
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
    public int reserve(int count) {
        int at = length;
        ensure((long) length + count);
        length += count;
        return at;
    }

    /** Pads with zero bytes up to the next multiple of {@code alignment}. */
    public void align(int alignment) {
        while (length % alignment != 0) {
            u8(0);
        }
    }

    public void patchU32(int at, long value) {
        for (int i = 0; i < 4; i++) {
            data[at + i] = (byte) (value >>> (8 * i));
        }
    }

    public void patchU64(int at, long value) {
        for (int i = 0; i < 8; i++) {
            data[at + i] = (byte) (value >>> (8 * i));
        }
    }

    public void patchBytes(int at, byte[] value) {
        System.arraycopy(value, 0, data, at, value.length);
    }

    /** The Jenkins lookup3 checksum HDF5 uses, over the written bytes {@code [from, to)}. */
    public int checksum(int from, int to) {
        return Lookup3.hashLittle(data, from, to - from, 0);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, length);
    }

    private static final int MAX_SIZE = Integer.MAX_VALUE - 8; // the largest array the JVM reliably allocates

    private void ensure(long capacity) {
        if (capacity > data.length) {
            if (capacity > MAX_SIZE) {
                throw new HdfUnsupportedException("the file being written exceeds " + MAX_SIZE
                        + " bytes; Hdf5Writer assembles files in memory and cannot write one this large");
            }
            long grown = Math.max(capacity, Math.min(MAX_SIZE, (long) data.length * 2));
            data = Arrays.copyOf(data, (int) grown);
        }
    }
}
