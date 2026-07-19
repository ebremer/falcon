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

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    @Test
    void roundTripChunkedDatasets() throws IOException {
        Path file = Files.createTempFile("falcon-chunked", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("c", range(10), new long[] {10}, new long[] {4}); // 3 chunks, last partial
                w.intChunkedDataset("grid", range(24), new long[] {4, 6}, new long[] {2, 3}); // 2x2 chunks
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(range(10), h5.root().dataset("c").readInts());
                assertArrayEquals(range(24), h5.root().dataset("grid").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripDeflateChunkedDataset() throws IOException {
        Path file = Files.createTempFile("falcon-deflate", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("z", range(100), new long[] {100}, new long[] {16}).deflate(6);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(range(100), h5.root().dataset("z").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripFilteredChunkedDatasets() throws IOException {
        Path file = Files.createTempFile("falcon-filters", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("shuf", range(50), new long[] {50}, new long[] {8}).shuffle();
                w.intChunkedDataset("flet", range(50), new long[] {50}, new long[] {8}).fletcher32();
                w.intChunkedDataset("all", range(50), new long[] {50}, new long[] {8})
                        .shuffle().deflate(4).fletcher32();
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(range(50), h5.root().dataset("shuf").readInts());
                assertArrayEquals(range(50), h5.root().dataset("flet").readInts());
                assertArrayEquals(range(50), h5.root().dataset("all").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripScaleOffsetDataset() throws IOException {
        Path file = Files.createTempFile("falcon-so", ".h5");
        try {
            int[] data = new int[16];
            for (int i = 0; i < 16; i++) {
                data[i] = 100 + i;
            }
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("so", data, new long[] {16}, new long[] {8}).scaleOffset();
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(data, h5.root().dataset("so").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripNbitDataset() throws IOException {
        Path file = Files.createTempFile("falcon-nbit", ".h5");
        try {
            int[] data = new int[16];
            for (int i = 0; i < 16; i++) {
                data[i] = 100 + i;
            }
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("nb", data, new long[] {16}, new long[] {8}).nbit(16);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(data, h5.root().dataset("nb").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripSubgroupsAndStrings() throws IOException {
        Path file = Files.createTempFile("falcon-tree", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intDataset("top", new int[] {1, 2}, new long[] {2});
                Hdf5Writer.GroupWriter run = w.group("run");
                run.doubleDataset("signal", new double[] {0.5, 1.5, 2.5}, new long[] {3});
                run.stringDataset("labels", new String[] {"alpha", "beta", "gamma"}, new long[] {3});
                run.intAttribute("count", new int[] {3}, new long[] {});
                run.group("nested").intDataset("inner", new int[] {7, 8, 9}, new long[] {3});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(new int[] {1, 2}, h5.root().dataset("top").readInts());
                Group run = h5.root().group("run");
                assertArrayEquals(new double[] {0.5, 1.5, 2.5}, run.dataset("signal").readDoubles());
                assertArrayEquals(new String[] {"alpha", "beta", "gamma"}, run.dataset("labels").readStrings());
                assertArrayEquals(new int[] {3}, run.attribute("count").orElseThrow().readInts());
                assertArrayEquals(new int[] {7, 8, 9}, run.group("nested").dataset("inner").readInts());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void roundTripDoubleDatasetAndAttribute() throws IOException {
        Path file = Files.createTempFile("falcon-write-f8", ".h5");
        try {
            try (Hdf5Writer writer = Hdf5Writer.create(file)) {
                writer.doubleDataset("values", new double[] {1.5, -2.25, 3.0}, new long[] {3})
                        .doubleAttribute("offset", new double[] {0.125}, new long[] {});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset values = h5.root().dataset("values");
                assertArrayEquals(new double[] {1.5, -2.25, 3.0}, values.readDoubles());
                assertArrayEquals(new double[] {0.125}, values.attribute("offset").orElseThrow().readDoubles());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
