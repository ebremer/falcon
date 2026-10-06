package com.ebremer.falcon.core.compress.zstd;

import java.io.ByteArrayOutputStream;

/**
 * Finite State Entropy encoding (RFC&nbsp;8878 &sect;4.1), the counterpart to {@link ZstdFse}: choosing a
 * table's accuracy, normalizing symbol counts to it, writing the normalized counts in the header format
 * {@link ZstdFse#readNCount} reads, and building the encoding table that drives the state machine.
 */
final class ZstdFseEncoder {

    /** The smallest accuracy log a table header can declare (its 4-bit field is the log minus 5). */
    static final int MIN_TABLE_LOG = 5;

    private ZstdFseEncoder() {
    }

    /**
     * The accuracy log for {@code total} symbols up to {@code maxSymbol}, as libzstd's
     * {@code FSE_optimalTableLog} picks it: small inputs get small tables, but every symbol must fit.
     */
    static int optimalTableLog(int maxTableLog, int total, int maxSymbol) {
        int maxBitsSrc = highbit(Math.max(total - 1, 1)) - 2;
        int minBits = Math.min(highbit(Math.max(total, 1)) + 1, highbit(Math.max(maxSymbol, 1)) + 2);
        int tableLog = maxTableLog;
        if (maxBitsSrc < tableLog) {
            tableLog = maxBitsSrc;
        }
        if (minBits > tableLog) {
            tableLog = minBits;
        }
        return Math.max(MIN_TABLE_LOG, Math.min(tableLog, maxTableLog));
    }

    /**
     * Normalizes {@code count[0..maxSymbol]} (summing to {@code total}, more than one symbol present) so the
     * counts sum to {@code 2^tableLog}, each present symbol keeping at least 1: proportional rounding, then
     * the difference taken from or given to the most probable symbols.
     */
    static short[] normalize(int[] count, int maxSymbol, int total, int tableLog) {
        int tableSize = 1 << tableLog;
        short[] norm = new short[maxSymbol + 1];
        int rest = tableSize;
        int largest = -1;
        for (int s = 0; s <= maxSymbol; s++) {
            if (count[s] == 0) {
                continue;
            }
            int p = (int) (((long) count[s] * tableSize + total / 2) / total);
            if (p < 1) {
                p = 1;
            }
            norm[s] = (short) p;
            rest -= p;
            if (largest < 0 || count[s] > count[largest]) {
                largest = s;
            }
        }
        if (rest > 0) {
            norm[largest] += (short) rest;
        }
        while (rest < 0) {
            // Take one from the symbol whose share is largest; every present symbol keeps at least 1.
            int best = -1;
            for (int s = 0; s <= maxSymbol; s++) {
                if (norm[s] > 1 && (best < 0 || norm[s] > norm[best])) {
                    best = s;
                }
            }
            norm[best]--;
            rest++;
        }
        return norm;
    }

    /**
     * Writes normalized counts in the table-description format (libzstd's {@code FSE_writeNCount}): the
     * accuracy log less 5 in 4 bits, then each count plus one in a variable number of bits, runs of zero
     * counts coded as repeat flags, stopping after the last symbol present.
     */
    static void writeNCount(ByteArrayOutputStream out, short[] norm, int maxSymbol, int tableLog) {
        int tableSize = 1 << tableLog;
        long bitStream = tableLog - MIN_TABLE_LOG;
        int bitCount = 4;
        int remaining = tableSize + 1;
        int threshold = tableSize;
        int nbBits = tableLog + 1;
        int symbol = 0;
        int alphabetSize = maxSymbol + 1;
        boolean previousIs0 = false;

        while (symbol < alphabetSize && remaining > 1) {
            if (previousIs0) {
                int start = symbol;
                while (symbol < alphabetSize && norm[symbol] == 0) {
                    symbol++;
                }
                if (symbol == alphabetSize) {
                    throw new IllegalStateException("normalized counts end in zeros");
                }
                while (symbol >= start + 24) {
                    start += 24;
                    bitStream += 0xFFFFL << bitCount;
                    out.write((int) bitStream & 0xff);
                    out.write((int) (bitStream >>> 8) & 0xff);
                    bitStream >>>= 16;
                }
                while (symbol >= start + 3) {
                    start += 3;
                    bitStream += 3L << bitCount;
                    bitCount += 2;
                }
                bitStream += (long) (symbol - start) << bitCount;
                bitCount += 2;
                if (bitCount > 16) {
                    out.write((int) bitStream & 0xff);
                    out.write((int) (bitStream >>> 8) & 0xff);
                    bitStream >>>= 16;
                    bitCount -= 16;
                }
            }
            int count = norm[symbol++];
            int max = (2 * threshold - 1) - remaining;
            remaining -= Math.abs(count);
            count++; // one more, so -1 ("less than one") is 0
            if (count >= threshold) {
                count += max;
            }
            bitStream += (long) count << bitCount;
            bitCount += nbBits;
            if (count < max) {
                bitCount--;
            }
            previousIs0 = count == 1;
            if (remaining < 1) {
                throw new IllegalStateException("normalized counts exceed the table size");
            }
            while (remaining < threshold) {
                nbBits--;
                threshold >>= 1;
            }
            if (bitCount > 16) {
                out.write((int) bitStream & 0xff);
                out.write((int) (bitStream >>> 8) & 0xff);
                bitStream >>>= 16;
                bitCount -= 16;
            }
        }
        if (remaining != 1) {
            throw new IllegalStateException("normalized counts do not sum to the table size");
        }
        int bytes = (bitCount + 7) / 8;
        for (int i = 0; i < bytes; i++) {
            out.write((int) (bitStream >>> (8 * i)) & 0xff);
        }
    }

    /** The highest set bit's index ({@code floor(log2(v))}) of a positive value. */
    static int highbit(int v) {
        return 31 - Integer.numberOfLeadingZeros(v);
    }

    /**
     * FSE-compresses a short symbol sequence with two interleaved states sharing one table, as libzstd's
     * {@code FSE_compress_usingCTable} does for Huffman weights: the decoder's first state takes the even
     * positions and its second the odd ones. The caller checks the result decodes as meant
     * ({@link ZstdHuffmanEncoder} reads it back with the decoder's own code).
     */
    static byte[] compressInterleaved(int[] symbols, int n, CTable table) {
        ZstdBitWriter bits = new ZstdBitWriter(n + 16);
        int ip = n;
        long s1;
        long s2;
        if ((n & 1) != 0) {
            s1 = table.initState(symbols[--ip]);
            s2 = table.initState(symbols[--ip]);
            s1 = table.encode(bits, s1, symbols[--ip]);
            bits.flush();
        } else {
            s2 = table.initState(symbols[--ip]);
            s1 = table.initState(symbols[--ip]);
        }
        while (ip > 0) {
            s2 = table.encode(bits, s2, symbols[--ip]);
            s1 = table.encode(bits, s1, symbols[--ip]);
            bits.flush();
        }
        table.flushState(bits, s2);
        table.flushState(bits, s1);
        return bits.close();
    }

    /**
     * An FSE encoding table built from normalized counts ({@code FSE_buildCTable}), and the encoder's
     * estimate of each symbol's cost.
     */
    static final class CTable {
        final int tableLog;
        final short[] norm;          // the normalized counts it was built from
        private final int[] nextState;
        private final long[] deltaNbBits;
        private final int[] deltaFindState;

        CTable(short[] norm, int tableLog) {
            this.tableLog = tableLog;
            this.norm = norm;
            int tableSize = 1 << tableLog;
            int tableMask = tableSize - 1;
            int step = (tableSize >>> 1) + (tableSize >>> 3) + 3;
            int maxSymbol = norm.length - 1;
            int maxSV1 = maxSymbol + 1;

            int[] cumul = new int[maxSV1 + 1];
            byte[] tableSymbol = new byte[tableSize];
            int highThreshold = tableSize - 1;
            for (int u = 1; u <= maxSV1; u++) {
                if (norm[u - 1] == -1) {
                    cumul[u] = cumul[u - 1] + 1;
                    tableSymbol[highThreshold--] = (byte) (u - 1);
                } else {
                    cumul[u] = cumul[u - 1] + norm[u - 1];
                }
            }
            cumul[maxSV1] = tableSize + 1;

            int position = 0;
            for (int symbol = 0; symbol < maxSV1; symbol++) {
                for (int n = 0; n < norm[symbol]; n++) {
                    tableSymbol[position] = (byte) symbol;
                    position = (position + step) & tableMask;
                    while (position > highThreshold) {
                        position = (position + step) & tableMask;
                    }
                }
            }

            this.nextState = new int[tableSize];
            for (int u = 0; u < tableSize; u++) {
                int s = tableSymbol[u] & 0xff;
                nextState[cumul[s]++] = tableSize + u;
            }

            this.deltaNbBits = new long[maxSV1];
            this.deltaFindState = new int[maxSV1];
            int total = 0;
            for (int s = 0; s <= maxSymbol; s++) {
                int count = norm[s];
                if (count == 0) {
                    deltaNbBits[s] = ((long) (tableLog + 1) << 16) - (1L << tableLog);
                } else if (count == -1 || count == 1) {
                    deltaNbBits[s] = ((long) tableLog << 16) - (1L << tableLog);
                    deltaFindState[s] = total - 1;
                    total += 1;
                } else {
                    int maxBitsOut = tableLog - highbit(count - 1);
                    int minStatePlus = count << maxBitsOut;
                    deltaNbBits[s] = ((long) maxBitsOut << 16) - minStatePlus;
                    deltaFindState[s] = total - count;
                    total += count;
                }
            }
        }

        /** Whether every symbol {@code count} holds has a nonzero probability here. */
        boolean covers(int[] count, int maxSymbol) {
            if (maxSymbol >= norm.length) {
                return false;
            }
            for (int s = 0; s <= maxSymbol; s++) {
                if (count[s] != 0 && norm[s] == 0) {
                    return false;
                }
            }
            return true;
        }

        /** The estimated bits to code {@code count} with this table: {@code tableLog - log2(p)} per symbol. */
        double cost(int[] count, int maxSymbol) {
            double bits = 0;
            for (int s = 0; s <= maxSymbol; s++) {
                if (count[s] != 0) {
                    int p = norm[s] == -1 ? 1 : norm[s];
                    bits += count[s] * (tableLog - Math.log(p) / Math.log(2));
                }
            }
            return bits;
        }

        /** Initializes a state for {@code symbol} using the smallest-state trick (FSE_initCState2). */
        long initState(int symbol) {
            long dnb = deltaNbBits[symbol];
            long nbBitsOut = (dnb + (1 << 15)) >> 16;
            long value = (nbBitsOut << 16) - dnb;
            return nextState[(int) ((value >> nbBitsOut) + deltaFindState[symbol])];
        }

        /** Encodes {@code symbol} from {@code state}, writing its bits, and returns the next state. */
        long encode(ZstdBitWriter bits, long state, int symbol) {
            long dnb = deltaNbBits[symbol];
            int nbBitsOut = (int) ((state + dnb) >> 16);
            bits.addBits(state, nbBitsOut);
            return nextState[(int) ((state >> nbBitsOut) + deltaFindState[symbol])];
        }

        /** Flushes the final state value (FSE_flushCState). */
        void flushState(ZstdBitWriter bits, long state) {
            bits.addBits(state, tableLog);
            bits.flush();
        }
    }
}
