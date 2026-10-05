package com.ebremer.falcon.hdf5.datatype;

import java.nio.ByteOrder;
import java.util.List;

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
 */
public sealed interface Datatype {

    /** The datatype class. */
    DatatypeClass typeClass();

    /** The on-disk size of one element, in bytes. */
    int size();

    /**
     * Integer type (class 0): a two's-complement or unsigned integer. The value is the
     * {@code bitPrecision} bits starting {@code bitOffset} bits above the least significant bit of the
     * {@code size}-byte element; the remaining bits are padding.
     */
    record FixedPoint(int size, ByteOrder byteOrder, boolean signed, int bitOffset, int bitPrecision)
            implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.FIXED_POINT;
        }
    }

    /**
     * Floating-point type (class 1): sign, exponent, and mantissa fields at the given bit locations of
     * the element (counted from its least significant bit), so IEEE 754 binary16/32/64 and other layouts
     * (bfloat16, x87 80-bit extended) are all described.
     */
    record FloatingPoint(int size, ByteOrder byteOrder, int bitOffset, int bitPrecision,
                         int exponentLocation, int exponentSize, int mantissaLocation, int mantissaSize,
                         long exponentBias, int signLocation, MantissaNormalization normalization)
            implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.FLOATING_POINT;
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

    /** Time type (class 2). */
    record Time(int size, ByteOrder byteOrder, int bitPrecision) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.TIME;
        }
    }

    /** Fixed-length string type (class 3). */
    record StringType(int size, StringPadding padding, CharacterSet characterSet) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.STRING;
        }
    }

    /** Bit field type (class 4). */
    record BitField(int size, ByteOrder byteOrder, int bitOffset, int bitPrecision) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.BIT_FIELD;
        }
    }

    /** Opaque type (class 5): raw bytes with an application-defined ASCII tag. */
    record Opaque(int size, String tag) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.OPAQUE;
        }
    }

    /** Compound (record/struct) type (class 6). */
    record Compound(int size, List<Member> members) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.COMPOUND;
        }

        /** A named field at a byte offset within the compound. */
        public record Member(String name, int offset, Datatype type) {
        }
    }

    /** Reference type (class 7): a pointer to an object or dataset region. */
    record Reference(int size, ReferenceKind kind) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.REFERENCE;
        }
    }

    /** Enumerated type (class 8): named values over an integer base type. */
    record Enumeration(int size, Datatype base, List<Member> members) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.ENUMERATED;
        }

        /** A named enum constant. */
        public record Member(String name, long value) {
        }
    }

    /** Variable-length type (class 9): a sequence or a variable-length string. */
    record VariableLength(int size, VlenKind kind, Datatype base, StringPadding padding,
                          CharacterSet characterSet) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.VARIABLE_LENGTH;
        }
    }

    /** Array type (class 10): a fixed multidimensional array of a base type. */
    record Array(int size, int[] dimensions, Datatype base) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.ARRAY;
        }

        /**
         * The number of array elements (product of the dimensions).
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

    /** Complex number type (class 11, new in datatype message version 5): a pair of floats. */
    record Complex(int size, Datatype base) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.COMPLEX;
        }
    }

    /** String padding convention. */
    enum StringPadding {
        NULL_TERMINATE, NULL_PAD, SPACE_PAD, RESERVED
    }

    /** Character set. */
    enum CharacterSet {
        ASCII, UTF8, RESERVED
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
        SEQUENCE, STRING
    }
}
