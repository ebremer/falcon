package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Hyperslab (partial) reads, validated against full-read subsets of the chunked fixtures. */
class HyperslabTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("chunked_data.h5"));
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
    void twoDimensionalSubRegion() {
        // chunk_2d holds 0..23 as 4x6; rows 1-2, cols 2-4 -> [8,9,10,14,15,16].
        int[] slab = ds("chunk_2d").select(new long[] {1, 2}, new long[] {2, 3}).readInts();
        assertArrayEquals(new int[] {8, 9, 10, 14, 15, 16}, slab);
    }

    @Test
    void oneDimensionalSlabMatchesFullSubset() {
        Dataset d = ds("gzip_i4");
        int[] full = d.readInts();
        int[] slab = d.select(new long[] {3}, new long[] {5}).readInts();
        assertArrayEquals(Arrays.copyOfRange(full, 3, 8), slab);
    }

    @Test
    void fullSelectionEqualsFullRead() {
        Dataset d = ds("chunk_2d");
        assertArrayEquals(d.readInts(), d.select(new long[] {0, 0}, new long[] {4, 6}).readInts());
    }

    @Test
    void outOfBoundsSelectionThrows() {
        Dataset d = ds("chunk_2d");
        assertThrows(IllegalArgumentException.class,
                () -> d.select(new long[] {3, 4}, new long[] {2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> d.select(new long[] {0}, new long[] {4})); // wrong rank
    }
}
