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
 * through a {@link RangeReader}. It is <b>not</b> a normal test: it is skipped unless run with
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

            Dataset chunked = root.dataset("chunked");
            Dataset contiguous = root.dataset("contiguous");
            row("blocks(16): 128 blocks of a chunked dataset", () -> chunked.blocks(16)
                    .mapToLong(b -> b.readDoubles().length).sum());
            row("1,000 random 4 x 4 selections, chunked", () -> randomSelections(chunked, 1000));
            row("1,000 random 4 x 4 selections, contiguous", () -> randomSelections(contiguous, 1000));
        }

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
            row("list 20,000 links (new handle)", () -> h5.root().group("many").links().size());
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
