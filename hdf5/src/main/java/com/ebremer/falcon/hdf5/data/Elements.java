package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Decodes raw element bytes into Java arrays for atomic datatypes, honouring each type's byte order
 * and precision. Multidimensional data is returned flattened in row-major order.
 */
public final class Elements {

    private Elements() {
    }

    private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort BE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    public static int[] toInts(MemorySegment data, int count, Datatype type) {
        Datatype.FixedPoint fp = requireFixed(type, "readInts");
        if (fp.size() > 4) {
            throw new HdfUnsupportedException("integer size " + fp.size() + " exceeds int range; use readLongs()");
        }
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        int size = fp.size();
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            long off = (long) i * size;
            out[i] = switch (size) {
                case 1 -> fp.signed() ? data.get(ValueLayout.JAVA_BYTE, off)
                                      : data.get(ValueLayout.JAVA_BYTE, off) & 0xff;
                case 2 -> {
                    short s = data.get(le ? LE_SHORT : BE_SHORT, off);
                    yield fp.signed() ? s : s & 0xffff;
                }
                case 4 -> data.get(le ? LE_INT : BE_INT, off);
                default -> throw new HdfUnsupportedException("integer size " + size);
            };
        }
        return out;
    }

    public static long[] toLongs(MemorySegment data, int count, Datatype type) {
        Datatype.FixedPoint fp = requireFixed(type, "readLongs");
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        int size = fp.size();
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            long off = (long) i * size;
            out[i] = switch (size) {
                case 1 -> fp.signed() ? data.get(ValueLayout.JAVA_BYTE, off)
                                      : data.get(ValueLayout.JAVA_BYTE, off) & 0xffL;
                case 2 -> {
                    short s = data.get(le ? LE_SHORT : BE_SHORT, off);
                    yield fp.signed() ? s : s & 0xffffL;
                }
                case 4 -> {
                    int v = data.get(le ? LE_INT : BE_INT, off);
                    yield fp.signed() ? v : v & 0xffff_ffffL;
                }
                case 8 -> data.get(le ? LE_LONG : BE_LONG, off);
                default -> throw new HdfUnsupportedException("integer size " + size);
            };
        }
        return out;
    }

    public static double[] toDoubles(MemorySegment data, int count, Datatype type) {
        Datatype.FloatingPoint fp = requireFloat(type, "readDoubles");
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        int size = fp.size();
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = readFloatingPoint(data, (long) i * size, size, le);
        }
        return out;
    }

    public static float[] toFloats(MemorySegment data, int count, Datatype type) {
        Datatype.FloatingPoint fp = requireFloat(type, "readFloats");
        boolean le = fp.byteOrder() == ByteOrder.LITTLE_ENDIAN;
        int size = fp.size();
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = (float) readFloatingPoint(data, (long) i * size, size, le);
        }
        return out;
    }

    public static String[] toStrings(MemorySegment data, int count, Datatype type) {
        if (!(type instanceof Datatype.StringType st)) {
            throw new HdfUnsupportedException("readStrings requires a string datatype, not " + type.typeClass());
        }
        int size = st.size();
        var charset = st.characterSet() == Datatype.CharacterSet.UTF8
                ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII;
        boolean spacePad = st.padding() == Datatype.StringPadding.SPACE_PAD;
        String[] out = new String[count];
        byte[] element = new byte[size];
        for (int i = 0; i < count; i++) {
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, (long) i * size, element, 0, size);
            int length;
            if (spacePad) {
                length = size;
                while (length > 0 && element[length - 1] == ' ') {
                    length--;
                }
            } else {
                length = 0;
                while (length < size && element[length] != 0) {
                    length++;
                }
            }
            out[i] = new String(element, 0, length, charset);
        }
        return out;
    }

    public static byte[] toRawBytes(MemorySegment data, long byteCount) {
        byte[] out = new byte[Math.toIntExact(byteCount)];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    private static double readFloatingPoint(MemorySegment data, long off, int size, boolean le) {
        return switch (size) {
            case 2 -> Float.float16ToFloat(data.get(le ? LE_SHORT : BE_SHORT, off));
            case 4 -> Float.intBitsToFloat(data.get(le ? LE_INT : BE_INT, off));
            case 8 -> Double.longBitsToDouble(data.get(le ? LE_LONG : BE_LONG, off));
            default -> throw new HdfUnsupportedException("floating-point size " + size);
        };
    }

    private static Datatype.FixedPoint requireFixed(Datatype type, String op) {
        if (type instanceof Datatype.FixedPoint fp) {
            return fp;
        }
        throw new HdfUnsupportedException(op + " requires a fixed-point datatype, not " + type.typeClass());
    }

    private static Datatype.FloatingPoint requireFloat(Datatype type, String op) {
        if (type instanceof Datatype.FloatingPoint fp) {
            return fp;
        }
        throw new HdfUnsupportedException(op + " requires a floating-point datatype, not " + type.typeClass());
    }
}
