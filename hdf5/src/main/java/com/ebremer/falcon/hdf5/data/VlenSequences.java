package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.lang.foreign.MemorySegment;

/**
 * Variable-length <em>sequence</em> data (ragged arrays): a class-9 datatype whose kind is
 * {@code SEQUENCE}. Each element is a "global heap ID" — {@code length(4) · collection address(O) ·
 * object index(4)} — where {@code length} is the number of base-type elements in that row; the base
 * elements live contiguously in the referenced global-heap object. An empty row has length 0 and no
 * heap reference.
 *
 * <p>Each row's bytes are elements of the sequence's base datatype, decoded as a dataset of that type
 * would be, so byte order and precision are honoured exactly.
 */
public final class VlenSequences {

    private VlenSequences() {
    }

    /** The raw global-heap bytes of sequence element {@code i} (an empty row yields no bytes). */
    public static byte[] row(FileContext ctx, MemorySegment data, int i, Datatype.VariableLength vlen) {
        HdfBuffer buf = new HdfBuffer(data);
        int offsets = ctx.sizeOfOffsets();
        long p = (long) i * vlen.size();
        long length = buf.getUnsignedInt(p);
        if (length == 0) {
            return new byte[0]; // empty sequence: collection address is undefined, do not dereference
        }
        long collection = buf.getAddress(p + 4, offsets);
        int index = (int) buf.getUnsignedInt(p + 4 + offsets);
        return GlobalHeap.readObject(ctx, collection, index);
    }
}
