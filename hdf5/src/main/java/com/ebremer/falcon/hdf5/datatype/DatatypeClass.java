package com.ebremer.falcon.hdf5.datatype;

/** The eleven HDF5 datatype classes (spec section IV.A.2 message 3), plus Complex (new in v5). */
public enum DatatypeClass {
    FIXED_POINT(0),
    FLOATING_POINT(1),
    TIME(2),
    STRING(3),
    BIT_FIELD(4),
    OPAQUE(5),
    COMPOUND(6),
    REFERENCE(7),
    ENUMERATED(8),
    VARIABLE_LENGTH(9),
    ARRAY(10),
    COMPLEX(11);

    private final int code;

    DatatypeClass(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DatatypeClass fromCode(int code) {
        for (DatatypeClass c : values()) {
            if (c.code == code) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown datatype class " + code);
    }
}
