package com.ebremer.falcon.zarr.data;

/** Copies a rectangular block of fixed-size elements between two C-order buffers. */
final class Blocks {

    private Blocks() {
    }

    /**
     * Copies a block of {@code blockShape} elements from {@code src} at {@code srcOrigin} to {@code dst}
     * at {@code dstOrigin}. Both buffers are laid out in C (row-major) order for their own shape, so the
     * last axis is contiguous and copied in one run.
     */
    static void copy(byte[] src, long[] srcShape, long[] srcOrigin,
                     byte[] dst, long[] dstShape, long[] dstOrigin,
                     long[] blockShape, int elementSize) {
        int rank = blockShape.length;
        if (rank == 0) {
            System.arraycopy(src, 0, dst, 0, elementSize);
            return;
        }
        long run = blockShape[rank - 1];
        if (run <= 0) {
            return;
        }
        for (long extent : blockShape) {
            if (extent <= 0) {
                return;
            }
        }
        long[] srcStride = strides(srcShape);
        long[] dstStride = strides(dstShape);
        long[] index = new long[rank]; // position within the block; the last axis stays at 0
        while (true) {
            long srcOffset = 0;
            long dstOffset = 0;
            for (int i = 0; i < rank; i++) {
                srcOffset += (srcOrigin[i] + index[i]) * srcStride[i];
                dstOffset += (dstOrigin[i] + index[i]) * dstStride[i];
            }
            System.arraycopy(src, (int) (srcOffset * elementSize),
                    dst, (int) (dstOffset * elementSize), (int) (run * elementSize));
            int d = rank - 2;
            for (; d >= 0; d--) {
                if (++index[d] < blockShape[d]) {
                    break;
                }
                index[d] = 0;
            }
            if (d < 0) {
                return;
            }
        }
    }

    /** Row-major (C-order) strides, in elements. */
    static long[] strides(long[] shape) {
        long[] stride = new long[shape.length];
        long acc = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            stride[i] = acc;
            acc *= shape[i];
        }
        return stride;
    }
}
