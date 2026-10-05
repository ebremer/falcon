package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.io.FileContext;
import java.lang.foreign.MemorySegment;

/**
 * A named attribute on a {@link Hdf5Object}: a small typed, shaped value. Read it with the typed
 * accessors, or {@link #read()} for the natural Java type; they read as a dataset's do (see
 * {@link Dataset#read()}). Variable-length string values are resolved through the global heap.
 * Obtained from {@link Hdf5Object#attributes()} and {@link Hdf5Object#attribute(String)}.
 */
public final class Attribute {

    private final FileContext ctx;
    private final String name;
    private final Datatype datatype;   // the type read: the attribute's, or a member's
    private final Dataspace dataspace;
    private final long dataOffset;
    private final int dataSize;
    private final int elementSize;     // the stored element's size (the attribute's own datatype's)
    private final int memberOffset;    // where in each stored element the type read lies
    private final boolean member;      // true for a member of a compound attribute

    Attribute(FileContext ctx, String name, Datatype datatype, Dataspace dataspace, long dataOffset, int dataSize) {
        this(ctx, name, datatype, dataspace, dataOffset, dataSize, datatype.size(), 0, false);
    }

    private Attribute(FileContext ctx, String name, Datatype datatype, Dataspace dataspace, long dataOffset,
                      int dataSize, int elementSize, int memberOffset, boolean member) {
        this.ctx = ctx;
        this.name = name;
        this.datatype = datatype;
        this.dataspace = dataspace;
        this.dataOffset = dataOffset;
        this.dataSize = dataSize;
        this.elementSize = elementSize;
        this.memberOffset = memberOffset;
        this.member = member;
    }

    /** The attribute's name. */
    public String name() {
        return name;
    }

    /** The attribute's datatype; for a {@linkplain #member(String) member}, the member's. */
    public Datatype datatype() {
        return datatype;
    }

    /** The attribute's shape. */
    public Dataspace dataspace() {
        return dataspace;
    }

    /**
     * The member {@code name} of each element of a compound attribute (or of a member that is itself a
     * compound): an attribute of the same name and shape whose datatype is the member's, read like one.
     *
     * @throws IllegalArgumentException if the datatype is not a compound
     * @throws java.util.NoSuchElementException if it has no member of that name
     */
    public Attribute member(String name) {
        Datatype.Compound.Member m = ElementReader.member(datatype, name, describe());
        return new Attribute(ctx, this.name, m.type(), dataspace, dataOffset, dataSize, elementSize,
                memberOffset + m.offset(), true);
    }

    /** Reads the value into the most natural Java value (see {@link Dataset#read()}). */
    public Object read() {
        return reader().natural();
    }

    /** Reads an integer attribute as {@code int} values; exact, as {@link Dataset#readInts()}. */
    public int[] readInts() {
        return reader().ints();
    }

    /** Reads an integer attribute as {@code long} values; exact, as {@link Dataset#readLongs()}. */
    public long[] readLongs() {
        return reader().longs();
    }

    /** Reads a floating-point or integer attribute as {@code float} values, as {@link Dataset#readFloats()}. */
    public float[] readFloats() {
        return reader().floats();
    }

    /** Reads a floating-point or integer attribute as {@code double} values, as {@link Dataset#readDoubles()}. */
    public double[] readDoubles() {
        return reader().doubles();
    }

    /** Reads complex values as interleaved (real, imaginary) pairs, as {@link Dataset#readComplexDoubles()}. */
    public double[] readComplexDoubles() {
        return reader().complexDoubles();
    }

    /** Reads complex values as interleaved (real, imaginary) {@code float} pairs, as {@link Dataset#readComplexFloats()}. */
    public float[] readComplexFloats() {
        return reader().complexFloats();
    }

    /** Reads fixed- or variable-length strings, or enumeration names, as {@link Dataset#readStrings()}. */
    public String[] readStrings() {
        return reader().strings();
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
        return reader().vlenInts();
    }

    /** Reads a variable-length sequence attribute, one {@code long[]} row per element. */
    public long[][] readVlenLongs() {
        return reader().vlenLongs();
    }

    /** Reads a variable-length sequence attribute, one {@code double[]} row per element. */
    public double[][] readVlenDoubles() {
        return reader().vlenDoubles();
    }

    /** Reads a variable-length sequence attribute, one {@code float[]} row per element. */
    public float[][] readVlenFloats() {
        return reader().vlenFloats();
    }

    /**
     * Resolves an object-reference attribute to the object(s) it points at (null for a null reference);
     * revised references as {@link Dataset#readObjectReferences()} reads them.
     */
    public Hdf5Object[] readObjectReferences() {
        return reader().objectReferences();
    }

    /**
     * Resolves a region-reference attribute to selections of the datasets it points into (null for a
     * null reference), original or revised, as {@link Dataset#readRegionReferences()} does.
     */
    public Selection[] readRegionReferences() {
        return reader().regionReferences();
    }

    /**
     * Resolves an attribute of revised attribute references to the attributes they name (null for a null
     * reference), as {@link Dataset#readAttributeReferences()} does.
     */
    public Attribute[] readAttributeReferences() {
        return reader().attributeReferences();
    }

    /** The value's bytes as stored, in the datatype's byte order. */
    public byte[] readRawBytes() {
        return reader().rawBytes();
    }

    private ElementReader reader() {
        return new ElementReader(ctx, datatype, count(), this::data, describe());
    }

    /** The stored bytes, or for a member, its bytes from each element. */
    private MemorySegment data() {
        int count = count();
        long needed = (long) count * elementSize;
        if (needed > dataSize) {
            throw new HdfFormatException("attribute '" + name + "' holds " + dataSize + " bytes but its dataspace"
                    + " and datatype need " + needed + " (corrupt attribute message?)");
        }
        MemorySegment stored = ctx.buffer().segmentSlice(dataOffset, dataSize);
        return member ? ElementReader.column(stored, count, elementSize, memberOffset, datatype.size()) : stored;
    }

    private int count() {
        return Elements.checkedInt(dataspace.elementCount());
    }

    private String describe() {
        return "attribute '" + name + "'";
    }

    @Override
    public String toString() {
        return "Attribute[" + name + "]";
    }
}
