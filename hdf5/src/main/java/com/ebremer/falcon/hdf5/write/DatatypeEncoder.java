package com.ebremer.falcon.hdf5.write;

import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Encodes a {@link Datatype} as a Datatype message (type 3, spec section IV.A.2.d), the inverse of the
 * reader's parser: every class but VAX floats and revised references. The modern format writes the
 * versions libhdf5 writes by default (version 1 for atomic types, 3 for compound, enumeration and array, 5
 * for complex); the earliest format writes the oldest each class allows (version 1, or 2 for an array and
 * for a compound with an array member), as libhdf5's earliest bounds do. Complex numbers exist only in
 * HDF5 2.0, so the earliest format refuses them.
 */
public final class DatatypeEncoder {

    private DatatypeEncoder() {
    }

    /**
     * The message body of {@code type}.
     *
     * @param legacy the earliest format
     * @throws IllegalArgumentException if the type is inconsistent (a member outside its compound, an array
     *         whose size is not its elements', ...)
     * @throws HdfUnsupportedException for a type Falcon does not write
     */
    public static byte[] encode(Datatype type, boolean legacy) {
        GrowBuffer b = new GrowBuffer();
        write(b, type, legacy);
        return b.toByteArray();
    }

    private static void write(GrowBuffer b, Datatype type, boolean legacy) {
        switch (type) {
            case Datatype.FixedPoint fp -> {
                requireBits(fp.size(), fp.bitOffset(), fp.bitPrecision(), "integer");
                header(b, 1, 0, order(fp.byteOrder()) | (fp.signed() ? 0x08 : 0), 0, fp.size());
                b.u16(fp.bitOffset());
                b.u16(fp.bitPrecision());
            }
            case Datatype.FloatingPoint fp -> {
                if (fp.vaxOrder()) {
                    throw new HdfUnsupportedException("writing VAX floating-point types is not supported");
                }
                requireBits(fp.size(), fp.bitOffset(), fp.bitPrecision(), "floating-point");
                int normalization = switch (fp.normalization()) {
                    case NONE -> 0;
                    case MSB_SET -> 1;
                    case IMPLIED -> 2;
                    case RESERVED -> throw new IllegalArgumentException("reserved mantissa normalization");
                };
                header(b, 1, 1, order(fp.byteOrder()) | normalization << 4, fp.signLocation(), fp.size());
                b.u16(fp.bitOffset());
                b.u16(fp.bitPrecision());
                b.u8(fp.exponentLocation());
                b.u8(fp.exponentSize());
                b.u8(fp.mantissaLocation());
                b.u8(fp.mantissaSize());
                b.u32(fp.exponentBias());
            }
            case Datatype.Time t -> {
                requireBits(t.size(), 0, t.bitPrecision(), "time");
                header(b, 1, 2, order(t.byteOrder()), 0, t.size());
                b.u16(t.bitPrecision());
            }
            case Datatype.StringType s -> {
                requirePositive(s.size(), "string");
                header(b, 1, 3, padding(s.padding()) | charset(s.characterSet()) << 4, 0, s.size());
            }
            case Datatype.BitField f -> {
                requireBits(f.size(), f.bitOffset(), f.bitPrecision(), "bit field");
                header(b, 1, 4, order(f.byteOrder()), 0, f.size());
                b.u16(f.bitOffset());
                b.u16(f.bitPrecision());
            }
            case Datatype.Opaque o -> {
                requirePositive(o.size(), "opaque");
                byte[] tag = o.tag() == null ? new byte[0] : o.tag().getBytes(StandardCharsets.US_ASCII);
                int padded = (tag.length + 1 + 7) & ~7; // null-terminated, padded to 8 bytes
                if (padded > 255) {
                    throw new IllegalArgumentException("an opaque tag is at most 254 characters: " + o.tag());
                }
                header(b, 1, 5, padded, 0, o.size());
                b.bytes(tag);
                for (int i = tag.length; i < padded; i++) {
                    b.u8(0);
                }
            }
            case Datatype.Compound c -> writeCompound(b, c, legacy);
            case Datatype.Reference r -> {
                int kind = switch (r.kind()) {
                    case OBJECT -> 0;
                    case DATASET_REGION -> 1;
                    default -> throw new HdfUnsupportedException("writing " + r.kind() + " references is not supported");
                };
                int size = kind == 0 ? 8 : 12;
                if (r.size() != size) {
                    throw new IllegalArgumentException(r.kind() + " references are " + size + " bytes, not " + r.size());
                }
                header(b, 1, 7, kind, 0, size);
            }
            case Datatype.Enumeration e -> {
                if (!(e.base() instanceof Datatype.FixedPoint base) || base.size() != e.size()) {
                    throw new IllegalArgumentException("an enumeration's base must be an integer type of its size");
                }
                int members = e.members().size();
                if (members == 0 || members > 0xFFFF) {
                    throw new IllegalArgumentException("an enumeration needs 1 to 65535 members, not " + members);
                }
                header(b, legacy ? 1 : 3, 8, members & 0xFF, members >>> 8, e.size());
                write(b, base, legacy);
                for (Datatype.Enumeration.Member member : e.members()) {
                    byte[] name = (member.name() + "\0").getBytes(StandardCharsets.UTF_8);
                    b.bytes(name);
                    if (legacy) {
                        pad8(b, name.length);
                    }
                }
                for (Datatype.Enumeration.Member member : e.members()) {
                    b.bytes(integerBytes(member.value(), base));
                }
            }
            case Datatype.VariableLength v -> {
                if (v.size() != 16) {
                    throw new IllegalArgumentException("variable-length elements are 16 bytes, not " + v.size());
                }
                if (v.kind() == Datatype.VlenKind.STRING) {
                    header(b, 1, 9, 1 | padding(v.padding()) << 4, charset(v.characterSet()), 16);
                    write(b, new Datatype.FixedPoint(1, ByteOrder.LITTLE_ENDIAN, false, 0, 8), legacy);
                } else {
                    header(b, 1, 9, 0, 0, 16);
                    write(b, v.base(), legacy);
                }
            }
            case Datatype.Array a -> {
                int elements = a.elementCount();
                if (a.dimensions().length < 1 || a.dimensions().length > 255
                        || (long) a.base().size() * elements != a.size()) {
                    throw new IllegalArgumentException("an array type's size must be its elements' size");
                }
                header(b, legacy ? 2 : 3, 10, 0, 0, a.size());
                b.u8(a.dimensions().length);
                if (legacy) {
                    b.u8(0);
                    b.u8(0);
                    b.u8(0);
                }
                for (int d : a.dimensions()) {
                    requirePositive(d, "array dimension");
                    b.u32(d);
                }
                if (legacy) {
                    for (int d = 0; d < a.dimensions().length; d++) {
                        b.u32(d); // permutation index (the identity)
                    }
                }
                write(b, a.base(), legacy);
            }
            case Datatype.Complex c -> {
                if (legacy) {
                    throw new HdfUnsupportedException("complex numbers are an HDF5 2.0 type, not written in the earliest format");
                }
                if (!(c.base() instanceof Datatype.FloatingPoint) || 2 * c.base().size() != c.size()) {
                    throw new IllegalArgumentException("a complex type is a pair of floating-point numbers");
                }
                header(b, 5, 11, 1, 0, c.size()); // homogeneous
                write(b, c.base(), legacy);
            }
        }
    }

    private static void writeCompound(GrowBuffer b, Datatype.Compound c, boolean legacy) {
        int members = c.members().size();
        if (members == 0 || members > 0xFFFF) {
            throw new IllegalArgumentException("a compound needs 1 to 65535 members, not " + members);
        }
        boolean arrayMember = c.members().stream().anyMatch(m -> m.type() instanceof Datatype.Array);
        int version = legacy ? (arrayMember ? 2 : 1) : 3;
        header(b, version, 6, members & 0xFF, members >>> 8, c.size());
        int offsetWidth = Math.max(1, (32 - Integer.numberOfLeadingZeros(Math.max(1, c.size())) + 7) / 8);
        for (Datatype.Compound.Member member : c.members()) {
            if (member.offset() < 0 || (long) member.offset() + member.type().size() > c.size()) {
                throw new IllegalArgumentException("compound member '" + member.name() + "' lies outside its "
                        + c.size() + "-byte compound");
            }
            byte[] name = (member.name() + "\0").getBytes(StandardCharsets.UTF_8);
            b.bytes(name);
            if (version < 3) {
                pad8(b, name.length);
                b.u32(member.offset());
                if (version == 1) {
                    b.u8(0);       // dimensionality (a scalar member)
                    b.u8(0);
                    b.u8(0);
                    b.u8(0);       // reserved (3)
                    b.u32(0);      // dimension permutation
                    b.u32(0);      // reserved
                    for (int d = 0; d < 4; d++) {
                        b.u32(0);  // dimension sizes
                    }
                }
            } else {
                b.uvar(member.offset(), offsetWidth);
            }
            write(b, member.type(), legacy);
        }
    }

    /** The class-and-version byte, the three class bit-field bytes, and the size. */
    private static void header(GrowBuffer b, int version, int typeClass, int bits0, int bits1, int size) {
        b.u8(version << 4 | typeClass);
        b.u8(bits0);
        b.u8(bits1);
        b.u8(0);
        b.u32(size);
    }

    /**
     * The value {@code value} of an integer {@code type}, as an element of that type stores it (in its
     * byte order, in its precision's bits at its offset).
     */
    public static byte[] integerBytes(long value, Datatype.FixedPoint type) {
        byte[] out = new byte[type.size()];
        long bits = type.bitPrecision() >= 64 ? value : value & ((1L << type.bitPrecision()) - 1);
        for (int i = 0; i < type.bitPrecision(); i++) {
            if ((bits >>> i & 1) != 0) {
                int bit = type.bitOffset() + i;
                int index = type.byteOrder() == ByteOrder.LITTLE_ENDIAN ? bit / 8 : type.size() - 1 - bit / 8;
                out[index] |= (byte) (1 << (bit % 8));
            }
        }
        return out;
    }

    private static int order(ByteOrder order) {
        return order == ByteOrder.BIG_ENDIAN ? 1 : 0;
    }

    private static int padding(Datatype.StringPadding padding) {
        return switch (padding == null ? Datatype.StringPadding.NULL_TERMINATE : padding) {
            case NULL_TERMINATE -> 0;
            case NULL_PAD -> 1;
            case SPACE_PAD -> 2;
            case RESERVED -> throw new IllegalArgumentException("reserved string padding");
        };
    }

    private static int charset(Datatype.CharacterSet charset) {
        return switch (charset == null ? Datatype.CharacterSet.ASCII : charset) {
            case ASCII -> 0;
            case UTF8 -> 1;
            case RESERVED -> throw new IllegalArgumentException("reserved character set");
        };
    }

    private static void requireBits(int size, int offset, int precision, String what) {
        requirePositive(size, what);
        if (offset < 0 || precision < 1 || (long) offset + precision > 8L * size || precision > 0xFFFF) {
            throw new IllegalArgumentException(what + " type of " + size + " bytes cannot hold " + precision
                    + " bits at offset " + offset);
        }
    }

    private static void requirePositive(int size, String what) {
        if (size < 1) {
            throw new IllegalArgumentException(what + " size must be positive, not " + size);
        }
    }

    private static void pad8(GrowBuffer b, int length) {
        for (int i = length; i % 8 != 0; i++) {
            b.u8(0);
        }
    }
}
