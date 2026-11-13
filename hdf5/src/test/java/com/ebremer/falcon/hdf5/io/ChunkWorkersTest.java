package com.ebremer.falcon.hdf5.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link ChunkWorkers} (P2 PF9, PF10): work added by one thread runs on several, its results come back in
 * the order it was added, what is in flight stays within the threads and the bytes allowed, and a failure
 * is the one a single thread would have met first.
 */
@Timeout(120)
class ChunkWorkersTest {

    private static final long NO_LIMIT = Long.MAX_VALUE;

    /** A piece of work that takes a little while, differently for each index, and results in the index. */
    private static Supplier<Integer> slow(int index) {
        return () -> {
            LockSupport.parkNanos(((index * 7919L) % 13) * 50_000L);
            return index;
        };
    }

    private static List<Integer> upTo(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    void resultsComeBackInTheOrderTheWorkWasAdded() {
        for (int threads : new int[] {1, 2, 3, 8, 64}) {
            List<Integer> results = new ArrayList<>();
            ChunkWorkers.<Integer>run(threads, NO_LIMIT, results::add, workers -> {
                for (int i = 0; i < 500; i++) {
                    workers.add(1, slow(i));
                }
            });
            assertEquals(upTo(500), results, threads + " threads");
        }
    }

    @Test
    void theResultsNeedNotBeTaken() {
        AtomicInteger ran = new AtomicInteger();
        ChunkWorkers.<Void>run(8, NO_LIMIT, null, workers -> {
            for (int i = 0; i < 300; i++) {
                workers.add(1, () -> {
                    ran.incrementAndGet();
                    return null;
                });
            }
        });
        assertEquals(300, ran.get());
    }

    @Test
    void noMoreWorkAtOnceThanTheThreadsAskedFor() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        ChunkWorkers.<Void>run(3, NO_LIMIT, null, workers -> {
            for (int i = 0; i < 150; i++) {
                workers.add(1, () -> {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    LockSupport.parkNanos(3_000_000L);
                    running.decrementAndGet();
                    return null;
                });
            }
        });
        assertTrue(most.get() <= 3, "at most 3 at once, not " + most.get());
        if (ChunkWorkers.threads(2) == 2) { // the common pool has a thread to help with
            assertTrue(most.get() >= 2, "150 pieces of 3 ms never ran two at once");
        }
    }

    @Test
    void onePieceAloneStaysOnTheCallingThread() {
        Thread caller = Thread.currentThread();
        List<Thread> ranOn = new ArrayList<>();
        ChunkWorkers.<Void>run(8, NO_LIMIT, null, workers -> workers.add(1, () -> {
            ranOn.add(Thread.currentThread());
            return null;
        }));
        assertEquals(List.of(caller), ranOn);
    }

    @Test
    void whatIsInFlightStaysWithinTheBytesAllowed() {
        AtomicInteger inFlight = new AtomicInteger(); // begun, its result not yet handed over
        AtomicInteger most = new AtomicInteger();
        ChunkWorkers.<Integer>run(16, 100, result -> inFlight.decrementAndGet(), workers -> {
            for (int i = 0; i < 200; i++) {
                int index = i;
                workers.add(30, () -> {
                    most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    return slow(index).get();
                });
            }
        });
        assertEquals(0, inFlight.get());
        assertTrue(most.get() <= 3, "three pieces of 30 bytes fit in 100, not " + most.get());
    }

    @Test
    void aPieceOverTheBytesAllowedRunsAloneOnTheCallingThread() {
        Thread caller = Thread.currentThread();
        List<Integer> results = Collections.synchronizedList(new ArrayList<>());
        List<Object> seen = new ArrayList<>();
        ChunkWorkers.<Integer>run(8, 100, results::add, workers -> {
            for (int i = 0; i < 5; i++) {
                workers.add(10, slow(i));
            }
            workers.add(1000, () -> {
                seen.add(Thread.currentThread());
                seen.add(List.copyOf(results)); // everything before it is done and handed over
                return 5;
            });
            assertEquals(upTo(6), results, "its own result is handed over before more is added");
            for (int i = 6; i < 12; i++) {
                workers.add(10, slow(i));
            }
        });
        assertEquals(List.of(caller, upTo(5)), seen);
        assertEquals(upTo(12), results);
    }

    @Test
    void drainCompletesWhatWasAddedBeforeItReturns() {
        List<Integer> results = new ArrayList<>();
        ChunkWorkers.<Integer>run(8, NO_LIMIT, results::add, workers -> {
            for (int i = 0; i < 40; i++) {
                workers.add(1, slow(i));
            }
            workers.drain();
            assertEquals(upTo(40), results);
            workers.drain(); // nothing left
            for (int i = 40; i < 90; i++) {
                workers.add(1, slow(i));
            }
        });
        assertEquals(upTo(90), results);
    }

    @Test
    void theFailureThrownIsTheFirstInTheOrderTheWorkWasAdded() {
        for (int round = 0; round < 20; round++) {
            int threads = 6;
            List<Integer> results = new ArrayList<>();
            AtomicInteger lastBegun = new AtomicInteger(-1);
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                    ChunkWorkers.<Integer>run(threads, NO_LIMIT, results::add, workers -> {
                        for (int i = 0; i < 400; i++) {
                            int index = i;
                            workers.add(1, () -> {
                                lastBegun.accumulateAndGet(index, Math::max);
                                if (index == 40 || index == 43 || index == 300) {
                                    throw new IllegalStateException("piece " + index);
                                }
                                return slow(index).get();
                            });
                        }
                    }));
            assertEquals("piece 40", thrown.getMessage());
            assertEquals(upTo(40), results, "every result before the failure, and none after");
            // Besides the failed piece, no more than fill the threads were in flight, and one was waiting.
            assertTrue(lastBegun.get() <= 40 + threads, "work went on to piece " + lastBegun.get());
        }
    }

    @Test
    void anErrorIsThrownAsItIs() {
        AssertionError error = new AssertionError("in a piece");
        AssertionError thrown = assertThrows(AssertionError.class, () ->
                ChunkWorkers.<Integer>run(4, NO_LIMIT, null, workers -> {
                    for (int i = 0; i < 50; i++) {
                        int index = i;
                        workers.add(1, () -> {
                            if (index == 20) {
                                throw error;
                            }
                            return index;
                        });
                    }
                }));
        assertSame(error, thrown);
    }

    @Test
    void aFailureOfTheFeedComesAfterTheWorkAddedBeforeIt() {
        // The feed fails after adding ten pieces: they are completed, and their results handed over, first.
        List<Integer> results = new ArrayList<>();
        IllegalArgumentException feedFailure = assertThrows(IllegalArgumentException.class, () ->
                ChunkWorkers.<Integer>run(4, NO_LIMIT, results::add, workers -> {
                    for (int i = 0; i < 10; i++) {
                        workers.add(1, slow(i));
                    }
                    throw new IllegalArgumentException("the feed");
                }));
        assertEquals("the feed", feedFailure.getMessage());
        assertEquals(upTo(10), results);

        // ... and a piece among them that fails did so first.
        results.clear();
        IllegalStateException pieceFailure = assertThrows(IllegalStateException.class, () ->
                ChunkWorkers.<Integer>run(4, NO_LIMIT, results::add, workers -> {
                    for (int i = 0; i < 10; i++) {
                        int index = i;
                        workers.add(1, () -> {
                            if (index == 3) {
                                throw new IllegalStateException("piece 3");
                            }
                            return slow(index).get();
                        });
                    }
                    throw new IllegalArgumentException("the feed");
                }));
        assertEquals("piece 3", pieceFailure.getMessage());
        assertEquals(upTo(3), results);
    }

    @Test
    void aFailureOfTakingAResultIsThrownAndNothingMoreIsTaken() {
        List<Integer> results = new ArrayList<>();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                ChunkWorkers.<Integer>run(4, NO_LIMIT, result -> {
                    if (result == 25) {
                        throw new IllegalStateException("taking 25");
                    }
                    results.add(result);
                }, workers -> {
                    for (int i = 0; i < 200; i++) {
                        workers.add(1, slow(i));
                    }
                }));
        assertEquals("taking 25", thrown.getMessage());
        assertEquals(upTo(25), results);
    }

    /** A feed that adds what it still holds while a failure passes through it does not change the failure. */
    @Test
    void workAddedWhileAFailurePassesThroughTheFeedIsIgnored() {
        for (int round = 0; round < 20; round++) {
            AtomicInteger ranAfter = new AtomicInteger();
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                    ChunkWorkers.<Integer>run(3, NO_LIMIT, null, workers -> {
                        try {
                            for (int i = 0; i < 200; i++) {
                                int index = i;
                                workers.add(1, () -> {
                                    if (index == 5 || index == 9) {
                                        throw new IllegalStateException("piece " + index);
                                    }
                                    return slow(index).get();
                                });
                            }
                        } finally {
                            workers.add(1, () -> {
                                ranAfter.incrementAndGet();
                                throw new IllegalStateException("added in passing");
                            });
                            workers.drain();
                        }
                    }));
            assertEquals("piece 5", thrown.getMessage());
            assertEquals(0, ranAfter.get());
        }
    }

    @Test
    void fewOrSmallChunksAreNotWorthSeveralThreads() {
        int piece = ChunkWorkers.PIECE_BYTES;
        assertTrue(ChunkWorkers.worthwhile(2, piece));
        assertTrue(ChunkWorkers.worthwhile(2, 100L * piece));
        assertTrue(!ChunkWorkers.worthwhile(1, 100L * piece), "one chunk has nothing to run beside");
        assertTrue(!ChunkWorkers.worthwhile(0, piece));
        assertTrue(ChunkWorkers.worthwhile(128, piece / 64), "128 chunks of 512 bytes make two pieces");
        assertTrue(!ChunkWorkers.worthwhile(127, piece / 64));
        assertTrue(!ChunkWorkers.worthwhile(16, piece / 64));
        assertTrue(ChunkWorkers.worthwhile(1L << 40, 0), "chunks of no bytes do not divide by zero");
    }

    /** Work added from the common pool's own threads, all of them busy with it, still completes. */
    @Test
    void workAddedFromThePoolsOwnThreadsCompletes() {
        int runs = 4 * (ForkJoinPool.getCommonPoolParallelism() + 1);
        List<List<Integer>> all = IntStream.range(0, runs).parallel().mapToObj(run -> {
            List<Integer> results = new ArrayList<>();
            ChunkWorkers.<Integer>run(4, NO_LIMIT, results::add, workers -> {
                for (int i = 0; i < 100; i++) {
                    workers.add(1, slow(i));
                }
            });
            return results;
        }).toList();
        assertEquals(runs, all.size());
        for (List<Integer> results : all) {
            assertEquals(upTo(100), results);
        }
    }

    @Test
    void threadsAreNoMoreThanAskedForNorThanThePoolsAndOne() {
        assertEquals(1, ChunkWorkers.threads(1));
        assertTrue(ChunkWorkers.threads(2) <= 2);
        int many = ChunkWorkers.threads(Integer.MAX_VALUE);
        assertTrue(many >= 1 && many <= ForkJoinPool.getCommonPoolParallelism() + 1, "threads: " + many);
    }
}
