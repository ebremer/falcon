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
    void nbitReducedPrecision() throws IOException {
        // 16-bit-precision unsigned stored in 4 bytes: n-bit packs only the significant bits.
        try (Hdf5File f = Hdf5File.open(Fixtures.path("nbit_data.h5"))) {
            assertArrayEquals(range(20), f.root().dataset("nbit_u").readInts());
        }
    }
}
