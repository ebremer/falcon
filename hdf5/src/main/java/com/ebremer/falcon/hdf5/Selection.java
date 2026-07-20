package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Elements;
import java.lang.foreign.MemorySegment;

/**
 * A rectangular sub-region (hyperslab) of a {@link Dataset}, created by
 * {@link Dataset#select(long[], long[])}. Reading returns just the selected elements, flattened
 * row-major in the shape of the selection.
 *
 * <pre>{@code
 * int[] slab = dataset.select(new long[]{1, 2}, new long[]{2, 3}).readInts();
 * }</pre>
 */
public final class Selection {

    private final Dataset dataset;
    private final long[] offset;
    private final long[] count;

    Selection(Dataset dataset, long[] offset, long[] count) {
        this.dataset = dataset;
        this.offset = offset.clone();
        this.count = count.clone();
    }

    /** The dataset this selection is taken from. */
    public Dataset dataset() {
        return dataset;
    }

    /** The start coordinate of the selection in each dimension. */
    public long[] offset() {
        return offset.clone();
    }

    /** The shape of the selection. */
    public long[] shape() {
        return count.clone();
    }

    public int[] readInts() {
        return Elements.toInts(data(), elementCount(), dataset.datatype());
    }

    public long[] readLongs() {
        return Elements.toLongs(data(), elementCount(), dataset.datatype());
    }

    public float[] readFloats() {
        return Elements.toFloats(data(), elementCount(), dataset.datatype());
    }

    public double[] readDoubles() {
        return Elements.toDoubles(data(), elementCount(), dataset.datatype());
    }

    public String[] readStrings() {
        return Elements.toStrings(data(), elementCount(), dataset.datatype());
    }

    private MemorySegment data() {
        return dataset.selectionData(offset, count);
    }

    private int elementCount() {
        long n = 1;
        for (long c : count) {
            n *= c;
        }
        return com.ebremer.falcon.hdf5.data.Elements.checkedInt(n);
    }
}
