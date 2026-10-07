package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.sz.SzDecoder;
import com.ebremer.falcon.core.compress.sz.SzEncoder;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;

/**
 * The SZ filter (32017, {@code H5Z_SZ.c} of SZ 2.1.12, as hdf5plugin builds it): a chunk is one SZ stream.
 * Its client data, as {@code H5Z_sz_set_local} leaves it, is {@code [dimensions, data type, sizes...]} then
 * the error bound ({@code mode, absolute (2), relative (2), point-wise relative (2), PSNR (2)}), which
 * decoding does not need. The sizes are SZ's own (fastest-varying first), as {@code SZ_cdArrayToMetaData}
 * reads them: one dimension's length in two values (high, low); two to five dimensions' lengths one value
 * each. A chunk of fewer than 20 values is stored as it is, and so is every chunk when there is no client
 * data.
 */
final class SzFilter {

    static final int ID = 32017;
    /** The name H5Z-SZ registers, which libhdf5 stores in the pipeline message. */
    static final String NAME = "SZ compressor/decompressor for floating-point data.";
    /** H5Z-SZ's error bound: nine values, the mode then four doubles as (high, low) words. */
    static final int BOUND_VALUES = 9;
    /** {@code computeDataLength} below which the filter leaves a chunk as it is. */
    private static final int MIN_ELEMENTS = 20;

    private SzFilter() {
    }

    /**
     * The client data H5Z-SZ's {@code set_local} stores for a dataset ({@code SZ_refreshDimForCdArray}): the
     * chunk's dimensions in HDF5's order become SZ's {@code r1} (the first) to {@code r5}, those of length 1
     * are dropped ({@code filterDimension}), and what is left is recorded fastest-varying first, behind its
     * count and the data type; then the nine error-bound values hdf5plugin passes. A chunk left with one
     * dimension records the first dimension as it was, not as {@code filterDimension} left it (a slip of
     * {@code set_local}'s), so a chunk such as {@code (1, 1, n)} records one value, and is stored as it is.
     *
     * @param dataType   SZ's data type ({@link SzDecoder#FLOAT} to {@link SzDecoder#INT64})
     * @param chunkShape the chunk's shape, at most 5 dimensions
     * @param bound      the nine error-bound values
     * @return the client data
     * @throws IllegalArgumentException if the chunk has more than 5 dimensions
     */
    static int[] clientData(int dataType, long[] chunkShape, int[] bound) {
        if (chunkShape.length > 5) {
            throw new IllegalArgumentException("H5Z-SZ takes chunks of at most 5 dimensions, not " + chunkShape.length);
        }
        long[] used = new long[5];
        System.arraycopy(chunkShape, 0, used, 0, chunkShape.length);
        long r1 = used[0];
        long[] c = SzEncoder.filterDimensions(used[4], used[3], used[2], used[1], r1);
        int dim = c[0] == 0 ? 0 : c[1] == 0 ? 1 : c[2] == 0 ? 2 : c[3] == 0 ? 3 : c[4] == 0 ? 4 : 5;
        int[] sizes = switch (dim) {
            case 1 -> new int[] {(int) (r1 >>> 32), (int) r1};
            case 2 -> new int[] {(int) c[1], (int) c[0]};
            case 3 -> new int[] {(int) c[2], (int) c[1], (int) c[0]};
            case 4 -> new int[] {(int) c[3], (int) c[2], (int) c[1], (int) c[0]};
            default -> new int[] {(int) c[4], (int) c[3], (int) c[2], (int) c[1], (int) c[0]};
        };
        int[] cd = new int[2 + sizes.length + BOUND_VALUES];
        cd[0] = dim;
        cd[1] = dataType;
        System.arraycopy(sizes, 0, cd, 2, sizes.length);
        System.arraycopy(bound, 0, cd, 2 + sizes.length, BOUND_VALUES);
        return cd;
    }

    /** {@code checkCDValuesWithErrors}: whether the client data carry an error bound after the sizes. */
    static boolean hasBound(int[] cd) {
        return cd.length > switch (cd[0]) {
            case 1, 2 -> 4;
            case 3 -> 5;
            case 4 -> 6;
            case 5 -> 7;
            default -> Integer.MAX_VALUE - 1;
        };
    }

    /**
     * Encodes one chunk as H5Z-SZ does: {@code SZ_compress_args} with the data type, dimensions, and error
     * bound of its client data; a chunk of fewer than 20 values, and every chunk of a dataset without client
     * data, is left as it is.
     *
     * @throws HdfUnsupportedException if the client data carry no error bound (H5Z-SZ then reads one from a
     *                                 {@code sz.config} file), or one Falcon does not write
     */
    static byte[] encode(int[] cd, byte[] data) {
        if (cd.length == 0) {
            return data;
        }
        long[] r = dimensions(cd);
        if (elements(r[4], r[3], r[2], r[1], r[0]) < MIN_ELEMENTS) {
            return data;
        }
        if (!hasBound(cd)) {
            throw new HdfUnsupportedException("sz filter: the client data carry no error bound, which H5Z-SZ "
                    + "then reads from its configuration file");
        }
        int k = cd[0] == 1 ? 4 : cd[0] + 2;
        if (cd.length < k + BOUND_VALUES) {
            throw new HdfFormatException("sz filter: " + cd.length + " client-data values, fewer than its bound needs");
        }
        int mode = cd[k];
        double abs = real(cd, k + 1);
        double rel = real(cd, k + 3);
        double pwr = real(cd, k + 5);
        try {
            return SzEncoder.compress(cd[1], data, r[4], r[3], r[2], r[1], r[0], mode, abs, rel, pwr);
        } catch (IllegalArgumentException e) {
            throw new HdfUnsupportedException("sz filter: " + e.getMessage());
        }
    }

    /**
     * Checks that Falcon can write chunks with a dataset's client data, before any is written: the data type
     * the dataset's elements give, an error bound in a mode and range SZ compresses (unless every chunk is too
     * short to compress), and no more than 4 dimensions.
     *
     * @param dataType the SZ data type of the dataset's elements
     * @throws IllegalArgumentException if Falcon cannot
     */
    static void checkWritable(int[] cd, int dataType) {
        if (cd.length == 0) {
            return;
        }
        long[] r = dimensions(cd);
        if (cd[1] != dataType) {
            throw new IllegalArgumentException("its SZ data type, " + cd[1] + ", is not its elements', " + dataType);
        }
        if (elements(r[4], r[3], r[2], r[1], r[0]) < MIN_ELEMENTS) {
            return;
        }
        if (!hasBound(cd)) {
            throw new IllegalArgumentException("its SZ client data carry no error bound (H5Z-SZ reads one from its"
                    + " configuration file)");
        }
        if (cd[0] < 1 || cd[0] > 4) {
            throw new IllegalArgumentException("SZ compresses 1 to 4 dimensions, not " + cd[0]);
        }
        int k = cd[0] == 1 ? 4 : cd[0] + 2;
        if (cd.length < k + BOUND_VALUES) {
            throw new IllegalArgumentException(cd.length + " SZ client-data values, fewer than its bound needs");
        }
        SzEncoder.check(cd[1], cd[k], real(cd, k + 1), real(cd, k + 3), real(cd, k + 5));
    }

    /** A double of the error bound: two values, high word first. */
    static double real(int[] cd, int at) {
        return Double.longBitsToDouble((cd[at] & 0xFFFFFFFFL) << 32 | (cd[at + 1] & 0xFFFFFFFFL));
    }

    /** {@code SZ_cdArrayToMetaData}: {@code {r1, r2, r3, r4, r5}}. */
    private static long[] dimensions(int[] cd) {
        if (cd.length < 4) {
            throw new HdfFormatException("sz filter: " + cd.length + " client-data values, fewer than 4");
        }
        return switch (cd[0]) {
            case 1 -> new long[] {u32(cd, 2) << 32 | u32(cd, 3), 0, 0, 0, 0};
            case 2 -> new long[] {u32(cd, 2), u32(cd, 3), 0, 0, 0};
            case 3 -> new long[] {u32(cd, 2), u32(cd, 3), u32(cd, 4), 0, 0};
            case 4 -> new long[] {u32(cd, 2), u32(cd, 3), u32(cd, 4), u32(cd, 5), 0};
            default -> new long[] {u32(cd, 2), u32(cd, 3), u32(cd, 4), u32(cd, 5), u32(cd, 6)};
        };
    }

    /** Reverses the filter, producing at most {@code maxBytes}. */
    static byte[] decode(int[] cd, byte[] data, long maxBytes) {
        if (cd.length == 0) {
            return data; // "special data such as string"
        }
        if (cd.length < 4) {
            throw new HdfFormatException("sz filter: " + cd.length + " client-data values, fewer than 4");
        }
        int dimSize = cd[0];
        int dataType = cd[1];
        long r5 = 0;
        long r4 = 0;
        long r3 = 0;
        long r2 = 0;
        long r1;
        switch (dimSize) {
            case 1 -> r1 = u32(cd, 2) << 32 | u32(cd, 3);
            case 2 -> {
                r2 = u32(cd, 3);
                r1 = u32(cd, 2);
            }
            case 3 -> {
                r3 = u32(cd, 4);
                r2 = u32(cd, 3);
                r1 = u32(cd, 2);
            }
            case 4 -> {
                r4 = u32(cd, 5);
                r3 = u32(cd, 4);
                r2 = u32(cd, 3);
                r1 = u32(cd, 2);
            }
            default -> {
                r5 = u32(cd, 6);
                r4 = u32(cd, 5);
                r3 = u32(cd, 4);
                r2 = u32(cd, 3);
                r1 = u32(cd, 2);
            }
        }
        if (elements(r5, r4, r3, r2, r1) < 20) {
            return data; // H5Z_filter_sz leaves short chunks as they are
        }
        try {
            return SzDecoder.decompress(dataType, data, 0, data.length, r5, r4, r3, r2, r1,
                    (int) Math.min(maxBytes, Integer.MAX_VALUE - 8));
        } catch (CompressionFormatException e) {
            throw new HdfFormatException("sz filter: " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new HdfUnsupportedException("sz filter: " + e.getMessage());
        }
    }

    private static long u32(int[] cd, int i) {
        if (i >= cd.length) {
            throw new HdfFormatException("sz filter: " + cd.length + " client-data values for " + cd[0] + " dimensions");
        }
        return cd[i] & 0xFFFFFFFFL;
    }

    /** {@code computeDataLength}: the product up to the first zero size; past 2^62, as many as matters. */
    private static long elements(long r5, long r4, long r3, long r2, long r1) {
        long[] dims = {r1, r2, r3, r4, r5};
        long n = 0;
        for (long d : dims) {
            if (d == 0) {
                break;
            }
            n = n == 0 ? d : n > Long.MAX_VALUE / d ? Long.MAX_VALUE : n * d;
        }
        return n;
    }
}
