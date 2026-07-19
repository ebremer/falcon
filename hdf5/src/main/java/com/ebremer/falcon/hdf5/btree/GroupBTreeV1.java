package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.group.SymbolTableEntry;
import com.ebremer.falcon.hdf5.group.SymbolTableNode;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-1 B-tree of type 0 (spec section III.A.1): the "group node" index that orders an old-style
 * group's links. Its leaves point to {@link SymbolTableNode symbol-table nodes}; internal nodes point
 * to child B-tree nodes.
 *
 * <p>Node layout: {@code "TREE"} (4) · node type (1) · node level (1) · entries used (2) ·
 * left sibling (O) · right sibling (O), then {@code key(L), child(O)} pairs with a trailing key
 * (for {@code n} entries: {@code n} children and {@code n+1} keys). Type-0 keys are heap offsets and
 * are not needed for a full enumeration, so this walk visits every child.
 */
public final class GroupBTreeV1 {

    private static final byte[] TREE = {'T', 'R', 'E', 'E'};
    private static final int NODE_TYPE_GROUP = 0;
    private static final int MAX_DEPTH = 4096;

    private GroupBTreeV1() {
        // Static walker only.
    }

    /** Enumerates every symbol-table entry reachable from the B-tree rooted at {@code btreeAddress}. */
    public static List<SymbolTableEntry> readEntries(FileContext ctx, long btreeAddress) {
        List<SymbolTableEntry> out = new ArrayList<>();
        walk(ctx, btreeAddress, out, 0);
        return out;
    }

    private static void walk(FileContext ctx, long addr, List<SymbolTableEntry> out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new HdfFormatException("group B-tree nested too deeply at " + addr);
        }
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(addr, TREE)) {
            throw new HdfFormatException("expected B-tree signature 'TREE' at " + addr);
        }
        int nodeType = buf.getUnsignedByte(addr + 4);
        if (nodeType != NODE_TYPE_GROUP) {
            throw new HdfFormatException("expected a group B-tree (node type 0) at " + addr + ", got " + nodeType);
        }
        int level = buf.getUnsignedByte(addr + 5);
        int entriesUsed = buf.getUnsignedShort(addr + 6);

        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        long recordsStart = addr + 8 + 2L * offsets; // after signature/type/level/count/siblings
        long stride = (long) lengths + offsets;       // key + child pointer
        for (int i = 0; i < entriesUsed; i++) {
            long childOffset = recordsStart + i * stride + lengths; // skip this record's key
            long child = buf.getAddress(childOffset, offsets);
            if (level == 0) {
                out.addAll(SymbolTableNode.parse(ctx, child));
            } else {
                walk(ctx, child, out, depth + 1);
            }
        }
    }
}
