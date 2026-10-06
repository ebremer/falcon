package com.ebremer.falcon.core.compress.zlib;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib streams (RFC 1950), through {@code java.util.zip}: HDF5's {@code deflate} filter and numcodecs'
 * {@code zlib} codec. Decoding is bounded and strict, as zlib's {@code inflate} is when libhdf5 and
 * numcodecs call it: a stream that ends early, needs a preset dictionary, or fails its Adler-32 check is
 * refused, and bytes after the stream's end are ignored.
 */
public final class Zlib {

    private Zlib() {
    }

    /**
     * Compresses {@code data} into one zlib stream.
     *
     * @param data  the bytes
     * @param level the deflate level, 0 (stored) to 9 (smallest)
     * @return the stream
     */
    public static byte[] compress(byte[] data, int level) {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] out = new byte[Math.max(64, data.length / 2 + 64)];
            int n = 0;
            while (!deflater.finished()) {
                if (n == out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                n += deflater.deflate(out, n, out.length - n);
            }
            return n == out.length ? out : Arrays.copyOf(out, n);
        } finally {
            deflater.end();
        }
    }

    /**
     * Decompresses the zlib stream at {@code src[offset, offset + length)}, which must decode to at most
     * {@code maxSize} bytes. The output grows as it is decoded, so a stream claiming much cannot exhaust
     * memory before it delivers.
     *
     * @param src     the stream's bytes
     * @param offset  where it starts
     * @param length  how many bytes it may use
     * @param maxSize the most it may decode to
     * @return the decoded bytes
     * @throws CompressionFormatException if the stream is malformed, ends early, needs a preset dictionary,
     *                                    or decodes to more than {@code maxSize} bytes
     */
    public static byte[] decompress(byte[] src, int offset, int length, int maxSize) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(src, offset, length);
            byte[] out = new byte[(int) Math.min(maxSize, Math.max(64, length * 4L))];
            int n = 0;
            while (!inflater.finished()) {
                if (n == out.length) {
                    if (n >= maxSize) {
                        if (probe(inflater)) {
                            throw new CompressionFormatException(
                                    "zlib stream decodes to more than the " + maxSize + " bytes expected");
                        }
                        break;
                    }
                    out = Arrays.copyOf(out, (int) Math.min(maxSize, Math.max(64, out.length * 2L)));
                }
                int produced = inflater.inflate(out, n, out.length - n);
                n += produced;
                if (produced == 0 && !inflater.finished()) {
                    if (inflater.needsDictionary()) {
                        throw new CompressionFormatException("zlib stream needs a preset dictionary");
                    }
                    if (inflater.needsInput()) {
                        throw new CompressionFormatException("zlib stream ends early, after " + n + " bytes");
                    }
                }
            }
            return n == out.length ? out : Arrays.copyOf(out, n);
        } catch (DataFormatException e) {
            throw new CompressionFormatException("zlib stream is malformed: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    /** Whether a stream that has filled its bound would deliver another byte (rather than just end). */
    private static boolean probe(Inflater inflater) throws DataFormatException {
        byte[] one = new byte[1];
        while (!inflater.finished()) {
            if (inflater.inflate(one) > 0) {
                return true;
            }
            // needsInput() holds once the stream is finished too
            if (!inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                throw new CompressionFormatException("zlib stream ends early");
            }
        }
        return false;
    }
}
