package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.layout.ChunkRecord;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The chunks of a file read through a {@link RangeReader} are read together (P2 PF11): those next to each
 * other in the file in one request, and several requests at once ({@link OpenOptions#readerRequests}). A
 * read returns the same bytes and fails with the same exception however many requests it makes at once.
 */
@Timeout(300)
class ChunksReadTogetherTest {

    private static final int PAGE = OpenOptions.DEFAULT_READER_PAGE_SIZE;

    @TempDir
    Path dir;

    /** A file in memory that notes each request made of it, and can wait, or fail, at chosen requests. */
    private static final class Store implements RangeReader {
        final byte[] file;
        final List<long[]> requests = Collections.synchronizedList(new ArrayList<>()); // {position, length}
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger mostInFlight = new AtomicInteger();
        volatile Each each = (position, length) -> { };

        interface Each {
            void request(long position, int length) throws IOException;
        }

        Store(byte[] file) {
            this.file = file;
        }

        @Override
        public long size() {
            return file.length;
        }

        @Override
        public void read(long position, ByteBuffer destination) throws IOException {
            int length = destination.remaining();
            synchronized (this) { // a request and its thread at the same place in the two lists
                requests.add(new long[] {position, length});
                threads.add(Thread.currentThread());
            }
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                each.request(position, length);
                destination.put(file, (int) position, length);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        /** Forgets the requests made so far. */
        void reset() {
            requests.clear();
            threads.clear();
            mostInFlight.set(0);
        }

        long bytesAskedFor() {
            long sum = 0;
            synchronized (requests) {
                for (long[] request : requests) {
                    sum += request[1];
                }
            }
            return sum;
        }

        /** The requests made, in the order of their positions. */
        List<long[]> sorted() {
            List<long[]> copy;
            synchronized (requests) {
                copy = new ArrayList<>(requests);
            }
            copy.sort((a, b) -> Long.compare(a[0], b[0]));
            return copy;
        }
    }

    private static double[] noise(int count, long seed) {
        Random random = new Random(seed);
        double[] data = new double[count];
        for (int i = 0; i < count; i++) {
            data[i] = random.nextDouble();
        }
        return data;
    }

    private static List<ChunkRecord> chunksOf(Dataset dataset) {
        return dataset.chunkIndex((DataLayout.Chunked) dataset.dataLayout()).all();
    }

    // ------------------------------------------------------------------ the same bytes

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void aReadIsTheSameHoweverManyRequestsAreMadeAtOnce(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("read.h5");
        ParallelChunksTest.write(file, format, 1);
        byte[] bytes = Files.readAllBytes(file);
        String[] datasets = {"whole", "wide", "rows", "strings", "lzf", "zstd", "szip", "partial"};
        Map<String, List<Object>> expected = new LinkedHashMap<>();
        try (Hdf5File h5 = Hdf5File.open(bytes, OpenOptions.defaults().decodeThreads(1))) {
            for (String name : datasets) {
                expected.put(name, ParallelChunksTest.reads(h5, name));
            }
        }
        for (int requests : new int[] {1, 2, 8, 64}) {
            for (int page : new int[] {512, 4096, PAGE}) {
                OpenOptions base = OpenOptions.defaults().readerRequests(requests).readerPageSize(page);
                for (OpenOptions options : List.of(base.decodeThreads(1).chunkCacheSize(0),
                        base.chunkCacheSize(40_000))) { // a cache of a few chunks: some are found in it, some not
                    Store store = new Store(bytes);
                    try (Hdf5File h5 = Hdf5File.open(store, options)) {
                        for (int pass = 0; pass < 2; pass++) {
                            for (String name : datasets) {
                                ParallelChunksTest.assertSameReads(expected.get(name), ParallelChunksTest.reads(h5, name),
                                        name + " with " + options);
                            }
                        }
                    }
                    assertEquals(0, store.inFlight.get());
                    assertTrue(store.mostInFlight.get() <= requests, store.mostInFlight.get() + " at once of " + requests);
                }
            }
        }
    }

    // ------------------------------------------------------------------ fewer requests

    /** 64 chunks of some 32 KiB each, stored one after another: a whole read asks for them in one request. */
    @Test
    void chunksNextToEachOtherComeInOneRequest() throws IOException {
        double[] data = noise(512 * 512, 3);
        Path file = dir.resolve("together.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleChunkedDataset("d", data, new long[] {512, 512}, new long[] {64, 64}).deflate(1);
        }
        Store store = new Store(Files.readAllBytes(file));
        // Pages of 4 KiB: every request here is of more than a page, so none comes from the cached pages.
        try (Hdf5File h5 = Hdf5File.open(store, OpenOptions.defaults().chunkCacheSize(0).readerPageSize(4096))) {
            Dataset dataset = h5.root().dataset("d");
            List<ChunkRecord> chunks = chunksOf(dataset);
            assertEquals(64, chunks.size());
            long first = Long.MAX_VALUE;
            long end = 0;
            long stored = 0;
            for (ChunkRecord chunk : chunks) {
                first = Math.min(first, chunk.address());
                end = Math.max(end, chunk.address() + chunk.size());
                stored += chunk.size();
            }
            assertTrue(end - first - stored < 1024, "the chunks lie one after another, with little between them: "
                    + (end - first - stored) + " bytes");
            assertTrue(stored > 20L * PAGE, "and are far more than a page: " + stored);

            store.reset();
            assertArrayEquals(data, dataset.readDoubles());
            List<long[]> requests = store.sorted();
            assertEquals(1, requests.size(), "one request, where each chunk's pages were " + stored / PAGE);
            assertEquals(first, requests.get(0)[0]);
            assertEquals(end - first, requests.get(0)[1], "from the first chunk's first byte to the last one's last");
            assertSame(Thread.currentThread(), store.threads.get(0), "one request: the reading thread makes it");

            // Two by two chunks in the middle: each row's two are next to each other, and the rows 8 chunks
            // apart, so two requests, made at once.
            store.reset();
            double[] box = dataset.select(new long[] {192, 192}, new long[] {128, 128}).readDoubles();
            for (int r = 0; r < 128; r++) {
                assertArrayEquals(Arrays.copyOfRange(data, (192 + r) * 512 + 192, (192 + r) * 512 + 320),
                        Arrays.copyOfRange(box, r * 128, r * 128 + 128));
            }
            requests = store.sorted();
            assertEquals(2, requests.size());
            Map<String, ChunkRecord> byOffset = new LinkedHashMap<>();
            for (ChunkRecord chunk : chunks) {
                byOffset.put(chunk.offset()[0] + "," + chunk.offset()[1], chunk);
            }
            for (int row = 0; row < 2; row++) {
                ChunkRecord left = byOffset.get((192 + 64 * row) + ",192");
                ChunkRecord right = byOffset.get((192 + 64 * row) + ",256");
                assertEquals(left.address(), requests.get(row)[0]);
                assertEquals(right.address() + right.size() - left.address(), requests.get(row)[1], "the two chunks");
            }
            assertTrue(store.mostInFlight.get() <= 2, "at most the two at once");

            // One chunk alone is a request of its own.
            store.reset();
            dataset.select(new long[] {70, 70}, new long[] {4, 4}).readDoubles();
            ChunkRecord one = byOffset.get("64,64");
            assertEquals(1, store.requests.size());
            assertEquals(one.address(), store.requests.get(0)[0]);
            assertEquals(one.size(), store.requests.get(0)[1]);
        }
    }

    @Test
    void chunksTheCacheHoldsAreNotAskedForAgain() throws IOException {
        double[] data = noise(512 * 512, 4);
        Path file = dir.resolve("cached.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleChunkedDataset("d", data, new long[] {512, 512}, new long[] {64, 64}).deflate(1);
        }
        Store store = new Store(Files.readAllBytes(file));
        try (Hdf5File h5 = Hdf5File.open(store)) { // a chunk cache of 16 MiB: the 2 MiB of chunks fit
            Dataset dataset = h5.root().dataset("d");
            assertArrayEquals(data, dataset.readDoubles());
            store.reset();
            assertArrayEquals(data, dataset.readDoubles());
            dataset.select(new long[] {100, 100}, new long[] {300, 300}).readDoubles();
            assertEquals(0, store.requests.size());
        }
    }

    /** 24 MiB of chunks one after another: read a batch at a time, in requests of no more than 8 MiB. */
    @Test
    void aLongReadIsReadABatchAtATime() throws IOException {
        int chunkElements = 1 << 17; // 1 MiB of doubles
        double[] data = noise(24 * chunkElements, 5);
        Path file = dir.resolve("long.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleChunkedDataset("d", data, new long[] {data.length}, new long[] {chunkElements});
        }
        byte[] bytes = Files.readAllBytes(file);
        for (int atOnce : new int[] {1, 8}) {
            Store store = new Store(bytes);
            try (Hdf5File h5 = Hdf5File.open(store, OpenOptions.defaults().readerRequests(atOnce))) {
                Dataset dataset = h5.root().dataset("d");
                assertEquals(24, chunksOf(dataset).size());
                store.reset();
                assertArrayEquals(data, dataset.readDoubles());
                long asked = 0;
                int count = 0;
                for (long[] request : store.sorted()) {
                    if (request[1] >= 1 << 20) {
                        assertTrue(request[1] <= 8 << 20, "a request of " + request[1] + " bytes");
                        asked += request[1];
                        count++;
                    }
                }
                assertEquals(24L << 20, asked, "each chunk is asked for once");
                assertTrue(count >= 3 && count <= 24, count + " requests");
                assertTrue(store.mostInFlight.get() <= atOnce);
            }
        }
    }

    // ------------------------------------------------------------------ at once

    /** 32 unfiltered chunks of 128 KiB, every other one selected: 16 requests, each chunk too far from the next. */
    @Test
    void requestsAreMadeAtOnceUpToTheNumberAskedFor() throws IOException {
        int chunkElements = 1 << 14;
        int chunkBytes = chunkElements * 8;
        double[] data = noise(32 * chunkElements, 6);
        Path file = dir.resolve("apart.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleChunkedDataset("d", data, new long[] {data.length}, new long[] {chunkElements});
        }
        byte[] bytes = Files.readAllBytes(file);
        double[] expected = new double[16 * chunkElements];
        for (int i = 0; i < 16; i++) {
            System.arraycopy(data, 2 * i * chunkElements, expected, i * chunkElements, chunkElements);
        }
        for (int atOnce : new int[] {1, 4, OpenOptions.DEFAULT_READER_REQUESTS}) {
            Store store = new Store(bytes);
            CyclicBarrier together = new CyclicBarrier(atOnce);
            store.each = (position, length) -> {
                if (length != chunkBytes) {
                    return; // the file's metadata, read a page at a time
                }
                try {
                    together.await(30, TimeUnit.SECONDS); // passes only with that many requests in flight
                } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                    throw new IOException("fewer than " + atOnce + " requests at once", e);
                }
            };
            try (Hdf5File h5 = Hdf5File.open(store, OpenOptions.defaults().readerRequests(atOnce))) {
                Dataset dataset = h5.root().dataset("d");
                Selection everyOther = dataset.select(new long[] {0}, new long[] {2L * chunkElements}, new long[] {16},
                        new long[] {chunkElements});
                Thread reading = Thread.currentThread();
                store.reset();
                assertArrayEquals(expected, everyOther.readDoubles());
                int chunkRequests = 0;
                for (int i = 0; i < store.requests.size(); i++) {
                    if (store.requests.get(i)[1] == chunkBytes) {
                        chunkRequests++;
                        Thread thread = store.threads.get(i);
                        assertTrue(thread == reading || thread.isVirtual(), thread.toString());
                        assertTrue(atOnce > 1 || thread == reading, "one at a time: the reading thread alone");
                    }
                }
                assertEquals(16, chunkRequests);
                assertEquals(atOnce, store.mostInFlight.get());
                assertEquals(0, store.inFlight.get());
            }
        }
    }

    // ------------------------------------------------------------------ failures

    /**
     * Every other chunk of 32 is read, each by a request of its own. The store fails the request for chunk
     * 20, and chunk 6 is damaged: the read fails with chunk 6's checksum, as it does read one chunk at a
     * time, whatever is in flight when chunk 20's request fails. Undamaged, it fails with the store's failure.
     */
    @Test
    void aReadFailsWhereItFailsOneRequestAtATime() throws IOException {
        int chunkElements = 1 << 14;
        double[] data = noise(32 * chunkElements, 7);
        Path file = dir.resolve("failing.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleChunkedDataset("d", data, new long[] {data.length}, new long[] {chunkElements}).fletcher32();
        }
        byte[] sound = Files.readAllBytes(file);
        ChunkRecord[] chunks = new ChunkRecord[32];
        try (Hdf5File h5 = Hdf5File.open(sound)) {
            for (ChunkRecord chunk : chunksOf(h5.root().dataset("d"))) {
                chunks[(int) (chunk.offset()[0] / chunkElements)] = chunk;
            }
        }
        byte[] damaged = sound.clone();
        damaged[(int) chunks[6].address() + 1000] ^= 0x21;
        long unreadable = chunks[20].address() + 5;
        Store.Each failing = (position, length) -> {
            if (position <= unreadable && unreadable < position + length) {
                throw new IOException("the store fails");
            }
            if (length > PAGE) {
                try {
                    Thread.sleep(2); // chunk 20's request fails while others are in flight
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        long[] start = {0};
        long[] stride = {2L * chunkElements};
        long[] block = {chunkElements};

        String checksum;
        try (Hdf5File h5 = Hdf5File.open(damaged, OpenOptions.defaults().decodeThreads(1))) {
            Dataset dataset = h5.root().dataset("d");
            checksum = assertThrows(HdfFormatException.class,
                    () -> dataset.select(start, stride, new long[] {16}, block).readDoubles()).getMessage();
            assertTrue(checksum.contains("fletcher32"), checksum);
        }
        for (int atOnce : new int[] {1, 3, 8, 32}) {
            for (int threads : new int[] {1, 8}) {
                OpenOptions options = OpenOptions.defaults().readerRequests(atOnce).decodeThreads(threads).chunkCacheSize(0);
                Store store = new Store(damaged);
                try (Hdf5File h5 = Hdf5File.open(store, options)) {
                    Dataset dataset = h5.root().dataset("d");
                    store.each = failing;
                    for (int round = 0; round < 10; round++) {
                        assertEquals(checksum, assertThrows(HdfFormatException.class,
                                () -> dataset.select(start, stride, new long[] {16}, block).readDoubles()).getMessage(),
                                options.toString());
                        assertEquals(0, store.inFlight.get(), "nothing is still reading when the read has failed");
                    }
                }
                store = new Store(sound);
                try (Hdf5File h5 = Hdf5File.open(store, options)) {
                    Dataset dataset = h5.root().dataset("d");
                    store.each = failing;
                    for (int round = 0; round < 10; round++) {
                        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                                () -> dataset.select(start, stride, new long[] {16}, block).readDoubles());
                        assertEquals("the store fails", failure.getCause().getMessage());
                        assertEquals(0, store.inFlight.get());
                    }
                    // what the failing request does not hold still reads
                    double[] firstTen = dataset.select(start, stride, new long[] {10}, block).readDoubles();
                    for (int i = 0; i < 10; i++) {
                        assertArrayEquals(Arrays.copyOfRange(data, 2 * i * chunkElements, (2 * i + 1) * chunkElements),
                                Arrays.copyOfRange(firstTen, i * chunkElements, (i + 1) * chunkElements));
                    }
                }
            }
        }
    }

    /**
     * A chunk whose index entry puts it outside the file fails the read as it does in a file in memory, with
     * the chunk's own place in the message, though its neighbours are read in one request.
     */
    @Test
    void aChunkRecordedOutsideTheFileFailsAsItDoesInMemory() throws IOException {
        double[] data = noise(256 * 250, 8);
        Path file = dir.resolve("outside.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, Hdf5Writer.Format.EARLIEST)) {
            w.doubleChunkedDataset("d", data, new long[] {256, 250}, new long[] {16, 32}).deflate(1);
        }
        byte[] bytes = Files.readAllBytes(file);
        ChunkRecord victim;
        try (Hdf5File h5 = Hdf5File.open(bytes)) {
            victim = chunksOf(h5.root().dataset("d")).get(40);
        }
        // The chunk's address in its B-tree node (a version 1 B-tree has no checksum), moved past the file's end.
        byte[] address = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(victim.address()).array();
        int at = -1;
        for (int i = 0; i + 8 <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + 8, address, 0, 8)) {
                assertEquals(-1, at, "the address is stored once");
                at = i;
            }
        }
        assertTrue(at > 0);
        ByteBuffer.wrap(bytes, at, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(bytes.length - 10L);
        String expected;
        try (Hdf5File h5 = Hdf5File.open(bytes, OpenOptions.defaults().decodeThreads(1))) {
            expected = assertThrows(HdfFormatException.class, () -> h5.root().dataset("d").readDoubles()).getMessage();
            assertTrue(expected.contains("out of bounds"), expected);
        }
        for (int atOnce : new int[] {1, 8}) {
            for (int threads : new int[] {1, 8}) {
                OpenOptions options = OpenOptions.defaults().readerRequests(atOnce).decodeThreads(threads);
                try (Hdf5File h5 = Hdf5File.open(new Store(bytes), options)) {
                    assertEquals(expected,
                            assertThrows(HdfFormatException.class, () -> h5.root().dataset("d").readDoubles()).getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------ the option

    @Test
    void readerRequestsIsAnOption() {
        assertEquals(8, OpenOptions.DEFAULT_READER_REQUESTS);
        assertEquals(8, OpenOptions.defaults().readerRequests());
        OpenOptions three = OpenOptions.defaults().readerRequests(3);
        assertEquals(3, three.readerRequests());
        assertEquals(3, three.chunkCacheSize(0).objectCacheSize(1).readerPageSize(1024).readerCacheSize(0)
                .virtualPrintfGap(2).virtualView(OpenOptions.VirtualView.FIRST_MISSING).decodeThreads(5)
                .externalFileAccess(ExternalFileAccess.sameDirectory()).readerRequests(), "the other setters keep it");
        assertEquals(5, OpenOptions.defaults().decodeThreads(5).readerRequests(3).decodeThreads());
        assertTrue(three.toString().contains("readerRequests=3"), three.toString());
        assertThrows(IllegalArgumentException.class, () -> OpenOptions.defaults().readerRequests(0));
        assertThrows(IllegalArgumentException.class, () -> OpenOptions.defaults().readerRequests(-2));
    }
}
