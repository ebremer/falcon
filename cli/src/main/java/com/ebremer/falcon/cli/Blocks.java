package com.ebremer.falcon.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

/**
 * Copies an array a block at a time: the array is cut into blocks of whole chunks of the target (so a
 * write never reads a chunk back), as many chunks as fit in {@link #BLOCK_BYTES}, and each block is read
 * from the source and written to the target.
 */
final class Blocks {

    /** About the most bytes one block holds (more if one chunk is larger). */
    static final long BLOCK_BYTES = 32L << 20;

    private Blocks() {
    }

    /** Reads a block of the source: its elements in C order, in the form the matching writer takes. */
    interface Reader {
        Object read(long[] offset, long[] count);
    }

    /** Writes a block to the target. */
    interface Writer {
        void write(long[] offset, long[] count, Object data);
    }

    /**
     * {@return the shape of the blocks: whole multiples of {@code unit}, grown from the last dimension while
     * they hold at most {@code maxElements} (and at least one unit)}
     *
     * @param shape       the array's shape
     * @param unit        the target's chunk shape
     * @param maxElements the most elements a block should hold
     */
    static long[] blockShape(long[] shape, long[] unit, long maxElements) {
        int rank = shape.length;
        long[] block = Chunking.clamp(unit, shape);
        long elements = 1;
        for (long b : block) {
            elements *= b;
        }
        for (int d = rank - 1; d >= 0; d--) {
            long chunks = (Math.max(1, shape[d]) + block[d] - 1) / block[d];
            long fit = Math.max(1, maxElements / Math.max(1, elements));
            long k = Math.min(chunks, fit);
            elements = elements / block[d] * Math.min(Math.max(1, shape[d]), block[d] * k);
            block[d] = Math.min(Math.max(1, shape[d]), block[d] * k);
            if (k < chunks) {
                break;
            }
        }
        return block;
    }

    /**
     * Copies an array block by block.
     *
     * @param shape       the array's shape (an empty shape is one block of one element)
     * @param unit        the target's chunk shape
     * @param elementSize about the bytes one element takes in memory
     * @param threads     how many blocks to copy at once; with more than one, {@code writer} must be safe to
     *                    call from several threads, unless {@code ordered}
     * @param ordered     whether the writer takes the blocks one at a time, in order (the blocks are then read
     *                    ahead in parallel)
     * @param reader      reads a block of the source
     * @param writer      writes it to the target
     * @throws Exception what the reader or the writer threw
     */
    static void copy(long[] shape, long[] unit, int elementSize, int threads, boolean ordered, Reader reader,
                     Writer writer) throws Exception {
        int rank = shape.length;
        for (long n : shape) {
            if (n == 0) {
                return;
            }
        }
        if (rank == 0) {
            writer.write(new long[0], new long[0], reader.read(new long[0], new long[0]));
            return;
        }
        long[] block = blockShape(shape, unit, Math.max(1, BLOCK_BYTES / Math.max(1, elementSize)));
        long[] grid = new long[rank];
        long count = 1;
        for (int d = 0; d < rank; d++) {
            grid[d] = (shape[d] + block[d] - 1) / block[d];
            count *= grid[d];
        }
        if (threads <= 1) {
            for (long i = 0; i < count; i++) {
                long[][] box = box(i, shape, block, grid);
                writer.write(box[0], box[1], reader.read(box[0], box[1]));
            }
        } else if (ordered) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (long start = 0; start < count; start += threads) {
                    List<Future<Object>> reads = new ArrayList<>();
                    List<long[][]> boxes = new ArrayList<>();
                    for (long i = start; i < Math.min(count, start + threads); i++) {
                        long[][] box = box(i, shape, block, grid);
                        boxes.add(box);
                        reads.add(pool.submit(() -> reader.read(box[0], box[1])));
                    }
                    for (int j = 0; j < boxes.size(); j++) {
                        writer.write(boxes.get(j)[0], boxes.get(j)[1], get(reads.get(j)));
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        } else {
            long blocks = count;
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                get(pool.submit(() -> LongStream.range(0, blocks).parallel().forEach(i -> {
                    long[][] box = box(i, shape, block, grid);
                    writer.write(box[0], box[1], reader.read(box[0], box[1]));
                })));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /** {@return block {@code i} of the grid, in C order: its offset and its extent, cut at the array's edge} */
    private static long[][] box(long i, long[] shape, long[] block, long[] grid) {
        int rank = shape.length;
        long[] offset = new long[rank];
        long[] extent = new long[rank];
        long rest = i;
        for (int d = rank - 1; d >= 0; d--) {
            long at = rest % grid[d];
            rest /= grid[d];
            offset[d] = at * block[d];
            extent[d] = Math.min(block[d], shape[d] - offset[d]);
        }
        return new long[][] {offset, extent};
    }

    /** {@return the number of elements of a box} */
    static long elements(long[] count) {
        long n = 1;
        for (long c : count) {
            n *= c;
        }
        return n;
    }
}
