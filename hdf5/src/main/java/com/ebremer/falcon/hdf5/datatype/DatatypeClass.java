package com.ebremer.falcon.hdf5.datatype;

/** The eleven HDF5 datatype classes (spec section IV.A.2 message 3), plus Complex (new in v5). */
public enum DatatypeClass {
    /** Fixed-point (integer) numbers: class 0. */
    FIXED_POINT(0),
    /** Floating-point numbers: class 1. */
    FLOATING_POINT(1),
    /** Unix times: class 2. */
    TIME(2),
    /** Fixed-length strings: class 3. */
    STRING(3),
    /** Bit fields: class 4. */
    BIT_FIELD(4),
    /** Opaque bytes with a tag: class 5. */
    OPAQUE(5),
    /** Compound records of named members: class 6. */
    COMPOUND(6),
    /** References to objects, regions, or attributes: class 7. */
    REFERENCE(7),
    /** Enumerations (named values over an integer type): class 8. */
    ENUMERATED(8),
    /** Variable-length sequences and strings: class 9. */
    VARIABLE_LENGTH(9),
    /** Fixed-shape arrays of a base type: class 10. */
    ARRAY(10),
    /** Complex numbers: class 11, new in datatype message version 5 (HDF5 2.0). */
    COMPLEX(11);

    private final int code;

    DatatypeClass(int code) {
        this.code = code;
    }

    /** {@return the class's number in a datatype message (0 to 11)} */
    public int code() {
        return code;
    }

    /**
     * {@return the class a datatype message numbers {@code code}}
     *
     * @param code the class number, 0 to 11
     * @throws IllegalArgumentException if no class has that number
     */
    public static DatatypeClass fromCode(int code) {
        for (DatatypeClass c : values()) {
            if (c.code == code) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown datatype class " + code);
    }
}
