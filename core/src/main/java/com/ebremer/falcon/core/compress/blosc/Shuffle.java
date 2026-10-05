package com.ebremer.falcon.core.compress.blosc;

/**
 * Blosc's byte-shuffle filter, applied per block before compression to group the same byte position of
 * every element together (so the high bytes of similar numbers become runs of equal bytes).
 *
 * <p>Only the inverse is needed for reading. Bytes past the last whole element are left untouched, as
 * the filter itself leaves them.
 */
final class Shuffle {

    private Shuffle() {
    }

    /**
     * Applies the byte shuffle: groups the first byte of every element, then the second, and so on. The
     * inverse of {@link #unshuffle}. Trailing bytes that do not form a whole element are copied through.
     */
    static void shuffle(byte[] src, int srcOff, byte[] dst, int dstOff, int length, int typeSize) {
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
     * @param typeSize the element size the block was shuffled with
     * @param length   the block's length in bytes
     */
    static void unshuffle(byte[] src, int srcOff, byte[] dst, int dstOff, int length, int typeSize) {
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
