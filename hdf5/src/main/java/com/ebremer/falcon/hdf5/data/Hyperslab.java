package com.ebremer.falcon.hdf5.data;

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
        int rank = datasetDims.length;
        long total = 1;
        for (long c : count) {
            total *= c;
        }
        byte[] out = new byte[Math.toIntExact(total * elementSize)];
        if (rank == 0) {
            MemorySegment.copy(source, ValueLayout.JAVA_BYTE, 0, out, 0, elementSize);
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
            MemorySegment.copy(source, ValueLayout.JAVA_BYTE, sourceFlat * elementSize,
                    out, (int) (outFlat * elementSize), run * elementSize);
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
