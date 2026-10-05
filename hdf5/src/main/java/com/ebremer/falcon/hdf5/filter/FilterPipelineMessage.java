package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the Filter Pipeline message (type 11), spec section IV.A.2.
 *
 * <ul>
 *   <li><b>Version 1</b>: {@code version, count, reserved(6)}, then per filter
 *       {@code id(2), nameLen(2), flags(2), nValues(2)}, an 8-byte-padded, NUL-terminated name, and
 *       client-data values padded to a multiple of 8 bytes.</li>
 *   <li><b>Version 2</b>: {@code version, count}, then per filter {@code id(2), [nameLen(2) if
 *       id >= 256], flags(2), nValues(2)}, the name, and client-data values (no padding).</li>
 * </ul>
 */
public final class FilterPipelineMessage {

    private FilterPipelineMessage() {
    }

    public static FilterPipeline parse(HdfBuffer buf, long off) {
        int version = buf.getUnsignedByte(off);
        int count = buf.getUnsignedByte(off + 1);
        List<FilterPipeline.Filter> filters = new ArrayList<>(count);

        if (version == 1) {
            long p = off + 8; // version, count, reserved(2), reserved(4)
            for (int i = 0; i < count; i++) {
                int id = buf.getUnsignedShort(p);
                int nameLength = buf.getUnsignedShort(p + 2);
                int flags = buf.getUnsignedShort(p + 4);
                int values = buf.getUnsignedShort(p + 6);
                p += 8;
                String name = readName(buf, p, nameLength);
                if (nameLength > 0) {
                    p += nameLength;
                    if (nameLength % 8 != 0) {
                        p += 8 - (nameLength % 8);
                    }
                }
                int[] clientData = readClientData(buf, p, values);
                p += 4L * values;
                if (values % 2 == 1) {
                    p += 4; // pad client data to a multiple of 8 bytes
                }
                filters.add(new FilterPipeline.Filter(id, flags, clientData, name));
            }
        } else if (version == 2) {
            long p = off + 2;
            for (int i = 0; i < count; i++) {
                int id = buf.getUnsignedShort(p);
                p += 2;
                int nameLength = 0;
                if (id >= 256) {
                    nameLength = buf.getUnsignedShort(p);
                    p += 2;
                }
                int flags = buf.getUnsignedShort(p);
                int values = buf.getUnsignedShort(p + 2);
                p += 4;
                String name = readName(buf, p, nameLength);
                p += nameLength;
                int[] clientData = readClientData(buf, p, values);
                p += 4L * values;
                filters.add(new FilterPipeline.Filter(id, flags, clientData, name));
            }
        } else {
            throw new HdfFormatException("unsupported filter pipeline message version " + version);
        }
        return new FilterPipeline(filters);
    }

    /** The filter's name, up to its NUL terminator, or null if the message stores none. */
    private static String readName(HdfBuffer buf, long p, int nameLength) {
        if (nameLength == 0) {
            return null;
        }
        byte[] bytes = buf.getBytes(p, nameLength);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static int[] readClientData(HdfBuffer buf, long p, int values) {
        int[] clientData = new int[values];
        for (int j = 0; j < values; j++) {
            clientData[j] = (int) buf.getUnsignedInt(p + 4L * j);
        }
        return clientData;
    }
}
