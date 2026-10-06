package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.sz.SzDecoder;
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

    private SzFilter() {
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
