package com.ebremer.falcon.hdf5.header;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * <p>{@link MessageType#NIL} padding messages are dropped. Continuation messages queue further chunks,
 * which are read after the current one, so messages appear in chunk order as libhdf5 lists them. Each
 * continuation chunk is read at most once (a corrupt header that loops back is rejected), and version-2
 * chunks are checksum-verified.
 */
public final class ObjectHeader {

    private static final byte[] OHDR = {'O', 'H', 'D', 'R'};
    private static final byte[] OCHK = {'O', 'C', 'H', 'K'};
    private static final int MAX_MESSAGES = 1_000_000;

    /** A header chunk still to be read: where its messages start, and how many bytes they span. */
    private record Chunk(long start, long size) {
    }

    private final int version;
    private final List<HeaderMessage> messages;
    private final int v1ReferenceCount;     // version-1 prefix hard-link count; -1 for version 2
    private final long headerModificationTime; // version-2 prefix modification time (seconds); -1 if absent

    private ObjectHeader(int version, List<HeaderMessage> messages, int v1ReferenceCount,
                         long headerModificationTime) {
        this.version = version;
        this.messages = messages;
        this.v1ReferenceCount = v1ReferenceCount;
        this.headerModificationTime = headerModificationTime;
    }

    public int version() {
        return version;
    }

    /** The number of hard links to this object (at least 1). */
    public int referenceCount() {
        if (v1ReferenceCount >= 0) {
            return v1ReferenceCount;
        }
        HeaderMessage message = find(MessageType.OBJECT_REFERENCE_COUNT);
        return message == null ? 1 : (int) message.body().getUnsignedInt(1); // version(1), count(4)
    }

    /** The object's modification time (seconds since the epoch), if the file tracks it. */
    public java.util.OptionalLong modificationTimeSeconds() {
        if (headerModificationTime >= 0) {
            return java.util.OptionalLong.of(headerModificationTime);
        }
        HeaderMessage message = find(MessageType.OBJECT_MODIFICATION_TIME);
        if (message != null) {
            return java.util.OptionalLong.of(
                    com.ebremer.falcon.hdf5.message.ObjectModificationTimeMessage.epochSeconds(message.body()));
        }
        HeaderMessage old = find(MessageType.OBJECT_MODIFICATION_TIME_OLD);
        if (old != null) {
            return java.util.OptionalLong.of(
                    com.ebremer.falcon.hdf5.message.ObjectModificationTimeMessage.epochSecondsOld(old.body()));
        }
        return java.util.OptionalLong.empty();
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
        int referenceCount = (int) buf.getUnsignedInt(addr + 4);
        long chunk0Size = buf.getUnsignedInt(addr + 8);
        long messageStart = addr + 16; // 12-byte prefix padded to an 8-byte boundary
        List<HeaderMessage> out = new ArrayList<>();
        ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        chunks.add(new Chunk(messageStart, chunk0Size));
        Set<Long> seen = new HashSet<>();
        while (!chunks.isEmpty()) {
            Chunk chunk = chunks.poll();
            long p = chunk.start();
            long end = chunk.start() + chunk.size();
            while (p + 8 <= end) {
                int type = buf.getUnsignedShort(p);
                int msgSize = buf.getUnsignedShort(p + 2);
                int flags = buf.getUnsignedByte(p + 4);
                long body = p + 8;
                checkFits(body, msgSize, end);
                if (type == MessageType.OBJECT_HEADER_CONTINUATION) {
                    long contAddr = buf.getAddress(body, ctx.sizeOfOffsets());
                    long contLen = buf.getUnsignedValue(body + ctx.sizeOfOffsets(), ctx.sizeOfLengths());
                    if (contAddr != HdfBuffer.UNDEFINED_ADDRESS) {
                        queue(chunks, seen, contAddr, contAddr, contLen, buf.size());
                    }
                } else if (type != MessageType.NIL) {
                    out.add(new HeaderMessage(type, flags, buf, body, msgSize));
                    guardCount(out);
                }
                p = body + msgSize;
            }
        }
        return new ObjectHeader(version, out, referenceCount, -1);
    }

    // ---------------------------------------------------------------- version 2

    private static ObjectHeader parseVersion2(FileContext ctx, long addr) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(addr + 4);
        int flags = buf.getUnsignedByte(addr + 5);
        long p = addr + 6;
        long modificationTime = -1;
        if ((flags & 0x20) != 0) {
            // access(4), modification(4), change(4), birth(4)
            modificationTime = buf.getUnsignedInt(addr + 10);
            p += 16;
        }
        if ((flags & 0x10) != 0) {
            p += 4; // max-compact / min-dense attribute phase-change values
        }
        int sizeFieldWidth = 1 << (flags & 0x03);
        long chunk0Size = buf.getUnsignedValue(p, sizeFieldWidth);
        p += sizeFieldWidth;
        if (chunk0Size < 0 || chunk0Size > buf.size()) {
            throw new HdfFormatException("object header chunk size " + chunk0Size + " exceeds the file at " + addr);
        }
        // The checksum of chunk 0 follows its messages and covers the header from the signature on.
        MetadataChecksum.verify(buf, addr, p + chunk0Size - addr, "object header");
        boolean creationOrder = (flags & 0x04) != 0;
        // type(1), size(2), flags(1), then a creation-order index(2) if tracked. Fewer bytes than this
        // left at the end of a chunk are a gap, not a message.
        int messageHeaderSize = creationOrder ? 6 : 4;
        List<HeaderMessage> out = new ArrayList<>();
        ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        chunks.add(new Chunk(p, chunk0Size));
        Set<Long> seen = new HashSet<>();
        while (!chunks.isEmpty()) {
            Chunk chunk = chunks.poll();
            long q = chunk.start();
            long end = chunk.start() + chunk.size();
            while (q + messageHeaderSize <= end) {
                int type = buf.getUnsignedByte(q);
                int msgSize = buf.getUnsignedShort(q + 1);
                int msgFlags = buf.getUnsignedByte(q + 3);
                long body = q + messageHeaderSize;
                checkFits(body, msgSize, end);
                if (type == MessageType.OBJECT_HEADER_CONTINUATION) {
                    long contAddr = buf.getAddress(body, ctx.sizeOfOffsets());
                    long contLen = buf.getUnsignedValue(body + ctx.sizeOfOffsets(), ctx.sizeOfLengths());
                    if (contAddr != HdfBuffer.UNDEFINED_ADDRESS) {
                        // A v2 continuation block is "OCHK" + messages + a 4-byte checksum over both.
                        if (!buf.hasSignature(contAddr, OCHK)) {
                            throw new HdfFormatException("expected OCHK continuation block at " + contAddr);
                        }
                        if (contLen < 8 || contLen > buf.size()) {
                            throw new HdfFormatException("invalid OCHK continuation length " + contLen + " at " + contAddr);
                        }
                        MetadataChecksum.verify(buf, contAddr, contLen - 4, "object header continuation");
                        queue(chunks, seen, contAddr, contAddr + 4, contLen - 8, buf.size());
                    }
                } else if (type != MessageType.NIL) {
                    out.add(new HeaderMessage(type, msgFlags, buf, body, msgSize));
                    guardCount(out);
                }
                q = body + msgSize;
            }
        }
        return new ObjectHeader(version, out, -1, modificationTime);
    }

    /** Queues a continuation chunk, refusing one already read (a corrupt header that loops). */
    private static void queue(ArrayDeque<Chunk> chunks, Set<Long> seen, long address, long start, long size,
                              long fileSize) {
        if (!seen.add(address)) {
            throw new HdfFormatException("object header continuation at " + address + " is referenced twice (a cycle)");
        }
        if (size < 0 || size > fileSize) {
            throw new HdfFormatException("invalid object header continuation length " + size + " at " + address);
        }
        chunks.add(new Chunk(start, size));
    }

    /** A message body must lie within its chunk. */
    private static void checkFits(long body, int size, long chunkEnd) {
        if (body + size > chunkEnd) {
            throw new HdfFormatException("object header message at " + body + " (" + size
                    + " bytes) runs past the end of its chunk at " + chunkEnd);
        }
    }

    private static void guardCount(List<HeaderMessage> out) {
        if (out.size() > MAX_MESSAGES) {
            throw new HdfFormatException("object header has too many messages (possible cycle)");
        }
    }
}
