package com.ebremer.falcon.core.compress.shuffle;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * The byte shuffle: the same byte position of every element grouped together (so the high bytes of similar
 * numbers become runs of equal bytes), as HDF5's {@code shuffle} filter ({@code H5Z__filter_shuffle}),
 * Blosc's per-block shuffle, and numcodecs' {@code shuffle} codec apply it. Bytes past the last whole
 * element are copied through unshuffled, as libhdf5 and c-blosc leave them.
 *
 * <p>Elements of 2, 4, and 8 bytes, which is what numbers are, move an element at a time where that is
 * faster: one read or write of the whole element, its bytes taken from or put to their streams. Other sizes
 * move a byte at a time, one byte position after another, so that position's stream is read or written
 * from end to end.
 */
public final class ByteShuffle {

    // An element as one value, its first byte the lowest: so byte b of an element is bits 8b to 8b + 7.
    private static final VarHandle SHORTS =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INTS =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private ByteShuffle() {
    }

    /**
     * The byte shuffle of {@code data}, in a new array.
     *
     * @param data     the bytes
     * @param typeSize the element size
     * @return the shuffled bytes
     */
    public static byte[] shuffle(byte[] data, int typeSize) {
        byte[] out = new byte[data.length];
        shuffle(data, 0, out, 0, data.length, typeSize);
        return out;
    }

    /**
     * Undoes the byte shuffle of {@code data}, in a new array.
     *
     * @param data     the shuffled bytes
     * @param typeSize the element size they were shuffled with
     * @return the bytes as they were
     */
    public static byte[] unshuffle(byte[] data, int typeSize) {
        byte[] out = new byte[data.length];
        unshuffle(data, 0, out, 0, data.length, typeSize);
        return out;
    }

    /**
     * Applies the byte shuffle: groups the first byte of every element, then the second, and so on. The
     * inverse of {@link #unshuffle(byte[], int, byte[], int, int, int)}. Trailing bytes that do not form a
     * whole element are copied through. The two ranges must not overlap.
     *
     * @param src      the bytes
     * @param srcOff   where they start
     * @param dst      where the shuffled bytes go
     * @param dstOff   where they start there
     * @param length   how many bytes
     * @param typeSize the element size
     */
    public static void shuffle(byte[] src, int srcOff, byte[] dst, int dstOff, int length, int typeSize) {
        if (typeSize <= 1) {
            System.arraycopy(src, srcOff, dst, dstOff, length);
            return;
        }
        int elements = length / typeSize;
        int remainder = length % typeSize;
        switch (typeSize) { // two-byte elements are as fast a byte position at a time
            case 4 -> {
                int d1 = dstOff + elements;
                int d2 = d1 + elements;
                int d3 = d2 + elements;
                for (int e = 0, s = srcOff; e < elements; e++, s += 4) {
                    int v = (int) INTS.get(src, s);
                    dst[dstOff + e] = (byte) v;
                    dst[d1 + e] = (byte) (v >>> 8);
                    dst[d2 + e] = (byte) (v >>> 16);
                    dst[d3 + e] = (byte) (v >>> 24);
                }
            }
            case 8 -> {
                int d1 = dstOff + elements;
                int d2 = d1 + elements;
                int d3 = d2 + elements;
                int d4 = d3 + elements;
                int d5 = d4 + elements;
                int d6 = d5 + elements;
                int d7 = d6 + elements;
                for (int e = 0, s = srcOff; e < elements; e++, s += 8) {
                    long v = (long) LONGS.get(src, s);
                    dst[dstOff + e] = (byte) v;
                    dst[d1 + e] = (byte) (v >>> 8);
                    dst[d2 + e] = (byte) (v >>> 16);
                    dst[d3 + e] = (byte) (v >>> 24);
                    dst[d4 + e] = (byte) (v >>> 32);
                    dst[d5 + e] = (byte) (v >>> 40);
                    dst[d6 + e] = (byte) (v >>> 48);
                    dst[d7 + e] = (byte) (v >>> 56);
                }
            }
            default -> {
                // A byte position at a time: its stream is written from end to end.
                for (int b = 0; b < typeSize; b++) {
                    int stream = dstOff + b * elements;
                    for (int e = 0, s = srcOff + b; e < elements; e++, s += typeSize) {
                        dst[stream + e] = src[s];
                    }
                }
            }
        }
        if (remainder > 0) {
            System.arraycopy(src, srcOff + length - remainder, dst, dstOff + length - remainder, remainder);
        }
    }

    /**
     * Undoes the byte shuffle: {@code src} holds all first bytes, then all second bytes, and so on. The two
     * ranges must not overlap.
     *
     * @param src      the shuffled bytes
     * @param srcOff   where they start
     * @param dst      where the bytes go
     * @param dstOff   where they start there
     * @param length   how many bytes
     * @param typeSize the element size they were shuffled with
     */
    public static void unshuffle(byte[] src, int srcOff, byte[] dst, int dstOff, int length, int typeSize) {
        if (typeSize <= 1) {
            System.arraycopy(src, srcOff, dst, dstOff, length);
            return;
        }
        int elements = length / typeSize;
        int remainder = length % typeSize;
        switch (typeSize) {
            case 2 -> {
                int s1 = srcOff + elements;
                for (int e = 0, d = dstOff; e < elements; e++, d += 2) {
                    SHORTS.set(dst, d, (short) ((src[srcOff + e] & 0xFF) | (src[s1 + e] & 0xFF) << 8));
                }
            }
            case 4 -> {
                int s1 = srcOff + elements;
                int s2 = s1 + elements;
                int s3 = s2 + elements;
                for (int e = 0, d = dstOff; e < elements; e++, d += 4) {
                    INTS.set(dst, d, (src[srcOff + e] & 0xFF) | (src[s1 + e] & 0xFF) << 8
                            | (src[s2 + e] & 0xFF) << 16 | (src[s3 + e] & 0xFF) << 24);
                }
            }
            case 8 -> {
                int s1 = srcOff + elements;
                int s2 = s1 + elements;
                int s3 = s2 + elements;
                int s4 = s3 + elements;
                int s5 = s4 + elements;
                int s6 = s5 + elements;
                int s7 = s6 + elements;
                for (int e = 0, d = dstOff; e < elements; e++, d += 8) {
                    LONGS.set(dst, d, (src[srcOff + e] & 0xFFL) | (src[s1 + e] & 0xFFL) << 8
                            | (src[s2 + e] & 0xFFL) << 16 | (src[s3 + e] & 0xFFL) << 24
                            | (src[s4 + e] & 0xFFL) << 32 | (src[s5 + e] & 0xFFL) << 40
                            | (src[s6 + e] & 0xFFL) << 48 | (src[s7 + e] & 0xFFL) << 56);
                }
            }
            default -> {
                // A byte position at a time: its stream is read from end to end.
                for (int b = 0; b < typeSize; b++) {
                    int stream = srcOff + b * elements;
                    for (int e = 0, d = dstOff + b; e < elements; e++, d += typeSize) {
                        dst[d] = src[stream + e];
                    }
                }
            }
        }
        // Trailing bytes that did not form a whole element were never shuffled.
        if (remainder > 0) {
            System.arraycopy(src, srcOff + length - remainder, dst, dstOff + length - remainder, remainder);
        }
    }
}
