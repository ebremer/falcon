package com.ebremer.falcon.core.compress.sz;

/**
 * SZ 2.1.12's Huffman coder of quantization codes, the encoding side of {@link SzHuffman}, as
 * {@code Huffman.c} builds it ({@code createHuffmanTree}, {@code init}, {@code build_code}, {@code encode},
 * {@code convert_HuffTree_to_bytes_anyStates}). The tree depends on the order libSZ meets equal frequencies,
 * so its node pool, its binary heap and its merges are libSZ's step for step: leaves enter the heap in code
 * order, and a merge's node takes the second node removed as its left child and the first as its right (the
 * MSVC build evaluates {@code new_node(.., qremove(), qremove())}'s arguments right to left).
 */
final class SzHuffmanEncoder {

    private final int stateNum;
    // The node pool, in creation order.
    private final long[] freq;
    private final int[] symbol;
    private final boolean[] leaf;
    private final int[] left;
    private final int[] right;
    private int nodes;
    // The 1-based heap of node indexes (qq), and its end (qend).
    private final int[] heap;
    private int qend = 1;
    // Each symbol's code: its bits in the top of a long, and their count.
    private final long[] code;
    private final int[] length;
    private final boolean[] coded;
    private int root = -1;

    /**
     * Builds the tree of {@code count} codes from {@code s}, each below {@code 2 * stateNum}
     * ({@code createHuffmanTree(stateNum)} then {@code init}).
     */
    SzHuffmanEncoder(int stateNum, int[] s, int offset, int count) {
        this.stateNum = stateNum;
        int allNodes = 2 * stateNum;
        long[] counts = new long[allNodes];
        for (int i = 0; i < count; i++) {
            counts[s[offset + i]]++;
        }
        int distinct = 0;
        for (long c : counts) {
            distinct += c != 0 ? 1 : 0;
        }
        int poolSize = Math.max(1, 2 * distinct);
        freq = new long[poolSize];
        symbol = new int[poolSize];
        leaf = new boolean[poolSize];
        left = new int[poolSize];
        right = new int[poolSize];
        heap = new int[poolSize + 2];
        code = new long[allNodes];
        length = new int[allNodes];
        coded = new boolean[allNodes];
        for (int i = 0; i < allNodes; i++) {
            if (counts[i] != 0) {
                qinsert(newLeaf(counts[i], i));
            }
        }
        while (qend > 2) {
            int b = qremove(); // evaluated first: the right child
            int a = qremove();
            qinsert(newInternal(a, b));
        }
        if (qend > 1) {
            root = heap[1];
            buildCode(root, 0, 0);
        }
    }

    private int newLeaf(long f, int c) {
        int n = nodes++;
        freq[n] = f;
        symbol[n] = c;
        leaf[n] = true;
        left[n] = -1;
        right[n] = -1;
        return n;
    }

    private int newInternal(int a, int b) {
        int n = nodes++;
        freq[n] = freq[a] + freq[b];
        left[n] = a;
        right[n] = b;
        return n;
    }

    /** {@code qinsert}: a binary heap on frequency that stops at the first parent not larger. */
    private void qinsert(int n) {
        int i = qend++;
        int j;
        while ((j = i >> 1) != 0) {
            if (freq[heap[j]] <= freq[n]) {
                break;
            }
            heap[i] = heap[j];
            i = j;
        }
        heap[i] = n;
    }

    /** {@code qremove}. */
    private int qremove() {
        int i = 1;
        int n = heap[i];
        qend--;
        heap[i] = heap[qend];
        int l;
        while ((l = i << 1) < qend) {
            if (l + 1 < qend && freq[heap[l + 1]] < freq[heap[l]]) {
                l++;
            }
            if (freq[heap[i]] > freq[heap[l]]) {
                int p = heap[i];
                heap[i] = heap[l];
                heap[l] = p;
                i = l;
            } else {
                break;
            }
        }
        return n;
    }

    /** {@code build_code}, for codes of at most 64 bits (longer ones need more values than an array holds). */
    private void buildCode(int n, int len, long out) {
        if (leaf[n]) {
            // out1 << (64 - len): x86-64 shifts by the count mod 64, so a lone root's empty code stays 0
            code[symbol[n]] = out << (64 - len);
            length[symbol[n]] = len;
            coded[symbol[n]] = true;
            return;
        }
        if (len >= 64) {
            throw new IllegalStateException("SZ Huffman code longer than 64 bits");
        }
        buildCode(left[n], len + 1, out << 1);
        buildCode(right[n], len + 1, out << 1 | 1);
    }

    /** The tree's node count as libSZ records it: twice the coded symbols below {@code stateNum}, less one. */
    int nodeCount() {
        int n = 0;
        for (int i = 0; i < stateNum; i++) {
            n += coded[i] ? 1 : 0;
        }
        return n * 2 - 1;
    }

    /**
     * {@code convert_HuffTree_to_bytes_anyStates}: {@code endianness · L[n] · R[n] · C[n] · t[n]}, the nodes
     * numbered in preorder, children as 1, 2, or 4 little-endian bytes by the node count.
     */
    byte[] treeBytes() {
        int nodeCount = nodeCount();
        int width = nodeCount <= 256 ? 1 : nodeCount <= 65536 ? 2 : 4;
        int[] l = new int[nodeCount];
        int[] r = new int[nodeCount];
        int[] c = new int[nodeCount];
        boolean[] t = new boolean[nodeCount];
        int[] next = {0};
        pad(l, r, c, t, 0, root, next);
        byte[] out = new byte[1 + nodeCount * (2 * width + 4 + 1)];
        out[0] = 0; // sysEndianType: little-endian
        int at = 1;
        for (int[] side : new int[][] {l, r}) {
            for (int i = 0; i < nodeCount; i++) {
                for (int b = 0; b < width; b++) {
                    out[at++] = (byte) (side[i] >>> (8 * b));
                }
            }
        }
        for (int i = 0; i < nodeCount; i++) {
            for (int b = 0; b < 4; b++) {
                out[at++] = (byte) (c[i] >>> (8 * b));
            }
        }
        for (int i = 0; i < nodeCount; i++) {
            out[at++] = (byte) (t[i] ? 1 : 0);
        }
        return out;
    }

    /** {@code pad_tree_*}: an internal node's symbol is the pool's zero. */
    private void pad(int[] l, int[] r, int[] c, boolean[] t, int i, int n, int[] next) {
        c[i] = leaf[n] ? symbol[n] : 0;
        t[i] = leaf[n];
        if (left[n] >= 0) {
            l[i] = ++next[0];
            pad(l, r, c, t, next[0], left[n], next);
        }
        if (right[n] >= 0) {
            r[i] = ++next[0];
            pad(l, r, c, t, next[0], right[n], next);
        }
    }

    /** {@code encode}: each code's bits, most significant first, the last byte padded with zeros. */
    void encode(int[] s, int offset, int count, SzBytes out) {
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < count; i++) {
            int sym = s[offset + i];
            long c = code[sym];
            int len = length[sym];
            for (int k = 0; k < len; k++) {
                acc = acc << 1 | (int) ((c >>> (63 - k)) & 1);
                if (++bits == 8) {
                    out.u8(acc);
                    acc = 0;
                    bits = 0;
                }
            }
        }
        if (bits != 0) {
            out.u8(acc << (8 - bits));
        }
    }

    /** The longest code's length ({@code encode_withTree_MSST19}'s {@code maxBits}). */
    int maxBits() {
        int max = 0;
        for (int i = 0; i < stateNum; i++) {
            if (coded[i]) {
                max = Math.max(max, length[i]);
            }
        }
        return max;
    }

    /**
     * {@code encode_withTree}: {@code nodeCount (4) · intervals (4) · tree · codes}, the counts big-endian.
     *
     * @return the encoder, for {@link #maxBits()}
     */
    static SzHuffmanEncoder encodeWithTree(int stateNum, int[] s, int count, SzBytes out) {
        SzHuffmanEncoder tree = new SzHuffmanEncoder(stateNum, s, 0, count);
        out.be32(tree.nodeCount());
        out.be32(stateNum / 2);
        out.bytes(tree.treeBytes());
        tree.encode(s, 0, count, out);
        return tree;
    }
}
