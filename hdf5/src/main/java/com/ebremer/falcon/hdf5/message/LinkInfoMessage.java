package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Link Info message (type 2): a new-style group's link-storage descriptor. When the fractal-heap
 * address is undefined the group uses <em>compact</em> storage (links are Link messages in the object
 * header); otherwise links are stored <em>densely</em> in the fractal heap indexed by v2 B-trees.
 */
public final class LinkInfoMessage {

    private LinkInfoMessage() {
    }

    /** The group's fractal-heap address, or {@link HdfBuffer#UNDEFINED_ADDRESS} for compact storage. */
    public static long fractalHeapAddress(FileContext ctx, HeaderMessage message) {
        HdfBuffer buf = ctx.buffer();
        long p = message.bodyOffset();
        int flags = buf.getUnsignedByte(p + 1);
        p += 2; // version, flags
        if ((flags & 0x01) != 0) {
            p += 8; // maximum creation index
        }
        return buf.getAddress(p, ctx.sizeOfOffsets());
    }
}
