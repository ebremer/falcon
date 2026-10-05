package com.ebremer.falcon.hdf5.header;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.heap.FractalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * A shared header message (spec section IV.A.2.* "Shared Message"): when a header message's flags
 * carry the {@link #SHARED_FLAG} bit, its body is not the message itself but a small record locating
 * the real message elsewhere. The common case is a <b>committed (named) datatype</b> — a datatype
 * stored once in its own object header and referenced by many datasets and attributes.
 *
 * <p>Three body layouts exist:
 * <ul>
 *   <li><b>version 1</b>: version(1), type(1), reserved(6), then a symbol-table entry: a local-heap
 *       offset(L), which is skipped, and the address(O) of another object header (libhdf5's
 *       {@code H5O__shared_decode});</li>
 *   <li><b>version 2</b>: version(1), type(1), address(O) of another object header;</li>
 *   <li><b>version 3</b>: version(1), type(1), then either an object-header address (type 2, committed)
 *       or an 8-byte fractal-heap ID (type 1: a Shared Object Header Message, stored once in the heap of
 *       the file's {@linkplain SharedMessageTable SOHM index} for its message type).</li>
 * </ul>
 * Any shareable message type may be shared this way (dataspace, datatype, fill value, filter pipeline,
 * attribute); {@link #resolve} follows the reference for all of them. A message in a SOHM heap is the
 * heap object itself, so it usually lies in the file; one small enough to be a "tiny" heap object lives
 * in its heap ID instead, and its {@link HeaderMessage#buffer()} is then a buffer of its own.
 */
public final class SharedMessage {

    /** Header-message flag bit: the message body is stored in a shared location. */
    public static final int SHARED_FLAG = 0x02;

    private static final int TYPE_SOHM_HEAP = 1;   // H5O_SHARE_TYPE_SOHM: message lives in the SOHM heap
    private static final int TYPE_COMMITTED = 2;   // H5O_SHARE_TYPE_COMMITTED: message in another object header

    private static final int MAX_DEPTH = 16;
    private static final int SOHM_HEAP_ID_LENGTH = 8; // H5O_FHEAP_ID_LEN

    private SharedMessage() {
    }

    /** The message {@code message} stands for: itself, or the message its shared body points at. */
    public static HeaderMessage resolve(FileContext ctx, HeaderMessage message) {
        return isShared(message) ? target(ctx, message.bodyOffset(), message.type(), 0) : message;
    }

    /**
     * The message of {@code type} that the shared-message body at {@code body} points at: in another
     * object header (following further shared references), or in the shared-message heap.
     */
    public static HeaderMessage target(FileContext ctx, long body, int type) {
        return target(ctx, body, type, 0);
    }

    private static HeaderMessage target(FileContext ctx, long body, int type, int depth) {
        if (depth > MAX_DEPTH) {
            throw new HdfFormatException("shared message reference chain too deep (possible cycle) at " + body);
        }
        HdfBuffer buf = ctx.buffer();
        if (buf.getUnsignedByte(body) == 3 && buf.getUnsignedByte(body + 1) == TYPE_SOHM_HEAP) {
            return heapMessage(ctx, buf.getBytes(body + 2, SOHM_HEAP_ID_LENGTH), type);
        }
        long address = objectHeaderAddress(ctx, body);
        HeaderMessage message = ObjectHeader.parse(ctx, address).find(type);
        if (message == null) {
            throw new HdfFormatException("shared message (type " + type + ") points at the object header at "
                    + address + ", which has no such message");
        }
        return isShared(message) ? target(ctx, message.bodyOffset(), type, depth + 1) : message;
    }

    /**
     * The message of {@code type} stored in the shared-message heap under {@code heapId}: the heap object
     * is the message body. Also used for dense attribute records flagged as shared, whose heap ID is a
     * shared-message heap ID.
     */
    public static HeaderMessage heapMessage(FileContext ctx, byte[] heapId, int type) {
        FractalHeap heap = FractalHeap.parse(ctx, ctx.sharedMessageTable().heapAddress(type));
        if (FractalHeap.isTiny(heapId)) {
            byte[] body = heap.readObject(heapId);
            return new HeaderMessage(type, 0, HdfBuffer.of(body), 0, body.length);
        }
        FractalHeap.HeapObject object = heap.locate(heapId);
        return new HeaderMessage(type, 0, ctx.buffer(), object.address(), object.length());
    }

    /** True if this header message stores its body in a shared location. */
    public static boolean isShared(HeaderMessage message) {
        return (message.flags() & SHARED_FLAG) != 0;
    }

    /**
     * Resolves a shared-message body to the address of the object header that stores the real message.
     *
     * @param body absolute file offset of the shared-message body
     * @throws HdfFormatException for a message in the shared-message heap, which no object header holds
     */
    public static long objectHeaderAddress(FileContext ctx, long body) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(body);
        int offsets = ctx.sizeOfOffsets();
        return switch (version) {
            case 1 -> buf.getAddress(body + 8 + ctx.sizeOfLengths(), offsets); // + 6 reserved, heap offset
            case 2 -> buf.getAddress(body + 2, offsets);  // version, type, address
            case 3 -> {
                int type = buf.getUnsignedByte(body + 1);
                if (type == TYPE_SOHM_HEAP) {
                    throw new HdfFormatException("a message in the shared-message heap has no object header");
                }
                yield buf.getAddress(body + 2, offsets); // committed: object-header address
            }
            default -> throw new HdfFormatException("unknown shared message version " + version);
        };
    }
}
