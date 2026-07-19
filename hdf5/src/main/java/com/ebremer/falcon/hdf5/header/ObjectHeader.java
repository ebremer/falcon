package com.ebremer.falcon.hdf5.header;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A parsed object header (the metadata record for a group, dataset, or committed datatype), holding
 * the flattened list of its messages.
 *
 * <p>Both on-disk formats are supported:
 * <ul>
 *   <li><b>Version 1</b> &mdash; a 12-byte prefix padded to 16 bytes, then 8-byte-aligned messages
 *       (type/size/flags/reserved), with continuation blocks holding more raw messages.</li>
 *   <li><b>Version 2</b> &mdash; an {@code "OHDR"} signature, flags-gated optional fields, a
 *       variable-width chunk-0 size, then messages (type/flags/size and an optional creation-order
 *       field), with checksummed {@code "OCHK"} continuation blocks.</li>
 * </ul>
 *
 * <p>{@link MessageType#NIL} padding messages are dropped; continuation messages are followed and
 * their contents inlined in order.
 */
public final class ObjectHeader {

    private static final byte[] OHDR = {'O', 'H', 'D', 'R'};
    private static final byte[] OCHK = {'O', 'C', 'H', 'K'};
    private static final int MAX_MESSAGES = 1_000_000;
    private static final int MAX_CONTINUATION_DEPTH = 4096;

    private final int version;
    private final List<HeaderMessage> messages;

    private ObjectHeader(int version, List<HeaderMessage> messages) {
        this.version = version;
        this.messages = messages;
    }

    public int version() {
        return version;
    }

    public List<HeaderMessage> messages() {
        return messages;
    }

    /** The first message of the given {@linkplain MessageType type}, or {@code null} if none. */
    public HeaderMessage find(int type) {
        for (HeaderMessage m : messages) {
            if (m.type() == type) {
                return m;
            }
        }
        return null;
    }

    public boolean contains(int type) {
        return find(type) != null;
    }

    /** Parses the object header located at file address {@code addr}. */
    public static ObjectHeader parse(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        if (buf.hasSignature(addr, OHDR)) {
            return parseVersion2(ctx, addr);
        }
        int firstByte = buf.getUnsignedByte(addr);
        if (firstByte == 1) {
            return parseVersion1(ctx, addr);
        }
        throw new HdfFormatException("unrecognized object header at " + addr + " (first byte " + firstByte + ")");
    }

    // ---------------------------------------------------------------- version 1

    private static ObjectHeader parseVersion1(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(addr);
        // addr+1 reserved, addr+2 total message count (2), addr+4 reference count (4)
        long chunk0Size = buf.getUnsignedInt(addr + 8);
        long messageStart = addr + 16; // 12-byte prefix padded to an 8-byte boundary
        List<HeaderMessage> out = new ArrayList<>();
        readVersion1Messages(ctx, messageStart, chunk0Size, out, 0);
        return new ObjectHeader(version, out);
    }

    private static void readVersion1Messages(FileContext ctx, long start, long size,
                                             List<HeaderMessage> out, int depth) {
        if (depth > MAX_CONTINUATION_DEPTH) {
            throw new HdfFormatException("object header continuation nested too deeply");
        }
        HdfBuffer buf = ctx.buffer();
        long p = start;
        long end = start + size;
        while (p + 8 <= end) {
            int type = buf.getUnsignedShort(p);
            int msgSize = buf.getUnsignedShort(p + 2);
            int flags = buf.getUnsignedByte(p + 4);
            long body = p + 8;
            if (type == MessageType.OBJECT_HEADER_CONTINUATION) {
                long contAddr = buf.getAddress(body, ctx.sizeOfOffsets());
                long contLen = buf.getUnsignedValue(body + ctx.sizeOfOffsets(), ctx.sizeOfLengths());
                if (contAddr != HdfBuffer.UNDEFINED_ADDRESS) {
                    readVersion1Messages(ctx, contAddr, contLen, out, depth + 1);
                }
            } else if (type != MessageType.NIL) {
                out.add(new HeaderMessage(type, flags, buf, body, msgSize));
                guardCount(out);
            }
            p = body + msgSize;
        }
    }

    // ---------------------------------------------------------------- version 2

    private static ObjectHeader parseVersion2(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(addr + 4);
        int flags = buf.getUnsignedByte(addr + 5);
        long p = addr + 6;
        if ((flags & 0x20) != 0) {
            p += 16; // access/modification/change/birth times
        }
        if ((flags & 0x10) != 0) {
            p += 4; // max-compact / min-dense attribute phase-change values
        }
        int sizeFieldWidth = 1 << (flags & 0x03);
        long chunk0Size = buf.getUnsignedValue(p, sizeFieldWidth);
        p += sizeFieldWidth;
        boolean creationOrder = (flags & 0x04) != 0;
        List<HeaderMessage> out = new ArrayList<>();
        readVersion2Messages(ctx, p, chunk0Size, creationOrder, out, 0);
        return new ObjectHeader(version, out);
    }

    private static void readVersion2Messages(FileContext ctx, long start, long size,
                                             boolean creationOrder, List<HeaderMessage> out, int depth) {
        if (depth > MAX_CONTINUATION_DEPTH) {
            throw new HdfFormatException("object header continuation nested too deeply");
        }
        HdfBuffer buf = ctx.buffer();
        long p = start;
        long end = start + size;
        while (p + 4 <= end) {
            int type = buf.getUnsignedByte(p);
            int msgSize = buf.getUnsignedShort(p + 1);
            int flags = buf.getUnsignedByte(p + 3);
            long body = p + 4 + (creationOrder ? 2 : 0);
            if (type == MessageType.OBJECT_HEADER_CONTINUATION) {
                long contAddr = buf.getAddress(body, ctx.sizeOfOffsets());
                long contLen = buf.getUnsignedValue(body + ctx.sizeOfOffsets(), ctx.sizeOfLengths());
                if (contAddr != HdfBuffer.UNDEFINED_ADDRESS) {
                    // A v2 continuation block is "OCHK" + messages + 4-byte checksum.
                    if (!buf.hasSignature(contAddr, OCHK)) {
                        throw new HdfFormatException("expected OCHK continuation block at " + contAddr);
                    }
                    readVersion2Messages(ctx, contAddr + 4, contLen - 8, creationOrder, out, depth + 1);
                }
            } else if (type != MessageType.NIL) {
                out.add(new HeaderMessage(type, flags, buf, body, msgSize));
                guardCount(out);
            }
            p = body + msgSize;
        }
    }

    private static void guardCount(List<HeaderMessage> out) {
        if (out.size() > MAX_MESSAGES) {
            throw new HdfFormatException("object header has too many messages (possible cycle)");
        }
    }
}
