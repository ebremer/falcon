package com.ebremer.falcon.hdf5.header;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.checksum.MetadataChecksum;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * The file's Shared Object Header Message (SOHM) table, spec section III.D: the indexes libhdf5 uses to
 * store a message once and share it between object headers. Each index holds some message types (bit
 * {@code 1 << type} of its flags, as libhdf5's {@code H5O_SHMESG_*_FLAG}) and keeps their single copies
 * in its own fractal heap. A {@link SharedMessage} of type 1 names its copy by an 8-byte ID in the heap of
 * the index that holds its message type.
 *
 * <p>The table is found through the superblock extension's Shared Message Table message (type 15):
 * {@code version(1)=0 · table address(O) · index count(1)}. At that address: {@code "SMTB"}, then per index
 * {@code version(1)=0 · index type(1) · message type flags(2) · minimum message size(4) · list cutoff(2) ·
 * B-tree cutoff(2) · message count(2) · index address(O) · heap address(O)}, then a checksum. Reading a
 * shared message needs only the heap; the index itself (a list or a v2 B-tree) serves libhdf5's lookups
 * when it writes.
 */
public final class SharedMessageTable {

    private static final byte[] SMTB = {'S', 'M', 'T', 'B'};
    private static final int MAX_INDEXES = 8; // H5O_SHMESG_MAX_NINDEXES

    private final int[] typeFlags;
    private final long[] heapAddresses;

    private SharedMessageTable(int[] typeFlags, long[] heapAddresses) {
        this.typeFlags = typeFlags;
        this.heapAddresses = heapAddresses;
    }

    /** Reads the table named by the file's superblock extension. */
    public static SharedMessageTable parse(FileContext ctx) {
        long extension = ctx.superblockExtensionAddress();
        if (extension == HdfBuffer.UNDEFINED_ADDRESS) {
            throw new HdfFormatException("a message is stored in the shared-message heap, but the file has no"
                    + " superblock extension to locate the shared-message table");
        }
        HeaderMessage message = ObjectHeader.parse(ctx, extension).find(MessageType.SHARED_MESSAGE_TABLE);
        if (message == null) {
            throw new HdfFormatException("a message is stored in the shared-message heap, but the superblock"
                    + " extension has no shared-message table");
        }
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        long body = message.bodyOffset();
        if (message.bodySize() < 2 + offsets || buf.getUnsignedByte(body) != 0) {
            throw new HdfFormatException("unsupported shared message table message at " + body);
        }
        long address = buf.getAddress(body + 1, offsets);
        int indexes = buf.getUnsignedByte(body + 1 + offsets);
        if (indexes < 1 || indexes > MAX_INDEXES || address == HdfBuffer.UNDEFINED_ADDRESS) {
            throw new HdfFormatException("invalid shared message table (" + indexes + " indexes at " + address + ")");
        }
        if (!buf.hasSignature(address, SMTB)) {
            throw new HdfFormatException("expected shared message table signature 'SMTB' at " + address);
        }
        int entrySize = 1 + 1 + 2 + 4 + 2 + 2 + 2 + 2 * offsets;
        MetadataChecksum.verify(buf, address, 4 + (long) indexes * entrySize, "shared message table");
        int[] typeFlags = new int[indexes];
        long[] heapAddresses = new long[indexes];
        long p = address + 4;
        for (int i = 0; i < indexes; i++, p += entrySize) {
            if (buf.getUnsignedByte(p) != 0) {
                throw new HdfFormatException("unsupported shared message index version " + buf.getUnsignedByte(p) + " at " + p);
            }
            typeFlags[i] = buf.getUnsignedShort(p + 2);
            heapAddresses[i] = buf.getAddress(p + 14 + offsets, offsets); // follows the index address
        }
        return new SharedMessageTable(typeFlags, heapAddresses);
    }

    /** The address of the fractal heap that holds shared messages of {@code messageType}. */
    public long heapAddress(int messageType) {
        // libhdf5 shares the old fill value message as the new one (H5SM__type_to_flag).
        int type = messageType == MessageType.FILL_VALUE_OLD ? MessageType.FILL_VALUE : messageType;
        for (int i = 0; i < typeFlags.length; i++) {
            if ((typeFlags[i] & (1 << type)) != 0) {
                if (heapAddresses[i] == HdfBuffer.UNDEFINED_ADDRESS) {
                    throw new HdfFormatException("the shared-message index for message type " + messageType
                            + " has no heap");
                }
                return heapAddresses[i];
            }
        }
        throw new HdfFormatException("no shared-message index holds message type " + messageType);
    }
}
