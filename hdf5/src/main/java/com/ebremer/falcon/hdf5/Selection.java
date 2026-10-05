package com.ebremer.falcon.hdf5;

import com.ebremer.falcon.hdf5.data.Elements;
import com.ebremer.falcon.hdf5.data.SelectedElements;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.lang.foreign.MemorySegment;

/**
 * Selected elements of a {@link Dataset}, read with the same readers a dataset has. A selection reads
 * only the data it needs: the chunks that hold selected elements, or the selected runs of contiguous data.
 *
 * <ul>
 *   <li>{@link Dataset#select(long[], long[])} selects a rectangular block (a hyperslab), read flattened
 *       row-major in the block's shape:
 *       <pre>{@code
 * int[] slab = dataset.select(new long[]{1, 2}, new long[]{2, 3}).readInts();
 * }</pre></li>
 *   <li>{@link Dataset#select(long[], long[], long[], long[])} selects a regular hyperslab with gaps
 *       (every other row, say), read in the shape of its selected indices: {@code count[d] * block[d]} in
 *       each dimension.</li>
 *   <li>{@link Dataset#selectPoints(long[][])} selects single elements, read in the order given.</li>
 *   <li>A region reference ({@link Dataset#readRegionReferences()}) may select individual points or
 *       several blocks, read in the order libhdf5 visits them (points as listed, blocks in row-major
 *       order).</li>
 * </ul>
 *
 * Only a single block is {@linkplain #isRectangular() rectangular}. Points and a region reference's blocks
 * read as a flat array whose {@link #shape()} is {@code {elementCount}}; for every selection but a single
 * block, {@link #offset()} is the corner of the box that bounds it.
 *
 * <p>{@link #member(String)} narrows a selection of a compound dataset to one member of each element,
 * which then reads like a dataset of the member's type.
 */
public final class Selection {

    private final Dataset dataset;
    private final long[] offset;
    private final long[] count;              // a rectangular block's shape; null otherwise
    private final SelectedElements elements; // the elements of any other selection; null for a block
    private final long[] shape;              // the shape a read returns
    private final int memberOffset;          // where in each element the type read lies
    private final Datatype memberType;       // the type read, if a member; null for the dataset's own
    private final HdfException failure;      // a region reference that could not be resolved

    Selection(Dataset dataset, long[] offset, long[] count) {
        this(dataset, offset.clone(), count.clone(), null, count.clone(), 0, null, null);
    }

    private Selection(Dataset dataset, long[] offset, long[] count, SelectedElements elements, long[] shape,
                      int memberOffset, Datatype memberType, HdfException failure) {
        this.dataset = dataset;
        this.offset = offset;
        this.count = count;
        this.elements = elements;
        this.shape = shape;
        this.memberOffset = memberOffset;
        this.memberType = memberType;
        this.failure = failure;
    }

    /** The {@code elements} of {@code dataset}, read in their order, in the given shape. */
    static Selection of(Dataset dataset, SelectedElements elements, long[] shape) {
        return new Selection(dataset, elements.lowCorner(), null, elements, shape, 0, null, null);
    }

    /**
     * A region reference that could not be resolved (for example, one pointing at a deleted dataset):
     * every use throws {@code failure}, so one bad reference does not fail the others read with it.
     */
    static Selection unresolved(HdfException failure) {
        return new Selection(null, null, null, null, null, 0, null, failure);
    }

    /** The dataset this selection is taken from. */
    public Dataset dataset() {
        checkResolved();
        return dataset;
    }

    /**
     * The datatype the selection reads: the dataset's, or, for a {@linkplain #member(String) member}, the
     * member's.
     */
    public Datatype datatype() {
        checkResolved();
        return memberType != null ? memberType : dataset.datatype();
    }

    /**
     * The member {@code name} of each selected element of a compound dataset (or of a member that is
     * itself a compound): a selection of the same elements that reads only that member, like a dataset of
     * the member's type.
     *
     * <pre>{@code
     * double[] b = dataset.member("b").readDoubles();             // column "b" of every element
     * int[] x = dataset.select(offset, count).member("pos").member("x").readInts();
     * }</pre>
     *
     * @throws IllegalArgumentException if the datatype read is not a compound
     * @throws java.util.NoSuchElementException if it has no member of that name
     */
    public Selection member(String name) {
        Datatype.Compound.Member member = ElementReader.member(datatype(), name, describe());
        return new Selection(dataset, offset, count, elements, shape, memberOffset + member.offset(),
                member.type(), null);
    }

    /** True for a single rectangular block, read in its own {@link #shape()}. */
    public boolean isRectangular() {
        checkResolved();
        return elements == null;
    }

    /** The start coordinate of the selection (of its bounding box, if it is not a single block). */
    public long[] offset() {
        checkResolved();
        return offset.clone();
    }

    /**
     * The shape of the data a read returns: a block's shape; for a regular hyperslab with gaps, its
     * selected indices in each dimension; or {@code {elementCount}}.
     */
    public long[] shape() {
        checkResolved();
        return shape.clone();
    }

    /** The number of selected elements. */
    public long elementCount() {
        checkResolved();
        if (elements != null) {
            return elements.count();
        }
        long n = 1;
        for (long c : count) {
            n = Math.multiplyExact(n, c);
        }
        return n;
    }

    /** Reads an integer selection as {@code int} values; exact, as {@link Dataset#readInts()}. */
    public int[] readInts() {
        return reader().ints();
    }

    /** Reads an integer selection as {@code long} values; exact, as {@link Dataset#readLongs()}. */
    public long[] readLongs() {
        return reader().longs();
    }

    /** Reads a floating-point or integer selection as {@code float} values, as {@link Dataset#readFloats()}. */
    public float[] readFloats() {
        return reader().floats();
    }

    /** Reads a floating-point or integer selection as {@code double} values, as {@link Dataset#readDoubles()}. */
    public double[] readDoubles() {
        return reader().doubles();
    }

    /** Reads complex values as interleaved (real, imaginary) pairs, as {@link Dataset#readComplexDoubles()}. */
    public double[] readComplexDoubles() {
        return reader().complexDoubles();
    }

    /** Reads complex values as interleaved (real, imaginary) {@code float} pairs, as {@link Dataset#readComplexFloats()}. */
    public float[] readComplexFloats() {
        return reader().complexFloats();
    }

    /** Reads fixed- or variable-length strings, or enumeration names, as {@link Dataset#readStrings()}. */
    public String[] readStrings() {
        return reader().strings();
    }

    /** Reads a variable-length sequence selection, one {@code int[]} row per element. */
    public int[][] readVlenInts() {
        return reader().vlenInts();
    }

    /** Reads a variable-length sequence selection, one {@code long[]} row per element. */
    public long[][] readVlenLongs() {
        return reader().vlenLongs();
    }

    /** Reads a variable-length sequence selection, one {@code double[]} row per element. */
    public double[][] readVlenDoubles() {
        return reader().vlenDoubles();
    }

    /** Reads a variable-length sequence selection, one {@code float[]} row per element. */
    public float[][] readVlenFloats() {
        return reader().vlenFloats();
    }

    /** Resolves object references, as {@link Dataset#readObjectReferences()}. */
    public Hdf5Object[] readObjectReferences() {
        return reader().objectReferences();
    }

    /** Resolves region references, as {@link Dataset#readRegionReferences()}. */
    public Selection[] readRegionReferences() {
        return reader().regionReferences();
    }

    /** Resolves revised attribute references, as {@link Dataset#readAttributeReferences()}. */
    public Attribute[] readAttributeReferences() {
        return reader().attributeReferences();
    }

    /** The selected elements' bytes as stored, in the datatype's byte order (chunk filters undone). */
    public byte[] readRawBytes() {
        return reader().rawBytes();
    }

    /** Reads the selection into the most natural Java value, as {@link Dataset#read()}. */
    public Object read() {
        return reader().natural();
    }

    private ElementReader reader() {
        checkResolved();
        return new ElementReader(dataset.ctx, datatype(), Elements.checkedInt(elementCount()), this::data, describe());
    }

    /** The bytes of the selected elements (of the member read, if one is), in the selection's order. */
    private MemorySegment data() {
        MemorySegment raw = elements == null ? dataset.selectionData(offset, count) : dataset.selectedData(elements);
        if (memberType == null) {
            return raw;
        }
        return ElementReader.column(raw, Elements.checkedInt(elementCount()), dataset.datatype().size(),
                memberOffset, memberType.size());
    }

    private String describe() {
        return "a selection of " + dataset.label();
    }

    private void checkResolved() {
        if (failure != null) {
            throw failure;
        }
    }
}
