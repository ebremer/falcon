package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Attribute Info message (type 21): an object's dense attribute-storage descriptor. When the
 * fractal-heap address is undefined, attributes are stored compactly (as Attribute messages);
 * otherwise they live in the fractal heap indexed by v2 B-trees.
 */
public final class AttributeInfoMessage {

    private AttributeInfoMessage() {
    }

    /** The fractal-heap address, or {@link HdfBuffer#UNDEFINED_ADDRESS} for compact storage. */
    public static long fractalHeapAddress(FileContext ctx, HeaderMessage message) {
        return ctx.buffer().getAddress(addressesOffset(ctx, message), ctx.sizeOfOffsets());
    }

    /** The address of the v2 B-tree indexing attributes by name (present when storage is dense). */
    public static long nameBTreeAddress(FileContext ctx, HeaderMessage message) {
        long p = addressesOffset(ctx, message) + ctx.sizeOfOffsets();
        return ctx.buffer().getAddress(p, ctx.sizeOfOffsets());
    }

    private static long addressesOffset(FileContext ctx, HeaderMessage message) {
        long p = message.bodyOffset();
        int flags = ctx.buffer().getUnsignedByte(p + 1);
        p += 2; // version, flags
        if ((flags & 0x01) != 0) {
            p += 2; // maximum creation index
        }
        return p;
    }
}
