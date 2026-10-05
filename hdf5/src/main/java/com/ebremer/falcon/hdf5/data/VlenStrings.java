package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Decodes variable-length string data. Each element is a "global heap ID" — {@code length(4) ·
 * collection address(O) · object index(4)} — that points at the string bytes in the global heap. An
 * element that was never written holds a null ID (zero length, and a zero or undefined address); it
 * reads back as the empty string, as libhdf5 does.
 */
public final class VlenStrings {

    private VlenStrings() {
    }

    public static String[] read(FileContext ctx, MemorySegment data, int count, Datatype.VariableLength vlen) {
        int stride = vlen.size();
        var charset = vlen.characterSet() == Datatype.CharacterSet.UTF8
                ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII;
        HdfBuffer buf = new HdfBuffer(data);
        int offsets = ctx.sizeOfOffsets();
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            long p = (long) i * stride;
            long length = buf.getUnsignedInt(p);
            long collection = buf.getAddress(p + 4, offsets);
            int index = (int) buf.getUnsignedInt(p + 4 + offsets);
            if (length == 0 || collection == 0 || collection == HdfBuffer.UNDEFINED_ADDRESS) {
                out[i] = ""; // null / never-written element
                continue;
            }
            byte[] bytes = GlobalHeap.readObject(ctx, collection, index);
            out[i] = new String(bytes, 0, (int) Math.min(length, bytes.length), charset);
        }
        return out;
    }
}
