package com.ebremer.falcon.hdf5.write;

import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import java.util.List;

/**
 * Writes a version-2 B-tree (spec section III.A.2) of fixed-size records, in nodes of libhdf5's default
 * size for dense links and attributes (512 bytes): a single leaf while the records fit in one, else as
 * many levels of internal nodes as they need. The tree is built bottom-up from the records in order, each
 * internal node holding the records that separate its children, the children evenly filled. The widths
 * of a child pointer's record counts follow from the node and record sizes, as libhdf5 computes them
 * ({@code H5B2__hdr_init}).
 */
public final class BTreeV2Writer {

    private BTreeV2Writer() {
    }

    private static final byte[] BTHD = {'B', 'T', 'H', 'D'};
    private static final byte[] BTLF = {'B', 'T', 'L', 'F'};
    private static final byte[] BTIN = {'B', 'T', 'I', 'N'};
    private static final long UNDEFINED = -1L;
    private static final int OFFSETS = 8;
    private static final int PREFIX = 10;      // signature, version, type, checksum
    private static final int MAX_DEPTH = 32;

    /** libhdf5's node size for the name and creation-order indexes of dense links and attributes. */
    public static final int NODE_SIZE = 512;

    /**
     * Writes a tree of {@code records} (each {@code recordSize} bytes, already in the tree's order) and
     * returns its header's address. An empty tree has no root node.
     */
    public static long write(GrowBuffer buf, int type, int recordSize, List<byte[]> records) {
        Shape shape = Shape.of(recordSize, records.size());
        long root = UNDEFINED;
        if (!records.isEmpty()) {
            root = writeNode(buf, type, recordSize, records, 0, records.size(), shape.depth, shape);
        }
        buf.align(8);
        long header = buf.position();
        buf.bytes(BTHD);
        buf.u8(0);
        buf.u8(type);
        buf.u32(NODE_SIZE);
        buf.u16(recordSize);
        buf.u16(shape.depth);
        buf.u8(100);                   // split percent
        buf.u8(40);                    // merge percent
        buf.u64(root);
        buf.u16(records.isEmpty() ? 0 : shape.rootRecords(records.size()));
        buf.u64(records.size());       // total records
        buf.u32(buf.checksum(header, buf.position()));
        return header;
    }

    /**
     * Writes the node of {@code depth} holding records {@code [from, to)}, its subtrees first, and returns
     * its address.
     */
    private static long writeNode(GrowBuffer buf, int type, int recordSize, List<byte[]> records, int from, int to,
                                  int depth, Shape shape) {
        int n = to - from;
        if (depth == 0) {
            buf.align(8);
            long node = buf.position();
            buf.bytes(BTLF);
            buf.u8(0);
            buf.u8(type);
            for (int i = from; i < to; i++) {
                buf.bytes(records.get(i));
            }
            buf.u32(buf.checksum(node, buf.position()));
            buf.reserve((int) (NODE_SIZE - (buf.position() - node)));
            return node;
        }
        // The fewest children that hold the records, each child filled evenly; a record between each two.
        int children = shape.children(depth, n);
        int inChildren = n - (children - 1);
        long[] addresses = new long[children];
        int[] counts = new int[children];
        int[] separators = new int[children - 1];
        int at = from;
        for (int c = 0; c < children; c++) {
            int count = inChildren / children + (c < inChildren % children ? 1 : 0);
            addresses[c] = writeNode(buf, type, recordSize, records, at, at + count, depth - 1, shape);
            counts[c] = count;
            at += count;
            if (c < children - 1) {
                separators[c] = at++;
            }
        }
        buf.align(8);
        long node = buf.position();
        buf.bytes(BTIN);
        buf.u8(0);
        buf.u8(type);
        for (int separator : separators) {
            buf.bytes(records.get(separator));
        }
        for (int c = 0; c < children; c++) {
            buf.u64(addresses[c]);
            buf.uvar(shape.nodeRecords(depth - 1, counts[c]), shape.recordCountSize);
            if (depth > 1) {
                buf.uvar(counts[c], shape.subtreeCountSize[depth - 1]);
            }
        }
        buf.u32(buf.checksum(node, buf.position()));
        buf.reserve((int) (NODE_SIZE - (buf.position() - node)));
        return node;
    }

    /** The tree's depth for a record count, and the field widths and node capacities it implies. */
    private static final class Shape {
        final int depth;
        final int recordCountSize;   // a node's record count, in a pointer to it
        final int[] subtreeCountSize; // a subtree's total record count, in a pointer to a node of that depth
        final long[] maxRecords;      // records one node of each depth holds
        final long[] maxSubtree;      // records a subtree rooted at each depth holds

        private Shape(int depth, int recordCountSize, int[] subtreeCountSize, long[] maxRecords, long[] maxSubtree) {
            this.depth = depth;
            this.recordCountSize = recordCountSize;
            this.subtreeCountSize = subtreeCountSize;
            this.maxRecords = maxRecords;
            this.maxSubtree = maxSubtree;
        }

        static Shape of(int recordSize, long records) {
            long leaf = (NODE_SIZE - PREFIX) / recordSize;
            if (leaf < 1) {
                throw new HdfUnsupportedException("a " + recordSize + "-byte v2 B-tree record does not fit a node");
            }
            long[] maxRecords = new long[MAX_DEPTH + 1];
            long[] maxSubtree = new long[MAX_DEPTH + 1];
            int[] subtreeCountSize = new int[MAX_DEPTH + 1];
            int recordCountSize = encodedSize(leaf);
            maxRecords[0] = leaf;
            maxSubtree[0] = leaf;
            int depth = 0;
            while (maxSubtree[depth] < records) {
                if (depth == MAX_DEPTH) {
                    throw new HdfUnsupportedException("too many records for a v2 B-tree: " + records);
                }
                depth++;
                int pointer = OFFSETS + recordCountSize + subtreeCountSize[depth - 1];
                maxRecords[depth] = (NODE_SIZE - PREFIX - pointer) / (recordSize + pointer);
                maxSubtree[depth] = (maxRecords[depth] + 1) * maxSubtree[depth - 1] + maxRecords[depth];
                subtreeCountSize[depth] = encodedSize(maxSubtree[depth]);
            }
            return new Shape(depth, recordCountSize, subtreeCountSize, maxRecords, maxSubtree);
        }

        /** The children a node of {@code depth} holding {@code n} records (with its subtrees) needs. */
        int children(int depth, int n) {
            long perChild = maxSubtree[depth - 1];
            return (int) ((n + 1 + perChild) / (perChild + 1)); // ceil((n + 1) / (perChild + 1))
        }

        /** The records in the node itself, of a subtree of {@code depth} holding {@code n} records. */
        int nodeRecords(int depth, int n) {
            return depth == 0 ? n : children(depth, n) - 1;
        }

        int rootRecords(int n) {
            return nodeRecords(depth, n);
        }

        private static int encodedSize(long value) {
            return (63 - Long.numberOfLeadingZeros(value)) / 8 + 1;
        }
    }
}
