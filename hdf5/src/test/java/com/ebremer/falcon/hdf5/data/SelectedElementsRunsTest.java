package com.ebremer.falcon.hdf5.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Selected elements visited a run at a time (P2 PF8): {@code forEachRunInBox} visits, run by run, exactly
 * the elements {@code forEachInBox} visits one by one, in the same order, for regular hyperslabs of every
 * shape of block and stride and for listed points; and {@code gather} copies what an element-by-element
 * copy would.
 */
class SelectedElementsRunsTest {

    /** Each element visited, as its position followed by its coordinates. */
    private static List<long[]> elements(SelectedElements selection, long[] offset, long[] count) {
        List<long[]> out = new ArrayList<>();
        selection.forEachInBox(offset, count, (position, coordinates) -> out.add(entry(position, coordinates)));
        return out;
    }

    /** Each element of each run, expanded, as its position followed by its coordinates. */
    private static List<long[]> runs(SelectedElements selection, long[] offset, long[] count) {
        List<long[]> out = new ArrayList<>();
        selection.forEachRunInBox(offset, count, (position, coordinates, length, step) -> {
            for (long k = 0; k < length; k++) {
                long[] at = coordinates.clone();
                if (at.length > 0) {
                    at[at.length - 1] += k * step;
                }
                out.add(entry(position + k, at));
            }
        });
        return out;
    }

    private static long[] entry(long position, long[] coordinates) {
        long[] e = new long[coordinates.length + 1];
        e[0] = position;
        System.arraycopy(coordinates, 0, e, 1, coordinates.length);
        return e;
    }

    private static void assertSameVisits(List<long[]> expected, List<long[]> actual, String label) {
        assertEquals(expected.size(), actual.size(), label);
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), label + ", element " + i);
        }
    }

    @Test
    void runsVisitWhatElementsVisitForEveryHyperslab() {
        Random random = new Random(42);
        for (int trial = 0; trial < 3000; trial++) {
            int rank = 1 + random.nextInt(3);
            long[] start = new long[rank];
            long[] stride = new long[rank];
            long[] count = new long[rank];
            long[] block = new long[rank];
            long[] extent = new long[rank];
            for (int d = 0; d < rank; d++) {
                block[d] = 1 + random.nextInt(4);
                // stride == block (touching blocks), stride > block (gaps), and blocks of one alike
                stride[d] = block[d] + (random.nextInt(3) == 0 ? 0 : random.nextInt(5));
                start[d] = random.nextInt(4);
                count[d] = 1 + random.nextInt(5);
                extent[d] = start[d] + (count[d] - 1) * stride[d] + block[d];
            }
            SelectedElements selection = SelectedElements.hyperslab(start, stride, count, block);
            long[] offset = new long[rank];
            long[] box = new long[rank];
            for (int d = 0; d < rank; d++) {
                offset[d] = random.nextInt((int) extent[d] + 1);
                box[d] = random.nextInt((int) (extent[d] - offset[d]) + 2);
            }
            String label = "start " + Arrays.toString(start) + " stride " + Arrays.toString(stride) + " count "
                    + Arrays.toString(count) + " block " + Arrays.toString(block) + " box " + Arrays.toString(offset)
                    + " + " + Arrays.toString(box);
            assertSameVisits(elements(selection, offset, box), runs(selection, offset, box), label);
        }
    }

    @Test
    void runsOfListedPointsVisitWhatElementsVisit() {
        // Points along a row (a run), repeated, out of order, and in another row.
        long[][] points = {{1, 2}, {1, 3}, {1, 4}, {1, 4}, {1, 5}, {0, 0}, {2, 7}, {2, 8}, {1, 9}, {1, 8}};
        SelectedElements selection = SelectedElements.points(points, 2);
        for (long[] box : new long[][] {{0, 0, 3, 10}, {1, 3, 2, 3}, {0, 0, 1, 1}, {2, 8, 1, 1}}) {
            long[] offset = {box[0], box[1]};
            long[] count = {box[2], box[3]};
            assertSameVisits(elements(selection, offset, count), runs(selection, offset, count), Arrays.toString(box));
        }
        List<Long> lengths = new ArrayList<>();
        selection.forEachRunInBox(new long[] {0, 0}, new long[] {3, 10}, (p, c, length, step) -> lengths.add(length));
        assertEquals(List.of(3L, 2L, 1L, 2L, 1L, 1L), lengths); // {1,2..4}, {1,4..5}, {0,0}, {2,7..8}, {1,9}, {1,8}
    }

    @Test
    void gatherCopiesWhatAnElementByElementCopyWould() {
        Random random = new Random(7);
        long[] dims = {13, 17};
        byte[] data = new byte[(int) (dims[0] * dims[1] * 4)];
        random.nextBytes(data);
        SelectedElements.Source source = (from, out, at, length) -> System.arraycopy(data, (int) from, out, at, length);
        for (int trial = 0; trial < 500; trial++) {
            long[] start = new long[2];
            long[] stride = new long[2];
            long[] count = new long[2];
            long[] block = new long[2];
            for (int d = 0; d < 2; d++) {
                block[d] = 1 + random.nextInt(3);
                stride[d] = block[d] + random.nextInt(3);
                start[d] = random.nextInt(3);
                long most = (dims[d] - start[d] - block[d]) / stride[d] + 1;
                count[d] = 1 + random.nextInt((int) most);
            }
            SelectedElements selection = random.nextBoolean() ? SelectedElements.hyperslab(start, stride, count, block)
                    : SelectedElements.points(randomPoints(random, dims), 2);
            byte[] expected = new byte[(int) selection.count() * 4];
            long[] at = new long[2];
            for (long p = 0; p < selection.count(); p++) {
                selection.coordinates(p, at);
                System.arraycopy(data, (int) ((at[0] * dims[1] + at[1]) * 4), expected, (int) (p * 4), 4);
            }
            assertArrayEquals(expected, selection.gather(source, new long[2], dims, 4));
        }
    }

    private static long[][] randomPoints(Random random, long[] dims) {
        long[][] points = new long[1 + random.nextInt(20)][];
        for (int i = 0; i < points.length; i++) {
            points[i] = i > 0 && random.nextBoolean() && points[i - 1][1] + 1 < dims[1]
                    ? new long[] {points[i - 1][0], points[i - 1][1] + 1} // next to the one before
                    : new long[] {random.nextInt((int) dims[0]), random.nextInt((int) dims[1])};
        }
        return points;
    }
}
