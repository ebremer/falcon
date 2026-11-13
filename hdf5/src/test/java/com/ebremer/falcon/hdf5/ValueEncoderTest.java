package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * P2 PF14: values that are a type's own (a Java array of elements of its size) are copied in one move. The
 * bytes are those an element at a time gives, in either byte order; and values that do not fit are refused as
 * they were, by the element that does not.
 */
class ValueEncoderTest {

    private static final ByteOrder[] ORDERS = {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN};
    private static final int N = 1000;

    private static byte[] encode(Datatype type, long count, Object values) {
        ValueEncoder.Encoded encoded = ValueEncoder.encode(type, count, values, null, "the test");
        assertTrue(encoded.ids().isEmpty() && encoded.refs().isEmpty());
        assertEquals(count * type.size(), encoded.bytes().length);
        return encoded.bytes();
    }

    private static Datatype.FixedPoint integer(int size, ByteOrder order, boolean signed) {
        return new Datatype.FixedPoint(size, order, signed, 0, 8 * size);
    }

    /** {@code n} values that fit a {@code size}-byte integer type: any at all, or none negative. */
    private static long[] integers(Random random, int size, boolean signed, int n) {
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            long v = random.nextLong() >> (64 - 8 * size); // sign-extended to the size
            values[i] = signed ? v : v & (size == 8 ? Long.MAX_VALUE : (1L << (8 * size - 1)) - 1);
        }
        values[0] = signed ? -(1L << (8 * size - 1)) : 0;                       // the least
        values[1] = size == 8 ? Long.MAX_VALUE : (1L << (8 * size - 1)) - 1;    // the most a Java array of the size holds
        return values;
    }

    /** {@code values}, written one after another in {@code order}, {@code size} bytes each. */
    private static byte[] bytes(long[] values, int size, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * size).order(order);
        for (long v : values) {
            switch (size) {
                case 1 -> buffer.put((byte) v);
                case 2 -> buffer.putShort((short) v);
                case 4 -> buffer.putInt((int) v);
                default -> buffer.putLong(v);
            }
        }
        return buffer.array();
    }

    /** {@code values} as the Java array whose elements are {@code size} bytes. */
    private static Object arrayOf(long[] values, int size) {
        switch (size) {
            case 1 -> {
                byte[] a = new byte[values.length];
                for (int i = 0; i < a.length; i++) {
                    a[i] = (byte) values[i];
                }
                return a;
            }
            case 2 -> {
                short[] a = new short[values.length];
                for (int i = 0; i < a.length; i++) {
                    a[i] = (short) values[i];
                }
                return a;
            }
            case 4 -> {
                int[] a = new int[values.length];
                for (int i = 0; i < a.length; i++) {
                    a[i] = (int) values[i];
                }
                return a;
            }
            default -> {
                return values.clone();
            }
        }
    }

    @Test
    void integersOfTheTypesOwnSizeAreTheirBytesInEitherOrder() {
        Random random = new Random(14);
        for (int size : new int[] {1, 2, 4, 8}) {
            for (boolean signed : new boolean[] {true, false}) {
                for (ByteOrder order : ORDERS) {
                    Datatype.FixedPoint type = integer(size, order, signed);
                    long[] values = integers(random, size, signed, N);
                    byte[] expected = bytes(values, size, order);
                    String what = (signed ? "int" : "uint") + 8 * size + " " + order;
                    assertArrayEquals(expected, encode(type, N, arrayOf(values, size)), what);
                    // And the same values given another way, which are encoded an element at a time.
                    Object another = size == 8 ? java.util.Arrays.stream(values).mapToObj(BigInteger::valueOf)
                            .toArray(BigInteger[]::new) : values;
                    assertArrayEquals(expected, encode(type, N, another), what + ", an element at a time");
                }
            }
        }
    }

    @Test
    void floatsOfTheTypesOwnSizeAreTheirBitsInEitherOrder() {
        Random random = new Random(15);
        double[] doubles = new double[N];
        float[] floats = new float[N];
        for (int i = 0; i < N; i++) {
            doubles[i] = Double.longBitsToDouble(random.nextLong()); // any bits at all: NaNs of every payload among them
            floats[i] = Float.intBitsToFloat(random.nextInt());
        }
        double[] special = {0.0, -0.0, Double.MIN_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN,
                Double.longBitsToDouble(0x7FF0000000000001L), Double.longBitsToDouble(0xFFF8000000000123L)};
        System.arraycopy(special, 0, doubles, 0, special.length);
        float[] special32 = {0.0f, -0.0f, Float.MIN_VALUE, -Float.MAX_VALUE, Float.NEGATIVE_INFINITY, Float.NaN,
                Float.intBitsToFloat(0x7F800001), Float.intBitsToFloat(0xFFC00123)};
        System.arraycopy(special32, 0, floats, 0, special32.length);
        for (ByteOrder order : ORDERS) {
            ByteBuffer expected = ByteBuffer.allocate(8 * N).order(order);
            for (double v : doubles) {
                expected.putLong(Double.doubleToRawLongBits(v));
            }
            assertArrayEquals(expected.array(), encode(Datatype.float64().withByteOrder(order), N, doubles), "float64 " + order);

            ByteBuffer expected32 = ByteBuffer.allocate(4 * N).order(order);
            for (float v : floats) {
                expected32.putInt(Float.floatToRawIntBits(v));
            }
            assertArrayEquals(expected32.array(), encode(Datatype.float32().withByteOrder(order), N, floats), "float32 " + order);
        }
        // Numbers of another kind are still converted: floats widened, doubles rounded once to the nearest.
        float[] plain = {1.5f, -2.25f, 1e-30f, 3.4e38f};
        double[] widened = {1.5f, -2.25f, 1e-30f, 3.4e38f};
        assertArrayEquals(encode(Datatype.float64(), 4, widened), encode(Datatype.float64(), 4, plain));
        assertArrayEquals(encode(Datatype.float32(), 4, plain), encode(Datatype.float32(), 4, widened));
        assertArrayEquals(encode(Datatype.float32(), 2, new float[] {0.1f, 16777216f}),
                encode(Datatype.float32(), 2, new double[] {0.1, 16777217.0}));
    }

    @Test
    void aValueThatDoesNotFitIsRefusedByItsElementAsBefore() {
        short[] shorts = {1, 2, 3, -4, 5, -6};
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> encode(Datatype.uint16(), 6, shorts));
        assertEquals("the test: -4 (element 3) does not fit uint16", thrown.getMessage());
        assertEquals("the test: -1 (element 0) does not fit uint8", assertThrows(IllegalArgumentException.class,
                () -> encode(Datatype.uint8(), 2, new byte[] {-1, 1})).getMessage());
        assertEquals("the test: -7 (element 1) does not fit uint32", assertThrows(IllegalArgumentException.class,
                () -> encode(Datatype.uint32(), 2, new int[] {7, -7})).getMessage());
        assertEquals("the test: -9 (element 2) does not fit uint64", assertThrows(IllegalArgumentException.class,
                () -> encode(Datatype.uint64(), 3, new long[] {Long.MAX_VALUE, 0, -9})).getMessage());
        // The same arrays fit the signed types of their size, whatever they hold.
        assertArrayEquals(bytes(new long[] {1, 2, 3, -4, 5, -6}, 2, ByteOrder.LITTLE_ENDIAN), encode(Datatype.int16(), 6, shorts));
        // The number of values is checked first, as it was.
        assertEquals("the test: 6 values given for 5 elements", assertThrows(IllegalArgumentException.class,
                () -> encode(Datatype.int16(), 5, shorts)).getMessage());
        assertEquals("the test: 3 values given for 4 elements", assertThrows(IllegalArgumentException.class,
                () -> encode(Datatype.float64(), 4, new double[3])).getMessage());
        // Values of a narrower precision than their bytes are still checked one by one.
        Datatype.FixedPoint twelveBits = new Datatype.FixedPoint(2, ByteOrder.LITTLE_ENDIAN, true, 0, 12);
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x07}, encode(twelveBits, 1, new short[] {2047}));
        assertThrows(IllegalArgumentException.class, () -> encode(twelveBits, 1, new short[] {2048}));
        // And an enumeration's against its members.
        assertArrayEquals(new byte[] {1, 0}, encode(Datatype.bool(), 2, new byte[] {1, 0}));
        assertThrows(IllegalArgumentException.class, () -> encode(Datatype.bool(), 2, new byte[] {1, 2}));
    }

    @Test
    void partsThatFillTheirElementsAreCopiedTogetherAndOthersPlaced() {
        Random random = new Random(16);
        double[] doubles = random.doubles(6 * 4).toArray();
        ByteBuffer plain = ByteBuffer.allocate(8 * doubles.length).order(ByteOrder.BIG_ENDIAN);
        for (double v : doubles) {
            plain.putDouble(v);
        }
        Datatype.FloatingPoint big = Datatype.float64().withByteOrder(ByteOrder.BIG_ENDIAN);
        // An array type's elements, and a complex number's two parts, lie one after another.
        assertArrayEquals(plain.array(), encode(Datatype.arrayOf(big, 2, 3), 4, doubles));
        assertArrayEquals(plain.array(), encode(Datatype.complexOf(big), 12, doubles));
        assertArrayEquals(plain.array(), encode(Datatype.arrayOf(Datatype.complexOf(big), 3), 4, doubles));

        // A compound's members lie apart, each at its offset in its record.
        int[] ids = random.ints(5).toArray();
        double[] weights = random.doubles(5).toArray();
        short[] flags = {-1, 2, -3, 4, -5};
        Map<String, Datatype> members = new LinkedHashMap<>();
        members.put("id", Datatype.int32());
        members.put("weight", big);
        members.put("flag", Datatype.int16());
        Datatype.Compound compound = Datatype.compound(members);
        ByteBuffer records = ByteBuffer.allocate(5 * 14);
        for (int i = 0; i < 5; i++) {
            records.order(ByteOrder.LITTLE_ENDIAN).putInt(ids[i]);
            records.order(ByteOrder.BIG_ENDIAN).putDouble(weights[i]);
            records.order(ByteOrder.LITTLE_ENDIAN).putShort(flags[i]);
        }
        assertArrayEquals(records.array(), encode(compound, 5, Map.of("id", ids, "weight", weights, "flag", flags)));

        // An array inside a record does not fill it: its elements are placed record by record.
        Map<String, Datatype> mixed = new LinkedHashMap<>();
        mixed.put("triple", Datatype.arrayOf(Datatype.int16(), 3));
        mixed.put("pair", Datatype.complexOf(big));
        short[] triples = {1, -2, 3, -4, 5, -6, 7, -8, 9};
        double[] pairs = {1.5, -2.5, 3.5, -4.5, 5.5, -6.5};
        ByteBuffer mixedRecords = ByteBuffer.allocate(3 * 22);
        for (int i = 0; i < 3; i++) {
            mixedRecords.order(ByteOrder.LITTLE_ENDIAN).putShort(triples[3 * i]).putShort(triples[3 * i + 1]).putShort(triples[3 * i + 2]);
            mixedRecords.order(ByteOrder.BIG_ENDIAN).putDouble(pairs[2 * i]).putDouble(pairs[2 * i + 1]);
        }
        assertArrayEquals(mixedRecords.array(),
                encode(Datatype.compound(mixed), 3, Map.of("triple", triples, "pair", pairs)));

        // A compound of one member is that member's values, at its offset.
        Datatype.Compound padded = new Datatype.Compound(8, List.of(new Datatype.Compound.Member("only", 0, Datatype.int64())));
        long[] longs = random.longs(7).toArray();
        assertArrayEquals(bytes(longs, 8, ByteOrder.LITTLE_ENDIAN), encode(padded, 7, Map.of("only", longs)));
        Datatype.Compound gap = new Datatype.Compound(12, List.of(new Datatype.Compound.Member("late", 4, Datatype.int64())));
        ByteBuffer gapped = ByteBuffer.allocate(7 * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : longs) {
            gapped.putInt(0).putLong(v);
        }
        assertArrayEquals(gapped.array(), encode(gap, 7, Map.of("late", longs)));

        // Seconds, and the bits of a bit field, are integers of their size.
        assertArrayEquals(bytes(longs, 8, ByteOrder.LITTLE_ENDIAN), encode(Datatype.unixTime(8), 7, longs));
        assertArrayEquals(new byte[] {0x34, 0x12, (byte) 0xFF, 0x7F}, encode(Datatype.bitField(2), 2, new short[] {0x1234, 0x7FFF}));
    }

    @Test
    void noValuesAreNoBytes() {
        assertEquals(0, encode(Datatype.float64(), 0, new double[0]).length);
        assertEquals(0, encode(Datatype.uint16(), 0, new short[0]).length);
        assertEquals(0, encode(Datatype.int8(), 0, new byte[0]).length);
    }
}
