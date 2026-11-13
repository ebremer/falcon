package com.ebremer.falcon.hdf5.btree;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.ChunkLookup;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p><b>Read ahead (P2 PF12).</b> The nodes of a tree lie apart in the file, among the chunks they were
 * written with. In a file read on demand each is a request of its own, so a tree walked a node at a time is
 * read one request after another. A node names its children, though: so before it descends, the walk says
 * which nodes it is about to read ({@link HdfBuffer#ahead}), and they are read together, several requests at
 * once; and a read of several chunks says so of the nodes on its chunks' paths, a level at a time. Only
 * when the pages are read changes: the walk and the lookups read each node as before, from the page cache,
 * and find, or fail on, what they did.
 */
public final class ChunkBTreeV1 {

    private static final byte[] TREE = {'T', 'R', 'E', 'E'};
    private static final int NODE_TYPE_CHUNK = 1;
    /**
     * The entries a node has room for as libhdf5 makes a file by default (2K, with K = 32). The file's own
     * K is in its superblock or its extension, not here: a node read ahead is taken to have room for these,
     * or for as many as any node read so far holds if those are more.
     */
    private static final int DEFAULT_ENTRIES = 64;
    /**
     * The most entries a node is read ahead for (K = 512). A node with more is read ahead that far, and the
     * rest of it in one request when the walk comes to it; and a corrupt node that claims 65,535 entries
     * does not have every node below it read ahead as if each were as large.
     */
    private static final int MOST_ENTRIES = 1024;

    private ChunkBTreeV1() {
    }

    /** Enumerates every chunk reachable from the B-tree rooted at {@code btreeAddress}. */
    public static List<ChunkRecord> read(FileContext ctx, long btreeAddress, int rank) {
        List<ChunkRecord> out = new ArrayList<>();
        walk(ctx, btreeAddress, rank, out, -1, new HashSet<>(), new int[] {DEFAULT_ENTRIES});
        return out;
    }

    /** {@code most} holds the most entries any node walked so far has: the room a node is taken to have. */
    private static void walk(FileContext ctx, long addr, int rank, List<ChunkRecord> out, int expectedLevel,
                             Set<Long> visited, int[] most) {
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

        most[0] = Math.max(most[0], entries);
        int offsets = ctx.sizeOfOffsets();
        int keySize = 4 + 4 + (rank + 1) * 8;
        long p = addr + 8 + 2L * offsets; // after signature/type/level/count/siblings
        if (buf.readOnDemand()) {
            // What of this node is not read yet (it has more entries than it was taken to have), in one request.
            buf.ahead(new long[] {addr}, 0, 1, 8 + 2L * offsets + (long) entries * (keySize + offsets) + keySize);
        }
        // The nodes below are read ahead of the walk, as many at a time as the page cache keeps for it.
        long[] children = level > 0 ? children(buf, p, keySize, offsets, entries) : null;
        int ahead = 0; // the children read ahead so far
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
                if (children != null && i == ahead) {
                    ahead += buf.ahead(children, i, entries, nodeBytes(keySize, offsets, most[0]));
                }
                walk(ctx, child, rank, out, level - 1, visited, most);
            }
            p += keySize + offsets;
        }
    }

    /**
     * The addresses of a node's {@code entries} children, to read them ahead: null where there is nothing to
     * gain (the file is mapped, or the node has one child), or where they cannot all be read, which the walk
     * finds when it comes to that entry.
     */
    private static long[] children(HdfBuffer buf, long firstKey, int keySize, int offsets, int entries) {
        if (entries < 2 || !buf.readOnDemand()) {
            return null;
        }
        try {
            long[] children = new long[entries];
            for (int i = 0; i < entries; i++) {
                children[i] = buf.getAddress(firstKey + (long) i * (keySize + offsets) + keySize, offsets);
            }
            return children;
        } catch (RuntimeException unread) {
            return null;
        }
    }

    /**
     * The bytes of a node with room for {@link #DEFAULT_ENTRIES} entries, or for {@code entries} if more, up
     * to {@link #MOST_ENTRIES}. A node with more room than that is read ahead in part, and the rest of it as
     * the walk comes to it.
     */
    private static long nodeBytes(int keySize, int offsets, int entries) {
        int room = Math.min(MOST_ENTRIES, Math.max(entries, DEFAULT_ENTRIES));
        return 8 + 2L * offsets + (long) room * (keySize + offsets) + keySize;
    }

    /** Finds a chunk by descending the tree from its root, one node per level (P2 PF5). */
    public static ChunkLookup lookup(FileContext ctx, long btreeAddress, int rank, int[] chunkDims) {
        return new ChunkLookup() {
            @Override
            public ChunkRecord find(long[] cell) {
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
                    int found = search(buf, first, stride, entries, target);
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
            }

            @Override
            public void ahead(long[][] cells) {
                try {
                    readAhead(ctx, btreeAddress, rank, chunkDims, cells);
                } catch (RuntimeException unread) {
                    // A hint: the lookups come to what it could not read, and fail there if they must.
                }
            }
        };
    }

    /**
     * Reads ahead the nodes on the paths to the cells whose coordinate in every dimension <i>d</i> is one of
     * {@code cells[d]}, a level at a time: the root names the nodes below it that the cells are under, which
     * are read together; those name the ones below them; and so on down to the leaves. It reads the nodes as
     * {@link ChunkLookup#find} does, and stops, reading no further ahead, at anything the lookups would fail
     * on.
     */
    private static void readAhead(FileContext ctx, long btreeAddress, int rank, int[] chunkDims, long[][] cells) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.readOnDemand()) {
            return;
        }
        long[][] targets = targets(cells, chunkDims);
        if (targets.length < 2) {
            return; // one chunk: a node a level, each known only once the one above it is read
        }
        int offsets = ctx.sizeOfOffsets();
        int keySize = 4 + 4 + (rank + 1) * 8;
        long stride = keySize + offsets;
        // The nodes of one level, and the targets under each: targets[from[n]] to targets[to[n] - 1].
        long[] nodes = {btreeAddress};
        int[] from = {0};
        int[] to = {targets.length};
        int count = 1;
        for (int expectedLevel = -1; ; ) {
            long[] below = new long[Math.min(targets.length, 16)];
            int[] belowFrom = new int[below.length];
            int[] belowTo = new int[below.length];
            int found = 0;
            int level = 0;
            int most = 0;
            for (int n = 0; n < count; n++) {
                long addr = nodes[n];
                if (!buf.hasSignature(addr, TREE) || buf.getUnsignedByte(addr + 4) != NODE_TYPE_CHUNK) {
                    return;
                }
                level = buf.getUnsignedByte(addr + 5);
                int entries = buf.getUnsignedShort(addr + 6);
                if (level == 0 || (expectedLevel >= 0 && level != expectedLevel)) {
                    return; // a leaf holds the chunks themselves; a node at the wrong level is corrupt
                }
                most = Math.max(most, entries);
                long first = addr + 8 + 2L * offsets;
                int last = -1; // the entry the target before this one is under
                for (int t = from[n]; t < to[n]; t++) {
                    int entry = search(buf, first, stride, entries, targets[t]);
                    if (entry < 0) {
                        continue; // before the node's first chunk: none is stored there
                    }
                    if (entry == last) {
                        belowTo[found - 1] = t + 1;
                        continue;
                    }
                    if (found == below.length) {
                        below = Arrays.copyOf(below, 2 * found);
                        belowFrom = Arrays.copyOf(belowFrom, 2 * found);
                        belowTo = Arrays.copyOf(belowTo, 2 * found);
                    }
                    below[found] = buf.getAddress(first + entry * stride + keySize, offsets);
                    belowFrom[found] = t;
                    belowTo[found] = t + 1;
                    found++;
                    last = entry;
                }
            }
            // One node below is read as the lookups come to it; several are read together.
            int taken = found < 2 ? found : buf.ahead(below, 0, found, nodeBytes(keySize, offsets, most));
            if (taken == 0 || level == 1) {
                return; // nothing below, or the leaves: they are read, and need not be looked into
            }
            nodes = below;
            from = belowFrom;
            to = belowTo;
            count = taken;
            expectedLevel = level - 1;
        }
    }

    /**
     * The chunk offsets of the cells whose coordinate in every dimension <i>d</i> is one of {@code cells[d]},
     * in row-major order, which is the order of the tree's keys: the first {@link ChunkLookup#AHEAD_CELLS}
     * of them.
     */
    private static long[][] targets(long[][] cells, int[] chunkDims) {
        int rank = chunkDims.length;
        long combinations = 1;
        for (long[] axis : cells) {
            combinations = Math.min(ChunkLookup.AHEAD_CELLS, combinations * axis.length);
        }
        long[][] targets = new long[cells.length != rank ? 0 : (int) combinations][];
        int[] at = new int[rank];
        for (int t = 0; t < targets.length; t++) {
            long[] target = new long[rank];
            for (int d = 0; d < rank; d++) {
                target[d] = cells[d][at[d]] * chunkDims[d];
            }
            targets[t] = target;
            for (int d = rank - 1; d >= 0; d--) {
                if (++at[d] < cells[d].length) {
                    break;
                }
                at[d] = 0;
            }
        }
        return targets;
    }

    /**
     * The last of a node's {@code entries} entries whose key is not after {@code target}, by a binary search
     * (the keys are in order), or -1 if the first is. {@code first} is where key 0 is, and {@code stride} the
     * bytes from one key to the next.
     */
    private static int search(HdfBuffer buf, long first, long stride, int entries, long[] target) {
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
        return found;
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
