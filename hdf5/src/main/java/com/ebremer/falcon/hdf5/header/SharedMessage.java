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
 *   <li><b>version 1</b>: version(1), type(1), reserved(6), address(O);</li>
 *   <li><b>version 2</b>: version(1), type(1), address(O);</li>
 *   <li><b>version 3</b>: version(1), type(1), then either an object-header address (committed) or a
 *       fractal-heap ID (a Shared Object Header Message stored in the SOHM heap).</li>
 * </ul>
 * The SOHM-heap form (used to deduplicate arbitrary messages across a file) is not yet supported.
 */
public final class SharedMessage {

    /** Header-message flag bit: the message body is stored in a shared location. */
    public static final int SHARED_FLAG = 0x02;

    private static final int TYPE_SOHM_HEAP = 1;   // H5O_SHARE_TYPE_SOHM: message lives in the SOHM heap
    private static final int TYPE_COMMITTED = 2;   // H5O_SHARE_TYPE_COMMITTED: message in another object header

    private SharedMessage() {
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
            case 1 -> buf.getAddress(body + 8, offsets);  // version, type, 6 reserved, address
            case 2 -> buf.getAddress(body + 2, offsets);  // version, type, address
            case 3 -> {
                int type = buf.getUnsignedByte(body + 1);
                if (type == TYPE_SOHM_HEAP) {
                    throw new HdfUnsupportedException(
                            "shared messages stored in the SOHM heap are not yet supported");
                }
                yield buf.getAddress(body + 2, offsets); // committed: object-header address
            }
            default -> throw new HdfFormatException("unknown shared message version " + version);
        };
    }
}
