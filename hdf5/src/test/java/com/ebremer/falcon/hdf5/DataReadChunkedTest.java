package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Chunked (v1 B-tree index) reads, with the deflate/shuffle/fletcher32 filters, validated vs h5py. */
class DataReadChunkedTest {

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

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    @Test
    void uncompressedChunked1D() {
        // 10 elements in chunks of 3: the last chunk overhangs the extent and contributes one element.
        assertArrayEquals(range(10), ds("chunk_i4").readInts());
    }

    @Test
    void uncompressedChunked2D() {
        Dataset d = ds("chunk_2d");
        assertArrayEquals(new long[] {4, 6}, d.dataspace().dimensions());
        assertArrayEquals(range(24), d.readInts());
    }

    @Test
    void gzipCompressedInts() {
        assertArrayEquals(range(20), ds("gzip_i4").readInts());
    }

    @Test
    void gzipCompressedDoubles() {
        double[] expected = new double[12];
        for (int i = 0; i < 12; i++) {
            expected[i] = i * 0.25;
        }
        assertArrayEquals(expected, ds("gzip_f8").readDoubles());
    }

    @Test
    void shuffleThenDeflate() {
        assertArrayEquals(range(20), ds("shuffle_i4").readInts());
    }

    @Test
    void fletcher32Checksummed() {
        assertArrayEquals(range(20), ds("fletcher_i4").readInts());
    }

    @Test
    void scaleOffsetInts() {
        // integer scale-offset: min-subtracted, bit-packed, with fill-value markers.
        assertArrayEquals(range(20), ds("scaleoffset_i4").readInts());
    }

    @Test
    void singleChunkIndex() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(5), h5.root().dataset("single").readInts());
        }
    }

    @Test
    void fixedArrayIndexUnfiltered() throws IOException {
        // 10 elements in two chunks of 5, indexed by a fixed array (client id 0).
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(10), h5.root().dataset("implicit").readInts());
        }
    }

    @Test
    void fixedArrayIndexFiltered() throws IOException {
        // 20 elements in four gzip chunks, fixed array with filtered entries (client id 1).
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(20), h5.root().dataset("fixed").readInts());
        }
    }

    @Test
    void extensibleArrayInline() throws IOException {
        // 3 chunks: all element pointers live inline in the extensible-array index block.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(12), h5.root().dataset("extensible").readInts());
        }
    }

    @Test
    void extensibleArrayDataBlocks() throws IOException {
        // 300 chunks: pointers spill from the index block into data blocks and a secondary block.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(1200), h5.root().dataset("extensible_big").readInts());
        }
    }

    @Test
    void extensibleArrayFiltered() throws IOException {
        // gzip-filtered extensible array: elements carry address + stored size + filter mask.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(200), h5.root().dataset("extensible_gz").readInts());
        }
    }

    @Test
    void v2BTreeIndex() throws IOException {
        // 4x4 with both dims unlimited and 2x2 chunks -> v2 B-tree index (type 10 records).
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            int[] expected = range(16);
            int[] actual = h5.root().dataset("btree2").readInts();
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    void v2BTreeIndexFiltered() throws IOException {
        // 8x8, both dims unlimited, gzip -> v2 B-tree with filtered (type 11) records.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(64), h5.root().dataset("btree2_gz").readInts());
        }
    }

    @Test
    void v2BTreeIndexDeep() throws IOException {
        // 400 chunks push the v2 B-tree past a single leaf into internal (BTIN) nodes.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("chunk_indexes.h5"))) {
            assertArrayEquals(range(1600), h5.root().dataset("btree2_deep").readInts());
        }
    }

    @Test
    void nbitReducedPrecision() throws IOException {
        // 16-bit-precision unsigned stored in 4 bytes: n-bit packs only the significant bits.
        try (Hdf5File f = Hdf5File.open(Fixtures.path("nbit_data.h5"))) {
            assertArrayEquals(range(20), f.root().dataset("nbit_u").readInts());
        }
    }
}
