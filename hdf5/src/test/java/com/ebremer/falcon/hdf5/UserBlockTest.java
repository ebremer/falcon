package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Files that begin with a user block (MATLAB v7.3 {@code .mat} files carry a 512-byte one): the
 * superblock sits after it, and every file address is relative to the superblock's position.
 */
class UserBlockTest {

    @ParameterizedTest
    @ValueSource(strings = {"userblock_v0.h5", "userblock_v3.h5"})
    void readsFilesWithAUserBlock(String fixture) throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path(fixture))) {
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, h5.root().group("grp").dataset("data").readInts());
            double[] chunked = h5.root().dataset("chunked").readDoubles();
            assertEquals(40, chunked.length);
            for (int i = 0; i < chunked.length; i++) {
                assertEquals(i, chunked[i]);
            }
            assertArrayEquals(new String[] {"alpha", "beta"}, h5.root().dataset("strings").readStrings());
            assertEquals("user block", h5.root().attribute("note").orElseThrow().readString());
        }
    }

    /**
     * A user block prepended to an existing file without updating the stored base address: libhdf5 uses
     * the superblock's actual position as the base, and so does Falcon.
     */
    @Test
    void readsAFileWhoseUserBlockWasPrependedAfterTheFact() throws IOException {
        byte[] original = Files.readAllBytes(Fixtures.path("data_contiguous.h5"));
        byte[] shifted = new byte[original.length + 512];
        System.arraycopy(original, 0, shifted, 512, original.length);
        Path file = Files.createTempFile("falcon-userblock", ".h5");
        try {
            Files.write(file, shifted);
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(new int[] {0, 1, 2, 3, 4}, h5.root().dataset("c_i4").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
