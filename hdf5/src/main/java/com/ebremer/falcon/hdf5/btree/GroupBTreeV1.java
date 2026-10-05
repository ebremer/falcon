package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.group.SymbolTableEntry;
import com.ebremer.falcon.hdf5.group.SymbolTableNode;
import com.ebremer.falcon.hdf5.heap.LocalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Version-1 B-tree of type 0 (spec section III.A.1): the "group node" index that orders an old-style
 * group's links. Its leaves point to {@link SymbolTableNode symbol-table nodes}; internal nodes point
 * to child B-tree nodes.
 *
 * <p>Node layout: {@code "TREE"} (4) · node type (1) · node level (1) · entries used (2) ·
 * left sibling (O) · right sibling (O), then {@code key(L), child(O)} pairs with a trailing key
 * (for {@code n} entries: {@code n} children and {@code n+1} keys). Type-0 keys are local-heap offsets
 * of names: child {@code i} holds the names after key {@code i}, up to and including key {@code i+1}.
 * {@link #readEntries} visits every child; {@link #find} follows the keys to the one symbol-table node
 * that can hold a name.
 */
public final class GroupBTreeV1 {

    private static final byte[] TREE = {'T', 'R', 'E', 'E'};
    private static final int NODE_TYPE_GROUP = 0;

    private GroupBTreeV1() {
        // Static walker only.
    }

    /** Enumerates every symbol-table entry reachable from the B-tree rooted at {@code btreeAddress}. */
    public static List<SymbolTableEntry> readEntries(FileContext ctx, long btreeAddress) {
        List<SymbolTableEntry> out = new ArrayList<>();
        walk(ctx, btreeAddress, out, -1, new HashSet<>());
        return out;
    }

    /**
     * The entry named {@code name} (its UTF-8 bytes), or null: the keys lead to one child per level, as in
     * libhdf5's {@code H5B_find}, and the symbol-table node is binary-searched as in {@code H5G__node_found}.
     * Names compare as {@code strcmp} does, byte by byte.
     */
    public static SymbolTableEntry find(FileContext ctx, long btreeAddress, LocalHeap heap, byte[] name) {
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        Set<Long> visited = new HashSet<>();
        long addr = btreeAddress;
        int expectedLevel = -1;
        while (true) {
            if (!visited.add(addr)) {
                throw new HdfFormatException("group B-tree node at " + addr + " is reached twice (a cycle)");
            }
            if (!buf.hasSignature(addr, TREE)) {
                throw new HdfFormatException("expected B-tree signature 'TREE' at " + addr);
            }
            int nodeType = buf.getUnsignedByte(addr + 4);
            if (nodeType != NODE_TYPE_GROUP) {
                throw new HdfFormatException("expected a group B-tree (node type 0) at " + addr + ", got " + nodeType);
            }
            int level = buf.getUnsignedByte(addr + 5);
            int entriesUsed = buf.getUnsignedShort(addr + 6);
            if (expectedLevel >= 0 && level != expectedLevel) {
                throw new HdfFormatException("group B-tree node at " + addr + " has level " + level
                        + ", expected " + expectedLevel);
            }
            long recordsStart = addr + 8 + 2L * offsets;
            long stride = (long) lengths + offsets;
            // The child whose keys bracket the name: key[i] < name <= key[i+1].
            int lo = 0;
            int hi = entriesUsed;
            int cmp = 1;
            int child = -1;
            while (lo < hi && cmp != 0) {
                int mid = (lo + hi) >>> 1;
                byte[] left = heap.nameBytes(ctx, buf.getUnsignedValue(recordsStart + mid * stride, lengths));
                byte[] right = heap.nameBytes(ctx, buf.getUnsignedValue(recordsStart + (mid + 1) * stride, lengths));
                if (Arrays.compareUnsigned(name, left) <= 0) {
                    cmp = -1;
                    hi = mid;
                } else if (Arrays.compareUnsigned(name, right) > 0) {
                    cmp = 1;
                    lo = mid + 1;
                } else {
                    cmp = 0;
                    child = mid;
                }
            }
            if (cmp != 0) {
                return null;
            }
            long childAddress = buf.getAddress(recordsStart + child * stride + lengths, offsets);
            if (level == 0) {
                return SymbolTableNode.find(ctx, childAddress, heap, name);
            }
            addr = childAddress;
            expectedLevel = level - 1;
        }
    }

    /** Walks one node; each child must be one level lower and visited once (a corrupt tree may loop). */
    private static void walk(FileContext ctx, long addr, List<SymbolTableEntry> out, int expectedLevel,
                             Set<Long> visited) {
        if (!visited.add(addr)) {
            throw new HdfFormatException("group B-tree node at " + addr + " is reached twice (a cycle)");
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
        if (expectedLevel >= 0 && level != expectedLevel) {
            throw new HdfFormatException("group B-tree node at " + addr + " has level " + level
                    + ", expected " + expectedLevel);
        }

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
                walk(ctx, child, out, level - 1, visited);
            }
        }
    }
}
