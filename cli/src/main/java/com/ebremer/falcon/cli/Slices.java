package com.ebremer.falcon.cli;

import java.util.Arrays;

/**
 * A selection as numpy writes one: per dimension an index ({@code 5}, or {@code -1} for the last), which
 * drops the dimension from the result, or a range {@code start:stop:step} whose parts may each be left out
 * ({@code :}, {@code 10:}, {@code ::2}) and whose start and stop may count from the end ({@code -10:}).
 * Dimensions not given are taken whole. Steps must be positive.
 *
 * @param start   the first index taken in each dimension
 * @param count   how many indices are taken in each dimension
 * @param step    the distance between the indices taken
 * @param squeeze whether a dimension was given as a single index, and so is left out of the result
 */
record Slices(long[] start, long[] count, long[] step, boolean[] squeeze) {

    /**
     * Parses a selection of an array of {@code shape}.
     *
     * @param spec  the selection, such as {@code 0,10:20,::4}; null or empty for everything
     * @param shape the array's shape
     * @return the selection
     * @throws UsageException if the selection is malformed, has more dimensions than the array, or an index
     *                        is outside it
     */
    static Slices parse(String spec, long[] shape) {
        int rank = shape.length;
        long[] start = new long[rank];
        long[] count = shape.clone();
        long[] step = new long[rank];
        Arrays.fill(step, 1);
        boolean[] squeeze = new boolean[rank];
        if (spec == null || spec.isBlank()) {
            return new Slices(start, count, step, squeeze);
        }
        String[] parts = spec.split(",", -1);
        if (parts.length > rank) {
            throw new UsageException("--slice gives " + parts.length + " dimensions, but the array has " + rank);
        }
        for (int d = 0; d < parts.length; d++) {
            String part = parts[d].strip();
            long n = shape[d];
            try {
                if (!part.contains(":")) {
                    long i = Long.parseLong(part);
                    long at = i < 0 ? i + n : i;
                    if (at < 0 || at >= n) {
                        throw new UsageException("--slice index " + i + " is outside dimension " + d + " of size " + n);
                    }
                    start[d] = at;
                    count[d] = 1;
                    squeeze[d] = true;
                    continue;
                }
                String[] range = part.split(":", -1);
                if (range.length > 3) {
                    throw new UsageException("--slice range '" + part + "' has more than start:stop:step");
                }
                long s = range.length > 2 && !range[2].isBlank() ? Long.parseLong(range[2].strip()) : 1;
                if (s <= 0) {
                    throw new UsageException("--slice step must be positive, not " + s);
                }
                long a = range[0].isBlank() ? 0 : clamp(Long.parseLong(range[0].strip()), n);
                long b = range[1].isBlank() ? n : clamp(Long.parseLong(range[1].strip()), n);
                start[d] = a;
                step[d] = s;
                count[d] = b <= a ? 0 : (b - a + s - 1) / s;
            } catch (NumberFormatException e) {
                throw new UsageException("--slice part '" + part + "' is not an index or a start:stop:step range");
            }
        }
        return new Slices(start, count, step, squeeze);
    }

    /** A start or stop as Python reads it: from the end if negative, then within {@code [0, n]}. */
    private static long clamp(long i, long n) {
        long at = i < 0 ? i + n : i;
        return Math.max(0, Math.min(n, at));
    }

    /** {@return the shape of the result: the counts of the dimensions not given as an index} */
    long[] resultShape() {
        int r = 0;
        for (boolean s : squeeze) {
            r += s ? 0 : 1;
        }
        long[] out = new long[r];
        int o = 0;
        for (int d = 0; d < count.length; d++) {
            if (!squeeze[d]) {
                out[o++] = count[d];
            }
        }
        return out;
    }
}
