package com.ebremer.falcon.hdf5.write;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.checksum.Lookup3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Changes an object header already in a file (spec section IV.A), as libhdf5 does when it modifies an
 * object: in either header version, a message removed becomes a null message (type 0) of the same size; a
 * message added takes a null message large enough for it (the rest of which stays null), or else goes
 * into a new continuation chunk, written after the file's end, which a Continuation message in a null
 * message of the existing chunks points at; when no null message can hold that, an existing message
 * moves to the new chunk to make room for it ({@code H5O__alloc}). A message replaced by one of the same
 * size is rewritten in place.
 *
 * <p>{@link #write} lays out the new chunks and returns the existing chunks that changed, rewritten whole
 * with their checksums (version 2) and, in version 1, the prefix's message count. A header that holds a
 * message flagged "fail if unknown and open for write" is not changed.
 */
public final class ObjectHeaderEditor {

    /** Reads bytes of the file: {@code length} bytes at {@code address}. */
    @FunctionalInterface
    public interface Source {
        byte[] read(long address, int length);
    }

    /** Bytes to write over the file at {@code address}: an existing chunk, rewritten. */
    public record Patch(long address, byte[] bytes) {
    }

    private static final byte[] OHDR = {'O', 'H', 'D', 'R'};
    private static final byte[] OCHK = {'O', 'C', 'H', 'K'};
    private static final int NIL = 0;
    private static final int CONTINUATION = 0x10;
    private static final int REFERENCE_COUNT = 0x16;
    private static final int FLAG_FAIL_IF_UNKNOWN_AND_WRITING = 0x08;
    private static final int CONTINUATION_BODY = 16;      // address + length
    private static final int NEW_CHUNK_SLACK = 64;        // null space left in a new chunk, for later additions
    private static final int MAX_BODY = 0xFFFF;
    private static final long UNDEFINED = -1L;

    private final long address;
    private final int version;
    private final int headerFlags;                        // version 2's flags
    private final int messageHeader;                      // the bytes before a message's body
    private final List<Chunk> chunks = new ArrayList<>();
    private int referenceCount;                           // version 1's prefix field
    private boolean referenceCountChanged;

    /** One chunk of the header: its bytes, where its messages lie in them, and its messages. */
    private static final class Chunk {
        long address;              // where the chunk starts in the file (for a new chunk, once laid out)
        byte[] image;              // the whole chunk: version 2 with its signature and checksum
        final int start;           // the first message
        final int end;             // the end of the message space (a gap may lie before it)
        final boolean fresh;       // written by this editor, after the file's end
        boolean gap;               // version 2: bytes too few for a message end it (it then holds no null message)
        boolean dirty;
        List<Slot> slots = new ArrayList<>();

        Chunk(long address, byte[] image, int start, int end, boolean fresh) {
            this.address = address;
            this.image = image;
            this.start = start;
            this.end = end;
            this.fresh = fresh;
        }
    }

    /** A message's place in a chunk: where its header is, and its body's size. */
    private static final class Slot {
        final Chunk chunk;
        int offset;
        int bodySize;
        int type;
        Chunk continuation;        // a continuation message this editor added: the chunk it points at
        Message handle;            // the message's handle, which follows it if it moves

        Slot(Chunk chunk, int offset, int bodySize, int type) {
            this.chunk = chunk;
            this.offset = offset;
            this.bodySize = bodySize;
            this.type = type;
        }
    }

    /** A message of the header, as {@link #messages} lists it. */
    public static final class Message {
        private Slot slot;
        private final ObjectHeaderEditor editor;

        private Message(ObjectHeaderEditor editor, Slot slot) {
            this.editor = editor;
            this.slot = slot;
        }

        public int type() {
            return slot.type;
        }

        public int flags() {
            return slot.chunk.image[slot.offset + (editor.version == 1 ? 4 : 3)] & 0xff;
        }

        /** The message's creation order, in a version-2 header that tracks attributes' (else 0). */
        public int creationOrder() {
            return editor.messageHeader == 6 ? u16(slot.chunk.image, slot.offset + 4) : 0;
        }

        /** A copy of the message's body (in version 1, with its padding). */
        public byte[] body() {
            int at = slot.offset + editor.messageHeader;
            return java.util.Arrays.copyOfRange(slot.chunk.image, at, at + slot.bodySize);
        }
    }

    private ObjectHeaderEditor(long address, int version, int headerFlags, int referenceCount) {
        this.address = address;
        this.version = version;
        this.headerFlags = headerFlags;
        this.messageHeader = version == 1 ? 8 : (headerFlags & 0x04) != 0 ? 6 : 4;
        this.referenceCount = referenceCount;
    }

    /**
     * Reads the header at {@code address} (its chunks, through their continuation messages).
     *
     * @throws HdfUnsupportedException if a message is flagged to fail when an unknown-to-it library writes
     */
    public static ObjectHeaderEditor load(Source source, long address) {
        byte[] start = source.read(address, 16);
        ObjectHeaderEditor editor;
        List<long[]> pending = new ArrayList<>(); // continuation chunks: address, length
        if (start[0] == 'O' && start[1] == 'H' && start[2] == 'D' && start[3] == 'R') {
            int flags = start[5] & 0xff;
            int prefix = 6 + ((flags & 0x20) != 0 ? 16 : 0) + ((flags & 0x10) != 0 ? 4 : 0);
            int sizeWidth = 1 << (flags & 0x03);
            byte[] head = source.read(address, prefix + sizeWidth);
            long chunk0 = 0;
            for (int i = 0; i < sizeWidth; i++) {
                chunk0 |= (long) (head[prefix + i] & 0xff) << (8 * i);
            }
            if (chunk0 > Integer.MAX_VALUE - 64) {
                throw new HdfFormatException("object header chunk of " + chunk0 + " bytes at " + address);
            }
            int messages = prefix + sizeWidth;
            byte[] image = source.read(address, messages + (int) chunk0 + 4);
            editor = new ObjectHeaderEditor(address, 2, flags, 0);
            editor.addChunk(new Chunk(address, image, messages, messages + (int) chunk0, false), pending);
        } else if (start[0] == 1) {
            int references = (int) u32(start, 4);
            long chunk0 = u32(start, 8);
            byte[] image = source.read(address, 16 + (int) chunk0);
            editor = new ObjectHeaderEditor(address, 1, 0, references);
            editor.addChunk(new Chunk(address, image, 16, 16 + (int) chunk0, false), pending);
        } else {
            throw new HdfFormatException("unrecognized object header at " + address);
        }
        Set<Long> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            long[] next = pending.removeFirst();
            if (!seen.add(next[0]) || next[1] > Integer.MAX_VALUE) {
                throw new HdfFormatException("object header continuation at " + next[0] + " of " + address);
            }
            byte[] image = source.read(next[0], (int) next[1]);
            if (editor.version == 2) {
                if (image.length < 8 || image[0] != 'O' || image[1] != 'C' || image[2] != 'H' || image[3] != 'K') {
                    throw new HdfFormatException("expected OCHK continuation block at " + next[0]);
                }
                editor.addChunk(new Chunk(next[0], image, 4, image.length - 4, false), pending);
            } else {
                editor.addChunk(new Chunk(next[0], image, 0, image.length, false), pending);
            }
        }
        return editor;
    }

    /** Parses a chunk's messages, queueing the chunks its continuation messages point at. */
    private void addChunk(Chunk chunk, List<long[]> pending) {
        chunks.add(chunk);
        int p = chunk.start;
        while (p + messageHeader <= chunk.end) {
            int type = version == 1 ? u16(chunk.image, p) : chunk.image[p] & 0xff;
            int size = version == 1 ? u16(chunk.image, p + 2) : u16(chunk.image, p + 1);
            int flags = chunk.image[p + (version == 1 ? 4 : 3)] & 0xff;
            if (p + messageHeader + size > chunk.end) {
                throw new HdfFormatException("object header message runs past its chunk at " + (chunk.address + p));
            }
            if ((flags & FLAG_FAIL_IF_UNKNOWN_AND_WRITING) != 0) {
                throw new HdfUnsupportedException("the object header at " + address + " holds a message (type " + type
                        + ") that libraries not knowing it must not change");
            }
            chunk.slots.add(new Slot(chunk, p, size, type));
            if (type == CONTINUATION) {
                pending.add(new long[] {u64(chunk.image, p + messageHeader), u64(chunk.image, p + messageHeader + 8)});
            }
            p += messageHeader + size;
        }
        chunk.gap = p < chunk.end;
    }

    /** The header's version: 1 (the original) or 2. */
    public int version() {
        return version;
    }

    /** A version-2 header's flags (bit 2: attributes' creation order tracked; bit 4: phase-change values stored). */
    public int headerFlags() {
        return headerFlags;
    }

    /** The most attributes a version-2 header keeps as messages before they go dense (8 unless it says). */
    public int maxCompactAttributes() {
        if (version == 2 && (headerFlags & 0x10) != 0) {
            int at = 6 + ((headerFlags & 0x20) != 0 ? 16 : 0);
            return u16(chunks.getFirst().image, at);
        }
        return 8;
    }

    /** The header's messages, in chunk order: all but null and continuation messages. */
    public List<Message> messages() {
        List<Message> result = new ArrayList<>();
        for (Chunk chunk : chunks) {
            for (Slot slot : chunk.slots) {
                if (slot.type != NIL && slot.type != CONTINUATION) {
                    if (slot.handle == null) {
                        slot.handle = new Message(this, slot);
                    }
                    result.add(slot.handle);
                }
            }
        }
        return result;
    }

    /** The messages of type {@code type}. */
    public List<Message> messages(int type) {
        List<Message> result = new ArrayList<>();
        for (Message message : messages()) {
            if (message.type() == type) {
                result.add(message);
            }
        }
        return result;
    }

    /** The first message of type {@code type}, or null. */
    public Message find(int type) {
        List<Message> found = messages(type);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** Makes {@code message} a null message. */
    public void remove(Message message) {
        Slot slot = message.slot;
        if (slot.type == NIL) {
            throw new IllegalStateException("message already removed");
        }
        clear(slot);
        slot.handle = null;
    }

    /** Makes a slot a null message, its body zeroed. */
    private void clear(Slot slot) {
        frame(slot, NIL, 0, 0, null);
        java.util.Arrays.fill(slot.chunk.image, slot.offset + messageHeader, slot.offset + messageHeader + slot.bodySize, (byte) 0);
    }

    /** Replaces {@code message}'s body: in place if it is the same size, else as a message added anew. */
    public void replace(Message message, byte[] body) {
        Slot slot = message.slot;
        if (padded(body.length) == slot.bodySize) {
            System.arraycopy(body, 0, slot.chunk.image, slot.offset + messageHeader, body.length);
            java.util.Arrays.fill(slot.chunk.image, slot.offset + messageHeader + body.length,
                    slot.offset + messageHeader + slot.bodySize, (byte) 0);
            slot.chunk.dirty = true;
            return;
        }
        int type = slot.type;
        int flags = message.flags();
        int order = message.creationOrder();
        remove(message);
        Slot moved = add(type, flags, body, order).slot;
        moved.handle = message;
        message.slot = moved;
    }

    /** Adds a message (its creation order 0). */
    public Message add(int type, int flags, byte[] body) {
        return add(type, flags, body, 0);
    }

    /**
     * Adds a message: in a null message that holds it, else in a new continuation chunk.
     *
     * @param creationOrder the message's creation order, kept in a version-2 header that tracks attributes'
     */
    public Message add(int type, int flags, byte[] body, int creationOrder) {
        if (body.length > (version == 1 ? MAX_BODY - 7 : MAX_BODY)) {
            throw new HdfUnsupportedException("an object header message of " + body.length + " bytes");
        }
        int need = messageHeader + padded(body.length);
        Slot slot = freeSlot(need);
        if (slot == null) {
            slot = newChunk(need);
        }
        place(slot, need, type, flags, body, creationOrder);
        slot.handle = new Message(this, slot);
        return slot.handle;
    }

    /** The object's hard-link count. */
    public int referenceCount() {
        if (version == 1) {
            return referenceCount;
        }
        Message message = find(REFERENCE_COUNT);
        return message == null ? 1 : (int) u32(message.body(), 1);
    }

    /** Sets the object's hard-link count (at least 1). */
    public void setReferenceCount(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("a reference count of " + count);
        }
        if (version == 1) {
            referenceCount = count;
            referenceCountChanged = true;
            chunks.getFirst().dirty = true;
            return;
        }
        Message message = find(REFERENCE_COUNT);
        byte[] body = {0, (byte) count, (byte) (count >>> 8), (byte) (count >>> 16), (byte) (count >>> 24)};
        if (message == null && count > 1) {
            add(REFERENCE_COUNT, 0, body);
        } else if (message != null && count == 1) {
            remove(message); // a count of 1 is the default, written as no message
        } else if (message != null) {
            replace(message, body);
        }
    }

    /** True if the header has changed. */
    public boolean changed() {
        for (Chunk chunk : chunks) {
            if (chunk.dirty) {
                return true;
            }
        }
        return referenceCountChanged;
    }

    /**
     * Lays out the new chunks in {@code buf}, fills in the continuation messages pointing at them, and
     * returns the existing chunks that changed, to be written over the file once it holds {@code buf}.
     */
    public List<Patch> write(GrowBuffer buf) {
        if (version == 2) {
            for (Chunk chunk : chunks) {
                eliminateGap(chunk);
            }
        }
        for (Chunk chunk : chunks) {
            if (chunk.fresh) {
                buf.align(8);
                chunk.address = buf.position();
                buf.reserve(chunk.image.length);
            }
        }
        for (Chunk chunk : chunks) {
            for (Slot slot : chunk.slots) {
                if (slot.continuation != null) {
                    putU64(chunk.image, slot.offset + messageHeader, slot.continuation.address);
                    putU64(chunk.image, slot.offset + messageHeader + 8, slot.continuation.image.length);
                }
            }
        }
        if (version == 1) {
            Chunk first = chunks.getFirst();
            int count = 0;
            for (Chunk chunk : chunks) {
                count += chunk.slots.size();
            }
            putU16(first.image, 2, count);
            putU32(first.image, 4, referenceCount);
        }
        List<Patch> patches = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (version == 2 && (chunk.dirty || chunk.fresh)) {
                int sum = Lookup3.hashLittle(chunk.image, 0, chunk.image.length - 4, 0);
                putU32(chunk.image, chunk.image.length - 4, sum);
            }
            if (chunk.fresh) {
                buf.patchBytes(chunk.address, chunk.image);
            } else if (chunk.dirty || (version == 1 && chunk == chunks.getFirst() && changed())) {
                patches.add(new Patch(chunk.address, chunk.image.clone()));
            }
        }
        return patches;
    }

    // ------------------------------------------------------------------ space

    /** A null message that holds {@code need} bytes (header and body), or null. */
    private Slot freeSlot(int need) {
        Slot best = null;
        for (Chunk chunk : chunks) {
            for (Slot slot : chunk.slots) {
                int size = messageHeader + slot.bodySize;
                if (slot.type == NIL && (size == need || size - need >= messageHeader)
                        && (best == null || slot.bodySize < best.bodySize)) {
                    best = slot;
                }
            }
        }
        return best;
    }

    /**
     * Ends a version-2 chunk's gap, if it now holds a null message: libhdf5 accepts a gap (bytes too few
     * for a message at a chunk's end) only in a chunk without null messages, and itself merges a gap into a
     * null message by moving the messages after it forward ({@code H5O__eliminate_gap}). So do these: the
     * chunk's messages move to its start, in order, and its null space (with the gap) follows them.
     */
    private void eliminateGap(Chunk chunk) {
        if (!chunk.gap || chunk.slots.stream().noneMatch(slot -> slot.type == NIL)) {
            return;
        }
        byte[] image = chunk.image.clone();
        List<Slot> slots = new ArrayList<>();
        int p = chunk.start;
        for (Slot slot : chunk.slots) {
            if (slot.type != NIL) {
                int size = messageHeader + slot.bodySize;
                System.arraycopy(chunk.image, slot.offset, image, p, size);
                slot.offset = p;
                slots.add(slot);
                p += size;
            }
        }
        chunk.image = image;
        chunk.slots = slots;
        int rest = chunk.end - p; // at least a null message's worth: one was there, and the gap
        while (rest > 0) {
            int body = Math.min(rest - messageHeader, MAX_BODY);
            if (rest - messageHeader - body > 0 && rest - messageHeader - body < messageHeader) {
                body -= messageHeader; // leave the last null message room for its header
            }
            Slot nil = new Slot(chunk, p, body, NIL);
            slots.add(nil);
            clear(nil);
            p += messageHeader + body;
            rest -= messageHeader + body;
        }
        chunk.gap = false;
        chunk.dirty = true;
    }

    /**
     * A new chunk with room for a message of {@code need} bytes, pointed at by a continuation message in a
     * null message of the header, or in the place of a message moved into the new chunk to make room;
     * returns the null message the new chunk holds for the message (some null space follows it).
     */
    private Slot newChunk(int need) {
        int continuation = messageHeader + CONTINUATION_BODY;
        Slot holder = freeSlot(continuation);
        Slot moved = null;
        if (holder == null) {
            // Move the smallest message that leaves room for the continuation message where it was.
            for (Chunk chunk : chunks) {
                for (Slot slot : chunk.slots) {
                    int size = messageHeader + slot.bodySize;
                    if (slot.type != NIL && slot.type != CONTINUATION
                            && (size == continuation || size - continuation >= messageHeader)
                            && (moved == null || slot.bodySize < moved.bodySize)) {
                        moved = slot;
                    }
                }
            }
            if (moved == null) {
                throw new HdfUnsupportedException("no room for a continuation message in the object header at " + address);
            }
        }
        int movedSize = moved == null ? 0 : messageHeader + moved.bodySize;
        int prefix = version == 2 ? 4 : 0;
        int space = movedSize + need + messageHeader + NEW_CHUNK_SLACK;
        byte[] image = new byte[prefix + space + (version == 2 ? 4 : 0)];
        if (version == 2) {
            System.arraycopy(OCHK, 0, image, 0, 4);
        }
        Chunk chunk = new Chunk(UNDEFINED, image, prefix, prefix + space, true);
        chunks.add(chunk);
        int at = prefix;
        if (moved != null) {
            Slot copy = new Slot(chunk, at, moved.bodySize, moved.type);
            chunk.slots.add(copy);
            System.arraycopy(moved.chunk.image, moved.offset, image, at, movedSize); // its header and body as they were
            copy.handle = moved.handle;
            if (copy.handle != null) {
                copy.handle.slot = copy;
            }
            moved.handle = null;
            clear(moved);
            holder = moved;
            at += movedSize;
        }
        Slot target = new Slot(chunk, at, need - messageHeader, NIL);
        chunk.slots.add(target);
        frame(target, NIL, 0, 0, null);
        Slot rest = new Slot(chunk, at + need, NEW_CHUNK_SLACK, NIL);
        chunk.slots.add(rest);
        frame(rest, NIL, 0, 0, null);
        Slot cont = place(holder, continuation, CONTINUATION, 0, new byte[CONTINUATION_BODY], 0);
        cont.continuation = chunk;
        return target;
    }

    /** Puts a message of {@code need} bytes in the null message {@code slot}; the rest stays null. */
    private Slot place(Slot slot, int need, int type, int flags, byte[] body, int creationOrder) {
        int size = messageHeader + slot.bodySize;
        if (size > need) {
            Slot rest = new Slot(slot.chunk, slot.offset + need, size - need - messageHeader, NIL);
            slot.chunk.slots.add(slot.chunk.slots.indexOf(slot) + 1, rest);
            clear(rest);
        }
        slot.bodySize = need - messageHeader;
        frame(slot, type, flags, creationOrder, body);
        return slot;
    }

    /** Writes a slot's message header (type, size, flags, creation order) and, if given, its body. */
    private void frame(Slot slot, int type, int flags, int creationOrder, byte[] body) {
        byte[] image = slot.chunk.image;
        int p = slot.offset;
        if (version == 1) {
            putU16(image, p, type);
            putU16(image, p + 2, slot.bodySize);
            image[p + 4] = (byte) flags;
            image[p + 5] = 0;
            image[p + 6] = 0;
            image[p + 7] = 0;
        } else {
            image[p] = (byte) type;
            putU16(image, p + 1, slot.bodySize);
            image[p + 3] = (byte) flags;
            if (messageHeader == 6) {
                putU16(image, p + 4, creationOrder);
            }
        }
        if (body != null) {
            int at = p + messageHeader;
            java.util.Arrays.fill(image, at, at + slot.bodySize, (byte) 0);
            System.arraycopy(body, 0, image, at, body.length);
        }
        slot.type = type;
        slot.chunk.dirty = true;
    }

    /** A body's size in its header: version 1 pads bodies to 8 bytes. */
    private int padded(int size) {
        return version == 1 ? (size + 7) & ~7 : size;
    }

    // ------------------------------------------------------------------ bytes

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xffL) | (b[at + 1] & 0xffL) << 8 | (b[at + 2] & 0xffL) << 16 | (b[at + 3] & 0xffL) << 24;
    }

    private static long u64(byte[] b, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (b[at + i] & 0xffL) << (8 * i);
        }
        return v;
    }

    private static void putU16(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >>> 8);
    }

    private static void putU32(byte[] b, int at, long value) {
        for (int i = 0; i < 4; i++) {
            b[at + i] = (byte) (value >>> (8 * i));
        }
    }

    private static void putU64(byte[] b, int at, long value) {
        for (int i = 0; i < 8; i++) {
            b[at + i] = (byte) (value >>> (8 * i));
        }
    }
}
