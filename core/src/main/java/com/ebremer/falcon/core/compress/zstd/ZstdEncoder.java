package com.ebremer.falcon.core.compress.zstd;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A pure-Java Zstandard compressor (RFC&nbsp;8878), the counterpart to {@link ZstdDecoder}, so Falcon can
 * <em>write</em> the format the Zarr ecosystem defaults to and stay dependency-free.
 *
 * <p>It works as libzstd's "lazy" strategies do:
 * <ul>
 *   <li><b>Matching:</b> a hash chain over 4-byte prefixes, searched to a depth that grows with the level,
 *       with lazy evaluation (a match is kept only if the next one or two positions do not offer a better
 *       one), and the repeat offsets checked first. Matches reach back across blocks, anywhere within the
 *       frame's window.</li>
 *   <li><b>Literals:</b> Huffman-coded (one stream, or four for more than 255), or RLE or raw when that is
 *       smaller ({@link ZstdHuffmanEncoder}).</li>
 *   <li><b>Sequences:</b> literal lengths, match lengths, and offsets are FSE-coded with tables built from
 *       each block's statistics, the predefined tables, the previous block's tables, or RLE, whichever is
 *       estimated cheapest; offsets use the three repeat codes.</li>
 * </ul>
 * Blocks hold up to 128&nbsp;KiB, cut short (at multiples of 8&nbsp;KiB) where the bytes' statistics drift,
 * so each part's literals get a code of their own, as libzstd's block splitter does; a block of a single
 * repeated byte is stored as an RLE block, and one that would not shrink is stored raw.
 *
 * <p><b>Levels</b> follow libzstd's scale, 1 (fastest) to 22 (smallest); 0 means the default, 3, and a
 * negative level the fastest settings. A level sets the search depth, the lazy evaluation, the shortest
 * match, and the window, as libzstd's levels do: 512&nbsp;KiB at level 1, 1&nbsp;MiB at 2, 2&nbsp;MiB at
 * 3&ndash;8, 4&nbsp;MiB at 9&ndash;16, and 8&nbsp;MiB above (libzstd's levels 20&ndash;22 go to
 * 128&nbsp;MiB; Falcon stops at 8), well inside the 128&nbsp;MiB a streaming libzstd decoder accepts by
 * default. A frame no larger than its window is single-segment (its window is its content), as libzstd
 * writes it; a larger one declares the window, so a streaming decoder needs no more memory than that. The
 * XXH64 content checksum is optional.
 *
 * <p>Frames it produces are read by {@link ZstdDecoder} and by libzstd (zarr-python, c-blosc), verified by
 * round-trip tests and {@code tools/fixtures/check_zstd_encoder.py}.
 */
public final class ZstdEncoder {

    /** The level {@link #compress(byte[])} uses, and level 0 stands for: libzstd's default. */
    public static final int DEFAULT_LEVEL = 3;
    /** The highest level, as in libzstd; higher levels are taken as this one. */
    public static final int MAX_LEVEL = 22;

    private static final int MAGIC = 0xFD2FB528;
    private static final int BLOCK_SIZE = 128 * 1024;
    /** Blocks are split at multiples of this many bytes from their start, as libzstd's splitter does. */
    private static final int SPLIT_UNIT = 8 * 1024;
    /** What a block of its own costs, in bits (headers and a Huffman table): the bar for splitting. */
    private static final double SPLIT_OVERHEAD_BITS = 1200;
    private static final int FLAG_SINGLE_SEGMENT = 0x20;
    private static final int FLAG_CHECKSUM = 0x04;
    private static final int FCS_8_BYTES = 0xC0;
    /** Below this many bytes from the input's end, no match is searched (the hash and compares read ahead). */
    private static final int TAIL = 8;
    /** libzstd's kSearchStrength: the search skips ahead faster the longer it goes without a match. */
    private static final int SEARCH_STRENGTH = 8;

    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

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
    private static final ZstdFseEncoder.CTable LL_PREDEFINED = new ZstdFseEncoder.CTable(LL_DEFAULT, 6);
    private static final ZstdFseEncoder.CTable ML_PREDEFINED = new ZstdFseEncoder.CTable(ML_DEFAULT, 6);
    private static final ZstdFseEncoder.CTable OF_PREDEFINED = new ZstdFseEncoder.CTable(OF_DEFAULT, 5);
    // RFC 8878 section 4.1.1: the largest accuracy log each sequence table may declare, and its largest code.
    private static final int LL_MAX_LOG = 9;
    private static final int ML_MAX_LOG = 9;
    private static final int OF_MAX_LOG = 8;
    private static final int LL_MAX_CODE = 35;
    private static final int ML_MAX_CODE = 52;
    private static final int OF_MAX_CODE = 31;

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

    /**
     * What a level sets: the window, the hash and chain table sizes, how many chain entries a search
     * visits, the lazy evaluation (0 greedy, 1 lazy, 2 lazy2), the shortest match, and the length at which a
     * search stops looking for longer.
     */
    record Params(int windowLog, int hashLog, int chainLog, int searchDepth, int lazy, int minMatch,
                  int targetLength) {
    }

    // Levels 1..22 (index 0 serves negative levels: the fastest settings), after libzstd's table for large
    // inputs (clevels.h): its window, table sizes, search depth (2^searchLog), and shortest match, with a
    // hash chain and lazy evaluation standing in for its binary-tree and optimal-parsing strategies, and
    // windows capped at 8 MiB.
    private static final Params[] LEVELS = {
        new Params(19, 14, 0, 1, 0, 7, 8),         // negative
        new Params(19, 15, 0, 1, 0, 6, 16),        // 1
        new Params(20, 16, 15, 2, 0, 6, 16),       // 2
        new Params(21, 17, 17, 4, 1, 5, 24),       // 3 (default)
        new Params(21, 18, 18, 6, 1, 5, 32),       // 4
        new Params(21, 19, 18, 8, 1, 5, 32),       // 5
        new Params(21, 19, 18, 8, 1, 5, 48),       // 6
        new Params(21, 20, 19, 16, 2, 5, 64),      // 7
        new Params(21, 20, 19, 16, 2, 5, 64),      // 8
        new Params(22, 21, 20, 16, 2, 5, 96),      // 9
        new Params(22, 22, 21, 32, 2, 5, 128),     // 10
        new Params(22, 22, 21, 64, 2, 5, 128),     // 11
        new Params(22, 22, 22, 64, 2, 5, 192),     // 12
        new Params(22, 22, 22, 64, 2, 5, 256),     // 13
        new Params(22, 22, 22, 96, 2, 5, 256),     // 14
        new Params(22, 22, 22, 128, 2, 5, 256),    // 15
        new Params(22, 22, 22, 128, 2, 5, 384),    // 16
        new Params(23, 22, 22, 192, 2, 4, 512),    // 17
        new Params(23, 22, 22, 256, 2, 4, 512),    // 18
        new Params(23, 22, 22, 256, 2, 4, 768),    // 19
        new Params(23, 22, 22, 384, 2, 4, 999),    // 20
        new Params(23, 22, 22, 512, 2, 4, 999),    // 21
        new Params(23, 22, 22, 512, 2, 4, 999),    // 22
    };

    /** The settings for {@code level}: 0 is the default; a negative level the fastest; above 22, 22. */
    static Params params(int level) {
        int l = level == 0 ? DEFAULT_LEVEL : level < 0 ? 0 : Math.min(level, MAX_LEVEL);
        return LEVELS[l];
    }

    /** The window a frame of {@code level} declares when it is larger than the window: {@code 2^windowLog}. */
    public static int windowSize(int level) {
        return 1 << params(level).windowLog();
    }

    private final byte[] src;
    private final int n;
    private final Params p;
    private final int windowSize;

    // The match finder: head[hash] is the latest position with that hash, chain[pos & chainMask] the one
    // before it. Positions below nextToUpdate are inserted.
    private final int[] head;
    private final int hashShift;
    private final int[] chain;
    private final int chainMask;
    private int nextToUpdate;

    // What the decoder holds after the last block it decoded: the repeat offsets, and the sequence tables
    // a "repeat" mode refers to (null after RLE, which this encoder never repeats).
    private final int[] rep = {1, 4, 8};
    private ZstdFseEncoder.CTable llPrevious = LL_PREDEFINED;
    private ZstdFseEncoder.CTable ofPrevious = OF_PREDEFINED;
    private ZstdFseEncoder.CTable mlPrevious = ML_PREDEFINED;
    private boolean anyCompressedBlock;

    // The block being built.
    private int nbSeq;
    private int[] seqLitLength = new int[256];
    private int[] seqMatchLength = new int[256];
    private int[] seqOffBase = new int[256];
    private byte[] literals = new byte[BLOCK_SIZE];
    private int literalCount;

    private ZstdEncoder(byte[] src, Params p) {
        this.src = src;
        this.n = src.length;
        this.p = p;
        this.windowSize = 1 << p.windowLog();
        int inputLog = 32 - Integer.numberOfLeadingZeros(Math.max(n, 1) - 1); // ceil(log2(n))
        int hashLog = Math.max(10, Math.min(p.hashLog(), inputLog + 1));
        this.head = new int[1 << hashLog];
        this.hashShift = 32 - hashLog;
        if (p.chainLog() > 0) {
            int chainLog = Math.max(10, Math.min(p.chainLog(), inputLog));
            this.chain = new int[1 << chainLog];
            this.chainMask = (1 << chainLog) - 1;
        } else {
            this.chain = null;
            this.chainMask = 0;
        }
        Arrays.fill(head, -1);
    }

    /** Compresses {@code input} into a single Zstandard frame at the default level, without a checksum. */
    public static byte[] compress(byte[] input) {
        return compress(input, DEFAULT_LEVEL, false);
    }

    /**
     * Compresses {@code input} into a single Zstandard frame at the default level, ending it with the
     * XXH64 content checksum when {@code checksum} is true (the {@code checksum} option of zstd and of
     * Zarr's {@code zstd} codec).
     */
    public static byte[] compress(byte[] input, boolean checksum) {
        return compress(input, DEFAULT_LEVEL, checksum);
    }

    /**
     * Compresses {@code input} into a single Zstandard frame at {@code level} (libzstd's scale: 1 fastest to
     * 22 smallest, 0 the default 3, a negative level the fastest settings), ending it with the XXH64
     * content checksum when {@code checksum} is true.
     */
    public static byte[] compress(byte[] input, int level, boolean checksum) {
        return new ZstdEncoder(input, params(level)).encodeFrame(checksum);
    }

    private byte[] encodeFrame(boolean checksum) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, n / 2));
        writeLe(out, MAGIC, 4);
        int checksumFlag = checksum ? FLAG_CHECKSUM : 0;
        if (n == 0) {
            // Match libzstd's minimal frame: single-segment, 1-byte content size 0, one empty raw block.
            out.write(FLAG_SINGLE_SEGMENT | checksumFlag);
            out.write(0x00);
            writeLe(out, 1, 3);
        } else {
            if (n <= windowSize) {
                // Single-segment (the window is the content), 8-byte content size, no dictionary.
                out.write(FCS_8_BYTES | FLAG_SINGLE_SEGMENT | checksumFlag);
            } else {
                // A window descriptor (RFC 8878 3.1.1.1.2: exponent windowLog - 10, mantissa 0), then the
                // 8-byte content size.
                out.write(FCS_8_BYTES | checksumFlag);
                out.write((p.windowLog() - 10) << 3);
            }
            writeLe(out, n, 8);
            int start = 0;
            while (start < n) {
                int end = blockEnd(start);
                writeBlock(out, start, end, end == n);
                start = end;
            }
        }
        if (checksum) {
            writeLe(out, Xxh64.hash(src, 0, n, 0), 4); // the low 32 bits
        }
        return out.toByteArray();
    }

    // ---- blocks ----------------------------------------------------------------------------------

    /** The sizes of the blocks {@code input} is cut into (for tests: the split depends on the input alone). */
    static List<Integer> blockSizes(byte[] input) {
        ZstdEncoder encoder = new ZstdEncoder(input, params(DEFAULT_LEVEL));
        List<Integer> sizes = new ArrayList<>();
        for (int start = 0; start < input.length; ) {
            int end = encoder.blockEnd(start);
            sizes.add(end - start);
            start = end;
        }
        return sizes;
    }

    /**
     * Where the block starting at {@code start} ends. A block takes {@link #SPLIT_UNIT}-byte units while the
     * next unit's bytes are distributed like the block's: when keeping them together would cost more, by
     * the Shannon estimate of their byte histograms, than a block of its own (its own Huffman table, costing
     * {@link #SPLIT_OVERHEAD_BITS}), the block ends. Data whose statistics drift, such as floats, so gets
     * literal codes fitted to each part, as libzstd's block splitter gives it; data whose statistics do not
     * keeps whole blocks of up to 128&nbsp;KiB.
     */
    private int blockEnd(int start) {
        int limit = Math.min(n, start + BLOCK_SIZE);
        if (limit - start <= SPLIT_UNIT) {
            return limit;
        }
        int[] block = new int[256];
        int[] unit = new int[256];
        histogram(start, start + SPLIT_UNIT, block);
        int blockSize = SPLIT_UNIT;
        double blockBits = entropyBits(block, blockSize);
        int[] merged = new int[256];
        for (int u = start + SPLIT_UNIT; u < limit; u += SPLIT_UNIT) {
            int unitEnd = Math.min(u + SPLIT_UNIT, limit);
            Arrays.fill(unit, 0);
            histogram(u, unitEnd, unit);
            int unitSize = unitEnd - u;
            for (int s = 0; s < 256; s++) {
                merged[s] = block[s] + unit[s];
            }
            double mergedBits = entropyBits(merged, blockSize + unitSize);
            if (mergedBits - blockBits - entropyBits(unit, unitSize) > SPLIT_OVERHEAD_BITS) {
                return u;
            }
            System.arraycopy(merged, 0, block, 0, 256);
            blockSize += unitSize;
            blockBits = mergedBits;
        }
        return limit;
    }

    private void histogram(int from, int to, int[] count) {
        for (int i = from; i < to; i++) {
            count[src[i] & 0xff]++;
        }
    }

    /** The Shannon bound in bits on coding {@code total} bytes with histogram {@code count}. */
    private static double entropyBits(int[] count, int total) {
        double bits = total * Math.log(total);
        for (int c : count) {
            if (c != 0) {
                bits -= c * Math.log(c);
            }
        }
        return bits / Math.log(2);
    }

    private void writeBlock(ByteArrayOutputStream out, int start, int end, boolean last) {
        int blockSize = end - start;
        if (allSame(start, end)) {
            writeBlockHeader(out, last, 1, blockSize);
            out.write(src[start]);
            return;
        }
        int[] repBefore = rep.clone();
        ZstdFseEncoder.CTable ll = llPrevious;
        ZstdFseEncoder.CTable of = ofPrevious;
        ZstdFseEncoder.CTable ml = mlPrevious;
        boolean anyBefore = anyCompressedBlock;

        findSequences(start, end);
        byte[] compressed = compressBlock();
        // A compressed block must be strictly smaller than raw.
        if (compressed.length < blockSize) {
            writeBlockHeader(out, last, 2, compressed.length);
            out.write(compressed, 0, compressed.length);
            anyCompressedBlock = true;
        } else {
            // The decoder will not see these sequences or tables: restore what it holds.
            System.arraycopy(repBefore, 0, rep, 0, 3);
            llPrevious = ll;
            ofPrevious = of;
            mlPrevious = ml;
            anyCompressedBlock = anyBefore;
            writeBlockHeader(out, last, 0, blockSize);
            out.write(src, start, blockSize);
        }
    }

    private boolean allSame(int start, int end) {
        byte first = src[start];
        for (int i = start + 1; i < end; i++) {
            if (src[i] != first) {
                return false;
            }
        }
        return true;
    }

    private static void writeBlockHeader(ByteArrayOutputStream out, boolean last, int type, int size) {
        int header = (last ? 1 : 0) | (type << 1) | (size << 3);
        writeLe(out, header, 3);
    }

    /** The block's literals section and sequences section. */
    private byte[] compressBlock() {
        ByteArrayOutputStream block = new ByteArrayOutputStream(literalCount / 2 + nbSeq * 3 + 64);
        ZstdHuffmanEncoder.writeLiterals(block, literals, literalCount);
        writeSequenceCount(block, nbSeq);
        if (nbSeq == 0) {
            return block.toByteArray();
        }

        int[] llCodes = new int[nbSeq];
        int[] mlCodes = new int[nbSeq];
        int[] ofCodes = new int[nbSeq];
        int[] llCount = new int[LL_MAX_CODE + 1];
        int[] mlCount = new int[ML_MAX_CODE + 1];
        int[] ofCount = new int[OF_MAX_CODE + 1];
        for (int i = 0; i < nbSeq; i++) {
            llCodes[i] = litLengthCode(seqLitLength[i]);
            mlCodes[i] = matchLengthCode(seqMatchLength[i]);
            ofCodes[i] = 31 - Integer.numberOfLeadingZeros(seqOffBase[i]);
            llCount[llCodes[i]]++;
            mlCount[mlCodes[i]]++;
            ofCount[ofCodes[i]]++;
        }
        ByteArrayOutputStream tables = new ByteArrayOutputStream();
        Choice ll = choose(llCount, LL_MAX_CODE, LL_MAX_LOG, LL_PREDEFINED, llPrevious);
        Choice of = choose(ofCount, OF_MAX_CODE, OF_MAX_LOG, OF_PREDEFINED, ofPrevious);
        Choice ml = choose(mlCount, ML_MAX_CODE, ML_MAX_LOG, ML_PREDEFINED, mlPrevious);
        block.write((ll.mode << 6) | (of.mode << 4) | (ml.mode << 2));
        for (Choice c : new Choice[] {ll, of, ml}) {
            if (c.description != null) {
                block.write(c.description, 0, c.description.length);
            }
        }
        byte[] bitstream = encodeSequences(llCodes, mlCodes, ofCodes, ll, of, ml);
        block.write(bitstream, 0, bitstream.length);
        llPrevious = ll.table;
        ofPrevious = of.table;
        mlPrevious = ml.table;
        return block.toByteArray();
    }

    private static final int MODE_PREDEFINED = 0;
    private static final int MODE_RLE = 1;
    private static final int MODE_COMPRESSED = 2;
    private static final int MODE_REPEAT = 3;

    /** How one of the three sequence fields is coded: its mode, table (null for RLE), and description. */
    private record Choice(int mode, ZstdFseEncoder.CTable table, byte[] description) {
    }

    /**
     * The cheapest way to code a field's codes: RLE if only one appears; otherwise the predefined table,
     * the decoder's current table ("repeat"), or a table built from these counts, by estimated size.
     */
    private Choice choose(int[] count, int maxCode, int maxLog, ZstdFseEncoder.CTable predefined,
                          ZstdFseEncoder.CTable previous) {
        int maxSymbol = 0;
        int largest = 0;
        for (int s = 0; s <= maxCode; s++) {
            if (count[s] != 0) {
                maxSymbol = s;
                largest = Math.max(largest, count[s]);
            }
        }
        if (largest == nbSeq) {
            return new Choice(MODE_RLE, null, new byte[] {(byte) maxSymbol});
        }
        double best = Double.MAX_VALUE;
        Choice choice = null;
        if (predefined.covers(count, maxSymbol)) {
            best = predefined.cost(count, maxSymbol);
            choice = new Choice(MODE_PREDEFINED, predefined, null);
        }
        if (anyCompressedBlock && previous != null && previous != predefined && previous.covers(count, maxSymbol)) {
            double cost = previous.cost(count, maxSymbol);
            if (cost < best) {
                best = cost;
                choice = new Choice(MODE_REPEAT, previous, null);
            }
        }
        int tableLog = ZstdFseEncoder.optimalTableLog(maxLog, nbSeq, maxSymbol);
        short[] norm = ZstdFseEncoder.normalize(count, maxSymbol, nbSeq, tableLog);
        ZstdFseEncoder.CTable table = new ZstdFseEncoder.CTable(norm, tableLog);
        ByteArrayOutputStream description = new ByteArrayOutputStream();
        ZstdFseEncoder.writeNCount(description, norm, maxSymbol, tableLog);
        double cost = 8.0 * description.size() + table.cost(count, maxSymbol);
        if (cost < best) {
            choice = new Choice(MODE_COMPRESSED, table, description.toByteArray());
        }
        return choice;
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

    // ---- sequence bitstream --------------------------------------------------------------------------

    private byte[] encodeSequences(int[] llCodes, int[] mlCodes, int[] ofCodes, Choice ll, Choice of, Choice ml) {
        ZstdBitWriter bits = new ZstdBitWriter(nbSeq * 8 + 64);
        int last = nbSeq - 1;
        // The last sequence first: its states use the smallest-state initialization. An RLE field has
        // no state and writes no bits for it.
        long mlState = ml.table == null ? 0 : ml.table.initState(mlCodes[last]);
        long ofState = of.table == null ? 0 : of.table.initState(ofCodes[last]);
        long llState = ll.table == null ? 0 : ll.table.initState(llCodes[last]);
        addExtras(bits, last, llCodes[last], mlCodes[last], ofCodes[last]);

        for (int i = last - 1; i >= 0; i--) {
            int ofCode = ofCodes[i];
            int mlCode = mlCodes[i];
            int llCode = llCodes[i];
            if (of.table != null) {
                ofState = of.table.encode(bits, ofState, ofCode);
            }
            if (ml.table != null) {
                mlState = ml.table.encode(bits, mlState, mlCode);
            }
            if (ll.table != null) {
                llState = ll.table.encode(bits, llState, llCode);
            }
            bits.flush();
            addExtras(bits, i, llCode, mlCode, ofCode);
        }

        if (ml.table != null) {
            ml.table.flushState(bits, mlState);
        }
        if (of.table != null) {
            of.table.flushState(bits, ofState);
        }
        if (ll.table != null) {
            ll.table.flushState(bits, llState);
        }
        return bits.close();
    }

    /** A sequence's extra bits: literal length, match length, then offset, as the decoder reads them backwards. */
    private void addExtras(ZstdBitWriter bits, int i, int llCode, int mlCode, int ofCode) {
        bits.addBits(seqLitLength[i] - LL_BASE[llCode], LL_BITS[llCode]);
        bits.addBits(seqMatchLength[i] - ML_BASE[mlCode], ML_BITS[mlCode]);
        bits.flush();
        bits.addBits(seqOffBase[i] - (1L << ofCode), ofCode);
        bits.flush();
    }

    private static int litLengthCode(int litLength) {
        if (litLength < 16) {
            return litLength;
        }
        return codeFor(litLength, LL_BASE);
    }

    private static int matchLengthCode(int matchLength) {
        if (matchLength < 35) {
            return matchLength - 3;
        }
        return codeFor(matchLength, ML_BASE);
    }

    /** The sequence code for {@code value}: the largest index with {@code base[code] <= value}. */
    private static int codeFor(int value, int[] base) {
        int code = base.length - 1;
        while (base[code] > value) {
            code--;
        }
        return code;
    }

    // ---- match finding ---------------------------------------------------------------------------

    /**
     * Parses {@code src[start..end)} into sequences and literals, as libzstd's lazy strategies do: at each
     * position the repeat offset is tried, then the hash chain; with lazy evaluation, the next one (or two)
     * positions are tried too, and a better match there wins. Matches may start before the block (within
     * the window) but end inside it.
     */
    private void findSequences(int start, int end) {
        nbSeq = 0;
        literalCount = 0;
        int anchor = start;
        int ip = start == 0 ? 1 : start; // the first byte has nothing to match
        int ilimit = end - TAIL;
        int lazy = p.lazy();
        while (ip < ilimit) {
            int matchLength = 0;
            int offset = 0;
            int matchStart = ip;

            // The most recent offset, one position ahead (as libzstd tries it first).
            int r0 = rep[0];
            if (ip + 1 - r0 >= 0 && ip + 1 - r0 < ip + 1 && read32(ip + 1) == read32(ip + 1 - r0)) {
                matchLength = 4 + count(ip + 1 + 4, ip + 1 + 4 - r0, end);
                offset = r0;
                matchStart = ip + 1;
            }
            int found = search(ip, end);
            if (foundLength > matchLength) {
                matchLength = foundLength;
                offset = found;
                matchStart = ip;
            }
            if (matchLength < p.minMatch()) {
                ip += ((ip - anchor) >> SEARCH_STRENGTH) + 1;
                continue;
            }

            // Lazy evaluation: a better match one or two positions on wins.
            if (lazy > 0) {
                int depth = 0;
                while (ip < ilimit && depth < lazy) {
                    ip++;
                    depth++;
                    int r = rep[0];
                    if (offset != r && ip - r >= 0 && read32(ip) == read32(ip - r)) {
                        int mlRep = 4 + count(ip + 4, ip + 4 - r, end);
                        int gain2 = mlRep * (depth == 1 ? 3 : 4);
                        int gain1 = matchLength * (depth == 1 ? 3 : 4) - highbit(offset + 3) + 1;
                        if (mlRep >= p.minMatch() && gain2 > gain1) {
                            matchLength = mlRep;
                            offset = r;
                            matchStart = ip;
                        }
                    }
                    int candidate = search(ip, end);
                    if (foundLength >= p.minMatch()) {
                        int gain2 = foundLength * 4 - highbit(candidate + 3);
                        int gain1 = matchLength * 4 - highbit(offset + 3) + (depth == 1 ? 4 : 7);
                        if (gain2 > gain1) {
                            matchLength = foundLength;
                            offset = candidate;
                            matchStart = ip;
                            depth = 0; // look further on from the better match
                        }
                    }
                }
            }

            // Catch up: extend the match backwards over equal literals.
            while (matchStart > anchor && matchStart - offset > 0
                    && src[matchStart - 1] == src[matchStart - 1 - offset]) {
                matchStart--;
                matchLength++;
            }
            storeSequence(anchor, matchStart - anchor, offset, matchLength);
            ip = matchStart + matchLength;
            anchor = ip;

            // A match at the second repeat offset right away needs no literals.
            while (ip < ilimit) {
                int r1 = rep[1];
                if (ip - r1 < 0 || read32(ip) != read32(ip - r1)) {
                    break;
                }
                int length = 4 + count(ip + 4, ip + 4 - r1, end);
                storeSequence(anchor, 0, r1, length);
                ip += length;
                anchor = ip;
            }
        }
        // The rest of the block is literals.
        appendLiterals(anchor, end - anchor);
    }

    private int foundLength; // the length of the match the last search() returned

    /**
     * The best match for position {@code ip} along its hash chain, at most {@code searchDepth} entries deep
     * and within the window; its offset is returned and its length left in {@link #foundLength} (0 for none).
     * Positions up to {@code ip} are inserted into the chain first.
     */
    private int search(int ip, int end) {
        insertUpTo(ip);
        int h = hash(ip);
        int candidate = head[h];
        if (chain != null) {
            chain[ip & chainMask] = candidate;
        }
        head[h] = ip;
        nextToUpdate = ip + 1;

        int lowLimit = Math.max(0, ip - windowSize + 1);
        int chainLimit = chain == null ? ip : Math.max(lowLimit, ip - chainMask);
        int bestLength = 0;
        int bestOffset = 0;
        int target = p.targetLength();
        int limit = end - ip;
        for (int depth = p.searchDepth(); depth > 0 && candidate >= lowLimit; depth--) {
            if (src[candidate + bestLength] == src[ip + bestLength] && read32(candidate) == read32(ip)) {
                int length = 4 + count(ip + 4, candidate + 4, end);
                if (length > bestLength) {
                    bestLength = length;
                    bestOffset = ip - candidate;
                    if (length >= target || length == limit) {
                        break;
                    }
                }
            }
            if (chain == null || candidate <= chainLimit) {
                break;
            }
            candidate = chain[candidate & chainMask];
        }
        foundLength = bestLength;
        return bestOffset;
    }

    /** Inserts every position from {@code nextToUpdate} up to (not including) {@code target}. */
    private void insertUpTo(int target) {
        int last = Math.min(target, n - 4);
        for (int pos = nextToUpdate; pos < last; pos++) {
            int h = hash(pos);
            if (chain != null) {
                chain[pos & chainMask] = head[h];
            }
            head[h] = pos;
        }
        nextToUpdate = Math.max(nextToUpdate, target);
    }

    /**
     * Stores one sequence (its literals from {@code anchor}, then a match of {@code length} at distance
     * {@code offset}), coding the offset as a repeat code when it is one and updating the repeat offsets
     * exactly as the decoder will (RFC 8878 section 3.1.1.5).
     */
    private void storeSequence(int anchor, int litLength, int offset, int length) {
        appendLiterals(anchor, litLength);
        int offBase;
        if (litLength > 0) {
            offBase = offset == rep[0] ? 1 : offset == rep[1] ? 2 : offset == rep[2] ? 3 : offset + 3;
        } else {
            offBase = offset == rep[1] ? 1 : offset == rep[2] ? 2 : offset == rep[0] - 1 ? 3 : offset + 3;
        }
        // The decoder's update (ZstdDecoder.resolveOffset).
        if (offBase > 3) {
            rep[2] = rep[1];
            rep[1] = rep[0];
            rep[0] = offset;
        } else {
            int index = litLength == 0 ? offBase + 1 : offBase;
            if (index == 2) {
                rep[1] = rep[0];
                rep[0] = offset;
            } else if (index >= 3) {
                rep[2] = rep[1];
                rep[1] = rep[0];
                rep[0] = offset;
            }
        }
        if (nbSeq == seqLitLength.length) {
            int grown = nbSeq * 2;
            seqLitLength = Arrays.copyOf(seqLitLength, grown);
            seqMatchLength = Arrays.copyOf(seqMatchLength, grown);
            seqOffBase = Arrays.copyOf(seqOffBase, grown);
        }
        seqLitLength[nbSeq] = litLength;
        seqMatchLength[nbSeq] = length;
        seqOffBase[nbSeq] = offBase;
        nbSeq++;
    }

    private void appendLiterals(int from, int length) {
        System.arraycopy(src, from, literals, literalCount, length);
        literalCount += length;
    }

    private int hash(int pos) {
        return (read32(pos) * 0x9E3779B1) >>> hashShift;
    }

    private int read32(int pos) {
        return (int) INT_LE.get(src, pos);
    }

    /** How many bytes from {@code a} equal those from {@code b} (with {@code b < a}), stopping at {@code end}. */
    private int count(int a, int b, int end) {
        int start = a;
        while (a + 8 <= end) {
            long diff = (long) LONG_LE.get(src, a) ^ (long) LONG_LE.get(src, b);
            if (diff != 0) {
                return a - start + (Long.numberOfTrailingZeros(diff) >>> 3);
            }
            a += 8;
            b += 8;
        }
        while (a < end && src[a] == src[b]) {
            a++;
            b++;
        }
        return a - start;
    }

    private static int highbit(int v) {
        return 31 - Integer.numberOfLeadingZeros(v);
    }

    private static void writeLe(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xff));
        }
    }
}
