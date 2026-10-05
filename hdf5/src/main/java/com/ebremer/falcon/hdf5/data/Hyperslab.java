package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Extracts a rectangular sub-region (hyperslab) from a dataset's full row-major element bytes into a
 * dense row-major buffer shaped like the selection.
 */
public final class Hyperslab {

    private Hyperslab() {
    }

    public static byte[] extract(MemorySegment source, long[] datasetDims, long[] offset, long[] count,
                                 int elementSize) {
        return extract(datasetDims, offset, count, elementSize, (from, out, at, length) ->
                MemorySegment.copy(source, ValueLayout.JAVA_BYTE, from, out, at, length));
    }

    /**
     * Extracts the hyperslab from row-major element bytes stored at {@code address} in {@code file},
     * reading only the selected runs (for a source read on demand, the rest is never fetched).
     */
    public static byte[] extract(HdfBuffer file, long address, long[] datasetDims, long[] offset, long[] count,
                                 int elementSize) {
        return extract(datasetDims, offset, count, elementSize, (from, out, at, length) ->
                file.copyTo(address + from, out, at, length));
    }

    /** Copies {@code length} source bytes from byte offset {@code from} to {@code out[at...]}. */
    private interface Runs {
        void copy(long from, byte[] out, int at, int length);
    }

    private static byte[] extract(long[] datasetDims, long[] offset, long[] count, int elementSize, Runs source) {
        // Fold trailing dimensions selected whole into the one before them: the same bytes, in longer runs.
        int whole = datasetDims.length;
        while (whole > 1 && count[whole - 1] == datasetDims[whole - 1]) {
            whole--;
        }
        if (whole < datasetDims.length) {
            long inner = 1;
            for (int d = whole; d < datasetDims.length; d++) {
                inner *= datasetDims[d];
            }
            datasetDims = java.util.Arrays.copyOf(datasetDims, whole);
            offset = java.util.Arrays.copyOf(offset, whole);
            count = java.util.Arrays.copyOf(count, whole);
            datasetDims[whole - 1] *= inner;
            offset[whole - 1] *= inner;
            count[whole - 1] *= inner;
        }
        int rank = datasetDims.length;
        long total = 1;
        for (long c : count) {
            total *= c;
        }
        byte[] out = new byte[Elements.checkedByteCount(total, elementSize)];
        if (total == 0) {
            return out; // an empty selection in any dimension selects nothing
        }
        if (rank == 0) {
            source.copy(0, out, 0, elementSize);
            return out;
        }

        int last = rank - 1;
        int run = (int) count[last];
        if (run == 0) {
            return out;
        }
        long[] sourceStride = strides(datasetDims);
        long[] outStride = strides(count);

        long[] local = new long[rank]; // coordinates within the selection; the last stays 0
        while (true) {
            long sourceFlat = 0;
            long outFlat = 0;
            for (int d = 0; d < rank; d++) {
                sourceFlat += (offset[d] + local[d]) * sourceStride[d];
                outFlat += local[d] * outStride[d];
            }
            source.copy(sourceFlat * elementSize, out, (int) (outFlat * elementSize), run * elementSize);
            int d = last - 1;
            while (d >= 0) {
                if (++local[d] < count[d]) {
                    break;
                }
                local[d] = 0;
                d--;
            }
            if (d < 0) {
                break;
            }
        }
        return out;
    }

    private static long[] strides(long[] dims) {
        long[] stride = new long[dims.length];
        long acc = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            stride[i] = acc;
            acc *= dims[i];
        }
        return stride;
    }
}
