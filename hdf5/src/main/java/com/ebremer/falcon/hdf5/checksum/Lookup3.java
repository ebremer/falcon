package com.ebremer.falcon.hdf5.checksum;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Bob Jenkins' {@code lookup3} hash ({@code hashlittle}), the checksum HDF5 uses throughout the file
 * format (version-2+ superblocks, version-2 B-tree nodes, fractal-heap blocks, checksummed chunks).
 *
 * <p>This is a faithful, endian-independent transcription of the public-domain {@code lookup3.c}
 * reference. HDF5 always calls it with an initial value of {@code 0}; use
 * {@link #hashLittle(byte[])} or pass {@code initval = 0}.
 *
 * <p>Correctness is pinned by unit tests against the canonical Jenkins self-test vectors and against a
 * real HDF5 version-3 superblock checksum written by the reference library (h5py / HDF5&nbsp;2.0).
 */
public final class Lookup3 {

    private Lookup3() {
        // No instances.
    }

    /** Hashes the whole array with initial value {@code 0} (the HDF5 metadata checksum). */
    public static int hashLittle(byte[] key) {
        return hashLittle(MemorySegment.ofArray(key), 0, key.length, 0);
    }

    /** Hashes the whole array with the given initial value. */
    public static int hashLittle(byte[] key, int initval) {
        return hashLittle(MemorySegment.ofArray(key), 0, key.length, initval);
    }

    /** Hashes {@code length} bytes of {@code key} starting at {@code offset}. */
    public static int hashLittle(byte[] key, int offset, int length, int initval) {
        return hashLittle(MemorySegment.ofArray(key), offset, length, initval);
    }

    /**
     * Hashes {@code length} bytes of a memory segment starting at {@code offset} &mdash; the zero-copy
     * path for checksumming metadata already mapped from a file.
     *
     * @return the 32-bit hash (as an {@code int}; compare bitwise against the stored little-endian value)
     */
    public static int hashLittle(MemorySegment key, long offset, long length, int initval) {
        int a, b, c;
        a = b = c = 0xdeadbeef + (int) length + initval;

        long i = offset;
        long len = length;
        while (len > 12) {
            a += u8(key, i)     + (u8(key, i + 1) << 8)  + (u8(key, i + 2) << 16)  + (u8(key, i + 3) << 24);
            b += u8(key, i + 4) + (u8(key, i + 5) << 8)  + (u8(key, i + 6) << 16)  + (u8(key, i + 7) << 24);
            c += u8(key, i + 8) + (u8(key, i + 9) << 8)  + (u8(key, i + 10) << 16) + (u8(key, i + 11) << 24);

            a -= c; a ^= rot(c, 4);  c += b;
            b -= a; b ^= rot(a, 6);  a += c;
            c -= b; c ^= rot(b, 8);  b += a;
            a -= c; a ^= rot(c, 16); c += b;
            b -= a; b ^= rot(a, 19); a += c;
            c -= b; c ^= rot(b, 4);  b += a;

            i += 12;
            len -= 12;
        }

        // Handle the last (up to) 12 bytes. Intentional fall-through mirrors the reference switch.
        switch ((int) len) {
            case 12: c += u8(key, i + 11) << 24;
            case 11: c += u8(key, i + 10) << 16;
            case 10: c += u8(key, i + 9) << 8;
            case 9:  c += u8(key, i + 8);
            case 8:  b += u8(key, i + 7) << 24;
            case 7:  b += u8(key, i + 6) << 16;
            case 6:  b += u8(key, i + 5) << 8;
            case 5:  b += u8(key, i + 4);
            case 4:  a += u8(key, i + 3) << 24;
            case 3:  a += u8(key, i + 2) << 16;
            case 2:  a += u8(key, i + 1) << 8;
            case 1:  a += u8(key, i);
                     break;
            case 0:  return c; // nothing added after the loop; final mixing is skipped
        }

        c ^= b; c -= rot(b, 14);
        a ^= c; a -= rot(c, 11);
        b ^= a; b -= rot(a, 25);
        c ^= b; c -= rot(b, 16);
        a ^= c; a -= rot(c, 4);
        b ^= a; b -= rot(a, 14);
        c ^= b; c -= rot(b, 24);
        return c;
    }

    private static int u8(MemorySegment s, long index) {
        return s.get(ValueLayout.JAVA_BYTE, index) & 0xff;
    }

    private static int rot(int x, int k) {
        return (x << k) | (x >>> (32 - k));
    }
}
