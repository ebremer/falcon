package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Virtual datasets (layout class 3): data assembled from selections of external source datasets. */
class VirtualDatasetTest {

    @Test
    void assemblesFromExternalSources() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds.h5"))) {
            Dataset vds = h5.root().dataset("vds");
            assertArrayEquals(new long[] {2, 4}, vds.dataspace().dimensions());
            // row 0 from vds_src0 (0..3), row 1 from vds_src1 (10..13).
            assertArrayEquals(new int[] {0, 1, 2, 3, 10, 11, 12, 13}, vds.readInts());
        }
    }

    @Test
    void unmappedRegionsKeepTheFillValue() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds.h5"))) {
            // rows 0 and 2 mapped to sources; row 1 is unmapped -> fill value -1.
            assertArrayEquals(new int[] {0, 1, 2, 3, -1, -1, -1, -1, 10, 11, 12, 13},
                    h5.root().dataset("vds_gap").readInts());
        }
    }

    @Test
    void assemblesColumnBlockMappings() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds.h5"))) {
            Dataset cols = h5.root().dataset("vds_cols");
            assertArrayEquals(new long[] {4, 2}, cols.dataspace().dimensions());
            // each source fills a (4,1) column block: col 0 <- src0, col 1 <- src1.
            assertArrayEquals(new int[] {0, 10, 1, 11, 2, 12, 3, 13}, cols.readInts());
        }
    }

    @Test
    void assemblesStridedMappings() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds.h5"))) {
            // source (0..3) lands on strided virtual indices 0,2,4,6; the rest keep fill value -1.
            assertArrayEquals(new int[] {0, -1, 1, -1, 2, -1, 3, -1},
                    h5.root().dataset("vds_step").readInts());
        }
    }
}
