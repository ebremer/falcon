package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Elements;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Selected elements of a {@link Dataset}. {@link Dataset#select(long[], long[])} makes a rectangular
 * sub-region (a hyperslab), read flattened row-major in the shape of the selection:
 *
 * <pre>{@code
 * int[] slab = dataset.select(new long[]{1, 2}, new long[]{2, 3}).readInts();
 * }</pre>
 *
 * A region reference ({@link Dataset#readRegionReferences()}) may also select individual points or
 * several blocks. Such a selection is not {@linkplain #isRectangular() rectangular}: it reads as a flat
 * array of its elements, in the order libhdf5 visits them (points as listed, blocks in row-major
 * order), its {@link #shape()} is {@code {elementCount}}, and its {@link #offset()} is the corner of the
 * box that bounds it.
 */
public final class Selection {

    private final Dataset dataset;
    private final long[] offset;
    private final long[] count;
    private final long[][] coordinates; // null for a rectangular selection
    private final long[] boxShape;      // bounding box of a non-rectangular selection
    private final HdfException failure; // a region reference that could not be resolved

    Selection(Dataset dataset, long[] offset, long[] count) {
        this(dataset, offset, count, null, null, null);
    }

    private Selection(Dataset dataset, long[] offset, long[] count, long[][] coordinates, long[] boxShape,
                      HdfException failure) {
        this.dataset = dataset;
        this.offset = offset == null ? null : offset.clone();
        this.count = count == null ? null : count.clone();
        this.coordinates = coordinates;
        this.boxShape = boxShape;
        this.failure = failure;
    }

    /** The elements at {@code coordinates} of {@code dataset}, read in the given order. */
    static Selection ofCoordinates(Dataset dataset, long[][] coordinates) {
        int rank = dataset.dataspace().dimensions().length;
        long[] low = new long[rank];
        long[] high = new long[rank];
        java.util.Arrays.fill(low, Long.MAX_VALUE);
        java.util.Arrays.fill(high, -1);
        for (long[] coordinate : coordinates) {
            for (int d = 0; d < rank; d++) {
                low[d] = Math.min(low[d], coordinate[d]);
                high[d] = Math.max(high[d], coordinate[d]);
            }
        }
        long[] box = new long[rank];
        for (int d = 0; d < rank; d++) {
            if (coordinates.length == 0) {
                low[d] = 0;
            } else {
                box[d] = high[d] - low[d] + 1;
            }
        }
        return new Selection(dataset, low, new long[] {coordinates.length}, coordinates, box, null);
    }

    /**
     * A region reference that could not be resolved (for example, one pointing at a deleted dataset):
     * every use throws {@code failure}, so one bad reference does not fail the others read with it.
     */
    static Selection unresolved(HdfException failure) {
        return new Selection(null, null, null, null, null, failure);
    }

    /** The dataset this selection is taken from. */
    public Dataset dataset() {
        checkResolved();
        return dataset;
    }

    /** True for a single rectangular block, read in its own {@link #shape()}. */
    public boolean isRectangular() {
        checkResolved();
        return coordinates == null;
    }

    /** The start coordinate of the selection (of its bounding box, if it is not rectangular). */
    public long[] offset() {
        checkResolved();
        return offset.clone();
    }

    /** The shape of the data a read returns: the block's shape, or {@code {elementCount}}. */
    public long[] shape() {
        checkResolved();
        return count.clone();
    }

    /** The number of selected elements. */
    public long elementCount() {
        checkResolved();
        if (coordinates != null) {
            return coordinates.length;
        }
        long n = 1;
        for (long c : count) {
            n = Math.multiplyExact(n, c);
        }
        return n;
    }

    public int[] readInts() {
        return Elements.toInts(data(), checkedCount(), dataset().datatype());
    }

    public long[] readLongs() {
        return Elements.toLongs(data(), checkedCount(), dataset().datatype());
    }

    public float[] readFloats() {
        return Elements.toFloats(data(), checkedCount(), dataset().datatype());
    }

    public double[] readDoubles() {
        return Elements.toDoubles(data(), checkedCount(), dataset().datatype());
    }

    public String[] readStrings() {
        return Elements.toStrings(data(), checkedCount(), dataset().datatype());
    }

    private MemorySegment data() {
        checkResolved();
        if (coordinates == null) {
            return dataset.selectionData(offset, count);
        }
        int size = dataset.datatype().size();
        byte[] out = new byte[Elements.checkedByteCount(coordinates.length, size)];
        if (coordinates.length == 0) {
            return MemorySegment.ofArray(out);
        }
        // Read the bounding box (only the chunks it overlaps), then pick each element out of it.
        MemorySegment box = dataset.selectionData(offset, boxShape);
        int rank = boxShape.length;
        for (int i = 0; i < coordinates.length; i++) {
            long flat = 0;
            for (int d = 0; d < rank; d++) {
                flat = flat * boxShape[d] + (coordinates[i][d] - offset[d]);
            }
            MemorySegment.copy(box, ValueLayout.JAVA_BYTE, flat * size, out, (int) ((long) i * size), size);
        }
        return MemorySegment.ofArray(out);
    }

    private int checkedCount() {
        return Elements.checkedInt(elementCount());
    }

    private void checkResolved() {
        if (failure != null) {
            throw failure;
        }
    }
}
