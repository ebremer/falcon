package com.ebremer.falcon.hdf5.message;

import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.HeaderMessage;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.header.SharedMessage;
import com.ebremer.falcon.hdf5.io.FileContext;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import java.lang.foreign.MemorySegment;
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
        return read(buf, offset, 0).type;
    }

    /** Deepest nesting of compound / enum / vlen / array / complex base types accepted. */
    private static final int MAX_NESTING = 64;

    /**
     * Resolves a datatype that may be <b>shared</b>: if {@code shared}, {@code bodyOffset} points at a
     * {@link SharedMessage} locating a committed datatype's object header (following a chain of committed
     * references if necessary) or a copy in the shared-message heap, whose Datatype message is read.
     * Otherwise the datatype is parsed inline from {@code bodyOffset}.
     */
    public static Datatype resolve(FileContext ctx, long bodyOffset, boolean shared) {
        if (!shared) {
            return parse(ctx.buffer(), bodyOffset);
        }
        HeaderMessage datatype = SharedMessage.target(ctx, bodyOffset, MessageType.DATATYPE);
        return parse(datatype.buffer(), datatype.bodyOffset());
    }

    private static final class Result {
        final Datatype type;
        final long next;

        Result(Datatype type, long next) {
            this.type = type;
            this.next = next;
        }
    }

    private static Result read(HdfBuffer buf, long off, int depth) {
        if (depth > MAX_NESTING) {
            throw new HdfFormatException("datatype nested more than " + MAX_NESTING + " levels deep at " + off);
        }
        int classAndVersion = buf.getUnsignedByte(off);
        int version = (classAndVersion >> 4) & 0x0F;
        int typeClass = classAndVersion & 0x0F;
        int bits0 = buf.getUnsignedByte(off + 1);
        int bits1 = buf.getUnsignedByte(off + 2);
        long storedSize = buf.getUnsignedInt(off + 4);
        if (storedSize > Integer.MAX_VALUE) {
            throw new HdfFormatException("datatype size " + storedSize + " is too large at " + off);
        }
        int size = (int) storedSize;
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
                // Bit 6 with bit 0 is VAX order (datatype version 3+); bit 6 alone is invalid there.
                boolean vax = version >= 3 && (bits0 & 0x41) == 0x41;
                if (version >= 3 && (bits0 & 0x41) == 0x40) {
                    throw new HdfFormatException("invalid floating-point byte order at " + off);
                }
                if (vax && size % 2 != 0) {
                    throw new HdfFormatException("VAX-order floating-point type of odd size " + size + " at " + off);
                }
                ByteOrder order = order(bits0);
                Datatype.MantissaNormalization normalization =
                        Datatype.MantissaNormalization.values()[(bits0 >> 4) & 0x03];
                int signLocation = bits1;
                int bitOffset = buf.getUnsignedShort(p);
                int bitPrecision = buf.getUnsignedShort(p + 2);
                int expLoc = buf.getUnsignedByte(p + 4);
                int expSize = buf.getUnsignedByte(p + 5);
                int mantLoc = buf.getUnsignedByte(p + 6);
                int mantSize = buf.getUnsignedByte(p + 7);
                long bias = buf.getUnsignedInt(p + 8);
                return new Result(new Datatype.FloatingPoint(size, order, bitOffset, bitPrecision,
                        expLoc, expSize, mantLoc, mantSize, bias, signLocation, normalization, vax), p + 12);
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
                    int[] memberDims = null;
                    if (version == 1) {
                        name = readNamePadded(buf, p);
                        p = name.next;
                        memberOffset = buf.getUnsignedInt(p);
                        // Pre-1.4 array members: a dimensionality (0-4) and up to four sizes, after a
                        // reserved block and a (never implemented) permutation.
                        int rank = buf.getUnsignedByte(p + 4);
                        if (rank > 4) {
                            throw new HdfFormatException("compound member dimensionality " + rank + " exceeds 4 at " + p);
                        }
                        if (rank > 0) {
                            memberDims = new int[rank];
                            for (int d = 0; d < rank; d++) {
                                long dim = buf.getUnsignedInt(p + 16 + 4L * d);
                                if (dim < 1 || dim > Integer.MAX_VALUE) {
                                    throw new HdfFormatException("invalid compound member dimension " + dim + " at " + p);
                                }
                                memberDims[d] = (int) dim;
                            }
                        }
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
                    Result member = read(buf, p, depth + 1);
                    p = member.next;
                    Datatype memberType = member.type;
                    if (memberDims != null) {
                        long elements = 1;
                        for (int dim : memberDims) {
                            elements *= dim;
                        }
                        long arraySize = elements * memberType.size();
                        if (arraySize > Integer.MAX_VALUE) {
                            throw new HdfFormatException("compound array member too large at " + p);
                        }
                        memberType = new Datatype.Array((int) arraySize, memberDims, memberType);
                    }
                    list.add(new Datatype.Compound.Member(name.value, (int) memberOffset, memberType));
                }
                return new Result(new Datatype.Compound(size, list), p);
            }
            case 7: { // reference
                return new Result(new Datatype.Reference(size, referenceKind(bits0 & 0x0F, version)), p);
            }
            case 8: { // enumerated
                int members = bits0 | (bits1 << 8);
                Result base = read(buf, p, depth + 1);
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
                    long value = enumValue(buf, p, base.type);
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
                Result base = read(buf, p, depth + 1);
                return new Result(new Datatype.VariableLength(size, kind, base.type, padding, charset), base.next);
            }
            case 10: { // array
                int rank = buf.getUnsignedByte(p);
                p += 1;
                if (version == 2) {
                    p += 3; // reserved
                }
                int[] dims = new int[rank];
                long elements = 1;
                for (int i = 0; i < rank; i++) {
                    long dim = buf.getUnsignedInt(p);
                    elements *= dim;
                    if (elements > Integer.MAX_VALUE) {
                        throw new HdfFormatException("array datatype has too many elements at " + off);
                    }
                    dims[i] = (int) dim;
                    p += 4;
                }
                if (version == 2) {
                    p += 4L * rank; // permutation indices (unused)
                }
                Result base = read(buf, p, depth + 1);
                return new Result(new Datatype.Array(size, dims, base.type), base.next);
            }
            case 11: { // complex (v5+)
                Result base = read(buf, p, depth + 1);
                return new Result(new Datatype.Complex(size, base.type), base.next);
            }
            default:
                throw new HdfFormatException("unknown datatype class " + typeClass + " at " + off);
        }
    }

    /**
     * An enumeration member's value, stored as an element of the base type ("in the same byte order as
     * the base type"): an integer base is read as its elements are (byte order, sign, bit offset and
     * precision), so a member's value is the integer its elements hold. Any other base, which libhdf5
     * never writes, is read as an unsigned little-endian value, as before.
     */
    private static long enumValue(HdfBuffer buf, long p, Datatype base) {
        if (base instanceof Datatype.FixedPoint fp && fp.bitPrecision() >= 1 && fp.bitPrecision() <= 64
                && (long) fp.bitOffset() + fp.bitPrecision() <= 8L * fp.size()) {
            return Elements.integerValue(MemorySegment.ofArray(buf.getBytes(p, fp.size())), 0, fp);
        }
        return buf.getUnsignedValue(p, base.size());
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

    /** Reference type codes: 0-1 in every version; 2-4 are the revised references of datatype version 4+. */
    private static Datatype.ReferenceKind referenceKind(int code, int version) {
        return switch (code) {
            case 0 -> Datatype.ReferenceKind.OBJECT;
            case 1 -> Datatype.ReferenceKind.DATASET_REGION;
            case 2 -> version >= 4 ? Datatype.ReferenceKind.REVISED_OBJECT : Datatype.ReferenceKind.OTHER;
            case 3 -> version >= 4 ? Datatype.ReferenceKind.REVISED_DATASET_REGION : Datatype.ReferenceKind.OTHER;
            case 4 -> version >= 4 ? Datatype.ReferenceKind.REVISED_ATTRIBUTE : Datatype.ReferenceKind.OTHER;
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
