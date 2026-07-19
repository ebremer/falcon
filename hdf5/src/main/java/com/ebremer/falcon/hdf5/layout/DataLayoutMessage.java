package com.ebremer.falcon.hdf5.layout;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Parser for the Data Layout message (type 8), spec section IV.A.2.
 *
 * <p>Versions 3 and 4 encode compact as {@code size(2) + data}, contiguous as
 * {@code address(O) + size(L)}, and chunked with a class byte. Versions 1 and 2 place the layout class
 * after a dimensionality byte, with dimension sizes following.
 */
public final class DataLayoutMessage {

    private DataLayoutMessage() {
    }

    public static DataLayout parse(FileContext ctx, long off) {
        HdfBuffer buf = ctx.buffer();
        int offsets = ctx.sizeOfOffsets();
        int lengths = ctx.sizeOfLengths();
        int version = buf.getUnsignedByte(off);

        switch (version) {
            case 1:
            case 2: {
                int dimensionality = buf.getUnsignedByte(off + 1);
                int layoutClass = buf.getUnsignedByte(off + 2);
                long p = off + 8; // version, dimensionality, class, reserved(5)
                return switch (layoutClass) {
                    case 0 -> { // compact: dimension sizes, then compact data size + data
                        long dp = p + 4L * dimensionality;
                        int size = (int) buf.getUnsignedInt(dp);
                        yield new DataLayout.Compact(buf.getBytes(dp + 4, size));
                    }
                    case 1 -> new DataLayout.Contiguous(buf.getAddress(p, offsets), -1);
                    default -> new DataLayout.Chunked();
                };
            }
            case 3:
            case 4: {
                int layoutClass = buf.getUnsignedByte(off + 1);
                long p = off + 2;
                return switch (layoutClass) {
                    case 0 -> {
                        int size = buf.getUnsignedShort(p);
                        yield new DataLayout.Compact(buf.getBytes(p + 2, size));
                    }
                    case 1 -> new DataLayout.Contiguous(
                            buf.getAddress(p, offsets), buf.getUnsignedValue(p + offsets, lengths));
                    case 2 -> new DataLayout.Chunked();
                    default -> throw new HdfFormatException("unknown data layout class " + layoutClass + " at " + off);
                };
            }
            default:
                throw new HdfFormatException("unsupported data layout message version " + version);
        }
    }
}
