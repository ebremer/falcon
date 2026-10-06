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

    /**
     * The dataset this selection is taken from.
     *
     * @return the dataset
     * @throws HdfException if this selection is a region reference that could not be resolved
     */
    public Dataset dataset() {
        checkResolved();
        return dataset;
    }

    /**
     * The datatype the selection reads: the dataset's, or, for a {@linkplain #member(String) member}, the
     * member's.
     *
     * @return the datatype of each element read
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
     * @param name the member's name, as the compound datatype gives it
     * @return a selection of the same elements that reads that member alone
     * @throws IllegalArgumentException if the datatype read is not a compound
     * @throws java.util.NoSuchElementException if it has no member of that name
     */
    public Selection member(String name) {
        Datatype.Compound.Member member = ElementReader.member(datatype(), name, describe());
        return new Selection(dataset, offset, count, elements, shape, memberOffset + member.offset(),
                member.type(), null);
    }

    /**
     * True for a single rectangular block, read in its own {@link #shape()}.
     *
     * @return whether the selection is one block (not points, a hyperslab with gaps, or several blocks)
     */
    public boolean isRectangular() {
        checkResolved();
        return elements == null;
    }

    /**
     * The start coordinate of the selection (of its bounding box, if it is not a single block).
     *
     * @return a copy of the lowest selected index in each dimension
     */
    public long[] offset() {
        checkResolved();
        return offset.clone();
    }

    /**
     * The shape of the data a read returns: a block's shape; for a regular hyperslab with gaps, its
     * selected indices in each dimension; or {@code {elementCount}}.
     *
     * @return a copy of the shape, whose product is the {@linkplain #elementCount() element count}
     */
    public long[] shape() {
        checkResolved();
        return shape.clone();
    }

    /**
     * The number of selected elements.
     *
     * @return the elements a read returns (a point listed twice counts twice)
     */
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

    /**
     * Reads an integer selection as {@code int} values; exact, as {@link Dataset#readInts()}.
     *
     * @return every value, flattened row-major in the selection's {@link #shape()}
     * @throws HdfUnsupportedException if the datatype is not an integer type or a value does not fit
     */
    public int[] readInts() {
        return reader().ints();
    }

    /**
     * Reads an integer selection as {@code long} values; exact, as {@link Dataset#readLongs()}.
     *
     * @return every value, flattened row-major in the selection's {@link #shape()}
     * @throws HdfUnsupportedException if the datatype is not an integer type or a value does not fit
     */
    public long[] readLongs() {
        return reader().longs();
    }

    /**
     * Reads a floating-point or integer selection as {@code float} values, as {@link Dataset#readFloats()}.
     *
     * @return every value, flattened row-major in the selection's {@link #shape()}
     * @throws HdfUnsupportedException if the datatype is neither floating-point, complex, nor an integer type
     */
    public float[] readFloats() {
        return reader().floats();
    }

    /**
     * Reads a floating-point or integer selection as {@code double} values, as {@link Dataset#readDoubles()}.
     *
     * @return every value, flattened row-major in the selection's {@link #shape()}
     * @throws HdfUnsupportedException if the datatype is neither floating-point, complex, nor an integer type
     */
    public double[] readDoubles() {
        return reader().doubles();
    }

    /**
     * Reads complex values as interleaved (real, imaginary) pairs, as {@link Dataset#readComplexDoubles()}.
     *
     * @return two values per element, flattened row-major: its real part, then its imaginary part
     * @throws HdfUnsupportedException if the datatype is none of those {@link Dataset#readComplexDoubles()} reads
     */
    public double[] readComplexDoubles() {
        return reader().complexDoubles();
    }

    /**
     * Reads complex values as interleaved (real, imaginary) {@code float} pairs, as
     * {@link Dataset#readComplexFloats()}.
     *
     * @return two values per element, flattened row-major: its real part, then its imaginary part
     * @throws HdfUnsupportedException if the datatype is none of those {@link Dataset#readComplexDoubles()} reads
     */
    public float[] readComplexFloats() {
        return reader().complexFloats();
    }

    /**
     * Reads fixed- or variable-length strings, or enumeration names, as {@link Dataset#readStrings()}.
     *
     * @return one string per element, flattened row-major
     * @throws HdfUnsupportedException if the datatype is neither a string nor an enumeration
     */
    public String[] readStrings() {
        return reader().strings();
    }

    /**
     * Reads a variable-length sequence selection, one {@code int[]} row per element.
     *
     * @return one row per element, flattened row-major; a row may be empty
     * @throws HdfUnsupportedException if the datatype is not a variable-length sequence, or its base type
     *         does not read as {@code int}s
     */
    public int[][] readVlenInts() {
        return reader().vlenInts();
    }

    /**
     * Reads a variable-length sequence selection, one {@code long[]} row per element.
     *
     * @return one row per element, flattened row-major; a row may be empty
     * @throws HdfUnsupportedException if the datatype is not a variable-length sequence, or its base type
     *         does not read as {@code long}s
     */
    public long[][] readVlenLongs() {
        return reader().vlenLongs();
    }

    /**
     * Reads a variable-length sequence selection, one {@code double[]} row per element.
     *
     * @return one row per element, flattened row-major; a row may be empty
     * @throws HdfUnsupportedException if the datatype is not a variable-length sequence, or its base type
     *         does not read as {@code double}s
     */
    public double[][] readVlenDoubles() {
        return reader().vlenDoubles();
    }

    /**
     * Reads a variable-length sequence selection, one {@code float[]} row per element.
     *
     * @return one row per element, flattened row-major; a row may be empty
     * @throws HdfUnsupportedException if the datatype is not a variable-length sequence, or its base type
     *         does not read as {@code float}s
     */
    public float[][] readVlenFloats() {
        return reader().vlenFloats();
    }

    /**
     * Resolves object references, as {@link Dataset#readObjectReferences()}.
     *
     * @return the object each element points at (or null), flattened row-major
     * @throws HdfUnsupportedException as {@link Dataset#readObjectReferences()} does
     * @throws HdfException for a reference into a file that is not found
     */
    public Hdf5Object[] readObjectReferences() {
        return reader().objectReferences();
    }

    /**
     * Resolves region references, as {@link Dataset#readRegionReferences()}.
     *
     * @return the selection each element refers to (or null), flattened row-major
     * @throws HdfUnsupportedException if the datatype is neither a region nor a revised reference
     */
    public Selection[] readRegionReferences() {
        return reader().regionReferences();
    }

    /**
     * Resolves revised attribute references, as {@link Dataset#readAttributeReferences()}.
     *
     * @return the attribute each element names (or null), flattened row-major
     * @throws HdfUnsupportedException as {@link Dataset#readAttributeReferences()} does
     * @throws HdfException if an element points into a file that is not found
     * @throws HdfFormatException if a referenced attribute does not exist
     */
    public Attribute[] readAttributeReferences() {
        return reader().attributeReferences();
    }

    /**
     * The selected elements' bytes as stored, in the datatype's byte order (chunk filters undone).
     *
     * @return the datatype's size in bytes for each element, in the selection's order (for a member, its bytes
     *         of each element)
     */
    public byte[] readRawBytes() {
        return reader().rawBytes();
    }

    /**
     * Reads the selection into the most natural Java value, as {@link Dataset#read()}.
     *
     * @return the elements, flattened row-major, in the form {@link Dataset#read()} lists
     * @throws HdfUnsupportedException as {@link Dataset#read()} does
     */
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
