package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.MemoryStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A small pure-JDK benchmark of the codec pipeline and the array read/write paths. It is <b>not</b> a
 * normal test: it is skipped unless run with {@code -Dfalcon.bench=true}, so it never slows CI.
 *
 * <pre>
 *   mvn -pl zarr test -Dtest=Benchmarks -Dfalcon.bench=true
 * </pre>
 *
 * <p>Timing is a warmup phase followed by measured iterations, reporting the median (nanoTime is the
 * clock; a sink consumes results so the work is not optimized away). Absolute numbers depend on the
 * machine and JVM; the point is the <em>relative</em> cost of the codecs and the compression ratios.
 * JMH would give more rigorous measurements but is not in {@code java.base} and is not a Falcon
 * dependency.
 */
class Benchmarks {

    private static final int WARMUP = 8;
    private static final int RUNS = 25;

    private long sink; // keeps the JIT from eliding decoded output

    @Test
    void runBenchmarks() {
        Assumptions.assumeTrue("true".equals(System.getProperty("falcon.bench")),
                "benchmark; run with -Dfalcon.bench=true");

        int elements = 1 << 20; // 1,048,576 int32 = 4 MiB chunk
        byte[] chunk = scientificInt32(elements);
        int rawBytes = chunk.length;

        System.out.println();
        System.out.println("Falcon Zarr benchmarks  —  " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.version"));
        System.out.println("chunk: " + elements + " int32 (" + mib(rawBytes) + " MiB), "
                + "structured numeric data\n");

        System.out.printf("%-10s %11s %11s %9s%n", "codec", "encode", "decode", "ratio");
        System.out.printf("%-10s %11s %11s %9s%n", "", "MB/s", "MB/s", "");
        System.out.println("-".repeat(44));
        codecRow("bytes", chunk, rawBytes, "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}]");
        codecRow("gzip-5", chunk, rawBytes, withBytes("{\"name\":\"gzip\",\"configuration\":{\"level\":5}}"));
        codecRow("zstd", chunk, rawBytes, withBytes("{\"name\":\"zstd\"}"));
        codecRow("blosc", chunk, rawBytes, withBytes("{\"name\":\"blosc\"}"));
        codecRow("crc32c", chunk, rawBytes, withBytes("{\"name\":\"crc32c\"}"));

        System.out.println();
        endToEnd("gzip-5", spec -> spec.gzip(5));
        endToEnd("zstd", ArraySpec.Builder::zstd);
        endToEnd("blosc", ArraySpec.Builder::blosc);

        System.out.println();
        cacheBenchmark();
        System.out.println("\nsink=" + sink); // print so nothing is dead code
    }

    // ---- codec-level ------------------------------------------------------------------------------

    private void codecRow(String name, byte[] chunk, int rawBytes, String codecsJson) {
        ChunkPipeline pipeline = ChunkPipeline.of(DataType.INT32, new long[] {rawBytes / 4}, codecs(codecsJson));
        byte[] fill = new byte[4];
        byte[] encoded = pipeline.encode(chunk, fill);

        long encodeNanos = median(() -> {
            byte[] out = pipeline.encode(chunk, fill);
            sink += out.length;
            return 0;
        });
        long decodeNanos = median(() -> {
            byte[] out = pipeline.decode(encoded);
            sink += out[0];
            return 0;
        });
        double ratio = (double) rawBytes / encoded.length;
        System.out.printf("%-10s %11.0f %11.0f %8.2fx%n",
                name, mbPerSec(rawBytes, encodeNanos), mbPerSec(rawBytes, decodeNanos), ratio);
    }

    // ---- end-to-end array read/write --------------------------------------------------------------

    private void endToEnd(String name, java.util.function.Consumer<ArraySpec.Builder> compressor) {
        int rows = 64;
        int cols = 1 << 16; // 64 x 65536 int32 = 16 MiB array
        int[] data = new int[rows * cols];
        byte[] pattern = scientificInt32(cols);
        ByteBuffer pb = ByteBuffer.wrap(pattern).order(ByteOrder.LITTLE_ENDIAN);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                data[r * cols + c] = pb.getInt(c * 4) + r; // vary per row so chunks differ
            }
        }
        long totalBytes = (long) data.length * 4;

        ArraySpec.Builder builder = ArraySpec.builder(new long[] {rows, cols}, DataType.INT32)
                .chunkShape(1, cols);
        compressor.accept(builder);
        ArraySpec spec = builder.build();

        long writeNanos = median(() -> {
            MemoryStore store = new MemoryStore();
            Zarr.createArray(store, spec).writeInts(data);
            sink += store.list().size();
            return 0;
        });

        MemoryStore store = new MemoryStore();
        Zarr.createArray(store, spec).writeInts(data);
        long readNanos = median(() -> {
            int[] out = Zarr.openArray(store).readInts();
            sink += out[0];
            return 0;
        });
        System.out.printf("end-to-end %-7s  write %6.0f MB/s   read %6.0f MB/s   (%s MiB array)%n",
                name, mbPerSec(totalBytes, writeNanos), mbPerSec(totalBytes, readNanos), mib(totalBytes));
    }

    // ---- chunk cache --------------------------------------------------------------------------------

    private void cacheBenchmark() {
        MemoryStore store = new MemoryStore();
        int cols = 1 << 16;
        ZarrArray array = Zarr.createArray(store, ArraySpec.builder(new long[] {8, cols}, DataType.INT32)
                .chunkShape(1, cols).gzip(5).build());
        int[] data = new int[8 * cols];
        byte[] pattern = scientificInt32(cols);
        ByteBuffer pb = ByteBuffer.wrap(pattern).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < data.length; i++) {
            data[i] = pb.getInt((i % cols) * 4);
        }
        array.writeInts(data);

        // 10 overlapping selections that all revisit chunk row 3
        long cached = median(() -> {
            ZarrArray a = Zarr.openArray(store);
            for (int i = 0; i < 10; i++) {
                sink += a.select(new long[] {3, i * 100}, new long[] {1, 4000}).readInts()[0];
            }
            return 0;
        });
        long uncached = median(() -> {
            ZarrArray a = Zarr.openArray(store);
            for (int i = 0; i < 10; i++) {
                sink += a.select(new long[] {3, i * 100}, new long[] {1, 4000}).readInts()[0];
                a.clearChunkCache();
            }
            return 0;
        });
        System.out.printf("chunk cache  10 overlapping selections:  cached %.2f ms   uncached %.2f ms   "
                + "(%.1fx)%n", cached / 1e6, uncached / 1e6, (double) uncached / cached);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Structured numeric data: a smooth ramp with a periodic component and light noise (compresses a few x). */
    private static byte[] scientificInt32(int n) {
        ByteBuffer b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        long state = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < n; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            int noise = (int) (state % 8);
            b.putInt(i, (i / 16) + (int) (100 * Math.sin(i * 0.01)) + noise);
        }
        return b.array();
    }

    private long median(LongSupplier op) {
        for (int i = 0; i < WARMUP; i++) {
            op.getAsLong();
        }
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            op.getAsLong();
            times[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(times);
        return times[RUNS / 2];
    }

    private static List<JsonObject> codecs(String json) {
        return Json.parse(json).asArray().values().stream().map(v -> v.asObject()).toList();
    }

    private static String withBytes(String codec) {
        return "[{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}," + codec + "]";
    }

    private static double mbPerSec(long bytes, long nanos) {
        return (bytes / 1e6) / (nanos / 1e9);
    }

    private static String mib(long bytes) {
        return String.format("%.1f", bytes / (1024.0 * 1024.0));
    }
}
