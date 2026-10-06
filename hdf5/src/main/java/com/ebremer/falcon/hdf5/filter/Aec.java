package com.ebremer.falcon.hdf5.filter;

/**
 * A pure-Java decoder and encoder for the <strong>CCSDS 121.0</strong> adaptive entropy (extended-Rice)
 * coding implemented by <a href="https://gitlab.dkrz.de/k202009/libaec">libaec</a> — the algorithm behind
 * HDF5's {@code szip} filter. Written from the format description and libaec's encoder (no native code),
 * and validated byte-for-byte against libaec across all coding modes, both ways.
 *
 * <p>The decoder produces the sample values. Modes handled: sample-splitting (fundamental sequence with
 * {@code k} split bits), uncompressed, zero-block (with remainder-of-segment), and second extension,
 * with optional nearest-neighbour (unit-delay) preprocessing. Bits are read most-significant-first. The
 * encoder makes the same choices libaec does, so it writes libaec's stream.
 *
 * <p>Both <b>unsigned</b> and <b>signed</b> samples are decoded; signed data (libaec's
 * {@code DATA_SIGNED}) sign-extends the reference/raw samples and unmaps against the signed value
 * bounds. HDF5's szip filter never sets it &mdash; libaec's SZ compatibility layer codes every sample
 * unsigned (see {@link Szip}) &mdash; but the libaec reference vectors cover it.
 */
public final class Aec {

    /** libaec flag: samples are signed integers (affects the reference sample and unmap bounds). */
    public static final int FLAG_SIGNED = 1;
    /** libaec flag: samples are stored most-significant-byte first (affects byte packing, not decoding). */
    public static final int FLAG_MSB = 4;
    /** libaec flag: nearest-neighbour (unit-delay) preprocessing was applied. */
    public static final int FLAG_PREPROCESS = 8;

    private Aec() {
    }

    /**
     * Encodes {@code samples} into an AEC bitstream exactly as libaec's encoder ({@code encode.c}) does,
     * so its output is libaec's, byte for byte. Samples are taken as {@code bitsPerSample}-bit patterns
     * (two's complement for signed data). Each reference-sample interval of {@code rsi} blocks is
     * preprocessed if {@link #FLAG_PREPROCESS} is set (a reference sample, then each sample mapped from its
     * difference to the one before), and each block of {@code blockSize} samples is then coded in the
     * option libaec picks for it: a run of zero blocks (up to the end of a 64-block segment or of the
     * interval, there "remainder of segment"), sample splitting with libaec's search for {@code k} (which
     * carries over from block to block), the second extension, or uncompressed. A final, shorter interval
     * is padded with its last sample and coded only as far as its blocks need.
     *
     * @param flags {@link #FLAG_PREPROCESS} and {@link #FLAG_SIGNED} (the others do not change the coding)
     */
    public static byte[] encode(long[] samples, int bitsPerSample, int blockSize, int rsi, int flags) {
        if (bitsPerSample < 1 || bitsPerSample > 32) {
            throw new IllegalArgumentException("AEC samples are 1 to 32 bits wide, not " + bitsPerSample);
        }
        if (blockSize < 2 || blockSize > 256 || (blockSize & 1) != 0 || rsi < 1 || rsi > 4096) {
            throw new IllegalArgumentException("invalid AEC parameters: block=" + blockSize + " rsi=" + rsi);
        }
        return new Encoder(bitsPerSample, blockSize, rsi, flags).encode(samples);
    }

    /** libaec's encoder state machine, run over the whole input at once. */
    private static final class Encoder {
        private final int bits;
        private final int blockSize;
        private final int rsi;
        private final boolean preprocess;
        private final boolean signed;
        private final int idLen;
        private final int kmax;
        private final long mask;
        private final long xmax;
        private final long xmin;
        private final BitWriter out = new BitWriter();
        private final long[] raw;
        private final long[] d;
        private int k;                  // the splitting position, carried from block to block
        private long refSample;
        private int zeroBlocks;         // a run of zero blocks not yet emitted
        private boolean zeroRef;        // ... whose first block held the interval's reference sample
        private long zeroRefSample;
        private boolean remainderOfSegment;

        Encoder(int bits, int blockSize, int rsi, int flags) {
            this.bits = bits;
            this.blockSize = blockSize;
            this.rsi = rsi;
            this.preprocess = (flags & FLAG_PREPROCESS) != 0;
            this.signed = (flags & FLAG_SIGNED) != 0;
            this.idLen = idLen(bits);
            this.kmax = (1 << idLen) - 3;
            this.mask = bits == 64 ? -1L : (1L << bits) - 1;
            this.xmax = signed ? (1L << (bits - 1)) - 1 : mask;
            this.xmin = signed ? -(1L << (bits - 1)) : 0;
            this.raw = new long[rsi * blockSize];
            this.d = preprocess ? new long[rsi * blockSize] : raw;
        }

        byte[] encode(long[] samples) {
            int interval = rsi * blockSize;
            for (int start = 0; start < samples.length; start += interval) {
                int got = Math.min(interval, samples.length - start);
                for (int i = 0; i < interval; i++) {
                    raw[i] = i < got ? samples[start + i] & mask : raw[got - 1]; // m_get_rsi_resumable's padding
                }
                int blocks = got == interval ? rsi : (got + blockSize - 1) / blockSize;
                if (preprocess) {
                    preprocess();
                }
                for (int b = 0; b < blocks; b++) {
                    block(b * blockSize, preprocess && b == 0, b == blocks - 1, b + 1);
                }
            }
            return out.toByteArray(); // the last byte padded with zero bits
        }

        /** {@code preprocess_unsigned} / {@code preprocess_signed}: the reference sample, then mapped differences. */
        private void preprocess() {
            refSample = raw[0];
            d[0] = 0;
            long previous = value(raw[0]);
            for (int i = 1; i < raw.length; i++) {
                long x = value(raw[i]);
                if (x < previous) {
                    long delta = previous - x;
                    d[i] = delta <= xmax - previous ? 2 * delta - 1 : xmax - x;
                } else {
                    long delta = x - previous;
                    d[i] = delta <= previous - xmin ? 2 * delta : x - xmin;
                }
                previous = x;
            }
        }

        private long value(long pattern) {
            return signed && bits < 64 ? pattern << (64 - bits) >> (64 - bits) : pattern;
        }

        /** {@code m_check_zero_block} and {@code m_select_code_option} for the block at {@code at}. */
        private void block(int at, boolean ref, boolean lastOfInterval, int dispensed) {
            boolean zero = true;
            for (int i = at; i < at + blockSize && zero; i++) {
                zero = d[i] == 0;
            }
            if (zero) {
                if (++zeroBlocks == 1) {
                    zeroRef = ref;
                    zeroRefSample = refSample;
                }
                if (lastOfInterval || dispensed % 64 == 0) {
                    remainderOfSegment = zeroBlocks > 4;
                    emitZeroRun();
                }
                return;
            }
            if (zeroBlocks > 0) {
                emitZeroRun();
            }
            int r = ref ? 1 : 0;
            long uncompressed = (long) (blockSize - r) * bits;
            long split = assessSplitting(at, r);
            long se = assessSecondExtension(at, uncompressed);
            if (split < uncompressed) {
                if (split < se) {
                    emitSplitting(at, r);
                } else {
                    emitSecondExtension(at, r);
                }
            } else if (uncompressed <= se) {
                emitUncompressed(at, r);
            } else {
                emitSecondExtension(at, r);
            }
        }

        /** {@code assess_splitting_option}: libaec's search for the best {@code k}, from the last block's. */
        private long assessSplitting(int at, int ref) {
            long thisBlock = blockSize - ref;
            long lenMin = Long.MAX_VALUE;
            int kk = k;
            int kMin = kk;
            boolean noTurn = kk == 0;
            boolean up = true;
            while (true) {
                long fs = 0;
                for (int i = at; i < at + blockSize; i++) {
                    fs += d[i] >>> kk;
                }
                long len = fs + thisBlock * (kk + 1);
                if (len < lenMin) {
                    if (lenMin < Long.MAX_VALUE) {
                        noTurn = true;
                    }
                    lenMin = len;
                    kMin = kk;
                    if (up) {
                        if (fs < thisBlock || kk >= kmax) {
                            if (noTurn) {
                                break;
                            }
                            kk = k - 1;
                            up = false;
                            noTurn = true;
                        } else {
                            kk++;
                        }
                    } else {
                        if (fs >= thisBlock || kk == 0) {
                            break;
                        }
                        kk--;
                    }
                } else {
                    if (noTurn) {
                        break;
                    }
                    kk = k - 1;
                    up = false;
                    noTurn = true;
                }
            }
            k = kMin;
            return lenMin & 0xFFFF_FFFFL; // as libaec returns it, a uint32_t
        }

        /**
         * {@code assess_se_option}: the second extension's length, or "too long" past {@code limit}, in
         * libaec's unsigned 64-bit arithmetic (which wraps for 32-bit samples, as there).
         */
        private long assessSecondExtension(int at, long limit) {
            long len = 1;
            for (int i = at; i < at + blockSize; i += 2) {
                long sum = d[i] + d[i + 1];
                len += (sum * (sum + 1) >>> 1) + d[i + 1] + 1;
                if (Long.compareUnsigned(len, limit) > 0) {
                    return 0xFFFF_FFFFL;
                }
            }
            return len;
        }

        private void emitSplitting(int at, int ref) {
            out.write(k + 1, idLen);
            if (ref == 1) {
                out.write(refSample, bits);
            }
            for (int i = at + ref; i < at + blockSize; i++) {
                out.writeFundamental(d[i] >>> k);
            }
            if (k > 0) {
                long low = (1L << k) - 1;
                for (int i = at + ref; i < at + blockSize; i++) {
                    out.write(d[i] & low, k);
                }
            }
        }

        private void emitUncompressed(int at, int ref) {
            out.write((1 << idLen) - 1, idLen);
            for (int i = at; i < at + blockSize; i++) {
                out.write(ref == 1 && i == at ? refSample : d[i], bits);
            }
        }

        private void emitSecondExtension(int at, int ref) {
            out.write(1, idLen + 1);
            if (ref == 1) {
                out.write(refSample, bits);
            }
            for (int i = at; i < at + blockSize; i += 2) {
                long sum = d[i] + d[i + 1];
                out.writeFundamental(sum * (sum + 1) / 2 + d[i + 1]);
            }
        }

        private void emitZeroRun() {
            out.write(0, idLen + 1);
            if (zeroRef) {
                out.write(zeroRefSample, bits);
            }
            out.writeFundamental(remainderOfSegment ? 4 : zeroBlocks >= 5 ? zeroBlocks : zeroBlocks - 1);
            zeroBlocks = 0;
            remainderOfSegment = false;
        }
    }

    /**
     * Decodes {@code sampleCount} samples of {@code bitsPerSample}-bit data from an AEC bitstream.
     *
     * @param blockSize samples per block (libaec {@code block_size})
     * @param rsi       blocks per reference-sample interval
     * @param flags     libaec flags ({@link #FLAG_PREPROCESS} and {@link #FLAG_SIGNED} affect decoding)
     */
    public static long[] decode(byte[] data, int sampleCount, int bitsPerSample, int blockSize, int rsi, int flags) {
        return decode(data, 0, sampleCount, bitsPerSample, blockSize, rsi, flags);
    }

    /** As {@link #decode(byte[], int, int, int, int, int)}, for a bitstream starting at byte {@code offset}. */
    public static long[] decode(byte[] data, int offset, int sampleCount, int bitsPerSample, int blockSize,
                                int rsi, int flags) {
        if (bitsPerSample < 1 || bitsPerSample > 32 || blockSize < 1 || rsi < 1) {
            throw new IllegalArgumentException("invalid AEC parameters: bits=" + bitsPerSample
                    + " block=" + blockSize + " rsi=" + rsi);
        }
        boolean preprocess = (flags & FLAG_PREPROCESS) != 0;
        boolean signed = (flags & FLAG_SIGNED) != 0;
        BitReader in = new BitReader(data, offset);
        int idLen = idLen(bitsPerSample);
        int idMax = (1 << idLen) - 1;
        long xmin = signed ? -(1L << (bitsPerSample - 1)) : 0;
        long xmax = signed ? (1L << (bitsPerSample - 1)) - 1
                : (bitsPerSample >= 64 ? -1L : (1L << bitsPerSample) - 1);
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
                    out[pos++] = signExtend(in.readLong(bitsPerSample), signed, bitsPerSample);
                    produced++;
                    ref = 1;
                }
                int samples = blockSize - ref;

                if (id == idMax) { // uncompressed
                    for (int i = 0; i < samples; i++) {
                        long v = in.readLong(bitsPerSample);
                        if (pos < sampleCount) {
                            out[pos] = preprocess ? unmap(v, out[pos - 1], xmin, xmax)
                                    : signExtend(v, signed, bitsPerSample);
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
                                out[pos] = preprocess ? unmap(vals[i], out[pos - 1], xmin, xmax) : vals[i];
                                pos++;
                            }
                            produced++;
                        }
                    } else { // zero block
                        long zeroBlocks = in.fundamentalSequence() + 1;
                        if (zeroBlocks == 5) {
                            // "remainder of segment": to the end of the current 64-block segment, or of
                            // the RSI if that comes first (libaec m_zero_block).
                            int used = produced / blockSize;
                            zeroBlocks = Math.min(rsi - used, 64 - (used % 64));
                        } else if (zeroBlocks > 5) {
                            zeroBlocks--;
                        }
                        long count = zeroBlocks * blockSize - ref;
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
                            out[pos] = preprocess ? unmap(v, out[pos - 1], xmin, xmax)
                                    : signExtend(v, signed, bitsPerSample);
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
    private static long unmap(long mapped, long prev, long xmin, long xmax) {
        long theta = Math.min(prev - xmin, xmax - prev);
        if (mapped > 2 * theta) {
            long d = mapped - theta;
            return (prev - xmin) < (xmax - prev) ? prev + d : prev - d;
        }
        return (mapped & 1) == 0 ? prev + mapped / 2 : prev - (mapped + 1) / 2;
    }

    /** Sign-extends a {@code bits}-wide sample to a full {@code long} when signed data is decoded. */
    private static long signExtend(long value, boolean signed, int bits) {
        if (!signed || bits >= 64) {
            return value;
        }
        long signBit = 1L << (bits - 1);
        return (value & signBit) != 0 ? value | -(1L << bits) : value;
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

    /** Most-significant-first bit writer over a zeroed buffer, growing it as needed. */
    private static final class BitWriter {
        private byte[] data = new byte[64];
        private long bit;

        void write(long value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    set(bit);
                }
                bit++;
            }
            capacity(bit);
        }

        /** Fundamental sequence: {@code value} zero bits followed by a single one bit. */
        void writeFundamental(long value) {
            bit += value;
            set(bit++);
        }

        private void set(long at) {
            capacity(at + 1);
            data[(int) (at >> 3)] |= (byte) (0x80 >>> (int) (at & 7));
        }

        private void capacity(long bits) {
            long bytes = (bits + 7) >> 3;
            if (bytes > data.length) {
                if (bytes > Integer.MAX_VALUE - 8) {
                    throw new IllegalArgumentException("AEC output exceeds 2 GiB");
                }
                data = java.util.Arrays.copyOf(data, (int) Math.max(bytes, Math.min(Integer.MAX_VALUE - 8, 2L * data.length)));
            }
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(data, (int) ((bit + 7) >> 3));
        }
    }

    /** Most-significant-first bit reader over a byte array. */
    private static final class BitReader {
        private final byte[] data;
        private long bit;

        BitReader(byte[] data, int offset) {
            this.data = data;
            this.bit = (long) offset * 8;
        }

        int read(int n) {
            return (int) readLong(n);
        }

        long readLong(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                int b = (data[(int) (bit >> 3)] >> (7 - (int) (bit & 7))) & 1;
                v = (v << 1) | b;
                bit++;
            }
            return v;
        }

        /** Counts zero bits up to (and consuming) the next 1 bit. */
        int fundamentalSequence() {
            int c = 0;
            while (((data[(int) (bit >> 3)] >> (7 - (int) (bit & 7))) & 1) == 0) {
                c++;
                bit++;
            }
            bit++;
            return c;
        }
    }
}
