package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A Link message (type 6): one link in a new-style group. Layout: version, flags, then (per the
 * flags) an optional link type, creation order, and character set, followed by the name length, the
 * name, and the link information (a hard link's target object-header address, or a soft/external
 * link's value).
 */
public final class LinkMessage {

    public static final int HARD = 0;
    public static final int SOFT = 1;
    public static final int EXTERNAL = 64;

    private final String name;
    private final int linkType;
    private final long targetAddress;

    private LinkMessage(String name, int linkType, long targetAddress) {
        this.name = name;
        this.linkType = linkType;
        this.targetAddress = targetAddress;
    }

    public String name() {
        return name;
    }

    public int linkType() {
        return linkType;
    }

    /** For a {@link #HARD} link, the target object-header address; otherwise {@code UNDEFINED_ADDRESS}. */
    public long targetAddress() {
        return targetAddress;
    }

    public static LinkMessage parse(FileContext ctx, HeaderMessage message) {
        HdfBuffer buf = ctx.buffer();
        long p = message.bodyOffset();
        p += 1; // version
        int flags = buf.getUnsignedByte(p);
        p += 1;

        int linkType = HARD;
        if ((flags & 0x08) != 0) {
            linkType = buf.getUnsignedByte(p);
            p += 1;
        }
        if ((flags & 0x04) != 0) {
            p += 8; // creation order
        }
        if ((flags & 0x10) != 0) {
            p += 1; // character set
        }
        int nameLengthSize = 1 << (flags & 0x03);
        int nameLength = (int) buf.getUnsignedValue(p, nameLengthSize);
        p += nameLengthSize;
        String name = new String(buf.getBytes(p, nameLength), StandardCharsets.UTF_8);
        p += nameLength;

        long target = HdfBuffer.UNDEFINED_ADDRESS;
        if (linkType == HARD) {
            target = buf.getAddress(p, ctx.sizeOfOffsets());
        }
        return new LinkMessage(name, linkType, target);
    }
}
