package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Writing a value an array's type cannot hold (P0 Z4), and fill values set from numbers (P0 Z10).
 *
 * <p>Writes used to narrow with Java casts, silently: 200 into int8 stored -56, NaN into int32 stored 0,
 * 1.8e19 into uint64 stored 2^63 - 1, NaN into bool stored false, and float16 rounded twice. Now a value is
 * stored exactly, or rounded to nearest for a float type, or refused with an IllegalArgumentException.
 */
class ConversionTest {

    private static ZarrArray array(DataType type, int n) {
        return Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {n}, type).build());
    }

    private static void assertRefused(Runnable write, String... expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, write::run);
        for (String text : expected) {
            assertTrue(e.getMessage().contains(text), "expected '" + text + "' in: " + e.getMessage());
        }
    }

    // ---- integer types ----------------------------------------------------------------------------

    @Test
    void integersOutOfRangeAreRefused() {
        ZarrArray int8 = array(DataType.INT8, 3);
        assertRefused(() -> int8.writeInts(new int[] {1, 200, 3}), "200", "index 1", "int8", "[-128, 127]");
        assertRefused(() -> int8.writeInts(new int[] {-129, 0, 0}), "-129");
        assertRefused(() -> int8.writeLongs(new long[] {0, 0, 1000}), "1000");
        assertArrayEquals(new int[] {0, 0, 0}, int8.readInts()); // nothing was written
        int8.writeInts(new int[] {-128, 0, 127});
        assertArrayEquals(new int[] {-128, 0, 127}, int8.readInts());

        ZarrArray uint16 = array(DataType.UINT16, 2);
        assertRefused(() -> uint16.writeInts(new int[] {-1, 0}), "-1", "[0, 65535]");
        uint16.writeInts(new int[] {65535, 0});
        assertArrayEquals(new int[] {65535, 0}, uint16.readInts());

        ZarrArray uint64 = array(DataType.UINT64, 2);
        assertRefused(() -> uint64.writeLongs(new long[] {0, -1}), "-1", "18446744073709551615");
        uint64.writeLongs(new long[] {Long.MAX_VALUE, 0});
        assertEquals(0x1p63, uint64.readDoubles()[0]);
    }

    @Test
    void doublesIntoIntegersMustBeWholeAndInRange() {
        ZarrArray int32 = array(DataType.INT32, 3);
        // Before the fix: [2, 0, -727379968].
        assertRefused(() -> int32.writeDoubles(new double[] {2.9, 0, 0}), "2.9", "not a whole number");
        assertRefused(() -> int32.writeDoubles(new double[] {0, Double.NaN, 0}), "NaN", "index 1");
        assertRefused(() -> int32.writeDoubles(new double[] {0, 0, 1e12}), "1.0E12", "out of range");
        assertRefused(() -> int32.writeDoubles(new double[] {Double.POSITIVE_INFINITY, 0, 0}), "Infinity");
        assertRefused(() -> int32.writeFloats(new float[] {0.5f, 0, 0}), "0.5");
        int32.writeDoubles(new double[] {-2147483648.0, -0.0, 2147483647.0});
        assertArrayEquals(new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, int32.readInts());

        ZarrArray int64 = array(DataType.INT64, 2);
        assertRefused(() -> int64.writeDoubles(new double[] {0x1p63, 0}), "out of range");
        int64.writeDoubles(new double[] {-0x1p63, 0x1p62});
        assertArrayEquals(new long[] {Long.MIN_VALUE, 1L << 62}, int64.readLongs());
    }

    @Test
    void uint64TakesDoublesUpTo2To64Exactly() {
        ZarrArray uint64 = array(DataType.UINT64, 3);
        // Before the fix 1.8e19 was stored as 2^63 - 1, though readDoubles decodes 1.8e19 correctly.
        uint64.writeDoubles(new double[] {1.8e19, 0x1p63, 0x1p64 - 0x1p11});
        ByteBuffer raw = ByteBuffer.wrap(uint64.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("18000000000000000000", Long.toUnsignedString(raw.getLong(0)));
        assertEquals("9223372036854775808", Long.toUnsignedString(raw.getLong(8)));
        assertEquals("18446744073709549568", Long.toUnsignedString(raw.getLong(16)));
        assertArrayEquals(new double[] {1.8e19, 0x1p63, 0x1p64 - 0x1p11}, uint64.readDoubles());
        assertRefused(() -> uint64.writeDoubles(new double[] {0x1p64, 0, 0}), "out of range");
        assertRefused(() -> uint64.writeDoubles(new double[] {-1, 0, 0}), "out of range");
    }

    @Test
    void uint64ReadsRoundToNearest() {
        // 2^63 + 1025 lies just above the midpoint of 2^63 and 2^63 + 2048. Halving it dropped the low
        // bit, so the midpoint rounded to even, 2^63; the nearest double is 2^63 + 2048.
        ZarrArray uint64 = array(DataType.UINT64, 1);
        uint64.writeRawBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(Long.MIN_VALUE + 1025).array());
        assertEquals(0x1p63 + 2048, uint64.readDoubles()[0]);
    }

    // ---- bool -------------------------------------------------------------------------------------

    @Test
    void boolStoresNonzeroAsTrue() {
        ZarrArray bool = array(DataType.BOOL, 5);
        // Before the fix 0.5 and NaN were stored as false; numpy gives true for both.
        bool.writeDoubles(new double[] {0.5, Double.NaN, 0, -0.0, -3});
        assertArrayEquals(new int[] {1, 1, 0, 0, 1}, bool.readInts());
        bool.writeLongs(new long[] {0, 2, Long.MIN_VALUE, 0, 1});
        assertArrayEquals(new int[] {0, 1, 1, 0, 1}, bool.readInts());
    }

    // ---- float types ------------------------------------------------------------------------------

    @Test
    void float16RoundsOnceFromDouble() {
        ZarrArray half = array(DataType.FLOAT16, 4);
        // Via float, 1 + 2^-11 + 2^-40 became the tie 1 + 2^-11, which rounds to even, 1.0.
        half.writeDoubles(new double[] {1 + 0x1p-11 + 0x1p-40, 65519.99, Double.POSITIVE_INFINITY, Double.NaN});
        double[] read = half.readDoubles();
        assertEquals(1 + 0x1p-10, read[0]);
        assertEquals(65504, read[1]);
        assertEquals(Double.POSITIVE_INFINITY, read[2]);
        assertTrue(Double.isNaN(read[3]));
        assertRefused(() -> half.writeDoubles(new double[] {65520, 0, 0, 0}), "65520.0", "float16", "out of range");
        assertRefused(() -> half.writeLongs(new long[] {0, 0, -70000, 0}), "-70000");
    }

    @Test
    void float32RefusesOnlyOverflow() {
        ZarrArray f32 = array(DataType.FLOAT32, 3);
        f32.writeDoubles(new double[] {0.1, Float.MAX_VALUE, 1e-50});
        assertArrayEquals(new float[] {0.1f, Float.MAX_VALUE, 0f}, f32.readFloats()); // rounding is not loss
        assertRefused(() -> f32.writeDoubles(new double[] {0, 1e40, 0}), "1.0E40", "float32");

        // long -> float32 rounds once: through a double, 2^60 + 2^36 + 1 first rounds to the tie
        // 2^60 + 2^36 and then to even, 2^60, while the nearest float is 2^60 + 2^37.
        f32.writeLongs(new long[] {(1L << 60) + (1L << 36) + 1, 0, 0});
        assertEquals(0x1p60 + 0x1p37, f32.readDoubles()[0]);
    }

    // ---- fill values set from numbers (Z10) --------------------------------------------------------

    private static JsonValue fill(ArraySpec.Builder builder) {
        return Zarr.createArray(new MemoryStore(), builder.build()).fillValue();
    }

    @Test
    void anIntegerFillIsWrittenAsAnInteger() {
        // fillValue(double) wrote 1.0 on an integer type; it is written as 1 now.
        ArraySpec spec = ArraySpec.builder(new long[] {2}, DataType.INT32).fillValue(1.0).build();
        assertTrue(Json.write(spec.toJson()).contains("\"fill_value\":1,"), Json.write(spec.toJson()));
        assertEquals("1", fill(ArraySpec.builder(new long[] {2}, DataType.INT32).fillValue(1.0))
                .asNumber().literal());
        assertEquals("18000000000000000000", fill(
                ArraySpec.builder(new long[] {2}, DataType.UINT64).fillValue(1.8e19)).asNumber().literal());

        assertBuildFails(ArraySpec.builder(new long[] {2}, DataType.INT32).fillValue(1.5), "not a whole number");
        assertBuildFails(ArraySpec.builder(new long[] {2}, DataType.UINT8).fillValue(300), "[0, 255]");
        assertBuildFails(ArraySpec.builder(new long[] {2}, DataType.INT8).fillValue(Double.NaN), "NaN");
    }

    @Test
    void numericFillsTakeEachTypesOwnForm() {
        assertEquals(JsonBool.TRUE, fill(ArraySpec.builder(new long[] {2}, DataType.BOOL).fillValue(1)));
        assertEquals("NaN", fill(
                ArraySpec.builder(new long[] {2}, DataType.FLOAT32).fillValue(Double.NaN)).asString());
        assertEquals("-Infinity", fill(
                ArraySpec.builder(new long[] {2}, DataType.FLOAT64).fillValue(Double.NEGATIVE_INFINITY)).asString());
        // A NaN other than the canonical one keeps its bits; "NaN" would lose them.
        double payload = Double.longBitsToDouble(0x7ff8000000000001L);
        ZarrArray a = Zarr.createArray(new MemoryStore(),
                ArraySpec.builder(new long[] {2}, DataType.FLOAT64).fillValue(payload).build());
        assertEquals("0x7ff8000000000001", a.fillValue().asString());
        assertEquals(0x7ff8000000000001L,
                Double.doubleToRawLongBits(ByteBuffer.wrap(a.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN).getDouble(0)));

        assertBuildFails(ArraySpec.builder(new long[] {2}, DataType.FLOAT32).fillValue(1e40), "out of range");
        assertBuildFails(ArraySpec.builder(new long[] {2}, DataType.COMPLEX64).fillValue(1.0), "fillValue(JsonValue)");
        assertBuildFails(ArraySpec.builder(new long[] {2}, DataType.STRING).fillValue(1), "fillValue(JsonValue)");
    }

    private static void assertBuildFails(ArraySpec.Builder builder, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }
}
