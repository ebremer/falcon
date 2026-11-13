package com.ebremer.falcon.hdf5.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.RangeReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link RangeBatch} (P2 PF11): the ranges of a file read on demand are read in as few requests as their
 * places allow, several requests at once, with the same bytes and the same failures as one at a time.
 */
@Timeout(120)
class RangeBatchTest {

    private static final int PAGE = 4096;
    private static final byte[] FILE = new byte[3 << 20];

    static {
        new Random(11).nextBytes(FILE);
    }

    /** A file in memory that notes each request made of it, and can be slow, or fail, at chosen places. */
    private static final class Store implements RangeReader {
        final List<long[]> requests = Collections.synchronizedList(new ArrayList<>()); // {position, length}
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger mostInFlight = new AtomicInteger();
        volatile Request each = (position, length) -> { };

        interface Request {
            void made(long position, int length) throws IOException;
        }

        @Override
        public long size() {
            return FILE.length;
        }

        @Override
        public void read(long position, ByteBuffer destination) throws IOException {
            int length = destination.remaining();
            requests.add(new long[] {position, length});
            threads.add(Thread.currentThread());
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                each.made(position, length);
                destination.put(FILE, (int) position, length);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        /** The requests made, in the order of their positions. */
        List<String> sorted() {
            List<long[]> copy;
            synchronized (requests) {
                copy = new ArrayList<>(requests);
            }
            copy.sort((a, b) -> Long.compare(a[0], b[0]));
            List<String> out = new ArrayList<>();
            for (long[] request : copy) {
                out.add(request[0] + "+" + request[1]);
            }
            return out;
        }
    }

    private static HdfBuffer buffer(Store store) throws IOException {
        return new HdfBuffer(PagedSource.open(store, PAGE, 16L * PAGE, 8));
    }

    private static RangeBatch batch(Store store, int requests, long budget) throws IOException {
        return new RangeBatch(buffer(store), PAGE, requests, budget);
    }

    private static byte[] stored(long offset, int length) {
        return Arrays.copyOfRange(FILE, (int) offset, (int) offset + length);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------------------- what is asked for

    @Test
    void rangesNextToEachOtherAreOneRequest() throws IOException {
        Store store = new Store();
        RangeBatch batch = batch(store, 8, 64 << 20);
        for (int i = 0; i < 100; i++) {
            assertTrue(batch.add(10_000 + i * 3000L, 3000));
        }
        assertEquals(100, batch.size());
        batch.read();
        assertEquals(1, batch.requests());
        assertEquals(List.of("10000+300000"), store.sorted());
        for (int i = 0; i < 100; i++) {
            assertArrayEquals(stored(10_000 + i * 3000L, 3000), batch.bytes(i), "range " + i);
        }
    }

    @Test
    void rangesNoMoreThanAPageApartAreOneRequestAndFartherApartAreTwo() throws IOException {
        Store store = new Store();
        RangeBatch batch = batch(store, 1, 64 << 20);
        batch.add(100_000, 5000);                 // ends at 105,000
        batch.add(105_000 + PAGE, 5000);          // a page after: the same request, the page between read too
        batch.add(114_096 + PAGE + 1, 5000);      // a page and a byte after: a request of its own
        batch.read();
        assertEquals(2, batch.requests());
        assertEquals(List.of("100000+14096", "118193+5000"), store.sorted());
        assertArrayEquals(stored(100_000, 5000), batch.bytes(0));
        assertArrayEquals(stored(109_096, 5000), batch.bytes(1));
        assertArrayEquals(stored(118_193, 5000), batch.bytes(2));
    }

    @Test
    void aRequestIsHeldToItsSizeAndALargerRangeIsOneOfItsOwn() throws IOException {
        Store store = new Store();
        // A budget of 2 MiB for two requests at once: each of at most 1 MiB, the least a request is held to.
        RangeBatch batch = batch(store, 2, 2 << 20);
        for (int i = 0; i < 6; i++) {
            assertTrue(batch.add(i * 300_000L, 300_000)); // 1.8 MB next to each other
        }
        batch.read();
        assertEquals(2, batch.requests());
        assertEquals(List.of("0+900000", "900000+900000"), store.sorted(), "split between ranges, three to a request");
        for (int i = 0; i < 6; i++) {
            assertArrayEquals(stored(i * 300_000L, 300_000), batch.bytes(i));
        }

        store.requests.clear();
        batch.clear();
        assertTrue(batch.add(0, 100_000));
        assertTrue(batch.add(100_000, 1_500_000), "larger than a request: still read, alone");
        assertTrue(batch.add(1_600_000, 100_000));
        batch.read();
        assertEquals(List.of("0+100000", "100000+1500000", "1600000+100000"), store.sorted());
        assertArrayEquals(stored(100_000, 1_500_000), batch.bytes(1));
        assertArrayEquals(stored(0, 100_000), batch.bytes(0));
        assertArrayEquals(stored(1_600_000, 100_000), batch.bytes(2));
    }

    @Test
    void theBatchTakesRangesUpToItsBudgetAndItsFirstWhateverItsLength() throws IOException {
        Store store = new Store();
        RangeBatch batch = batch(store, 4, 250_000);
        assertTrue(batch.add(0, 100_000));
        assertTrue(batch.add(500_000, 100_000));
        assertFalse(batch.add(900_000, 100_000), "a third would pass the budget");
        assertEquals(2, batch.size());
        assertTrue(batch.add(900_000, 50_000), "a smaller one fits");
        batch.read();
        assertArrayEquals(stored(900_000, 50_000), batch.bytes(2));

        batch.clear();
        assertEquals(0, batch.size());
        assertTrue(batch.add(0, 2_000_000), "the first, though larger than the budget");
        assertFalse(batch.add(2_000_000, 1));
        batch.read();
        assertArrayEquals(stored(0, 2_000_000), batch.bytes(0));
    }

    @Test
    void rangesInAnyOrderOverlappingAndEmptyAreReadRight() throws IOException {
        Store store = new Store();
        RangeBatch batch = batch(store, 3, 64 << 20);
        Random random = new Random(5);
        List<long[]> ranges = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            long offset = random.nextInt(FILE.length - 100_000);
            int length = i % 50 == 0 ? 0 : 1 + random.nextInt(i % 7 == 0 ? 60_000 : 3000);
            ranges.add(new long[] {offset, length});
            if (i % 9 == 0) {
                ranges.add(new long[] {offset, length});               // the same again
                ranges.add(new long[] {offset + length / 2, length});  // and one overlapping it
            }
        }
        ranges.add(new long[] {FILE.length - 10, 10});                 // the file's last bytes
        ranges.add(new long[] {FILE.length, 0});                       // and nothing, at its end
        for (long[] range : ranges) {
            assertTrue(batch.add(range[0], (int) range[1]));
        }
        batch.read();
        assertTrue(batch.requests() < ranges.size(), batch.requests() + " requests for " + ranges.size() + " ranges");
        for (int i = 0; i < ranges.size(); i++) {
            assertArrayEquals(stored(ranges.get(i)[0], (int) ranges.get(i)[1]), batch.bytes(i), "range " + i);
        }
        // Each range's bytes are the caller's own: taken once, and no part of another's.
        assertThrows(IllegalStateException.class, () -> batch.bytes(0));
    }

    @Test
    void anEmptyBatchReadsNothingAndARangeOutsideTheFileIsRefused() throws IOException {
        Store store = new Store();
        RangeBatch batch = batch(store, 8, 64 << 20);
        batch.read();
        assertEquals(0, batch.requests());
        assertTrue(store.requests.isEmpty());
        assertThrows(IllegalStateException.class, () -> batch.bytes(0));
        assertThrows(IllegalArgumentException.class, () -> batch.add(FILE.length - 5, 6));
        assertThrows(IllegalArgumentException.class, () -> batch.add(-1, 6));
        assertThrows(IllegalArgumentException.class, () -> batch.add(5, -6));
        assertThrows(IllegalArgumentException.class, () -> batch.add(Long.MAX_VALUE, 6));
        assertEquals(0, batch.size());
        batch.add(7, 9);
        assertThrows(IllegalStateException.class, () -> batch.bytes(0), "not read yet");
    }

    @Test
    void aRequestOfLessThanAPageComesFromTheCachedPagesWhereThatIsNoMoreThanOneRead() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store);
        RangeBatch batch = new RangeBatch(buffer, PAGE, 4, 64 << 20);
        batch.add(PAGE * 10L + 100, 200);
        batch.add(PAGE * 10L + 300, 200);       // together 400 bytes of one page: the page is read, and kept
        batch.add(PAGE * 50L + 4000, 500);      // across two pages, neither cached: one request, not two
        batch.read();
        assertEquals(2, batch.requests());
        assertEquals(List.of(PAGE * 10L + "+" + PAGE, (PAGE * 50L + 4000) + "+500"), store.sorted());
        assertArrayEquals(stored(PAGE * 10L + 300, 200), batch.bytes(1));
        assertArrayEquals(stored(PAGE * 50L + 4000, 500), batch.bytes(2));

        store.requests.clear();
        RangeBatch again = buffer.batch();
        again.add(PAGE * 10L + 1000, 3000);     // in the cached page: nothing is read
        again.add(PAGE * 11L - 100, 300);       // across it and the next page: only that one is read, and kept
        again.add(PAGE * 30L, 0);               // nothing
        again.read();
        assertEquals(List.of(PAGE * 11L + "+" + PAGE), store.sorted());
        assertArrayEquals(stored(PAGE * 10L + 1000, 3000), again.bytes(0));
        assertArrayEquals(stored(PAGE * 11L - 100, 300), again.bytes(1));
        assertEquals(0, again.bytes(2).length);

        store.requests.clear();
        assertArrayEquals(stored(PAGE * 11L + 5, 50), buffer.getBytes(PAGE * 11L + 5, 50));
        assertTrue(store.requests.isEmpty(), "the page read for the batch serves other reads");
    }

    // --------------------------------------------------------------- by whom, and how many at once

    @Test
    void severalRequestsAreMadeAtOnceAndNoMoreThanAskedFor() throws IOException {
        Store store = new Store();
        CyclicBarrier four = new CyclicBarrier(4);
        store.each = (position, length) -> {
            try {
                four.await(30, TimeUnit.SECONDS); // passes only when four requests are in flight together
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IOException("fewer than four requests at once", e);
            }
            pause(5);
        };
        RangeBatch batch = batch(store, 4, 64 << 20);
        for (int i = 0; i < 12; i++) {
            batch.add(i * 100_000L, PAGE * 2); // far apart: a request each
        }
        Thread caller = Thread.currentThread();
        batch.read();
        assertEquals(12, batch.requests());
        for (int i = 0; i < 12; i++) {
            assertArrayEquals(stored(i * 100_000L, PAGE * 2), batch.bytes(i));
        }
        assertEquals(4, store.mostInFlight.get());
        assertTrue(store.threads.contains(caller), "the calling thread makes requests too");
        assertEquals(4, store.threads.stream().distinct().count());
        for (Thread thread : store.threads) {
            assertTrue(thread == caller || thread.isVirtual(), thread.toString());
            assertFalse(thread.isAlive() && thread != caller, "nothing is still reading: " + thread);
        }
    }

    @Test
    void oneRequestAndOneRequestAtATimeAreMadeByTheCallingThread() throws IOException {
        Store store = new Store();
        RangeBatch batch = batch(store, 8, 64 << 20);
        batch.add(50_000, 10_000);
        batch.add(60_000, 10_000);
        batch.read();
        assertEquals(List.of(Thread.currentThread()), store.threads, "one request: no other thread");

        Store serial = new Store();
        RangeBatch one = batch(serial, 1, 64 << 20);
        // Added out of the file's order: the requests are made in the order the ranges are wanted.
        long[] offsets = {900_000, 100_000, 500_000, 110_000, 300_000};
        for (long offset : offsets) {
            one.add(offset, 10_000);
        }
        one.read();
        assertEquals(4, one.requests());
        assertEquals(1, serial.mostInFlight.get());
        assertEquals(1, serial.threads.stream().distinct().count());
        assertSame(Thread.currentThread(), serial.threads.get(0));
        List<Long> positions = new ArrayList<>();
        for (long[] request : serial.requests) {
            positions.add(request[0]);
        }
        assertEquals(List.of(900_000L, 100_000L, 500_000L, 300_000L), positions);
        for (int i = 0; i < offsets.length; i++) {
            assertArrayEquals(stored(offsets[i], 10_000), one.bytes(i));
        }
    }

    @Test
    void anInterruptedCallerStillWaitsForItsRequests() throws IOException {
        Store store = new Store();
        store.each = (position, length) -> pause(position == 0 ? 0 : 20);
        RangeBatch batch = batch(store, 4, 64 << 20);
        for (int i = 0; i < 8; i++) {
            batch.add(i * 100_000L, PAGE * 2);
        }
        Thread.currentThread().interrupt();
        try {
            batch.read();
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for the caller");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, store.inFlight.get());
        assertEquals(8, store.requests.size());
        assertArrayEquals(stored(700_000, PAGE * 2), batch.bytes(7));
    }

    // --------------------------------------------------------------- failures

    @Test
    void aFailedRequestFailsItsRangesWhenTheyAreTaken() throws IOException {
        for (int requests : new int[] {1, 2, 8}) {
            Store store = new Store();
            store.each = (position, length) -> {
                if (position <= 400_000 && 400_000 < position + length) {
                    throw new IOException("no byte 400000");
                }
            };
            RangeBatch batch = batch(store, requests, 64 << 20);
            batch.add(0, 10_000);
            batch.add(200_000, 10_000);
            batch.add(390_000, 10_000);   // in one request with the next range, which cannot be read
            batch.add(400_000, 10_000);
            batch.add(600_000, 10_000);
            batch.read();                 // does not throw
            assertArrayEquals(stored(0, 10_000), batch.bytes(0));
            assertArrayEquals(stored(200_000, 10_000), batch.bytes(1));
            UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> batch.bytes(2));
            assertEquals("no byte 400000", failure.getCause().getMessage());
            assertTrue(failure.getMessage().contains("20000 bytes at 390000"), failure.getMessage());
            assertSame(failure, assertThrows(UncheckedIOException.class, () -> batch.bytes(3)));
            assertEquals(0, store.inFlight.get());
        }
    }

    @Test
    void noRequestIsStartedAfterOneHasFailed() throws IOException {
        Store store = new Store();
        store.each = (position, length) -> {
            if (position == 0) {
                throw new IOException("the store does not answer");
            }
            pause(100);
        };
        RangeBatch batch = batch(store, 2, 64 << 20);
        for (int i = 0; i < 40; i++) {
            batch.add(i * 50_000L, PAGE * 2);
        }
        batch.read();
        assertTrue(store.requests.size() <= 2, store.requests.size() + " requests made");
        assertEquals("the store does not answer",
                assertThrows(UncheckedIOException.class, () -> batch.bytes(0)).getCause().getMessage());

        Store serial = new Store();
        serial.each = store.each;
        RangeBatch one = batch(serial, 1, 64 << 20);
        for (int i = 0; i < 40; i++) {
            one.add(i * 50_000L, PAGE * 2);
        }
        one.read();
        assertEquals(1, serial.requests.size());
        assertThrows(UncheckedIOException.class, () -> one.bytes(0));
    }

    /**
     * Two requests fail, the later one first: the ranges, taken in turn, still fail at the earlier one, which
     * is where they fail read one request at a time.
     */
    @Test
    void theFirstRangeToFailIsTheOneThatFailsOneRequestAtATime() throws IOException {
        for (int requests : new int[] {1, 3, 8}) {
            for (int round = 0; round < 10; round++) {
                Store store = new Store();
                store.each = (position, length) -> {
                    if (position == 200_000) {
                        pause(30);
                        throw new IOException("slow to fail");
                    }
                    if (position == 500_000) {
                        throw new IOException("quick to fail");
                    }
                };
                RangeBatch batch = batch(store, requests, 64 << 20);
                for (int i = 0; i < 10; i++) {
                    batch.add(i * 100_000L, PAGE * 2);
                }
                batch.read();
                assertArrayEquals(stored(0, PAGE * 2), batch.bytes(0));
                assertArrayEquals(stored(100_000, PAGE * 2), batch.bytes(1));
                assertEquals("slow to fail",
                        assertThrows(UncheckedIOException.class, () -> batch.bytes(2)).getCause().getMessage(),
                        requests + " requests at once");
            }
        }
    }

    @Test
    void anErrorIsThrownAsItIs() throws IOException {
        Store store = new Store();
        StackOverflowError error = new StackOverflowError("deep");
        store.each = (position, length) -> {
            if (position == 100_000) {
                throw error;
            }
        };
        RangeBatch batch = batch(store, 4, 64 << 20);
        batch.add(0, PAGE * 2);
        batch.add(100_000, PAGE * 2);
        batch.read();
        assertSame(error, assertThrows(StackOverflowError.class, () -> batch.bytes(1)));
    }

    // --------------------------------------------------------------- which buffers have one

    @Test
    void onlyBytesReadOnDemandAreReadInBatches() throws IOException {
        assertNull(HdfBuffer.of(FILE).batch(), "in memory: reading a range is a copy");
        assertNull(HdfBuffer.of(FILE).slice(10, 100).batch());
        assertNull(HdfBuffer.of(FILE).redirected(new long[] {10}, new long[] {5}, new long[] {100}).batch());

        Store store = new Store();
        HdfBuffer whole = buffer(store);
        assertNotNull(whole.batch());

        // A view that starts within the file (a file with a user block): its ranges are counted from there.
        HdfBuffer view = whole.slice(1000, FILE.length - 1000);
        RangeBatch batch = view.batch();
        batch.add(0, 20_000);
        batch.add(20_000, 20_000);
        assertThrows(IllegalArgumentException.class, () -> batch.add(FILE.length - 1000, 1));
        batch.read();
        assertEquals(List.of("1000+40000"), store.sorted());
        assertArrayEquals(stored(21_000, 20_000), batch.bytes(1));

        // Bytes read from elsewhere in the file (a metadata cache image's) are read from there.
        HdfBuffer redirected = whole.redirected(new long[] {50_000}, new long[] {30_000}, new long[] {900_000});
        RangeBatch other = redirected.batch();
        assertNotNull(other);
        other.add(40_000, 10_000);
        other.add(50_000, 30_000);
        other.add(80_000, 10_000);
        other.read();
        assertArrayEquals(stored(40_000, 10_000), other.bytes(0));
        assertArrayEquals(stored(900_000, 30_000), other.bytes(1));
        assertArrayEquals(stored(80_000, 10_000), other.bytes(2));
    }

    @Test
    void theDefaultBudgetFillsEveryRequestAndIsHeldToTheHeap() throws IOException {
        Store store = new Store();
        RangeBatch batch = buffer(store).batch(); // eight requests at once
        long heapShare = Runtime.getRuntime().maxMemory() / 16;
        long budget = Math.min(heapShare, 8 * RangeBatch.MAX_REQUEST);
        // Ranges of nothing but their length count against the budget: none is read here.
        int taken = 0;
        while (batch.add(0, 1 << 20)) {
            taken++;
            assertTrue(taken <= 64, "the budget is at most eight requests of 8 MiB");
        }
        assertEquals(Math.max(1, budget >> 20), taken);
    }
}
