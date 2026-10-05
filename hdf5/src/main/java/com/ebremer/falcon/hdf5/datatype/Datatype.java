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

    /** Integer type (class 0): a two's-complement or unsigned integer. */
    record FixedPoint(int size, ByteOrder byteOrder, boolean signed, int bitOffset, int bitPrecision)
            implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.FIXED_POINT;
        }
    }

    /** IEEE-style floating-point type (class 1). */
    record FloatingPoint(int size, ByteOrder byteOrder, int bitOffset, int bitPrecision,
                         int exponentLocation, int exponentSize, int mantissaLocation, int mantissaSize,
                         long exponentBias) implements Datatype {
        @Override public DatatypeClass typeClass() {
            return DatatypeClass.FLOATING_POINT;
        }
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

    /** Reference kind. */
    enum ReferenceKind {
        OBJECT, DATASET_REGION, ATTRIBUTE, OTHER
    }

    /** Variable-length flavour. */
    enum VlenKind {
        SEQUENCE, STRING
    }
}
