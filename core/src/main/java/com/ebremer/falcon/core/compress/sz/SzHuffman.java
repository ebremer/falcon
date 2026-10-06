package com.ebremer.falcon.core.compress.sz;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;

/**
 * SZ's Huffman tree of quantization codes, as SZ 2.1.12's {@code Huffman.c} stores and decodes it. A tree
 * of {@code nodeCount} nodes is {@code endianness (1) · L[n] · R[n] · C[n] (4 each) · t[n] (1)}, where
 * {@code L} and {@code R} are child indexes of one byte (up to 256 nodes), two, or four; index 0 is the
 * root, so 0 as a child means "none". {@code t} marks the leaves, {@code C} their codes. The codes follow
 * MSB first.
 */
final class SzHuffman {

    private final int[] left;
    private final int[] right;
    private final int[] code;
    private final boolean[] leaf;
    /** The tree's serialized size, from its {@code endianness} byte to its last {@code t}. */
    final int size;

    private SzHuffman(int[] left, int[] right, int[] code, boolean[] leaf, int size) {
        this.left = left;
        this.right = right;
        this.code = code;
        this.leaf = leaf;
        this.size = size;
    }

    /**
     * Rebuilds a tree of {@code nodeCount} nodes stored at {@code at}, as
     * {@code reconstruct_HuffTree_from_bytes_anyStates} does.
     */
    static SzHuffman read(SzBuffer in, int at, long nodeCount) {
        if (nodeCount < 1 || nodeCount > (in.length() - (long) at) / 6) {
            throw new CompressionFormatException("SZ Huffman tree of " + nodeCount + " nodes does not fit its stream");
        }
        int n = (int) nodeCount;
        int width = n <= 256 ? 1 : n <= 65536 ? 2 : 4;
        if (in.u8(at) != 0) {
            // A tree written on a big-endian machine; hdf5plugin's builds are all little-endian.
            throw new UnsupportedCompressionException("SZ stream written by a big-endian machine");
        }
        int size = 1 + n * (2 * width + 4 + 1);
        in.require(at, size);
        int[] left = new int[n];
        int[] right = new int[n];
        int[] code = new int[n];
        boolean[] leaf = new boolean[n];
        int l = at + 1;
        int r = l + n * width;
        int c = r + n * width;
        int t = c + 4 * n;
        for (int i = 0; i < n; i++) {
            left[i] = index(in, l + i * width, width, n);
            right[i] = index(in, r + i * width, width, n);
            code[i] = in.le32(c + 4 * i);
            leaf[i] = in.u8(t + i) != 0;
        }
        if (width > 1) {
            // Above 256 nodes the root is a fresh internal node: new_node2(huffmanTree, 0, 0).
            leaf[0] = false;
            code[0] = 0;
        }
        return new SzHuffman(left, right, code, leaf, size);
    }

    private static int index(SzBuffer in, int at, int width, int n) {
        long v = switch (width) {
            case 1 -> in.u8(at);
            case 2 -> in.le16(at);
            default -> in.le32(at) & 0xFFFFFFFFL;
        };
        if (v >= n) {
            throw new CompressionFormatException("SZ Huffman node index " + v + " past its " + n + " nodes");
        }
        return (int) v;
    }

    /**
     * Decodes {@code count} codes from the bits at {@code at} into {@code out}, as {@code decode} (and,
     * code for code, {@code decode_MSST19}'s table-driven version) does.
     */
    void decode(SzBuffer in, int at, int count, int[] out) {
        if (leaf[0]) { // a one-node tree: every value has the same code
            java.util.Arrays.fill(out, 0, count, code[0]);
            return;
        }
        long bits = Math.max(0, (long) (in.length() - at)) * 8;
        long i = 0;
        int node = 0;
        for (int found = 0; found < count; i++) {
            if (i >= bits) {
                throw new CompressionFormatException("SZ Huffman codes end after " + found + " of " + count);
            }
            int bit = (in.u8(at + (int) (i >>> 3)) >>> (7 - (int) (i & 7))) & 1;
            node = bit == 0 ? left[node] : right[node];
            if (node == 0) {
                throw new CompressionFormatException("SZ Huffman code leads to no node");
            }
            if (leaf[node]) {
                out[found++] = code[node];
                node = 0;
            }
        }
    }

    /**
     * Decodes a type array as {@code decode_withTree} does: {@code nodeCount (4, big-endian) · intervals (4)
     * · tree · codes}.
     */
    static int[] decodeWithTree(SzBuffer in, int at, int count) {
        SzHuffman tree = read(in, at + 8, in.be32(at) & 0xFFFFFFFFL);
        int[] out = new int[count];
        tree.decode(in, at + 8 + tree.size, count, out);
        return out;
    }
}
