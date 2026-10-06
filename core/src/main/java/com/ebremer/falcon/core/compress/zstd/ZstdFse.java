package com.ebremer.falcon.core.compress.zstd;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * Finite State Entropy decoding: reading a table's normalized counts and building the decoding table
 * that drives the state machine (RFC&nbsp;8878 &sect;4.1).
 */
final class ZstdFse {

    /** A built FSE decoding table: for each state, the symbol it emits and how it advances. */
    static final class Table {
        final int tableLog;
        final byte[] symbol;
        final byte[] numberOfBits;
        final short[] newState;

        Table(int tableLog, int size) {
            this.tableLog = tableLog;
            this.symbol = new byte[size];
            this.numberOfBits = new byte[size];
            this.newState = new short[size];
        }
    }

    /** A running FSE state over a bitstream. */
    static final class State {
        private final Table table;
        private int state;

        State(Table table, ZstdBitReader in) {
            this.table = table;
            this.state = in.readBits(table.tableLog);
        }

        int symbol() {
            return table.symbol[state] & 0xff;
        }

        /** Advances to the next state, consuming this state's bits. */
        void advance(ZstdBitReader in) {
            int bits = table.numberOfBits[state] & 0xff;
            state = (table.newState[state] & 0xffff) + in.readBits(bits);
        }
    }

    private ZstdFse() {
    }

    /** Builds a table whose every state emits {@code symbol} without consuming bits (RLE mode). */
    static Table rleTable(int symbol) {
        Table table = new Table(0, 1);
        table.symbol[0] = (byte) symbol;
        table.numberOfBits[0] = 0;
        table.newState[0] = 0;
        return table;
    }

    /**
     * Reads a normalized-count header. Unlike the entropy streams, this is a forward bitstream, so it is
     * decoded with the reference algorithm's sliding 32-bit window.
     *
     * @param counts      receives the normalized count per symbol
     * @param header      {@code [maxSymbolValue, tableLog]} on return
     * @param maxTableLog the largest accuracy log the format allows for this table (RFC 8878: 9 for
     *                    literal and match lengths, 8 for offsets, 6 for Huffman weights)
     * @return the number of bytes consumed
     */
    static int readNCount(short[] counts, int[] header, byte[] in, int off, int len, int maxTableLog) {
        int maxSymbolValue = header[0];
        int start = off;
        int end = off + len;
        int ip = off;
        int charnum = 0;
        boolean previous0 = false;

        if (len < 4) {
            throw new CompressionFormatException("FSE table header is truncated");
        }
        int bitStream = readLe32(in, ip, end);
        int nbBits = (bitStream & 0xF) + 5;
        if (nbBits > maxTableLog) {
            throw new CompressionFormatException("FSE accuracy log " + nbBits + " exceeds the format's limit of "
                    + maxTableLog + " for this table");
        }
        bitStream >>>= 4;
        int bitCount = 4;
        header[1] = nbBits;
        int remaining = (1 << nbBits) + 1;
        int threshold = 1 << nbBits;
        nbBits++;

        while (remaining > 1 && charnum <= maxSymbolValue) {
            if (previous0) {
                int zeros = charnum;
                while ((bitStream & 0xFFFF) == 0xFFFF) {
                    zeros += 24;
                    if (ip < end - 5) {
                        ip += 2;
                        bitStream = readLe32(in, ip, end) >>> bitCount;
                    } else {
                        bitStream >>>= 16;
                        bitCount += 16;
                    }
                }
                while ((bitStream & 3) == 3) {
                    zeros += 3;
                    bitStream >>>= 2;
                    bitCount += 2;
                }
                zeros += bitStream & 3;
                bitCount += 2;
                if (zeros > maxSymbolValue) {
                    throw new CompressionFormatException("FSE zero run exceeds the symbol range");
                }
                while (charnum < zeros) {
                    counts[charnum++] = 0;
                }
                if (ip <= end - 7 || ip + (bitCount >> 3) <= end - 4) {
                    ip += bitCount >> 3;
                    bitCount &= 7;
                    bitStream = readLe32(in, ip, end) >>> bitCount;
                } else {
                    bitStream >>>= 2;
                }
                previous0 = false;
                continue;
            }

            int max = (2 * threshold - 1) - remaining;
            int count;
            if ((bitStream & (threshold - 1)) < max) {
                count = bitStream & (threshold - 1);
                bitCount += nbBits - 1;
            } else {
                count = bitStream & (2 * threshold - 1);
                if (count >= threshold) {
                    count -= max;
                }
                bitCount += nbBits;
            }
            count--; // a count of -1 marks a "less than one" probability
            remaining -= Math.abs(count);
            if (charnum >= counts.length) {
                throw new CompressionFormatException("FSE table declares too many symbols");
            }
            counts[charnum++] = (short) count;
            previous0 = count == 0;
            while (remaining < threshold) {
                nbBits--;
                threshold >>= 1;
            }
            if (ip <= end - 7 || ip + (bitCount >> 3) <= end - 4) {
                ip += bitCount >> 3;
                bitCount &= 7;
            } else {
                bitCount -= 8 * (end - 4 - ip);
                ip = end - 4;
            }
            bitStream = readLe32(in, ip, end) >>> (bitCount & 31);
        }
        if (remaining != 1) {
            throw new CompressionFormatException("FSE normalized counts do not sum to the table size");
        }
        if (bitCount > 32) {
            throw new CompressionFormatException("FSE table header overran its bit budget");
        }
        header[0] = charnum - 1;
        ip += (bitCount + 7) >> 3;
        return ip - start;
    }

    /** Builds the decoding table from normalized counts (the "spread symbols" construction). */
    static Table buildTable(short[] counts, int maxSymbolValue, int tableLog) {
        int size = 1 << tableLog;
        Table table = new Table(tableLog, size);
        byte[] spread = new byte[size];
        int highThreshold = size - 1;

        int[] symbolNext = new int[maxSymbolValue + 2];
        for (int s = 0; s <= maxSymbolValue; s++) {
            if (counts[s] == -1) {
                // "less than one" probability: parked at the top of the table
                spread[highThreshold--] = (byte) s;
                symbolNext[s] = 1;
            } else {
                symbolNext[s] = counts[s];
            }
        }

        int position = 0;
        int step = (size >> 1) + (size >> 3) + 3;
        int mask = size - 1;
        for (int s = 0; s <= maxSymbolValue; s++) {
            for (int i = 0; i < counts[s]; i++) {
                spread[position] = (byte) s;
                position = (position + step) & mask;
                while (position > highThreshold) {
                    position = (position + step) & mask;
                }
            }
        }
        if (position != 0) {
            throw new CompressionFormatException("FSE symbol spreading did not consume the table");
        }

        for (int u = 0; u < size; u++) {
            int s = spread[u] & 0xff;
            int next = symbolNext[s]++;
            int bits = tableLog - (31 - Integer.numberOfLeadingZeros(next));
            table.symbol[u] = (byte) s;
            table.numberOfBits[u] = (byte) bits;
            table.newState[u] = (short) ((next << bits) - size);
        }
        return table;
    }

    /** Builds a table directly from a predefined distribution. */
    static Table predefined(short[] distribution, int tableLog) {
        return buildTable(distribution, distribution.length - 1, tableLog);
    }

    /** Little-endian 32-bit read that treats bytes past {@code end} as zero. */
    private static int readLe32(byte[] in, int off, int end) {
        int value = 0;
        for (int i = 3; i >= 0; i--) {
            int index = off + i;
            int b = (index >= 0 && index < end) ? (in[index] & 0xff) : 0;
            value = (value << 8) | b;
        }
        return value;
    }
}
