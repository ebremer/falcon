package com.ebremer.falcon.core.compress.lzf;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.util.Arrays;

/**
 * LZF (Marc Lehmann's liblzf), the compressor of h5py's built-in {@code lzf} HDF5 filter: decompression
 * ({@code lzf_d.c}) and compression ({@code lzf_c.c}, as h5py builds it). The stream has no header: it is
 * a run of instructions, each starting with a control byte {@code c}:
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

    // h5py's lzf/lzfP.h: HLOG 17 and ULTRA_FAST (which overrides VERY_FAST); lzf_c.c's limits.
    private static final int HLOG = 17;
    private static final int HSIZE = 1 << HLOG;
    private static final int MAX_LIT = 1 << 5;
    private static final int MAX_OFF = 1 << 13;
    private static final int MAX_REF = (1 << 8) + (1 << 3);

    private Lzf() {
    }

    /**
     * Compresses {@code length} bytes of {@code src} from {@code offset} as h5py's liblzf
     * {@code lzf_compress} does (HLOG 17, {@code ULTRA_FAST}), into at most {@code maxOut} bytes. The hash
     * table starts empty. (h5py's build leaves it uninitialised, {@code INIT_HTAB 0}: a stale entry that
     * happens to point into the input's last 8 KiB may add a match there, so liblzf's output can differ, as
     * valid, from run to run.)
     *
     * @param src    the bytes to compress
     * @param offset where they start
     * @param length how many there are
     * @param maxOut the most bytes the stream may take (h5py's filter allows the input's length)
     * @return the LZF stream, or null if it would not fit in {@code maxOut} bytes or the input is empty
     *         (where {@code lzf_compress} returns 0)
     */
    public static byte[] compress(byte[] src, int offset, int length, int maxOut) {
        if (offset < 0 || length < 0 || length > src.length - offset || maxOut < 0) {
            throw new IllegalArgumentException("invalid range " + offset + "+" + length + " of " + src.length);
        }
        if (length == 0 || maxOut == 0) {
            return null;
        }
        int[] htab = new int[HSIZE]; // each slot: a position plus 1, or 0 for none
        byte[] out = new byte[maxOut + 1]; // a run may be opened one byte past the end, never written there
        int ip = offset;
        int inEnd = offset + length;
        int op = 1; // out[0] is the first literal run's control byte
        int lit = 0;
        int hval = length >= 2 ? first(src, ip) : 0;
        while (ip < inEnd - 2) {
            hval = (hval << 8) | (src[ip + 2] & 0xff);
            int slot = index(hval);
            int ref = htab[slot] - 1;
            htab[slot] = ip + 1;
            int off = ip - ref - 1;
            if (ref >= 0 && off < MAX_OFF && ip + 4 < inEnd && ref > offset
                    && src[ref] == src[ip] && src[ref + 1] == src[ip + 1] && src[ref + 2] == src[ip + 2]) {
                if (op + 3 + 1 >= maxOut && op - (lit == 0 ? 1 : 0) + 3 + 1 >= maxOut) {
                    return null;
                }
                out[op - lit - 1] = (byte) (lit - 1); // stop the run
                op -= lit == 0 ? 1 : 0;               // or drop it, if it is empty
                int len = matchLength(src, ref, ip, Math.min(inEnd - ip - 2, MAX_REF));
                len -= 2; // the length less 1, less the 1 the control byte's count implies
                ip++;
                if (len < 7) {
                    out[op++] = (byte) ((off >> 8) + (len << 5));
                } else {
                    out[op++] = (byte) ((off >> 8) + (7 << 5));
                    out[op++] = (byte) (len - 7);
                }
                out[op++] = (byte) off;
                lit = 0;
                op++; // start a run
                ip += len + 1;
                if (ip >= inEnd - 2) {
                    break;
                }
                // ULTRA_FAST hashes only the position before the match's end.
                --ip;
                hval = first(src, ip);
                hval = (hval << 8) | (src[ip + 2] & 0xff);
                htab[index(hval)] = ip + 1;
                ip++;
            } else {
                if (op >= maxOut) {
                    return null;
                }
                lit++;
                out[op++] = src[ip++];
                if (lit == MAX_LIT) {
                    out[op - lit - 1] = (byte) (lit - 1); // stop the run
                    lit = 0;
                    op++; // start a run
                }
            }
        }
        if (op + 3 > maxOut) { // at most 3 bytes can be missing here
            return null;
        }
        while (ip < inEnd) {
            lit++;
            out[op++] = src[ip++];
            if (lit == MAX_LIT) {
                out[op - lit - 1] = (byte) (lit - 1);
                lit = 0;
                op++;
            }
        }
        out[op - lit - 1] = (byte) (lit - 1); // end the run
        op -= lit == 0 ? 1 : 0;
        return Arrays.copyOf(out, op);
    }

    /**
     * Where a match of at least 3 bytes ends, as {@code lzf_compress} measures it: 16 bytes unchecked against
     * {@code maxlen} while more than 16 may follow (so, as liblzf's, it may pass {@code maxlen} by up to 2
     * near the input's end, still inside the input), then byte by byte up to {@code maxlen}.
     */
    private static int matchLength(byte[] src, int ref, int ip, int maxlen) {
        int len = 2;
        if (maxlen > 16) {
            for (int i = 0; i < 16; i++) {
                len++;
                if (src[ref + len] != src[ip + len]) {
                    return len;
                }
            }
        }
        do {
            len++;
        } while (len < maxlen && src[ref + len] == src[ip + len]);
        return len;
    }

    /** {@code FRST}: the hash of a position's first two bytes. */
    private static int first(byte[] src, int p) {
        return ((src[p] & 0xff) << 8) | (src[p + 1] & 0xff);
    }

    /** {@code IDX} under {@code ULTRA_FAST}: {@code ((h >> (3*8 - HLOG)) - h) & (HSIZE - 1)}, unsigned. */
    private static int index(int h) {
        return ((h >>> (3 * 8 - HLOG)) - h) & (HSIZE - 1);
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
