package com.ebremer.falcon.cli;

/**
 * Chunk shapes for arrays that have none to keep: the heuristic h5py and zarr-python share
 * ({@code guess_chunk} / {@code guess_chunks}), each with its own sizes. The chunk aims at a size that grows
 * with the array (from a base, doubling per factor of ten above 1&nbsp;MiB of data, within a minimum and a
 * maximum), and is found by halving the dimensions in turn, first to last, from the array's own shape.
 */
final class Chunking {

    /** h5py's sizes: chunks of 8&nbsp;KiB to 1&nbsp;MiB, which libhdf5's 1&nbsp;MiB chunk cache holds. */
    static final Chunking HDF5 = new Chunking(16 * 1024, 8 * 1024, 1024 * 1024);
    /** zarr-python's sizes: chunks of 128&nbsp;KiB to 64&nbsp;MiB, each one request to a store. */
    static final Chunking ZARR = new Chunking(256 * 1024, 128 * 1024, 64 * 1024 * 1024);

    private final double base;
    private final double min;
    private final double max;

    private Chunking(double base, double min, double max) {
        this.base = base;
        this.min = min;
        this.max = max;
    }

    /**
     * {@return a chunk shape for an array}
     *
     * @param shape       the array's shape (a dimension of 0 is taken as 1)
     * @param elementSize the bytes of one element (16 for variable-length ones, as h5py takes them)
     */
    long[] guess(long[] shape, int elementSize) {
        int rank = shape.length;
        double[] chunks = new double[rank];
        double elements = 1;
        for (int d = 0; d < rank; d++) {
            chunks[d] = Math.max(1, shape[d]);
            elements *= chunks[d];
        }
        double size = elements * elementSize;
        double target = base * Math.pow(2, Math.log10(size / (1024.0 * 1024)));
        target = Math.max(min, Math.min(max, target));
        for (int i = 0; rank > 0; i++) {
            double bytes = product(chunks) * elementSize;
            if ((bytes < target || Math.abs(bytes - target) / target < 0.5) && bytes < max) {
                break;
            }
            if (product(chunks) == 1) {
                break; // an element larger than the maximum
            }
            int d = i % rank;
            chunks[d] = Math.ceil(chunks[d] / 2);
        }
        long[] out = new long[rank];
        for (int d = 0; d < rank; d++) {
            out[d] = (long) chunks[d];
        }
        return out;
    }

    private static double product(double[] values) {
        double p = 1;
        for (double v : values) {
            p *= v;
        }
        return p;
    }

    /**
     * {@return a chunk shape fitted to an array: each dimension at least 1 and at most the array's extent}
     *
     * @param chunk the chunk shape
     * @param shape the array's shape
     */
    static long[] clamp(long[] chunk, long[] shape) {
        long[] out = new long[chunk.length];
        for (int d = 0; d < chunk.length; d++) {
            out[d] = Math.max(1, Math.min(chunk[d], Math.max(1, shape[d])));
        }
        return out;
    }
}
