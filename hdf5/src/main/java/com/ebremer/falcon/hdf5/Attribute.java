package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.heap.GlobalHeap;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * A named attribute on a {@link Hdf5Object}: a small typed, shaped value. Read it with the typed
 * accessors, or {@link #read()} for the natural Java type. Variable-length string values are resolved
 * through the global heap.
 */
public final class Attribute {

    private final FileContext ctx;
    private final String name;
    private final Datatype datatype;
    private final Dataspace dataspace;
    private final long dataOffset;
    private final int dataSize;

    /** Constructs an attribute value. Normally obtained via {@link Hdf5Object#attributes()}. */
    public Attribute(FileContext ctx, String name, Datatype datatype, Dataspace dataspace,
                     long dataOffset, int dataSize) {
        this.ctx = ctx;
        this.name = name;
        this.datatype = datatype;
        this.dataspace = dataspace;
        this.dataOffset = dataOffset;
        this.dataSize = dataSize;
    }

    public String name() {
        return name;
    }

    public Datatype datatype() {
        return datatype;
    }

    public Dataspace dataspace() {
        return dataspace;
    }

    /** Reads the value into the most natural Java array (see {@link Dataset#read()}). */
    public Object read() {
        if (datatype instanceof Datatype.FixedPoint fp) {
            return fp.size() <= 4 ? readInts() : readLongs();
        }
        if (datatype instanceof Datatype.FloatingPoint) {
            return readDoubles();
        }
        if (datatype instanceof Datatype.StringType) {
            return readStrings();
        }
        if (datatype instanceof Datatype.VariableLength vlen && vlen.kind() == Datatype.VlenKind.STRING) {
            return readStrings();
        }
        throw new HdfUnsupportedException(
                "reading attribute datatype " + datatype.typeClass() + " is not yet supported: " + name);
    }

    public int[] readInts() {
        return Elements.toInts(data(), count(), datatype);
    }

    public long[] readLongs() {
        return Elements.toLongs(data(), count(), datatype);
    }

    public float[] readFloats() {
        return Elements.toFloats(data(), count(), datatype);
    }

    public double[] readDoubles() {
        return Elements.toDoubles(data(), count(), datatype);
    }

    public String[] readStrings() {
        if (datatype instanceof Datatype.VariableLength vlen && vlen.kind() == Datatype.VlenKind.STRING) {
            return readVariableLengthStrings(vlen);
        }
        return Elements.toStrings(data(), count(), datatype);
    }

    /** Convenience for a scalar string attribute. */
    public String readString() {
        return readStrings()[0];
    }

    private String[] readVariableLengthStrings(Datatype.VariableLength vlen) {
        int count = count();
        int stride = vlen.size();
        var charset = vlen.characterSet() == Datatype.CharacterSet.UTF8
                ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII;
        HdfBuffer buf = ctx.buffer();
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            long p = dataOffset + (long) i * stride;
            long length = buf.getUnsignedInt(p);
            long collection = buf.getAddress(p + 4, ctx.sizeOfOffsets());
            int index = (int) buf.getUnsignedInt(p + 4 + ctx.sizeOfOffsets());
            byte[] bytes = GlobalHeap.readObject(ctx, collection, index);
            out[i] = new String(bytes, 0, (int) Math.min(length, bytes.length), charset);
        }
        return out;
    }

    private MemorySegment data() {
        return ctx.buffer().segment().asSlice(dataOffset, dataSize);
    }

    private int count() {
        return Math.toIntExact(dataspace.elementCount());
    }
}
