package com.ebremer.falcon.core.compress.lzf;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;

/**
 * LZF decompression (Marc Lehmann's liblzf, {@code lzf_d.c}), the compressor of h5py's built-in
 * {@code lzf} HDF5 filter. The stream has no header: it is a run of instructions, each starting with a
 * control byte {@code c}:
 * <ul>
 *   <li>{@code c < 32}: a literal run of {@code c + 1} bytes, which follow;</li>
 *   <li>otherwise a back reference: length {@code c >> 5} (7 means a further byte is added), plus 2; the
 *       distance back from the output position is {@code ((c & 0x1f) << 8) + next byte + 1}. A reference
 *       may overlap the bytes it produces.</li>
 * </ul>
 */
public final class Lzf {

    /** liblzf's longest back reference produces 264 bytes from 3, so no stream expands more than 88-fold. */
    private static final long MAX_EXPANSION = 88;

    private Lzf() {
    }

    /**
     * Decompresses {@code length} bytes of {@code src} from {@code offset}.
     *
     * @param expectedSize the decompressed size if known (a starting capacity), else 0
     * @throws CompressionFormatException if the stream is malformed
     */
    public static byte[] decompress(byte[] src, int offset, int length, int expectedSize) {
        return decompress(src, offset, length, expectedSize, Integer.MAX_VALUE - 8);
    }

    /**
     * Decompresses as {@link #decompress(byte[], int, int, int)}, failing if the stream decodes to more
     * than {@code maxSize} bytes.
     */
    public static byte[] decompress(byte[] src, int offset, int length, int expectedSize, int maxSize) {
        if (offset < 0 || length < 0 || length > src.length - offset) {
            throw new IllegalArgumentException("invalid range " + offset + "+" + length + " of " + src.length);
        }
        long limit = Math.min(Math.min(Integer.MAX_VALUE - 8, maxSize), MAX_EXPANSION * length + 32);
        byte[] out = new byte[(int) Math.min(limit, expectedSize > 0 ? expectedSize : 2L * length + 32)];
        int ip = offset;
        int end = offset + length;
        int op = 0;
        while (ip < end) {
            int ctrl = src[ip++] & 0xff;
            if (ctrl < 32) { // literal run
                int run = ctrl + 1;
                if (ip + run > end) {
                    throw new CompressionFormatException("LZF literal run of " + run + " bytes overruns the input");
                }
                out = ensure(out, op + run, limit);
                System.arraycopy(src, ip, out, op, run);
                ip += run;
                op += run;
            } else { // back reference
                int run = ctrl >>> 5;
                if (ip >= end) {
                    throw new CompressionFormatException("LZF back reference is truncated");
                }
                if (run == 7) {
                    run += src[ip++] & 0xff;
                    if (ip >= end) {
                        throw new CompressionFormatException("LZF back reference is truncated");
                    }
                }
                int from = op - ((ctrl & 0x1f) << 8) - 1 - (src[ip++] & 0xff);
                if (from < 0) {
                    throw new CompressionFormatException("LZF back reference reaches before the output");
                }
                run += 2;
                out = ensure(out, op + run, limit);
                for (int i = 0; i < run; i++) { // byte by byte: the reference may overlap its output
                    out[op + i] = out[from + i];
                }
                op += run;
            }
        }
        return op == out.length ? out : Arrays.copyOf(out, op);
    }

    private static byte[] ensure(byte[] out, long needed, long limit) {
        if (needed <= out.length) {
            return out;
        }
        if (needed > limit) {
            throw new CompressionFormatException("LZF stream decodes to more than " + limit + " bytes");
        }
        return Arrays.copyOf(out, (int) Math.min(limit, Math.max(needed, 2L * out.length)));
    }
}
