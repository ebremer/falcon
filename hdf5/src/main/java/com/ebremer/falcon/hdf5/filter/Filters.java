package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.checksum.Fletcher32;
import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.shuffle.ByteShuffle;
import com.ebremer.falcon.core.compress.zlib.Zlib;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import java.util.Arrays;

/**
 * Decoders for HDF5's six built-in filters: {@code deflate} and {@code shuffle} (Falcon Core's zlib and
 * byte shuffle), {@code fletcher32} (verified), {@code szip} ({@link Szip}), {@code nbit} ({@link Nbit}),
 * and {@code scaleoffset} ({@link ScaleOffset}); and for the common third-party filters LZF, Blosc, LZ4,
 * bitshuffle, Zstandard, bzip2, and Blosc2 ({@link ThirdPartyFilters}), ZFP ({@link ZfpFilter}), and SZ
 * ({@link SzFilter}).
 */
public final class Filters {

    public static final int DEFLATE = 1;
    public static final int SHUFFLE = 2;
    public static final int FLETCHER32 = 3;
    public static final int SZIP = 4;
    public static final int NBIT = 5;
    public static final int SCALEOFFSET = 6;

    private Filters() {
    }

    /**
     * Reverses one filter. {@code uncompressedSize} is the chunk's full decoded size; intermediate
     * stages of a pipeline are bounded by it (plus a little slack for filters that add a trailer or
     * header), so a corrupt or malicious chunk cannot expand without limit.
     */
    public static byte[] decode(FilterPipeline.Filter filter, byte[] data, int elementSize, int uncompressedSize) {
        long maxBytes = (long) uncompressedSize + DECODE_SLACK;
        return switch (filter.id()) {
            case DEFLATE -> inflate(data, maxBytes);
            case SHUFFLE -> ByteShuffle.unshuffle(data, filter.clientData().length > 0 ? filter.clientData()[0]
                    : elementSize);
            case FLETCHER32 -> verifyAndStripFletcher32(data);
            case SZIP -> Szip.decode(data, filter.clientData(), maxBytes);
            case SCALEOFFSET -> ScaleOffset.decode(data, filter.clientData(), maxBytes);
            case NBIT -> Nbit.decode(data, filter.clientData(), uncompressedSize);
            case ThirdPartyFilters.LZF, ThirdPartyFilters.BLOSC, ThirdPartyFilters.LZ4, ThirdPartyFilters.BITSHUFFLE,
                 ThirdPartyFilters.ZSTD, ThirdPartyFilters.BZIP2, ThirdPartyFilters.BLOSC2 ->
                    ThirdPartyFilters.decode(filter.id(), filter.clientData(), data, elementSize, uncompressedSize,
                            maxBytes);
            case ZfpFilter.ID -> ZfpFilter.decode(filter.clientData(), data, maxBytes);
            case SzFilter.ID -> SzFilter.decode(filter.clientData(), data, maxBytes);
            default -> throw new HdfUnsupportedException("HDF5 filter id " + filter.id() + " is not supported");
        };
    }

    /** Headroom over the chunk size for intermediate pipeline stages (scale-offset header, checksums). */
    private static final int DECODE_SLACK = 4096;

    /**
     * Inflates a zlib-wrapped deflate stream (HDF5 {@code deflate} / gzip filter), refusing to produce
     * more than {@code maxBytes} (a deflate stream can expand ~1000:1, so a corrupt chunk could
     * otherwise exhaust the heap), and a stream that ends early or fails its check, as libhdf5's
     * {@code H5Z__filter_deflate} does.
     */
    private static byte[] inflate(byte[] data, long maxBytes) {
        try {
            return Zlib.decompress(data, 0, data.length, (int) Math.min(Integer.MAX_VALUE - 8, maxBytes));
        } catch (CompressionFormatException e) {
            throw new HdfFormatException("deflate filter: " + e.getMessage(), e);
        }
    }


    /**
     * Verifies and strips the 4-byte little-endian {@code fletcher32} checksum trailer. Like libhdf5,
     * also accepts the byte-swapped value written by releases before 1.6.3.
     */
    private static byte[] verifyAndStripFletcher32(byte[] data) {
        if (data.length < 4) {
            throw new HdfFormatException("fletcher32 chunk shorter than its checksum");
        }
        int length = data.length - 4;
        int stored = (data[length] & 0xff) | (data[length + 1] & 0xff) << 8
                | (data[length + 2] & 0xff) << 16 | (data[length + 3] & 0xff) << 24;
        int computed = Fletcher32.checksum(data, length);
        if (stored != computed && stored != Fletcher32.legacyByteSwapped(computed)) {
            throw new HdfFormatException(String.format(
                    "fletcher32 checksum mismatch: stored=0x%08x computed=0x%08x", stored, computed));
        }
        return Arrays.copyOf(data, length);
    }
}
