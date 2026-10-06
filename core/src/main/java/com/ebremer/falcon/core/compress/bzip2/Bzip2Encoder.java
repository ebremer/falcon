package com.ebremer.falcon.core.compress.bzip2;

import java.util.Arrays;

/**
 * bzip2 compression, byte for byte libbzip2 1.0.8's: {@code BZ2_bzBuffToBuffCompress(.., blockSize100k, 0, 0)},
 * the default work factor (30), as the HDF5 bzip2 filter ({@code H5Zbzip2.c}) and Python's
 * {@code bz2.compress} call it. A port of {@code bzlib.c} (the run-length coding of 4 to 255 equal bytes, and
 * how input fills blocks of {@code 100000 * blockSize100k - 19} bytes), {@code blocksort.c}
 * ({@link Bzip2BlockSort}), {@code compress.c} (move-to-front coding with runs, 2 to 6 Huffman tables refined
 * over 4 passes, each group of 50 symbols coded with the cheapest) and {@code huffman.c} (code lengths of at
 * most 17 bits). See {@link Bzip2Decoder} for the format.
 */
public final class Bzip2Encoder {

    private static final int GROUP_SIZE = 50;
    private static final int MAX_GROUPS = 6;
    private static final int MAX_ALPHA_SIZE = 258;
    private static final int ITERATIONS = 4;
    private static final int MAX_CODE_LEN = 17;
    private static final int LESSER_ICOST = 0;
    private static final int GREATER_ICOST = 15;
    private static final int RUNA = 0;
    private static final int RUNB = 1;

    private Bzip2Encoder() {
    }

    /**
     * Compresses {@code data} into one bzip2 stream.
     *
     * @param data          the bytes to compress
     * @param blockSize100k the block size in units of 100000 bytes, 1 to 9 (bzip2's {@code -1} to {@code -9})
     * @return the bzip2 stream
     * @throws IllegalArgumentException if the block size is not 1 to 9
     */
    public static byte[] compress(byte[] data, int blockSize100k) {
        return compress(data, 0, data.length, blockSize100k);
    }

    /**
     * Compresses {@code length} bytes of {@code data} from {@code offset} into one bzip2 stream.
     *
     * @param data          the bytes
     * @param offset        where the bytes to compress start
     * @param length        how many there are
     * @param blockSize100k the block size in units of 100000 bytes, 1 to 9
     * @return the bzip2 stream
     * @throws IllegalArgumentException if the block size is not 1 to 9, or the range is not in {@code data}
     */
    public static byte[] compress(byte[] data, int offset, int length, int blockSize100k) {
        return encode(data, offset, length, blockSize100k, false);
    }

    /**
     * Compresses as {@link #compress(byte[], int)}, but with every block randomised as bzip2 before 0.9.5
     * wrote them (its {@code randomiseBlock}), so tests can check {@link Bzip2Decoder} reads such blocks.
     */
    static byte[] compressRandomised(byte[] data, int blockSize100k) {
        return encode(data, 0, data.length, blockSize100k, true);
    }

    private static byte[] encode(byte[] data, int offset, int length, int blockSize100k, boolean randomise) {
        if (blockSize100k < 1 || blockSize100k > 9) {
            throw new IllegalArgumentException("bzip2 block size must be 1 to 9, not " + blockSize100k);
        }
        if (offset < 0 || length < 0 || length > data.length - offset) {
            throw new IllegalArgumentException("invalid range " + offset + "+" + length + " of " + data.length);
        }
        return new State(blockSize100k, length, randomise).compress(data, offset, offset + length);
    }

    /** One stream's compression ({@code EState}). */
    private static final class State {

        private final int blockSize100k;
        private final int nblockMax;
        private final boolean randomise;
        private final byte[] block;
        private final boolean[] inUse = new boolean[256];
        private int nblock;
        private int blockCrc;
        private int combinedCrc;
        private int blockNo;
        // The pending run of the run-length coding (state_in_ch, state_in_len); 256 is none.
        private int inCh = 256;
        private int inLen;

        private final BitWriter out;

        // Per block.
        private int[] ptr = new int[0];
        private char[] mtfv = new char[0];
        private int nMtf;
        private int nInUse;
        private final int[] unseqToSeq = new int[256];
        private final int[] mtfFreq = new int[MAX_ALPHA_SIZE];
        private final int[][] len = new int[MAX_GROUPS][MAX_ALPHA_SIZE];
        private final int[][] code = new int[MAX_GROUPS][MAX_ALPHA_SIZE];
        private final int[][] rfreq = new int[MAX_GROUPS][MAX_ALPHA_SIZE];

        State(int blockSize100k, int length, boolean randomise) {
            this.blockSize100k = blockSize100k;
            this.nblockMax = 100000 * blockSize100k - 19;
            this.randomise = randomise;
            // The run-length coding stores at most 5 bytes per 4, and a block ends near nblockMax.
            long cap = Math.min(100000L * blockSize100k, 5L * length / 4 + 8);
            this.block = new byte[(int) cap + Bzip2BlockSort.OVERSHOOT];
            this.out = new BitWriter(length / 2 + 64);
        }

        /** {@code BZ2_bzCompress(.., BZ_FINISH)} over all the input. */
        byte[] compress(byte[] data, int pos, int end) {
            prepareNewBlock();
            while (true) {
                while (nblock < nblockMax && pos < end) {
                    addChar(data[pos++] & 0xff);
                }
                if (pos == end) {
                    flushRun();
                    compressBlock(true);
                    return out.toByteArray();
                }
                compressBlock(false); // full: the pending run carries into the next block
                prepareNewBlock();
            }
        }

        private void prepareNewBlock() {
            nblock = 0;
            blockCrc = 0xffffffff;
            Arrays.fill(inUse, false);
            blockNo++;
        }

        /** {@code ADD_CHAR_TO_BLOCK}. */
        private void addChar(int ch) {
            if (ch != inCh && inLen == 1) {
                blockCrc = Bzip2Tables.updateCrc(blockCrc, inCh);
                inUse[inCh] = true;
                block[nblock++] = (byte) inCh;
                inCh = ch;
            } else if (ch != inCh || inLen == 255) {
                if (inCh < 256) {
                    addPairToBlock();
                }
                inCh = ch;
                inLen = 1;
            } else {
                inLen++;
            }
        }

        /** {@code add_pair_to_block}: the pending run, its bytes, and past 3 a count of the rest. */
        private void addPairToBlock() {
            for (int i = 0; i < inLen; i++) {
                blockCrc = Bzip2Tables.updateCrc(blockCrc, inCh);
            }
            inUse[inCh] = true;
            int copies = Math.min(inLen, 4);
            for (int i = 0; i < copies; i++) {
                block[nblock++] = (byte) inCh;
            }
            if (inLen >= 4) {
                inUse[inLen - 4] = true;
                block[nblock++] = (byte) (inLen - 4);
            }
        }

        private void flushRun() {
            if (inCh < 256) {
                addPairToBlock();
            }
            inCh = 256;
            inLen = 0;
        }

        /** {@code BZ2_compressBlock}. */
        private void compressBlock(boolean last) {
            int origPtr = 0;
            if (nblock > 0) {
                blockCrc = ~blockCrc;
                combinedCrc = Bzip2Tables.combine(combinedCrc, blockCrc);
                if (randomise) {
                    randomiseBlock();
                }
                if (ptr.length < nblock) {
                    ptr = new int[nblock];
                }
                origPtr = Bzip2BlockSort.sort(block, ptr, nblock);
            }
            if (blockNo == 1) {
                out.write(8, 'B');
                out.write(8, 'Z');
                out.write(8, 'h');
                out.write(8, '0' + blockSize100k);
            }
            if (nblock > 0) {
                out.write(24, 0x314159);
                out.write(24, 0x265359);
                out.write(16, blockCrc >>> 16);
                out.write(16, blockCrc & 0xffff);
                out.write(1, randomise ? 1 : 0);
                out.write(24, origPtr);
                generateMtfValues();
                sendMtfValues();
            }
            if (last) {
                out.write(24, 0x177245);
                out.write(24, 0x385090);
                out.write(16, combinedCrc >>> 16);
                out.write(16, combinedCrc & 0xffff);
                out.flush();
            }
        }

        /** bzip2 0.9.0's {@code randomiseBlock}: flips the low bit of the bytes {@code BZ2_rNums} picks. */
        private void randomiseBlock() {
            Arrays.fill(inUse, false);
            int rNToGo = 0;
            int rTPos = 0;
            for (int i = 0; i < nblock; i++) {
                if (rNToGo == 0) {
                    rNToGo = Bzip2Tables.R_NUMS[rTPos];
                    rTPos = (rTPos + 1) & 511;
                }
                rNToGo--;
                if (rNToGo == 1) {
                    block[i] ^= 1;
                }
                inUse[block[i] & 0xff] = true;
            }
        }

        /** {@code generateMTFValues}: move-to-front indices, runs of the front value as RUNA/RUNB. */
        private void generateMtfValues() {
            nInUse = 0;
            for (int i = 0; i < 256; i++) {
                if (inUse[i]) {
                    unseqToSeq[i] = nInUse++;
                }
            }
            int eob = nInUse + 1;
            Arrays.fill(mtfFreq, 0, eob + 1, 0);
            if (mtfv.length < nblock + 1) {
                mtfv = new char[nblock + 1];
            }
            int[] yy = new int[256];
            for (int i = 0; i < nInUse; i++) {
                yy[i] = i;
            }
            int wr = 0;
            int zPend = 0;
            for (int i = 0; i < nblock; i++) {
                int j = ptr[i] - 1;
                if (j < 0) {
                    j += nblock;
                }
                int llI = unseqToSeq[block[j] & 0xff];
                if (yy[0] == llI) {
                    zPend++;
                    continue;
                }
                if (zPend > 0) {
                    wr = writeRun(zPend, wr);
                    zPend = 0;
                }
                int rtmp = yy[1];
                yy[1] = yy[0];
                int k = 1;
                while (llI != rtmp) {
                    k++;
                    int rtmp2 = rtmp;
                    rtmp = yy[k];
                    yy[k] = rtmp2;
                }
                yy[0] = rtmp;
                mtfv[wr++] = (char) (k + 1);
                mtfFreq[k + 1]++;
            }
            if (zPend > 0) {
                wr = writeRun(zPend, wr);
            }
            mtfv[wr++] = (char) eob;
            mtfFreq[eob]++;
            nMtf = wr;
        }

        /** A run of {@code zPend} front values, in bijective base 2. */
        private int writeRun(int zPend, int wr) {
            zPend--;
            while (true) {
                if ((zPend & 1) != 0) {
                    mtfv[wr++] = RUNB;
                    mtfFreq[RUNB]++;
                } else {
                    mtfv[wr++] = RUNA;
                    mtfFreq[RUNA]++;
                }
                if (zPend < 2) {
                    return wr;
                }
                zPend = (zPend - 2) / 2;
            }
        }

        /** {@code sendMTFValues}: chooses and writes the Huffman tables, the selectors, and the symbols. */
        private void sendMtfValues() {
            int alphaSize = nInUse + 2;
            for (int t = 0; t < MAX_GROUPS; t++) {
                Arrays.fill(len[t], 0, alphaSize, GREATER_ICOST);
            }
            int nGroups = nMtf < 200 ? 2 : nMtf < 600 ? 3 : nMtf < 1200 ? 4 : nMtf < 2400 ? 5 : 6;

            // An initial set of tables, each covering a run of symbol values of about equal frequency.
            int nPart = nGroups;
            int remF = nMtf;
            int gs = 0;
            while (nPart > 0) {
                int tFreq = remF / nPart;
                int ge = gs - 1;
                int aFreq = 0;
                while (aFreq < tFreq && ge < alphaSize - 1) {
                    ge++;
                    aFreq += mtfFreq[ge];
                }
                if (ge > gs && nPart != nGroups && nPart != 1 && ((nGroups - nPart) % 2 == 1)) {
                    aFreq -= mtfFreq[ge];
                    ge--;
                }
                for (int v = 0; v < alphaSize; v++) {
                    len[nPart - 1][v] = v >= gs && v <= ge ? LESSER_ICOST : GREATER_ICOST;
                }
                nPart--;
                gs = ge + 1;
                remF -= aFreq;
            }

            // Refine the tables: give each group the cheapest, then rebuild each from its groups' symbols.
            int nSelectors = (nMtf + GROUP_SIZE - 1) / GROUP_SIZE;
            byte[] selector = new byte[nSelectors];
            int[] cost = new int[MAX_GROUPS];
            for (int iter = 0; iter < ITERATIONS; iter++) {
                for (int t = 0; t < nGroups; t++) {
                    Arrays.fill(rfreq[t], 0, alphaSize, 0);
                }
                int sel = 0;
                for (gs = 0; gs < nMtf; gs += GROUP_SIZE) {
                    int ge = Math.min(gs + GROUP_SIZE - 1, nMtf - 1);
                    Arrays.fill(cost, 0);
                    for (int i = gs; i <= ge; i++) {
                        int icv = mtfv[i];
                        for (int t = 0; t < nGroups; t++) {
                            cost[t] += len[t][icv];
                        }
                    }
                    int bc = 999999999;
                    int bt = -1;
                    for (int t = 0; t < nGroups; t++) {
                        if (cost[t] < bc) {
                            bc = cost[t];
                            bt = t;
                        }
                    }
                    selector[sel++] = (byte) bt;
                    for (int i = gs; i <= ge; i++) {
                        rfreq[bt][mtfv[i]]++;
                    }
                }
                for (int t = 0; t < nGroups; t++) {
                    makeCodeLengths(len[t], rfreq[t], alphaSize, MAX_CODE_LEN);
                }
            }

            // Move-to-front code the selectors.
            byte[] selectorMtf = new byte[nSelectors];
            int[] pos = new int[MAX_GROUPS];
            for (int i = 0; i < nGroups; i++) {
                pos[i] = i;
            }
            for (int i = 0; i < nSelectors; i++) {
                int llI = selector[i];
                int j = 0;
                int tmp = pos[j];
                while (llI != tmp) {
                    j++;
                    int tmp2 = tmp;
                    tmp = pos[j];
                    pos[j] = tmp2;
                }
                pos[0] = tmp;
                selectorMtf[i] = (byte) j;
            }

            // Assign the codes (BZ2_hbAssignCodes).
            for (int t = 0; t < nGroups; t++) {
                int minLen = 32;
                int maxLen = 0;
                for (int i = 0; i < alphaSize; i++) {
                    maxLen = Math.max(maxLen, len[t][i]);
                    minLen = Math.min(minLen, len[t][i]);
                }
                int vec = 0;
                for (int n = minLen; n <= maxLen; n++) {
                    for (int i = 0; i < alphaSize; i++) {
                        if (len[t][i] == n) {
                            code[t][i] = vec++;
                        }
                    }
                    vec <<= 1;
                }
            }

            // The symbol map.
            int ranges = 0;
            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    if (inUse[i * 16 + j]) {
                        ranges |= 0x8000 >>> i;
                    }
                }
            }
            out.write(16, ranges);
            for (int i = 0; i < 16; i++) {
                if ((ranges & (0x8000 >>> i)) != 0) {
                    int used = 0;
                    for (int j = 0; j < 16; j++) {
                        if (inUse[i * 16 + j]) {
                            used |= 0x8000 >>> j;
                        }
                    }
                    out.write(16, used);
                }
            }

            // The selectors.
            out.write(3, nGroups);
            out.write(15, nSelectors);
            for (int i = 0; i < nSelectors; i++) {
                for (int j = 0; j < selectorMtf[i]; j++) {
                    out.write(1, 1);
                }
                out.write(1, 0);
            }

            // The code lengths, delta coded.
            for (int t = 0; t < nGroups; t++) {
                int curr = len[t][0];
                out.write(5, curr);
                for (int i = 0; i < alphaSize; i++) {
                    while (curr < len[t][i]) {
                        out.write(2, 2);
                        curr++;
                    }
                    while (curr > len[t][i]) {
                        out.write(2, 3);
                        curr--;
                    }
                    out.write(1, 0);
                }
            }

            // The symbols.
            int sel = 0;
            for (gs = 0; gs < nMtf; gs += GROUP_SIZE) {
                int ge = Math.min(gs + GROUP_SIZE - 1, nMtf - 1);
                int[] l = len[selector[sel]];
                int[] c = code[selector[sel]];
                for (int i = gs; i <= ge; i++) {
                    out.write(l[mtfv[i]], c[mtfv[i]]);
                }
                sel++;
            }
        }
    }

    /**
     * {@code BZ2_hbMakeCodeLengths}: Huffman code lengths for {@code freq}, a node's depth packed below its
     * weight to break ties toward shallower trees; while a code is longer than {@code maxLen}, the
     * frequencies are halved and the tree built again.
     */
    private static void makeCodeLengths(int[] len, int[] freq, int alphaSize, int maxLen) {
        int[] heap = new int[MAX_ALPHA_SIZE + 2];
        int[] weight = new int[MAX_ALPHA_SIZE * 2];
        int[] parent = new int[MAX_ALPHA_SIZE * 2];
        for (int i = 0; i < alphaSize; i++) {
            weight[i + 1] = (freq[i] == 0 ? 1 : freq[i]) << 8;
        }
        while (true) {
            int nNodes = alphaSize;
            int nHeap = 0;
            heap[0] = 0;
            weight[0] = 0;
            parent[0] = -2;
            for (int i = 1; i <= alphaSize; i++) {
                parent[i] = -1;
                heap[++nHeap] = i;
                upHeap(heap, weight, nHeap);
            }
            while (nHeap > 1) {
                int n1 = heap[1];
                heap[1] = heap[nHeap--];
                downHeap(heap, weight, nHeap, 1);
                int n2 = heap[1];
                heap[1] = heap[nHeap--];
                downHeap(heap, weight, nHeap, 1);
                nNodes++;
                parent[n1] = nNodes;
                parent[n2] = nNodes;
                weight[nNodes] = ((weight[n1] & 0xffffff00) + (weight[n2] & 0xffffff00))
                        | (1 + Math.max(weight[n1] & 0xff, weight[n2] & 0xff));
                parent[nNodes] = -1;
                heap[++nHeap] = nNodes;
                upHeap(heap, weight, nHeap);
            }
            boolean tooLong = false;
            for (int i = 1; i <= alphaSize; i++) {
                int j = 0;
                int k = i;
                while (parent[k] >= 0) {
                    k = parent[k];
                    j++;
                }
                len[i - 1] = j;
                if (j > maxLen) {
                    tooLong = true;
                }
            }
            if (!tooLong) {
                return;
            }
            for (int i = 1; i <= alphaSize; i++) {
                int j = weight[i] >> 8;
                j = 1 + (j / 2);
                weight[i] = j << 8;
            }
        }
    }

    private static void upHeap(int[] heap, int[] weight, int z) {
        int zz = z;
        int tmp = heap[zz];
        while (weight[tmp] < weight[heap[zz >> 1]]) {
            heap[zz] = heap[zz >> 1];
            zz >>= 1;
        }
        heap[zz] = tmp;
    }

    private static void downHeap(int[] heap, int[] weight, int nHeap, int z) {
        int zz = z;
        int tmp = heap[zz];
        while (true) {
            int yy = zz << 1;
            if (yy > nHeap) {
                break;
            }
            if (yy < nHeap && weight[heap[yy + 1]] < weight[heap[yy]]) {
                yy++;
            }
            if (weight[tmp] < weight[heap[yy]]) {
                break;
            }
            heap[zz] = heap[yy];
            zz = yy;
        }
        heap[zz] = tmp;
    }

    /** Bits written most significant first, as libbzip2's {@code bsW} packs them. */
    private static final class BitWriter {

        private byte[] bytes;
        private int size;
        private long buffer;
        private int live;

        BitWriter(int capacity) {
            bytes = new byte[capacity];
        }

        /** Writes the low {@code n} (at most 24) bits of {@code v}. */
        void write(int n, int v) {
            buffer = (buffer << n) | (v & ((1L << n) - 1));
            live += n;
            while (live >= 8) {
                live -= 8;
                put((int) (buffer >>> live));
            }
        }

        /** Pads the last byte with zero bits. */
        void flush() {
            if (live > 0) {
                put((int) (buffer << (8 - live)));
                live = 0;
            }
        }

        private void put(int b) {
            if (size == bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(64, 2 * bytes.length));
            }
            bytes[size++] = (byte) b;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(bytes, size);
        }
    }
}
