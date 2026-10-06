package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Version-1 B-tree of type 1 (spec section III.A.1): the "raw data chunk" index. Each key holds a
 * chunk's stored size, filter mask, and element coordinates; the corresponding child is the chunk's
 * file address (at leaf level) or a child B-tree node.
 *
 * <p>Key layout: {@code chunkSize(4) · filterMask(4) · (rank+1) × 8-byte offsets} (the trailing
 * offset is the element dimension and is ignored). For {@code n} entries there are {@code n} children
 * and {@code n+1} keys.
 *
 * <p>Each child must sit exactly one level below its parent and be visited once, so a corrupt tree
 * that loops or shares subtrees is rejected instead of recursing without end.
 *
 * <p>{@link #lookup} finds one chunk by descending from the root (P2 PF5), as libhdf5's {@code H5B_find}
 * does: key <i>i</i> of a node is the first chunk of its child <i>i</i>, in the order of the chunks'
 * offsets, so the chunk is under the last child whose key is not after it.
 */
public final class ChunkBTreeV1 {

    private static final byte[] TREE = {'T', 'R', 'E', 'E'};
    private static final int NODE_TYPE_CHUNK = 1;

    private ChunkBTreeV1() {
    }

    /** Enumerates every chunk reachable from the B-tree rooted at {@code btreeAddress}. */
    public static List<ChunkRecord> read(FileContext ctx, long btreeAddress, int rank) {
        List<ChunkRecord> out = new ArrayList<>();
        walk(ctx, btreeAddress, rank, out, -1, new HashSet<>());
        return out;
    }

    private static void walk(FileContext ctx, long addr, int rank, List<ChunkRecord> out, int expectedLevel,
                             Set<Long> visited) {
        if (!visited.add(addr)) {
            throw new HdfFormatException("chunk B-tree node at " + addr + " is reached twice (a cycle)");
        }
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(addr, TREE)) {
            throw new HdfFormatException("expected B-tree signature 'TREE' at " + addr);
        }
        int nodeType = buf.getUnsignedByte(addr + 4);
        if (nodeType != NODE_TYPE_CHUNK) {
            throw new HdfFormatException("expected a chunk B-tree (node type 1) at " + addr + ", got " + nodeType);
        }
        int level = buf.getUnsignedByte(addr + 5);
        int entries = buf.getUnsignedShort(addr + 6);
        if (expectedLevel >= 0 && level != expectedLevel) {
            throw new HdfFormatException("chunk B-tree node at " + addr + " has level " + level
                    + ", expected " + expectedLevel);
        }

        int offsets = ctx.sizeOfOffsets();
        int keySize = 4 + 4 + (rank + 1) * 8;
        long p = addr + 8 + 2L * offsets; // after signature/type/level/count/siblings
        for (int i = 0; i < entries; i++) {
            long chunkSize = buf.getUnsignedInt(p);
            if (chunkSize > Integer.MAX_VALUE) {
                throw new HdfFormatException("invalid chunk size " + chunkSize + " in B-tree at " + addr);
            }
            int filterMask = (int) buf.getUnsignedInt(p + 4);
            long[] offset = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = buf.getLong(p + 8 + 8L * d);
            }
            long child = buf.getAddress(p + keySize, offsets);
            if (level == 0) {
                out.add(new ChunkRecord(offset, child, (int) chunkSize, filterMask));
            } else {
                walk(ctx, child, rank, out, level - 1, visited);
            }
            p += keySize + offsets;
        }
    }

    /** Finds a chunk by descending the tree from its root, one node per level (P2 PF5). */
    public static ChunkLookup lookup(FileContext ctx, long btreeAddress, int rank, int[] chunkDims) {
        return cell -> {
            long[] target = new long[rank];
            for (int d = 0; d < rank; d++) {
                target[d] = cell[d] * chunkDims[d];
            }
            HdfBuffer buf = ctx.buffer();
            int offsets = ctx.sizeOfOffsets();
            int keySize = 4 + 4 + (rank + 1) * 8;
            long addr = btreeAddress;
            int expectedLevel = -1;
            while (true) {
                if (!buf.hasSignature(addr, TREE)) {
                    throw new HdfFormatException("expected B-tree signature 'TREE' at " + addr);
                }
                int nodeType = buf.getUnsignedByte(addr + 4);
                if (nodeType != NODE_TYPE_CHUNK) {
                    throw new HdfFormatException("expected a chunk B-tree (node type 1) at " + addr + ", got " + nodeType);
                }
                int level = buf.getUnsignedByte(addr + 5);
                int entries = buf.getUnsignedShort(addr + 6);
                if (expectedLevel >= 0 && level != expectedLevel) {
                    throw new HdfFormatException("chunk B-tree node at " + addr + " has level " + level
                            + ", expected " + expectedLevel);
                }
                long first = addr + 8 + 2L * offsets; // key 0, after signature/type/level/count/siblings
                long stride = keySize + offsets;
                // The last entry whose key is not after the target (at a leaf, it must be the target).
                int lo = 0;
                int hi = entries - 1;
                int found = -1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int c = compareKey(buf, first + mid * stride + 8, target);
                    if (c <= 0) {
                        found = mid;
                        if (c == 0) {
                            break;
                        }
                        lo = mid + 1;
                    } else {
                        hi = mid - 1;
                    }
                }
                if (found < 0) {
                    return null;
                }
                long key = first + found * stride;
                long child = buf.getAddress(key + keySize, offsets);
                if (level == 0) {
                    if (compareKey(buf, key + 8, target) != 0) {
                        return null;
                    }
                    long chunkSize = buf.getUnsignedInt(key);
                    if (chunkSize > Integer.MAX_VALUE) {
                        throw new HdfFormatException("invalid chunk size " + chunkSize + " in B-tree at " + addr);
                    }
                    return new ChunkRecord(target, child, (int) chunkSize, (int) buf.getUnsignedInt(key + 4));
                }
                addr = child;
                expectedLevel = level - 1;
            }
        };
    }

    /** Compares the chunk offset stored at {@code at} with {@code target}, dimension by dimension. */
    private static int compareKey(HdfBuffer buf, long at, long[] target) {
        for (int d = 0; d < target.length; d++) {
            int c = Long.compare(buf.getLong(at + 8L * d), target[d]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
