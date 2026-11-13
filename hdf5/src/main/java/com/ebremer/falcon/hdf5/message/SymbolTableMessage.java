package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.btree.GroupBTreeV1;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.heap.LocalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;

/**
 * Symbol Table message (type 17): present in an old-style group's object header, pointing to the
 * version-1 B-tree that indexes the group's entries and the local heap that stores their names.
 */
public final class SymbolTableMessage {

    private final long btreeAddress;
    private final long localHeapAddress;

    private SymbolTableMessage(long btreeAddress, long localHeapAddress) {
        this.btreeAddress = btreeAddress;
        this.localHeapAddress = localHeapAddress;
    }

    public long btreeAddress() {
        return btreeAddress;
    }

    public long localHeapAddress() {
        return localHeapAddress;
    }

    public static SymbolTableMessage parse(FileContext ctx, HeaderMessage message) {
        long off = message.bodyOffset();
        long btree = ctx.buffer().getAddress(off, ctx.sizeOfOffsets());
        long heap = ctx.buffer().getAddress(off + ctx.sizeOfOffsets(), ctx.sizeOfOffsets());
        return new SymbolTableMessage(btree, heap);
    }

    /**
     * The root group's Symbol Table message, repaired as libhdf5 repairs it when it opens a file for writing
     * ({@code H5G__stab_valid}): an address of the message at which there is no B-tree node, or no local heap,
     * gives way to the one a version 0&ndash;1 superblock caches in its root symbol table entry, if there is
     * one there. A message whose addresses are good, or which the superblock cannot mend, is as it is stored.
     */
    public static SymbolTableMessage parseRoot(FileContext ctx, HeaderMessage message) {
        SymbolTableMessage stored = parse(ctx, message);
        long btree = stored.btreeAddress;
        long heap = stored.localHeapAddress;
        if (!GroupBTreeV1.isNode(ctx, btree) && GroupBTreeV1.isNode(ctx, ctx.rootBTreeAddress())) {
            btree = ctx.rootBTreeAddress();
        }
        if (!LocalHeap.isHeap(ctx, heap) && LocalHeap.isHeap(ctx, ctx.rootHeapAddress())) {
            heap = ctx.rootHeapAddress();
        }
        return btree == stored.btreeAddress && heap == stored.localHeapAddress ? stored : new SymbolTableMessage(btree, heap);
    }
}
