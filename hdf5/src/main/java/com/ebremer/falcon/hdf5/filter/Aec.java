package com.ebremer.falcon.hdf5.filter;

/**
 * A pure-Java decoder for the <strong>CCSDS 121.0</strong> adaptive entropy (extended-Rice) coding
 * implemented by <a href="https://gitlab.dkrz.de/k202009/libaec">libaec</a> — the algorithm behind
 * HDF5's {@code szip} filter. Written from the format description (no native code), and validated
 * byte-for-byte against libaec across all coding modes.
 *
 * <p>Produces the decoded sample values. Modes handled: sample-splitting (fundamental sequence with
 * {@code k} split bits), uncompressed, zero-block (with remainder-of-segment), and second extension,
 * with optional nearest-neighbour (unit-delay) preprocessing. Bits are read most-significant-first.
 *
 * <p>Samples are treated as <b>unsigned</b>. Signed data (libaec's {@code DATA_SIGNED}, used by HDF5
 * for signed integer types under nearest-neighbour preprocessing) needs the signed mapper bounds and
 * is a planned extension.
 */
public final class Aec {

    /** libaec flag: samples are stored most-significant-byte first (affects byte packing, not decoding). */
    public static final int FLAG_MSB = 4;
    /** libaec flag: nearest-neighbour (unit-delay) preprocessing was applied. */
    public static final int FLAG_PREPROCESS = 8;

    private Aec() {
    }

    /**
     * Decodes {@code sampleCount} samples of {@code bitsPerSample}-bit data from an AEC bitstream.
     *
     * @param blockSize samples per block (libaec {@code block_size})
     * @param rsi       blocks per reference-sample interval
     * @param flags     libaec flags ({@link #FLAG_PREPROCESS} is the only one that affects decoding)
     */
    public static long[] decode(byte[] data, int sampleCount, int bitsPerSample, int blockSize, int rsi, int flags) {
        boolean preprocess = (flags & FLAG_PREPROCESS) != 0;
        BitReader in = new BitReader(data);
        int idLen = idLen(bitsPerSample);
        int idMax = (1 << idLen) - 1;
        long xmax = bitsPerSample >= 64 ? -1L : (1L << bitsPerSample) - 1;
        long[] out = new long[sampleCount];
        int pos = 0;
        int rsiSamples = rsi * blockSize;

        while (pos < sampleCount) {
            int produced = 0;
            while (produced < rsiSamples && pos < sampleCount) {
                int id = in.read(idLen);
                int sub = id == 0 ? in.read(1) : -1;

                int ref = 0;
                if (preprocess && produced == 0) { // reference sample follows the mode selection
                    out[pos++] = in.readLong(bitsPerSample);
                    produced++;
                    ref = 1;
                }
                int samples = blockSize - ref;

                if (id == idMax) { // uncompressed
                    for (int i = 0; i < samples; i++) {
                        long v = in.readLong(bitsPerSample);
                        if (pos < sampleCount) {
                            out[pos] = preprocess ? unmap(v, out[pos - 1], xmax) : v;
                            pos++;
                        }
                        produced++;
                    }
                } else if (id == 0) {
                    if (sub == 1) { // second extension: blockSize/2 pairs -> blockSize values
                        long[] vals = new long[blockSize];
                        int vi = 0;
                        for (int p = 0; p < blockSize / 2; p++) {
                            long g = in.fundamentalSequence();
                            long ms = triangularRoot(g);
                            long d2 = g - ms * (ms + 1) / 2;
                            vals[vi++] = ms - d2;
                            vals[vi++] = d2;
                        }
                        for (int i = ref; i < blockSize; i++) {
                            if (pos < sampleCount) {
                                out[pos] = preprocess ? unmap(vals[i], out[pos - 1], xmax) : vals[i];
                                pos++;
                            }
                            produced++;
                        }
                    } else { // zero block
                        long zeroBlocks = in.fundamentalSequence() + 1;
                        long count;
                        if (zeroBlocks == 5) { // remainder of segment
                            count = rsiSamples - produced;
                        } else {
                            if (zeroBlocks > 5) {
                                zeroBlocks--;
                            }
                            count = zeroBlocks * blockSize - ref;
                        }
                        for (long i = 0; i < count && produced < rsiSamples && pos < sampleCount; i++) {
                            out[pos] = preprocess ? out[pos - 1] : 0;
                            pos++;
                            produced++;
                        }
                    }
                } else { // sample splitting, k = id - 1
                    int k = id - 1;
                    long[] highs = new long[samples];
                    for (int i = 0; i < samples; i++) {
                        highs[i] = in.fundamentalSequence();
                    }
                    for (int i = 0; i < samples; i++) {
                        long low = k > 0 ? in.readLong(k) : 0;
                        long v = (highs[i] << k) | low;
                        if (pos < sampleCount) {
                            out[pos] = preprocess ? unmap(v, out[pos - 1], xmax) : v;
                            pos++;
                        }
                        produced++;
                    }
                }
            }
        }
        return out;
    }

    private static int idLen(int bitsPerSample) {
        if (bitsPerSample <= 8) {
            return 3;
        }
        return bitsPerSample <= 16 ? 4 : 5;
    }

    /** Inverse of the nearest-neighbour mapper: recovers a sample from its mapped delta and predictor. */
    private static long unmap(long mapped, long prev, long xmax) {
        long theta = Math.min(prev, xmax - prev);
        if (mapped > 2 * theta) {
            long d = mapped - theta;
            return prev < xmax - prev ? prev + d : prev - d;
        }
        return (mapped & 1) == 0 ? prev + mapped / 2 : prev - (mapped + 1) / 2;
    }

    /** Largest {@code m} with {@code m(m+1)/2 <= g} (inverse triangular for second extension). */
    private static long triangularRoot(long g) {
        long m = (long) ((Math.sqrt(8.0 * g + 1) - 1) / 2);
        while ((m + 1) * (m + 2) / 2 <= g) {
            m++;
        }
        while (m * (m + 1) / 2 > g) {
            m--;
        }
        return m;
    }

    /** Most-significant-first bit reader over a byte array. */
    private static final class BitReader {
        private final byte[] data;
        private int bit;

        BitReader(byte[] data) {
            this.data = data;
        }

        int read(int n) {
            return (int) readLong(n);
        }

        long readLong(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                int b = (data[bit >> 3] >> (7 - (bit & 7))) & 1;
                v = (v << 1) | b;
                bit++;
            }
            return v;
        }

        /** Counts zero bits up to (and consuming) the next 1 bit. */
        int fundamentalSequence() {
            int c = 0;
            while (((data[bit >> 3] >> (7 - (bit & 7))) & 1) == 0) {
                c++;
                bit++;
            }
            bit++;
            return c;
        }
    }
}
