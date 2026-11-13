package com.ebremer.falcon.hdf5;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A small pure-JDK benchmark of the HDF5 read paths: open and walk, whole and partial reads of chunked
 * and contiguous data, streaming in blocks, reads of virtual datasets, lookups by name, and reading
 * through a {@link RangeReader}; and of writing a dataset, contiguous, chunked, and filtered. It is
 * <b>not</b> a normal test: it is skipped unless run with
 * {@code -Dfalcon.bench=true}, so it never slows CI.
 *
 * <pre>
 *   mvn -pl hdf5 -am test -Dtest=Benchmarks -Dfalcon.bench=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Each row is a warmup phase followed by measured runs, reporting the median ({@code nanoTime} is the
 * clock; a sink consumes results so the work is not optimized away). Absolute numbers depend on the
 * machine and JVM; compare rows, or runs of two versions on one machine (see {@code BENCHMARKS.md}).
 * JMH would measure more rigorously but is not in {@code java.base} and is not a Falcon dependency.
 */
class Benchmarks {

    private static final int WARMUP = 5;
    private static final int RUNS = 15;

    private long sink; // keeps the JIT from eliding results

    @Test
    void runBenchmarks(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue("true".equals(System.getProperty("falcon.bench")),
                "benchmark; run with -Dfalcon.bench=true");
        // -Dtest=Benchmarks also selects it in the fuzz execution, whose 128 MB heap its 32 MiB datasets outgrow.
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() > 256L << 20, "benchmark; needs more than 256 MB of heap");

        int side = 2048;
        double[] data = new double[side * side];
        for (int i = 0; i < data.length; i++) {
            data[i] = Math.sin(i * 1e-3) * 1000 + (i % 977);
        }
        Path file = dir.resolve("bench.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleDataset("contiguous", data, new long[] {side, side});
            w.doubleChunkedDataset("chunked", data, new long[] {side, side}, new long[] {64, 64});
            w.doubleChunkedDataset("gzip", data, new long[] {side, side}, new long[] {64, 64}).deflate(4);
            w.doubleChunkedDataset("shuffled", data, new long[] {side, side}, new long[] {64, 64}).shuffle().deflate(4);
        }

        System.out.println();
        System.out.println("Falcon HDF5 benchmarks  —  " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version"));
        System.out.println("data: " + side + " x " + side + " float64 (" + (8L * data.length >> 20)
                + " MiB), chunks 64 x 64 (1,024 chunks)\n");
        System.out.printf("%-58s %12s%n", "operation", "median");
        System.out.println("-".repeat(71));

        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            row("read whole: contiguous", () -> root.dataset("contiguous").readDoubles().length);
            row("read whole: chunked (1,024 chunks)", () -> root.dataset("chunked").readDoubles().length);
            row("read whole: chunked + gzip", () -> root.dataset("gzip").readDoubles().length);
            row("read whole: chunked + shuffle + gzip", () -> root.dataset("shuffled").readDoubles().length);

            Dataset chunked = root.dataset("chunked");
            Dataset contiguous = root.dataset("contiguous");
            row("blocks(16): 128 blocks of a chunked dataset", () -> chunked.blocks(16)
                    .mapToLong(b -> b.readDoubles().length).sum());
            row("1,000 random 4 x 4 selections, chunked", () -> randomSelections(chunked, 1000));
            row("1,000 random 4 x 4 selections, contiguous", () -> randomSelections(contiguous, 1000));
            // P2 PF8: strided and point selections of chunked data, copied out of each chunk
            long[] zero = {0, 0};
            row("every other element (stride 2 x 2), chunked", () -> chunked.select(zero, new long[] {2, 2},
                    new long[] {side / 2, side / 2}, new long[] {1, 1}).readDoubles().length);
            row("every other element (stride 2 x 2), contiguous", () -> contiguous.select(zero, new long[] {2, 2},
                    new long[] {side / 2, side / 2}, new long[] {1, 1}).readDoubles().length);
            row("every other row (stride 2 x 1), chunked", () -> chunked.select(zero, new long[] {2, 1},
                    new long[] {side / 2, side}, new long[] {1, 1}).readDoubles().length);
            row("4 x 4 blocks every 8 x 8, chunked", () -> chunked.select(zero, new long[] {8, 8},
                    new long[] {side / 8, side / 8}, new long[] {4, 4}).readDoubles().length);
            long[][] points = new long[100_000][];
            Random random = new Random(7);
            for (int i = 0; i < points.length; i++) {
                points[i] = new long[] {random.nextInt(side), random.nextInt(side)};
            }
            row("100,000 random points, chunked", () -> chunked.selectPoints(points).readDoubles().length);
        }

        // P2 PF9: filtered chunks decoded on several threads, and on the reading thread alone. The small
        // selections are read with no chunk cache, so that each decodes the one to four chunks it covers.
        try (Hdf5File h5 = Hdf5File.open(file, OpenOptions.defaults().decodeThreads(1))) {
            Dataset gzip = h5.root().dataset("gzip");
            row("read whole: chunked + gzip, one thread", () -> gzip.readDoubles().length);
            // P2 PF13: the byte shuffle, undone an element at a time.
            Dataset shuffled = h5.root().dataset("shuffled");
            row("read whole: chunked + shuffle + gzip, one thread", () -> shuffled.readDoubles().length);
        }
        try (Hdf5File h5 = Hdf5File.open(file, OpenOptions.defaults().chunkCacheSize(0))) {
            Dataset gzip = h5.root().dataset("gzip");
            row("1,000 random 4 x 4 selections, gzip, no cache", () -> randomSelections(gzip, 1000));
        }
        try (Hdf5File h5 = Hdf5File.open(file, OpenOptions.defaults().chunkCacheSize(0).decodeThreads(1))) {
            Dataset gzip = h5.root().dataset("gzip");
            row("1,000 random 4 x 4 selections, gzip, no cache, one thread", () -> randomSelections(gzip, 1000));
        }

        // P2 PF10: writing (create, one dataset, close), its chunks filtered on several threads and on one.
        Path written = dir.resolve("written.h5");
        long[] shape = {side, side};
        long[] chunk = {64, 64};
        row("write: contiguous", () -> {
            try (Hdf5Writer w = Hdf5Writer.create(written)) {
                w.doubleDataset("d", data, shape);
            }
            return Files.size(written);
        });
        row("write: chunked (1,024 chunks)", () -> {
            try (Hdf5Writer w = Hdf5Writer.create(written)) {
                w.doubleChunkedDataset("d", data, shape, chunk);
            }
            return Files.size(written);
        });
        row("write: chunked + gzip", () -> {
            try (Hdf5Writer w = Hdf5Writer.create(written)) {
                w.doubleChunkedDataset("d", data, shape, chunk).deflate(4);
            }
            return Files.size(written);
        });
        row("write: chunked + gzip, one thread", () -> {
            try (Hdf5Writer w = Hdf5Writer.create(written).encodeThreads(1)) {
                w.doubleChunkedDataset("d", data, shape, chunk).deflate(4);
            }
            return Files.size(written);
        });
        // P2 PF13: the byte shuffle before the deflate. P2 PF14: values of the type's own, copied in one move.
        row("write: chunked + shuffle + gzip", () -> {
            try (Hdf5Writer w = Hdf5Writer.create(written)) {
                w.doubleChunkedDataset("d", data, shape, chunk).shuffle().deflate(4);
            }
            return Files.size(written);
        });
        row("write: chunked + shuffle + gzip, one thread", () -> {
            try (Hdf5Writer w = Hdf5Writer.create(written).encodeThreads(1)) {
                w.doubleChunkedDataset("d", data, shape, chunk).shuffle().deflate(4);
            }
            return Files.size(written);
        });
        int[] ints = new int[data.length];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = (int) data[i];
        }
        row("write: contiguous, int32 (16 MiB)", () -> {
            try (Hdf5Writer w = Hdf5Writer.create(written)) {
                w.intDataset("d", ints, shape);
            }
            return Files.size(written);
        });

        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds.h5"))) {
            Dataset vds = h5.root().dataset("vds");
            row("virtual: 100 one-element selections (2 source files)", () -> {
                long n = 0;
                for (int i = 0; i < 100; i++) {
                    n += vds.select(new long[] {i % 2, i % 4}, new long[] {1, 1}).readInts()[0];
                }
                return n;
            });
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("vds_unlimited.h5"))) {
            Dataset printf = h5.root().dataset("printf");
            long[] dims = printf.dataspace().dimensions();
            row("virtual: 100 one-element selections (printf, 3 sources)", () -> {
                long n = 0;
                for (int i = 0; i < 100; i++) {
                    long[] at = new long[dims.length];
                    at[0] = i % dims[0];
                    long[] one = new long[dims.length];
                    Arrays.fill(one, 1);
                    n += printf.select(at, one).readInts()[0];
                }
                return n;
            });
        }

        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("dense_big.h5"))) {
            row("100 link lookups, dense group of 20,000 (new handles)", () -> {
                long n = 0;
                for (int i = 0; i < 100; i++) {
                    n += h5.root().group("many").link("link" + String.format("%05d", i * 199)).isPresent() ? 1 : 0;
                }
                return n;
            });
            row("100 attribute lookups among 3,000 dense (new handles)", () -> {
                long n = 0;
                for (int i = 0; i < 100; i++) {
                    n += h5.root().dataset("d").attribute("attr" + String.format("%04d", i * 29)).orElseThrow().readInt();
                }
                return n;
            });
        }
        // Since P2 PF6 a group's links are kept for every handle of it, so the listing is timed with nothing kept.
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("dense_big.h5"), OpenOptions.defaults().objectCacheSize(0))) {
            row("list 20,000 links (new handle, nothing cached)", () -> h5.root().group("many").links().size());
        }
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("oldstyle_big.h5"))) {
            row("100 link lookups, old-style group of 5,000 (new handles)", () -> {
                long n = 0;
                for (int i = 0; i < 100; i++) {
                    n += h5.root().group("many").link("link" + String.format("%04d", i * 49)).isPresent() ? 1 : 0;
                }
                return n;
            });
        }

        System.out.println();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            AtomicLong bytes = new AtomicLong();
            RangeReader counting = new RangeReader() {
                @Override
                public long size() throws IOException {
                    return channel.size();
                }

                @Override
                public void read(long position, ByteBuffer destination) throws IOException {
                    bytes.addAndGet(destination.remaining());
                    RangeReader.of(channel).read(position, destination);
                }
            };
            try (Hdf5File h5 = Hdf5File.open(counting)) {
                h5.root().dataset("gzip").select(new long[] {1000, 1000}, new long[] {4, 4}).readDoubles();
            }
            System.out.printf("RangeReader: a 4 x 4 selection of the gzip dataset read %,d of %,d bytes%n",
                    bytes.get(), Files.size(file));
        }

        // P2 PF11: through a reader that answers each request after 20 ms, as a store at some distance does.
        // The chunks a read needs are asked for together: one request at a time, and eight at once. No chunk
        // cache, so that every run reads its chunks again.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            AtomicLong requests = new AtomicLong();
            RangeReader slow = new RangeReader() {
                @Override
                public long size() throws IOException {
                    return channel.size();
                }

                @Override
                public void read(long position, ByteBuffer destination) throws IOException {
                    requests.incrementAndGet();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    RangeReader.of(channel).read(position, destination);
                }
            };
            for (int atOnce : new int[] {1, OpenOptions.DEFAULT_READER_REQUESTS}) {
                String how = atOnce == 1 ? "one request at a time" : atOnce + " requests at once";
                try (Hdf5File h5 = Hdf5File.open(slow, OpenOptions.defaults().chunkCacheSize(0).readerRequests(atOnce))) {
                    Dataset gzip = h5.root().dataset("gzip");
                    sink += gzip.readDoubles().length; // the chunk index is read once
                    requests.set(0);
                    row("20 ms a request: read whole gzip, " + how, () -> gzip.readDoubles().length);
                    System.out.printf("%60s%n", "(" + requests.get() / (WARMUP + RUNS) + " requests a read)");
                    requests.set(0);
                    row("20 ms a request: 20 random 128 x 128, " + how, () -> {
                        Random random = new Random(7);
                        long n = 0;
                        for (int i = 0; i < 20; i++) {
                            long[] at = {random.nextInt(1920), random.nextInt(1920)};
                            n += gzip.select(at, new long[] {128, 128}).readDoubles().length;
                        }
                        return n;
                    });
                    System.out.printf("%60s%n", "(" + requests.get() / (WARMUP + RUNS) + " requests for the 20)");
                }
            }
        }
        System.out.println("\nsink=" + sink);
    }

    private static long randomSelections(Dataset dataset, int count) {
        Random random = new Random(7);
        long n = 0;
        for (int i = 0; i < count; i++) {
            long[] at = {random.nextInt(2044), random.nextInt(2044)};
            n += dataset.select(at, new long[] {4, 4}).readDoubles().length;
        }
        return n;
    }

    private interface Work {
        long run() throws IOException;
    }

    private void row(String name, Work work) throws IOException {
        for (int i = 0; i < WARMUP; i++) {
            sink += work.run();
        }
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            sink += work.run();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        System.out.printf("%-58s %9.2f ms%n", name, times[RUNS / 2] / 1e6);
    }
}
