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

    /** The rank of the dataspace selected from. */
    public abstract int rank();

    /** The number of elements selected. */
    public abstract long count();

    /** Stores in {@code out} the coordinates of the element at {@code position} ({@code 0 <= position < count()}). */
    public abstract void coordinates(long position, long[] out);

    /**
     * Visits, in iteration order, every selected element inside the box {@code [offset, offset + count)}.
     */
    public abstract void forEachInBox(long[] offset, long[] count, Visitor visitor);

    /** False if no selected element can lie inside the box (a quick test; true may still visit none). */
    public abstract boolean mayIntersect(long[] offset, long[] count);

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
        public void coordinates(long position, long[] out) {
            for (int d = axes.length - 1; d >= 0; d--) {
                long size = axes[d].size();
                out[d] = axes[d].valueAt(position % size);
                position /= size;
            }
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
        public void coordinates(long position, long[] out) {
            System.arraycopy(coordinates[(int) position], 0, out, 0, rank);
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
