package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Write path: Falcon writes a minimal file and reads it back identically. */
class WriteTest {

    @Test
    void roundTripIntDatasets() throws IOException {
        Path file = Files.createTempFile("falcon-write", ".h5");
        try {
            try (Hdf5Writer writer = Hdf5Writer.create(file)) {
                writer.intDataset("counts", new int[] {10, 20, 30, 40, 50}, new long[] {5})
                        .intAttribute("scale", new int[] {100}, new long[] {})   // scalar attribute
                        .intAttribute("range", new int[] {10, 50}, new long[] {2});
                writer.intDataset("grid", new int[] {0, 1, 2, 3, 4, 5}, new long[] {2, 3});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertEquals(List.of("counts", "grid"), h5.root().childNames());

                Dataset counts = h5.root().dataset("counts");
                assertArrayEquals(new long[] {5}, counts.dataspace().dimensions());
                assertArrayEquals(new int[] {10, 20, 30, 40, 50}, counts.readInts());
                assertArrayEquals(new int[] {100}, counts.attribute("scale").orElseThrow().readInts());
                assertArrayEquals(new int[] {10, 50}, counts.attribute("range").orElseThrow().readInts());

                Dataset grid = h5.root().dataset("grid");
                assertArrayEquals(new long[] {2, 3}, grid.dataspace().dimensions());
                assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, grid.readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
