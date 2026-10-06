package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.btree.BTreeV2;
import com.ebremer.falcon.hdf5.btree.GroupBTreeV1;
import com.ebremer.falcon.hdf5.group.SymbolTableEntry;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.header.SharedMessage;
import com.ebremer.falcon.hdf5.heap.FractalHeap;
import com.ebremer.falcon.hdf5.heap.LocalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An existing file an {@link Hdf5Writer} changes in place (P2 WF6, WF10): read through Falcon's reader, which
 * gives its superblock and what its groups and objects hold, as the raw messages the writer copies when it
 * rebuilds a group's links or an object's attributes (those kept in the shared-message table named by their
 * heap IDs there).
 *
 * <p>Only files the writer can change safely open: 8-byte addresses and lengths, the default file
 * driver, no persistent free-space tracking or paged allocation (which keep their own record of the
 * file's space), and not marked as open by a writer.
 */
final class ExistingFile implements AutoCloseable {

    final Hdf5File file;
    final FileContext ctx;
    final long base;               // where the superblock is: after the user block, if any
    final int superblockVersion;
    final long endOfFile;          // the end of the file's data, as an address (relative to the base)
    final long rootAddress;
    final int groupLeafK;
    final int groupInternalK;
    final int chunkK;

    private ExistingFile(Hdf5File file, long base, long endOfFile) {
        this.file = file;
        this.ctx = file.context();
        this.base = base;
        this.superblockVersion = file.superblockVersion();
        this.endOfFile = endOfFile;
        this.rootAddress = ctx.rootAddress();
        BTreeKValues k = file.btreeKValues();
        this.groupLeafK = k.groupLeafNodeK();
        this.groupInternalK = k.groupInternalNodeK();
        this.chunkK = k.indexedStorageInternalNodeK();
    }

    /**
     * Opens {@code path} for an {@link Hdf5Writer} to change.
     *
     * @throws HdfUnsupportedException if the file is of a kind the writer does not change
     */
    static ExistingFile open(Path path) throws IOException {
        Hdf5File file = Hdf5File.open(path);
        try {
            FileContext ctx = file.context();
            if (ctx.sizeOfOffsets() != 8 || ctx.sizeOfLengths() != 8) {
                throw new HdfUnsupportedException("files with " + ctx.sizeOfOffsets() + "-byte addresses and "
                        + ctx.sizeOfLengths() + "-byte lengths are not changed; only 8-byte ones");
            }
            if (file.driverInfo().isPresent()) {
                throw new HdfUnsupportedException("a file of the " + file.driverInfo().get().driverId()
                        + " driver is not changed");
            }
            if (file.fileSpaceInfo().isPresent()) {
                FileSpaceInfo space = file.fileSpaceInfo().get();
                if (space.persistingFreeSpace() || space.strategy() == FileSpaceInfo.Strategy.PAGE) {
                    throw new HdfUnsupportedException("a file that tracks its free space (" + space + ") is not changed");
                }
            }
            byte[] head = new byte[64];
            long size = Files.size(path);
            long base = superblockOffset(path, size);
            try (var channel = java.nio.channels.FileChannel.open(path)) {
                channel.read(java.nio.ByteBuffer.wrap(head), base);
            }
            int version = head[8] & 0xff;
            // The end-of-file address is absolute, from the stored base address (libhdf5's
            // H5F__super_read): the end of the file's data, as an address, is the difference.
            int baseAt = version == 0 ? 24 : version == 1 ? 28 : 12;
            long endOfFile = u64(head, baseAt + 16) - u64(head, baseAt);
            int consistency = version >= 2 ? head[11] & 0xff : 0; // libhdf5 sets them in versions 2-3 only
            if (consistency != 0) {
                throw new HdfUnsupportedException("the file is marked as open by a writer (file consistency flags "
                        + consistency + "); clear them first (h5clear -s)");
            }
            if (base + endOfFile > size) {
                throw new HdfFormatException("the file is truncated: it ends at " + size + ", before its end-of-file address "
                        + (base + endOfFile));
            }
            return new ExistingFile(file, base, endOfFile);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    /** Where the superblock is: offset 0, 512, 1024, ... (after a user block). */
    private static long superblockOffset(Path path, long size) throws IOException {
        byte[] signature = new byte[8];
        try (var channel = java.nio.channels.FileChannel.open(path)) {
            for (long at = 0; at + 8 <= size; at = at == 0 ? 512 : at * 2) {
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(signature);
                channel.read(b, at);
                if (Arrays.equals(signature, com.ebremer.falcon.hdf5.superblock.Superblock.SIGNATURE)) {
                    return at;
                }
            }
        }
        throw new HdfFormatException("HDF5 superblock signature not found");
    }

    @Override
    public void close() {
        file.close();
    }

    // ------------------------------------------------------------------ objects

    /** The object (group, dataset, or committed datatype) whose header is at {@code address}, named {@code path}. */
    Hdf5Object object(String parentPath, String name, long address) {
        return Hdf5Object.classify(ctx, name, parentPath, address);
    }

    /** The parsed header at {@code address}. */
    ObjectHeader header(long address) {
        return ObjectHeader.parse(ctx, address);
    }

    // ------------------------------------------------------------------ links

    /** A link in a new-style group: its name, its Link message, and its creation order (or -1). */
    record StoredLink(String name, byte[] message, long creationOrder) {
    }

    /** A new-style group's links in dense storage: its heap's, through its name index. */
    List<StoredLink> denseLinks(long heapAddress, long nameIndex) {
        FractalHeap heap = FractalHeap.parse(ctx, heapAddress);
        List<StoredLink> links = new ArrayList<>();
        for (byte[] record : BTreeV2.readRecords(ctx, nameIndex)) {
            byte[] message = heap.readObject(Arrays.copyOfRange(record, 4, 4 + heap.idLength()));
            links.add(storedLink(message));
        }
        return links;
    }

    /** A Link message's name and creation order. */
    static StoredLink storedLink(byte[] message) {
        int flags = message[1] & 0xff;
        int p = 2;
        if ((flags & 0x08) != 0) {
            p++; // link type
        }
        long order = -1;
        if ((flags & 0x04) != 0) {
            order = u64(message, p);
            p += 8;
        }
        if ((flags & 0x10) != 0) {
            p++; // character set
        }
        int width = 1 << (flags & 0x03);
        long length = 0;
        for (int i = 0; i < width; i++) {
            length |= (long) (message[p + i] & 0xff) << (8 * i);
        }
        p += width;
        return new StoredLink(new String(message, p, (int) length, StandardCharsets.UTF_8), message, order);
    }

    /** An old-style group's entry: its name, object, cache type, and a soft link's target. */
    record StoredEntry(String name, long address, int cacheType, String softTarget) {
    }

    /** An old-style group's entries, from its symbol table's B-tree and local heap. */
    List<StoredEntry> symbolEntries(long btree, long heapAddress) {
        LocalHeap heap = LocalHeap.parse(ctx, heapAddress);
        List<StoredEntry> entries = new ArrayList<>();
        for (SymbolTableEntry entry : GroupBTreeV1.readEntries(ctx, btree)) {
            entries.add(new StoredEntry(heap.name(ctx, entry.linkNameOffset()), entry.objectHeaderAddress(),
                    entry.cacheType(), entry.isSoftLink() ? heap.name(ctx, entry.linkValueOffset()) : null));
        }
        return entries;
    }

    /** The symbol table (B-tree, local heap) of the group whose header is at {@code address}, or null. */
    long[] symbolTable(long address) {
        HeaderMessage message = header(address).find(MessageType.SYMBOL_TABLE);
        if (message == null) {
            return null;
        }
        HdfBuffer body = message.body();
        return new long[] {body.getAddress(0, 8), body.getAddress(8, 8)};
    }

    // ------------------------------------------------------------------ attributes

    /**
     * An attribute of an object: its name, the message's (or dense record's) flags and creation order, and
     * its message; for one kept in the shared-message table (flagged shared, 0x02), the message's ID in the
     * shared-message heap, and as its message the header message that names it there.
     */
    record StoredAttribute(String name, int flags, int creationOrder, byte[] message, byte[] sharedId) {
        StoredAttribute(String name, int flags, int creationOrder, byte[] message) {
            this(name, flags, creationOrder, message, null);
        }
    }

    /** An object's attributes in dense storage: its heap's (or the shared-message heap's), through its name index. */
    List<StoredAttribute> denseAttributes(long heapAddress, long nameIndex) {
        FractalHeap heap = FractalHeap.parse(ctx, heapAddress);
        List<StoredAttribute> attributes = new ArrayList<>();
        int id = heap.idLength();
        for (byte[] record : BTreeV2.readRecords(ctx, nameIndex)) {
            int flags = record[id] & 0xff;
            byte[] heapId = Arrays.copyOfRange(record, 0, id);
            int order = (int) (u64(record, id + 1) & 0xffff_ffffL);
            if ((flags & 0x02) != 0) {
                attributes.add(new StoredAttribute(attributeName(sharedMessage(heapId, MessageType.ATTRIBUTE)), flags,
                        order, sharedBody(heapId), heapId));
            } else {
                byte[] message = heap.readObject(heapId);
                attributes.add(new StoredAttribute(attributeName(message), flags, order, message));
            }
        }
        return attributes;
    }

    /** The body of the message of {@code type} kept in the shared-message heap under {@code heapId}. */
    byte[] sharedMessage(byte[] heapId, int type) {
        HeaderMessage message = SharedMessage.heapMessage(ctx, heapId, type);
        return message.buffer().getBytes(message.bodyOffset(), message.bodySize());
    }

    /** The file's shared-message table, or null if it has none. */
    SharedMessages sharedMessages() {
        return SharedMessages.of(ctx);
    }

    /** The body of a header message kept in the shared-message heap under {@code heapId} (version 3, type 1). */
    static byte[] sharedBody(byte[] heapId) {
        byte[] body = new byte[10];
        body[0] = 3;
        body[1] = 1;
        System.arraycopy(heapId, 0, body, 2, 8);
        return body;
    }

    /** The shared-message heap ID a shared message's body names, or null if it names another object's header. */
    static byte[] sharedId(byte[] body) {
        return body.length >= 10 && body[0] == 3 && body[1] == 1 ? Arrays.copyOfRange(body, 2, 10) : null;
    }

    /** An Attribute message's name: after its 8-byte start in versions 1 and 2, its 9-byte start in 3. */
    static String attributeName(byte[] message) {
        int version = message[0] & 0xff;
        int nameSize = (message[2] & 0xff) | (message[3] & 0xff) << 8; // with its terminator
        int start = version >= 3 ? 9 : 8;
        int length = Math.max(0, nameSize - 1);
        while (length > 0 && message[start + length - 1] == 0) {
            length--;
        }
        return new String(message, start, length, StandardCharsets.UTF_8);
    }

    private static long u64(byte[] b, int at) {
        long v = 0;
        for (int i = 0; i < 8 && at + i < b.length; i++) {
            v |= (b[at + i] & 0xffL) << (8 * i);
        }
        return v;
    }
}
