package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Version-2 B-tree (spec section III.A.2): the index for dense link and attribute storage, chunk
 * indexes, and other fixed-size record sets. The header ({@code "BTHD"}) describes the record size,
 * tree depth, and root node; leaf nodes ({@code "BTLF"}) hold records, and internal nodes
 * ({@code "BTIN"}) hold records plus pointers to child subtrees.
 *
 * <p>This reader returns every record's raw bytes, walking the whole tree regardless of depth. An
 * internal node stores its {@code N} records first, then {@code N+1} child pointers; each pointer is a
 * child address, the child's record count, and — when the child is itself internal — its subtree
 * record count. The widths of those two counts are derived from the node size and record size exactly
 * as the library computes them.
 *
 * <p>The header and every node are checksum-verified; each node is visited once, one level below its
 * parent, and the walk stops at the header's total record count, so corrupt pointers cannot make it
 * loop or explode.
 */
public final class BTreeV2 {

    private static final byte[] BTHD = {'B', 'T', 'H', 'D'};
    private static final byte[] BTLF = {'B', 'T', 'L', 'F'};
    private static final byte[] BTIN = {'B', 'T', 'I', 'N'};
    private static final int PREFIX = 10; // signature(4) + version(1) + type(1) + checksum(4)

    private BTreeV2() {
    }

    /** Returns every record (each {@code recordSize} raw bytes) in the tree at {@code headerAddress}. */
    public static List<byte[]> readRecords(FileContext ctx, long headerAddress) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(headerAddress, BTHD)) {
            throw new HdfFormatException("expected v2 B-tree signature 'BTHD' at " + headerAddress);
        }
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        MetadataChecksum.verify(buf, headerAddress, 16L + offsets + 2 + lengths, "v2 B-tree header");
        long nodeSize = buf.getUnsignedInt(headerAddress + 6);
        int recordSize = buf.getUnsignedShort(headerAddress + 10);
        int depth = buf.getUnsignedShort(headerAddress + 12);
        long rootNode = buf.getAddress(headerAddress + 16, offsets);
        int rootRecords = buf.getUnsignedShort(headerAddress + 16 + offsets);
        long totalRecords = buf.getUnsignedValue(headerAddress + 18 + offsets, lengths);
        if (recordSize == 0 || nodeSize < PREFIX + recordSize || nodeSize > buf.size() || depth > 64) {
            throw new HdfFormatException("invalid v2 B-tree parameters at " + headerAddress + " (node size "
                    + nodeSize + ", record size " + recordSize + ", depth " + depth + ")");
        }
        if (rootNode == HdfBuffer.UNDEFINED_ADDRESS || rootRecords == 0) {
            return List.of();
        }
        if (totalRecords < 0 || totalRecords > buf.size() / recordSize) {
            throw new HdfFormatException("v2 B-tree at " + headerAddress + " claims " + totalRecords + " records");
        }

        Widths widths = Widths.compute((int) nodeSize, recordSize, depth, offsets);
        Walk walk = new Walk(ctx.buffer(), recordSize, widths, totalRecords);
        walk.collect(rootNode, depth, rootRecords);
        return walk.records;
    }

    /** One traversal: the records found so far, the nodes visited, and the record budget. */
    private static final class Walk {
        final HdfBuffer buf;
        final int recordSize;
        final Widths widths;
        final long budget;
        final List<byte[]> records = new ArrayList<>();
        final Set<Long> visited = new HashSet<>();

        Walk(HdfBuffer buf, int recordSize, Widths widths, long budget) {
            this.buf = buf;
            this.recordSize = recordSize;
            this.widths = widths;
            this.budget = budget;
        }

        void collect(long node, int nodeDepth, int count) {
            if (!visited.add(node)) {
                throw new HdfFormatException("v2 B-tree node at " + node + " is reached twice (a cycle)");
            }
            if (records.size() + (long) count > budget) {
                throw new HdfFormatException("v2 B-tree holds more records than its header's count");
            }
            long p = node + 6; // signature, version, type
            if (nodeDepth == 0) {
                if (!buf.hasSignature(node, BTLF)) {
                    throw new HdfFormatException("expected v2 B-tree leaf 'BTLF' at " + node);
                }
                MetadataChecksum.verify(buf, node, 6L + (long) count * recordSize, "v2 B-tree leaf");
                for (int i = 0; i < count; i++) {
                    records.add(buf.getBytes(p, recordSize));
                    p += recordSize;
                }
                return;
            }
            if (!buf.hasSignature(node, BTIN)) {
                throw new HdfFormatException("expected v2 B-tree internal node 'BTIN' at " + node);
            }
            int pointerSize = widths.pointerSize(nodeDepth);
            MetadataChecksum.verify(buf, node, 6L + (long) count * recordSize + (count + 1L) * pointerSize,
                    "v2 B-tree internal node");
            // An internal node stores all its records, then N+1 child pointers.
            for (int i = 0; i < count; i++) {
                records.add(buf.getBytes(p, recordSize));
                p += recordSize;
            }
            for (int i = 0; i <= count; i++) {
                long childAddress = buf.getAddress(p, widths.offsets);
                long childRecords = buf.getUnsignedValue(p + widths.offsets, widths.maxRecordCountSize);
                p += pointerSize;
                if (childRecords > 0xFFFF) {
                    throw new HdfFormatException("v2 B-tree node at " + node + " points at a child of "
                            + childRecords + " records");
                }
                collect(childAddress, nodeDepth - 1, (int) childRecords);
            }
        }
    }

    /**
     * The two variable field widths a child pointer uses, indexed by depth. {@code maxRecordCountSize}
     * (tree-wide) sizes a node's record count; {@code subtreeCountSize[d]} sizes the total record count
     * of a subtree rooted at depth {@code d} (0 for a leaf, which carries no subtree count).
     */
    private static final class Widths {
        final int offsets;
        final int maxRecordCountSize;
        final int[] subtreeCountSize;

        private Widths(int offsets, int maxRecordCountSize, int[] subtreeCountSize) {
            this.offsets = offsets;
            this.maxRecordCountSize = maxRecordCountSize;
            this.subtreeCountSize = subtreeCountSize;
        }

        /** Size of a pointer to a child of a node at {@code nodeDepth}: address + counts. */
        int pointerSize(int nodeDepth) {
            return offsets + maxRecordCountSize + subtreeCountSize[nodeDepth - 1];
        }

        static Widths compute(int nodeSize, int recordSize, int depth, int offsets) {
            long[] cumulativeMax = new long[depth + 1];
            int[] subtreeCountSize = new int[depth + 1];
            long leafMax = (nodeSize - PREFIX) / recordSize;
            cumulativeMax[0] = leafMax;
            subtreeCountSize[0] = 0; // leaf children carry no subtree count
            int maxRecordCountSize = encodedSize(leafMax);
            for (int d = 1; d <= depth; d++) {
                int pointer = offsets + maxRecordCountSize + subtreeCountSize[d - 1];
                long max = (nodeSize - PREFIX - pointer) / (recordSize + pointer);
                cumulativeMax[d] = (max + 1) * cumulativeMax[d - 1] + max;
                subtreeCountSize[d] = encodedSize(cumulativeMax[d]);
            }
            return new Widths(offsets, maxRecordCountSize, subtreeCountSize);
        }

        /** Bytes needed to encode {@code value} (>= 1): floor(log2(value))/8 + 1. */
        private static int encodedSize(long value) {
            return (63 - Long.numberOfLeadingZeros(value)) / 8 + 1;
        }
    }
}
