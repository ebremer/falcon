package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;

/**
 * An SZ stream of floats or doubles, parsed as SZ 2.1.12's {@code new_TightDataPointStorageF_fromFlatBytes}
 * and {@code new_TightDataPointStorageD_fromFlatBytes} do: {@code version (3) · flags (1) · parameters
 * (28 for floats, 36 for doubles) · length (4 or 8)}, then one of a stored copy of the data (lossless), one
 * value (all the same), the regression format, or the classic one's fields and arrays.
 */
final class SzStorage {

    // The flag byte (sameRByte).
    private static final int SAME = 0x01;
    private static final int PROTECT_RANGE = 0x04;
    private static final int ACCELERATE_PWR = 0x08;
    private static final int LOSSLESS = 0x10;
    private static final int PW_REL = 0x20;
    private static final int SIZE_TYPE_8 = 0x40;
    private static final int REGRESSION = 0x80;

    /** More quantization intervals than SZ ever uses (its maximum is 65536): a corrupt stream. */
    static final int MAX_INTERVALS = 1 << 20;

    final SzBuffer in;
    final int width;
    final boolean lossless;
    final boolean allSame;
    final boolean pwRel;
    final boolean accelerate;
    final boolean protectValueRange;
    final int sizeType;
    final int solId;
    /** The data's minimum and maximum (parameters bytes 20 on), for {@code protectValueRange}. */
    final double min;
    final double max;
    /** Where the stored data (lossless), the one value (all the same), or the regression format starts. */
    final int dataAt;
    final boolean regression;

    // The classic format's fields.
    int intervals;
    double medianValue;
    int reqLength;
    int plusBits;
    int maxBits;
    double realPrecision;
    long exactDataNum;
    double minLogValue;
    int typeArrayAt;
    int pwrErrBoundAt;
    int pwrErrBoundSize;
    int leadNumAt;
    long leadNumSize;
    int exactMidAt;
    long exactMidSize;
    int residualAt;

    /**
     * @param width 4 for floats, 8 for doubles
     */
    SzStorage(SzBuffer in, int width) {
        this.in = in;
        this.width = width;
        int metaLength = width == 4 ? 28 : 36; // MetaDataByteLength, MetaDataByteLength_double
        checkVersion(in);
        int flags = in.u8(3);
        lossless = (flags & LOSSLESS) != 0;
        pwRel = (flags & PW_REL) != 0;
        sizeType = (flags & SIZE_TYPE_8) != 0 ? 8 : 4;
        protectValueRange = (flags & PROTECT_RANGE) != 0;
        accelerate = (flags & ACCELERATE_PWR) != 0;
        boolean same = (flags & SAME) != 0;
        int meta = 4;
        solId = in.u8(meta + 14);
        // convertBytesToSZParams sets the minimum and maximum only for its own data type (bits 0-2 of byte 5).
        int paramsType = in.u8(meta + 5) & 0x07;
        if (width == 4 && paramsType == SzDecoder.FLOAT) {
            min = in.beFloat(meta + 20);
            max = in.beFloat(meta + 24);
        } else if (width == 8 && paramsType == SzDecoder.DOUBLE) {
            min = in.beDouble(meta + 20);
            max = in.beDouble(meta + 28);
        } else {
            min = 0;
            max = 0;
        }
        int index = meta + metaLength;
        in.size(index, sizeType); // dataSeriesLength: the caller's dimensions decide the length
        index += sizeType;
        dataAt = index;
        allSame = !lossless && same;
        regression = !lossless && !same && (flags & REGRESSION) != 0;
        if (lossless || same || regression) {
            return;
        }
        index += 4; // max_quant_intervals
        if (pwRel) {
            index += 1; // radExpo
            index += sizeType; // segment_size
            pwrErrBoundSize = in.be32(index);
            if (pwrErrBoundSize < 0) {
                throw new CompressionFormatException("SZ sign array of " + pwrErrBoundSize + " bytes");
            }
            index += 4;
        }
        intervals = in.be32(index);
        index += 4;
        medianValue = width == 4 ? in.beFloat(index) : in.beDouble(index);
        index += width;
        reqLength = (byte) in.u8(index++); // a C char: signed
        if (pwRel && accelerate) {
            plusBits = in.u8(index++);
            maxBits = in.u8(index++);
        }
        realPrecision = in.beDouble(index);
        index += 8;
        long typeArraySize = in.size(index, sizeType);
        index += sizeType;
        exactDataNum = in.size(index, sizeType);
        index += sizeType;
        exactMidSize = in.size(index, sizeType);
        index += sizeType;
        leadNumSize = (exactDataNum * 2 + 7) / 8;
        if (pwRel) {
            minLogValue = width == 4 ? in.beFloat(index) : in.beDouble(index);
            index += width;
        }
        typeArrayAt = index;
        long at = index + typeArraySize;
        pwrErrBoundAt = checked(at);
        at += pwrErrBoundSize;
        leadNumAt = checked(at);
        at += leadNumSize;
        exactMidAt = checked(at);
        at += exactMidSize;
        residualAt = checked(at);
        if (intervals < 0 || intervals > MAX_INTERVALS) {
            throw new CompressionFormatException("SZ stream of " + Integer.toUnsignedString(intervals)
                    + " quantization intervals");
        }
    }

    private int checked(long at) {
        in.require(0, at);
        return (int) at;
    }

    /**
     * {@code checkVersion2}: a stream from before SZ 2.1.8 must be this version's own (2.1.12), so is
     * refused (libSZ exits); later ones are read.
     */
    static void checkVersion(SzBuffer in) {
        int major = (byte) in.u8(0);
        int minor = (byte) in.u8(1);
        int revision = (byte) in.u8(2);
        if (major * 10000 + minor * 100 + revision < 20108) {
            throw new UnsupportedCompressionException("SZ stream version " + major + "." + minor + "." + revision
                    + " is not supported");
        }
    }

    /** The two-bit lead counts of the unpredictable values. */
    SzExact exact() {
        byte[] leadNum = in.twoBit(leadNumAt, leadNumSize, exactDataNum);
        return new SzExact(in, leadNum, exactMidAt, residualAt, reqLength, width);
    }

    /** The decoded quantization codes of {@code count} values. */
    int[] types(int count) {
        return SzHuffman.decodeWithTree(in, typeArrayAt, count);
    }
}
