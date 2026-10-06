package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes Java values as elements of a datatype, for writing: the inverse of {@link ElementReader}, and
 * taking the values its {@code read()} returns. Values must fit their type exactly; nothing is rounded or
 * wrapped except a number written to a floating-point type, which is rounded to the nearest, as libhdf5
 * converts it.
 *
 * <table>
 *   <caption>The Java values each datatype class takes</caption>
 *   <tr><th>Datatype</th><th>Values</th></tr>
 *   <tr><td>integer, bit field</td><td>{@code byte[]}, {@code short[]}, {@code int[]}, {@code long[]},
 *       {@code BigInteger[]}, {@code boolean[]}, or whole {@code float[]}/{@code double[]} values</td></tr>
 *   <tr><td>float</td><td>any numeric array</td></tr>
 *   <tr><td>enumeration</td><td>member names ({@code String[]}), or values as for an integer;
 *       {@code boolean[]} for a type with members {@code FALSE} and {@code TRUE}</td></tr>
 *   <tr><td>time</td><td>{@code Instant[]} (whole seconds), or seconds as for an integer</td></tr>
 *   <tr><td>string (fixed or variable length)</td><td>{@code String[]}</td></tr>
 *   <tr><td>complex</td><td>{@code double[]} or {@code float[]} of (real, imaginary) pairs</td></tr>
 *   <tr><td>compound</td><td>{@code Map<String, ?>} of each member's values</td></tr>
 *   <tr><td>array</td><td>its base type's values, every element's in turn</td></tr>
 *   <tr><td>opaque</td><td>{@code byte[][]}, one array per element</td></tr>
 *   <tr><td>variable-length sequence</td><td>{@code int[][]}, {@code long[][]}, {@code double[][]}, ...,
 *       or {@code Object[]} rows of its base type's values</td></tr>
 *   <tr><td>object reference</td><td>{@code String[]} of absolute paths ({@code null} for none)</td></tr>
 *   <tr><td>region reference</td><td>{@link Hdf5Writer.Region}{@code []}</td></tr>
 * </table>
 */
final class ValueEncoder {

    private ValueEncoder() {
    }

    /** Where variable-length data, and the objects of region references, are kept. */
    interface Heap {
        /** Stores {@code object}; returns its collection number and its index there. */
        long[] add(byte[] object);

        /** Stores a region reference's object, whose dataset address is filled in once known. */
        long[] addRegion(String datasetPath, byte[] selection);
    }

    /** A variable-length id's collection address, at {@code offset}, to fill in once collection {@code collection} is placed. */
    record IdPatch(long offset, int collection) {
    }

    /** An object reference, at {@code offset}, to the object at {@code path}, to fill in once its address is known. */
    record RefPatch(long offset, String path) {
    }

    /** Encoded elements, with what still has to be filled in. */
    record Encoded(byte[] bytes, List<IdPatch> ids, List<RefPatch> refs) {
    }

    /** Where element <i>k</i> of a run of elements is, in bytes. */
    @FunctionalInterface
    private interface Place {
        long at(long k);
    }

    /**
     * {@code count} elements of {@code type} from {@code values}.
     *
     * @throws IllegalArgumentException if the values are of a kind the type does not take, the wrong
     *         number, or do not fit
     * @throws HdfUnsupportedException for a type Falcon does not write values of
     */
    static Encoded encode(Datatype type, long count, Object values, Heap heap, String what) {
        long bytes = count * type.size();
        if (count < 0 || bytes > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException(what + ": " + count + " elements of " + type.size()
                    + " bytes are too many to write at once");
        }
        ValueEncoder.Writer writer = new ValueEncoder.Writer(new byte[(int) bytes], heap, what);
        writer.put(type, count, values, k -> k * type.size());
        return new Encoded(writer.out, writer.ids, writer.refs);
    }

    private static final class Writer {
        final byte[] out;
        final Heap heap;
        final String what;
        final List<IdPatch> ids = new ArrayList<>();
        final List<RefPatch> refs = new ArrayList<>();

        Writer(byte[] out, Heap heap, String what) {
            this.out = out;
            this.heap = heap;
            this.what = what;
        }

        void put(Datatype type, long count, Object values, Place place) {
            switch (type) {
                case Datatype.FixedPoint fp -> integers(fp, count, values, place, null);
                case Datatype.BitField b -> integers(new Datatype.FixedPoint(b.size(), b.byteOrder(), false,
                        b.bitOffset(), b.bitPrecision()), count, values, place, null);
                case Datatype.Time t -> {
                    Datatype.FixedPoint seconds = new Datatype.FixedPoint(t.size(), t.byteOrder(), true, 0, t.bitPrecision());
                    if (values instanceof Instant[] instants) {
                        long[] s = new long[instants.length];
                        for (int i = 0; i < s.length; i++) {
                            if (instants[i].getNano() != 0) {
                                throw new IllegalArgumentException(what + ": time " + instants[i] + " is not whole seconds");
                            }
                            s[i] = instants[i].getEpochSecond();
                        }
                        values = s;
                    }
                    integers(seconds, count, values, place, null);
                }
                case Datatype.Enumeration e -> enumeration(e, count, values, place);
                case Datatype.FloatingPoint fp -> floats(fp, count, values, place);
                case Datatype.Complex c -> {
                    if (!(c.base() instanceof Datatype.FloatingPoint base)) {
                        throw new IllegalArgumentException(what + ": a complex type is a pair of floats");
                    }
                    floats(base, 2 * count, values, k -> place.at(k / 2) + (k % 2) * base.size());
                }
                case Datatype.StringType s -> strings(s, count, values, place);
                case Datatype.VariableLength v -> variableLength(v, count, values, place);
                case Datatype.Opaque o -> opaque(o, count, values, place);
                case Datatype.Compound c -> {
                    if (!(values instanceof Map<?, ?> columns)) {
                        throw kind(type, values, "a Map of each member's values");
                    }
                    for (Datatype.Compound.Member member : c.members()) {
                        if (!columns.containsKey(member.name())) {
                            throw new IllegalArgumentException(what + ": no values for compound member '" + member.name() + "'");
                        }
                        put(member.type(), count, columns.get(member.name()), k -> place.at(k) + member.offset());
                    }
                    for (Object name : columns.keySet()) {
                        if (c.members().stream().noneMatch(m -> m.name().equals(name))) {
                            throw new IllegalArgumentException(what + ": the compound has no member '" + name + "'");
                        }
                    }
                }
                case Datatype.Array a -> {
                    int n = a.elementCount();
                    int baseSize = a.base().size();
                    put(a.base(), count * n, values, k -> place.at(k / n) + (k % n) * baseSize);
                }
                case Datatype.Reference r -> references(r, count, values, place);
            }
        }

        // ------------------------------------------------------------ integers

        /** Integers of {@code fp}; {@code checkMember} (if not null) refuses values no member has. */
        void integers(Datatype.FixedPoint fp, long count, Object values, Place place, java.util.Set<Long> members) {
            int precision = fp.bitPrecision();
            boolean plain = fp.bitOffset() == 0 && precision == 8 * fp.size() && fp.size() <= 8;
            if (fp.size() > 8 || precision > 64) {
                throw new HdfUnsupportedException(what + ": integers wider than 64 bits are not written");
            }
            requireLength(values, count, fp);
            for (long k = 0; k < count; k++) {
                long value = integerAt(values, (int) k, fp);
                if (members != null && !members.contains(value)) {
                    throw new IllegalArgumentException(what + ": " + value + " (element " + k + ") is no member's value");
                }
                int pos = (int) place.at(k);
                if (plain) {
                    boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
                    for (int b = 0; b < fp.size(); b++) {
                        out[pos + (le ? b : fp.size() - 1 - b)] = (byte) (value >>> (8 * b));
                    }
                } else {
                    byte[] element = com.ebremer.falcon.hdf5.write.DatatypeEncoder.integerBytes(value, fp);
                    System.arraycopy(element, 0, out, pos, element.length);
                }
            }
        }

        /** Element {@code i} of {@code values} as an integer of {@code fp}, checked to fit. */
        long integerAt(Object values, int i, Datatype.FixedPoint fp) {
            long value;
            boolean big = false; // an unsigned 64-bit value of 2^63 or more, as its bit pattern
            switch (values) {
                case byte[] a -> value = a[i];
                case short[] a -> value = a[i];
                case int[] a -> value = a[i];
                case long[] a -> value = a[i];
                case boolean[] a -> value = a[i] ? 1 : 0;
                case BigInteger[] a -> {
                    BigInteger v = a[i];
                    if (v.signum() >= 0 && v.bitLength() == 64) {
                        value = v.longValue();
                        big = true;
                    } else if (v.bitLength() < 64) {
                        value = v.longValue();
                    } else {
                        throw new IllegalArgumentException(what + ": " + v + " (element " + i + ") does not fit " + describe(fp));
                    }
                }
                case float[] a -> value = whole(a[i], i, fp);
                case double[] a -> value = whole(a[i], i, fp);
                default -> throw kind(fp, values, "integer values (int[], long[], ...)");
            }
            int p = fp.bitPrecision();
            boolean fits = fp.signed()
                    ? !big && (p >= 64 || (value >= -(1L << (p - 1)) && value < 1L << (p - 1)))
                    : big ? p == 64 : value >= 0 && (p >= 63 || value < 1L << p);
            if (!fits) {
                throw new IllegalArgumentException(what + ": " + (big ? Long.toUnsignedString(value) : value)
                        + " (element " + i + ") does not fit " + describe(fp));
            }
            return value;
        }

        long whole(double v, int i, Datatype.FixedPoint fp) {
            if (v != Math.rint(v) || Math.abs(v) >= 0x1p63) {
                throw new IllegalArgumentException(what + ": " + v + " (element " + i + ") is not a whole number for " + describe(fp));
            }
            return (long) v;
        }

        void enumeration(Datatype.Enumeration e, long count, Object values, Place place) {
            if (!(e.base() instanceof Datatype.FixedPoint base)) {
                throw new IllegalArgumentException(what + ": an enumeration's base must be an integer type");
            }
            Map<String, Long> byName = new HashMap<>();
            for (Datatype.Enumeration.Member member : e.members()) {
                byName.putIfAbsent(member.name(), member.value());
            }
            if (values instanceof String[] names) {
                long[] v = new long[names.length];
                for (int i = 0; i < names.length; i++) {
                    Long value = byName.get(names[i]);
                    if (value == null) {
                        throw new IllegalArgumentException(what + ": '" + names[i] + "' (element " + i + ") is no member's name");
                    }
                    v[i] = value;
                }
                values = v;
            } else if (values instanceof boolean[] flags) {
                Long no = byName.get("FALSE");
                Long yes = byName.get("TRUE");
                if (no == null || yes == null) {
                    throw new IllegalArgumentException(what + ": booleans need an enumeration of FALSE and TRUE");
                }
                long[] v = new long[flags.length];
                for (int i = 0; i < flags.length; i++) {
                    v[i] = flags[i] ? yes : no;
                }
                values = v;
            }
            integers(base, count, values, place, new java.util.HashSet<>(byName.values()));
        }

        // ------------------------------------------------------------ floats

        void floats(Datatype.FloatingPoint fp, long count, Object values, Place place) {
            int size = fp.size();
            boolean ieee = fp.normalization() == Datatype.MantissaNormalization.IMPLIED && fp.mantissaLocation() == 0
                    && fp.bitOffset() == 0 && !fp.vaxOrder() && switch (size) {
                        case 2 -> fp.exponentSize() == 5 && fp.mantissaSize() == 10 && fp.exponentBias() == 15 && fp.signLocation() == 15;
                        case 4 -> fp.exponentSize() == 8 && fp.mantissaSize() == 23 && fp.exponentBias() == 127 && fp.signLocation() == 31;
                        case 8 -> fp.exponentSize() == 11 && fp.mantissaSize() == 52 && fp.exponentBias() == 1023 && fp.signLocation() == 63;
                        default -> false;
                    };
            if (!ieee) {
                throw new HdfUnsupportedException(what + ": values are written only to IEEE 754 binary16, 32 and 64 floats");
            }
            requireLength(values, count, fp);
            boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
            for (long k = 0; k < count; k++) {
                int i = (int) k;
                long bits = switch (size) {
                    case 2 -> Float.floatToFloat16(floatAt(values, i, fp)) & 0xFFFFL;
                    case 4 -> Float.floatToRawIntBits(floatAt(values, i, fp)) & 0xFFFFFFFFL;
                    default -> Double.doubleToRawLongBits(doubleAt(values, i, fp));
                };
                int pos = (int) place.at(k);
                for (int b = 0; b < size; b++) {
                    out[pos + (le ? b : size - 1 - b)] = (byte) (bits >>> (8 * b));
                }
            }
        }

        /** As a {@code float}: a {@code float} as is, anything else rounded once to the nearest. */
        float floatAt(Object values, int i, Datatype type) {
            return switch (values) {
                case float[] a -> a[i];
                case long[] a -> (float) a[i];
                default -> (float) doubleAt(values, i, type);
            };
        }

        double doubleAt(Object values, int i, Datatype type) {
            return switch (values) {
                case double[] a -> a[i];
                case float[] a -> a[i];
                case int[] a -> a[i];
                case long[] a -> a[i];
                case short[] a -> a[i];
                case byte[] a -> a[i];
                case BigInteger[] a -> a[i].doubleValue();
                default -> throw kind(type, values, "numbers (double[], float[], int[], ...)");
            };
        }

        // ------------------------------------------------------------ strings, opaque

        void strings(Datatype.StringType s, long count, Object values, Place place) {
            if (!(values instanceof String[] strings)) {
                throw kind(s, values, "String[]");
            }
            requireLength(values, count, s);
            boolean ascii = s.characterSet() == Datatype.CharacterSet.ASCII;
            int room = s.size() - (s.padding() == Datatype.StringPadding.NULL_TERMINATE ? 1 : 0);
            for (int i = 0; i < strings.length; i++) {
                byte[] bytes = encodeString(strings[i], ascii, i);
                if (bytes.length > room) {
                    throw new IllegalArgumentException(what + ": string " + i + " takes " + bytes.length
                            + " bytes; a " + s.size() + "-byte " + s.padding() + " string holds " + room);
                }
                int pos = (int) place.at(i);
                System.arraycopy(bytes, 0, out, pos, bytes.length);
                if (s.padding() == Datatype.StringPadding.SPACE_PAD) {
                    for (int b = bytes.length; b < s.size(); b++) {
                        out[pos + b] = ' ';
                    }
                }
            }
        }

        byte[] encodeString(String value, boolean ascii, int i) {
            if (value == null) {
                throw new IllegalArgumentException(what + ": string " + i + " is null");
            }
            if (ascii && !StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
                throw new IllegalArgumentException(what + ": string " + i + " is not ASCII, as its type's character set is");
            }
            if (value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(what + ": string " + i + " contains NUL");
            }
            return value.getBytes(StandardCharsets.UTF_8);
        }

        void opaque(Datatype.Opaque o, long count, Object values, Place place) {
            if (!(values instanceof byte[][] elements)) {
                throw kind(o, values, "byte[][], one array per element");
            }
            requireLength(values, count, o);
            for (int i = 0; i < elements.length; i++) {
                if (elements[i].length != o.size()) {
                    throw new IllegalArgumentException(what + ": opaque element " + i + " has " + elements[i].length
                            + " bytes, not " + o.size());
                }
                System.arraycopy(elements[i], 0, out, (int) place.at(i), o.size());
            }
        }

        // ------------------------------------------------------------ heap data and references

        void variableLength(Datatype.VariableLength v, long count, Object values, Place place) {
            requireLength(values, count, v);
            for (int i = 0; i < count; i++) {
                byte[] object;
                long length;
                if (v.kind() == Datatype.VlenKind.STRING) {
                    if (!(values instanceof String[] strings)) {
                        throw kind(v, values, "String[]");
                    }
                    object = encodeString(strings[i], v.characterSet() == Datatype.CharacterSet.ASCII, i);
                    length = object.length;
                } else {
                    if (isHeapType(v.base())) {
                        throw new HdfUnsupportedException(what + ": sequences of variable-length data or references are not written");
                    }
                    Object row = java.lang.reflect.Array.get(values, i);
                    if (row == null) {
                        throw new IllegalArgumentException(what + ": row " + i + " is null");
                    }
                    length = rowLength(v.base(), row, i);
                    object = encode(v.base(), length, row, heap, what).bytes();
                }
                int pos = (int) place.at(i);
                writeU32(pos, length);
                if (object.length == 0) {
                    continue; // an empty row or string: no heap object, a null address
                }
                long[] slot = heap.add(object);
                ids.add(new IdPatch(pos + 4, (int) slot[0]));
                writeU32(pos + 12, slot[1]);
            }
        }

        /** The elements a sequence row holds: a compound row's first column's length, else its values over each element's. */
        long rowLength(Datatype base, Object row, int i) {
            if (base instanceof Datatype.Compound) {
                if (!(row instanceof Map<?, ?> columns) || columns.isEmpty()) {
                    throw new IllegalArgumentException(what + ": row " + i + " of a compound sequence must be a Map of its members' values");
                }
                Object first = columns.values().iterator().next();
                return first == null || !first.getClass().isArray() ? 0 : java.lang.reflect.Array.getLength(first);
            }
            if (!row.getClass().isArray()) {
                throw new IllegalArgumentException(what + ": row " + i + " is a " + row.getClass().getSimpleName() + ", not an array");
            }
            return java.lang.reflect.Array.getLength(row) / Math.max(1, flattenedCount(base));
        }

        void references(Datatype.Reference r, long count, Object values, Place place) {
            requireLength(values, count, r);
            switch (r.kind()) {
                case OBJECT -> {
                    if (!(values instanceof String[] paths)) {
                        throw kind(r, values, "String[] of absolute paths");
                    }
                    for (int i = 0; i < paths.length; i++) {
                        if (paths[i] != null) {
                            refs.add(new RefPatch(place.at(i), paths[i])); // a null reference stays all zeros
                        }
                    }
                }
                case DATASET_REGION -> {
                    if (!(values instanceof Hdf5Writer.Region[] regions)) {
                        throw kind(r, values, "Hdf5Writer.Region[]");
                    }
                    for (int i = 0; i < regions.length; i++) {
                        if (regions[i] == null) {
                            continue;
                        }
                        long[] slot = heap.addRegion(regions[i].datasetPath(), regions[i].serialize());
                        int pos = (int) place.at(i);
                        ids.add(new IdPatch(pos, (int) slot[0]));
                        writeU32(pos + 8, slot[1]);
                    }
                }
                default -> throw new HdfUnsupportedException(what + ": writing " + r.kind() + " references is not supported");
            }
        }

        void writeU32(int pos, long value) {
            for (int b = 0; b < 4; b++) {
                out[pos + b] = (byte) (value >>> (8 * b));
            }
        }

        void requireLength(Object values, long count, Datatype type) {
            if (values == null || !values.getClass().isArray()) {
                throw kind(type, values, "an array");
            }
            int length = java.lang.reflect.Array.getLength(values);
            if (length != count) {
                throw new IllegalArgumentException(what + ": " + length + " values given for " + count + " elements");
            }
        }

        IllegalArgumentException kind(Datatype type, Object values, String expected) {
            return new IllegalArgumentException(what + ": a " + type.typeClass() + " datatype takes " + expected
                    + ", not " + (values == null ? "null" : values.getClass().getSimpleName()));
        }
    }

    /**
     * How many elements of {@code type} {@code values} hold: their length, over an array type's elements,
     * or for a compound the first member's values'.
     */
    static long valueCount(Datatype type, Object values) {
        if (type instanceof Datatype.Compound && values instanceof Map<?, ?> columns && !columns.isEmpty()) {
            Object first = columns.values().iterator().next();
            Datatype.Compound c = (Datatype.Compound) type;
            Object name = columns.keySet().iterator().next();
            Datatype member = c.members().stream().filter(m -> m.name().equals(name)).findFirst()
                    .map(Datatype.Compound.Member::type).orElse(null);
            return first == null || !first.getClass().isArray() || member == null ? 0
                    : java.lang.reflect.Array.getLength(first) / Math.max(1, valuesPerElement(member));
        }
        if (values == null || !values.getClass().isArray()) {
            throw new IllegalArgumentException("values must be an array, not " + (values == null ? "null" : values.getClass().getSimpleName()));
        }
        return java.lang.reflect.Array.getLength(values) / Math.max(1, valuesPerElement(type));
    }

    /** The values one element takes in a flat array: an array type's elements, a complex number's two parts. */
    private static int valuesPerElement(Datatype type) {
        return switch (type) {
            case Datatype.Array a -> a.elementCount() * valuesPerElement(a.base());
            case Datatype.Complex c -> 2;
            default -> 1;
        };
    }

    /** True for a type whose elements hold heap ids or references (which need filling in after encoding). */
    static boolean isHeapType(Datatype type) {
        return switch (type) {
            case Datatype.VariableLength v -> true;
            case Datatype.Reference r -> true;
            case Datatype.Compound c -> c.members().stream().anyMatch(m -> isHeapType(m.type()));
            case Datatype.Array a -> isHeapType(a.base());
            default -> false;
        };
    }

    /** True for a type whose elements hold object addresses (references), known only when the file is complete. */
    static boolean holdsReferences(Datatype type) {
        return switch (type) {
            case Datatype.Reference r -> true;
            case Datatype.Compound c -> c.members().stream().anyMatch(m -> holdsReferences(m.type()));
            case Datatype.Array a -> holdsReferences(a.base());
            default -> false;
        };
    }

    /** The leaf values one element of {@code type} takes in a flattened array (an array type's element count). */
    private static int flattenedCount(Datatype type) {
        return type instanceof Datatype.Array a ? a.elementCount() * flattenedCount(a.base()) : 1;
    }

    private static String describe(Datatype.FixedPoint fp) {
        return (fp.signed() ? "int" : "uint") + fp.bitPrecision();
    }
}
