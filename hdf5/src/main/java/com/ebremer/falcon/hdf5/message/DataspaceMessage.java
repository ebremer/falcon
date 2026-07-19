package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.Dataspace;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;

/**
 * Parser for the Dataspace message (type 1), spec section IV.A.2 / Appendix D.A.
 *
 * <ul>
 *   <li><b>Version 1</b>: version, rank, flags, reserved(1), reserved(4), then dimensions and
 *       (if flagged) maximum dimensions. Rank 0 denotes a scalar.</li>
 *   <li><b>Version 2</b>: version, rank, flags, and an explicit type byte (scalar/simple/null).</li>
 * </ul>
 *
 * <p>Dimension sizes are "size of lengths" wide; an all-ones maximum denotes an unlimited dimension.
 */
public final class DataspaceMessage {

    private DataspaceMessage() {
    }

    public static Dataspace parse(FileContext ctx, long off) {
        HdfBuffer buf = ctx.buffer();
        int version = buf.getUnsignedByte(off);
        int rank = buf.getUnsignedByte(off + 1);
        int flags = buf.getUnsignedByte(off + 2);

        Dataspace.Kind kind;
        long p;
        if (version == 1) {
            kind = rank == 0 ? Dataspace.Kind.SCALAR : Dataspace.Kind.SIMPLE;
            p = off + 8; // version, rank, flags, reserved(1), reserved(4)
        } else if (version == 2) {
            kind = switch (buf.getUnsignedByte(off + 3)) {
                case 0 -> Dataspace.Kind.SCALAR;
                case 1 -> Dataspace.Kind.SIMPLE;
                case 2 -> Dataspace.Kind.NULL;
                default -> throw new HdfFormatException("unknown dataspace type at " + off);
            };
            p = off + 4;
        } else {
            throw new HdfFormatException("unsupported dataspace message version " + version);
        }

        int lengths = ctx.sizeOfLengths();
        long[] dims = new long[rank];
        for (int i = 0; i < rank; i++) {
            dims[i] = buf.getUnsignedValue(p, lengths);
            p += lengths;
        }

        long[] maxDims = null;
        if ((flags & 0x01) != 0) {
            maxDims = new long[rank];
            for (int i = 0; i < rank; i++) {
                long value = buf.getUnsignedValue(p, lengths);
                maxDims[i] = HdfBuffer.isAllOnes(value, lengths) ? Dataspace.UNLIMITED : value;
                p += lengths;
            }
        }
        return new Dataspace(version, kind, dims, maxDims);
    }
}
