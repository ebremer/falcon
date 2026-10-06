package com.ebremer.falcon.hdf5;

import java.util.Arrays;

/**
 * A dataset's shape (object-header message 1): its dimensionality, current dimension sizes, and
 * optional maximum sizes.
 *
 * <p>A dimension whose maximum is {@link #UNLIMITED} can grow without bound. A {@link Kind#SCALAR}
 * space holds exactly one element; a {@link Kind#NULL} space holds none.
 */
public final class Dataspace {

    /** Maximum-dimension value marking an unlimited (extendible) dimension. */
    public static final long UNLIMITED = -1L;

    /** The three dataspace kinds. */
    public enum Kind {
        /** One element, with no dimensions. */
        SCALAR,
        /** An array of one or more dimensions. */
        SIMPLE,
        /** No elements at all. */
        NULL
    }

    private final int version;
    private final Kind kind;
    private final long[] dimensions;
    private final long[] maxDimensions; // null if not stored

    /**
     * Constructs a dataspace value. Normally obtained via {@link Dataset#dataspace()}.
     *
     * @param version       the dataspace message version (1 or 2)
     * @param kind          scalar, simple, or null
     * @param dimensions    the current size of each dimension (empty for scalar and null spaces); not copied
     * @param maxDimensions the maximum size of each dimension ({@link #UNLIMITED} where extendible), or
     *                      {@code null} if none are stored; not copied
     */
    public Dataspace(int version, Kind kind, long[] dimensions, long[] maxDimensions) {
        this.version = version;
        this.kind = kind;
        this.dimensions = dimensions;
        this.maxDimensions = maxDimensions;
    }

    /**
     * The dataspace message version (1 or 2).
     *
     * @return the message version
     */
    public int version() {
        return version;
    }

    /**
     * Whether the space is scalar, simple, or null.
     *
     * @return the dataspace kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * The number of dimensions (0 for scalar and null spaces).
     *
     * @return the rank
     */
    public int rank() {
        return dimensions.length;
    }

    /**
     * The current size of each dimension.
     *
     * @return a copy of the dimension sizes, empty for scalar and null spaces
     */
    public long[] dimensions() {
        return dimensions.clone();
    }

    /**
     * The maximum size of each dimension.
     *
     * @return a copy of the maximum sizes ({@link #UNLIMITED} where extendible), or {@code null} if the
     *         message stores none (the maximum is then the current size)
     */
    public long[] maxDimensions() {
        return maxDimensions == null ? null : maxDimensions.clone();
    }

    /**
     * Whether dimension {@code i} is unlimited.
     *
     * @param i the dimension, from 0
     * @return true if its maximum size is {@link #UNLIMITED}; false if it has a fixed maximum or the
     *         message stores no maximum sizes
     */
    public boolean isUnlimited(int i) {
        return maxDimensions != null && maxDimensions[i] == UNLIMITED;
    }

    /**
     * The number of elements: 1 for scalar, 0 for null, otherwise the product of the dimensions.
     *
     * @return the element count
     * @throws HdfFormatException if the dimensions are negative or their product overflows a {@code long}
     */
    public long elementCount() {
        if (kind == Kind.NULL) {
            return 0;
        }
        long n = 1;
        for (long d : dimensions) {
            if (d < 0) {
                throw new HdfFormatException("dataspace dimension " + Long.toUnsignedString(d) + " is too large");
            }
            try {
                n = Math.multiplyExact(n, d);
            } catch (ArithmeticException e) {
                throw new HdfFormatException("dataspace element count overflows: " + this);
            }
        }
        return n;
    }

    @Override
    public String toString() {
        return "Dataspace[" + kind + " dims=" + Arrays.toString(dimensions)
                + (maxDimensions == null ? "" : " max=" + Arrays.toString(maxDimensions)) + "]";
    }
}
