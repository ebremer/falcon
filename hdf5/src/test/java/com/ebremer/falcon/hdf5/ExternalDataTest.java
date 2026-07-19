package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * External File List (message 7): a contiguous dataset whose raw data lives in external raw files. The
 * fixture spreads {@code arange(12)} across two {@code .bin} files, the second starting 16 bytes in.
 */
class ExternalDataTest {

    @Test
    void readsContiguousDataFromExternalFiles() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("external.h5"))) {
            int[] expected = new int[12];
            for (int i = 0; i < 12; i++) {
                expected[i] = i;
            }
            assertArrayEquals(expected, h5.root().dataset("ext").readInts());
        }
    }
}
