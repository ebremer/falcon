package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decoders for HDF5's built-in filters. Stage H4 (increment 1) implements {@code deflate} (via
 * {@code java.util.zip}), {@code shuffle}, and {@code fletcher32}; {@code nbit}, {@code scaleoffset},
 * and {@code szip} follow in later increments.
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

    // szip option-mask bits (H5Zszip.c)
    private static final int SZ_LSB_MASK = 0x08;
    private static final int SZ_MSB_MASK = 0x10;
    private static final int SZ_NN_MASK = 0x20;

    public static byte[] decode(FilterPipeline.Filter filter, byte[] data, int elementSize, int uncompressedSize) {
        return switch (filter.id()) {
            case DEFLATE -> inflate(data);
            case SHUFFLE -> unshuffle(data, filter.clientData().length > 0 ? filter.clientData()[0] : elementSize);
            case FLETCHER32 -> stripFletcher32(data);
            case SZIP -> szip(data, filter.clientData(), elementSize, uncompressedSize);
            default -> throw new HdfUnsupportedException(
                    "HDF5 filter id " + filter.id() + " is not yet supported (arrives in a later H4 increment)");
        };
    }

    /**
     * Decodes an szip-compressed chunk (filter id 4) via the pure-Java {@link Aec} decoder, mapping
     * the filter's client data ({@code mask, pixelsPerBlock, bitsPerPixel, pixelsPerScanline}) to AEC
     * parameters and packing the decoded samples back to bytes in the chunk's byte order.
     */
    private static byte[] szip(byte[] data, int[] clientData, int elementSize, int uncompressedSize) {
        if (clientData.length < 4) {
            throw new HdfFormatException("szip filter requires 4 client-data values, got " + clientData.length);
        }
        int optionMask = clientData[0];
        int pixelsPerBlock = clientData[1];
        int bitsPerPixel = clientData[2];
        int pixelsPerScanline = clientData[3];

        int flags = (optionMask & SZ_NN_MASK) != 0 ? Aec.FLAG_PREPROCESS : 0;
        boolean mostSignificantFirst = (optionMask & SZ_MSB_MASK) != 0;
        int samples = uncompressedSize / elementSize;
        int rsi = pixelsPerBlock == 0 ? 1 : pixelsPerScanline / pixelsPerBlock;

        long[] values = Aec.decode(data, samples, bitsPerPixel, pixelsPerBlock, rsi, flags);

        byte[] out = new byte[uncompressedSize];
        for (int i = 0; i < samples; i++) {
            long v = values[i];
            int base = i * elementSize;
            for (int b = 0; b < elementSize; b++) {
                int shift = mostSignificantFirst ? (elementSize - 1 - b) * 8 : b * 8;
                out[base + b] = (byte) (v >>> shift);
            }
        }
        return out;
    }

    /** Inflates a zlib-wrapped deflate stream (HDF5 {@code deflate} / gzip filter). */
    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 3));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.finished() || inflater.needsDictionary() || inflater.needsInput())) {
                    break;
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

    /** Strips the 4-byte {@code fletcher32} checksum trailer. */
    private static byte[] stripFletcher32(byte[] data) {
        if (data.length < 4) {
            throw new HdfFormatException("fletcher32 chunk shorter than its checksum");
        }
        return Arrays.copyOf(data, data.length - 4);
    }
}
