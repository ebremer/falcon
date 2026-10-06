package com.ebremer.falcon.core.compress.zstd;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Writes a block's literals section (RFC&nbsp;8878 &sect;3.1.1.3.1): Huffman-compressed when that pays,
 * otherwise RLE (one repeated byte) or raw. The counterpart to the literals half of {@link ZstdDecoder}.
 *
 * <p>Codes are at most 11 bits, as the format allows. The tree is described by per-symbol weights,
 * FSE-compressed when that is smaller (and always when more than 128 are listed), otherwise listed
 * directly. The first 256 literals or fewer go in one stream; more go in four, as libzstd writes them.
 */
final class ZstdHuffmanEncoder {

    /** The longest code the format allows. */
    static final int MAX_BITS = 11;
    /** The largest accuracy log for FSE-compressed weights (RFC 8878 section 4.2.1.2). */
    private static final int WEIGHTS_MAX_TABLE_LOG = 6;
    /** At most this many literals go in a single stream. */
    private static final int SINGLE_STREAM_MAX = 255;

    private ZstdHuffmanEncoder() {
    }

    /**
     * Appends the literals section for {@code literals[0..n)} to {@code out}: Huffman-compressed if that
     * saves more than libzstd's minimum gain, else RLE if every byte is the same, else raw.
     */
    static void writeLiterals(ByteArrayOutputStream out, byte[] literals, int n) {
        if (n == 0) {
            out.write(0); // raw, no literals
            return;
        }
        int[] count = new int[256];
        int maxSymbol = 0;
        int distinct = 0;
        for (int i = 0; i < n; i++) {
            int s = literals[i] & 0xff;
            if (count[s]++ == 0) {
                distinct++;
            }
            maxSymbol = Math.max(maxSymbol, s);
        }
        if (distinct == 1) {
            writeRawOrRleHeader(out, 1, n);
            out.write(literals[0]);
            return;
        }
        if (n >= 64) {
            byte[] compressed = compress(literals, n, count, maxSymbol);
            int minGain = (n >> 6) + 2;
            if (compressed != null && compressed.length < n - minGain) {
                out.write(compressed, 0, compressed.length);
                return;
            }
        }
        writeRawOrRleHeader(out, 0, n);
        out.write(literals, 0, n);
    }

    /** The header of a raw ({@code type} 0) or RLE (1) literals section regenerating {@code n} bytes. */
    private static void writeRawOrRleHeader(ByteArrayOutputStream out, int type, int n) {
        if (n < 32) {
            out.write((n << 3) | type);
        } else if (n < 4096) {
            out.write(((n & 0xF) << 4) | 0x04 | type);
            out.write(n >>> 4);
        } else {
            out.write(((n & 0xF) << 4) | 0x0C | type);
            out.write((n >>> 4) & 0xff);
            out.write((n >>> 12) & 0xff);
        }
    }

    /** The whole compressed literals section (header, tree, streams), or null if it cannot be written. */
    private static byte[] compress(byte[] literals, int n, int[] count, int maxSymbol) {
        int[] lengths = codeLengths(count, maxSymbol);
        int maxBits = 0;
        for (int s = 0; s <= maxSymbol; s++) {
            maxBits = Math.max(maxBits, lengths[s]);
        }
        int[] codes = canonicalCodes(lengths, maxSymbol, maxBits);
        byte[] tree = describeTree(lengths, codes, maxSymbol, maxBits);
        if (tree == null) {
            return null;
        }

        boolean single = n <= SINGLE_STREAM_MAX;
        ByteArrayOutputStream streams = new ByteArrayOutputStream(n);
        if (single) {
            byte[] stream = encodeStream(literals, 0, n, codes, lengths);
            streams.write(stream, 0, stream.length);
        } else {
            int quarter = (n + 3) / 4;
            byte[][] parts = new byte[4][];
            for (int i = 0; i < 4; i++) {
                int from = Math.min(n, i * quarter);
                int to = i == 3 ? n : Math.min(n, from + quarter);
                parts[i] = encodeStream(literals, from, to, codes, lengths);
            }
            for (int i = 0; i < 3; i++) {
                if (parts[i].length > 0xFFFF) {
                    return null; // the jump table holds 16-bit sizes
                }
                streams.write(parts[i].length & 0xff);
                streams.write(parts[i].length >>> 8);
            }
            for (byte[] part : parts) {
                streams.write(part, 0, part.length);
            }
        }

        int compressedSize = tree.length + streams.size();
        int sizeFormat;
        int sizeBits;
        if (single) {
            if (compressedSize > 0x3FF) {
                return null;
            }
            sizeFormat = 0;
            sizeBits = 10;
        } else if (n <= 0x3FF && compressedSize <= 0x3FF) {
            sizeFormat = 1;
            sizeBits = 10;
        } else if (n <= 0x3FFF && compressedSize <= 0x3FFF) {
            sizeFormat = 2;
            sizeBits = 14;
        } else if (n <= 0x3FFFF && compressedSize <= 0x3FFFF) {
            sizeFormat = 3;
            sizeBits = 18;
        } else {
            return null;
        }
        int headerBytes = (4 + 2 * sizeBits + 7) / 8;
        long header = 2 | ((long) sizeFormat << 2) | ((long) n << 4) | ((long) compressedSize << (4 + sizeBits));
        ByteArrayOutputStream section = new ByteArrayOutputStream(headerBytes + compressedSize);
        for (int i = 0; i < headerBytes; i++) {
            section.write((int) (header >>> (8 * i)) & 0xff);
        }
        section.write(tree, 0, tree.length);
        byte[] s = streams.toByteArray();
        section.write(s, 0, s.length);
        return section.toByteArray();
    }

    /** One Huffman stream of {@code literals[from..to)}: written last-first, so a decoder reads them in order. */
    private static byte[] encodeStream(byte[] literals, int from, int to, int[] codes, int[] lengths) {
        ZstdBitWriter bits = new ZstdBitWriter((to - from) + 8);
        int i = to - 1;
        while (i >= from) {
            // At most 4 codes of 11 bits between flushes: 44 bits plus up to 7 pending.
            for (int k = 0; k < 4 && i >= from; k++, i--) {
                int s = literals[i] & 0xff;
                bits.addBits(codes[s], lengths[s]);
            }
            bits.flush();
        }
        return bits.close();
    }

    // ---- the code -----------------------------------------------------------------------------------

    /**
     * Huffman code lengths for {@code count[0..maxSymbol]} (at least two symbols present), at most
     * {@link #MAX_BITS}: a Huffman tree, rebuilt from flattened counts (each halved, at least 1) until no
     * code is too long. A full tree, so the lengths satisfy Kraft's equality, as the format requires.
     */
    static int[] codeLengths(int[] count, int maxSymbol) {
        int[] c = Arrays.copyOf(count, maxSymbol + 1);
        while (true) {
            int[] lengths = huffmanLengths(c, maxSymbol);
            int max = 0;
            for (int l : lengths) {
                max = Math.max(max, l);
            }
            if (max <= MAX_BITS) {
                return lengths;
            }
            for (int s = 0; s <= maxSymbol; s++) {
                if (c[s] != 0) {
                    c[s] = Math.max(1, c[s] >>> 1);
                }
            }
        }
    }

    private static int[] huffmanLengths(int[] count, int maxSymbol) {
        int symbols = maxSymbol + 1;
        // Nodes 0..symbols-1 are leaves; internal nodes follow. A node's weight breaks ties by index.
        long[] weight = new long[2 * symbols];
        int[] parent = new int[2 * symbols];
        PriorityQueue<Integer> queue = new PriorityQueue<>((a, b) -> weight[a] != weight[b]
                ? Long.compare(weight[a], weight[b]) : Integer.compare(a, b));
        for (int s = 0; s < symbols; s++) {
            if (count[s] != 0) {
                weight[s] = count[s];
                queue.add(s);
            }
        }
        int next = symbols;
        while (queue.size() > 1) {
            int a = queue.poll();
            int b = queue.poll();
            weight[next] = weight[a] + weight[b];
            parent[a] = next;
            parent[b] = next;
            queue.add(next++);
        }
        int root = queue.poll();
        int[] depth = new int[next];
        for (int node = next - 1; node >= 0; node--) {
            if (node != root && (node >= symbols || count[node] != 0)) {
                depth[node] = depth[parent[node]] + 1;
            }
        }
        return Arrays.copyOf(depth, symbols);
    }

    /**
     * The code of each symbol as the decoder lays out its table: by weight ascending (longest codes first),
     * then by symbol, each weight {@code w} taking {@code 2^(w-1)} consecutive entries.
     */
    private static int[] canonicalCodes(int[] lengths, int maxSymbol, int maxBits) {
        int[] rankCount = new int[maxBits + 2];
        for (int s = 0; s <= maxSymbol; s++) {
            if (lengths[s] > 0) {
                rankCount[maxBits + 1 - lengths[s]]++;
            }
        }
        int[] rankStart = new int[maxBits + 2];
        int start = 0;
        for (int w = 1; w <= maxBits; w++) {
            rankStart[w] = start;
            start += rankCount[w] << (w - 1);
        }
        int[] codes = new int[256];
        for (int s = 0; s <= maxSymbol; s++) {
            if (lengths[s] > 0) {
                int w = maxBits + 1 - lengths[s];
                codes[s] = rankStart[w] >>> (w - 1);
                rankStart[w] += 1 << (w - 1);
            }
        }
        return codes;
    }

    // ---- the tree description -------------------------------------------------------------------------

    /**
     * The tree description: the weights of symbols {@code 0..maxSymbol-1} (the last one's is implied),
     * FSE-compressed when smaller or when more than 128 are listed, else 4 bits each. Each candidate is read
     * back with the decoder's own {@link ZstdHuffman#readTable} and kept only if it describes exactly this
     * code. Returns null when neither form can be written.
     */
    private static byte[] describeTree(int[] lengths, int[] codes, int maxSymbol, int maxBits) {
        int listed = maxSymbol;
        int[] weights = new int[listed];
        for (int s = 0; s < listed; s++) {
            weights[s] = lengths[s] == 0 ? 0 : maxBits + 1 - lengths[s];
        }
        byte[] fse = fseWeights(weights, listed, maxBits);
        if (fse != null && fse.length < 128 && fse.length - 1 < (listed + 1) / 2 && describes(fse, lengths, codes, maxSymbol, maxBits)) {
            return fse;
        }
        if (listed <= 128) {
            byte[] direct = new byte[1 + (listed + 1) / 2];
            direct[0] = (byte) (127 + listed);
            for (int i = 0; i < listed; i++) {
                direct[1 + i / 2] |= (byte) (i % 2 == 0 ? weights[i] << 4 : weights[i]);
            }
            if (describes(direct, lengths, codes, maxSymbol, maxBits)) {
                return direct;
            }
        }
        return null;
    }

    /** The FSE-compressed weights with their 1-byte size header, or null if they do not compress. */
    private static byte[] fseWeights(int[] weights, int listed, int maxBits) {
        if (listed < 3) {
            return null;
        }
        int[] count = new int[maxBits + 1];
        int maxWeight = 0;
        int largest = 0;
        for (int i = 0; i < listed; i++) {
            count[weights[i]]++;
            maxWeight = Math.max(maxWeight, weights[i]);
        }
        for (int c : count) {
            largest = Math.max(largest, c);
        }
        if (largest == listed || largest == 1) {
            return null; // one value only, or nothing repeats: libzstd lists such weights directly
        }
        int tableLog = ZstdFseEncoder.optimalTableLog(WEIGHTS_MAX_TABLE_LOG, listed, maxWeight);
        short[] norm = ZstdFseEncoder.normalize(count, maxWeight, listed, tableLog);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ZstdFseEncoder.writeNCount(body, norm, maxWeight, tableLog);
        byte[] stream = ZstdFseEncoder.compressInterleaved(weights, listed,
                new ZstdFseEncoder.CTable(norm, tableLog));
        body.write(stream, 0, stream.length);
        byte[] out = new byte[1 + body.size()];
        out[0] = (byte) body.size();
        System.arraycopy(body.toByteArray(), 0, out, 1, body.size());
        return out;
    }

    /** Whether the decoder reads {@code description} as exactly this code. */
    private static boolean describes(byte[] description, int[] lengths, int[] codes, int maxSymbol, int maxBits) {
        ZstdHuffman.Table table;
        int[] consumed = new int[1];
        try {
            table = ZstdHuffman.readTable(description, 0, description.length, consumed);
        } catch (CompressionFormatException e) {
            return false;
        }
        if (consumed[0] != description.length || table.maxBits != maxBits) {
            return false;
        }
        for (int s = 0; s <= maxSymbol; s++) {
            if (lengths[s] == 0) {
                continue;
            }
            int index = codes[s] << (maxBits - lengths[s]);
            if ((table.symbol[index] & 0xff) != s || table.numberOfBits[index] != lengths[s]) {
                return false;
            }
        }
        return true;
    }
}
