package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An open {@link Hdf5File} may be read from many threads at once: the mapped bytes are read without a
 * cursor, the decoded-chunk cache is synchronized, and every lazily parsed object field is safely
 * published. These tests share one file (and some shared object handles) between threads doing random
 * reads of filtered, chunked data and check every value.
 */
@Timeout(120)
class ConcurrencyTest {

    private static final int THREADS = 16;

    /**
     * Random hyperslab reads of a gzip-chunked dataset whose decoded chunks (32 MB) overflow the 16 MB
     * chunk cache, so threads constantly insert, look up, and evict entries concurrently.
     */
    @Test
    void concurrentHyperslabReadsOfFilteredChunks() throws Exception {
        int n = 1 << 22; // 4M doubles in 128 chunks of 256 KB
        double[] data = new double[n];
        for (int i = 0; i < n; i++) {
            data[i] = i * 0.5;
        }
        Path file = Files.createTempFile("falcon-concurrency", ".h5");
        try {
            try (Hdf5Writer w = Hdf5Writer.create(file)) {
                w.doubleChunkedDataset("d", data, new long[] {n}, new long[] {1 << 15}).deflate(1);
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                Dataset shared = h5.root().dataset("d");
                ExecutorService pool = Executors.newFixedThreadPool(THREADS);
                try {
                    List<Future<?>> results = new ArrayList<>();
                    for (int t = 0; t < THREADS; t++) {
                        long seed = t;
                        results.add(pool.submit(() -> {
                            Random random = new Random(seed);
                            for (int i = 0; i < 40; i++) {
                                // alternate a shared handle with a fresh one (lazy fields per instance)
                                Dataset d = (i % 2 == 0) ? shared : h5.root().dataset("d");
                                int start = random.nextInt(n - 100_000);
                                int count = 1 + random.nextInt(100_000);
                                double[] values = d.select(new long[] {start}, new long[] {count}).readDoubles();
                                for (int k = 0; k < count; k += 997) {
                                    assertEquals((start + k) * 0.5, values[k]);
                                }
                            }
                            return null;
                        }));
                    }
                    for (Future<?> result : results) {
                        result.get(); // rethrows any assertion or exception from the worker
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void parallelBlockStream() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("layout_v4.h5"))) {
            Dataset d = h5.root().dataset("fa_big_chunk"); // 60000 doubles in gzip+shuffle chunks
            double sum = d.blocks(997).parallel()
                    .mapToDouble(block -> {
                        double s = 0;
                        for (double v : block.readDoubles()) {
                            s += v;
                        }
                        return s;
                    })
                    .sum();
            assertEquals(59999.0 * 60000.0 / 2, sum);
        }
    }

    @Test
    void concurrentNavigationAndAttributes() throws Exception {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("dense_links_big.h5"))) {
            List<String> expected = h5.root().childNames();
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                List<Future<List<String>>> results = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    results.add(pool.submit(() -> {
                        List<String> names = null;
                        for (int i = 0; i < 50; i++) {
                            names = h5.root().childNames();
                            for (Hdf5Object child : h5.root().children()) {
                                child.attributes();
                            }
                        }
                        return names;
                    }));
                }
                for (Future<List<String>> result : results) {
                    assertArrayEquals(expected.toArray(), result.get().toArray());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
