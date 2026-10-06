package com.ebremer.falcon.hdf5.data;

import com.ebremer.falcon.hdf5.HdfFormatException;

/**
 * The elements a dataspace selection picks, in libhdf5's iteration order, each addressed by its position
 * in that order: what a virtual dataset's mapping pairs, the <i>i</i>th selected source element with the
 * <i>i</i>th selected virtual one.
 *
 * <p>A regular hyperslab, or "all", selects a Cartesian product of per-dimension index sets (each a run of
 * blocks), visited in row-major order; it is kept as those sets, so a position and a coordinate convert
 * into each other by arithmetic and finding the elements inside a box costs only what the box holds,
 * however large the selection. Points and irregular hyperslabs are kept as their listed coordinates.
 */
public abstract sealed class SelectedElements permits SelectedElements.Product, SelectedElements.Listed {

    /** Receives one selected element: its position in iteration order and its coordinates. */
    @FunctionalInterface
    public interface Visitor {
        /** {@code coordinates} must not be kept or changed; it is reused. */
        void visit(long position, long[] coordinates);
    }

    private SelectedElements() {
    }

    /**
     * A regular hyperslab ({@code H5Sselect_hyperslab}): in each dimension <i>d</i>, {@code count[d]}
     * blocks of {@code block[d]} indices, each block {@code stride[d]} after the last, from
     * {@code start[d]}. The caller has checked the arguments (see {@code Dataset.select}).
     */
    public static SelectedElements hyperslab(long[] start, long[] stride, long[] count, long[] block) {
        Axis[] axes = new Axis[start.length];
        for (int d = 0; d < axes.length; d++) {
            axes[d] = new Axis(start[d], stride[d], block[d], Math.multiplyExact(count[d], block[d]));
        }
        return new Product(axes);
    }

    /** The points at {@code coordinates} (each of length {@code rank}), in the order given. */
    public static SelectedElements points(long[][] coordinates, int rank) {
        long[][] copy = new long[coordinates.length][];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = coordinates[i].clone();
        }
        return new Listed(copy, rank);
    }

    /** The rank of the dataspace selected from. */
    public abstract int rank();

    /** The low corner of the box that bounds the selected elements (zeros if none is selected). */
    public abstract long[] lowCorner();

    /** The high corner (inclusive) of the box that bounds the selected elements (-1s if none is selected). */
    public abstract long[] highCorner();

    /** The number of elements selected. */
    public abstract long count();

    /** Stores in {@code out} the coordinates of the element at {@code position} ({@code 0 <= position < count()}). */
    public abstract void coordinates(long position, long[] out);

    /**
     * The position of the element at {@code coordinates}, the inverse of {@link #coordinates}: -1 if it is not
     * selected (for one listed twice, its first position).
     */
    public abstract long positionOf(long[] coordinates);

    /**
     * Visits, in iteration order, every selected element inside the box {@code [offset, offset + count)}.
     */
    public abstract void forEachInBox(long[] offset, long[] count, Visitor visitor);

    /** False if no selected element can lie inside the box (a quick test; true may still visit none). */
    public abstract boolean mayIntersect(long[] offset, long[] count);

    /**
     * Receives selected elements a run at a time (P2 PF8): {@code length} elements at consecutive positions
     * from {@code position}, the first at {@code coordinates}, each {@code step} after the one before in the
     * last dimension (the other coordinates the same). A step of 1 is a run of elements next to each other.
     */
    @FunctionalInterface
    public interface RunVisitor {
        /** {@code coordinates} must not be kept or changed; it is reused. */
        void visit(long position, long[] coordinates, long length, long step);
    }

    /**
     * Visits, in iteration order, every selected element inside the box {@code [offset, offset + count)}, as
     * {@link #forEachInBox} does, but a run at a time: a regular hyperslab's elements along its last
     * dimension, in a box, are blocks of consecutive indices (or, for blocks of one, indices a stride apart),
     * so a copy takes a whole run at once rather than each element on its own.
     */
    public abstract void forEachRunInBox(long[] offset, long[] count, RunVisitor visitor);

    /** Copies {@code length} bytes from byte {@code from} of a source to {@code out[at...]}. */
    @FunctionalInterface
    public interface Source {
        void copy(long from, byte[] out, int at, int length);
    }

    /**
     * The selected elements' bytes, in iteration order, from {@code source}: the row-major elements of the
     * box of shape {@code dims} whose corner is {@code origin}, which holds every selected element. Elements
     * next to each other in both the source and the selection are copied as one run.
     */
    public byte[] gather(Source source, long[] origin, long[] dims, int elementSize) {
        long n = count();
        byte[] out = new byte[Elements.checkedByteCount(n, elementSize)];
        if (n == 0) {
            return out;
        }
        long[] low = lowCorner();
        long[] high = highCorner();
        long[] box = new long[low.length];
        for (int d = 0; d < box.length; d++) {
            box[d] = high[d] - low[d] + 1;
        }
        long[] pending = new long[3]; // a run of adjacent elements: its source element, position, and length
        forEachRunInBox(low, box, (position, coordinates, length, step) -> {
            long flat = 0;
            for (int d = 0; d < coordinates.length; d++) {
                flat = flat * dims[d] + (coordinates[d] - origin[d]);
            }
            if (step == 1 || length == 1) {
                // Adjacent to the run before, in the source and in the selection: one copy for both.
                if (pending[2] > 0 && flat == pending[0] + pending[2] && position == pending[1] + pending[2]) {
                    pending[2] += length;
                    return;
                }
                flush(source, out, pending, elementSize);
                pending[0] = flat;
                pending[1] = position;
                pending[2] = length;
                return;
            }
            flush(source, out, pending, elementSize);
            for (long k = 0; k < length; k++) {
                source.copy((flat + k * step) * elementSize, out, (int) ((position + k) * elementSize), elementSize);
            }
        });
        flush(source, out, pending, elementSize);
        return out;
    }

    /** Copies the pending run of adjacent elements, if any, and empties it. */
    private static void flush(Source source, byte[] out, long[] pending, int elementSize) {
        if (pending[2] > 0) {
            source.copy(pending[0] * elementSize, out, (int) (pending[1] * elementSize), (int) (pending[2] * elementSize));
            pending[2] = 0;
        }
    }

    /**
     * One dimension of a product: {@code size} indices taken in runs of {@code block}, each run starting
     * {@code stride} after the last, from {@code start}.
     */
    record Axis(long start, long stride, long block, long size) {

        Axis {
            if (size > 0 && (start < 0 || block <= 0 || (size > block && stride < block))) {
                throw new HdfFormatException("invalid selection (start " + start + ", stride " + stride
                        + ", block " + block + ")");
            }
        }

        /** The index at position {@code p} of this axis. */
        long valueAt(long p) {
            return start + (p / block) * stride + p % block;
        }

        /** The position of index {@code v} on this axis, or -1 if it is not one of its indices. */
        long indexOf(long v) {
            if (size == 0 || v < start) {
                return -1;
            }
            long t = v - start;
            long p;
            if (size <= block) {
                p = t; // a single run
            } else {
                if (t % stride >= block) {
                    return -1; // between two runs
                }
                p = t / stride * block + t % stride;
            }
            return p < size ? p : -1;
        }

        /**
         * The step between this axis's indices within one run, and so whether runs span blocks: 1 when its
         * indices are consecutive throughout (a single block, or blocks that touch), the stride when its
         * blocks are single indices, and 0 when runs are its blocks, each of consecutive indices.
         */
        long runStep() {
            if (size <= block || stride == block) {
                return 1;
            }
            return block == 1 ? stride : 0;
        }

        /** How many of this axis's indices are below {@code v}. */
        long countBelow(long v) {
            if (v <= start || size == 0) {
                return 0;
            }
            long t = v - start;
            if (size <= block) {
                return Math.min(t, size);
            }
            long runs = t / stride;
            long within = Math.min(t % stride, block);
            if (runs >= (size + block - 1) / block) {
                return size;
            }
            return Math.min(size, runs * block + within);
        }

        /** The last index, which must lie below {@code extent}. */
        long last() {
            try {
                return Math.addExact(start, Math.addExact(Math.multiplyExact((size - 1) / block, stride), (size - 1) % block));
            } catch (ArithmeticException e) {
                throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
            }
        }

        /**
         * The cells of width {@code width} (chunks, along this dimension) that hold at least one of this
         * axis's indices, in increasing order: one pass over its runs of indices.
         */
        long[] cells(long width) {
            if (size == 0) {
                return new long[0];
            }
            long runs = (size - 1) / block + 1;
            long[] out = new long[(int) Math.min(runs * 2, 1 << 16)];
            int n = 0;
            for (long r = 0; r < runs; r++) {
                long first = start + r * stride;
                long length = Math.min(block, size - r * block);
                long from = first / width;
                long to = (first + length - 1) / width;
                if (n > 0 && from <= out[n - 1]) {
                    from = out[n - 1] + 1;
                }
                for (long c = from; c <= to; c++) {
                    if (n == out.length) {
                        out = java.util.Arrays.copyOf(out, Math.multiplyExact(out.length, 2));
                    }
                    out[n++] = c;
                }
            }
            return java.util.Arrays.copyOf(out, n);
        }
    }

    /** A product of per-dimension index sets, visited in row-major order. */
    static final class Product extends SelectedElements {
        private final Axis[] axes;
        private final long count;

        Product(Axis[] axes) {
            this.axes = axes;
            long n = 1;
            for (Axis axis : axes) {
                try {
                    n = Math.multiplyExact(n, axis.size());
                } catch (ArithmeticException e) {
                    throw new HdfFormatException("dataspace selection size overflows (corrupt?)", e);
                }
            }
            this.count = n;
        }

        @Override
        public int rank() {
            return axes.length;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public long[] lowCorner() {
            long[] low = new long[axes.length];
            for (int d = 0; d < axes.length && count > 0; d++) {
                low[d] = axes[d].start();
            }
            return low;
        }

        @Override
        public long[] highCorner() {
            long[] high = new long[axes.length];
            for (int d = 0; d < axes.length; d++) {
                high[d] = count > 0 ? axes[d].last() : -1;
            }
            return high;
        }

        /** The chunk-grid cells along dimension {@code d}, for chunks {@code width} wide, that hold a selected index. */
        long[] cells(int d, long width) {
            return axes[d].cells(width);
        }

        @Override
        public void coordinates(long position, long[] out) {
            for (int d = axes.length - 1; d >= 0; d--) {
                long size = axes[d].size();
                out[d] = axes[d].valueAt(position % size);
                position /= size;
            }
        }

        @Override
        public void forEachRunInBox(long[] offset, long[] count, RunVisitor visitor) {
            int rank = axes.length;
            if (this.count == 0) {
                return;
            }
            if (rank == 0) {
                visitor.visit(0, new long[0], 1, 1);
                return;
            }
            long[] from = new long[rank];
            long[] to = new long[rank];
            long[] weight = new long[rank];
            long w = 1;
            for (int d = rank - 1; d >= 0; d--) {
                from[d] = axes[d].countBelow(offset[d]);
                to[d] = axes[d].countBelow(offset[d] + count[d]);
                if (from[d] >= to[d]) {
                    return;
                }
                weight[d] = w;
                w *= axes[d].size(); // never overflows: the product is count()
            }
            int last = rank - 1;
            Axis axis = axes[last];
            long step = axis.runStep();
            long[] p = from.clone();
            long[] coordinates = new long[rank];
            while (true) {
                long base = 0;
                for (int d = 0; d < last; d++) {
                    coordinates[d] = axes[d].valueAt(p[d]);
                    base += p[d] * weight[d];
                }
                // The last axis's positions in the box, a run at a time (its weight is 1).
                if (step != 0) {
                    coordinates[last] = axis.valueAt(from[last]);
                    visitor.visit(base + from[last], coordinates, to[last] - from[last], step);
                } else {
                    for (long q = from[last]; q < to[last]; ) {
                        long end = Math.min(to[last], (q / axis.block() + 1) * axis.block()); // the end of q's block
                        coordinates[last] = axis.valueAt(q);
                        visitor.visit(base + q, coordinates, end - q, 1);
                        q = end;
                    }
                }
                int d = last - 1;
                while (d >= 0) {
                    if (++p[d] < to[d]) {
                        break;
                    }
                    p[d] = from[d];
                    d--;
                }
                if (d < 0) {
                    return;
                }
            }
        }

        @Override
        public long positionOf(long[] coordinates) {
            long position = 0;
            for (int d = 0; d < axes.length; d++) {
                long p = axes[d].indexOf(coordinates[d]);
                if (p < 0) {
                    return -1;
                }
                position = position * axes[d].size() + p;
            }
            return count > 0 ? position : -1;
        }

        @Override
        public boolean mayIntersect(long[] offset, long[] count) {
            for (int d = 0; d < axes.length; d++) {
                if (axes[d].countBelow(offset[d]) >= axes[d].countBelow(offset[d] + count[d])) {
                    return false;
                }
            }
            return this.count > 0;
        }

        @Override
        public void forEachInBox(long[] offset, long[] count, Visitor visitor) {
            int rank = axes.length;
            long[] from = new long[rank];
            long[] to = new long[rank];
            long[] weight = new long[rank];
            long w = 1;
            for (int d = rank - 1; d >= 0; d--) {
                from[d] = axes[d].countBelow(offset[d]);
                to[d] = axes[d].countBelow(offset[d] + count[d]);
                if (from[d] >= to[d]) {
                    return;
                }
                weight[d] = w;
                w *= axes[d].size(); // never overflows: the product is count()
            }
            if (this.count == 0) {
                return;
            }
            long[] p = from.clone();
            long[] coordinates = new long[rank];
            while (true) {
                long position = 0;
                for (int d = 0; d < rank; d++) {
                    coordinates[d] = axes[d].valueAt(p[d]);
                    position += p[d] * weight[d];
                }
                visitor.visit(position, coordinates);
                int d = rank - 1;
                while (d >= 0) {
                    if (++p[d] < to[d]) {
                        break;
                    }
                    p[d] = from[d];
                    d--;
                }
                if (d < 0) {
                    return;
                }
            }
        }
    }

    /** Elements listed one by one, in their iteration order (points, or an irregular hyperslab's elements). */
    static final class Listed extends SelectedElements {
        private final long[][] coordinates;
        private final int rank;
        private final long[] low;
        private final long[] high;
        private volatile java.util.Map<Coordinates, Long> positions; // made on first positionOf

        Listed(long[][] coordinates, int rank) {
            this.coordinates = coordinates;
            this.rank = rank;
            this.low = new long[rank];
            this.high = new long[rank];
            java.util.Arrays.fill(low, Long.MAX_VALUE);
            java.util.Arrays.fill(high, Long.MIN_VALUE);
            for (long[] c : coordinates) {
                for (int d = 0; d < rank; d++) {
                    low[d] = Math.min(low[d], c[d]);
                    high[d] = Math.max(high[d], c[d]);
                }
            }
        }

        @Override
        public int rank() {
            return rank;
        }

        @Override
        public long count() {
            return coordinates.length;
        }

        @Override
        public long[] lowCorner() {
            return coordinates.length == 0 ? new long[rank] : low.clone();
        }

        @Override
        public long[] highCorner() {
            if (coordinates.length == 0) {
                long[] none = new long[rank];
                java.util.Arrays.fill(none, -1);
                return none;
            }
            return high.clone();
        }

        /** The coordinates of the element at {@code position}, not to be changed. */
        long[] at(int position) {
            return coordinates[position];
        }

        @Override
        public void coordinates(long position, long[] out) {
            System.arraycopy(coordinates[(int) position], 0, out, 0, rank);
        }

        @Override
        public void forEachRunInBox(long[] offset, long[] count, RunVisitor visitor) {
            // Points listed one after another that are next to each other in the last dimension form a run.
            long[] start = new long[rank];
            long[] run = {-1, 0}; // the run's first position, and its length
            forEachInBox(offset, count, (position, c) -> {
                if (run[1] > 0 && position == run[0] + run[1] && continues(start, c, run[1])) {
                    run[1]++;
                    return;
                }
                if (run[1] > 0) {
                    visitor.visit(run[0], start, run[1], 1);
                }
                System.arraycopy(c, 0, start, 0, rank);
                run[0] = position;
                run[1] = 1;
            });
            if (run[1] > 0) {
                visitor.visit(run[0], start, run[1], 1);
            }
        }

        /** True if {@code at} is the element {@code length} after {@code start} in the last dimension. */
        private static boolean continues(long[] start, long[] at, long length) {
            int last = start.length - 1;
            if (last < 0) {
                return false;
            }
            for (int d = 0; d < last; d++) {
                if (at[d] != start[d]) {
                    return false;
                }
            }
            return at[last] == start[last] + length;
        }

        @Override
        public long positionOf(long[] at) {
            java.util.Map<Coordinates, Long> map = positions;
            if (map == null) {
                map = new java.util.HashMap<>();
                for (int i = coordinates.length - 1; i >= 0; i--) {
                    map.put(new Coordinates(coordinates[i]), (long) i); // the first position wins
                }
                positions = map;
            }
            Long position = map.get(new Coordinates(at));
            return position == null ? -1 : position;
        }

        /** Coordinates as a map key. */
        private record Coordinates(long[] values) {
            @Override
            public boolean equals(Object other) {
                return other instanceof Coordinates c && java.util.Arrays.equals(values, c.values);
            }

            @Override
            public int hashCode() {
                return java.util.Arrays.hashCode(values);
            }
        }

        @Override
        public boolean mayIntersect(long[] offset, long[] count) {
            if (coordinates.length == 0) {
                return false;
            }
            for (int d = 0; d < rank; d++) {
                if (high[d] < offset[d] || low[d] >= offset[d] + count[d]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void forEachInBox(long[] offset, long[] count, Visitor visitor) {
            long[] copy = new long[rank];
            next:
            for (int i = 0; i < coordinates.length; i++) {
                long[] c = coordinates[i];
                for (int d = 0; d < rank; d++) {
                    if (c[d] < offset[d] || c[d] >= offset[d] + count[d]) {
                        continue next;
                    }
                }
                System.arraycopy(c, 0, copy, 0, rank);
                visitor.visit(i, copy);
            }
        }
    }
}
