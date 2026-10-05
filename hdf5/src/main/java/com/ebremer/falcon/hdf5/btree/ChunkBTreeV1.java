package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
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
}
