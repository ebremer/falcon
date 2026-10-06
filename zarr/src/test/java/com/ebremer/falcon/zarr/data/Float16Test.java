package com.ebremer.falcon.zarr.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * {@link Float16#fromDouble} rounds a double to binary16 once (P0 Z4). On a value a {@code float} holds
 * exactly it must agree with the JDK's {@link Float#floatToFloat16}; off those values it must give the
 * half nearest the double itself, which rounding through a float can miss.
 */
class Float16Test {

    private static String hex(short h) {
        return String.format("0x%04x", h & 0xffff);
    }

    @Test
    void agreesWithTheJdkOnEveryFloatInASample() {
        SplittableRandom random = new SplittableRandom(16);
        for (int i = 0; i < 2_000_000; i++) {
            float f = Float.intBitsToFloat(random.nextInt());
            if (Float.isNaN(f)) {
                continue; // payload handling is checked below
            }
            assertEquals(hex(Float.floatToFloat16(f)), hex(Float16.fromDouble(f)), Float.toString(f));
        }
        // Every float near the boundaries: zero, subnormal/normal, the largest half, overflow.
        for (float base : new float[] {0f, 0x1p-24f, 0x1p-14f, 65504f, 65520f}) {
            int bits = Float.floatToRawIntBits(base);
            for (int d = -5000; d <= 5000; d++) {
                for (int sign : new int[] {0, 0x80000000}) {
                    float f = Float.intBitsToFloat((bits + d) | sign);
                    if (!Float.isNaN(f)) {
                        assertEquals(hex(Float.floatToFloat16(f)), hex(Float16.fromDouble(f)), Float.toString(f));
                    }
                }
            }
        }
    }

    @Test
    void roundsTheDoubleItselfNotAFloat() {
        assertEquals("0x3c01", hex(Float16.fromDouble(1 + 0x1p-11 + 0x1p-40))); // via float: 0x3c00
        assertEquals("0x3c00", hex(Float16.fromDouble(1 + 0x1p-11)));           // a true tie: to even
        assertEquals("0x3c02", hex(Float16.fromDouble(1 + 0x1p-10 + 0x1p-11))); // a tie, to even upward
        assertEquals("0x0001", hex(Float16.fromDouble(0x1p-25 + 0x1p-60)));    // just over half the least
        assertEquals("0x0000", hex(Float16.fromDouble(0x1p-25)));              // half the least: to even, 0
        assertEquals("0x7bff", hex(Float16.fromDouble(65519.999999)));
        assertEquals("0x7c00", hex(Float16.fromDouble(65520)));
        assertEquals("0xfc00", hex(Float16.fromDouble(Double.NEGATIVE_INFINITY)));
        assertEquals("0x8000", hex(Float16.fromDouble(-0.0)));
    }

    @Test
    void aNaNStaysAQuietNaN() {
        assertEquals("0x7e00", hex(Float16.fromDouble(Double.NaN)));
        assertEquals("0xfe00", hex(Float16.fromDouble(Double.longBitsToDouble(0xfff8000000000000L))));
        assertEquals("0x7e01", hex(Float16.fromDouble(Double.longBitsToDouble(0x7ff8040000000000L))));
        // A signalling NaN is quieted rather than turned into an infinity.
        assertEquals("0x7e00", hex(Float16.fromDouble(Double.longBitsToDouble(0x7ff0000000000001L))));
    }
}
