package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
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
}
