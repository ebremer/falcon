package com.ebremer.falcon.core.compress.shuffle;

/**
 * The byte shuffle: the same byte position of every element grouped together (so the high bytes of similar
 * numbers become runs of equal bytes), as HDF5's {@code shuffle} filter ({@code H5Z__filter_shuffle}),
 * Blosc's per-block shuffle, and numcodecs' {@code shuffle} codec apply it. Bytes past the last whole
 * element are copied through unshuffled, as libhdf5 and c-blosc leave them.
 */
public final class ByteShuffle {

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
     * whole element are copied through.
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
        for (int element = 0; element < elements; element++) {
            for (int b = 0; b < typeSize; b++) {
                dst[dstOff + b * elements + element] = src[srcOff + element * typeSize + b];
            }
        }
        if (remainder > 0) {
            System.arraycopy(src, srcOff + length - remainder, dst, dstOff + length - remainder, remainder);
        }
    }

    /**
     * Undoes the byte shuffle: {@code src} holds all first bytes, then all second bytes, and so on.
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
        for (int element = 0; element < elements; element++) {
            for (int b = 0; b < typeSize; b++) {
                dst[dstOff + element * typeSize + b] = src[srcOff + b * elements + element];
            }
        }
        // Trailing bytes that did not form a whole element were never shuffled.
        if (remainder > 0) {
            System.arraycopy(src, srcOff + length - remainder, dst, dstOff + length - remainder, remainder);
        }
    }
}
