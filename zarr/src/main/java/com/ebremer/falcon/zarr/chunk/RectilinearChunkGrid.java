package com.ebremer.falcon.zarr.chunk;

import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A rectilinear chunk grid (the {@code rectilinear} extension, zarr-extensions {@code chunk-grids/rectilinear},
 * which zarr-python 3.4 writes behind its {@code array.rectilinear_chunks} option): each dimension lists the
 * lengths of its chunks, end to end from the origin, so chunks need not share a shape. A dimension may
 * instead give one length that repeats, as a regular grid's does.
 *
 * <p>The lengths a dimension lists must reach the array's extent and may run past it, by several chunks
 * even (an array shrunk by a resize keeps them). A chunk that reaches past the array is still encoded at
 * its full listed length, its tail holding fill, as zarr-python encodes it; the grid's chunks along a
 * dimension are those that overlap the array.
 *
 * <p>Listed lengths are kept as runs (a length and how many chunks have it), as the metadata's
 * {@code [length, count]} pairs give them, so a run of many chunks costs nothing to hold. Finding the chunk
 * that holds an index is a binary search over the runs.
 */
public final class RectilinearChunkGrid extends ChunkGrid {

    /** The chunking along one dimension: a repeating length, or runs of listed lengths. */
    public static final class Axis {

        private final long step;         // > 0 for a repeating length; 0 for listed lengths
        private final long[] lengths;    // each run's chunk length
        private final long[] counts;     // each run's number of chunks
        private final long[] firstChunk; // the index of each run's first chunk
        private final long[] start;      // the element offset of each run's first chunk
        private final long chunks;       // the number of listed chunks
        private final long end;          // the sum of the listed lengths

        private Axis(long step, long[] lengths, long[] counts) {
            this.step = step;
            this.lengths = lengths;
            this.counts = counts;
            this.firstChunk = new long[lengths.length];
            this.start = new long[lengths.length];
            long chunkTotal = 0;
            long elementTotal = 0;
            for (int r = 0; r < lengths.length; r++) {
                firstChunk[r] = chunkTotal;
                start[r] = elementTotal;
                try {
                    chunkTotal = Math.addExact(chunkTotal, counts[r]);
                    elementTotal = Math.addExact(elementTotal, Math.multiplyExact(lengths[r], counts[r]));
                } catch (ArithmeticException e) {
                    throw new IllegalArgumentException("chunk lengths sum past " + Long.MAX_VALUE);
                }
            }
            this.chunks = chunkTotal;
            this.end = elementTotal;
        }

        /**
         * Chunks of one length repeating along the whole dimension, however long (the metadata's bare
         * integer).
         *
         * @throws IllegalArgumentException if {@code length} is not positive
         */
        public static Axis repeating(long length) {
            if (length <= 0) {
                throw new IllegalArgumentException("chunk length must be positive, not " + length);
            }
            return new Axis(length, new long[0], new long[0]);
        }

        /**
         * Chunks of the listed lengths, in order.
         *
         * @throws IllegalArgumentException if none is listed, one is not positive, or they sum past
         *                                  {@code Long.MAX_VALUE}
         */
        public static Axis listed(long... lengths) {
            long[] counts = new long[lengths.length];
            Arrays.fill(counts, 1);
            return runs(lengths, counts);
        }

        /**
         * Runs of chunks: {@code counts[r]} chunks of length {@code lengths[r]}, run after run (the metadata's
         * {@code [length, count]} pairs, a bare length being a run of one).
         *
         * @throws IllegalArgumentException if the arrays differ in length, there are no runs, a length or count
         *                                  is not positive, or the lengths sum past {@code Long.MAX_VALUE}
         */
        public static Axis runs(long[] lengths, long[] counts) {
            if (lengths.length != counts.length) {
                throw new IllegalArgumentException(lengths.length + " run lengths but " + counts.length + " counts");
            }
            if (lengths.length == 0) {
                throw new IllegalArgumentException("a dimension must list at least one chunk length");
            }
            // Merge neighbouring runs of one length, so the runs are as few as can be (and the metadata
            // written from them is zarr-python's compressed form).
            List<long[]> merged = new ArrayList<>();
            for (int r = 0; r < lengths.length; r++) {
                if (lengths[r] <= 0) {
                    throw new IllegalArgumentException("chunk length must be positive, not " + lengths[r]);
                }
                if (counts[r] <= 0) {
                    throw new IllegalArgumentException("run count must be positive, not " + counts[r]);
                }
                long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && last[0] == lengths[r]) {
                    try {
                        last[1] = Math.addExact(last[1], counts[r]);
                    } catch (ArithmeticException e) {
                        throw new IllegalArgumentException("chunk lengths sum past " + Long.MAX_VALUE);
                    }
                } else {
                    merged.add(new long[] {lengths[r], counts[r]});
                }
            }
            long[] l = new long[merged.size()];
            long[] c = new long[merged.size()];
            for (int r = 0; r < l.length; r++) {
                l[r] = merged.get(r)[0];
                c[r] = merged.get(r)[1];
            }
            return new Axis(0, l, c);
        }

        /** Whether the dimension repeats one length rather than listing lengths. */
        public boolean isRepeating() {
            return step > 0;
        }

        /** The run holding chunk {@code chunk}: the last run whose first chunk is at or before it. */
        private int runOfChunk(long chunk) {
            int r = Arrays.binarySearch(firstChunk, chunk);
            return r >= 0 ? r : -r - 2;
        }

        private long at(long index) {
            if (index < 0) {
                throw new IndexOutOfBoundsException("element index " + index + " is negative");
            }
            if (step > 0) {
                return index / step;
            }
            if (index >= end) {
                throw new IndexOutOfBoundsException("element index " + index
                        + " is past the listed chunk lengths, which end at " + end);
            }
            int r = Arrays.binarySearch(start, index);
            r = r >= 0 ? r : -r - 2;
            return firstChunk[r] + (index - start[r]) / lengths[r];
        }

        private long startOf(long chunk) {
            if (step > 0) {
                return chunk * step;
            }
            int r = runOfChunk(chunk);
            return start[r] + (chunk - firstChunk[r]) * lengths[r];
        }

        private long lengthOf(long chunk) {
            return step > 0 ? step : lengths[runOfChunk(chunk)];
        }

        /** The number of chunks overlapping {@code [0, extent)}. */
        private long overlapping(long extent) {
            return extent == 0 ? 0 : at(extent - 1) + 1;
        }

        /** This axis for an extent of {@code newExtent}: one more chunk if it grows past the listed lengths. */
        private Axis resized(long newExtent) {
            if (step > 0 || newExtent <= end) {
                return this;
            }
            long[] l = Arrays.copyOf(lengths, lengths.length + 1);
            long[] c = Arrays.copyOf(counts, counts.length + 1);
            l[l.length - 1] = newExtent - end;
            c[c.length - 1] = 1;
            return runs(l, c);
        }

        /**
         * The dimension's entry in {@code chunk_shapes}, as zarr-python writes it: a repeating length as a bare
         * integer; listed lengths compressed, a run of several as {@code [length, count]} and a run of one as a
         * bare length, when that is shorter than listing every length, and listed one by one otherwise.
         */
        private JsonValue toJson() {
            if (step > 0) {
                return JsonNumber.of(step);
            }
            List<JsonValue> out = new ArrayList<>();
            if (lengths.length < chunks) {
                for (int r = 0; r < lengths.length; r++) {
                    out.add(counts[r] == 1 ? JsonNumber.of(lengths[r])
                            : new JsonArray(List.of(JsonNumber.of(lengths[r]), JsonNumber.of(counts[r]))));
                }
            } else {
                for (long length : lengths) { // every run is one chunk
                    out.add(JsonNumber.of(length));
                }
            }
            return new JsonArray(out);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Axis a && step == a.step && Arrays.equals(lengths, a.lengths)
                    && Arrays.equals(counts, a.counts);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(step) * 31 + Arrays.hashCode(lengths) * 17 + Arrays.hashCode(counts);
        }

        @Override
        public String toString() {
            return toJson().toJson();
        }
    }

    private final Axis[] axes;
    private final long[] gridShape;

    /**
     * A grid over an array of {@code arrayShape}, chunked along each dimension as its axis says.
     *
     * @throws IllegalArgumentException if the ranks differ, an array dimension is negative, a dimension's
     *                                  listed lengths do not reach its extent, or the array has more than
     *                                  {@code Long.MAX_VALUE} elements
     */
    public RectilinearChunkGrid(long[] arrayShape, Axis... axes) {
        super(arrayShape);
        if (axes.length != arrayShape.length) {
            throw new IllegalArgumentException("array rank " + arrayShape.length
                    + " does not match the grid's " + axes.length + " dimensions");
        }
        this.axes = axes.clone();
        this.gridShape = new long[axes.length];
        for (int i = 0; i < axes.length; i++) {
            Axis axis = axes[i];
            if (axis.step == 0 && axis.end < arrayShape[i]) {
                throw new IllegalArgumentException("dimension " + i + "'s chunk lengths sum to " + axis.end
                        + ", short of its extent " + arrayShape[i]);
            }
            gridShape[i] = axis.overlapping(arrayShape[i]);
        }
    }

    /** The chunking along dimension {@code dim}. */
    public Axis axis(int dim) {
        return axes[dim];
    }

    /**
     * The distinct chunk lengths along {@code dim}, over every chunk the dimension lists (those past the
     * array included), in order of first appearance; a repeating dimension has one.
     */
    public long[] distinctLengths(int dim) {
        Axis axis = axes[dim];
        if (axis.step > 0) {
            return new long[] {axis.step};
        }
        return Arrays.stream(axis.lengths).distinct().toArray();
    }

    @Override
    public long chunksAlong(int dim) {
        return gridShape[dim];
    }

    @Override
    public long chunkAt(int dim, long index) {
        return axes[dim].at(index);
    }

    @Override
    public long chunkStart(int dim, long chunk) {
        return axes[dim].startOf(chunk);
    }

    @Override
    public long chunkLength(int dim, long chunk) {
        return axes[dim].lengthOf(chunk);
    }

    @Override
    public boolean isRegular() {
        return false;
    }

    @Override
    public RectilinearChunkGrid resized(long[] newShape) {
        if (newShape.length != rank()) {
            throw new IllegalArgumentException("new shape has rank " + newShape.length + ", the grid has rank "
                    + rank());
        }
        Axis[] next = new Axis[axes.length];
        for (int i = 0; i < axes.length; i++) {
            if (newShape[i] < 0) {
                throw new IllegalArgumentException("array dimension " + i + " must be non-negative");
            }
            next[i] = axes[i].resized(newShape[i]);
        }
        return new RectilinearChunkGrid(newShape, next);
    }

    @Override
    public JsonObject toJson() {
        List<JsonValue> shapes = new ArrayList<>(axes.length);
        for (Axis axis : axes) {
            shapes.add(axis.toJson());
        }
        return JsonObject.builder().put("name", "rectilinear")
                .put("configuration", JsonObject.builder().put("kind", "inline")
                        .put("chunk_shapes", new JsonArray(shapes)).build())
                .build();
    }

    @Override
    public String toString() {
        return "RectilinearChunkGrid[array=" + Arrays.toString(arrayShape()) + " chunks=" + Arrays.toString(axes)
                + " grid=" + Arrays.toString(gridShape) + "]";
    }
}
