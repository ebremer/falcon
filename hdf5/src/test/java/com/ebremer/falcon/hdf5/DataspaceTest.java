package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Dataspace decoding validated against h5py-generated fixtures. */
class DataspaceTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("datatypes.h5"));
    }

    @AfterAll
    static void close() {
        if (h5 != null) {
            h5.close();
        }
    }

    private static Dataspace sp(String name) {
        return h5.root().dataset(name).dataspace();
    }

    @Test
    void oneDimensional() {
        Dataspace s = sp("i4");
        assertEquals(Dataspace.Kind.SIMPLE, s.kind());
        assertEquals(1, s.rank());
        assertArrayEquals(new long[] {3}, s.dimensions());
        assertEquals(3, s.elementCount());
    }

    @Test
    void scalar() {
        Dataspace s = sp("scalar");
        assertEquals(Dataspace.Kind.SCALAR, s.kind());
        assertEquals(0, s.rank());
        assertEquals(1, s.elementCount());
    }

    @Test
    void twoDimensional() {
        Dataspace s = sp("twod");
        assertEquals(2, s.rank());
        assertArrayEquals(new long[] {2, 3}, s.dimensions());
        assertEquals(6, s.elementCount());
    }

    @Test
    void unlimitedMaxDimension() {
        Dataspace s = sp("unlimited");
        assertArrayEquals(new long[] {3}, s.dimensions());
        assertTrue(s.isUnlimited(0));
        assertEquals(Dataspace.UNLIMITED, s.maxDimensions()[0]);
    }
}
