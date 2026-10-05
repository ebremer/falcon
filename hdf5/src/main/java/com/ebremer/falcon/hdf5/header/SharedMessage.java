package com.ebremer.falcon.hdf5.header;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * A shared header message (spec section IV.A.2.* "Shared Message"): when a header message's flags
 * carry the {@link #SHARED_FLAG} bit, its body is not the message itself but a small record locating
 * the real message elsewhere. The common case is a <b>committed (named) datatype</b> — a datatype
 * stored once in its own object header and referenced by many datasets and attributes.
 *
 * <p>Three body layouts exist, all locating another object header:
 * <ul>
 *   <li><b>version 1</b>: version(1), type(1), reserved(6), then a symbol-table entry: a local-heap
 *       offset(L), which is skipped, and the address(O) (libhdf5's {@code H5O__shared_decode});</li>
 *   <li><b>version 2</b>: version(1), type(1), address(O);</li>
 *   <li><b>version 3</b>: version(1), type(1), then either an object-header address (committed) or a
 *       fractal-heap ID (a Shared Object Header Message stored in the SOHM heap).</li>
 * </ul>
 * Any shareable message type may be shared this way (dataspace, datatype, fill value, filter pipeline,
 * attribute); {@link #resolve} follows the reference for all of them. The SOHM-heap form (used to
 * deduplicate messages across a file) is not yet supported and is reported as such rather than misread.
 */
public final class SharedMessage {

    /** Header-message flag bit: the message body is stored in a shared location. */
    public static final int SHARED_FLAG = 0x02;

    private static final int TYPE_SOHM_HEAP = 1;   // H5O_SHARE_TYPE_SOHM: message lives in the SOHM heap
    private static final int TYPE_COMMITTED = 2;   // H5O_SHARE_TYPE_COMMITTED: message in another object header

    private static final int MAX_DEPTH = 16;

    private SharedMessage() {
    }

    /** The message {@code message} stands for: itself, or the message its shared body points at. */
    public static HeaderMessage resolve(FileContext ctx, HeaderMessage message) {
        return isShared(message) ? target(ctx, message.bodyOffset(), message.type(), 0) : message;
    }

    /**
     * The message of {@code type} that the shared-message body at {@code body} points at (in another
     * object header, following further shared references).
     *
     * @throws HdfUnsupportedException for a message in the SOHM heap
     */
    public static HeaderMessage target(FileContext ctx, long body, int type) {
        return target(ctx, body, type, 0);
    }

    private static HeaderMessage target(FileContext ctx, long body, int type, int depth) {
        if (depth > MAX_DEPTH) {
            throw new HdfFormatException("shared message reference chain too deep (possible cycle) at " + body);
        }
        long address = objectHeaderAddress(ctx, body);
        HeaderMessage message = ObjectHeader.parse(ctx, address).find(type);
        if (message == null) {
            throw new HdfFormatException("shared message (type " + type + ") points at the object header at "
                    + address + ", which has no such message");
        }
        return isShared(message) ? target(ctx, message.bodyOffset(), type, depth + 1) : message;
    }

    /** True if this header message stores its body in a shared location. */
    public static boolean isShared(HeaderMessage message) {
        return (message.flags() & SHARED_FLAG) != 0;
    }

    /**
     * Resolves a shared-message body to the address of the object header that stores the real message.
     *
     * @param body absolute file offset of the shared-message body
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
                    throw new HdfUnsupportedException("shared object header messages (SOHM, stored once in"
                            + " the file's shared-message heap) are not yet supported");
                }
                yield buf.getAddress(body + 2, offsets); // committed: object-header address
            }
            default -> throw new HdfFormatException("unknown shared message version " + version);
        };
    }
}
