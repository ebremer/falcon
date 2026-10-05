package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.checksum.Fletcher32;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decoders for HDF5's six built-in filters: {@code deflate} (via {@code java.util.zip}),
 * {@code shuffle}, {@code fletcher32} (verified), {@code szip} ({@link Szip}), {@code nbit}, and
 * {@code scaleoffset} ({@link ScaleOffset}); and for the common third-party filters LZF, Blosc, LZ4,
 * bitshuffle, and Zstandard ({@link ThirdPartyFilters}).
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
            case SHUFFLE -> unshuffle(data, filter.clientData().length > 0 ? filter.clientData()[0] : elementSize);
            case FLETCHER32 -> verifyAndStripFletcher32(data);
            case SZIP -> Szip.decode(data, filter.clientData(), maxBytes);
            case SCALEOFFSET -> ScaleOffset.decode(data, filter.clientData(), maxBytes);
            case NBIT -> nbit(data, filter.clientData(), uncompressedSize);
            case ThirdPartyFilters.LZF, ThirdPartyFilters.BLOSC, ThirdPartyFilters.LZ4, ThirdPartyFilters.BITSHUFFLE,
                 ThirdPartyFilters.ZSTD -> ThirdPartyFilters.decode(filter.id(), filter.clientData(), data, elementSize,
                    uncompressedSize, maxBytes);
            default -> throw new HdfUnsupportedException("HDF5 filter id " + filter.id() + " is not supported");
        };
    }

    /** Headroom over the chunk size for intermediate pipeline stages (scale-offset header, checksums). */
    private static final int DECODE_SLACK = 4096;

    private static final int NBIT_ATOMIC = 1;
    private static final int NBIT_COMPOUND = 3;

    /**
     * Decodes an n-bit chunk (filter id 5). The filter drops each element's padding bits, packing only
     * the {@code precision} significant bits (at bit {@code offset}, MSB-first, from the chunk start);
     * decoding restores full-width, zero-padded elements in the datatype's byte order. Atomic client
     * data: {@code [total, flag, nelmts, ATOMIC, size, order, precision, offset]}; compound datatypes
     * ({@code clientData[3] == 3}) pack each record's atomic members in turn.
     */
    private static byte[] nbit(byte[] data, int[] clientData, int uncompressedSize) {
        if (clientData.length > 1 && clientData[1] == 1) {
            // "no compression needed": the datatype is already at full precision, so libhdf5 stored the
            // chunk untouched (H5Z__filter_nbit).
            return data;
        }
        int typeClass = clientData.length > 3 ? clientData[3] : NBIT_ATOMIC;
        if (typeClass == NBIT_COMPOUND) {
            return nbitCompound(data, clientData, uncompressedSize);
        }
        if (clientData.length < 8 || typeClass != NBIT_ATOMIC) {
            throw new HdfUnsupportedException("n-bit datatype class " + typeClass + " is not supported");
        }
        int size = clientData[4];
        boolean bigEndian = clientData[5] == 1;
        int precision = clientData[6];
        int offset = clientData[7];
        int elements = uncompressedSize / size;

        byte[] out = new byte[uncompressedSize]; // padding bits stay zero
        long[] bit = {0};
        for (int i = 0; i < elements; i++) {
            unpackMember(data, bit, out, i * size, size, bigEndian, precision, offset);
        }
        return out;
    }

    /**
     * Decodes a compound n-bit chunk: each record's atomic members are packed in turn, each member's
     * {@code precision} bits (MSB-first) restored full-width and zero-padded at its byte {@code offset}
     * within the record. Compound client data: {@code [total, flag, nelmts, COMPOUND, size, member
     * count]} then, per member, {@code [offset, ATOMIC, size, order, precision, bit offset]}.
     */
    private static byte[] nbitCompound(byte[] data, int[] clientData, int uncompressedSize) {
        int recordSize = clientData[4];
        int members = clientData[5];
        int elements = uncompressedSize / recordSize;
        byte[] out = new byte[uncompressedSize];
        long[] bit = {0};
        for (int i = 0; i < elements; i++) {
            int p = 6;
            for (int m = 0; m < members; m++) {
                int memberOffset = clientData[p];
                if (clientData[p + 1] != NBIT_ATOMIC) {
                    throw new HdfUnsupportedException("nested n-bit compound members are not supported");
                }
                int memberSize = clientData[p + 2];
                boolean bigEndian = clientData[p + 3] == 1;
                int precision = clientData[p + 4];
                int bitOffset = clientData[p + 5];
                unpackMember(data, bit, out, i * recordSize + memberOffset, memberSize,
                        bigEndian, precision, bitOffset);
                p += 6;
            }
        }
        return out;
    }

    /** Unpacks one atomic member's {@code precision} bits (MSB-first) into {@code out} at {@code base}. */
    private static void unpackMember(byte[] data, long[] bit, byte[] out, int base, int size,
                                     boolean bigEndian, int precision, int bitOffset) {
        long significant = 0;
        for (int b = 0; b < precision; b++) {
            significant = (significant << 1) | ((data[(int) (bit[0] >> 3)] >> (7 - (int) (bit[0] & 7))) & 1);
            bit[0]++;
        }
        long value = significant << bitOffset;
        for (int b = 0; b < size; b++) {
            int shift = bigEndian ? (size - 1 - b) * 8 : b * 8;
            out[base + b] = (byte) (value >>> shift);
        }
    }

    /**
     * Inflates a zlib-wrapped deflate stream (HDF5 {@code deflate} / gzip filter), refusing to produce
     * more than {@code maxBytes} (a deflate stream can expand ~1000:1, so a corrupt chunk could
     * otherwise exhaust the heap).
     */
    private static byte[] inflate(byte[] data, long maxBytes) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, Math.max(64, data.length * 3L)));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.finished() || inflater.needsDictionary() || inflater.needsInput())) {
                    break;
                }
                if (out.size() + (long) n > maxBytes) {
                    throw new HdfFormatException("deflate filter: chunk inflates past its " + maxBytes + "-byte bound");
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException e) {
            throw new HdfFormatException("deflate filter: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** Reverses the byte-{@code shuffle} filter for elements of {@code elementSize} bytes. */
    private static byte[] unshuffle(byte[] data, int elementSize) {
        if (elementSize <= 1) {
            return data;
        }
        int elements = data.length / elementSize;
        byte[] out = new byte[data.length];
        int p = 0;
        for (int b = 0; b < elementSize; b++) {
            for (int i = 0; i < elements; i++) {
                out[i * elementSize + b] = data[p++];
            }
        }
        int done = elements * elementSize; // any trailing bytes are stored unshuffled
        if (done < data.length) {
            System.arraycopy(data, done, out, done, data.length - done);
        }
        return out;
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
