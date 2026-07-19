package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Contiguous/compact reads validated byte-for-byte against h5py-written values. */
class DataReadTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("data_contiguous.h5"));
    }

    @AfterAll
    static void close() {
        if (h5 != null) {
            h5.close();
        }
    }

    private static Dataset ds(String name) {
        return h5.root().dataset(name);
    }

    @Test
    void contiguousLittleEndianInts() {
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, ds("c_i4").readInts());
    }

    @Test
    void contiguousBigEndianInts() {
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, ds("c_be_i4").readInts());
    }

    @Test
    void unsignedByteZeroExtends() {
        assertArrayEquals(new int[] {1, 2, 255}, ds("c_u1").readInts());
    }

    @Test
    void contiguousDoubles() {
        assertArrayEquals(new double[] {1.5, 2.5, 3.5, -4.25}, ds("c_f8").readDoubles());
    }

    @Test
    void contiguousFloats() {
        assertArrayEquals(new float[] {0.5f, 1.5f, 2.5f}, ds("c_f4").readFloats());
        // A float32 dataset can also be widened to double losslessly.
        assertArrayEquals(new double[] {0.5, 1.5, 2.5}, ds("c_f4").readDoubles());
    }

    @Test
    void twoDimensionalFlattensRowMajor() {
        Dataset d = ds("c_2d");
        assertArrayEquals(new long[] {2, 3}, d.dataspace().dimensions());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, d.readInts());
    }

    @Test
    void fixedLengthStrings() {
        assertArrayEquals(new String[] {"abc", "de", "fghij", ""}, ds("c_str").readStrings());
    }

    @Test
    void compactStorage() {
        assertArrayEquals(new int[] {10, 20, 30}, ds("compact_i4").readInts());
    }

    @Test
    void unallocatedDatasetReadsFillValue() {
        assertArrayEquals(new int[] {7, 7, 7, 7}, ds("unwritten").readInts());
    }

    @Test
    void variableLengthStrings() throws IOException {
        try (Hdf5File f = Hdf5File.open(Fixtures.path("vlen_data.h5"))) {
            assertArrayEquals(new String[] {"alpha", "beta", "gamma", "delta"},
                    f.root().dataset("vstr").readStrings());
        }
    }

    @Test
    void genericReadPicksNaturalType() {
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, (int[]) ds("c_i4").read());
        assertInstanceOf(double[].class, ds("c_f8").read());
        assertInstanceOf(String[].class, ds("c_str").read());
        assertEquals(20, ds("c_i4").readRawBytes().length);
    }
}
