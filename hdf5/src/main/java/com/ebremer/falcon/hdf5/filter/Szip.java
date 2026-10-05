package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.hdf5.HdfFormatException;
import java.util.Arrays;

/**
 * The szip filter (filter id 4) in the exact form libhdf5 stores it: {@code H5Zszip.c} on top of
 * libaec's SZ compatibility layer ({@code sz_compat.c}), around the CCSDS 121.0 coder in {@link Aec}.
 *
 * <ul>
 *   <li><b>Chunk</b>: a 4-byte little-endian uncompressed size, then the AEC bitstream.</li>
 *   <li><b>Samples</b>: 32- and 64-bit pixels are byte-<em>interleaved</em> (all first bytes, then all
 *       second bytes, ...) and coded as 8-bit samples; narrower pixels are coded directly as 1-, 2- or
 *       4-byte samples in the byte order the option mask names (MSB or LSB).</li>
 *   <li><b>Scanlines</b>: each scanline of {@code pixelsPerScanline} samples is padded to a whole number
 *       of blocks (the last pixel repeated under nearest-neighbour preprocessing, zeros otherwise), and
 *       one reference-sample interval spans one padded scanline.</li>
 * </ul>
 *
 * <p>Client data: {@code options mask, pixels per block, bits per pixel, pixels per scanline}. The SZ
 * layer has no signed-data option, so samples are always coded unsigned.
 */
public final class Szip {

    /** szip option-mask bits (szlib.h). */
    public static final int ALLOW_K13 = 1;
    public static final int EC = 4;
    public static final int LSB = 8;
    public static final int MSB = 16;
    public static final int NN = 32;
    public static final int RAW = 128;

    /** libhdf5's limits (H5Zszip.c / szlib.h). */
    static final int MAX_PIXELS_PER_BLOCK = 32;
    static final int MAX_BLOCKS_PER_SCANLINE = 128;
    static final int MAX_PIXELS_PER_SCANLINE = MAX_PIXELS_PER_BLOCK * MAX_BLOCKS_PER_SCANLINE;

    private Szip() {
    }

    /**
     * Decodes one szip chunk.
     *
     * @param maxBytes an upper bound on the decoded size, to reject a corrupt size header
     */
    public static byte[] decode(byte[] chunk, int[] cd, long maxBytes) {
        Params p = Params.of(cd);
        if (chunk.length < 4) {
            throw new HdfFormatException("szip chunk is shorter than its 4-byte size header");
        }
        long nalloc = ScaleOffset.readLittleEndian(chunk, 0, 4);
        if (nalloc > maxBytes || nalloc > Integer.MAX_VALUE - 8) {
            throw new HdfFormatException("szip chunk claims " + nalloc + " bytes, more than the chunk holds");
        }
        int n = (int) nalloc;
        long samples;
        if (p.padScanline || p.interleave) { // decoded into whole padded scanlines
            long scanlines = ((long) n / p.pixelSize + p.pixelsPerScanline - 1) / p.pixelsPerScanline;
            samples = scanlines * p.rsi * p.pixelsPerBlock;
        } else {
            samples = ((long) n + p.pixelSize - 1) / p.pixelSize;
        }
        if (samples * p.pixelSize > Integer.MAX_VALUE - 8) {
            throw new HdfFormatException("szip chunk too large");
        }
        int flags = (p.mask & NN) != 0 ? Aec.FLAG_PREPROCESS : 0;
        long[] values = Aec.decode(chunk, 4, (int) samples, p.bitsPerSample, p.pixelsPerBlock, p.rsi, flags);

        byte[] buf = new byte[(int) samples * p.pixelSize];
        boolean msb = (p.mask & MSB) != 0;
        for (int i = 0; i < values.length; i++) {
            putSample(buf, i * p.pixelSize, p.pixelSize, values[i], msb);
        }
        if (p.padScanline) {
            buf = removePadding(buf, p.pixelsPerScanline * p.pixelSize, p.rsi * p.pixelsPerBlock * p.pixelSize);
        }
        if (buf.length < n) {
            throw new HdfFormatException("szip chunk decoded to " + buf.length + " bytes, expected " + n);
        }
        if (p.interleave) {
            return deinterleave(buf, n, p.bitsPerPixel / 8);
        }
        return buf.length == n ? buf : Arrays.copyOf(buf, n);
    }

    /**
     * Encodes one chunk as libhdf5 + libaec would read it, or returns {@code null} if the coded form is
     * larger than the input (libhdf5 then stores the chunk unfiltered and sets its filter-mask bit).
     * Falcon's coder uses entropy coding without nearest-neighbour preprocessing, so {@code cd} must
     * select {@link #EC} coding.
     */
    public static byte[] encode(byte[] chunk, int[] cd) {
        Params p = Params.of(cd);
        if ((p.mask & NN) != 0) {
            throw new IllegalArgumentException("Falcon's szip encoder writes entropy coding (EC) only");
        }
        byte[] source = p.interleave ? interleave(chunk, p.bitsPerPixel / 8) : chunk;
        int lineBytes = p.pixelsPerScanline * p.pixelSize;
        int paddedLineBytes = p.rsi * p.pixelsPerBlock * p.pixelSize;
        byte[] padded = addPadding(source, lineBytes, paddedLineBytes);
        boolean msb = (p.mask & MSB) != 0;
        long[] samples = new long[padded.length / p.pixelSize];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = getSample(padded, i * p.pixelSize, p.pixelSize, msb);
        }
        byte[] coded = Aec.encode(samples, p.bitsPerSample, p.pixelsPerBlock);
        if (coded.length > chunk.length) {
            return null;
        }
        byte[] out = new byte[4 + coded.length];
        ScaleOffset.writeLittleEndian(out, 0, 4, chunk.length);
        System.arraycopy(coded, 0, out, 4, coded.length);
        return out;
    }

    /**
     * The client data libhdf5's {@code H5Pset_szip} + {@code H5Z__set_local_szip} store for a dataset:
     * the mask gains K13, RAW and the byte order; bits per pixel is the precision rounded up to 32 or
     * 64 above 24; the scanline is the chunk's fastest dimension, clamped to the szip limits.
     */
    public static int[] clientData(int optionMask, int pixelsPerBlock, int precisionBits, boolean bigEndian,
                                   long[] chunkDims) {
        int mask = (optionMask & ~(LSB | MSB | 2)) | ALLOW_K13 | RAW | (bigEndian ? MSB : LSB);
        int bpp = precisionBits;
        if (bpp > 24) {
            bpp = bpp <= 32 ? 32 : 64;
        }
        long scanline = chunkDims[chunkDims.length - 1];
        long points = 1;
        for (long d : chunkDims) {
            points *= d;
        }
        if (scanline < pixelsPerBlock) {
            if (points < pixelsPerBlock) {
                throw new IllegalArgumentException("szip pixels per block (" + pixelsPerBlock
                        + ") exceeds the elements in a chunk (" + points + ")");
            }
            scanline = Math.min((long) pixelsPerBlock * MAX_BLOCKS_PER_SCANLINE, points);
        } else if (scanline <= MAX_PIXELS_PER_SCANLINE) {
            scanline = Math.min((long) pixelsPerBlock * MAX_BLOCKS_PER_SCANLINE, scanline);
        } else {
            scanline = (long) pixelsPerBlock * MAX_BLOCKS_PER_SCANLINE;
        }
        return new int[] {mask, pixelsPerBlock, bpp, (int) scanline};
    }

    /** Validated szip parameters and the SZ layer's derived sample geometry. */
    private record Params(int mask, int pixelsPerBlock, int bitsPerPixel, int pixelsPerScanline,
                          boolean interleave, int bitsPerSample, int pixelSize, int rsi, boolean padScanline) {

        static Params of(int[] cd) {
            if (cd.length < 4) {
                throw new HdfFormatException("szip filter requires 4 client-data values, got " + cd.length);
            }
            int mask = cd[0];
            int ppb = cd[1];
            int bpp = cd[2];
            int ppsl = cd[3];
            if (ppb < 2 || ppb > 64 || (ppb & 1) != 0) {
                throw new HdfFormatException("invalid szip pixels per block " + ppb);
            }
            if (bpp < 1 || (bpp > 32 && bpp != 64)) {
                throw new HdfFormatException("invalid szip bits per pixel " + bpp);
            }
            if (ppsl < 1 || ppsl > ppb * MAX_BLOCKS_PER_SCANLINE * 64) {
                throw new HdfFormatException("invalid szip pixels per scanline " + ppsl);
            }
            boolean interleave = bpp == 32 || bpp == 64;
            int bitsPerSample = interleave ? 8 : bpp;
            int pixelSize = bitsPerSample > 16 ? 4 : bitsPerSample > 8 ? 2 : 1;
            int rsi = (ppsl + ppb - 1) / ppb;
            return new Params(mask, ppb, bpp, ppsl, interleave, bitsPerSample, pixelSize, rsi, ppsl % ppb != 0);
        }
    }

    /** Pads each {@code lineBytes} scanline (the last one too) with zeros to {@code paddedLineBytes}. */
    private static byte[] addPadding(byte[] source, int lineBytes, int paddedLineBytes) {
        int lines = (source.length + lineBytes - 1) / lineBytes;
        byte[] out = new byte[lines * paddedLineBytes];
        for (int line = 0; line < lines; line++) {
            int from = line * lineBytes;
            System.arraycopy(source, from, out, line * paddedLineBytes, Math.min(lineBytes, source.length - from));
        }
        return out;
    }

    /** Keeps the first {@code lineBytes} of every {@code paddedLineBytes} line. */
    private static byte[] removePadding(byte[] buf, int lineBytes, int paddedLineBytes) {
        int lines = buf.length / paddedLineBytes;
        byte[] out = new byte[lines * lineBytes];
        for (int line = 0; line < lines; line++) {
            System.arraycopy(buf, line * paddedLineBytes, out, line * lineBytes, lineBytes);
        }
        return out;
    }

    /** libaec {@code interleave_buffer}: byte {@code j} of word {@code i} goes to {@code j*(n/ws) + i}. */
    private static byte[] interleave(byte[] src, int wordSize) {
        int words = src.length / wordSize;
        byte[] out = src.clone();
        for (int i = 0; i < words; i++) {
            for (int j = 0; j < wordSize; j++) {
                out[j * words + i] = src[i * wordSize + j];
            }
        }
        return out;
    }

    /** libaec {@code deinterleave_buffer}, producing {@code n} bytes. */
    private static byte[] deinterleave(byte[] src, int n, int wordSize) {
        int words = n / wordSize;
        byte[] out = Arrays.copyOf(src, n);
        for (int i = 0; i < words; i++) {
            for (int j = 0; j < wordSize; j++) {
                out[i * wordSize + j] = src[j * words + i];
            }
        }
        return out;
    }

    private static void putSample(byte[] buf, int offset, int size, long value, boolean msb) {
        for (int b = 0; b < size; b++) {
            buf[offset + (msb ? size - 1 - b : b)] = (byte) (value >>> (8 * b));
        }
    }

    private static long getSample(byte[] buf, int offset, int size, boolean msb) {
        long v = 0;
        for (int b = 0; b < size; b++) {
            v |= (long) (buf[offset + (msb ? size - 1 - b : b)] & 0xff) << (8 * b);
        }
        return v;
    }
}
