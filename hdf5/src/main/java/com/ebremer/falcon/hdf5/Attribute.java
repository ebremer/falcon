package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.data.VlenSequences;
import com.ebremer.falcon.hdf5.data.VlenStrings;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.io.FileContext;
import java.lang.foreign.MemorySegment;

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
        if (datatype instanceof Datatype.VariableLength vlen) {
            if (vlen.kind() == Datatype.VlenKind.STRING) {
                return readStrings();
            }
            return switch (vlen.base()) {
                case Datatype.FixedPoint fp -> fp.size() <= 4 ? readVlenInts() : readVlenLongs();
                case Datatype.FloatingPoint fp -> readVlenDoubles();
                default -> throw new HdfUnsupportedException("reading attribute variable-length sequences of "
                        + vlen.base().typeClass() + " is not yet supported: " + name);
            };
        }
        if (datatype instanceof Datatype.Reference ref && ref.kind() == Datatype.ReferenceKind.OBJECT) {
            return readObjectReferences();
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

    /** Convenience for a scalar (single-element) string attribute. */
    public String readString() {
        requireSingleElement("readString");
        return readStrings()[0];
    }

    /** Convenience for a scalar (single-element) integer attribute. */
    public int readInt() {
        requireSingleElement("readInt");
        return readInts()[0];
    }

    /** Convenience for a scalar (single-element) integer attribute (up to 8 bytes). */
    public long readLong() {
        requireSingleElement("readLong");
        return readLongs()[0];
    }

    /** Convenience for a scalar (single-element) floating-point attribute. */
    public double readDouble() {
        requireSingleElement("readDouble");
        return readDoubles()[0];
    }

    private void requireSingleElement(String op) {
        long n = dataspace.elementCount();
        if (n != 1) {
            throw new HdfUnsupportedException(op + " requires a single-element attribute, but '" + name + "' has " + n);
        }
    }

    /** Reads a variable-length sequence attribute, one {@code int[]} row per element. */
    public int[][] readVlenInts() {
        return VlenSequences.toInts(ctx, data(), count(), requireVlenSequence());
    }

    /** Reads a variable-length sequence attribute, one {@code long[]} row per element. */
    public long[][] readVlenLongs() {
        return VlenSequences.toLongs(ctx, data(), count(), requireVlenSequence());
    }

    /** Reads a variable-length sequence attribute, one {@code double[]} row per element. */
    public double[][] readVlenDoubles() {
        return VlenSequences.toDoubles(ctx, data(), count(), requireVlenSequence());
    }

    /** Reads a variable-length sequence attribute, one {@code float[]} row per element. */
    public float[][] readVlenFloats() {
        return VlenSequences.toFloats(ctx, data(), count(), requireVlenSequence());
    }

    private Datatype.VariableLength requireVlenSequence() {
        if (datatype instanceof Datatype.VariableLength vlen && vlen.kind() == Datatype.VlenKind.SEQUENCE) {
            return vlen;
        }
        throw new HdfUnsupportedException("readVlen* requires a variable-length sequence attribute: " + name);
    }

    /** Resolves an object-reference attribute to the object(s) it points at (null for a null reference). */
    public Hdf5Object[] readObjectReferences() {
        if (!(datatype instanceof Datatype.Reference ref) || ref.kind() != Datatype.ReferenceKind.OBJECT) {
            throw new HdfUnsupportedException("readObjectReferences requires an object-reference attribute: " + name);
        }
        return Hdf5Object.resolveObjectReferences(ctx, data(), count(), datatype.size());
    }

    private String[] readVariableLengthStrings(Datatype.VariableLength vlen) {
        return VlenStrings.read(ctx, data(), count(), vlen);
    }

    private MemorySegment data() {
        return ctx.buffer().segmentSlice(dataOffset, dataSize);
    }

    private int count() {
        return com.ebremer.falcon.hdf5.data.Elements.checkedInt(dataspace.elementCount());
    }
}
