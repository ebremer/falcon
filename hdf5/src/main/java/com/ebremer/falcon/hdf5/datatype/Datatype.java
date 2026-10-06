package com.ebremer.falcon.hdf5.datatype;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A decoded HDF5 datatype (object-header message 3), modelled as a sealed hierarchy of records &mdash;
 * one per datatype class. Compound, enumerated, variable-length, array, and complex types nest a base
 * or member {@code Datatype}, so arbitrarily nested types are represented directly.
 *
 * <p>Pattern-match to inspect:
 * <pre>{@code
 * switch (dataset.datatype()) {
 *     case Datatype.FixedPoint fp -> ...
 *     case Datatype.Compound c    -> for (var m : c.members()) ...
 *     default -> ...
 * }
 * }</pre>
 *
 * <p>The static factories name the types a writer most often needs ({@code Hdf5Writer}'s
 * {@code createDataset} takes any {@code Datatype}):
 * <pre>{@code
 * Datatype.uint16()                                  // H5T_STD_U16LE
 * Datatype.float32().withByteOrder(ByteOrder.BIG_ENDIAN) // H5T_IEEE_F32BE
 * Datatype.variableString()                          // UTF-8, as h5py writes str
 * Datatype.compound(new LinkedHashMap<>(Map.of(...)))  // members packed in order
 * }</pre>
 */
public sealed interface Datatype {

    /** {@return the datatype class} */
    DatatypeClass typeClass();

    /** {@return the on-disk size of one element, in bytes} */
    int size();

    /**
     * Integer type (class 0): a two's-complement or unsigned integer. The value is the
     * {@code bitPrecision} bits starting {@code bitOffset} bits above the least significant bit of the
     * {@code size}-byte element; the remaining bits are padding.
     *
     * @param size         the bytes of one element
     * @param byteOrder    the order of the element's bytes
     * @param signed       true for two's complement, false for unsigned
     * @param bitOffset    the bit the value starts at, counted from the element's least significant bit
     * @param bitPrecision the bits of the value
     */
    record FixedPoint(int size, ByteOrder byteOrder, boolean signed, int bitOffset, int bitPrecision)
            implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.FIXED_POINT;
        }

        /**
         * {@return this type in another byte order}
         *
         * @param order the byte order of the type returned
         */
        public FixedPoint withByteOrder(ByteOrder order) {
            return new FixedPoint(size, order, signed, bitOffset, bitPrecision);
        }
    }

    /**
     * Floating-point type (class 1): sign, exponent, and mantissa fields at the given bit locations of
     * the element (counted from its least significant bit), so IEEE 754 binary16/32/64 and other layouts
     * (bfloat16, x87 80-bit extended, VAX) are all described.
     *
     * <p>{@code vaxOrder} marks VAX byte order (datatype version 3+): the element's 16-bit words are
     * stored most significant first, each little-endian; {@code byteOrder} is then big-endian, as the
     * message's byte-order bit says.
     *
     * @param size             the bytes of one element
     * @param byteOrder        the order of the element's bytes (big-endian in VAX order)
     * @param bitOffset        the bit the value starts at, counted from the element's least significant bit
     * @param bitPrecision     the bits of the value
     * @param exponentLocation the bit the exponent starts at (its least significant bit)
     * @param exponentSize     the bits of the exponent
     * @param mantissaLocation the bit the mantissa starts at (its least significant bit)
     * @param mantissaSize     the bits of the mantissa
     * @param exponentBias     what is subtracted from the stored exponent (127 for binary32)
     * @param signLocation     the bit that holds the sign
     * @param normalization    how the mantissa is normalized
     * @param vaxOrder         true for VAX byte order (see above)
     */
    record FloatingPoint(int size, ByteOrder byteOrder, int bitOffset, int bitPrecision,
                         int exponentLocation, int exponentSize, int mantissaLocation, int mantissaSize,
                         long exponentBias, int signLocation, MantissaNormalization normalization,
                         boolean vaxOrder)
            implements Datatype {

        /**
         * A floating-point type in plain big- or little-endian order.
         *
         * @param size             the bytes of one element
         * @param byteOrder        the order of the element's bytes
         * @param bitOffset        the bit the value starts at, counted from the element's least significant bit
         * @param bitPrecision     the bits of the value
         * @param exponentLocation the bit the exponent starts at (its least significant bit)
         * @param exponentSize     the bits of the exponent
         * @param mantissaLocation the bit the mantissa starts at (its least significant bit)
         * @param mantissaSize     the bits of the mantissa
         * @param exponentBias     what is subtracted from the stored exponent (127 for binary32)
         * @param signLocation     the bit that holds the sign
         * @param normalization    how the mantissa is normalized
         */
        public FloatingPoint(int size, ByteOrder byteOrder, int bitOffset, int bitPrecision,
                             int exponentLocation, int exponentSize, int mantissaLocation, int mantissaSize,
                             long exponentBias, int signLocation, MantissaNormalization normalization) {
            this(size, byteOrder, bitOffset, bitPrecision, exponentLocation, exponentSize, mantissaLocation,
                    mantissaSize, exponentBias, signLocation, normalization, false);
        }

        @Override public DatatypeClass typeClass() {
            return DatatypeClass.FLOATING_POINT;
        }

        /**
         * {@return this type in another (plain) byte order}
         *
         * @param order the byte order of the type returned, which is not VAX order
         */
        public FloatingPoint withByteOrder(ByteOrder order) {
            return new FloatingPoint(size, order, bitOffset, bitPrecision, exponentLocation, exponentSize,
                    mantissaLocation, mantissaSize, exponentBias, signLocation, normalization, false);
        }
    }

    /** How a floating-point mantissa is normalized. */
    enum MantissaNormalization {
        /** No normalization: the mantissa is a plain binary fraction (x87 extended precision). */
        NONE,
        /** The mantissa's most significant bit is always set (except for zero) and is stored. */
        MSB_SET,
        /** The most significant bit is implied and not stored (IEEE 754). */
        IMPLIED,
        /** A reserved code. */
        RESERVED
    }

    /**
     * Time type (class 2): a Unix {@code time_t}, the signed number of seconds since
     * 1970-01-01T00:00:00Z, in its low {@code bitPrecision} bits. HDF5's only time types are
     * {@code H5T_UNIX_D32BE/LE} and {@code H5T_UNIX_D64BE/LE}.
     *
     * @param size         the bytes of one element: 4 or 8
     * @param byteOrder    the order of the element's bytes
     * @param bitPrecision the bits of the value, from the element's least significant bit
     */
    record Time(int size, ByteOrder byteOrder, int bitPrecision) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.TIME;
        }

        /**
         * {@return this type in another byte order}
         *
         * @param order the byte order of the type returned
         */
        public Time withByteOrder(ByteOrder order) {
            return new Time(size, order, bitPrecision);
        }
    }

    /**
     * Fixed-length string type (class 3).
     *
     * @param size         the bytes of each string, its padding included
     * @param padding      how a string shorter than {@code size} ends
     * @param characterSet how the bytes encode characters
     */
    record StringType(int size, StringPadding padding, CharacterSet characterSet) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.STRING;
        }
    }

    /**
     * Bit field type (class 4).
     *
     * @param size         the bytes of one element
     * @param byteOrder    the order of the element's bytes
     * @param bitOffset    the bit the field starts at, counted from the element's least significant bit
     * @param bitPrecision the bits of the field
     */
    record BitField(int size, ByteOrder byteOrder, int bitOffset, int bitPrecision) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.BIT_FIELD;
        }

        /**
         * {@return this type in another byte order}
         *
         * @param order the byte order of the type returned
         */
        public BitField withByteOrder(ByteOrder order) {
            return new BitField(size, order, bitOffset, bitPrecision);
        }
    }

    /**
     * Opaque type (class 5): raw bytes with an application-defined ASCII tag.
     *
     * @param size the bytes of one element
     * @param tag  the application's ASCII description of the bytes, without its null padding; may be empty
     */
    record Opaque(int size, String tag) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.OPAQUE;
        }
    }

    /**
     * Compound (record/struct) type (class 6).
     *
     * @param size    the bytes of one record, its members and any padding between and after them
     * @param members the members, in the order the file lists them
     */
    record Compound(int size, List<Member> members) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.COMPOUND;
        }

        /**
         * A named field at a byte offset within the compound.
         *
         * @param name   the member's name
         * @param offset the byte the member starts at, within the record
         * @param type   the member's datatype
         */
        public record Member(String name, int offset, Datatype type) {
        }
    }

    /**
     * Reference type (class 7): a pointer to an object or dataset region.
     *
     * @param size the bytes of one element in the file (8 for an object reference and 12 for a region
     *             reference, in a file of 8-byte offsets)
     * @param kind which reference it is
     */
    record Reference(int size, ReferenceKind kind) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.REFERENCE;
        }
    }

    /**
     * Enumerated type (class 8): named values over an integer base type.
     *
     * @param size    the bytes of one element: the base type's size
     * @param base    the integer type the values are stored as
     * @param members the named values, in the order the file lists them
     */
    record Enumeration(int size, Datatype base, List<Member> members) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.ENUMERATED;
        }

        /**
         * A named enum constant. {@code value} is the integer its elements hold, read as the base type
         * stores it (its byte order and sign); a {@code uint64} value of 2<sup>63</sup> or more is its bit
         * pattern.
         *
         * @param name  the constant's name
         * @param value the integer the constant's elements hold
         */
        public record Member(String name, long value) {
        }
    }

    /**
     * Variable-length type (class 9): a sequence or a variable-length string.
     *
     * @param size         the bytes of one element in the file: a length and a global-heap ID (16 in a file
     *                     of 8-byte offsets)
     * @param kind         a sequence or a string
     * @param base         the type of one element of the sequence (of a string, a one-byte character type)
     * @param padding      how a string ends; null for a sequence
     * @param characterSet how a string's bytes encode characters; null for a sequence
     */
    record VariableLength(int size, VlenKind kind, Datatype base, StringPadding padding,
                          CharacterSet characterSet) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.VARIABLE_LENGTH;
        }
    }

    /**
     * Array type (class 10): a fixed multidimensional array of a base type.
     *
     * @param size       the bytes of one element: the base type's size times {@link #elementCount()}
     * @param dimensions the array's shape, slowest-varying dimension first
     * @param base       the type of each array element
     */
    record Array(int size, int[] dimensions, Datatype base) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.ARRAY;
        }

        /**
         * {@return the number of array elements (product of the dimensions)}
         *
         * @throws ArithmeticException if the product does not fit in an {@code int}
         */
        public int elementCount() {
            int n = 1;
            for (int d : dimensions) {
                n = Math.multiplyExact(n, d);
            }
            return n;
        }
    }

    /**
     * Complex number type (class 11, new in datatype message version 5): a pair of floats.
     *
     * @param size the bytes of one element: twice the base type's size
     * @param base the floating-point type of the real part and of the imaginary part, which follows it
     */
    record Complex(int size, Datatype base) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.COMPLEX;
        }
    }

    // ------------------------------------------------------------------ factories

    /** {@return a signed 8-bit integer type ({@code H5T_STD_I8LE})} */
    static FixedPoint int8() {
        return integer(1, true);
    }

    /** {@return a signed 16-bit little-endian integer type ({@code H5T_STD_I16LE})} */
    static FixedPoint int16() {
        return integer(2, true);
    }

    /** {@return a signed 32-bit little-endian integer type ({@code H5T_STD_I32LE})} */
    static FixedPoint int32() {
        return integer(4, true);
    }

    /** {@return a signed 64-bit little-endian integer type ({@code H5T_STD_I64LE})} */
    static FixedPoint int64() {
        return integer(8, true);
    }

    /** {@return an unsigned 8-bit integer type ({@code H5T_STD_U8LE}), as image data usually is} */
    static FixedPoint uint8() {
        return integer(1, false);
    }

    /** {@return an unsigned 16-bit little-endian integer type ({@code H5T_STD_U16LE})} */
    static FixedPoint uint16() {
        return integer(2, false);
    }

    /** {@return an unsigned 32-bit little-endian integer type ({@code H5T_STD_U32LE})} */
    static FixedPoint uint32() {
        return integer(4, false);
    }

    /** {@return an unsigned 64-bit little-endian integer type ({@code H5T_STD_U64LE})} */
    static FixedPoint uint64() {
        return integer(8, false);
    }

    private static FixedPoint integer(int size, boolean signed) {
        return new FixedPoint(size, ByteOrder.LITTLE_ENDIAN, signed, 0, 8 * size);
    }

    /** {@return an IEEE 754 binary16 float type, little-endian ({@code H5T_IEEE_F16LE})} */
    static FloatingPoint float16() {
        return new FloatingPoint(2, ByteOrder.LITTLE_ENDIAN, 0, 16, 10, 5, 0, 10, 15, 15, MantissaNormalization.IMPLIED);
    }

    /** {@return an IEEE 754 binary32 float type, little-endian ({@code H5T_IEEE_F32LE})} */
    static FloatingPoint float32() {
        return new FloatingPoint(4, ByteOrder.LITTLE_ENDIAN, 0, 32, 23, 8, 0, 23, 127, 31, MantissaNormalization.IMPLIED);
    }

    /** {@return an IEEE 754 binary64 float type, little-endian ({@code H5T_IEEE_F64LE})} */
    static FloatingPoint float64() {
        return new FloatingPoint(8, ByteOrder.LITTLE_ENDIAN, 0, 64, 52, 11, 0, 52, 1023, 63, MantissaNormalization.IMPLIED);
    }

    /**
     * {@return a fixed-length UTF-8 string type of {@code size} bytes, null-padded}
     *
     * @param size the bytes of each string, its padding included
     */
    static StringType string(int size) {
        return new StringType(size, StringPadding.NULL_PAD, CharacterSet.UTF8);
    }

    /** {@return a variable-length UTF-8 string type, as h5py writes a Python {@code str}} */
    static VariableLength variableString() {
        return new VariableLength(16, VlenKind.STRING, uint8(), StringPadding.NULL_TERMINATE, CharacterSet.UTF8);
    }

    /**
     * {@return a variable-length sequence type (a ragged row) of {@code base} elements}
     *
     * @param base the type of the sequence's elements
     */
    static VariableLength sequenceOf(Datatype base) {
        return new VariableLength(16, VlenKind.SEQUENCE, base, null, null);
    }

    /**
     * {@return a fixed-shape array of {@code base} elements, as an element type}
     *
     * @param base       the type of each array element
     * @param dimensions the array's shape, slowest-varying dimension first
     * @throws IllegalArgumentException if there are no dimensions, or an element would take more than
     *         2 GiB
     */
    static Array arrayOf(Datatype base, int... dimensions) {
        long size = base.size();
        for (int d : dimensions) {
            size *= d;
        }
        if (dimensions.length == 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("an array type needs dimensions, and at most 2 GiB per element");
        }
        return new Array((int) size, dimensions.clone(), base);
    }

    /**
     * {@return a complex number type of two {@code base} floats (HDF5 2.0)}
     *
     * @param base the type of the real part and of the imaginary part
     */
    static Complex complexOf(FloatingPoint base) {
        return new Complex(2 * base.size(), base);
    }

    /** {@return h5py's boolean: an 8-bit enumeration of {@code FALSE = 0} and {@code TRUE = 1}} */
    static Enumeration bool() {
        return new Enumeration(1, int8(), List.of(new Enumeration.Member("FALSE", 0), new Enumeration.Member("TRUE", 1)));
    }

    /**
     * {@return a compound of {@code members}, packed in iteration order without gaps}
     *
     * @param members each member's name and datatype (pass a {@link java.util.LinkedHashMap} for a chosen
     *                order)
     * @throws IllegalArgumentException if a record would take more than 2 GiB
     */
    static Compound compound(Map<String, ? extends Datatype> members) {
        List<Compound.Member> list = new ArrayList<>();
        long offset = 0;
        for (Map.Entry<String, ? extends Datatype> member : members.entrySet()) {
            list.add(new Compound.Member(member.getKey(), (int) offset, member.getValue()));
            offset += member.getValue().size();
        }
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a compound of " + offset + " bytes is too large");
        }
        return new Compound((int) offset, List.copyOf(list));
    }

    /**
     * {@return an opaque type: elements of {@code size} bytes, with an application-defined ASCII {@code tag}}
     *
     * @param size the bytes of one element
     * @param tag  the application's ASCII description of the bytes
     */
    static Opaque opaque(int size, String tag) {
        return new Opaque(size, tag);
    }

    /**
     * {@return a bit field type of {@code size} bytes, little-endian ({@code H5T_STD_B8LE} for 1)}
     *
     * @param size the bytes of one element
     */
    static BitField bitField(int size) {
        return new BitField(size, ByteOrder.LITTLE_ENDIAN, 0, 8 * size);
    }

    /**
     * {@return a Unix time type, seconds since 1970, little-endian ({@code H5T_UNIX_D32LE}, {@code D64LE})}
     *
     * @param size the bytes of one element: 4 or 8
     */
    static Time unixTime(int size) {
        return new Time(size, ByteOrder.LITTLE_ENDIAN, 8 * size);
    }

    /** {@return an object reference type ({@code H5R_OBJECT1}): an object's header address} */
    static Reference objectReference() {
        return new Reference(8, ReferenceKind.OBJECT);
    }

    /**
     * {@return a dataset-region reference type ({@code H5R_DATASET_REGION1}): a dataset and a selection of it}
     */
    static Reference regionReference() {
        return new Reference(12, ReferenceKind.DATASET_REGION);
    }

    /** String padding convention. */
    enum StringPadding {
        /** A null byte ends a string shorter than its size, and the bytes after it are padding (C). */
        NULL_TERMINATE,
        /** Null bytes pad a string shorter than its size; a string of the full size has none. */
        NULL_PAD,
        /** Spaces pad a string shorter than its size (Fortran). */
        SPACE_PAD,
        /** A reserved code. */
        RESERVED
    }

    /** Character set. */
    enum CharacterSet {
        /** US-ASCII. */
        ASCII,
        /** UTF-8. */
        UTF8,
        /** A reserved code. */
        RESERVED
    }

    /**
     * Reference kind. {@code OBJECT} and {@code DATASET_REGION} are the original encodings Falcon reads;
     * the {@code REVISED_*} kinds are HDF5 1.12's {@code H5R_ref_t} encoding (datatype version 4).
     */
    enum ReferenceKind {
        /** An object reference ({@code H5R_OBJECT1}): an object-header address. */
        OBJECT,
        /** A dataset-region reference ({@code H5R_DATASET_REGION1}): a global-heap ID. */
        DATASET_REGION,
        /** A revised object reference ({@code H5R_OBJECT2}). */
        REVISED_OBJECT,
        /** A revised dataset-region reference ({@code H5R_DATASET_REGION2}). */
        REVISED_DATASET_REGION,
        /** A revised attribute reference ({@code H5R_ATTR}). */
        REVISED_ATTRIBUTE,
        /** A reserved or unknown code. */
        OTHER
    }

    /** Variable-length flavour. */
    enum VlenKind {
        /** A sequence of elements of the base type (a ragged row). */
        SEQUENCE,
        /** A string of characters. */
        STRING
    }
}
