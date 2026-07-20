package com.ebremer.falcon.zarr.datatype;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DataTypeTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static JsonValue num(long v) {
        return JsonNumber.of(v);
    }

    private static JsonValue num(double v) {
        return JsonNumber.of(v);
    }

    private static JsonValue str(String v) {
        return new JsonString(v);
    }

    // ---- name resolution --------------------------------------------------------------------------

    @Test
    void resolvesCoreTypes() {
        assertSame(DataType.FLOAT64, DataType.of("float64"));
        assertSame(DataType.INT32, DataType.of("int32"));
        assertSame(DataType.COMPLEX128, DataType.of("complex128"));
        assertEquals(DataTypeKind.UINT, DataType.of("uint16").kind());
        assertEquals(8, DataType.of("int64").byteCount());
        assertEquals(2, DataType.of("float16").byteCount());
    }

    @Test
    void resolvesRawTypes() {
        assertEquals(DataTypeKind.RAW, DataType.of("r8").kind());
        assertEquals(1, DataType.of("r8").byteCount());
        assertEquals(3, DataType.of("r24").byteCount());
        assertEquals(DataType.of("r16"), DataType.of("r16"));
    }

    @Test
    void rejectsBadTypeNames() {
        assertThrows(ZarrFormatException.class, () -> DataType.of("r7"));   // not a multiple of 8
        assertThrows(ZarrFormatException.class, () -> DataType.of("r0"));
        assertThrows(ZarrUnsupportedException.class, () -> DataType.of("float128"));
        assertThrows(ZarrUnsupportedException.class, () -> DataType.of("rabc"));
    }

    // ---- bool -------------------------------------------------------------------------------------

    @Test
    void boolFill() {
        assertArrayEquals(bytes(1), DataType.BOOL.decodeFillValue(JsonBool.TRUE, LITTLE_ENDIAN));
        assertArrayEquals(bytes(0), DataType.BOOL.decodeFillValue(JsonBool.FALSE, LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> DataType.BOOL.decodeFillValue(num(1), LITTLE_ENDIAN));
    }

    // ---- integers -------------------------------------------------------------------------------

    @Test
    void signedIntegerFillBytes() {
        assertArrayEquals(bytes(0xff), DataType.INT8.decodeFillValue(num(-1), LITTLE_ENDIAN));
        assertArrayEquals(bytes(0x34, 0x12), DataType.INT16.decodeFillValue(num(0x1234), LITTLE_ENDIAN));
        assertArrayEquals(bytes(0x12, 0x34), DataType.INT16.decodeFillValue(num(0x1234), BIG_ENDIAN));
        assertArrayEquals(bytes(0x44, 0x33, 0x22, 0x11),
                DataType.INT32.decodeFillValue(num(0x11223344), LITTLE_ENDIAN));
    }

    @Test
    void unsignedIntegerFillBytes() {
        assertArrayEquals(bytes(0xff), DataType.UINT8.decodeFillValue(num(255), LITTLE_ENDIAN));
        assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff),
                DataType.UINT32.decodeFillValue(num(4_294_967_295L), LITTLE_ENDIAN));
        // uint64 max = 2^64 - 1 exceeds long; supplied as a bare JSON integer literal
        byte[] max = DataType.UINT64.decodeFillValue(JsonNumber.of(new java.math.BigInteger("18446744073709551615")),
                LITTLE_ENDIAN);
        assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), max);
    }

    @Test
    void integerRangeIsChecked() {
        assertThrows(ZarrFormatException.class, () -> DataType.INT8.decodeFillValue(num(128), LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> DataType.INT8.decodeFillValue(num(-129), LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> DataType.UINT8.decodeFillValue(num(-1), LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> DataType.UINT8.decodeFillValue(num(256), LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class, () -> DataType.INT32.decodeFillValue(num(1.5), LITTLE_ENDIAN));
    }

    // ---- floats ---------------------------------------------------------------------------------

    @Test
    void float32FillBytes() {
        assertArrayEquals(bytes(0x00, 0x00, 0x80, 0x3f),
                DataType.FLOAT32.decodeFillValue(num(1.0), LITTLE_ENDIAN));
        assertArrayEquals(bytes(0x3f, 0x80, 0x00, 0x00),
                DataType.FLOAT32.decodeFillValue(num(1.0), BIG_ENDIAN));
    }

    @Test
    void float64FillBytes() {
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xf0, 0x3f),
                DataType.FLOAT64.decodeFillValue(num(1.0), LITTLE_ENDIAN));
    }

    @Test
    void float16FillBytes() {
        assertArrayEquals(bytes(0x00, 0x3c), DataType.FLOAT16.decodeFillValue(num(1.0), LITTLE_ENDIAN));
    }

    @Test
    void floatSpecialValues() {
        assertArrayEquals(bytes(0x00, 0x00, 0xc0, 0x7f),
                DataType.FLOAT32.decodeFillValue(str("NaN"), LITTLE_ENDIAN));       // 0x7fc00000
        assertArrayEquals(bytes(0x00, 0x00, 0x80, 0x7f),
                DataType.FLOAT32.decodeFillValue(str("Infinity"), LITTLE_ENDIAN));  // 0x7f800000
        assertArrayEquals(bytes(0x00, 0x00, 0x80, 0xff),
                DataType.FLOAT32.decodeFillValue(str("-Infinity"), LITTLE_ENDIAN)); // 0xff800000
    }

    @Test
    void floatHexStringMatchesNumber() {
        assertArrayEquals(DataType.FLOAT32.decodeFillValue(num(1.0), LITTLE_ENDIAN),
                DataType.FLOAT32.decodeFillValue(str("0x3f800000"), LITTLE_ENDIAN));
        assertArrayEquals(DataType.FLOAT32.decodeFillValue(num(1.0), BIG_ENDIAN),
                DataType.FLOAT32.decodeFillValue(str("0x3f800000"), BIG_ENDIAN));
    }

    // ---- complex --------------------------------------------------------------------------------

    @Test
    void complex64FillBytes() {
        JsonValue fill = JsonArray.of(num(1.0), num(2.0)); // real=1.0f, imag=2.0f
        assertArrayEquals(bytes(0x00, 0x00, 0x80, 0x3f, 0x00, 0x00, 0x00, 0x40),
                DataType.COMPLEX64.decodeFillValue(fill, LITTLE_ENDIAN));
    }

    @Test
    void complexRejectsWrongArity() {
        assertThrows(ZarrFormatException.class,
                () -> DataType.COMPLEX64.decodeFillValue(JsonArray.of(num(1.0)), LITTLE_ENDIAN));
    }

    // ---- raw ------------------------------------------------------------------------------------

    @Test
    void rawFillFromArrayAndHexAreByteOrderIndependent() {
        DataType r24 = DataType.of("r24");
        assertArrayEquals(bytes(1, 2, 3),
                r24.decodeFillValue(JsonArray.of(num(1), num(2), num(3)), LITTLE_ENDIAN));
        assertArrayEquals(bytes(1, 2, 3),
                r24.decodeFillValue(JsonArray.of(num(1), num(2), num(3)), BIG_ENDIAN));
        assertArrayEquals(bytes(1, 2, 3), r24.decodeFillValue(str("0x010203"), LITTLE_ENDIAN));
    }

    @Test
    void rawRejectsWrongLengthAndBadByte() {
        DataType r16 = DataType.of("r16");
        assertThrows(ZarrFormatException.class,
                () -> r16.decodeFillValue(JsonArray.of(num(1)), LITTLE_ENDIAN));
        assertThrows(ZarrFormatException.class,
                () -> r16.decodeFillValue(JsonArray.of(num(1), num(256)), LITTLE_ENDIAN));
    }

    // ---- encode / round-trip --------------------------------------------------------------------

    private static void assertRoundTrips(DataType t, JsonValue fill) {
        for (ByteOrder order : new ByteOrder[] {LITTLE_ENDIAN, BIG_ENDIAN}) {
            byte[] b1 = t.decodeFillValue(fill, order);
            JsonValue reencoded = t.encodeFillValue(b1, order);
            byte[] b2 = t.decodeFillValue(reencoded, order);
            assertArrayEquals(b1, b2, t.name() + " " + order + " round-trip");
        }
    }

    @Test
    void roundTripsEveryKind() {
        assertRoundTrips(DataType.BOOL, JsonBool.TRUE);
        assertRoundTrips(DataType.INT8, num(-5));
        assertRoundTrips(DataType.INT64, num(-1234567890123L));
        assertRoundTrips(DataType.UINT32, num(4_000_000_000L));
        assertRoundTrips(DataType.UINT64, JsonNumber.of(new java.math.BigInteger("18446744073709551615")));
        assertRoundTrips(DataType.FLOAT16, num(1.5));
        assertRoundTrips(DataType.FLOAT32, num(3.5));
        assertRoundTrips(DataType.FLOAT32, str("NaN"));
        assertRoundTrips(DataType.FLOAT64, str("-Infinity"));
        assertRoundTrips(DataType.COMPLEX64, JsonArray.of(num(1.0), num(-2.0)));
        assertRoundTrips(DataType.COMPLEX128, JsonArray.of(num(1.0), str("Infinity")));
        assertRoundTrips(DataType.of("r24"), JsonArray.of(num(10), num(20), num(30)));
    }

    @Test
    void encodeProducesExpectedJson() {
        assertEquals(JsonBool.FALSE, DataType.BOOL.encodeFillValue(bytes(0), LITTLE_ENDIAN));
        assertEquals("-5", DataType.INT8.encodeFillValue(bytes(0xfb), LITTLE_ENDIAN).asNumber().literal());
        assertEquals("18446744073709551615", DataType.UINT64
                .encodeFillValue(bytes(0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), LITTLE_ENDIAN)
                .asNumber().literal());
        assertEquals("NaN", DataType.FLOAT32
                .encodeFillValue(bytes(0x00, 0x00, 0xc0, 0x7f), LITTLE_ENDIAN).asString());
        assertEquals("Infinity", DataType.FLOAT64
                .encodeFillValue(bytes(0, 0, 0, 0, 0, 0, 0xf0, 0x7f), LITTLE_ENDIAN).asString());
        JsonArray raw = (JsonArray) DataType.of("r16").encodeFillValue(bytes(0xde, 0xad), LITTLE_ENDIAN);
        assertEquals(0xde, raw.get(0).asNumber().intValue());
        assertEquals(0xad, raw.get(1).asNumber().intValue());
    }
}
