package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.Link;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A Link message (type 6, spec section IV.A.2.g): one link in a new-style group. Layout: version,
 * flags, then (per the flags) an optional link type, creation order, and character set, followed by the
 * name length, the name, and the link information: a hard link's target object-header address, a soft
 * link's path ({@code length(2) · path}), or an external link's value ({@code length(2) · version/flags(1)
 * · file name\0 · object path\0}).
 */
public final class LinkMessage {

    public static final int HARD = 0;
    public static final int SOFT = 1;
    public static final int EXTERNAL = 64;

    private LinkMessage() {
    }

    public static Link parse(FileContext ctx, HeaderMessage message) {
        return parse(ctx.buffer(), message.bodyOffset(), ctx.sizeOfOffsets());
    }

    /** Parses a Link message body starting at {@code start} (e.g. a fractal-heap object). */
    public static Link parse(HdfBuffer buf, long start, int sizeOfOffsets) {
        long p = start;
        int version = buf.getUnsignedByte(p);
        if (version != 1) {
            throw new HdfFormatException("unsupported link message version " + version + " at " + start);
        }
        p += 1;
        int flags = buf.getUnsignedByte(p);
        p += 1;

        int linkType = HARD;
        if ((flags & 0x08) != 0) {
            linkType = buf.getUnsignedByte(p);
            p += 1;
        }
        if ((flags & 0x04) != 0) {
            p += 8; // creation order
        }
        if ((flags & 0x10) != 0) {
            p += 1; // character set
        }
        int nameLengthSize = 1 << (flags & 0x03);
        long nameLength = buf.getUnsignedValue(p, nameLengthSize);
        if (nameLength < 1 || nameLength > Integer.MAX_VALUE) {
            throw new HdfFormatException("invalid link name length " + nameLength + " at " + start);
        }
        p += nameLengthSize;
        String name = new String(buf.getBytes(p, (int) nameLength), StandardCharsets.UTF_8);
        p += nameLength;

        return switch (linkType) {
            case HARD -> new Link.Hard(name, buf.getAddress(p, sizeOfOffsets));
            case SOFT -> {
                int length = buf.getUnsignedShort(p);
                yield new Link.Soft(name, new String(buf.getBytes(p + 2, length), StandardCharsets.UTF_8));
            }
            case EXTERNAL -> {
                int length = buf.getUnsignedShort(p);
                if (length < 3) {
                    throw new HdfFormatException("external link '" + name + "' value is too short at " + start);
                }
                byte[] value = buf.getBytes(p + 2, length);
                // value[0] is the version (0) and flags; then two null-terminated strings.
                int fileEnd = indexOfZero(value, 1);
                int pathEnd = indexOfZero(value, fileEnd + 1);
                yield new Link.External(name, new String(value, 1, fileEnd - 1, StandardCharsets.UTF_8),
                        new String(value, fileEnd + 1, pathEnd - fileEnd - 1, StandardCharsets.UTF_8));
            }
            default -> {
                if (linkType < 65) {
                    throw new HdfFormatException("reserved link type " + linkType + " at " + start);
                }
                yield new Link.UserDefined(name, linkType);
            }
        };
    }

    private static int indexOfZero(byte[] bytes, int from) {
        int i = Math.min(from, bytes.length);
        while (i < bytes.length && bytes[i] != 0) {
            i++;
        }
        return i;
    }
}
