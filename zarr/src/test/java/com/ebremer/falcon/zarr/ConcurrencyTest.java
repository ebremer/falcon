package com.ebremer.falcon.zarr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrent use of one array (P1 C1, T4). A write to part of a chunk, or part of a shard, reads the chunk,
 * updates it, and stores it again; two threads doing that to one chunk lost each other's updates. Writes
 * to the same chunk through the same store object now take turns.
 */
class ConcurrencyTest {

    private static void runAll(int threads, int tasks, java.util.function.IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int n = i;
                futures.add(pool.submit(() -> task.accept(n)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
    }

    private static void assertNoUpdateLost(ArraySpec spec, Path dir) throws Exception {
        ZarrArray a = Zarr.createArray(FileSystemStore.open(dir), spec);
        int n = (int) a.size();
        runAll(8, n, i -> a.select(new long[] {i}, new long[] {1}).writeInts(new int[] {i + 1}));
        assertArrayEquals(IntStream.rangeClosed(1, n).toArray(), Zarr.openArray(FileSystemStore.open(dir)).readInts());
    }

    @Test
    void elementWritesToOneChunkFromManyThreadsAllLand(@TempDir Path dir) throws Exception {
        assertNoUpdateLost(ArraySpec.builder(new long[] {256}, DataType.INT32).gzip(1).build(), dir);
    }

    @Test
    void elementWritesToOneShardFromManyThreadsAllLand(@TempDir Path dir) throws Exception {
        // Different sub-chunks of one shard, and the same sub-chunk, from different threads.
        assertNoUpdateLost(ArraySpec.builder(new long[] {256}, DataType.INT32).sharding(16).build(), dir);
    }

    @Test
    void stringWritesToOneChunkFromManyThreadsAllLand(@TempDir Path dir) throws Exception {
        ZarrArray a = Zarr.createArray(FileSystemStore.open(dir),
                ArraySpec.builder(new long[] {128}, DataType.STRING).build());
        runAll(8, 128, i -> a.select(new long[] {i}, new long[] {1}).writeStrings(new String[] {"s" + i}));
        String[] read = Zarr.openArray(FileSystemStore.open(dir)).readStrings();
        for (int i = 0; i < 128; i++) {
            assertEquals("s" + i, read[i]);
        }
    }

    @Test
    void parallelBlockReadsWhileAnotherChunkIsWritten(@TempDir Path dir) throws Exception {
        ZarrArray a = Zarr.createArray(FileSystemStore.open(dir), ArraySpec.builder(new long[] {64, 64}, DataType.INT32)
                .chunkShape(8, 8).zstd().build());
        int[] data = IntStream.range(0, 64 * 64).toArray();
        a.writeInts(data);
        long expected = (long) data.length * (data.length - 1) / 2;
        // Readers sum every block while a writer keeps rewriting chunk (7, 7) with the same values.
        runAll(4, 40, i -> {
            if (i % 10 == 0) {
                a.select(new long[] {56, 56}, new long[] {8, 8}).writeInts(
                        IntStream.range(0, 64).map(k -> (56 + k / 8) * 64 + 56 + k % 8).toArray());
            } else {
                long sum = a.blocks().parallel().mapToLong(b -> IntStream.of(b.readInts()).asLongStream().sum()).sum();
                assertEquals(expected, sum);
            }
        });
    }
}
