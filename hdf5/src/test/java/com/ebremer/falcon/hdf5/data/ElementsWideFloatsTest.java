package com.ebremer.falcon.hdf5.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.datatype.Datatype.MantissaNormalization;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Floating-point types whose mantissa is wider than 64 bits: 16-byte long doubles. The HDF5 conformance harness
 * found Falcon refusing the HDF5 library's tcomplex.h5, whose long double complex numbers h5py reads on Linux.
 */
class ElementsWideFloatsTest {

    /** A 16-byte type with binary128's fields: sign 127, a 15-bit exponent at 112, a 112-bit mantissa at 0. */
    private static Datatype.FloatingPoint quad(MantissaNormalization normalization) {
        return new Datatype.FloatingPoint(16, ByteOrder.LITTLE_ENDIAN, 0, 128, 112, 15, 0, 112, 16383, 127,
                normalization, false);
    }

    /** Little-endian 16-byte elements from their values as unsigned 128-bit integers. */
    private static MemorySegment elements(BigInteger... values) {
        byte[] bytes = new byte[16 * values.length];
        for (int i = 0; i < values.length; i++) {
            byte[] be = values[i].toByteArray();
            for (int j = 0; j < Math.min(16, be.length); j++) {
                bytes[16 * i + j] = be[be.length - 1 - j];
            }
        }
        return MemorySegment.ofArray(bytes);
    }

    private static BigInteger bits(int sign, int exponent, BigInteger mantissa) {
        return BigInteger.valueOf(sign).shiftLeft(127).or(BigInteger.valueOf(exponent).shiftLeft(112)).or(mantissa);
    }

    /**
     * tcomplex.h5's DatasetLongDoubleComplex: x87 80-bit values in 16 bytes, which libhdf5 describes as binary128's
     * fields with no implied bit, the explicit leading bit at the top of the 112-bit mantissa.
     */
    @Test
    void readsLongDoublesWithAnExplicitLeadingBit() {
        BigInteger lead = BigInteger.ONE.shiftLeft(111);
        MemorySegment data = elements(
                bits(0, 16386, lead.or(BigInteger.ONE.shiftLeft(109))),   // 1.01b x 2^3 = 10
                bits(0, 16383, lead),                                     // 1
                bits(1, 16384, lead.or(BigInteger.ONE.shiftLeft(110))),   // -1.1b x 2^1 = -3
                BigInteger.ZERO);
        assertArrayEquals(new double[] {10, 1, -3, 0}, Elements.toDoubles(data, 4, quad(MantissaNormalization.NONE)));
    }

    /** IEEE binary128 (an implied leading bit), rounded to the nearest double once, ties to even. */
    @Test
    void readsBinary128RoundedOnceToTheNearestDouble() {
        BigInteger third = BigInteger.ONE.shiftLeft(112).divide(BigInteger.valueOf(3));   // .0101...b, 112 bits
        MemorySegment data = elements(
                bits(0, 16383 - 2, third),                                                // 1/3 = 1.0101...b x 2^-2
                bits(0, 16383, BigInteger.ONE.shiftLeft(112 - 53)),                       // 1 + 2^-53: a tie, to 1
                bits(0, 16383, BigInteger.ONE.shiftLeft(112 - 53).or(BigInteger.ONE)),   // just above it: 1 + 2^-52
                bits(1, 16383 + 10, BigInteger.ZERO),                                     // -1024
                bits(0, 16383 - 1, BigInteger.ONE.shiftLeft(111)));                       // 0.75
        assertArrayEquals(new double[] {1.0 / 3, 1, 1 + Math.ulp(1.0), -1024, 0.75},
                Elements.toDoubles(data, 5, quad(MantissaNormalization.IMPLIED)));
    }
}
