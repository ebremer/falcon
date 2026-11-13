package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Chunks filtered on several threads (P2 PF9, PF10): a read returns the same bytes, fails with the same
 * exception, and reads the file in the same requests however many threads decode its chunks
 * ({@link OpenOptions#decodeThreads}); and a writer writes the same file, byte for byte, however many
 * filter them ({@link Hdf5Writer#encodeThreads}).
 */
@Timeout(300)
class ParallelChunksTest {

    private static final int ROWS = 300;
    private static final int COLUMNS = 250;
    static final double[] DOUBLES = new double[ROWS * COLUMNS];
    private static final int[] INTS = new int[400 * 64];
    private static final int[] HALF = new int[32 * 4096];
    private static final String[] STRINGS = new String[5000];

    static {
        for (int i = 0; i < DOUBLES.length; i++) {
            DOUBLES[i] = Math.sin(i * 1e-3) * 1000 + (i % 977);
        }
        for (int i = 0; i < INTS.length; i++) {
            INTS[i] = i * 31 - 7;
        }
        for (int i = 0; i < HALF.length; i++) {
            HALF[i] = (i % 4096) * (i / 4096 + 3);
        }
        for (int i = 0; i < STRINGS.length; i++) {
            STRINGS[i] = "s" + i + "é".repeat(i % 5);
        }
    }

    @TempDir
    Path dir;

    /**
     * Writes a file whose datasets exercise every way chunks reach the file: given whole, in small chunks
     * (filtered several to a piece of work) and large ones (each a piece); appended, many chunks at a time and
     * few (too few for another thread), and written again; of variable-length data (whose chunks are filtered
     * one at a time); through third-party filters; and partly written (so filtered on close). {@code threads}
     * 0 leaves the writer's default.
     */
    static void write(Path file, Hdf5Writer.Format format, int threads) throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            if (threads > 0) {
                assertSame(w, w.encodeThreads(threads));
            }
            w.doubleChunkedDataset("whole", DOUBLES, new long[] {ROWS, COLUMNS}, new long[] {16, 32})
                    .shuffle().deflate(4).fletcher32();                       // 152 chunks of 4 KiB
            w.doubleChunkedDataset("wide", DOUBLES, new long[] {ROWS, COLUMNS}, new long[] {64, 125}).deflate(1); // 10 of 62 KiB
            Hdf5Writer.DatasetWriter rows = w.createDataset("rows", Datatype.int32(), 0, 64).chunked(8, 16)
                    .maxShape(Hdf5Writer.UNLIMITED, 64).deflate(6);
            rows.append(INTS);                                 // 400 rows: 200 chunks of 512 bytes complete at once
            rows.append(Arrays.copyOf(INTS, 40 * 64));         // 20 chunks: too few to hand to another thread
            rows.append(INTS);
            rows.write(new long[] {3, 0}, new long[] {300, 64}, Arrays.copyOf(INTS, 300 * 64)); // stored chunks, read back
            Hdf5Writer.DatasetWriter strings = w.createDataset("strings", Datatype.variableString(), 0).chunked(100)
                    .maxShape(Hdf5Writer.UNLIMITED).deflate(1);
            for (int i = 0; i < 2; i++) {
                strings.append(STRINGS);                       // 50 chunks, each with heap ids to fill in
            }
            w.createDataset("lzf", Datatype.float64(), 75_000).chunked(1000).lzf().write(DOUBLES);
            w.createDataset("zstd", Datatype.int32(), 400, 64).chunked(40, 16).shuffle().zstd(3).write(INTS);
            w.createDataset("szip", Datatype.int32(), 25_600).chunked(256).szip().write(INTS);
            Hdf5Writer.DatasetWriter partial = w.createDataset("partial", Datatype.int32(), 64, 4096).chunked(64, 64)
                    .fillValue(-7).deflate(2);
            partial.write(new long[] {0, 0}, new long[] {32, 4096}, HALF); // half of each chunk: all 64 wait for close
        }
    }

    // ------------------------------------------------------------------ writing

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void aFileIsTheSameHoweverManyThreadsFilterItsChunks(Hdf5Writer.Format format) throws IOException {
        Path one = dir.resolve("one.h5");
        write(one, format, 1);
        byte[] expected = Files.readAllBytes(one);
        for (int threads : new int[] {0, 2, 3, 8, 64}) {
            Path file = dir.resolve("threads" + threads + ".h5");
            write(file, format, threads);
            assertArrayEquals(expected, Files.readAllBytes(file), threads + " threads");
        }
        try (Hdf5File h5 = Hdf5File.open(one)) {
            assertArrayEquals(DOUBLES, h5.root().dataset("whole").readDoubles());
            assertArrayEquals(DOUBLES, h5.root().dataset("wide").readDoubles());
            int[] rows = h5.root().dataset("rows").readInts();
            assertEquals(840 * 64, rows.length);
            assertEquals(INTS[INTS.length - 1], rows[rows.length - 1]);
            assertArrayEquals(Arrays.copyOf(INTS, 3 * 64), Arrays.copyOf(rows, 3 * 64));
            assertArrayEquals(Arrays.copyOf(INTS, 300 * 64), Arrays.copyOfRange(rows, 3 * 64, 303 * 64)); // written again
            assertArrayEquals(Arrays.copyOfRange(INTS, 303 * 64, 400 * 64), Arrays.copyOfRange(rows, 303 * 64, 400 * 64));
            String[] strings = h5.root().dataset("strings").readStrings();
            assertEquals(10_000, strings.length);
            assertArrayEquals(STRINGS, Arrays.copyOfRange(strings, 5000, 10_000));
            assertArrayEquals(DOUBLES, h5.root().dataset("lzf").readDoubles());
            assertArrayEquals(INTS, h5.root().dataset("zstd").readInts());
            assertArrayEquals(INTS, h5.root().dataset("szip").readInts());
            int[] partial = h5.root().dataset("partial").readInts();
            assertArrayEquals(HALF, Arrays.copyOf(partial, HALF.length));
            assertEquals(-7, partial[HALF.length]);
            assertEquals(-7, partial[partial.length - 1]);
        }
    }

    @Test
    void aChangedFileIsTheSameHoweverManyThreadsFilterItsChunks() throws IOException {
        Path original = dir.resolve("original.h5");
        write(original, Hdf5Writer.Format.LATEST, 1);
        Map<Integer, byte[]> files = new LinkedHashMap<>();
        for (int threads : new int[] {1, 8}) {
            Path file = dir.resolve("changed" + threads + ".h5");
            Files.copy(original, file);
            try (Hdf5Writer w = Hdf5Writer.open(file).encodeThreads(threads)) {
                Hdf5Writer.DatasetWriter rows = w.root().dataset("rows");
                rows.append(INTS);
                rows.write(new long[] {100, 0}, new long[] {400, 64}, INTS);
                w.createDataset("more", Datatype.float64(), 0, COLUMNS).chunked(4, 50).maxShape(Hdf5Writer.UNLIMITED, COLUMNS)
                        .shuffle().deflate(3).append(DOUBLES);
            }
            files.put(threads, Files.readAllBytes(file));
            try (Hdf5File h5 = Hdf5File.open(file)) {
                int[] rows = h5.root().dataset("rows").readInts();
                assertEquals(1240 * 64, rows.length);
                assertArrayEquals(INTS, Arrays.copyOfRange(rows, 100 * 64, 500 * 64));
                assertArrayEquals(INTS, Arrays.copyOfRange(rows, 840 * 64, 1240 * 64));
                assertArrayEquals(DOUBLES, h5.root().dataset("more").readDoubles());
            }
        }
        assertArrayEquals(files.get(1), files.get(8));
        assertTrue(files.get(1).length > Files.size(original), "the file grew");
    }

    @Test
    void encodeThreadsMustBeAtLeastOne() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("unused.h5"))) {
            assertThrows(IllegalArgumentException.class, () -> w.encodeThreads(0));
            assertThrows(IllegalArgumentException.class, () -> w.encodeThreads(-1));
            assertSame(w, w.encodeThreads(1));
        }
    }

    // ------------------------------------------------------------------ reading

    /** Everything a read can return of the dataset at {@code path}: whole, a box, a strided selection, points. */
    static List<Object> reads(Hdf5File h5, String path) {
        Dataset dataset = h5.root().dataset(path);
        long[] dims = dataset.dataspace().dimensions();
        int rank = dims.length;
        List<Object> out = new ArrayList<>();
        out.add(dataset.readRawBytes());
        long[] offset = new long[rank];
        long[] count = new long[rank];
        long[] stride = new long[rank];
        long[] blocks = new long[rank];
        for (int d = 0; d < rank; d++) {
            offset[d] = dims[d] / 5;
            count[d] = dims[d] / 2;
            stride[d] = 3;
            blocks[d] = (dims[d] - offset[d]) / 3;
        }
        out.add(dataset.select(offset, count).readRawBytes());
        out.add(dataset.select(offset, stride, blocks, null).readRawBytes());
        Random random = new Random(11);
        long[][] points = new long[500][rank];
        for (long[] point : points) {
            for (int d = 0; d < rank; d++) {
                point[d] = random.nextInt((int) dims[d]);
            }
        }
        out.add(dataset.selectPoints(points).readRawBytes());
        return out;
    }

    static void assertSameReads(List<Object> expected, List<Object> actual, String what) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals((byte[]) expected.get(i), (byte[]) actual.get(i), what + ", read " + i);
        }
    }

    @Test
    void aReadIsTheSameHoweverManyThreadsDecodeItsChunks() throws IOException {
        Path file = dir.resolve("read.h5");
        write(file, Hdf5Writer.Format.LATEST, 1);
        String[] datasets = {"whole", "wide", "rows", "strings", "lzf", "zstd", "szip", "partial"};
        Map<String, List<Object>> expected = new LinkedHashMap<>();
        try (Hdf5File h5 = Hdf5File.open(file, OpenOptions.defaults().decodeThreads(1))) {
            for (String name : datasets) {
                expected.put(name, reads(h5, name));
            }
            assertArrayEquals(DOUBLES, h5.root().dataset("whole").readDoubles());
        }
        List<OpenOptions> others = List.of(OpenOptions.defaults(), OpenOptions.defaults().decodeThreads(2),
                OpenOptions.defaults().decodeThreads(8), OpenOptions.defaults().decodeThreads(64).chunkCacheSize(0),
                OpenOptions.defaults().decodeThreads(8).chunkCacheSize(40_000)); // a cache of a few chunks
        for (OpenOptions options : others) {
            try (Hdf5File h5 = Hdf5File.open(file, options)) {
                for (int pass = 0; pass < 2; pass++) { // the second finds some chunks in the cache
                    for (String name : datasets) {
                        assertSameReads(expected.get(name), reads(h5, name), name + " with " + options);
                    }
                }
            }
            // ... and through a reader, which serves the file a request at a time
            try (FileChannel channel = FileChannel.open(file);
                 Hdf5File h5 = Hdf5File.open(RangeReader.of(channel), options)) {
                assertSameReads(expected.get("whole"), reads(h5, "whole"), "whole through a reader with " + options);
            }
        }
    }

    /** The libhdf5-written fixtures' filtered datasets, of every built-in filter, read the same on eight threads. */
    @ParameterizedTest
    @ValueSource(strings = {"szip.h5", "scaleoffset.h5", "chunked_data.h5", "nbit_data.h5", "compound_nbit.h5",
        "filter_edge.h5"})
    void fixturesReadTheSameOnSeveralThreads(String fixture) throws IOException {
        int compared = 0;
        try (Hdf5File one = Hdf5File.open(Fixtures.path(fixture), OpenOptions.defaults().decodeThreads(1));
             Hdf5File eight = Hdf5File.open(Fixtures.path(fixture), OpenOptions.defaults().decodeThreads(8))) {
            for (Hdf5Object object : one.root().children()) {
                if (object instanceof Dataset dataset && dataset.dataLayout() instanceof DataLayout.Chunked) {
                    Dataset other = eight.root().dataset(dataset.name());
                    assertArrayEquals(dataset.readRawBytes(), other.readRawBytes(), fixture + " " + dataset.path());
                    compared++;
                }
            }
        }
        assertTrue(compared > 0, fixture + " has no chunked dataset");
    }

    /**
     * Chunks 37, 41, 90 and 130 (in row-major order) of 152 are damaged: a read fails with the first of them
     * it covers, whose checksums its message gives, on any number of threads. The chunks are of 4 KiB (16 x
     * 32, decoded several to a piece of work) or, 16 rows of them to a chunk, of 64 KiB (each a piece).
     */
    @ParameterizedTest
    @ValueSource(ints = {16, 256})
    void theFirstDamagedChunkFailsTheReadHoweverManyThreadsDecode(int chunkRows) throws IOException {
        int rows = 19 * chunkRows;
        double[] data = new double[rows * COLUMNS];
        for (int i = 0; i < data.length; i++) {
            data[i] = DOUBLES[i % DOUBLES.length] + i;
        }
        Path file = dir.resolve("damaged.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            // fletcher32 alone: a damaged chunk fails with its own checksums in the message
            w.doubleChunkedDataset("d", data, new long[] {rows, COLUMNS}, new long[] {chunkRows, 32}).fletcher32();
        }
        byte[] bytes = Files.readAllBytes(file);
        List<ChunkRecord> chunks;
        try (Hdf5File h5 = Hdf5File.open(bytes)) {
            Dataset dataset = h5.root().dataset("d");
            chunks = dataset.chunkIndex((DataLayout.Chunked) dataset.dataLayout()).all();
        }
        assertEquals(19 * 8, chunks.size());
        for (int damaged : new int[] {130, 37, 41, 90}) {
            bytes[(int) chunks.get(damaged).address() + 100 + damaged] ^= 0x55;
        }
        // From chunk row 4 on, chunk 37 is still the first damaged one read; from chunk row 6 on, chunk 90 is.
        long[] fromRow4 = {4L * chunkRows, 0};
        long[] fromRow6 = {6L * chunkRows, 0};
        long[] toTheEnd4 = {15L * chunkRows, COLUMNS};
        long[] toTheEnd6 = {13L * chunkRows, COLUMNS};
        String first;
        String later;
        try (Hdf5File h5 = Hdf5File.open(bytes, OpenOptions.defaults().decodeThreads(1))) {
            Dataset dataset = h5.root().dataset("d");
            first = assertThrows(HdfFormatException.class, dataset::readDoubles).getMessage();
            assertTrue(first.contains("fletcher32"), first);
            assertEquals(first, assertThrows(HdfFormatException.class,
                    () -> dataset.select(fromRow4, toTheEnd4).readDoubles()).getMessage());
            later = assertThrows(HdfFormatException.class,
                    () -> dataset.select(fromRow6, toTheEnd6).readDoubles()).getMessage();
            assertNotEquals(first, later);
        }
        for (int threads : new int[] {2, 4, 16}) {
            try (Hdf5File h5 = Hdf5File.open(bytes, OpenOptions.defaults().decodeThreads(threads).chunkCacheSize(0))) {
                Dataset dataset = h5.root().dataset("d");
                for (int round = 0; round < 25; round++) {
                    assertEquals(first, assertThrows(HdfFormatException.class, dataset::readDoubles).getMessage());
                    assertEquals(later, assertThrows(HdfFormatException.class,
                            () -> dataset.select(fromRow6, toTheEnd6).readDoubles()).getMessage());
                }
                // what no damaged chunk holds still reads
                double[] top = dataset.select(new long[] {0, 0}, new long[] {4L * chunkRows, COLUMNS}).readDoubles();
                assertArrayEquals(Arrays.copyOf(data, 4 * chunkRows * COLUMNS), top);
            }
        }
    }

    /** A reader that notes each request made of it. */
    private static final class Recording implements RangeReader {
        private final RangeReader inner;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());

        Recording(RangeReader inner) {
            this.inner = inner;
        }

        @Override
        public long size() throws IOException {
            return inner.size();
        }

        @Override
        public void read(long position, ByteBuffer destination) throws IOException {
            requests.add(position + "+" + destination.remaining());
            threads.add(Thread.currentThread());
            inner.read(position, destination);
        }
    }

    @Test
    void theFileIsReadInTheSameRequestsHoweverManyThreadsDecode() throws IOException {
        Path file = dir.resolve("requests.h5");
        write(file, Hdf5Writer.Format.LATEST, 1);
        List<String> expected = null;
        for (int threads : new int[] {1, 8}) {
            try (FileChannel channel = FileChannel.open(file)) {
                Recording reader = new Recording(RangeReader.of(channel));
                // One request at a time, so that the reading thread makes them all, in an order of its own
                // (several at once are ChunksReadTogetherTest's); and small pages, so that there are many
                // (fewer since P2 PF12, which reads the pages of a chunk index together).
                OpenOptions options = OpenOptions.defaults().decodeThreads(threads).readerPageSize(512).readerRequests(1);
                try (Hdf5File h5 = Hdf5File.open(reader, options)) {
                    reads(h5, "whole");
                    reads(h5, "rows");
                }
                assertTrue(reader.requests.size() > 10, "requests: " + reader.requests.size());
                assertEquals(1, reader.threads.stream().distinct().count(), "only the reading thread reads the file");
                if (expected == null) {
                    expected = List.copyOf(reader.requests);
                } else {
                    assertEquals(expected, reader.requests);
                }
            }
        }
    }

    @Test
    void decodeThreadsIsAnOption() {
        assertEquals(Runtime.getRuntime().availableProcessors(), OpenOptions.DEFAULT_DECODE_THREADS);
        assertEquals(OpenOptions.DEFAULT_DECODE_THREADS, OpenOptions.defaults().decodeThreads());
        OpenOptions three = OpenOptions.defaults().decodeThreads(3);
        assertEquals(3, three.decodeThreads());
        assertEquals(3, three.chunkCacheSize(0).objectCacheSize(1).readerPageSize(1024).readerCacheSize(0)
                .virtualPrintfGap(2).virtualView(OpenOptions.VirtualView.FIRST_MISSING)
                .externalFileAccess(ExternalFileAccess.sameDirectory()).decodeThreads(), "the other setters keep it");
        assertTrue(three.toString().contains("decodeThreads=3"), three.toString());
        assertThrows(IllegalArgumentException.class, () -> OpenOptions.defaults().decodeThreads(0));
        assertThrows(IllegalArgumentException.class, () -> OpenOptions.defaults().decodeThreads(-4));
    }
}
