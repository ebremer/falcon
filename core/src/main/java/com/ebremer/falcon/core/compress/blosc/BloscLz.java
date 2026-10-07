package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * BloscLZ &mdash; c-blosc's built-in LZ codec (a FastLZ variant) and its default compressor. Translated
 * directly from {@code blosclz_decompress} and {@code blosclz_compress} in c-blosc 1.21.6's
 * {@code blosclz.c}, so a stream is the one c-blosc writes for the same block and clevel; and from c-blosc2
 * 3.3.2's {@code blosclz_compress} ({@link #compress2}), which probes more of the block and always matches
 * with a shift and minimum length of 4.
 *
 * <p>The stream is a sequence of opcodes. A control byte below 32 introduces a literal run of
 * {@code ctrl + 1} bytes; a control byte of 32 or more introduces a match: its top three bits carry the
 * length (extended by trailing bytes when 7) and its low five bits the high part of the back-reference
 * distance, with the low part in the following byte and a 16-bit "far" distance signaled by an all-ones
 * low byte.
 */
final class BloscLz {

    private static final int MAX_DISTANCE = 8191;
    private static final int MAX_FARDISTANCE = 65535 + MAX_DISTANCE - 1;
    private static final int MAX_COPY = 32;
    private static final int HASH_LOG = 14;
    private static final int HASH_LOG2 = 12;
    /** Per clevel: the entropy probe's smallest ratio worth compressing, and the hash table's size. */
    private static final double[] CRATIO = {0, 2, 1.5, 1.2, 1.2, 1.2, 1.2, 1.15, 1.1, 1.0};
    private static final int[] HASHLOG = {0, HASH_LOG - 2, HASH_LOG - 1, HASH_LOG, HASH_LOG, HASH_LOG, HASH_LOG,
        HASH_LOG, HASH_LOG, HASH_LOG};

    private BloscLz() {
    }

    /**
     * Compresses one block into {@code dst}, as {@code blosclz_compress}. Like c-blosc, it first probes the
     * last quarter of the block and gives up (returns 0, so Blosc stores the block raw) when the estimated
     * ratio is below what {@code clevel} asks for; it also gives up on a block under 16 bytes, room under 66
     * bytes, or output that would pass {@code maxOut}.
     *
     * @param clevel     1 to 9
     * @param splitBlock whether the Blosc buffer splits its blocks into per-byte streams (the header's
     *                   split flag, not whether this stream is one of them)
     * @return the stream's length, or 0
     */
    static int compress(int clevel, byte[] src, int off, int length, byte[] dst, int dstOff, int maxOut,
                        boolean splitBlock) {
        int maxlen = length / 4; // checking the last quarter is enough to estimate the ratio
        double cratio = estimateRatio(src, off + length - maxlen, maxlen, 3, 3, HASH_LOG2);
        if (cratio < CRATIO[clevel]) {
            return 0;
        }
        // A shift of 4 suits split blocks (bit-shuffled small types especially); low-entropy and unsplit data
        // do better with 3.
        int ipshift = 4;
        int minlen = 4;
        if (!splitBlock || cratio < 4) {
            ipshift = 3;
            minlen = 3;
        }
        return encode(clevel, src, off, length, dst, dstOff, maxOut, ipshift, minlen);
    }

    /**
     * Compresses one block into {@code dst} as c-blosc2 3.3.2's {@code blosclz_compress} does: the entropy
     * probe reads the block's last eighth at clevel 1, quarter at 2 and 3, half at 4 to 6, and all of it from
     * 7, with the clevel's hash table and the shift and minimum length of 4 the compressor then always uses.
     * It gives up (returns 0) as {@link #compress} does.
     *
     * @param clevel 1 to 9
     * @return the stream's length, or 0
     */
    static int compress2(int clevel, byte[] src, int off, int length, byte[] dst, int dstOff, int maxOut) {
        int maxlen = length;
        if (clevel < 2) {
            maxlen /= 8;
        } else if (clevel < 4) {
            maxlen /= 4;
        } else if (clevel < 7) {
            maxlen /= 2;
        }
        double cratio = estimateRatio(src, off + length - maxlen, maxlen, 4, 4, HASHLOG[clevel]);
        if (cratio < CRATIO[clevel]) {
            return 0;
        }
        return encode(clevel, src, off, length, dst, dstOff, maxOut, 4, 4);
    }

    /** The compressor proper, after the probe: {@code blosclz_compress}'s main loop. */
    private static int encode(int clevel, byte[] src, int off, int length, byte[] dst, int dstOff, int maxOut,
                              int ipshift, int minlen) {
        int hashlog = HASHLOG[clevel];
        if (length < 16 || maxOut < 66) {
            return 0;
        }
        int ip = off;
        int ipBound = off + length - 1;
        int ipLimit = off + length - 12;
        int op = dstOff;
        int opLimit = dstOff + maxOut;
        int[] htab = new int[1 << hashlog];

        int copy = 4; // start with a literal run of the first four bytes
        dst[op++] = MAX_COPY - 1;
        for (int i = 0; i < 4; i++) {
            dst[op++] = src[ip++];
        }
        while (ip < ipLimit) {
            int anchor = ip;
            int hval = hash(read32(src, ip), hashlog);
            int ref = off + htab[hval];
            int distance = anchor - ref;
            htab[hval] = anchor - off;
            boolean literal = distance == 0 || distance >= MAX_FARDISTANCE || read32(src, ref) != read32(src, ip);
            int len = 0;
            if (!literal) {
                distance--; // biased: 0 means a run of one byte
                ip = matchEnd(src, anchor + 4, ipBound, ref + 4) - ipshift;
                len = ip - anchor; // biased: 1 means a match of 3 bytes
                literal = len < minlen || (len <= 5 && distance >= MAX_DISTANCE); // short ones decode slowly
            }
            if (literal) {
                if (op + 2 > opLimit) {
                    return 0;
                }
                dst[op++] = src[anchor];
                ip = anchor + 1;
                if (++copy == MAX_COPY) {
                    copy = 0;
                    dst[op++] = MAX_COPY - 1;
                }
                continue;
            }
            if (copy != 0) {
                dst[op - copy - 1] = (byte) (copy - 1); // the literal run's length, now known
            } else {
                op--; // no literals: drop the run's control byte
            }
            copy = 0;
            int far = distance < MAX_DISTANCE ? 0 : 1;
            if (far == 1) {
                distance -= MAX_DISTANCE;
            }
            if (len < 7) {
                if (op + 2 + 2 * far > opLimit) {
                    return 0;
                }
                if (far == 0) {
                    dst[op++] = (byte) ((len << 5) + (distance >>> 8));
                    dst[op++] = (byte) distance;
                } else {
                    dst[op++] = (byte) ((len << 5) + 31);
                    dst[op++] = (byte) 255;
                    dst[op++] = (byte) (distance >>> 8);
                    dst[op++] = (byte) distance;
                }
            } else {
                if (op + 1 > opLimit) {
                    return 0;
                }
                dst[op++] = (byte) ((7 << 5) + (far == 0 ? distance >>> 8 : 31));
                for (len -= 7; len >= 255; len -= 255) {
                    if (op + 1 > opLimit) {
                        return 0;
                    }
                    dst[op++] = (byte) 255;
                }
                if (op + 2 + 2 * far > opLimit) {
                    return 0;
                }
                dst[op++] = (byte) len;
                if (far == 0) {
                    dst[op++] = (byte) distance;
                } else {
                    dst[op++] = (byte) 255;
                    dst[op++] = (byte) (distance >>> 8);
                    dst[op++] = (byte) distance;
                }
            }
            // update the hash at the match boundary (twice at clevel 9)
            int seq = read32(src, ip);
            htab[hash(seq, hashlog)] = ip++ - off;
            if (clevel == 9) {
                htab[hash(seq >>> 8, hashlog)] = ip++ - off;
            } else {
                ip++;
            }
            if (op + 1 > opLimit) {
                return 0;
            }
            dst[op++] = MAX_COPY - 1; // assume a literal run follows
        }
        while (ip <= ipBound) { // the rest as literals
            if (op + 2 > opLimit) {
                return 0;
            }
            dst[op++] = src[ip++];
            if (++copy == MAX_COPY) {
                copy = 0;
                dst[op++] = MAX_COPY - 1;
            }
        }
        if (copy != 0) {
            dst[op - copy - 1] = (byte) (copy - 1);
        } else {
            op--;
        }
        dst[dstOff] |= 1 << 5; // the BloscLZ marker; the decoder masks the first opcode
        return op - dstOff;
    }

    /**
     * {@code get_cratio}: a quick estimate of the ratio the compressor will reach on up to
     * 2<sup>hashlog</sup> bytes from {@code base}, from a dry run with that hash (c-blosc's is 12 bits, its
     * shift and minimum length 3).
     */
    private static double estimateRatio(byte[] src, int base, int maxlen, int minlen, int ipshift, int hashlog) {
        int ip = base;
        int oc = 0;
        int[] htab = new int[1 << hashlog];
        int limit = Math.min(maxlen, 1 << hashlog);
        int ipBound = base + limit - 1;
        int ipLimit = base + limit - 12;
        int copy = 4; // starts with a literal run
        oc += 5;
        while (ip < ipLimit) {
            int anchor = ip;
            int hval = hash(read32(src, ip), hashlog);
            int ref = base + htab[hval];
            int distance = anchor - ref;
            htab[hval] = anchor - base;
            boolean literal = distance == 0 || distance >= MAX_FARDISTANCE || read32(src, ref) != read32(src, ip);
            int len = 0;
            if (!literal) {
                distance--;
                ip = matchEnd(src, anchor + 4, ipBound, ref + 4) - ipshift;
                len = ip - anchor;
                literal = len < minlen;
            }
            if (literal) {
                oc++;
                ip = anchor + 1;
                if (++copy == MAX_COPY) {
                    copy = 0;
                    oc++;
                }
                continue;
            }
            if (copy == 0) {
                oc--;
            }
            copy = 0;
            if (len >= 7) {
                oc += (len - 7) / 255 + 1;
            }
            oc += distance < MAX_DISTANCE ? 2 : 4;
            htab[hash(read32(src, ip), hashlog)] = ip - base;
            ip += 2;
            oc++; // assume a literal run follows
        }
        return (double) (ip - base) / (double) oc;
    }

    /**
     * Where a match from {@code ip} against {@code ref} ends, as c-blosc's {@code get_match} and
     * {@code get_run} find it: one past the first byte that differs, or {@code bound}.
     */
    private static int matchEnd(byte[] src, int ip, int bound, int ref) {
        while (ip < bound) {
            if (src[ref++] != src[ip++]) {
                return ip;
            }
        }
        return bound;
    }

    private static int hash(int sequence, int log) {
        return (sequence * -1640531535) >>> (32 - log); // 2654435761U
    }

    private static int read32(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
    }

    /** Decompresses one block, which must produce exactly {@code outLength} bytes. */
    static void decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int outLength) {
        int ip = srcOff;
        int ipLimit = srcOff + srcLen;
        int op = dstOff;
        int opLimit = dstOff + outLength;
        if (srcLen == 0) {
            throw new CompressionFormatException("empty BloscLZ block");
        }
        int ctrl = (src[ip++] & 0xff) & 31; // the first opcode is always a literal run

        while (true) {
            if (ctrl >= 32) {
                int len = (ctrl >>> 5) - 1;
                int ofs = (ctrl & 31) << 8;
                int ref = op - ofs;
                int code;

                if (len == 7 - 1) {
                    do {
                        if (ip + 1 >= ipLimit) {
                            throw truncated();
                        }
                        code = src[ip++] & 0xff;
                        len += code;
                        if (len > opLimit) { // cannot fit, and must not overflow
                            throw new CompressionFormatException("BloscLZ match overruns the block");
                        }
                    } while (code == 255);
                } else if (ip + 1 >= ipLimit) {
                    throw truncated();
                }
                code = src[ip++] & 0xff;
                len += 3;
                ref -= code;

                if (code == 255 && ofs == (31 << 8)) { // 16-bit "far" distance
                    if (ip + 1 >= ipLimit) {
                        throw truncated();
                    }
                    ofs = (src[ip++] & 0xff) << 8;
                    ofs += src[ip++] & 0xff;
                    ref = op - ofs - MAX_DISTANCE;
                }

                if (len > opLimit - op) {
                    throw new CompressionFormatException("BloscLZ match overruns the block");
                }
                if (ref - 1 < dstOff) {
                    throw new CompressionFormatException("BloscLZ match reaches before the block");
                }
                if (ip >= ipLimit) {
                    break;
                }
                ctrl = src[ip++] & 0xff;
                ref--;
                if (op - ref >= len) {
                    System.arraycopy(dst, ref, dst, op, len);
                } else {
                    // Overlapping matches are legal and must be copied byte by byte.
                    for (int i = 0; i < len; i++) {
                        dst[op + i] = dst[ref + i];
                    }
                }
                op += len;
            } else {
                int literals = ctrl + 1;
                if (literals > opLimit - op) {
                    throw new CompressionFormatException("BloscLZ literal run overruns the block");
                }
                if (literals > ipLimit - ip) {
                    throw truncated();
                }
                System.arraycopy(src, ip, dst, op, literals);
                op += literals;
                ip += literals;
                if (ip >= ipLimit) {
                    break;
                }
                ctrl = src[ip++] & 0xff;
            }
        }

        if (op != opLimit) {
            throw new CompressionFormatException(
                    "BloscLZ block produced " + (op - dstOff) + " bytes, expected " + outLength);
        }
    }

    private static CompressionFormatException truncated() {
        return new CompressionFormatException("BloscLZ block is truncated");
    }
}
