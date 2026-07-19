package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.Attribute;
import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Parser for the Attribute message (type 12), spec section IV.A.2. Layout: version, then the name /
 * datatype / dataspace sizes, then the name, an embedded datatype message, an embedded dataspace
 * message, and the value. In version 1 the name/datatype/dataspace fields are each padded to a
 * multiple of 8 bytes; versions 2–3 drop the padding, and version 3 adds a name character-set byte.
 */
public final class AttributeMessage {

    private AttributeMessage() {
    }

    public static Attribute parse(FileContext ctx, HeaderMessage message) {
        HdfBuffer buf = ctx.buffer();
        long base = message.bodyOffset();
        int version = buf.getUnsignedByte(base);
        int nameSize = buf.getUnsignedShort(base + 2);
        int datatypeSize = buf.getUnsignedShort(base + 4);
        int dataspaceSize = buf.getUnsignedShort(base + 6);
        boolean padded = version == 1;

        long p = base + 8;
        if (version == 3) {
            p += 1; // name character-set encoding
        }
        String name = readName(buf, p, nameSize);
        p += padded ? align8(nameSize) : nameSize;
        long datatypeOffset = p;
        p += padded ? align8(datatypeSize) : datatypeSize;
        long dataspaceOffset = p;
        p += padded ? align8(dataspaceSize) : dataspaceSize;
        long dataOffset = p;

        Datatype datatype = DatatypeMessage.parse(buf, datatypeOffset);
        Dataspace dataspace = DataspaceMessage.parse(ctx, dataspaceOffset);
        int dataSize = (int) (base + message.bodySize() - dataOffset);
        return new Attribute(ctx, name, datatype, dataspace, dataOffset, dataSize);
    }

    private static int align8(int n) {
        return (n + 7) & ~7;
    }

    private static String readName(HdfBuffer buf, long p, int size) {
        byte[] bytes = buf.getBytes(p, size);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
