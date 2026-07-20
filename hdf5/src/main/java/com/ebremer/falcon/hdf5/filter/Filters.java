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
            case SCALEOFFSET -> scaleOffset(data, filter.clientData(), elementSize, uncompressedSize);
            case NBIT -> nbit(data, filter.clientData(), uncompressedSize);
            default -> throw new HdfUnsupportedException(
                    "HDF5 filter id " + filter.id() + " is not yet supported (arrives in a later H4 increment)");
        };
    }

    // scale-offset scale types (H5Zscaleoffset.c)
    private static final int SO_FLOAT_DSCALE = 0;
    private static final int SO_FLOAT_ESCALE = 1;
    private static final int SO_INT = 2;

    /**
     * Decodes a scale-offset chunk (filter id 6). The chunk header holds {@code minbits} (4-byte LE) and
     * the chunk's minimum value; each element is stored as {@code minbits} bits (min-subtracted,
     * MSB-first). An all-ones code marks an element that held the fill value. Integer and
     * floating-point (decimal-scaling) variants are handled per {@code clientData[0]}.
     */
    private static byte[] scaleOffset(byte[] data, int[] clientData, int elementSize, int uncompressedSize) {
        int scaleType = clientData.length > 0 ? clientData[0] : SO_INT;
        if (scaleType == SO_FLOAT_DSCALE) {
            return scaleOffsetFloat(data, clientData, elementSize, uncompressedSize);
        }
        if (scaleType == SO_FLOAT_ESCALE) {
            throw new HdfUnsupportedException("exponent-scaling float scale-offset is not produced by HDF5");
        }
        int elements = uncompressedSize / elementSize;
        int minBits = (int) readLittleEndian(data, 0, 4);
        long minVal = readSignedLittleEndian(data, 5, elementSize);
        byte[] out = new byte[uncompressedSize];

        if (minBits == 0) { // every value equals the minimum
            for (int i = 0; i < elements; i++) {
                writeLittleEndian(out, i * elementSize, elementSize, minVal);
            }
            return out;
        }

        long fillMarker = (1L << minBits) - 1;
        long fillValue = 0; // elements that held the dataset fill value
        int packedBytes = (int) (((long) elements * minBits + 7) / 8);
        long bit = (long) (data.length - packedBytes) * 8; // packed data sits at the chunk's end
        for (int i = 0; i < elements; i++) {
            long code = 0;
            for (int b = 0; b < minBits; b++) {
                code = (code << 1) | ((data[(int) (bit >> 3)] >> (7 - (int) (bit & 7))) & 1);
                bit++;
            }
            long value = code == fillMarker ? fillValue : code + minVal;
            writeLittleEndian(out, i * elementSize, elementSize, value);
        }
        return out;
    }

    /**
     * Decodes a floating-point (decimal-scaling) scale-offset chunk. Header: {@code minbits(4) ·
     * minval-size(1) · minimum · fill value} (the last two each {@code minval-size} bytes, as native
     * floats). Each element's {@code minbits}-bit code {@code c} restores to {@code c · 10^-D + min}
     * where {@code D} is the decimal scale factor ({@code clientData[1]}); an all-ones code restores the
     * fill value. The decoded values are little-endian (the layout HDF5's scale-offset produces).
     */
    private static byte[] scaleOffsetFloat(byte[] data, int[] clientData, int elementSize, int uncompressedSize) {
        int minBits = (int) readLittleEndian(data, 0, 4);
        int minvalSize = data[4] & 0xff; // width of the min/fill fields (the float itself takes elementSize)
        int scaleFactor = clientData.length > 1 ? clientData[1] : 0;
        double scale = Math.pow(10.0, -scaleFactor);
        int elements = uncompressedSize / elementSize;
        double min = decodeFloat(readLittleEndian(data, 5, elementSize), elementSize);
        double fill = decodeFloat(readLittleEndian(data, 5 + minvalSize, elementSize), elementSize);
        byte[] out = new byte[uncompressedSize];

        if (minBits == 0) { // every value equals the minimum
            for (int i = 0; i < elements; i++) {
                putFloat(out, i * elementSize, elementSize, min);
            }
            return out;
        }
        long fillMarker = (1L << minBits) - 1;
        long bit = (long) (5 + 2 * minvalSize) * 8; // packed codes follow the header
        for (int i = 0; i < elements; i++) {
            long code = 0;
            for (int b = 0; b < minBits; b++) {
                code = (code << 1) | ((data[(int) (bit >> 3)] >> (7 - (int) (bit & 7))) & 1);
                bit++;
            }
            putFloat(out, i * elementSize, elementSize, code == fillMarker ? fill : code * scale + min);
        }
        return out;
    }

    private static double decodeFloat(long raw, int size) {
        return size == 8 ? Double.longBitsToDouble(raw) : Float.intBitsToFloat((int) raw);
    }

    private static void putFloat(byte[] out, int offset, int size, double value) {
        if (size == 8) {
            writeLittleEndian(out, offset, 8, Double.doubleToLongBits(value));
        } else {
            writeLittleEndian(out, offset, 4, Float.floatToIntBits((float) value));
        }
    }

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

    private static long readLittleEndian(byte[] d, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= (long) (d[off + i] & 0xff) << (8 * i);
        }
        return v;
    }

    private static long readSignedLittleEndian(byte[] d, int off, int n) {
        long v = readLittleEndian(d, off, n);
        if (n < 8) {
            long signBit = 1L << (n * 8 - 1);
            if ((v & signBit) != 0) {
                v |= -(1L << (n * 8));
            }
        }
        return v;
    }

    private static void writeLittleEndian(byte[] d, int off, int n, long v) {
        for (int i = 0; i < n; i++) {
            d[off + i] = (byte) (v >>> (8 * i));
        }
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
