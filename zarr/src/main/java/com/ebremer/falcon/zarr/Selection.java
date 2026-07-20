package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.data.ChunkAssembler;
import com.ebremer.falcon.zarr.data.Elements;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import java.nio.ByteOrder;

/**
 * A hyperslab of a {@link ZarrArray}: a rectangular region {@code [offset, offset+shape)} in element
 * coordinates. The read methods assemble the region from the array's chunks, touching only the chunks
 * that overlap it, and interpret the result as a typed Java array (row-major).
 *
 * <p>Obtain one from {@link ZarrArray#select(long[], long[])} or {@link ZarrArray#selectAll()}.
 */
public final class Selection {

    private final ZarrArray array;
    private final long[] offset;
    private final long[] shape;

    Selection(ZarrArray array, long[] offset, long[] shape) {
        this.array = array;
        this.offset = offset;
        this.shape = shape;
    }

    /** The selection's start in each dimension (a defensive copy). */
    public long[] offset() {
        return offset.clone();
    }

    /** The selection's extent in each dimension (a defensive copy). */
    public long[] shape() {
        return shape.clone();
    }

    /** The number of selected elements (the product of the shape). */
    public long elementCount() {
        long count = 1;
        for (long s : shape) {
            count *= s;
        }
        return count;
    }

    /** The raw decoded element bytes of the selection, in C order, each primitive in the array's byte order. */
    public byte[] readRawBytes() {
        return ChunkAssembler.assemble(array.store, array.path, array.metadata(), offset, shape);
    }

    /** The selected elements as {@code double}s (any numeric type, widened). */
    public double[] readDoubles() {
        return Elements.toDoubles(readRawBytes(), dataType(), order(), intCount());
    }

    /** The selected elements as {@code float}s (float data types only). */
    public float[] readFloats() {
        return Elements.toFloats(readRawBytes(), dataType(), order(), intCount());
    }

    /** The selected elements as {@code long}s (integer data types that fit). */
    public long[] readLongs() {
        return Elements.toLongs(readRawBytes(), dataType(), order(), intCount());
    }

    /** The selected elements as {@code int}s (integer data types that fit). */
    public int[] readInts() {
        return Elements.toInts(readRawBytes(), dataType(), order(), intCount());
    }

    private DataType dataType() {
        return array.dataType();
    }

    private ByteOrder order() {
        return array.metadata().pipeline().elementOrder();
    }

    private int intCount() {
        long count = elementCount();
        if (count > Integer.MAX_VALUE) {
            throw new ZarrException("selection of " + count + " elements is too large to read into an array");
        }
        return (int) count;
    }

    @Override
    public String toString() {
        return "Selection[" + array.path() + " offset=" + java.util.Arrays.toString(offset)
                + " shape=" + java.util.Arrays.toString(shape) + "]";
    }
}
