package com.ebremer.falcon.core.compress.zstd;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * Huffman decoding for a compressed literals section (RFC&nbsp;8878 &sect;4.2).
 *
 * <p>The tree is described by per-symbol <em>weights</em>, either listed directly (4 bits each) or
 * themselves FSE-compressed. A symbol of weight {@code w > 0} has a code of {@code maxBits + 1 - w} bits
 * and therefore occupies {@code 2^(w-1)} entries of the flat decoding table; the final symbol's weight is
 * inferred from what is left of the power-of-two budget.
 */
final class ZstdHuffman {

    /** A flat decoding table indexed by the next {@link #maxBits} bits of the stream. */
    static final class Table {
        final int maxBits;
        final byte[] symbol;
        final byte[] numberOfBits;

        Table(int maxBits) {
            this.maxBits = maxBits;
            this.symbol = new byte[1 << maxBits];
            this.numberOfBits = new byte[1 << maxBits];
        }
    }

    private static final int MAX_SYMBOL = 255;
    private static final int MAX_TABLE_LOG = 11;

    private ZstdHuffman() {
    }

    /**
     * Reads a Huffman tree description.
     *
     * @param consumed receives the number of input bytes used
     */
    static Table readTable(byte[] in, int off, int available, int[] consumed) {
        if (available < 1) {
            throw new CompressionFormatException("Huffman tree description is truncated");
        }
        int headerByte = in[off] & 0xff;
        int[] weights = new int[MAX_SYMBOL + 2];
        int symbolCount;
        int used;

        if (headerByte >= 128) {
            // Weights listed directly, 4 bits each, two per byte.
            symbolCount = headerByte - 127;
            int bytes = (symbolCount + 1) / 2;
            if (1 + bytes > available) {
                throw new CompressionFormatException("direct Huffman weights are truncated");
            }
            for (int i = 0; i < symbolCount; i++) {
                int b = in[off + 1 + (i / 2)] & 0xff;
                weights[i] = (i % 2 == 0) ? (b >>> 4) : (b & 0xF);
            }
            used = 1 + bytes;
        } else {
            // Weights are FSE-compressed; headerByte is the compressed size.
            int compressedSize = headerByte;
            if (1 + compressedSize > available) {
                throw new CompressionFormatException("FSE-coded Huffman weights are truncated");
            }
            symbolCount = readFseWeights(in, off + 1, compressedSize, weights);
            used = 1 + compressedSize;
        }

        return buildTable(weights, symbolCount, consumed, used);
    }

    /** Decodes the FSE-compressed weight list; returns the number of weights read. */
    private static int readFseWeights(byte[] in, int off, int size, int[] weights) {
        short[] counts = new short[256];
        int[] header = {255, 0};
        int headerBytes = ZstdFse.readNCount(counts, header, in, off, size, 6); // RFC 8878 4.2.1.2: at most 6
        int maxSymbol = header[0];
        int tableLog = header[1];
        ZstdFse.Table table = ZstdFse.buildTable(counts, maxSymbol, tableLog);

        int streamStart = off + headerBytes;
        int streamLength = size - headerBytes;
        if (streamLength <= 0) {
            throw new CompressionFormatException("Huffman weight bitstream is empty");
        }
        ZstdBitReader in2 = new ZstdBitReader(in, streamStart, streamLength);

        // Two interleaved FSE states share one table: state 1 takes the even indices, state 2 the odd.
        // Decoding stops when advancing a state would need more bits than remain; the symbols held by
        // both final states are then emitted (RFC 8878 section 4.2.1.2).
        ZstdFse.State first = new ZstdFse.State(table, in2);
        ZstdFse.State second = new ZstdFse.State(table, in2);
        int count = 0;
        while (true) {
            if (count + 1 > MAX_SYMBOL) {
                throw new CompressionFormatException("too many Huffman weights");
            }
            weights[count++] = first.symbol();
            first.advance(in2);
            if (in2.overflowed()) {
                weights[count++] = second.symbol();
                break;
            }
            weights[count++] = second.symbol();
            second.advance(in2);
            if (in2.overflowed()) {
                weights[count++] = first.symbol();
                break;
            }
        }
        return count;
    }

    private static Table buildTable(int[] weights, int symbolCount, int[] consumed, int used) {
        // The last symbol's weight is implied, so at most 255 are listed (RFC 8878 section 4.2.1).
        if (symbolCount > MAX_SYMBOL) {
            throw new CompressionFormatException("too many Huffman weights (" + symbolCount + ")");
        }
        long totalWeight = 0;
        int maxWeight = 0;
        for (int i = 0; i < symbolCount; i++) {
            if (weights[i] > MAX_TABLE_LOG) {
                throw new CompressionFormatException("Huffman weight " + weights[i] + " is out of range");
            }
            if (weights[i] > 0) {
                totalWeight += 1L << (weights[i] - 1);
            }
            maxWeight = Math.max(maxWeight, weights[i]);
        }
        if (totalWeight == 0) {
            throw new CompressionFormatException("Huffman tree has no symbols");
        }
        int maxBits = 64 - Long.numberOfLeadingZeros(totalWeight); // highbit + 1
        if (maxBits > MAX_TABLE_LOG) {
            throw new CompressionFormatException("Huffman table log " + maxBits + " exceeds " + MAX_TABLE_LOG);
        }
        long left = (1L << maxBits) - totalWeight;
        if (left == 0 || Long.bitCount(left) != 1) {
            throw new CompressionFormatException("Huffman weights do not complete a power of two");
        }
        int lastWeight = Long.numberOfTrailingZeros(left) + 1;
        weights[symbolCount] = lastWeight;
        int total = symbolCount + 1;
        maxWeight = Math.max(maxWeight, lastWeight);

        Table table = new Table(maxBits);
        // Entries are laid out by ascending weight, i.e. longest codes first.
        int[] rankStart = new int[maxWeight + 2];
        int[] rankCount = new int[maxWeight + 2];
        for (int i = 0; i < total; i++) {
            rankCount[weights[i]]++;
        }
        int next = 0;
        for (int w = 1; w <= maxWeight; w++) {
            rankStart[w] = next;
            next += rankCount[w] << (w - 1);
        }
        if (next != (1 << maxBits)) {
            throw new CompressionFormatException("Huffman table is not exactly filled");
        }
        for (int symbol = 0; symbol < total; symbol++) {
            int w = weights[symbol];
            if (w == 0) {
                continue;
            }
            int entries = 1 << (w - 1);
            int start = rankStart[w];
            rankStart[w] += entries;
            byte bits = (byte) (maxBits + 1 - w);
            for (int i = 0; i < entries; i++) {
                table.symbol[start + i] = (byte) symbol;
                table.numberOfBits[start + i] = bits;
            }
        }
        consumed[0] = used;
        return table;
    }

    /** Decodes one symbol from {@code in}. */
    static int decodeSymbol(Table table, ZstdBitReader in) {
        int index = in.peekBits(table.maxBits);
        in.skipBits(table.numberOfBits[index] & 0xff);
        return table.symbol[index] & 0xff;
    }

    /** Decodes {@code count} literals from a single Huffman stream, which they must consume exactly. */
    static void decodeStream(Table table, byte[] in, int off, int length,
                             byte[] out, int outOff, int count) {
        ZstdBitReader reader = new ZstdBitReader(in, off, length);
        for (int i = 0; i < count; i++) {
            out[outOff + i] = (byte) decodeSymbol(table, reader);
        }
        if (!reader.finished()) {
            throw new CompressionFormatException("Huffman literal stream is not consumed exactly");
        }
    }

    /**
     * Decodes literals stored as four Huffman streams. A 6-byte jump table gives the first three
     * compressed sizes; the fourth takes the remainder. Each stream produces a quarter of the output
     * (the last one the remainder).
     */
    static void decodeFourStreams(Table table, byte[] in, int off, int length,
                                  byte[] out, int regeneratedSize) {
        if (length < 10) { // the jump table and at least one byte per stream (libzstd's minimum)
            throw new CompressionFormatException("4-stream literals are too short");
        }
        if (regeneratedSize < 6) {
            throw new CompressionFormatException("4-stream literals of " + regeneratedSize + " bytes cannot be split");
        }
        int size1 = (in[off] & 0xff) | ((in[off + 1] & 0xff) << 8);
        int size2 = (in[off + 2] & 0xff) | ((in[off + 3] & 0xff) << 8);
        int size3 = (in[off + 4] & 0xff) | ((in[off + 5] & 0xff) << 8);
        int size4 = length - 6 - size1 - size2 - size3;
        if (size1 < 0 || size2 < 0 || size3 < 0 || size4 < 0) {
            throw new CompressionFormatException("4-stream literal sizes are inconsistent");
        }
        int quarter = (regeneratedSize + 3) / 4;
        int last = regeneratedSize - 3 * quarter;
        if (last < 0) {
            throw new CompressionFormatException("4-stream literals are too small to split");
        }
        int base = off + 6;
        decodeStream(table, in, base, size1, out, 0, quarter);
        decodeStream(table, in, base + size1, size2, out, quarter, quarter);
        decodeStream(table, in, base + size1 + size2, size3, out, 2 * quarter, quarter);
        decodeStream(table, in, base + size1 + size2 + size3, size4, out, 3 * quarter, last);
    }
}
