package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.btree.BTreeV2;
import com.ebremer.falcon.core.checksum.Lookup3;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.ObjectHeader;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.write.BTreeV2Writer;
import com.ebremer.falcon.hdf5.write.GrowBuffer;
import com.ebremer.falcon.hdf5.write.ObjectHeaderEditor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared-message table (SOHM, spec section III.D) of a file an {@link Hdf5Writer} changes (P2 WF10): an
 * object that stops holding a message kept in the shared-message heap (an attribute deleted or replaced, a
 * dataspace the dataset no longer shares as it grows) releases it, as libhdf5's {@code H5SM_delete} does: the
 * message's count in its index drops, and a message no object holds leaves the index. Its copy stays in the
 * shared-message heap, as unused space (libhdf5 frees it; the heap's other objects are untouched).
 *
 * <p>The table holds up to 8 indexes, each of some message types, as a list (an {@code SMLI} block of
 * fixed-size records, rewritten in place) or a version-2 B-tree of record type 7 (written anew, the table
 * pointing at it). A record is 17 bytes: its location (0 in the heap, 1 in an object header), the message's
 * hash, then a heap message's count and heap ID, or an object header message's type, index and header.
 */
final class SharedMessages {

    /**
     * A message an object no longer holds: its type, and its ID in the shared-message heap; or, for a message
     * the object keeps in its own header marked shareable (libhdf5 counts it in the index too), its encoding
     * and the header's address, by which libhdf5 finds its record (the encoding's hash).
     */
    record Release(int messageType, byte[] heapId, byte[] encoding, long header) {
        Release(int messageType, byte[] heapId) {
            this(messageType, heapId, null, -1);
        }
    }

    private static final byte[] SMLI = {'S', 'M', 'L', 'I'};
    private static final int ENTRY = 30;        // an index's entry in the table
    private static final int RECORD = 17;       // a record of an index (list or B-tree)
    private static final int BTREE_TYPE = 7;    // H5B2_SOHM_INDEX_ID
    private static final int IN_HEAP = 0;
    private static final int IN_HEADER = 1;

    private final FileContext ctx;
    private final long tableAddress;
    private final int indexes;

    private SharedMessages(FileContext ctx, long tableAddress, int indexes) {
        this.ctx = ctx;
        this.tableAddress = tableAddress;
        this.indexes = indexes;
    }

    /** The file's table, or null if it has none. */
    static SharedMessages of(FileContext ctx) {
        long extension = ctx.superblockExtensionAddress();
        if (extension == HdfBuffer.UNDEFINED_ADDRESS) {
            return null;
        }
        HeaderMessage message = ObjectHeader.parse(ctx, extension).find(MessageType.SHARED_MESSAGE_TABLE);
        if (message == null) {
            return null;
        }
        HdfBuffer body = message.body();
        return new SharedMessages(ctx, body.getAddress(1, 8), body.getUnsignedByte(9));
    }

    /**
     * Releases {@code releases} (each once per object that held it): returns the table and the lists to
     * write over the file, and writes the B-trees rewritten into {@code buf}.
     *
     * @throws HdfFormatException if a message released is not in its index
     */
    List<ObjectHeaderEditor.Patch> release(GrowBuffer buf, List<Release> releases) {
        byte[] table = ctx.buffer().getBytes(tableAddress, 4 + indexes * ENTRY + 4);
        Map<Integer, List<Release>> byIndex = new LinkedHashMap<>();
        for (Release release : releases) {
            byIndex.computeIfAbsent(index(table, release.messageType()), i -> new ArrayList<>()).add(release);
        }
        List<ObjectHeaderEditor.Patch> patches = new ArrayList<>();
        for (Map.Entry<Integer, List<Release>> entry : byIndex.entrySet()) {
            int at = 4 + entry.getKey() * ENTRY;
            boolean list = table[at + 1] == 0;
            int listMax = u16(table, at + 8);
            int count = u16(table, at + 12);
            long address = u64(table, at + 14);
            List<byte[]> records = list ? listRecords(address, count) : BTreeV2.readRecords(ctx, address);
            for (Release release : entry.getValue()) {
                releaseOne(records, release, release.heapId() != null ? -1 : find(records, release));
            }
            putU16(table, at + 12, records.size());
            if (list) {
                patches.add(new ObjectHeaderEditor.Patch(address, listImage(records, listMax)));
            } else {
                putU64(table, at + 14, BTreeV2Writer.write(buf, BTREE_TYPE, RECORD, records));
            }
        }
        putU32(table, table.length - 4, Lookup3.hashLittle(table, 0, table.length - 4, 0));
        patches.add(new ObjectHeaderEditor.Patch(tableAddress, table));
        return patches;
    }

    /** How many objects hold the message of {@code type} kept under {@code heapId}: its index's count, or 0. */
    int count(int type, byte[] heapId) {
        byte[] table = ctx.buffer().getBytes(tableAddress, 4 + indexes * ENTRY + 4);
        int at = 4 + index(table, type) * ENTRY;
        long address = u64(table, at + 14);
        List<byte[]> records = table[at + 1] == 0 ? listRecords(address, u16(table, at + 12))
                : BTreeV2.readRecords(ctx, address);
        for (byte[] record : records) {
            if (record[0] == IN_HEAP && Arrays.equals(record, 9, 17, heapId, 0, 8)) {
                return (int) u32(record, 5);
            }
        }
        return 0;
    }

    /** The index that holds messages of {@code type} (libhdf5 shares the old fill value message as the new). */
    private int index(byte[] table, int type) {
        int kind = type == MessageType.FILL_VALUE_OLD ? MessageType.FILL_VALUE : type;
        for (int i = 0; i < indexes; i++) {
            if ((u16(table, 4 + i * ENTRY + 2) & (1 << kind)) != 0) {
                return i;
            }
        }
        throw new HdfFormatException("no shared-message index holds message type " + type);
    }

    /**
     * One holder fewer: the record's count drops, and at 0 (or for a message kept in an object header) the
     * record leaves the index. A message kept in its own header that its index does not hold (libhdf5 never
     * counted it) has nothing to release.
     *
     * @param found the record of a message kept in a header (see {@link #find}), or -1 to find it by heap ID
     */
    private static void releaseOne(List<byte[]> records, Release release, int found) {
        if (release.heapId() == null && found < 0) {
            return;
        }
        for (int i = found >= 0 ? found : 0; i < records.size(); i++) {
            byte[] record = records.get(i);
            if (i == found || (record[0] == IN_HEAP && Arrays.equals(record, 9, 17, release.heapId(), 0, 8))) {
                long count = u32(record, 5);
                if (record[0] == IN_HEADER || count <= 1) {
                    records.remove(i);
                } else {
                    putU32(record, 5, count - 1);
                }
                return;
            }
        }
        throw new HdfFormatException("a shared message (type " + release.messageType() + ") is not in its index");
    }

    /**
     * The record of a message kept in its own header, as libhdf5 finds it ({@code H5SM__find_in_list}, or its
     * B-tree search): of the hash of the message's encoding (lookup3, seeded with its type), and in the heap
     * with the same encoding, or in that header; -1 if there is none.
     */
    private int find(List<byte[]> records, Release release) {
        int hash = Lookup3.hashLittle(release.encoding(), 0, release.encoding().length, release.messageType());
        for (int i = 0; i < records.size(); i++) {
            byte[] record = records.get(i);
            if ((int) u32(record, 1) != hash) {
                continue;
            }
            if (record[0] == IN_HEADER && u64(record, 9) == release.header() && record[6] == release.messageType()) {
                return i;
            }
            if (record[0] == IN_HEAP && Arrays.equals(release.encoding(),
                    heapMessage(Arrays.copyOfRange(record, 9, 17), release.messageType()))) {
                return i;
            }
        }
        return -1;
    }

    /** The encoding of the message of {@code type} kept under {@code heapId} in the shared-message heap. */
    private byte[] heapMessage(byte[] heapId, int type) {
        HeaderMessage message = com.ebremer.falcon.hdf5.header.SharedMessage.heapMessage(ctx, heapId, type);
        return message.buffer().getBytes(message.bodyOffset(), message.bodySize());
    }

    /** A list index's records, after its signature. */
    private List<byte[]> listRecords(long address, int count) {
        HdfBuffer buf = ctx.buffer();
        if (!buf.hasSignature(address, SMLI)) {
            throw new HdfFormatException("expected shared-message list signature 'SMLI' at " + address);
        }
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(buf.getBytes(address + 4 + (long) i * RECORD, RECORD));
        }
        return records;
    }

    /**
     * A list index's block, as libhdf5 serializes it ({@code H5SM__cache_list_serialize}): its records, the
     * checksum right after them, then zeros to the block's size (room for {@code listMax} records).
     */
    private static byte[] listImage(List<byte[]> records, int listMax) {
        byte[] image = new byte[4 + Math.max(listMax, records.size()) * RECORD + 4];
        System.arraycopy(SMLI, 0, image, 0, 4);
        int p = 4;
        for (byte[] record : records) {
            System.arraycopy(record, 0, image, p, RECORD);
            p += RECORD;
        }
        putU32(image, p, Lookup3.hashLittle(image, 0, p, 0));
        return image;
    }

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
