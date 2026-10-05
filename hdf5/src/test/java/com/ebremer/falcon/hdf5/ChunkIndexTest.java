package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Chunk-index conformance against files written by libhdf5 (h5py 3.16 / HDF5 2.0, with library-version
 * bounds selecting older encodings where noted). Each fixture reproduces a file shape whose chunks
 * Falcon once located wrongly; every dataset holds a row-major arange unless stated otherwise.
 */
class ChunkIndexTest {

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    private static int[] read(String fixture, String dataset) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            return h5.root().dataset(dataset).readInts();
        }
    }

    /**
     * Fixed arrays, the implicit index, and extensible arrays number chunks over the maximum chunk
     * grid; extensible arrays also move their unlimited dimension to the slowest position.
     */
    @ParameterizedTest
    @ValueSource(strings = {"fa_wider_max", "fa_max_3d", "fa_wider_max_gz", "implicit_wider_max",
        "ea_unlim_last", "ea_unlim_mid", "ea_unlim_first", "ea_unlim_last_gz", "ea_unlim_last_wide",
        "bt2_two_unlim"})
    void locatesChunksOverTheMaximumGrid(String name) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_maxshape.h5"))) {
            Dataset d = h5.root().dataset(name);
            int n = (int) d.dataspace().elementCount();
            assertArrayEquals(range(n), d.readInts(), name);
        }
    }

    @Test
    void hyperslabOfASwizzledExtensibleArray() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_maxshape.h5"))) {
            // ea_unlim_last is arange(24).reshape(3, 8) with its unlimited dimension last
            int[] window = h5.root().dataset("ea_unlim_last").select(new long[] {1, 2}, new long[] {2, 5}).readInts();
            assertArrayEquals(new int[] {10, 11, 12, 13, 14, 18, 19, 20, 21, 22}, window);
        }
    }

    /**
     * HDF5 1.10&ndash;1.14 (layout message version 4) sizes a filtered entry's chunk-size field to the
     * chunk (1 + bytes for its unfiltered size), not "size of lengths".
     */
    @Test
    void readsLayoutVersion4FilteredIndexes() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("layout_v4.h5"))) {
            assertArrayEquals(range(4000), h5.root().dataset("fa_gzip").readInts());
            assertArrayEquals(range(300), h5.root().dataset("ea_gzip").readInts());
            assertArrayEquals(range(64), h5.root().dataset("bt2_gzip").readInts());
            assertArrayEquals(range(500), h5.root().dataset("single_gzip").readInts());
            assertArrayEquals(range(50), h5.root().dataset("fa_plain").readInts());
            assertArrayEquals(range(30), h5.root().dataset("fa_fletcher").readInts());
            double[] big = h5.root().dataset("fa_big_chunk").readDoubles();
            assertEquals(60000, big.length);
            for (int i = 0; i < big.length; i++) {
                assertEquals(i, big[i]);
            }
        }
    }

    /** Paged fixed-array data blocks (more than 1024 entries), fully written and filtered. */
    @Test
    void readsPagedFixedArrays() throws IOException {
        assertArrayEquals(range(5000), read("paged_sparse.h5", "fa_paged"));
        assertArrayEquals(range(5000), read("paged_sparse.h5", "fa_paged_gz"));
    }

    /** Never-written pages have a clear page-init bit: their chunks are unallocated (fill value -7). */
    @Test
    void honoursPageInitBitmaps() throws IOException {
        int[] fixed = read("paged_sparse.h5", "fa_paged_sparse");
        int[] expectedFixed = new int[5000];
        Arrays.fill(expectedFixed, -7);
        for (int i = 0; i < 10; i++) {
            expectedFixed[i] = i;
            expectedFixed[4990 + i] = 4990 + i;
        }
        assertArrayEquals(expectedFixed, fixed);

        int[] extensible = read("paged_sparse.h5", "ea_sparse");
        int[] expectedExtensible = new int[300000];
        Arrays.fill(expectedExtensible, -7);
        for (int i : new int[] {0, 5000, 123456, 299999}) {
            expectedExtensible[i] = i;
        }
        for (int i = 2000; i < 2010; i++) {
            expectedExtensible[i] = i;
        }
        assertArrayEquals(expectedExtensible, extensible);
    }

    /** A filtered single-chunk index records the chunk's filtered size and filter mask in the layout. */
    @Test
    void readsFilteredSingleChunks() throws IOException {
        assertArrayEquals(range(1000), read("filtered_single.h5", "single_gzip"));
        assertArrayEquals(range(100), read("filtered_single.h5", "single_fletcher"));
        assertArrayEquals(range(16), read("filtered_single.h5", "single_masked")); // gzip skipped by its mask
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("filtered_single.h5"))) {
            double[] values = h5.root().dataset("single_shuffle_gzip").readDoubles();
            for (int i = 0; i < 64; i++) {
                assertEquals(i, values[i]);
            }
        }
    }

    /** Chunked datasets created but never written have no index: every element reads as the fill value. */
    @Test
    void unwrittenChunkedDatasetsReadAsFill() throws IOException {
        int[] sevens = new int[10];
        Arrays.fill(sevens, 7);
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("unwritten_earliest.h5"))) {
            assertArrayEquals(sevens, h5.root().dataset("chunked").readInts());
            double[] fill = new double[16];
            Arrays.fill(fill, 1.5);
            assertArrayEquals(fill, h5.root().dataset("chunked_gz").readDoubles());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("unwritten_latest.h5"))) {
            for (String name : new String[] {"single", "fixed", "fixed_gz", "extensible"}) {
                assertArrayEquals(sevens, h5.root().dataset(name).readInts(), name);
            }
            int[] grid = new int[16];
            Arrays.fill(grid, 7);
            assertArrayEquals(grid, h5.root().dataset("btree_v2").readInts());
        }
    }

    /** Unwritten variable-length strings hold a null heap id and read back empty, as in libhdf5. */
    @Test
    void unwrittenVariableLengthStringsReadEmpty() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("unwritten_earliest.h5"))) {
            assertArrayEquals(new String[] {"only", "", ""}, h5.root().dataset("vlen").readStrings());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("unwritten_latest.h5"))) {
            assertArrayEquals(new String[] {"", "one", "", "", "four", ""},
                    h5.root().dataset("vlen_chunked").readStrings());
        }
    }

    /** {@code H5Pset_chunk_opts(DONT_FILTER_PARTIAL_CHUNKS)}: the partial edge chunk is stored raw. */
    @Test
    void readsUnfilteredPartialEdgeChunks() throws IOException {
        assertArrayEquals(range(10), read("filter_edge.h5", "dont_filter_partial"));
    }

    /** An n-bit filter over a full-precision type stores chunks untouched ("no compression needed"). */
    @Test
    void readsFullPrecisionNbit() throws IOException {
        int[] expected = new int[12];
        for (int i = 0; i < 12; i++) {
            expected[i] = i * 1000 - 3000;
        }
        assertArrayEquals(expected, read("filter_edge.h5", "nbit_full_le"));
        assertArrayEquals(expected, read("filter_edge.h5", "nbit_full_be"));
    }
}
