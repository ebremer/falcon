package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;

/** Small shared helpers for chunk shapes within the codec package. */
final class Pipelines {

    private Pipelines() {
    }

    /** Narrows a chunk shape to {@code int} dimensions, rejecting a chunk too large for a Java array. */
    static int[] toIntShape(long[] shape) {
        int[] out = new int[shape.length];
        long count = 1;
        for (int i = 0; i < shape.length; i++) {
            if (shape[i] < 0 || shape[i] > Integer.MAX_VALUE) {
                throw new ZarrFormatException("chunk dimension " + i + " = " + shape[i] + " is too large");
            }
            out[i] = (int) shape[i];
            count *= out[i];
            if (count > Integer.MAX_VALUE) {
                throw new ZarrFormatException("chunk has too many elements for a single buffer");
            }
        }
        return out;
    }

    /** The number of elements in a chunk of {@code shape} (1 for a scalar). */
    static int elementCount(int[] shape) {
        int count = 1;
        for (int d : shape) {
            count *= d;
        }
        return count;
    }

    /** Row-major (C-order) strides, in elements. */
    static int[] strides(int[] shape) {
        int[] stride = new int[shape.length];
        int acc = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            stride[i] = acc;
            acc *= shape[i];
        }
        return stride;
    }

    /** Fills {@code buffer} with copies of one element. */
    static void tile(byte[] buffer, byte[] element) {
        boolean allZero = true;
        for (byte b : element) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero || element.length == 0) {
            return; // a fresh byte[] is already zero
        }
        for (int off = 0; off < buffer.length; off += element.length) {
            System.arraycopy(element, 0, buffer, off, element.length);
        }
    }

    /**
     * Copies the box of {@code box} elements at {@code srcOrigin} in {@code src} to {@code dstOrigin} in
     * {@code dst}. Both buffers hold {@code elementSize}-byte elements in C order for their own shape, so
     * the last axis is copied in runs.
     */
    static void copyBox(byte[] src, int[] srcShape, int[] srcOrigin, byte[] dst, int[] dstShape, int[] dstOrigin,
                        int[] box, int elementSize) {
        copy(src, srcShape, srcOrigin, dst, dstShape, dstOrigin, box, elementSize);
    }

    /** {@link #copyBox(byte[], int[], int[], byte[], int[], int[], int[], int)} for object elements. */
    static void copyBox(Object[] src, int[] srcShape, int[] srcOrigin, Object[] dst, int[] dstShape, int[] dstOrigin,
                        int[] box) {
        copy(src, srcShape, srcOrigin, dst, dstShape, dstOrigin, box, 1);
    }

    /** Both copies: {@code array} is a byte[] (scaled by elementSize) or an Object[] (elementSize 1). */
    private static void copy(Object src, int[] srcShape, int[] srcOrigin, Object dst, int[] dstShape,
                             int[] dstOrigin, int[] box, int elementSize) {
        int rank = box.length;
        if (rank == 0) {
            System.arraycopy(src, 0, dst, 0, elementSize);
            return;
        }
        for (int extent : box) {
            if (extent <= 0) {
                return;
            }
        }
        int[] srcStride = strides(srcShape);
        int[] dstStride = strides(dstShape);
        int run = box[rank - 1] * elementSize;
        int[] index = new int[rank]; // position within the box; the last axis stays at 0
        while (true) {
            int srcOffset = 0;
            int dstOffset = 0;
            for (int i = 0; i < rank; i++) {
                srcOffset += (srcOrigin[i] + index[i]) * srcStride[i];
                dstOffset += (dstOrigin[i] + index[i]) * dstStride[i];
            }
            System.arraycopy(src, srcOffset * elementSize, dst, dstOffset * elementSize, run);
            int d = rank - 2;
            for (; d >= 0; d--) {
                if (++index[d] < box[d]) {
                    break;
                }
                index[d] = 0;
            }
            if (d < 0) {
                return;
            }
        }
    }

    /**
     * The overlap of the box {@code [aOrigin, aOrigin + aShape)} with {@code [bOrigin, bOrigin + bShape)}:
     * {@code {origin, shape}}, or {@code null} if they do not overlap.
     */
    static int[][] intersect(int[] aOrigin, int[] aShape, int[] bOrigin, int[] bShape) {
        int rank = aOrigin.length;
        int[] origin = new int[rank];
        int[] shape = new int[rank];
        for (int i = 0; i < rank; i++) {
            int lo = Math.max(aOrigin[i], bOrigin[i]);
            int hi = Math.min(aOrigin[i] + aShape[i], bOrigin[i] + bShape[i]);
            if (hi <= lo) {
                return null;
            }
            origin[i] = lo;
            shape[i] = hi - lo;
        }
        return new int[][] {origin, shape};
    }

    /** {@code a - b}, element by element. */
    static int[] minus(int[] a, int[] b) {
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] - b[i];
        }
        return out;
    }
}
