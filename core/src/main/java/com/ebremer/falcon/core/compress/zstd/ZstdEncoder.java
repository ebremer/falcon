package com.ebremer.falcon.core.compress.zstd;

import java.io.ByteArrayOutputStream;

/**
 * A pure-Java Zstandard compressor (RFC&nbsp;8878), the counterpart to {@link ZstdDecoder}, so Falcon can
 * <em>write</em> the format the Zarr ecosystem defaults to and stay dependency-free.
 *
 * <p>It is a straightforward but real compressor: an LZ77 match finder (a 4-byte hash table, greedy
 * matching) produces sequences of (literal run, match length, offset); literals are stored uncompressed
 * and the sequences are entropy-coded with FSE using the format's <em>predefined</em> tables. That avoids
 * a Huffman encoder and custom-table transmission while still compressing repetitive data (which array
 * chunks usually are). Input is split into blocks of at most 64&nbsp;KiB, which keeps every literal and
 * match length inside the predefined code ranges. A block that would not shrink is stored raw.
 *
 * <p>A frame no larger than 128&nbsp;KiB is single-segment (its window is its content), as libzstd writes
 * small frames. A larger one declares a 128&nbsp;KiB window, which covers every block and match, and keeps
 * its content size: a streaming decoder then needs a 128&nbsp;KiB window, not one the size of the frame
 * (libzstd's streaming API refuses a window over 128&nbsp;MiB by default). The XXH64 content checksum is
 * optional.
 *
 * <p>Frames it produces are read by {@link ZstdDecoder} and by libzstd (zarr-python), verified by
 * round-trip and conformance tests.
 */
public final class ZstdEncoder {

    private static final int MAGIC = 0xFD2FB528;
    private static final int BLOCK_SIZE = 64 * 1024;
    /** The window a frame larger than it declares; it covers the blocks, and matches never leave a block. */
    private static final int WINDOW_LOG = 17;
    private static final int FLAG_SINGLE_SEGMENT = 0x20;
    private static final int FLAG_CHECKSUM = 0x04;
    private static final int FCS_8_BYTES = 0xC0;
    private static final int MIN_MATCH = 3;
    private static final int MAX_MATCH = 65535;
    private static final int HASH_LOG = 15;
    private static final int HASH_SIZE = 1 << HASH_LOG;

    // Predefined FSE distributions and their table logs (RFC 8878 section 3.1.1.3.2.2).
    private static final short[] LL_DEFAULT = {
        4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
        -1, -1, -1, -1};
    private static final short[] ML_DEFAULT = {
        1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
        -1, -1, -1, -1, -1};
    private static final short[] OF_DEFAULT = {
        1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1};
    private static final int LL_LOG = 6;
    private static final int ML_LOG = 6;
    private static final int OF_LOG = 5;

    private static final int[] LL_BASE = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
        8192, 16384, 32768, 65536};
    private static final int[] LL_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
        13, 14, 15, 16};
    private static final int[] ML_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
        19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
        35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
        4099, 8195, 16387, 32771, 65539};
    private static final int[] ML_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
        12, 13, 14, 15, 16};

    private final CTable litLengthTable = new CTable(LL_DEFAULT, LL_LOG);
    private final CTable matchLengthTable = new CTable(ML_DEFAULT, ML_LOG);
    private final CTable offsetTable = new CTable(OF_DEFAULT, OF_LOG);
    private final int[] hashTable = new int[HASH_SIZE];

    private ZstdEncoder() {
    }

    /** Compresses {@code input} into a single Zstandard frame, without a content checksum. */
    public static byte[] compress(byte[] input) {
        return compress(input, false);
    }

    /**
     * Compresses {@code input} into a single Zstandard frame, ending it with the XXH64 content checksum
     * when {@code checksum} is true (the {@code checksum} option of zstd and of Zarr's {@code zstd} codec).
     */
    public static byte[] compress(byte[] input, boolean checksum) {
        return new ZstdEncoder().encodeFrame(input, checksum);
    }

    private byte[] encodeFrame(byte[] input, boolean checksum) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 2));
        writeLe(out, MAGIC, 4);
        int checksumFlag = checksum ? FLAG_CHECKSUM : 0;
        if (input.length == 0) {
            // Match libzstd's minimal frame: single-segment, 1-byte content size 0, one empty raw block.
            out.write(FLAG_SINGLE_SEGMENT | checksumFlag);
            out.write(0x00);
            writeLe(out, 1, 3);
        } else {
            if (input.length <= 1 << WINDOW_LOG) {
                // Single-segment, 8-byte content size, no dictionary.
                out.write(FCS_8_BYTES | FLAG_SINGLE_SEGMENT | checksumFlag);
            } else {
                // A window descriptor (RFC 8878 3.1.1.1.2: exponent WINDOW_LOG - 10, mantissa 0), then the
                // 8-byte content size.
                out.write(FCS_8_BYTES | checksumFlag);
                out.write((WINDOW_LOG - 10) << 3);
            }
            writeLe(out, input.length, 8);
            for (int start = 0; start < input.length; start += BLOCK_SIZE) {
                int end = Math.min(start + BLOCK_SIZE, input.length);
                boolean last = end == input.length;
                writeBlock(out, input, start, end, last);
            }
        }
        if (checksum) {
            writeLe(out, Xxh64.hash(input, 0, input.length, 0), 4); // the low 32 bits
        }
        return out.toByteArray();
    }

    // ---- blocks ----------------------------------------------------------------------------------

    private void writeBlock(ByteArrayOutputStream out, byte[] input, int start, int end, boolean last) {
        int blockSize = end - start;
        byte[] compressed = compressBlock(input, start, end);
        // A compressed block must be strictly smaller than raw, and the format caps a compressed block at
        // one byte under the block size.
        if (compressed != null && compressed.length < blockSize) {
            writeBlockHeader(out, last, 2, compressed.length);
            out.write(compressed, 0, compressed.length);
        } else {
            writeBlockHeader(out, last, 0, blockSize); // raw
            out.write(input, start, blockSize);
        }
    }

    private void writeBlockHeader(ByteArrayOutputStream out, boolean last, int type, int size) {
        int header = (last ? 1 : 0) | (type << 1) | (size << 3);
        writeLe(out, header, 3);
    }

    /** Compresses one block to a literals section + sequences section, or null if it cannot help. */
    private byte[] compressBlock(byte[] input, int start, int end) {
        java.util.Arrays.fill(hashTable, -1);
        IntList llCodes = new IntList();
        IntList mlCodes = new IntList();
        IntList ofCodes = new IntList();
        IntList llExtras = new IntList();
        IntList mlExtras = new IntList();
        IntList ofExtras = new IntList();

        ByteArrayOutputStream literals = new ByteArrayOutputStream();
        int literalStart = start;
        int pos = start;
        int limit = end - 4; // the hash reads a 4-byte window at pos
        while (pos <= limit) {
            int h = hash(input, pos);
            int candidate = hashTable[h];
            hashTable[h] = pos;
            if (candidate >= start && matches(input, candidate, pos)) {
                int matchLen = extend(input, candidate, pos, end);
                int offset = pos - candidate;
                int litLen = pos - literalStart;
                literals.write(input, literalStart, litLen);

                int llCode = codeFor(litLen, LL_BASE);
                llCodes.add(llCode);
                llExtras.add(litLen - LL_BASE[llCode]);

                int mlCode = codeFor(matchLen, ML_BASE);
                mlCodes.add(mlCode);
                mlExtras.add(matchLen - ML_BASE[mlCode]);

                int offBase = offset + 3; // no repeat offsets: offBase = offset + 3
                int ofCode = 31 - Integer.numberOfLeadingZeros(offBase);
                ofCodes.add(ofCode);
                ofExtras.add(offBase - (1 << ofCode));

                // seed the hash for a couple of interior positions, then skip the match
                int next = pos + matchLen;
                for (int p = pos + 1; p < next - MIN_MATCH && p < pos + 4; p++) {
                    hashTable[hash(input, p)] = p;
                }
                pos = next;
                literalStart = pos;
            } else {
                pos++;
            }
        }
        int nbSeq = llCodes.size();
        if (nbSeq == 0) {
            return null; // nothing matched; a raw block is smaller than an empty compressed one
        }
        literals.write(input, literalStart, end - literalStart); // trailing literals

        ByteArrayOutputStream block = new ByteArrayOutputStream();
        writeRawLiterals(block, literals.toByteArray());
        writeSequenceCount(block, nbSeq);
        block.write(0); // compression modes: predefined for LL, OF, ML
        byte[] bitstream = encodeSequences(nbSeq, llCodes, mlCodes, ofCodes, llExtras, mlExtras, ofExtras);
        block.write(bitstream, 0, bitstream.length);
        return block.toByteArray();
    }

    // ---- literals section (raw) ------------------------------------------------------------------

    private static void writeRawLiterals(ByteArrayOutputStream out, byte[] literals) {
        int size = literals.length;
        if (size < 32) {
            out.write((size << 3)); // type 0 (raw), size_format 0
        } else if (size < 4096) {
            out.write(((size & 0xF) << 4) | 0x04); // size_format 1
            out.write(size >>> 4);
        } else {
            out.write(((size & 0xF) << 4) | 0x0C); // size_format 3
            out.write((size >>> 4) & 0xff);
            out.write((size >>> 12) & 0xff);
        }
        out.write(literals, 0, size);
    }

    private static void writeSequenceCount(ByteArrayOutputStream out, int nbSeq) {
        if (nbSeq < 128) {
            out.write(nbSeq);
        } else if (nbSeq < 0x7F00) {
            out.write((nbSeq >>> 8) + 128);
            out.write(nbSeq & 0xff);
        } else {
            out.write(255);
            out.write((nbSeq - 0x7F00) & 0xff);
            out.write((nbSeq - 0x7F00) >>> 8);
        }
    }

    // ---- sequence bitstream (FSE, predefined tables) ---------------------------------------------

    private byte[] encodeSequences(int nbSeq, IntList llCodes, IntList mlCodes, IntList ofCodes,
                                   IntList llExtras, IntList mlExtras, IntList ofExtras) {
        BitCStream bits = new BitCStream(nbSeq * 16 + 64);

        // First symbols: the last sequence, whose states use the smallest-state initialization.
        long mlState = matchLengthTable.initState(mlCodes.get(nbSeq - 1));
        long ofState = offsetTable.initState(ofCodes.get(nbSeq - 1));
        long llState = litLengthTable.initState(llCodes.get(nbSeq - 1));
        bits.addBits(llExtras.get(nbSeq - 1), LL_BITS[llCodes.get(nbSeq - 1)]);
        bits.addBits(mlExtras.get(nbSeq - 1), ML_BITS[mlCodes.get(nbSeq - 1)]);
        bits.addBits(ofExtras.get(nbSeq - 1), ofCodes.get(nbSeq - 1));
        bits.flush();

        for (int n = nbSeq - 2; n >= 0; n--) {
            int ofCode = ofCodes.get(n);
            int mlCode = mlCodes.get(n);
            int llCode = llCodes.get(n);
            ofState = offsetTable.encode(bits, ofState, ofCode);
            mlState = matchLengthTable.encode(bits, mlState, mlCode);
            llState = litLengthTable.encode(bits, llState, llCode);
            bits.flush();
            bits.addBits(llExtras.get(n), LL_BITS[llCode]);
            bits.addBits(mlExtras.get(n), ML_BITS[mlCode]);
            bits.addBits(ofExtras.get(n), ofCode);
            bits.flush();
        }

        matchLengthTable.flushState(bits, mlState);
        offsetTable.flushState(bits, ofState);
        litLengthTable.flushState(bits, llState);
        return bits.close();
    }

    // ---- match finding ---------------------------------------------------------------------------

    private static int hash(byte[] in, int pos) {
        int v = (in[pos] & 0xff) | ((in[pos + 1] & 0xff) << 8)
                | ((in[pos + 2] & 0xff) << 16) | ((in[pos + 3] & 0xff) << 24);
        return (v * 0x9E3779B1) >>> (32 - HASH_LOG);
    }

    private static boolean matches(byte[] in, int a, int b) {
        return in[a] == in[b] && in[a + 1] == in[b + 1] && in[a + 2] == in[b + 2];
    }

    private static int extend(byte[] in, int ref, int pos, int end) {
        int len = MIN_MATCH;
        int max = Math.min(end - pos, MAX_MATCH);
        while (len < max && in[ref + len] == in[pos + len]) {
            len++;
        }
        return len;
    }

    /** The sequence code for {@code value}: the largest index with {@code base[code] <= value}. */
    private static int codeFor(int value, int[] base) {
        int code = base.length - 1;
        while (base[code] > value) {
            code--;
        }
        return code;
    }

    private static void writeLe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xff));
        }
    }

    // ---- FSE compression table -------------------------------------------------------------------

    /** An FSE encoding table built from a normalized distribution (FSE_buildCTable_wksp). */
    private static final class CTable {
        final int tableLog;
        final int[] nextState; // size tableSize; the "next state number" values
        final long[] deltaNbBits;
        final int[] deltaFindState;

        CTable(short[] norm, int tableLog) {
            this.tableLog = tableLog;
            int tableSize = 1 << tableLog;
            int tableMask = tableSize - 1;
            int step = (tableSize >>> 1) + (tableSize >>> 3) + 3;
            int maxSymbol = norm.length - 1;
            int maxSV1 = maxSymbol + 1;

            int[] cumul = new int[maxSV1 + 1];
            byte[] tableSymbol = new byte[tableSize];
            int highThreshold = tableSize - 1;
            cumul[0] = 0;
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
                    int maxBitsOut = tableLog - (31 - Integer.numberOfLeadingZeros(count - 1));
                    int minStatePlus = count << maxBitsOut;
                    deltaNbBits[s] = ((long) maxBitsOut << 16) - minStatePlus;
                    deltaFindState[s] = total - count;
                    total += count;
                }
            }
        }

        /** Initializes a state for {@code symbol} using the smallest-state trick (FSE_initCState2). */
        long initState(int symbol) {
            long dnb = deltaNbBits[symbol];
            long nbBitsOut = (dnb + (1 << 15)) >> 16;
            long value = (nbBitsOut << 16) - dnb;
            return nextState[(int) ((value >> nbBitsOut) + deltaFindState[symbol])];
        }

        /** Encodes {@code symbol} from {@code state}, writing its bits, and returns the next state. */
        long encode(BitCStream bits, long state, int symbol) {
            long dnb = deltaNbBits[symbol];
            int nbBitsOut = (int) ((state + dnb) >> 16);
            bits.addBits(state, nbBitsOut);
            return nextState[(int) ((state >> nbBitsOut) + deltaFindState[symbol])];
        }

        /** Flushes the final state value (FSE_flushCState). */
        void flushState(BitCStream bits, long state) {
            bits.addBits(state, tableLog);
            bits.flush();
        }
    }

    // ---- forward bit writer (BIT_CStream) --------------------------------------------------------

    /** Writes bits LSB-first into a little-endian byte stream, the way an FSE bitstream is built. */
    private static final class BitCStream {
        private byte[] out;
        private int pos;
        private long container;
        private int bitPos;

        BitCStream(int capacity) {
            this.out = new byte[Math.max(capacity, 16)];
        }

        void addBits(long value, int nbBits) {
            if (nbBits == 0) {
                return;
            }
            container |= (value & ((1L << nbBits) - 1)) << bitPos;
            bitPos += nbBits;
        }

        void flush() {
            while (bitPos >= 8) {
                ensure(1);
                out[pos++] = (byte) container;
                container >>>= 8;
                bitPos -= 8;
            }
        }

        byte[] close() {
            addBits(1, 1); // end marker
            flush();
            if (bitPos > 0) {
                ensure(1);
                out[pos++] = (byte) container;
                container = 0;
                bitPos = 0;
            }
            return java.util.Arrays.copyOf(out, pos);
        }

        private void ensure(int extra) {
            if (pos + extra > out.length) {
                out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, pos + extra));
            }
        }
    }

    /** A tiny growable int list, to avoid boxing while collecting sequence fields. */
    private static final class IntList {
        private int[] data = new int[16];
        private int size;

        void add(int value) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        int get(int i) {
            return data[i];
        }

        int size() {
            return size;
        }
    }
}
