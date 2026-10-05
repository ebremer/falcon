package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;

/**
 * The scale-offset filter (filter id 6), transcribed from libhdf5's {@code H5Zscaleoffset.c} so that
 * Falcon reads and writes exactly what the reference library does.
 *
 * <p><b>Client data</b> (20 values): scale type (0 = float decimal scaling, 1 = float exponent
 * scaling, 2 = integer), scale factor, elements per chunk, datatype class (0 integer, 1 float), size,
 * sign (0 unsigned, 1 signed), byte order (0 little-, 1 big-endian), fill-value availability, then the
 * fill value's native (little-endian) bytes packed into the remaining 32-bit values.
 *
 * <p><b>Chunk layout</b>: a fixed 21-byte header &mdash; {@code minbits} (4 bytes LE), the width of the
 * minimum field (1 byte, always 8), the minimum (8 bytes LE), and 8 zero bytes &mdash; then the packed
 * codes, {@code minbits} bits each, most-significant bit first, in a buffer of
 * {@code floor(n·minbits/8) + 1} bytes. Two special cases: {@code minbits == 0} (every element equal,
 * no codes stored) and {@code minbits} equal to the full precision, where the elements follow the
 * header verbatim in little-endian order. With a defined fill value, an all-ones code stands for an
 * element that held the fill value.
 *
 * <p>Integers decode as {@code code + minimum} in the type's width; decimal-scaled floats as
 * {@code code / 10^D + minimum}, computed in the type's own precision as libhdf5 does.
 */
public final class ScaleOffset {

    /** Client-data indices (H5Z_SCALEOFFSET_PARM_*). */
    static final int PARM_SCALETYPE = 0;
    static final int PARM_SCALEFACTOR = 1;
    static final int PARM_NELMTS = 2;
    static final int PARM_CLASS = 3;
    static final int PARM_SIZE = 4;
    static final int PARM_SIGN = 5;
    static final int PARM_ORDER = 6;
    static final int PARM_FILAVAIL = 7;
    static final int PARM_FILVAL = 8;
    /** Total client-data values libhdf5 stores (H5Z_SCALEOFFSET_TOTAL_NPARMS). */
    public static final int TOTAL_PARMS = 20;

    static final int SCALE_FLOAT_DSCALE = 0;
    static final int SCALE_FLOAT_ESCALE = 1;
    /** Integer scaling; the scale factor is the user's minbits (0 = compute it). */
    public static final int SCALE_INT = 2;
    static final int CLASS_INTEGER = 0;
    static final int CLASS_FLOAT = 1;

    /** Bytes reserved at the start of every chunk for minbits and the minimum. */
    static final int HEADER_SIZE = 21;

    private ScaleOffset() {
    }

    /**
     * Decodes one chunk.
     *
     * @param maxBytes an upper bound on the decoded size, to reject corrupt element counts
     */
    public static byte[] decode(byte[] data, int[] cd, long maxBytes) {
        if (cd.length < PARM_FILVAL) {
            throw new HdfFormatException("scale-offset filter has " + cd.length + " client-data values");
        }
        int scaleType = cd[PARM_SCALETYPE];
        int typeClass = cd[PARM_CLASS];
        int size = cd[PARM_SIZE];
        boolean signed = cd[PARM_SIGN] == 1;
        boolean bigEndian = cd[PARM_ORDER] == 1;
        boolean fillDefined = cd[PARM_FILAVAIL] == 1;
        long elements = Integer.toUnsignedLong(cd[PARM_NELMTS]);
        if (size != 1 && size != 2 && size != 4 && size != 8) {
            throw new HdfFormatException("scale-offset datatype size " + size + " is not 1, 2, 4 or 8");
        }
        if (typeClass == CLASS_FLOAT && size != 4 && size != 8) {
            throw new HdfFormatException("scale-offset float size " + size);
        }
        long outBytes = elements * size;
        if (outBytes > maxBytes || outBytes > Integer.MAX_VALUE - 8) {
            throw new HdfFormatException("scale-offset chunk claims " + elements + " elements of " + size
                    + " bytes, more than the chunk holds");
        }
        if (data.length < HEADER_SIZE) {
            throw new HdfFormatException("scale-offset chunk is shorter than its 21-byte header");
        }
        int n = (int) elements;
        int bits = size * 8;
        long minBits = readLittleEndian(data, 0, 4);
        int minvalSize = Math.min(8, data[4] & 0xff);
        long minval = readLittleEndian(data, 5, minvalSize);
        if (minBits > bits) {
            throw new HdfFormatException("scale-offset minbits " + minBits + " exceeds the " + bits + "-bit type");
        }
        byte[] out = new byte[(int) outBytes];

        if (minBits == bits) { // full precision: the elements follow the header verbatim (native = LE)
            if (data.length - HEADER_SIZE < outBytes) {
                throw new HdfFormatException("scale-offset full-precision chunk is truncated");
            }
            System.arraycopy(data, HEADER_SIZE, out, 0, out.length);
            if (bigEndian) {
                swapBytes(out, size);
            }
            return out;
        }

        int k = (int) minBits;
        if (k > 0 && (long) (data.length - HEADER_SIZE) * 8 < (long) n * k) {
            throw new HdfFormatException("scale-offset chunk is truncated: " + n + " codes of " + k + " bits");
        }
        long fillCode = k == 64 ? -1L : (1L << k) - 1;
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        long fill = fillDefined ? fillBits(cd, size) : 0;

        if (typeClass == CLASS_INTEGER) {
            long bit = (long) HEADER_SIZE * 8;
            for (int i = 0; i < n; i++) {
                long code = k == 0 ? 0 : readBits(data, bit, k);
                bit += k;
                long value = fillDefined && code == fillCode ? fill : (code + minval) & mask;
                writeElement(out, i * size, size, value, bigEndian);
            }
            return out;
        }
        if (typeClass != CLASS_FLOAT) {
            throw new HdfFormatException("unknown scale-offset datatype class " + typeClass);
        }
        if (scaleType == SCALE_FLOAT_ESCALE) {
            throw new HdfUnsupportedException("exponent-scaling float scale-offset is not implemented by libhdf5");
        }
        if (scaleType != SCALE_FLOAT_DSCALE) {
            throw new HdfFormatException("scale type " + scaleType + " for a float scale-offset chunk");
        }
        int decimalScale = cd[PARM_SCALEFACTOR];
        long bit = (long) HEADER_SIZE * 8;
        if (size == 4) {
            float min = Float.intBitsToFloat((int) minval);
            float pow = (float) Math.pow(10.0, decimalScale);
            for (int i = 0; i < n; i++) {
                long code = k == 0 ? 0 : readBits(data, bit, k);
                bit += k;
                long value = fillDefined && code == fillCode ? fill
                        : Float.floatToRawIntBits((float) (int) code / pow + min) & 0xffff_ffffL;
                writeElement(out, i * 4, 4, value, bigEndian);
            }
        } else {
            double min = Double.longBitsToDouble(minval);
            double pow = Math.pow(10.0, decimalScale);
            for (int i = 0; i < n; i++) {
                long code = k == 0 ? 0 : readBits(data, bit, k);
                bit += k;
                long value = fillDefined && code == fillCode ? fill
                        : Double.doubleToRawLongBits((double) code / pow + min);
                writeElement(out, i * 8, 8, value, bigEndian);
            }
        }
        return out;
    }

    /**
     * Encodes one chunk of integer data exactly as libhdf5's scale-offset filter does with automatic
     * minbits (scale type 2, factor 0): the minimum and maximum are taken over the elements that do not
     * equal the fill value, which encode as the all-ones code.
     *
     * @param chunk     the chunk's elements, little-endian
     * @param size      element size in bytes (1, 2, 4 or 8)
     * @param signed    whether the integers are signed
     * @param fill      the fill value's bits (low {@code size} bytes), or {@code null} if undefined
     */
    public static byte[] encodeInteger(byte[] chunk, int size, boolean signed, Long fill) {
        int bits = size * 8;
        int n = chunk.length / size;
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            long v = readLittleEndian(chunk, i * size, size);
            values[i] = signed ? signExtend(v, bits) : v;
        }
        long fillValue = fill == null ? 0 : (signed ? signExtend(fill & mask, bits) : fill & mask);

        // H5Z_scaleoffset_max_min_1 / _2: min and max over the non-fill elements (0/0 if there are none)
        long min = 0;
        long max = 0;
        boolean seen = false;
        for (long v : values) {
            if (fill != null && v == fillValue) {
                continue;
            }
            if (!seen) {
                min = max = v;
                seen = true;
            } else if (signed) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            } else {
                min = Long.compareUnsigned(v, min) < 0 ? v : min;
                max = Long.compareUnsigned(v, max) > 0 ? v : max;
            }
        }
        // H5Z_scaleoffset_check_1 / _2: a range this wide is stored at full precision, minimum unset (0)
        long minval = 0;
        int minBits;
        if (Long.compareUnsigned((max - min) & mask, mask - 2) > 0) {
            minBits = bits;
        } else {
            long span = (max - min + 1) & mask;
            minBits = ceilLog2(fill != null ? span + 1 : span);
            minval = min;
            if (minBits != bits) {
                long fillCode = (1L << minBits) - 1;
                for (int i = 0; i < n; i++) {
                    values[i] = fill != null && values[i] == fillValue ? fillCode : (values[i] - min) & mask;
                }
            }
        }

        byte[] out;
        if (minBits == bits) {
            out = new byte[HEADER_SIZE + chunk.length];
            System.arraycopy(chunk, 0, out, HEADER_SIZE, chunk.length);
        } else {
            out = new byte[HEADER_SIZE + (int) ((long) n * minBits / 8) + 1];
            long bit = (long) HEADER_SIZE * 8;
            for (int i = 0; minBits > 0 && i < n; i++) {
                writeBits(out, bit, minBits, values[i]);
                bit += minBits;
            }
        }
        writeLittleEndian(out, 0, 4, minBits);
        out[4] = 8; // sizeof(unsigned long long)
        writeLittleEndian(out, 5, 8, minval);
        return out;
    }

    /**
     * The client data libhdf5's {@code H5Z__set_local_scaleoffset} stores for an integer dataset with
     * automatic minbits.
     */
    public static int[] integerClientData(int chunkElements, int size, boolean signed, boolean bigEndian, Long fill) {
        int[] cd = new int[TOTAL_PARMS];
        cd[PARM_SCALETYPE] = SCALE_INT;
        cd[PARM_SCALEFACTOR] = 0;
        cd[PARM_NELMTS] = chunkElements;
        cd[PARM_CLASS] = CLASS_INTEGER;
        cd[PARM_SIZE] = size;
        cd[PARM_SIGN] = signed ? 1 : 0;
        cd[PARM_ORDER] = bigEndian ? 1 : 0;
        cd[PARM_FILAVAIL] = fill == null ? 0 : 1;
        if (fill != null) {
            for (int b = 0; b < size; b++) { // native (little-endian) bytes, packed into 32-bit values
                cd[PARM_FILVAL + b / 4] |= (int) (((fill >>> (8 * b)) & 0xff) << (8 * (b % 4)));
            }
        }
        return cd;
    }

    /** The fill value's bits from client data: native little-endian bytes from cd[8] onwards. */
    private static long fillBits(int[] cd, int size) {
        if (cd.length < PARM_FILVAL + (size + 3) / 4) {
            throw new HdfFormatException("scale-offset fill value missing from client data");
        }
        long v = 0;
        for (int b = 0; b < size; b++) {
            long octet = (cd[PARM_FILVAL + b / 4] >>> (8 * (b % 4))) & 0xff;
            v |= octet << (8 * b);
        }
        return v;
    }

    /** {@code H5Z__scaleoffset_log2}: the smallest {@code v} with {@code 2^v >= num} (num unsigned). */
    private static int ceilLog2(long num) {
        int floor = 63 - Long.numberOfLeadingZeros(num);
        if (num == 0) {
            return 0;
        }
        return Long.bitCount(num) == 1 ? floor : floor + 1;
    }

    private static long readBits(byte[] data, long bit, int count) {
        long v = 0;
        for (int i = 0; i < count; i++, bit++) {
            v = (v << 1) | ((data[(int) (bit >>> 3)] >>> (7 - (int) (bit & 7))) & 1);
        }
        return v;
    }

    private static void writeBits(byte[] data, long bit, int count, long value) {
        for (int i = count - 1; i >= 0; i--, bit++) {
            if (((value >>> i) & 1) != 0) {
                data[(int) (bit >>> 3)] |= (byte) (0x80 >>> (int) (bit & 7));
            }
        }
    }

    private static void writeElement(byte[] out, int offset, int size, long value, boolean bigEndian) {
        for (int b = 0; b < size; b++) {
            out[offset + (bigEndian ? size - 1 - b : b)] = (byte) (value >>> (8 * b));
        }
    }

    private static void swapBytes(byte[] data, int size) {
        for (int off = 0; off + size <= data.length; off += size) {
            for (int a = off, z = off + size - 1; a < z; a++, z--) {
                byte t = data[a];
                data[a] = data[z];
                data[z] = t;
            }
        }
    }

    private static long signExtend(long value, int bits) {
        return bits >= 64 ? value : (value << (64 - bits)) >> (64 - bits);
    }

    static long readLittleEndian(byte[] d, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v |= (long) (d[off + i] & 0xff) << (8 * i);
        }
        return v;
    }

    static void writeLittleEndian(byte[] d, int off, int n, long v) {
        for (int i = 0; i < n; i++) {
            d[off + i] = (byte) (v >>> (8 * i));
        }
    }
}
