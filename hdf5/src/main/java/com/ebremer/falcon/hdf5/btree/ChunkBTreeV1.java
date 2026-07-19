package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-1 B-tree of type 1 (spec section III.A.1): the "raw data chunk" index. Each key holds a
 * chunk's stored size, filter mask, and element coordinates; the corresponding child is the chunk's
 * file address (at leaf level) or a child B-tree node.
 *
 * <p>Key layout: {@code chunkSize(4) · filterMask(4) · (rank+1) × 8-byte offsets} (the trailing
 * offset is the element dimension and is ignored). For {@code n} entries there are {@code n} children
 * and {@code n+1} keys.
 */
public final class ChunkBTreeV1 {

    private static final byte[] TREE = {'T', 'R', 'E', 'E'};
    private static final int NODE_TYPE_CHUNK = 1;
    private static final int MAX_DEPTH = 4096;

    private ChunkBTreeV1() {
    }

    /** Enumerates every chunk reachable from the B-tree rooted at {@code btreeAddress}. */
    public static List<ChunkRecord> read(FileContext ctx, long btreeAddress, int rank) {
        List<ChunkRecord> out = new ArrayList<>();
        walk(ctx, btreeAddress, rank, out, 0);
        return out;
    }

    private static void walk(FileContext ctx, long addr, int rank, List<ChunkRecord> out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new HdfFormatException("chunk B-tree nested too deeply at " + addr);
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

        int offsets = ctx.sizeOfOffsets();
        int keySize = 4 + 4 + (rank + 1) * 8;
        long p = addr + 8 + 2L * offsets; // after signature/type/level/count/siblings
        for (int i = 0; i < entries; i++) {
            int chunkSize = (int) buf.getUnsignedInt(p);
            int filterMask = (int) buf.getUnsignedInt(p + 4);
            long[] offset = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = buf.getLong(p + 8 + 8L * d);
            }
            long child = buf.getAddress(p + keySize, offsets);
            if (level == 0) {
                out.add(new ChunkRecord(offset, child, chunkSize, filterMask));
            } else {
                walk(ctx, child, rank, out, depth + 1);
            }
            p += keySize + offsets;
        }
    }
}
