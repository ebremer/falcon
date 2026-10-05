package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Scale-offset and szip datasets in the exact on-disk form libhdf5 writes, each compared with the
 * reference library's own decoding (stored by the fixture generator as the dataset's
 * {@code expected} attribute).
 *
 * <ul>
 *   <li>{@code scaleoffset.h5} &mdash; h5py; integer cases with power-of-two chunks, fill values,
 *       big-endian data and full-precision chunks, plus lossy decimal-scaled floats (whose expected
 *       values are libhdf5's decoding, read back after reopening the file).</li>
 *   <li>{@code szip.h5} &mdash; chunks from libaec's SZ layer, byte-identical to libhdf5 + libaec
 *       output (h5py ships szip disabled): EC and NN coding, padded scanlines, interleaved 32/64-bit
 *       pixels, both byte orders, multiple chunks with a partial edge.</li>
 * </ul>
 */
class FilterConformanceTest {

    @Test
    void scaleOffsetMatchesLibhdf5() throws IOException {
        assertTrue(compareAll("scaleoffset.h5") >= 13);
    }

    @Test
    void szipMatchesLibhdf5() throws IOException {
        assertTrue(compareAll("szip.h5") >= 9);
    }

    /** Reads every dataset and checks it against its {@code expected} attribute; returns the count. */
    private static int compareAll(String fixture) throws IOException {
        int count = 0;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            for (Hdf5Object child : h5.root().children()) {
                Dataset d = (Dataset) child;
                Object actual = d.read();
                Object expected = d.attribute("expected").orElseThrow().read();
                assertTrue(Objects.deepEquals(expected, actual), () -> d.path() + " differs from libhdf5");
                assertEquals(expected.getClass(), actual.getClass());
                count++;
            }
        }
        return count;
    }
}
