package com.ebremer.falcon.hdf5.io;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs the chunk work of one read or one write (each chunk's filters reversed, or applied) on several
 * threads at once: the common {@link ForkJoinPool}'s, and the calling thread, which works too whenever it
 * would otherwise wait.
 *
 * <p>The calling thread {@linkplain #add adds} each piece of work in turn, and is the only one to touch the
 * file: it reads a chunk's stored bytes before it adds the work that decodes them, and is handed each
 * piece's result, in the order the work was added, once that piece and every one before it are done (which
 * is where a write puts an encoded chunk in the file). So a file is read and written exactly as on one
 * thread, in the same order, and only the filters run beside one another.
 *
 * <p>What is in flight (added, its result not yet handed over) is bounded: by a number of pieces, the
 * {@code threads} asked for, and by their cost in bytes, an eighth of the heap, so that many threads never
 * hold many large chunks at once. A piece that alone costs more than that is done by the calling thread on
 * its own, as on one thread. The work added last is not started until the next is added, so one piece alone
 * never leaves the calling thread.
 *
 * <p>A failure is the one a single thread would have met first: the work is completed in the order it was
 * added up to the first piece that failed, whose exception is thrown; a failure of the caller's own, between
 * two pieces, is thrown unless a piece added before it failed. Work after the failure is not started, and
 * what is already running elsewhere is left to end by itself, its result dropped.
 *
 * <p>Internal type &mdash; lives in a non-exported package.
 *
 * @param <R> what a piece of work results in
 */
public final class ChunkWorkers<R> {

    /**
     * The least a piece of work should hold, in bytes of chunks (32 KiB): handing a piece to another thread
     * costs some microseconds, more than filtering a chunk of a few hundred bytes takes, so small chunks are
     * filtered several to a piece.
     */
    public static final int PIECE_BYTES = 32 << 10;

    private static final ForkJoinPool POOL = ForkJoinPool.commonPool();
    /** The threads the common pool has: what it reports, but for a pool set to none at all, which reports one. */
    private static final int POOL_THREADS = poolThreads();

    private final int capacity;                 // the most pieces in flight
    private final long budget;                  // ... and the most bytes
    private final Consumer<? super R> inOrder;  // takes each result in turn, or null
    private final ConcurrentLinkedQueue<Piece<R>> todo = new ConcurrentLinkedQueue<>(); // in flight, not yet begun
    private final ArrayDeque<Piece<R>> window = new ArrayDeque<>(); // in flight, oldest first: the caller's alone
    private final AtomicInteger helpers = new AtomicInteger();      // pool tasks begun for this work, not yet ended
    private long bytes;                         // the cost of what is in flight
    private Piece<R> held;                      // the piece added last, not yet in flight
    private volatile boolean stopped;           // after a failure: pieces not yet begun are skipped
    private boolean failed;                     // a piece, or inOrder, has failed and its exception been thrown

    private ChunkWorkers(int threads, long budget, Consumer<? super R> inOrder) {
        this.capacity = threads;
        this.budget = budget;
        this.inOrder = inOrder;
    }

    /**
     * How many threads work at once when {@code asked} are asked for: no more than the common pool's and
     * the calling thread. 1 means the calling thread alone, for which this class is not needed.
     *
     * @param asked the most threads wanted, at least 1
     * @return the threads to use, between 1 and {@code asked}
     */
    public static int threads(int asked) {
        return Math.max(1, Math.min(asked, POOL_THREADS + 1));
    }

    /**
     * Whether {@code chunks} chunks of {@code chunkBytes} bytes each are worth several threads: there are at
     * least two, and enough of them for two pieces of work. Fewer are filtered sooner by the calling thread
     * than they are handed over.
     *
     * @param chunks     how many chunks, or the most there may be
     * @param chunkBytes a chunk's size, unfiltered
     * @return true if their filters are worth running on several threads
     */
    public static boolean worthwhile(long chunks, long chunkBytes) {
        return chunks >= 2 && chunks >= 2L * PIECE_BYTES / Math.max(1, chunkBytes);
    }

    private static int poolThreads() {
        try {
            String set = System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism");
            if (set != null && Integer.parseInt(set.trim()) <= 0) {
                return 0; // no thread would ever take the work
            }
        } catch (NumberFormatException | SecurityException e) {
            // the pool ignored the property too
        }
        return ForkJoinPool.getCommonPoolParallelism();
    }

    /**
     * Runs the work {@code feed} adds on up to {@code threads} threads, the calling one among them, and
     * returns once all of it is done and every result has been handed to {@code inOrder}.
     *
     * @param threads the most pieces of work in flight at once, at least 1
     * @param inOrder takes each piece's result on the calling thread, in the order the work was added, or
     *                null if the results are not needed
     * @param feed    adds the work, on the calling thread
     * @param <R>     what a piece of work results in
     */
    public static <R> void run(int threads, Consumer<? super R> inOrder, Consumer<? super ChunkWorkers<R>> feed) {
        run(threads, Runtime.getRuntime().maxMemory() / 8, inOrder, feed);
    }

    /** As {@link #run(int, Consumer, Consumer)}, with at most {@code budget} bytes of work in flight. */
    static <R> void run(int threads, long budget, Consumer<? super R> inOrder, Consumer<? super ChunkWorkers<R>> feed) {
        ChunkWorkers<R> workers = new ChunkWorkers<>(Math.max(1, threads), budget, inOrder);
        try {
            feed.accept(workers);
            workers.drain();
        } catch (RuntimeException | Error failure) {
            if (!workers.failed) {
                // The feed itself failed: what it added before that is completed first, as one thread would
                // have, and a piece of it that fails did so before this.
                try {
                    workers.drain();
                } catch (RuntimeException | Error earlier) {
                    workers.stop();
                    throw earlier;
                }
            }
            workers.stop();
            throw failure;
        }
    }

    /**
     * Adds a piece of work, to be run on some thread, perhaps this one; its result is handed over once it
     * and every piece added before it are done. Waits, working meanwhile, while as much is in flight as may
     * be. Once a piece has failed, and its exception been thrown from here or from {@link #drain}, more work
     * is ignored: a feed may add what it still holds while that exception passes through it.
     *
     * @param cost the bytes the piece holds while it is in flight: its input, its result, and what it
     *             allocates between them
     * @param work the work; it must not touch the file, and may run beside other pieces
     */
    public void add(long cost, Supplier<? extends R> work) {
        if (failed) {
            return; // its exception is on its way out: nothing after it is run, nor may fail in its place
        }
        Piece<R> piece = new Piece<>(work, cost);
        Piece<R> previous = held;
        held = null;
        if (cost > budget) {
            // Too large to hold beside other work: everything before it first, then this alone, here.
            if (previous != null) {
                admit(previous);
            }
            drain();
            window.addLast(piece);
            bytes += cost;
            piece.run(this);
            advance();
            return;
        }
        if (previous != null) {
            admit(previous);
            if (enlist()) {
                POOL.execute(this::help);
            }
        }
        held = piece;
    }

    /**
     * Completes every piece added so far and hands over its result, in order, before returning: what the
     * caller does next comes after all of it.
     */
    public void drain() {
        if (failed) {
            return;
        }
        Piece<R> last = held;
        held = null;
        if (last != null) {
            admit(last); // no helper is called for it: the calling thread takes whatever has not begun
        }
        while (!window.isEmpty()) {
            advance();
        }
    }

    /** Puts {@code piece} in flight, once there is room for it. */
    private void admit(Piece<R> piece) {
        while (window.size() >= capacity || (!window.isEmpty() && bytes + piece.cost > budget)) {
            advance();
        }
        window.addLast(piece);
        bytes += piece.cost;
        todo.add(piece);
    }

    /**
     * One step toward the oldest piece in flight leaving: its result handed over if it is done; else a piece
     * not yet begun run here; else, every piece being on a helper, a wait for the oldest.
     */
    private void advance() {
        Piece<R> oldest = window.peekFirst();
        if (!oldest.done.isDone()) {
            Piece<R> free = todo.poll();
            if (free != null) {
                free.run(this);
                return;
            }
            oldest.done.join(); // it runs on a helper, which completes it whatever happens
        }
        window.removeFirst();
        bytes -= oldest.cost;
        Throwable failure = oldest.failure;
        R result = oldest.result;
        oldest.result = null;
        if (failure != null) {
            failed = true;
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException unchecked) {
                throw unchecked;
            }
            throw new IllegalStateException(failure); // not from a Supplier, which declares nothing checked
        }
        if (inOrder != null) {
            try {
                inOrder.accept(result);
            } catch (RuntimeException | Error e) {
                failed = true;
                throw e;
            }
        }
    }

    /** True if one more helper may be called, which is then counted. */
    private boolean enlist() {
        for (int n = helpers.get(); n < capacity - 1; n = helpers.get()) {
            if (helpers.compareAndSet(n, n + 1)) {
                return true;
            }
        }
        return false;
    }

    /** A helper, on a pool thread: runs pieces not yet begun until it finds none. */
    private void help() {
        while (true) {
            for (Piece<R> piece = todo.poll(); piece != null; piece = todo.poll()) {
                piece.run(this);
            }
            helpers.decrementAndGet();
            if (todo.isEmpty() || !enlist()) {
                return; // a piece added just now is another helper's, or the calling thread's
            }
        }
    }

    /** After a failure: nothing not yet begun is run, and nothing is kept. */
    private void stop() {
        stopped = true;
        held = null;
        todo.clear();
        window.clear();
        bytes = 0;
    }

    /** One piece of work: run once, by whichever thread takes it from the queue. */
    private static final class Piece<R> {
        private Supplier<? extends R> work; // dropped once it has run, with the chunk it holds
        private final long cost;
        private R result;                   // read once done is complete
        private Throwable failure;
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        Piece(Supplier<? extends R> work, long cost) {
            this.work = work;
            this.cost = cost;
        }

        void run(ChunkWorkers<R> owner) {
            try {
                if (!owner.stopped) {
                    result = work.get();
                }
            } catch (Throwable t) {
                failure = t;
            } finally {
                work = null;
                done.complete(null);
            }
        }
    }
}
