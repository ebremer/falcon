package com.ebremer.falcon.hdf5.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link HdfBuffer#ahead} (P2 PF12): the pages a caller says it is about to read are read together, those
 * next to each other in one request and several requests at once, no more of them than the page cache keeps
 * for it; the reads that follow make no request, return the same bytes, and fail as they did.
 */
@Timeout(120)
class ReadAheadTest {

    private static final int PAGE = 4096;
    private static final byte[] FILE = new byte[(3 << 20) + 1000]; // its last page a short one

    static {
        new Random(12).nextBytes(FILE);
    }

    /** A file in memory that notes each request made of it, and can wait, or fail, at chosen places. */
    private static class Store implements RangeReader {
        final byte[] file;
        final List<long[]> requests = Collections.synchronizedList(new ArrayList<>()); // {position, length}
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger mostInFlight = new AtomicInteger();
        volatile Request each = (position, length) -> { };

        interface Request {
            void made(long position, int length) throws IOException;
        }

        Store() {
            this(FILE);
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
            requests.add(new long[] {position, length});
            threads.add(Thread.currentThread());
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                each.made(position, length);
                destination.put(file, (int) position, length);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        /** The requests made, in the order of their positions, each as {@code position+length}. */
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

    /** The store's bytes read on demand, {@code cachePages} pages kept, {@code requests} requests at once. */
    private static HdfBuffer buffer(Store store, int cachePages, int requests) throws IOException {
        return new HdfBuffer(PagedSource.open(store, PAGE, (long) cachePages * PAGE, requests));
    }

    private static byte[] stored(long offset, int length) {
        return Arrays.copyOfRange(FILE, (int) offset, (int) offset + length);
    }

    private static String pages(long first, long count) {
        return first * PAGE + "+" + count * PAGE;
    }

    // ----------------------------------------------------------------- what is read

    @Test
    void whatIsReadAheadIsReadAtOnceAndNotAgain() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 64, 8);
        assertTrue(buffer.readOnDemand());
        // Six places far apart; the third lies across the end of its page.
        long[] offsets = {10L * PAGE + 100, 50L * PAGE, 30L * PAGE + 4000, 90L * PAGE + 7, 200L * PAGE, 400L * PAGE + 1};
        CountDownLatch together = new CountDownLatch(offsets.length);
        store.each = (position, length) -> { // no request is answered until all six have been made
            together.countDown();
            try {
                if (!together.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("made alone");
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        };
        assertEquals(6, buffer.ahead(offsets, 0, 6, 200));
        assertEquals(List.of(pages(10, 1), pages(30, 2), pages(50, 1), pages(90, 1), pages(200, 1), pages(400, 1)),
                store.sorted());
        assertEquals(6, store.mostInFlight.get());
        assertEquals(6, new HashSet<>(store.threads).size(), "each by a thread of its own");
        assertTrue(store.threads.contains(Thread.currentThread()), "one of them the caller's");
        for (Thread thread : store.threads) {
            assertTrue(thread == Thread.currentThread() || thread.isVirtual());
        }
        // The reads that follow find their pages there.
        store.each = (position, length) -> { };
        for (long offset : offsets) {
            assertArrayEquals(stored(offset, 200), buffer.getBytes(offset, 200));
            assertEquals(FILE[(int) offset], buffer.getByte(offset));
        }
        assertEquals(6, store.requests.size());
        // And what is there is not read ahead again.
        assertEquals(6, buffer.ahead(offsets, 0, 6, 200));
        assertEquals(6, store.requests.size());
    }

    @Test
    void pagesNextToEachOtherOrWithOneBetweenAreOneRequest() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 64, 1);
        // Pages 3, 4, 5 and 6 (the third range lies across two), 8 (one page between), and 11 (two between).
        long[] offsets = {11L * PAGE, 3L * PAGE, 4L * PAGE + 5, 5L * PAGE + 4000, 8L * PAGE + 9};
        assertEquals(5, buffer.ahead(offsets, 0, 5, 200));
        assertEquals(List.of(pages(3, 6), pages(11, 1)), store.sorted());
        assertEquals(1, store.mostInFlight.get());
        for (Thread thread : store.threads) {
            assertSame(Thread.currentThread(), thread, "one request at a time is the caller's own");
        }
        for (long page = 3; page <= 8; page++) { // the page between, 7, is there too
            assertArrayEquals(stored(page * PAGE + 17, 300), buffer.getBytes(page * PAGE + 17, 300));
        }
        assertArrayEquals(stored(11L * PAGE, PAGE - 1), buffer.getBytes(11L * PAGE, PAGE - 1));
        assertEquals(2, store.requests.size());
    }

    @Test
    void aRequestIsHeldToItsSize() throws IOException {
        byte[] file = new byte[11 << 20];
        new Random(3).nextBytes(file);
        Store store = new Store(file);
        int megabyte = 1 << 20;
        // Pages of 1 MiB, 64 of them kept: ten in a row are more than a request of 8 MiB takes.
        HdfBuffer buffer = new HdfBuffer(PagedSource.open(store, megabyte, 64L * megabyte, 1));
        assertEquals(1, buffer.ahead(new long[] {0}, 0, 1, 10L * megabyte));
        assertEquals(List.of("0+" + 8 * megabyte, 8 * megabyte + "+" + 2 * megabyte), store.sorted());
        assertArrayEquals(Arrays.copyOfRange(file, 9 * megabyte - 50, 9 * megabyte + 50), buffer.getBytes(9L * megabyte - 50, 100));
        assertEquals(2, store.requests.size());
    }

    @Test
    void theLastPageOfTheFileIsShort() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 64, 8);
        long last = FILE.length / PAGE; // the page the file ends in
        assertEquals(2, buffer.ahead(new long[] {FILE.length - 10, (last - 2) * PAGE + 4090}, 0, 2, 500));
        assertEquals(List.of((last - 2) * PAGE + "+" + (FILE.length - (last - 2) * PAGE)), store.sorted());
        assertArrayEquals(stored(FILE.length - 10, 10), buffer.getBytes(FILE.length - 10, 10));
        assertEquals(1, store.requests.size());
    }

    // ----------------------------------------------------------------- how much

    @Test
    void noMoreIsReadAheadThanAQuarterOfThePagesKept() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 16, 8); // sixteen pages kept: four read ahead at a time
        long[] offsets = new long[10];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = (20L + 10 * i) * PAGE + i;
        }
        assertEquals(4, buffer.ahead(offsets, 0, 10, 100));
        assertEquals(4, store.requests.size());
        assertEquals(4, buffer.ahead(offsets, 4, 10, 100));
        assertEquals(8, store.requests.size());
        // What is there takes no room: the eight are passed over, and the two after them read.
        assertEquals(10, buffer.ahead(offsets, 0, 10, 100));
        assertEquals(10, store.requests.size());
        for (long offset : offsets) {
            assertArrayEquals(stored(offset, 100), buffer.getBytes(offset, 100));
        }
        assertEquals(10, store.requests.size());
        // A range longer than that is cut to it: its first four pages.
        assertEquals(1, buffer.ahead(new long[] {300L * PAGE + 5}, 0, 1, 9L * PAGE));
        assertEquals(11, store.requests.size());
        assertTrue(store.sorted().contains(pages(300, 4)), store.sorted().toString());
        // A range is taken whole or left: two pages do not fit beside three.
        Store other = new Store();
        HdfBuffer small = buffer(other, 16, 8);
        long[] ranges = {40L * PAGE, 50L * PAGE, 60L * PAGE, 70L * PAGE + 4000, 80L * PAGE};
        assertEquals(3, small.ahead(ranges, 0, 5, 200));
        assertEquals(List.of(pages(40, 1), pages(50, 1), pages(60, 1)), other.sorted());
        assertEquals(2, small.ahead(ranges, 3, 5, 200));
        assertEquals(List.of(pages(40, 1), pages(50, 1), pages(60, 1), pages(70, 2), pages(80, 1)), other.sorted());
    }

    @Test
    void aCacheTooSmallToKeepThemReadsNothingAhead() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 3, 8);
        long[] offsets = {10L * PAGE, 20L * PAGE, 30L * PAGE};
        assertEquals(3, buffer.ahead(offsets, 0, 3, 100)); // all taken: there is nothing more to say of them
        assertEquals(0, store.requests.size());
        assertArrayEquals(stored(20L * PAGE, 100), buffer.getBytes(20L * PAGE, 100));
        assertEquals(1, store.requests.size());
    }

    @Test
    void noMoreRequestsAreMadeAtOnceThanAskedFor() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 256, 3);
        long[] offsets = new long[24];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = (5L + 7 * i) * PAGE;
        }
        store.each = (position, length) -> { // each waits a while for two others
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (store.inFlight.get() < 3 && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
        };
        assertEquals(24, buffer.ahead(offsets, 0, 24, 100));
        assertEquals(24, store.requests.size());
        assertEquals(3, store.mostInFlight.get());
    }

    // ----------------------------------------------------------------- what is not there

    @Test
    void aRangeOutsideTheFileIsPassedOver() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 64, 8);
        long[] offsets = {-1, FILE.length, FILE.length + 5L, Long.MAX_VALUE, 7L * PAGE, Long.MIN_VALUE};
        assertEquals(6, buffer.ahead(offsets, 0, 6, 100));
        assertEquals(List.of(pages(7, 1)), store.sorted());
        assertEquals(0, buffer.ahead(offsets, 3, 3, 100));
        assertEquals(2, buffer.ahead(offsets, 0, 2, 0)); // no bytes: nothing to read
        assertEquals(1, buffer.ahead(new long[] {9L * PAGE}, 0, 1, -5));
        assertEquals(1, store.requests.size());
        // Its read says so, as it always has.
        assertThrows(com.ebremer.falcon.hdf5.HdfFormatException.class, () -> buffer.getBytes(FILE.length, 1));
    }

    @Test
    void aRequestThatFailsIsLeftToTheReadThatNeedsIt() throws IOException {
        Store store = new Store();
        HdfBuffer buffer = buffer(store, 64, 1);
        long[] offsets = {5L * PAGE, 9L * PAGE, 13L * PAGE, 17L * PAGE};
        store.each = (position, length) -> {
            if (position == 9L * PAGE) {
                throw new IOException("the store is down");
            }
        };
        assertEquals(4, buffer.ahead(offsets, 0, 4, 100)); // it does not fail
        assertEquals(List.of(pages(5, 1), pages(9, 1)), store.sorted(), "none is started after one that failed");
        assertArrayEquals(stored(5L * PAGE, 100), buffer.getBytes(5L * PAGE, 100));
        assertEquals(2, store.requests.size());
        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> buffer.getBytes(9L * PAGE, 100));
        assertEquals("the store is down", thrown.getCause().getMessage());
        assertEquals("cannot read " + PAGE + " bytes at " + 9L * PAGE + " of the HDF5 file", thrown.getMessage());
        // What was not read ahead is read in its turn.
        assertArrayEquals(stored(13L * PAGE, 100), buffer.getBytes(13L * PAGE, 100));
        assertEquals(4, store.requests.size());

        // Several at once, and a reader that fails without an IOException: dropped as well.
        Store wild = new Store();
        HdfBuffer other = buffer(wild, 64, 8);
        wild.each = (position, length) -> {
            if (position == 13L * PAGE) {
                throw new IllegalStateException("not an I/O failure");
            }
        };
        assertEquals(4, other.ahead(offsets, 0, 4, 100));
        assertEquals("not an I/O failure",
                assertThrows(IllegalStateException.class, () -> other.getBytes(13L * PAGE, 100)).getMessage());
        assertArrayEquals(stored(5L * PAGE, 100), other.getBytes(5L * PAGE, 100));
        // A reader that returns too few bytes: its page is not kept.
        Store shortOne = new Store() {
            @Override
            public void read(long position, ByteBuffer destination) throws IOException {
                if (position == 9L * PAGE) {
                    destination.put(file, (int) position, 10);
                    return;
                }
                super.read(position, destination);
            }
        };
        HdfBuffer third = buffer(shortOne, 64, 8);
        assertEquals(4, third.ahead(offsets, 0, 4, 100));
        assertThrows(UncheckedIOException.class, () -> third.getBytes(9L * PAGE, 100));
    }

    @Test
    void aClosedFileReadsNothingAhead() throws IOException {
        Store store = new Store();
        PagedSource source = PagedSource.open(store, PAGE, 64L * PAGE, 8);
        HdfBuffer buffer = new HdfBuffer(source);
        source.close();
        assertEquals(2, buffer.ahead(new long[] {5L * PAGE, 9L * PAGE}, 0, 2, 100));
        assertEquals(0, store.requests.size());
    }

    // ----------------------------------------------------------------- other buffers

    @Test
    void mappedOrInMemoryThereIsNothingToReadAhead() {
        HdfBuffer buffer = HdfBuffer.of(new byte[1000]);
        assertFalse(buffer.readOnDemand());
        assertEquals(3, buffer.ahead(new long[] {0, 500, 5000}, 0, 3, 100));
        assertEquals(1, buffer.ahead(new long[] {0, 500, 5000}, 2, 3, 100));
        assertEquals(0, buffer.ahead(new long[] {0}, 1, 1, 100));
        HdfBuffer redirected = buffer.redirected(new long[] {100}, new long[] {50}, new long[] {600});
        assertFalse(redirected.readOnDemand());
        assertEquals(2, redirected.ahead(new long[] {110, 400}, 0, 2, 10));
    }

    @Test
    void aSliceReadsAheadWhereItsBytesAre() throws IOException {
        Store store = new Store();
        HdfBuffer slice = buffer(store, 64, 8).slice(100L * PAGE, 50L * PAGE);
        assertTrue(slice.readOnDemand());
        // The third is past the slice's end, though within the file.
        assertEquals(3, slice.ahead(new long[] {2L * PAGE + 9, 30L * PAGE, 50L * PAGE}, 0, 3, 100));
        assertEquals(List.of(pages(102, 1), pages(130, 1)), store.sorted());
        assertArrayEquals(stored(102L * PAGE + 9, 100), slice.getBytes(2L * PAGE + 9, 100));
        assertEquals(2, store.requests.size());
    }

    @Test
    void aRedirectedBufferReadsAheadWhereItsBytesAreStored() throws IOException {
        Store store = new Store();
        // The page at 10 pages in is read from 300 pages in.
        HdfBuffer buffer = buffer(store, 64, 8).redirected(new long[] {10L * PAGE}, new long[] {PAGE}, new long[] {300L * PAGE});
        assertTrue(buffer.readOnDemand());
        long[] offsets = {10L * PAGE + 5, 20L * PAGE, 10L * PAGE - 50, 11L * PAGE - 50, FILE.length - 20, -3};
        assertEquals(6, buffer.ahead(offsets, 0, 6, 100));
        // The ranges across an edge of the redirected one, and the one past the file's end, are left to their reads.
        assertEquals(List.of(pages(20, 1), pages(300, 1)), store.sorted());
        assertArrayEquals(stored(300L * PAGE + 5, 100), buffer.getBytes(10L * PAGE + 5, 100));
        assertArrayEquals(stored(20L * PAGE, 100), buffer.getBytes(20L * PAGE, 100));
        assertEquals(2, store.requests.size());
        byte[] across = buffer.getBytes(10L * PAGE - 50, 100);
        assertArrayEquals(stored(10L * PAGE - 50, 50), Arrays.copyOf(across, 50));
        assertArrayEquals(stored(300L * PAGE, 50), Arrays.copyOfRange(across, 50, 100));
    }
}
