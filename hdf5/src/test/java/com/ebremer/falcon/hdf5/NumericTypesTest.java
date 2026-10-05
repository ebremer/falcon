package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integer and floating-point layouts beyond the native ones ({@code numeric.h5}): integers with a bit
 * offset and reduced precision, bfloat16, x87 extended precision, and unsigned values outside Java's
 * signed ranges. The float fixtures carry libhdf5's own conversion to float64 as an {@code expected}
 * attribute.
 */
class NumericTypesTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("numeric.h5"));
    }

    @AfterAll
    static void close() {
        h5.close();
    }

    private static Dataset ds(String name) {
        return h5.root().dataset(name);
    }

    private static double[] expected(String name) {
        return ds(name).attribute("expected").orElseThrow().readDoubles();
    }

    @Test
    void integersHonourBitOffsetAndPrecision() {
        assertArrayEquals(new int[] {-5, 100, 2047, -2048}, ds("i12_off4").readInts());
        assertArrayEquals(new int[] {0, 4095, 1234, 7}, ds("u12be_off3").readInts());
        assertArrayEquals(new int[] {-8388608, 8388607, -1, 12345}, ds("i24be").readInts());
        assertArrayEquals(new long[] {-(1L << 39), (1L << 39) - 1, -1, 3}, ds("i40_off20").readLongs());
        // Each agrees with libhdf5's own conversion.
        for (String name : new String[] {"i12_off4", "u12be_off3", "i24be", "i40_off20"}) {
            long[] values = ds(name).readLongs();
            double[] oracle = expected(name);
            for (int i = 0; i < values.length; i++) {
                assertEquals(oracle[i], values[i], name + "[" + i + "]");
            }
        }
    }

    @Test
    void reducedPrecisionReadsAsTheSmallestFittingArray() {
        assertInstanceOf(int[].class, ds("i12_off4").read());
        assertInstanceOf(int[].class, ds("u12be_off3").read());
        assertInstanceOf(long[].class, ds("i40_off20").read());
    }

    @Test
    void floatsAreDecodedFromTheirFields() {
        for (String name : new String[] {"bf16", "f32_in6", "x87"}) {
            assertArrayEquals(expected(name), ds(name).readDoubles(), 0.0, name);
        }
        assertArrayEquals(new double[] {1.5, -2.0, 3.140625, 9.183549615799121e-41}, ds("bf16").readDoubles(), 0.0);
        assertArrayEquals(new double[] {1.5, -2.0e-300, 3.25e300, Double.NEGATIVE_INFINITY}, ds("x87").readDoubles(), 0.0);
        assertArrayEquals(new float[] {1.5f, -2f, 3.25f, Float.POSITIVE_INFINITY}, ds("f32_in6").readFloats(), 0f);
        var x87 = (Datatype.FloatingPoint) ds("x87").datatype();
        assertEquals(Datatype.MantissaNormalization.NONE, x87.normalization());
        assertEquals(79, x87.signLocation());
    }

    @Test
    void uint32ReadsAsLongAndNeverWraps() {
        assertInstanceOf(long[].class, ds("u32").read());
        assertArrayEquals(new long[] {0, 4_000_000_000L, 1L << 31, 7}, ds("u32").readLongs());
        HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class, () -> ds("u32").readInts());
        assertTrue(e.getMessage().contains("4000000000"), e.getMessage());
        // A uint32 whose values happen to fit still reads as int[] on request.
        assertArrayEquals(new int[] {0, 1, Integer.MAX_VALUE, 7}, ds("u32_small").readInts());
        assertInstanceOf(long[].class, ds("u32_small").read());
    }

    @Test
    void uint64ReadsAsBigIntegerAndNeverWraps() {
        BigInteger[] values = (BigInteger[]) ds("u64").read();
        assertArrayEquals(new BigInteger[] {
            BigInteger.ZERO, BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), BigInteger.ONE.shiftLeft(63),
            BigInteger.valueOf(7)}, values);
        assertThrows(HdfUnsupportedException.class, () -> ds("u64").readLongs());
        assertArrayEquals(new long[] {0, 5, 1L << 62, 7}, ds("u64_small").readLongs());
    }

    @Test
    void int64NarrowsToIntOnlyWhenEveryValueFits() {
        assertArrayEquals(new int[] {0, -5, Integer.MAX_VALUE, Integer.MIN_VALUE}, ds("i64_small").readInts());
        assertThrows(HdfUnsupportedException.class, () -> ds("i64_big").readInts());
        assertInstanceOf(long[].class, ds("i64_small").read());
    }

    @Test
    void unsignedAttributes() {
        Attribute u32 = h5.root().attribute("u32_attr").orElseThrow();
        assertArrayEquals(new long[] {4_000_000_000L, 1}, (long[]) u32.read());
        assertThrows(HdfUnsupportedException.class, u32::readInts);
        assertArrayEquals(new int[] {65535}, (int[]) h5.root().attribute("u16_attr").orElseThrow().read());
    }

    @Test
    void selectionsUseTheSameDecoding() {
        assertArrayEquals(new int[] {100, 2047}, ds("i12_off4").select(new long[] {1}, new long[] {2}).readInts());
        assertArrayEquals(new double[] {-2.0e-300}, ds("x87").select(new long[] {1}, new long[] {1}).readDoubles(), 0.0);
    }
}
