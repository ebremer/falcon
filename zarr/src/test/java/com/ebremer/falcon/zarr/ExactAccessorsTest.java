package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exact unsigned and complex accessors (P2 F8). {@code readLongs} refuses {@code uint64}, whose values may
 * not fit a {@code long}, and no primitive reader took complex values at all; {@code readUnsignedLongs} /
 * {@code writeUnsignedLongs} and {@code readComplex} / {@code writeComplex} now cover both exactly.
 * zarr-python's own arrays ({@code gen_zarr_exact_fixtures.py}) read exactly.
 */
class ExactAccessorsTest {

    private static ZarrArray array(DataType type, int n) {
        return Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {n}, type).chunkShape(3).build());
    }

    @Test
    void uint64ValuesAbove2To63RoundTripExactly() {
        // 2^63 + 1025 and 2^64 - 1 have no double: readDoubles rounds them, readUnsignedLongs does not.
        long[] values = {0, 1, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1025, -1};
        ZarrArray a = array(DataType.UINT64, values.length);
        a.writeUnsignedLongs(values);
        assertArrayEquals(values, a.readUnsignedLongs());
        assertEquals("9223372036854776833", Long.toUnsignedString(a.readUnsignedLongs()[4]));
        assertEquals("18446744073709551615", Long.toUnsignedString(a.readUnsignedLongs()[5]));
        assertEquals(0x1p64, a.readDoubles()[5]); // the nearest double, as before
        assertArrayEquals(new long[] {Long.MAX_VALUE, Long.MIN_VALUE},
                a.select(new long[] {2}, new long[] {2}).readUnsignedLongs());

        ZarrException e = assertThrows(ZarrException.class, a::readLongs);
        assertTrue(e.getMessage().contains("readUnsignedLongs"), e.getMessage());
    }

    @Test
    void narrowerUnsignedTypesAreZeroExtendedAndRangeChecked() {
        ZarrArray u8 = array(DataType.UINT8, 3);
        u8.writeUnsignedLongs(new long[] {0, 200, 255});
        assertArrayEquals(new long[] {0, 200, 255}, u8.readUnsignedLongs());

        ZarrArray u32 = array(DataType.UINT32, 2);
        u32.writeUnsignedLongs(new long[] {0xffffffffL, 7});
        assertArrayEquals(new long[] {0xffffffffL, 7}, u32.readUnsignedLongs());

        // -1 is 2^64 - 1 taken unsigned: too large for uint8, and the message says so.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> u8.writeUnsignedLongs(new long[] {1, -1, 2}));
        assertTrue(e.getMessage().contains("18446744073709551615") && e.getMessage().contains("index 1"),
                e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> u8.writeUnsignedLongs(new long[] {256, 0, 0}));
        assertArrayEquals(new long[] {0, 200, 255}, u8.readUnsignedLongs()); // nothing was written
    }

    @Test
    void theUnsignedAccessorsRefuseOtherTypes() {
        ZarrArray i64 = array(DataType.INT64, 2);
        assertThrows(ZarrException.class, i64::readUnsignedLongs);
        assertThrows(ZarrException.class, () -> i64.writeUnsignedLongs(new long[] {1, 2}));
        assertThrows(ZarrException.class, array(DataType.FLOAT64, 2)::readUnsignedLongs);
        assertThrows(ZarrException.class, array(DataType.BOOL, 2)::readUnsignedLongs);
        assertThrows(IllegalArgumentException.class, () -> array(DataType.UINT16, 2).writeUnsignedLongs(new long[3]));
    }

    @Test
    void complex128RoundTripsInterleaved() {
        double[] values = {1.5, -2.25, 0.1, 0.2, Double.NaN, Double.POSITIVE_INFINITY, -0.0, Double.MIN_VALUE};
        ZarrArray a = array(DataType.COMPLEX128, 4);
        a.writeComplex(values);
        assertArrayEquals(values, a.readComplex());
        assertArrayEquals(new double[] {0.1, 0.2, Double.NaN, Double.POSITIVE_INFINITY},
                a.select(new long[] {1}, new long[] {2}).readComplex());
        // Stored as numpy stores it: the real part, then the imaginary, each a little-endian double.
        ByteBuffer raw = ByteBuffer.wrap(a.readRawBytes()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.5, raw.getDouble(0));
        assertEquals(-2.25, raw.getDouble(8));
    }

    @Test
    void complex64RoundsEachPartOnceAndWidensExactly() {
        ZarrArray a = array(DataType.COMPLEX64, 2);
        a.writeComplex(new double[] {0.1, 1e-50, 3.0, -0.5});
        assertArrayEquals(new double[] {(float) 0.1, 0.0, 3.0, -0.5}, a.readComplex());

        assertThrows(IllegalArgumentException.class, () -> a.writeComplex(new double[] {1, 2, 1e40, 0}));
        assertArrayEquals(new double[] {(float) 0.1, 0.0, 3.0, -0.5}, a.readComplex()); // nothing was written
    }

    @Test
    void bigEndianComplexArraysRoundTrip() {
        ZarrArray a = Zarr.createArray(new MemoryStore(), ArraySpec.builder(new long[] {2}, DataType.COMPLEX64)
                .endian(ByteOrder.BIG_ENDIAN).build());
        a.writeComplex(new double[] {1, 2, 3, 4});
        assertArrayEquals(new double[] {1, 2, 3, 4}, a.readComplex());
        assertEquals(1f, ByteBuffer.wrap(a.readRawBytes()).order(ByteOrder.BIG_ENDIAN).getFloat(0));
    }

    private static ZarrArray fixture(String name) {
        return Zarr.open(fixturePath(name)).asArray();
    }

    private static Path fixturePath(String name) {
        try {
            return Path.of(ExactAccessorsTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("missing fixture " + name + " (tools/fixtures/gen_zarr_exact_fixtures.py)", e);
        }
    }

    private static List<JsonValue> expected(String name) throws IOException {
        return Json.parse(Files.readAllBytes(fixturePath(name + ".expected.json"))).asObject().get("values")
                .asArray().values();
    }

    /** A part as Python's float.hex() writes it: Java parses the hex form; nan and inf are spelled out. */
    private static double part(JsonValue v) {
        return switch (v.asString()) {
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(v.asString());
        };
    }

    /** zarr-python's uint64 values up to 2^64 - 1 and complex arrays (gen_zarr_exact_fixtures.py). */
    @Test
    void zarrPythonsValuesReadExactly() throws IOException {
        long[] want = expected("uint64_full").stream().mapToLong(v -> Long.parseUnsignedLong(v.asString())).toArray();
        assertArrayEquals(want, fixture("uint64_full").readUnsignedLongs());

        for (String name : new String[] {"complex64_parts", "complex128_parts"}) {
            double[] parts = expected(name).stream().flatMap(pair -> pair.asArray().values().stream())
                    .mapToDouble(ExactAccessorsTest::part).toArray();
            assertArrayEquals(parts, fixture(name).readComplex(), name);
        }
        assertArrayEquals(new double[] {-0.5, 0.25, 0.1, 0.7},
                fixture("complex128_parts").select(new long[] {0, 1}, new long[] {1, 2}).readComplex());
    }

    @Test
    void complexValuesComeInPairsAndOnlyForComplexTypes() {
        ZarrArray a = array(DataType.COMPLEX128, 2);
        assertThrows(IllegalArgumentException.class, () -> a.writeComplex(new double[3]));
        assertThrows(IllegalArgumentException.class, () -> a.writeComplex(new double[6]));
        assertThrows(ZarrException.class, () -> array(DataType.FLOAT64, 2).writeComplex(new double[4]));
        assertThrows(ZarrException.class, array(DataType.FLOAT64, 2)::readComplex);

        ZarrException e = assertThrows(ZarrException.class, a::readDoubles);
        assertTrue(e.getMessage().contains("readComplex"), e.getMessage());
        e = assertThrows(ZarrException.class, () -> a.writeDoubles(new double[2]));
        assertTrue(e.getMessage().contains("writeComplex"), e.getMessage());
    }
}
