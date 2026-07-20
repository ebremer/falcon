package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Convenience scalar reads and block streaming (H9 API ergonomics). */
class StreamingApiTest {

    @Test
    void scalarConvenienceReads() throws IOException {
        Path file = Files.createTempFile("falcon-scalar", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intDataset("i", new int[] {42}, new long[] {1});
                w.doubleDataset("d", new double[] {3.5}, new long[] {}); // scalar (rank 0)
                w.intDataset("data", new int[] {1, 2, 3}, new long[] {3})
                        .intAttribute("scale", new int[] {100}, new long[] {});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertEquals(42, h5.root().dataset("i").readInt());
                assertEquals(3.5, h5.root().dataset("d").readDouble());
                assertEquals(100, h5.root().dataset("data").attribute("scale").orElseThrow().readInt());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void blockStreamingReconstructsChunkedDataset() throws IOException {
        Path file = Files.createTempFile("falcon-blocks", ".h5");
        try {
            int[] data = new int[100];
            for (int i = 0; i < 100; i++) {
                data[i] = i;
            }
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("big", data, new long[] {100}, new long[] {16});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset ds = h5.root().dataset("big");
                int[] collected = new int[100];
                int[] pos = {0};
                ds.blocks(30).forEach(block -> {
                    int[] values = block.readInts();
                    System.arraycopy(values, 0, collected, pos[0], values.length);
                    pos[0] += values.length;
                });
                assertEquals(100, pos[0]);
                assertArrayEquals(data, collected);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void streamingFilteredDatasetReusesDecodedChunks() throws IOException {
        Path file = Files.createTempFile("falcon-cache", ".h5");
        try {
            int[] data = new int[100];
            for (int i = 0; i < 100; i++) {
                data[i] = i * 7;
            }
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.intChunkedDataset("z", data, new long[] {100}, new long[] {16}).deflate(4);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset ds = h5.root().dataset("z");
                // Blocks of 30 over 16-element chunks share boundary chunks between adjacent blocks, so
                // the decoded-chunk cache is exercised; correctness must be unaffected.
                int[] collected = new int[100];
                int[] pos = {0};
                ds.blocks(30).forEach(block -> {
                    int[] values = block.readInts();
                    System.arraycopy(values, 0, collected, pos[0], values.length);
                    pos[0] += values.length;
                });
                assertArrayEquals(data, collected);
                assertArrayEquals(data, ds.readInts()); // a second full read hits the cache
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
