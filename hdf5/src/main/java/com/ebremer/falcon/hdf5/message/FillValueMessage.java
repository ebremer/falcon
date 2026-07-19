package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Parser for the Fill Value message (type 5) and the old Fill Value message (type 4).
 *
 * <p>Returns the raw fill-value bytes, or {@code null} when no fill value is defined (callers then use
 * a default of all-zero bytes).
 *
 * <ul>
 *   <li><b>Type 5, versions 1–2</b>: {@code version, allocTime, writeTime, defined}, then
 *       {@code size(4) + value} (present unconditionally in v1; only when {@code defined} in v2).</li>
 *   <li><b>Type 5, version 3</b>: a flags byte (bit 5 = fill value defined), then {@code size(4) + value}.</li>
 *   <li><b>Type 4</b>: {@code size(4) + value}.</li>
 * </ul>
 */
public final class FillValueMessage {

    private FillValueMessage() {
    }

    public static byte[] parse(HdfBuffer buf, long off, int messageType) {
        if (messageType == 4) {
            int size = (int) buf.getUnsignedInt(off);
            return size == 0 ? null : buf.getBytes(off + 4, size);
        }

        int version = buf.getUnsignedByte(off);
        switch (version) {
            case 1: {
                int size = (int) buf.getUnsignedInt(off + 4);
                return size == 0 ? null : buf.getBytes(off + 8, size);
            }
            case 2: {
                int defined = buf.getUnsignedByte(off + 3);
                if (defined == 0) {
                    return null;
                }
                int size = (int) buf.getUnsignedInt(off + 4);
                return size == 0 ? null : buf.getBytes(off + 8, size);
            }
            case 3: {
                int flags = buf.getUnsignedByte(off + 1);
                boolean fillDefined = (flags & 0x20) != 0;
                if (!fillDefined) {
                    return null;
                }
                int size = (int) buf.getUnsignedInt(off + 2);
                return size == 0 ? null : buf.getBytes(off + 6, size);
            }
            default:
                return null;
        }
    }
}
