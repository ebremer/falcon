package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The SZ filter (32017), from sz.h5, made by h5py and hdf5plugin's H5Z-SZ (SZ 2.1.12): SZ is lossy, so
 * every dataset must read as libSZ itself decodes it, stored under /expected.
 *
 * <p>Bit for bit, but for doubles under a point-wise relative bound: those decoders exponentiate
 * ({@code pow}, {@code exp2}) in double, where the C library's last bit and Java's can differ, and a
 * prediction carries such a difference on. There each value must be within 128 units in the last place.
 */
class SzFilterTest {

    @Test
    void everyDatasetReadsAsLibSzDecodesIt() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("sz.h5"))) {
            Group root = h5.root();
            Group expected = root.group("expected");
            List<String> names = root.childNames().stream().filter(n -> !n.equals("expected")).sorted().toList();
            assertEquals(72, names.size());
            int exact = 0;
            for (String name : names) {
                byte[] want = expected.dataset(name).readRawBytes();
                byte[] got = root.dataset(name).readRawBytes();
                if (name.startsWith("f8_") && name.contains("pwr")) {
                    assertWithinUlps(name, want, got, 128);
                } else {
                    assertArrayEquals(want, got, name);
                }
                exact += Arrays.equals(want, got) ? 1 : 0;
            }
            assertTrue(exact >= names.size() - 8, exact + " of " + names.size() + " bit for bit");
        }
    }

    @Test
    void partialReadsDecodeOnlyTheChunksTheyTouch() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("sz.h5"))) {
            Dataset dataset = h5.root().dataset("f4_2d_abs");
            float[] all = h5.root().group("expected").dataset("f4_2d_abs").readFloats();
            float[] part = dataset.select(new long[] {18, 14}, new long[] {4, 5}).readFloats();
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 5; j++) {
                    assertEquals(Float.floatToRawIntBits(all[(18 + i) * 33 + 14 + j]),
                            Float.floatToRawIntBits(part[i * 5 + j]), "(" + i + ", " + j + ")");
                }
            }
            // Chunks of exactly 20 values are stored as they are, which libSZ cannot read back; Falcon can.
            assertArrayEquals(h5.root().group("expected").dataset("f4_twenty").readFloats(),
                    h5.root().dataset("f4_twenty").readFloats());
        }
    }

    private static void assertWithinUlps(String name, byte[] want, byte[] got, long ulps) {
        assertEquals(want.length, got.length, name);
        var w = ByteBuffer.wrap(want).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
        var g = ByteBuffer.wrap(got).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
        for (int i = 0; i < w.capacity(); i++) {
            long a = w.get(i);
            long b = g.get(i);
            assertTrue(a == b || (a ^ b) >= 0 && Math.abs(a - b) <= ulps,
                    name + " value " + i + ": " + Double.longBitsToDouble(a) + " vs " + Double.longBitsToDouble(b));
        }
    }
}
