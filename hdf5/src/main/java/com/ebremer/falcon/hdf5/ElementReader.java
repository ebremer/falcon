package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.data.VlenSequences;
import com.ebremer.falcon.hdf5.data.VlenStrings;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.io.FileContext;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Decodes the elements of one datatype, read from a dataset, an attribute, or a selection, into Java
 * values: the readers those three share. Composite datatypes are taken apart here:
 * <ul>
 *   <li>an <b>array</b> element is its base elements in row-major order, so the elements of an array
 *       type read as one flat array of every element's values in turn;</li>
 *   <li>a <b>compound</b> is read a member at a time ({@link #member}), each member as a column of its
 *       own, and {@link #natural()} returns the columns by name;</li>
 *   <li>an <b>enumeration</b> reads as the integers its elements hold, or as its members' names;</li>
 *   <li>a <b>complex</b> number reads as its real part where a real number is asked for, as libhdf5
 *       converts it, or as (real, imaginary) pairs;</li>
 *   <li>a <b>bit field</b> reads as the unsigned integer of its bits, as h5py reads it;</li>
 *   <li>an <b>opaque</b> element reads as its bytes.</li>
 * </ul>
 * The data is fetched only once the datatype is known to suit the read.
 */
final class ElementReader {

    private final FileContext ctx;
    private final Datatype type;
    private final int count;
    private final Supplier<MemorySegment> data;
    private final String what; // for messages: a dataset's path, "attribute 'name'"

    ElementReader(FileContext ctx, Datatype type, int count, Supplier<MemorySegment> data, String what) {
        this.ctx = ctx;
        this.type = type;
        this.count = count;
        this.data = data;
        this.what = what;
    }

    // ---------------------------------------------------------------- numbers

    int[] ints() {
        Flat flat = flatten();
        Datatype.FixedPoint fp = integerType(flat.type(), "readInts");
        return Elements.toInts(data.get(), flat.count(), fp);
    }

    long[] longs() {
        Flat flat = flatten();
        Datatype.FixedPoint fp = integerType(flat.type(), "readLongs");
        return Elements.toLongs(data.get(), flat.count(), fp);
    }

    double[] doubles() {
        Flat flat = flatten();
        if (flat.type() instanceof Datatype.Complex complex) {
            double[] pairs = Elements.toDoubles(data.get(), twice(flat.count()), complexBase(complex));
            double[] real = new double[flat.count()];
            for (int i = 0; i < real.length; i++) {
                real[i] = pairs[2 * i];
            }
            return real;
        }
        return Elements.toDoubles(data.get(), flat.count(), numericType(flat.type(), "readDoubles"));
    }

    float[] floats() {
        Flat flat = flatten();
        if (flat.type() instanceof Datatype.Complex complex) {
            float[] pairs = Elements.toFloats(data.get(), twice(flat.count()), complexBase(complex));
            float[] real = new float[flat.count()];
            for (int i = 0; i < real.length; i++) {
                real[i] = pairs[2 * i];
            }
            return real;
        }
        return Elements.toFloats(data.get(), flat.count(), numericType(flat.type(), "readFloats"));
    }

    /**
     * (real, imaginary) pairs, interleaved: from a complex type, from h5py's complex compound (two
     * floating-point members named {@code r} and {@code i}), or from a real number, whose imaginary part
     * is 0 (as libhdf5 converts a real number to a complex one).
     */
    double[] complexDoubles() {
        Flat flat = flatten();
        int n = flat.count();
        switch (flat.type()) {
            case Datatype.Complex complex -> {
                return Elements.toDoubles(data.get(), twice(n), complexBase(complex));
            }
            case Datatype.Compound compound when complexPair(compound) != null -> {
                Datatype.Compound.Member[] pair = complexPair(compound);
                MemorySegment segment = data.get();
                double[] re = Elements.toDoubles(column(segment, n, compound.size(), pair[0]), n, pair[0].type());
                double[] im = Elements.toDoubles(column(segment, n, compound.size(), pair[1]), n, pair[1].type());
                double[] out = new double[twice(n)];
                for (int i = 0; i < n; i++) {
                    out[2 * i] = re[i];
                    out[2 * i + 1] = im[i];
                }
                return out;
            }
            default -> {
                double[] re = Elements.toDoubles(data.get(), n, realType(flat.type(), "readComplexDoubles"));
                double[] out = new double[twice(n)];
                for (int i = 0; i < n; i++) {
                    out[2 * i] = re[i];
                }
                return out;
            }
        }
    }

    /** As {@link #complexDoubles()}, each part rounded to a {@code float}. */
    float[] complexFloats() {
        Flat flat = flatten();
        int n = flat.count();
        switch (flat.type()) {
            case Datatype.Complex complex -> {
                return Elements.toFloats(data.get(), twice(n), complexBase(complex));
            }
            case Datatype.Compound compound when complexPair(compound) != null -> {
                Datatype.Compound.Member[] pair = complexPair(compound);
                MemorySegment segment = data.get();
                float[] re = Elements.toFloats(column(segment, n, compound.size(), pair[0]), n, pair[0].type());
                float[] im = Elements.toFloats(column(segment, n, compound.size(), pair[1]), n, pair[1].type());
                float[] out = new float[twice(n)];
                for (int i = 0; i < n; i++) {
                    out[2 * i] = re[i];
                    out[2 * i + 1] = im[i];
                }
                return out;
            }
            default -> {
                float[] re = Elements.toFloats(data.get(), n, realType(flat.type(), "readComplexFloats"));
                float[] out = new float[twice(n)];
                for (int i = 0; i < n; i++) {
                    out[2 * i] = re[i];
                }
                return out;
            }
        }
    }

    // ---------------------------------------------------------------- strings

    /** Strings: fixed- or variable-length ones, or the names of enumeration members. */
    String[] strings() {
        Flat flat = flatten();
        return switch (flat.type()) {
            case Datatype.StringType st -> Elements.toStrings(data.get(), flat.count(), st);
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.STRING ->
                    VlenStrings.read(ctx, data.get(), flat.count(), v);
            case Datatype.Enumeration e -> enumNames(data.get(), flat.count(), e);
            default -> throw new HdfUnsupportedException("readStrings requires a string or enumeration datatype, not "
                    + flat.type().typeClass() + ": " + what);
        };
    }

    // ------------------------------------------------- variable-length sequences

    int[][] vlenInts() {
        Datatype.VariableLength vlen = vlenSequence("readVlenInts");
        return rows(vlen, int[][]::new, row -> row.ints());
    }

    long[][] vlenLongs() {
        Datatype.VariableLength vlen = vlenSequence("readVlenLongs");
        return rows(vlen, long[][]::new, row -> row.longs());
    }

    double[][] vlenDoubles() {
        Datatype.VariableLength vlen = vlenSequence("readVlenDoubles");
        return rows(vlen, double[][]::new, row -> row.doubles());
    }

    float[][] vlenFloats() {
        Datatype.VariableLength vlen = vlenSequence("readVlenFloats");
        return rows(vlen, float[][]::new, row -> row.floats());
    }

    private Datatype.VariableLength vlenSequence(String op) {
        if (flatten().type() instanceof Datatype.VariableLength vlen && vlen.kind() == Datatype.VlenKind.SEQUENCE) {
            return vlen;
        }
        throw new HdfUnsupportedException(op + " requires a variable-length sequence datatype: " + what);
    }

    /** Each sequence element's row, decoded by {@code decode} as elements of the sequence's base type. */
    private <R> R[] rows(Datatype.VariableLength vlen, java.util.function.IntFunction<R[]> array,
                         java.util.function.Function<ElementReader, R> decode) {
        int n = flatten().count();
        MemorySegment segment = data.get();
        Datatype base = vlen.base();
        if (base.size() <= 0) {
            throw new HdfFormatException("variable-length sequence of " + base.size() + "-byte elements: " + what);
        }
        R[] out = array.apply(n);
        for (int i = 0; i < n; i++) {
            byte[] row = VlenSequences.row(ctx, segment, i, vlen);
            out[i] = decode.apply(new ElementReader(ctx, base, row.length / base.size(),
                    () -> MemorySegment.ofArray(row), what));
        }
        return out;
    }

    // ------------------------------------------------------------- references

    Hdf5Object[] objectReferences() {
        Flat flat = flatten();
        boolean revised = Hdf5Object.isRevisedReference(flat.type());
        if (!revised && !Hdf5Object.isReference(flat.type(), Datatype.ReferenceKind.OBJECT)) {
            throw new HdfUnsupportedException("readObjectReferences requires an object-reference datatype: " + what);
        }
        return Hdf5Object.resolveObjectReferences(ctx, data.get(), flat.count(), flat.type().size(), revised);
    }

    Selection[] regionReferences() {
        Flat flat = flatten();
        boolean revised = Hdf5Object.isRevisedReference(flat.type());
        if (!revised && !Hdf5Object.isReference(flat.type(), Datatype.ReferenceKind.DATASET_REGION)) {
            throw new HdfUnsupportedException("readRegionReferences requires a region-reference datatype: " + what);
        }
        return Hdf5Object.resolveRegionReferences(ctx, data.get(), flat.count(), flat.type().size(), revised);
    }

    Attribute[] attributeReferences() {
        Flat flat = flatten();
        if (!Hdf5Object.isRevisedReference(flat.type())) {
            throw new HdfUnsupportedException("readAttributeReferences requires a revised reference datatype: " + what);
        }
        return Hdf5Object.resolveAttributeReferences(ctx, data.get(), flat.count(), flat.type().size());
    }

    // ------------------------------------------------------------- raw, natural

    /** The elements' bytes as stored, in the datatype's byte order. */
    byte[] rawBytes() {
        return Elements.toRawBytes(data.get(), (long) count * type.size());
    }

    /**
     * The most natural Java value for the elements (see {@code Dataset.read()}): an array of the
     * elements' values, or, for a compound, each member's by name.
     */
    Object natural() {
        Flat flat = flatten();
        int n = flat.count();
        return switch (flat.type()) {
            case Datatype.FixedPoint fp -> Elements.toNaturalIntegers(data.get(), n, fp);
            case Datatype.FloatingPoint fp -> Elements.toDoubles(data.get(), n, fp);
            case Datatype.StringType st -> strings();
            case Datatype.VariableLength v when v.kind() == Datatype.VlenKind.STRING -> strings();
            case Datatype.VariableLength v -> naturalRows(v);
            case Datatype.Reference r when r.kind() == Datatype.ReferenceKind.DATASET_REGION -> regionReferences();
            case Datatype.Reference r when r.kind() != Datatype.ReferenceKind.OTHER -> objectReferences();
            case Datatype.Enumeration e -> strings();
            case Datatype.Compound c -> columns(c, n);
            case Datatype.Complex c -> complexDoubles();
            case Datatype.BitField b -> Elements.toNaturalIntegers(data.get(), n, unsigned(b));
            case Datatype.Opaque o -> elementBytes(data.get(), n, o.size());
            default -> throw new HdfUnsupportedException(
                    "reading datatype class " + flat.type().typeClass() + " is not yet supported: " + what);
        };
    }

    /** A sequence's rows in their most natural form: {@code int[][]}, {@code long[][]}, {@code double[][]}, or {@code Object[]}. */
    private Object naturalRows(Datatype.VariableLength vlen) {
        return switch (vlen.base()) {
            case Datatype.FixedPoint fp when fp.size() <= 4 && Elements.fitsInt(fp) -> vlenInts();
            case Datatype.FixedPoint fp -> vlenLongs();
            case Datatype.FloatingPoint fp -> vlenDoubles();
            default -> rows(vlen, Object[]::new, ElementReader::natural);
        };
    }

    /** A compound's members, each the natural value of its column, by name in member order. */
    private Map<String, Object> columns(Datatype.Compound compound, int n) {
        MemorySegment segment = data.get();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Datatype.Compound.Member member : compound.members()) {
            checkMember(compound, member);
            MemorySegment column = column(segment, n, compound.size(), member);
            out.put(member.name(), new ElementReader(ctx, member.type(), n, () -> column,
                    what + "." + member.name()).natural());
        }
        return Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------ compounds

    /**
     * The member named {@code name} of {@code type}.
     *
     * @throws IllegalArgumentException if {@code type} is not a compound
     * @throws NoSuchElementException if it has no member of that name
     */
    static Datatype.Compound.Member member(Datatype type, String name, String what) {
        if (!(type instanceof Datatype.Compound compound)) {
            throw new IllegalArgumentException(what + " is of datatype class " + type.typeClass()
                    + ", not a compound, so it has no member '" + name + "'");
        }
        List<String> names = new ArrayList<>();
        for (Datatype.Compound.Member member : compound.members()) {
            if (member.name().equals(name)) {
                checkMember(compound, member);
                return member;
            }
            names.add(member.name());
        }
        throw new NoSuchElementException(what + " has no member '" + name + "'; its members are " + names);
    }

    /**
     * The {@code size} bytes at {@code offset} in each of {@code count} elements {@code stride} bytes apart,
     * packed together: one member of each element of a compound.
     */
    static MemorySegment column(MemorySegment data, int count, int stride, int offset, int size) {
        byte[] out = new byte[Elements.checkedByteCount(count, size)];
        for (int i = 0; i < count; i++) {
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, (long) i * stride + offset, out, i * size, size);
        }
        return MemorySegment.ofArray(out);
    }

    private static MemorySegment column(MemorySegment data, int count, int stride, Datatype.Compound.Member member) {
        return column(data, count, stride, member.offset(), member.type().size());
    }

    private static void checkMember(Datatype.Compound compound, Datatype.Compound.Member member) {
        if (member.offset() < 0 || (long) member.offset() + member.type().size() > compound.size()) {
            throw new HdfFormatException("compound member '" + member.name() + "' at offset " + member.offset()
                    + " (" + member.type().size() + " bytes) lies outside its " + compound.size() + "-byte compound");
        }
    }

    /** h5py's complex compound: floating-point members {@code r} and {@code i}, and no others. */
    private static Datatype.Compound.Member[] complexPair(Datatype.Compound compound) {
        if (compound.members().size() != 2) {
            return null;
        }
        Datatype.Compound.Member re = null;
        Datatype.Compound.Member im = null;
        for (Datatype.Compound.Member member : compound.members()) {
            if (!(member.type() instanceof Datatype.FloatingPoint)) {
                return null;
            }
            if (member.name().equals("r")) {
                re = member;
            } else if (member.name().equals("i")) {
                im = member;
            }
        }
        if (re == null || im == null) {
            return null;
        }
        checkMember(compound, re);
        checkMember(compound, im);
        return new Datatype.Compound.Member[] {re, im};
    }

    // ------------------------------------------------------------- the rest

    /** The elements as their innermost ones: an array element is its base elements, in order. */
    private Flat flatten() {
        Datatype t = type;
        long n = count;
        while (t instanceof Datatype.Array array) {
            int elements;
            try {
                elements = array.elementCount();
            } catch (ArithmeticException e) {
                throw new HdfFormatException("array datatype has too many elements: " + what);
            }
            if ((long) array.base().size() * elements != array.size()) {
                throw new HdfFormatException("array datatype of " + array.size() + " bytes does not hold "
                        + elements + " elements of " + array.base().size() + " bytes: " + what);
            }
            n *= elements;
            if (n > Integer.MAX_VALUE) {
                throw new HdfUnsupportedException(what + " has too many array elements for a Java array: " + n);
            }
            t = array.base();
        }
        return new Flat(t, (int) n);
    }

    private record Flat(Datatype type, int count) {
    }

    /**
     * The integer type an integer read decodes: an integer, an enumeration's base, or a bit field read as
     * an unsigned integer.
     */
    private Datatype.FixedPoint integerType(Datatype t, String op) {
        return switch (t) {
            case Datatype.FixedPoint fp -> fp;
            case Datatype.Enumeration e -> enumBase(e);
            case Datatype.BitField b -> unsigned(b);
            default -> throw new HdfUnsupportedException(op + " requires an integer datatype, not " + t.typeClass()
                    + ": " + what);
        };
    }

    /** The type a floating-point read decodes: a float, or an integer type (see {@link #integerType}). */
    private Datatype numericType(Datatype t, String op) {
        if (t instanceof Datatype.FloatingPoint) {
            return t;
        }
        if (t instanceof Datatype.FixedPoint || t instanceof Datatype.Enumeration || t instanceof Datatype.BitField) {
            return integerType(t, op);
        }
        throw new HdfUnsupportedException(op + " requires a floating-point or integer datatype, not "
                + t.typeClass() + ": " + what);
    }

    /** A real number libhdf5 converts to a complex one: a float or an integer. */
    private Datatype realType(Datatype t, String op) {
        if (t instanceof Datatype.FloatingPoint || t instanceof Datatype.FixedPoint) {
            return t;
        }
        throw new HdfUnsupportedException(op + " requires a complex, floating-point, or integer datatype, not "
                + t.typeClass() + ": " + what);
    }

    private Datatype.FloatingPoint complexBase(Datatype.Complex complex) {
        if (complex.base() instanceof Datatype.FloatingPoint fp && 2L * fp.size() == complex.size()) {
            return fp;
        }
        throw new HdfFormatException("complex datatype of " + complex.size() + " bytes over " + complex.base()
                + " is not a pair of floating-point numbers: " + what);
    }

    private Datatype.FixedPoint enumBase(Datatype.Enumeration e) {
        if (e.base() instanceof Datatype.FixedPoint fp && fp.size() == e.size()) {
            return fp;
        }
        throw new HdfFormatException("enumeration of " + e.size() + " bytes over " + e.base().typeClass()
                + " is not over an integer type of its size: " + what);
    }

    /** A bit field as the unsigned integer of the same bits (h5py reads {@code H5T_STD_B8} as {@code uint8}). */
    private static Datatype.FixedPoint unsigned(Datatype.BitField b) {
        return new Datatype.FixedPoint(b.size(), b.byteOrder(), false, b.bitOffset(), b.bitPrecision());
    }

    /** Each element's member name, or null for a value no member has. */
    private String[] enumNames(MemorySegment segment, int n, Datatype.Enumeration e) {
        Datatype.FixedPoint base = enumBase(e);
        Map<Long, String> names = new HashMap<>();
        for (Datatype.Enumeration.Member member : e.members()) {
            names.putIfAbsent(member.value(), member.name());
        }
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = names.get(Elements.integerValue(segment, (long) i * base.size(), base));
        }
        return out;
    }

    private static byte[][] elementBytes(MemorySegment segment, int n, int size) {
        byte[][] out = new byte[n][size];
        for (int i = 0; i < n; i++) {
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, (long) i * size, out[i], 0, size);
        }
        return out;
    }

    private int twice(int n) {
        if (n > Integer.MAX_VALUE / 2) {
            throw new HdfUnsupportedException(what + " has too many complex values for a Java array: " + n);
        }
        return 2 * n;
    }
}
