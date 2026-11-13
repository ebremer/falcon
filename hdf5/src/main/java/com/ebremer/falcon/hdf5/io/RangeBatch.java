package com.ebremer.falcon.hdf5.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ranges of a file that is read on demand, read together (P2 PF11): the stored bytes of the chunks one read
 * needs, say. Read one at a time, each range costs the store a request and the read a round trip; here the
 * ranges are put into as few requests as their places in the file allow, and several requests are made at
 * once.
 *
 * <ul>
 *   <li><b>Fewer requests.</b> Ranges next to each other in the file, or no more than a page apart (the
 *       bytes between them are read and dropped: less than a round trip costs), are read in one request,
 *       of at most {@link #MAX_REQUEST} bytes. A range larger than that is a request of its own.</li>
 *   <li><b>At once.</b> Up to {@code OpenOptions.readerRequests} requests are in flight: the calling thread
 *       makes them one after another, and virtual threads make the others beside it. One request alone
 *       is made by the calling thread, with no other thread started.</li>
 *   <li><b>The cached pages, where they cost nothing.</b> A request of a page or more goes to the
 *       {@code RangeReader} directly, as any such read does. A smaller one comes from the cached pages when
 *       at most one of those has to be read first (which keeps the page for the small reads around it);
 *       lying across two pages that are not cached, it is read as it is, in one request, not two.</li>
 *   <li><b>Bounded.</b> A batch takes ranges up to a budget of bytes (a sixteenth of the heap, and no more
 *       than fills every request), so reading far ahead of what is used never holds much; the caller reads
 *       a long list of ranges batch after batch.</li>
 * </ul>
 *
 * <p><b>Failures</b> are kept, not thrown by {@link #read()}: {@link #bytes} throws its range's request's
 * failure when the caller comes to that range, so a caller that takes the ranges in the order it added them
 * fails where it would have, reading them one at a time. Requests are started in the order of the first
 * range each holds, and none is started after one has failed; so the first range to fail is the one that
 * fails with a single request at a time, and a store that does not answer costs one wait, not one a
 * request.
 *
 * <p>Not thread-safe: one thread adds, reads, and takes. Internal &mdash; lives in a non-exported package.
 */
public final class RangeBatch {

    /** The most bytes one request asks for, unless one range alone is larger: 8 MiB. */
    static final long MAX_REQUEST = 8L << 20;
    /** The least a request may be held to, however small the budget: 1 MiB. */
    static final long MIN_REQUEST = 1L << 20;

    private final HdfBuffer buffer;  // what the ranges are read from
    private final long gap;          // ranges this far apart, or less, are read together
    private final int requests;      // the most requests at once
    private final long budget;       // the most bytes of ranges in one batch
    private final long requestBytes; // the most bytes in one request

    private long[] offsets = new long[16];
    private int[] lengths = new int[16];
    private int count;
    private long bytes;
    private Request[] requestOf;     // each range's request, once read
    private int made;                // the requests the last read planned

    /**
     * A batch of ranges of {@code buffer}, read with up to {@code requests} requests at once, ranges up to
     * {@code gap} bytes apart in one request.
     */
    RangeBatch(HdfBuffer buffer, long gap, int requests) {
        this(buffer, gap, requests, Math.min(Runtime.getRuntime().maxMemory() / 16, requests * MAX_REQUEST));
    }

    /** As above, with a budget of {@code budget} bytes of ranges a batch. */
    RangeBatch(HdfBuffer buffer, long gap, int requests, long budget) {
        this.buffer = buffer;
        this.gap = gap;
        this.requests = Math.max(1, requests);
        this.budget = budget;
        this.requestBytes = Math.max(MIN_REQUEST, Math.min(MAX_REQUEST, budget / this.requests));
    }

    /**
     * Adds the {@code length} bytes at {@code offset}, which lie within the buffer, unless the batch is
     * full: it takes ranges up to its budget, and its first whatever its length.
     *
     * @return false, the range not added, if the batch is full: read it, take its ranges, and
     *         {@link #clear} it first
     */
    public boolean add(long offset, int length) {
        if (!buffer.holds(offset, length)) {
            throw new IllegalArgumentException(length + " bytes at " + offset + " lie outside the file");
        }
        if (count > 0 && bytes + length > budget) {
            return false;
        }
        if (count == offsets.length) {
            offsets = Arrays.copyOf(offsets, count * 2);
            lengths = Arrays.copyOf(lengths, count * 2);
        }
        offsets[count] = offset;
        lengths[count] = length;
        count++;
        bytes += length;
        requestOf = null; // what was read before is not this batch
        return true;
    }

    /** The number of ranges added. */
    public int size() {
        return count;
    }

    /** The number of requests the last {@link #read} put its ranges in. */
    public int requests() {
        return made;
    }

    /** Empties the batch, for the next ranges. */
    public void clear() {
        count = 0;
        bytes = 0;
        requestOf = null;
        made = 0;
    }

    /**
     * Reads the ranges added. A request's failure is kept for {@link #bytes} to throw; after one, no further
     * request is started, and those in flight are waited for, so nothing is still reading when this returns.
     */
    public void read() {
        Request[] plan = plan();
        made = plan.length;
        int workers = Math.min(requests, plan.length);
        if (workers < 2) {
            for (Request request : plan) {
                if (!request.read(buffer)) {
                    return;
                }
            }
            return;
        }
        AtomicInteger next = new AtomicInteger();
        AtomicBoolean failed = new AtomicBoolean();
        Runnable work = () -> {
            while (!failed.get()) {
                // A request, once taken, is made: so every request before a failed one has been made too.
                int taken = next.getAndIncrement();
                if (taken >= plan.length) {
                    return;
                }
                if (!plan[taken].read(buffer)) {
                    failed.set(true);
                }
            }
        };
        Thread[] others = new Thread[workers - 1];
        int started = 0;
        try {
            for (; started < others.length; started++) {
                others[started] = Thread.ofVirtual().name("falcon-hdf5-range-" + started).start(work);
            }
            work.run();
        } finally {
            boolean interrupted = false;
            for (int i = 0; i < started; i++) {
                for (;;) {
                    try {
                        others[i].join();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true; // the requests in flight are waited for all the same
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The bytes of range {@code index}, in the order the ranges were added, in an array of the caller's own.
     * Each range is taken once; a request's bytes are let go when its last range is.
     *
     * @throws RuntimeException the failure of the request that read the range, as the read of the range
     *                          alone would have thrown it
     */
    public byte[] bytes(int index) {
        if (requestOf == null || index < 0 || index >= count) {
            throw new IllegalStateException("range " + index + " of " + count + " has not been read");
        }
        Request request = requestOf[index];
        if (request.failure != null) {
            if (request.failure instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            if (request.failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(request.failure);
        }
        byte[] data = request.data;
        if (data == null) {
            throw new IllegalStateException("range " + index + " has not been read, or was taken before");
        }
        int from = (int) (offsets[index] - request.start);
        byte[] out = request.ranges == 1 && from == 0 && lengths[index] == data.length
                ? data : Arrays.copyOfRange(data, from, from + lengths[index]);
        if (++request.taken == request.ranges) {
            request.data = null;
        }
        return out;
    }

    /**
     * Puts the ranges into requests: in the order of their offsets, each range joins the request before it
     * if it starts no more than the gap after that request's end and the two together stay within a
     * request's size. The requests are returned in the order of the first range each holds.
     */
    private Request[] plan() {
        requestOf = new Request[count];
        List<Request> plan = new ArrayList<>();
        Request current = null;
        for (int index : byOffset()) {
            long start = offsets[index];
            long end = start + lengths[index];
            if (current == null || start - current.end > gap || Math.max(end, current.end) - current.start > requestBytes) {
                current = new Request(start, end, index);
                plan.add(current);
            } else {
                current.end = Math.max(current.end, end);
                current.first = Math.min(current.first, index);
            }
            current.ranges++;
            requestOf[index] = current;
        }
        plan.sort(Comparator.comparingInt(request -> request.first));
        return plan.toArray(new Request[0]);
    }

    /** The ranges' indices in the order of their offsets: as added, when they were added in that order. */
    private int[] byOffset() {
        int[] order = new int[count];
        boolean ascending = true;
        for (int i = 0; i < count; i++) {
            order[i] = i;
            ascending &= i == 0 || offsets[i - 1] <= offsets[i];
        }
        if (ascending) {
            return order;
        }
        Integer[] boxed = new Integer[count];
        Arrays.setAll(boxed, i -> i);
        Arrays.sort(boxed, Comparator.comparingLong(i -> offsets[i])); // stable: equal offsets as added
        for (int i = 0; i < count; i++) {
            order[i] = boxed[i];
        }
        return order;
    }

    /** One request: the bytes from {@code start} to {@code end}, which hold {@code ranges} of the ranges. */
    private static final class Request {
        final long start;
        long end;
        int first;         // the first range, as added, that it holds
        int ranges;
        int taken;
        byte[] data;       // written by the thread that makes the request, read after it is joined
        Throwable failure;

        Request(long start, long end, int first) {
            this.start = start;
            this.end = end;
            this.first = first;
        }

        /** Makes the request; false if it failed. */
        boolean read(HdfBuffer buffer) {
            try {
                byte[] read = new byte[(int) (end - start)];
                buffer.copyRequest(start, read, 0, read.length);
                data = read;
                return true;
            } catch (Throwable thrown) {
                failure = thrown;
                return false;
            }
        }
    }
}
