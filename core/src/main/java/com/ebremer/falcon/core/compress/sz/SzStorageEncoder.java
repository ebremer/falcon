package com.ebremer.falcon.core.compress.sz;

/**
 * SZ 2.1.12's stream of floats or doubles before its lossless stage, as {@code convertTDPStoFlatBytes_float}
 * and {@code _double} write it ({@code TightDataPointStorageF.c}, {@code TightDataPointStorageD.c}); the
 * layout {@link SzStorage} reads. Three forms: the classic one (quantization codes, a Huffman tree, and the
 * unpredictable values), one value for data all within the bound of each other, and a stored copy.
 */
final class SzStorageEncoder {

    // The flag byte (sameRByte).
    private static final int SAME = 0x01;
    private static final int ACCELERATE_PWR = 0x08;
    private static final int LOSSLESS = 0x10;
    private static final int PW_REL = 0x20;
    private static final int SIZE_TYPE_8 = 0x40;

    private SzStorageEncoder() {
    }

    /** {@code MetaDataByteLength} or {@code MetaDataByteLength_double}. */
    static int metaLength(int width) {
        return width == 4 ? SzParams.META_FLOAT : SzParams.META_DOUBLE;
    }

    /** The flag byte of a classic or constant stream. */
    private static int flags(SzParams p, boolean same) {
        int flags = (same ? SAME : 0) | SIZE_TYPE_8;
        if (p.errorBoundMode >= SzParams.PW_REL) {
            flags |= PW_REL;
        }
        if (p.msst19()) {
            flags |= ACCELERATE_PWR;
        }
        return flags;
    }

    /**
     * {@code SZ_compress_args_float_withinRange} (and its double twin): the first value, big-endian, for
     * {@code count} values.
     */
    static byte[] constant(SzParams p, int width, long count, long firstValueBits) {
        SzBytes out = new SzBytes(64);
        p.header(out, flags(p, true), metaLength(width), count);
        if (width == 4) {
            out.be32((int) firstValueBits);
        } else {
            out.be64(firstValueBits);
        }
        return out.toArray();
    }

    /**
     * {@code SZ_compress_args_float_StoreOriData} (and its double twin): flags {@code 0x50}, then the values
     * big-endian. {@code values} holds them as raw bits.
     */
    static byte[] stored(SzParams p, int width, long[] values) {
        SzBytes out = new SzBytes(4 + metaLength(width) + 8 + width * values.length);
        p.header(out, LOSSLESS | SIZE_TYPE_8, metaLength(width), values.length);
        for (long v : values) {
            if (width == 4) {
                out.be32((int) v);
            } else {
                out.be64(v);
            }
        }
        return out.toArray();
    }

    /** The size a stored copy takes, the bound past which libSZ stores the data instead. */
    static long storedSize(int width, long count) {
        return 3 + metaLength(width) + SzParams.SIZE_TYPE + 1 + (long) width * count;
    }

    /** The classic form's fields, as a {@code TightDataPointStorageF}/{@code D} holds them. */
    static final class Classic {
        int width;
        long dataSeriesLength;
        int intervals;
        double medianValue;
        int reqLength;
        double realPrecision;
        /** The quantization codes, Huffman-coded by {@link #classic}. */
        int[] types;
        int plusBits;
        SzExactEncoder exact;
        byte[] signs = new byte[0];
        double minLogValue;
        int radExpo;
    }

    /**
     * {@code new_TightDataPointStorageF} / {@code D}'s Huffman step ({@code encode_withTree}, or
     * {@code encode_withTree_MSST19}, which also records the longest code), then
     * {@code convertTDPStoFlatBytes_float} / {@code _double} for the classic form.
     */
    static byte[] classic(SzParams p, Classic t) {
        int width = t.width;
        SzBytes types = new SzBytes(t.types.length + 64);
        SzHuffmanEncoder tree = SzHuffmanEncoder.encodeWithTree(2 * t.intervals, t.types, t.types.length, types);
        int maxBits = p.msst19() ? tree.maxBits() : 0;
        byte[] typeArray = types.toArray();
        byte[] lead = t.exact.leadNumBytes();
        byte[] resi = t.exact.residualBytes();
        SzBytes out = new SzBytes(typeArray.length + t.exact.midBytes.size() + lead.length + resi.length + 128);
        p.header(out, flags(p, false), metaLength(width), t.dataSeriesLength);
        out.be32(SzParams.MAX_QUANT_INTERVALS);
        boolean pwr = p.errorBoundMode >= SzParams.PW_REL;
        if (pwr) {
            out.u8(t.radExpo);
            out.be64(SzParams.SEGMENT_SIZE);
            out.be32(t.signs.length);
        }
        out.be32(t.intervals);
        if (width == 4) {
            out.beFloat((float) t.medianValue);
        } else {
            out.beDouble(t.medianValue);
        }
        out.u8(t.reqLength);
        if (p.msst19()) {
            out.u8(t.plusBits);
            out.u8(maxBits);
        }
        out.beDouble(t.realPrecision);
        out.be64(typeArray.length);
        out.be64(t.exact.count());
        out.be64(t.exact.midBytes.size());
        if (pwr) {
            if (width == 4) {
                out.beFloat((float) t.minLogValue);
            } else {
                out.beDouble(t.minLogValue);
            }
        }
        out.bytes(typeArray);
        if (pwr) {
            out.bytes(t.signs);
        }
        out.bytes(lead);
        byte[] mid = t.exact.midBytes.toArray();
        out.bytes(mid);
        out.bytes(resi);
        return out.toArray();
    }
}
