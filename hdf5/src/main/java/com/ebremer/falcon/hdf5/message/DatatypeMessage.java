package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the Datatype message (type 3), spec section IV.A.2 / Appendix D.B. Handles message
 * versions 1–5 and all classes 0–11, recursing for compound members and enum/vlen/array/complex base
 * types.
 *
 * <p>Every datatype begins with a "class and version" byte (version in the high nibble, class in the
 * low nibble), a 3-byte class bit field, and a 4-byte element size, followed by class-specific
 * properties.
 */
public final class DatatypeMessage {

    private DatatypeMessage() {
    }

    /** Parses the datatype whose message body starts at {@code offset}. */
    public static Datatype parse(HdfBuffer buf, long offset) {
        return read(buf, offset).type;
    }

    private static final class Result {
        final Datatype type;
        final long next;

        Result(Datatype type, long next) {
            this.type = type;
            this.next = next;
        }
    }

    private static Result read(HdfBuffer buf, long off) {
        int classAndVersion = buf.getUnsignedByte(off);
        int version = (classAndVersion >> 4) & 0x0F;
        int typeClass = classAndVersion & 0x0F;
        int bits0 = buf.getUnsignedByte(off + 1);
        int bits1 = buf.getUnsignedByte(off + 2);
        int size = (int) buf.getUnsignedInt(off + 4);
        long p = off + 8;

        switch (typeClass) {
            case 0: { // fixed-point
                ByteOrder order = order(bits0);
                boolean signed = (bits0 & 0x08) != 0;
                int bitOffset = buf.getUnsignedShort(p);
                int bitPrecision = buf.getUnsignedShort(p + 2);
                return new Result(new Datatype.FixedPoint(size, order, signed, bitOffset, bitPrecision), p + 4);
            }
            case 1: { // floating-point
                ByteOrder order = order(bits0);
                int bitOffset = buf.getUnsignedShort(p);
                int bitPrecision = buf.getUnsignedShort(p + 2);
                int expLoc = buf.getUnsignedByte(p + 4);
                int expSize = buf.getUnsignedByte(p + 5);
                int mantLoc = buf.getUnsignedByte(p + 6);
                int mantSize = buf.getUnsignedByte(p + 7);
                long bias = buf.getUnsignedInt(p + 8);
                return new Result(new Datatype.FloatingPoint(size, order, bitOffset, bitPrecision,
                        expLoc, expSize, mantLoc, mantSize, bias), p + 12);
            }
            case 2: { // time
                ByteOrder order = order(bits0);
                int bitPrecision = buf.getUnsignedShort(p);
                return new Result(new Datatype.Time(size, order, bitPrecision), p + 2);
            }
            case 3: { // string
                Datatype.StringPadding padding = stringPadding(bits0 & 0x0F);
                Datatype.CharacterSet charset = characterSet((bits0 >> 4) & 0x0F);
                return new Result(new Datatype.StringType(size, padding, charset), p);
            }
            case 4: { // bit field
                ByteOrder order = order(bits0);
                int bitOffset = buf.getUnsignedShort(p);
                int bitPrecision = buf.getUnsignedShort(p + 2);
                return new Result(new Datatype.BitField(size, order, bitOffset, bitPrecision), p + 4);
            }
            case 5: { // opaque
                int tagLength = bits0;
                String tag = readAsciiTag(buf, p, tagLength);
                return new Result(new Datatype.Opaque(size, tag), p + tagLength);
            }
            case 6: { // compound
                int members = bits0 | (bits1 << 8);
                List<Datatype.Compound.Member> list = new ArrayList<>(members);
                for (int i = 0; i < members; i++) {
                    Name name;
                    long memberOffset;
                    if (version == 1) {
                        name = readNamePadded(buf, p);
                        p = name.next;
                        memberOffset = buf.getUnsignedInt(p);
                        p += 4 + 1 + 3 + 4 + 4 + 16; // offset + legacy dimensionality block
                    } else if (version == 2) {
                        name = readNamePadded(buf, p);
                        p = name.next;
                        memberOffset = buf.getUnsignedInt(p);
                        p += 4;
                    } else { // version 3+: null-terminated name, packed offset
                        name = readNameCString(buf, p);
                        p = name.next;
                        int width = byteWidthFor(size);
                        memberOffset = buf.getUnsignedValue(p, width);
                        p += width;
                    }
                    Result member = read(buf, p);
                    p = member.next;
                    list.add(new Datatype.Compound.Member(name.value, (int) memberOffset, member.type));
                }
                return new Result(new Datatype.Compound(size, list), p);
            }
            case 7: { // reference
                return new Result(new Datatype.Reference(size, referenceKind(bits0 & 0x0F)), p);
            }
            case 8: { // enumerated
                int members = bits0 | (bits1 << 8);
                Result base = read(buf, p);
                p = base.next;
                List<String> names = new ArrayList<>(members);
                for (int i = 0; i < members; i++) {
                    Name name = version >= 3 ? readNameCString(buf, p) : readNamePadded(buf, p);
                    names.add(name.value);
                    p = name.next;
                }
                int baseSize = base.type.size();
                List<Datatype.Enumeration.Member> list = new ArrayList<>(members);
                for (int i = 0; i < members; i++) {
                    long value = buf.getUnsignedValue(p, baseSize);
                    p += baseSize;
                    list.add(new Datatype.Enumeration.Member(names.get(i), value));
                }
                return new Result(new Datatype.Enumeration(size, base.type, list), p);
            }
            case 9: { // variable-length
                int vlenCode = bits0 & 0x0F;
                Datatype.VlenKind kind = vlenCode == 1 ? Datatype.VlenKind.STRING : Datatype.VlenKind.SEQUENCE;
                Datatype.StringPadding padding = null;
                Datatype.CharacterSet charset = null;
                if (kind == Datatype.VlenKind.STRING) {
                    padding = stringPadding((bits0 >> 4) & 0x0F);
                    charset = characterSet(bits1 & 0x0F);
                }
                Result base = read(buf, p);
                return new Result(new Datatype.VariableLength(size, kind, base.type, padding, charset), base.next);
            }
            case 10: { // array
                int rank = buf.getUnsignedByte(p);
                p += 1;
                if (version == 2) {
                    p += 3; // reserved
                }
                int[] dims = new int[rank];
                for (int i = 0; i < rank; i++) {
                    dims[i] = (int) buf.getUnsignedInt(p);
                    p += 4;
                }
                if (version == 2) {
                    p += 4L * rank; // permutation indices (unused)
                }
                Result base = read(buf, p);
                return new Result(new Datatype.Array(size, dims, base.type), base.next);
            }
            case 11: { // complex (v5+)
                Result base = read(buf, p);
                return new Result(new Datatype.Complex(size, base.type), base.next);
            }
            default:
                throw new HdfFormatException("unknown datatype class " + typeClass + " at " + off);
        }
    }

    private static ByteOrder order(int bitField0) {
        return (bitField0 & 0x01) != 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    private static Datatype.StringPadding stringPadding(int code) {
        return switch (code) {
            case 0 -> Datatype.StringPadding.NULL_TERMINATE;
            case 1 -> Datatype.StringPadding.NULL_PAD;
            case 2 -> Datatype.StringPadding.SPACE_PAD;
            default -> Datatype.StringPadding.RESERVED;
        };
    }

    private static Datatype.CharacterSet characterSet(int code) {
        return switch (code) {
            case 0 -> Datatype.CharacterSet.ASCII;
            case 1 -> Datatype.CharacterSet.UTF8;
            default -> Datatype.CharacterSet.RESERVED;
        };
    }

    private static Datatype.ReferenceKind referenceKind(int code) {
        return switch (code) {
            case 0 -> Datatype.ReferenceKind.OBJECT;
            case 1 -> Datatype.ReferenceKind.DATASET_REGION;
            case 2 -> Datatype.ReferenceKind.ATTRIBUTE;
            default -> Datatype.ReferenceKind.OTHER;
        };
    }

    /** Minimum number of bytes needed to hold values up to {@code size} (version-3 member offsets). */
    private static int byteWidthFor(int size) {
        if (size <= 0) {
            return 1;
        }
        int bits = 32 - Integer.numberOfLeadingZeros(size);
        return Math.max(1, (bits + 7) / 8);
    }

    private static String readAsciiTag(HdfBuffer buf, long p, int length) {
        byte[] raw = buf.getBytes(p, length);
        int end = 0;
        while (end < raw.length && raw[end] != 0) {
            end++;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII);
    }

    private static final class Name {
        final String value;
        final long next;

        Name(String value, long next) {
            this.value = value;
            this.next = next;
        }
    }

    /** A null-terminated name padded so the whole field is a multiple of 8 bytes. */
    private static Name readNamePadded(HdfBuffer buf, long start) {
        long p = start;
        while (buf.getByte(p) != 0) {
            p++;
        }
        String value = new String(buf.getBytes(start, (int) (p - start)), StandardCharsets.UTF_8);
        p++; // consume the null
        long consumed = p - start;
        long padding = (8 - (consumed % 8)) % 8;
        return new Name(value, p + padding);
    }

    /** A plain null-terminated name (version 3+). */
    private static Name readNameCString(HdfBuffer buf, long start) {
        long p = start;
        while (buf.getByte(p) != 0) {
            p++;
        }
        String value = new String(buf.getBytes(start, (int) (p - start)), StandardCharsets.UTF_8);
        return new Name(value, p + 1);
    }
}
