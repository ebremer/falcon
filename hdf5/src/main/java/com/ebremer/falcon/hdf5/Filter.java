package com.ebremer.falcon.hdf5;

import java.util.Arrays;
import java.util.Objects;

/**
 * One filter of a chunked dataset's pipeline (see {@link Dataset#filters()}), as libhdf5's
 * {@code H5Pget_filter2} reports it. Filters are listed in the order they were applied on writing.
 *
 * @param id         the registered filter identifier, such as {@link #DEFLATE} or {@link #ZSTD}
 * @param name       the name the file stores for the filter; otherwise libhdf5's name for its built-in
 *                   filters ({@code "deflate"}, {@code "shuffle"}, {@code "fletcher32"}, {@code "szip"},
 *                   {@code "nbit"}, {@code "scaleoffset"}); otherwise empty
 * @param optional   true if a chunk may have skipped the filter (when it would not help, say)
 * @param clientData the filter's parameters, as stored (libhdf5's {@code cd_values}, including any its
 *                   filter added when the dataset was created)
 */
public record Filter(int id, String name, boolean optional, int[] clientData) {

    /** gzip/zlib compression (built in). */
    public static final int DEFLATE = 1;
    /** Byte shuffle (built in). */
    public static final int SHUFFLE = 2;
    /** Fletcher-32 checksum (built in). */
    public static final int FLETCHER32 = 3;
    /** szip / CCSDS 121.0 compression (built in). */
    public static final int SZIP = 4;
    /** N-bit packing (built in). */
    public static final int NBIT = 5;
    /** Scale-offset compression (built in). */
    public static final int SCALEOFFSET = 6;
    /** bzip2 compression. */
    public static final int BZIP2 = 307;
    /** LZF compression (h5py's filter). */
    public static final int LZF = 32000;
    /** Blosc compression. */
    public static final int BLOSC = 32001;
    /** LZ4 compression. */
    public static final int LZ4 = 32004;
    /** Bitshuffle, alone or with LZ4 or zstd. */
    public static final int BITSHUFFLE = 32008;
    /** ZFP compression of floating-point and integer arrays (LLNL's H5Z-ZFP). */
    public static final int ZFP = 32013;
    /** Zstandard compression. */
    public static final int ZSTD = 32015;
    /** SZ, error-bounded lossy compression (SZ 2's H5Z-SZ). */
    public static final int SZ = 32017;
    /** Blosc2 compression (hdf5-blosc2: Blosc2 frames, and b2nd arrays for chunks of rank 2 and up). */
    public static final int BLOSC2 = 32026;

    /**
     * A filter, copying {@code clientData}.
     *
     * @param id         the registered filter identifier
     * @param name       the filter's name, or empty; not null
     * @param optional   true if a chunk may have skipped the filter
     * @param clientData the filter's parameters
     */
    public Filter {
        Objects.requireNonNull(name, "name");
        clientData = clientData.clone();
    }

    /**
     * A copy of the filter's parameters.
     *
     * @return the filter's parameters ({@code cd_values}), copied
     */
    @Override
    public int[] clientData() {
        return clientData.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Filter f && id == f.id && name.equals(f.name) && optional == f.optional
                && Arrays.equals(clientData, f.clientData);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * id + name.hashCode()) + Boolean.hashCode(optional)) + Arrays.hashCode(clientData);
    }

    @Override
    public String toString() {
        return "Filter[" + id + (name.isEmpty() ? "" : " " + name) + (optional ? ", optional" : "")
                + ", " + Arrays.toString(clientData) + "]";
    }
}
