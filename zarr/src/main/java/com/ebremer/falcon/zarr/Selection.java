package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.data.ChunkAssembler;
import com.ebremer.falcon.zarr.data.ChunkWriter;
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

    /** Writes raw element bytes (C order, the array's byte order) into this region. */
    public void writeRawBytes(byte[] elements) {
        ChunkWriter.write(array.store, array.path, array.metadata(), offset, shape, elements);
    }

    /** Writes {@code values} into this region, narrowing to the array's data type. */
    public void writeDoubles(double[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromDoubles(values, dataType(), order()));
    }

    /** Writes {@code values} into this region, narrowing to the array's data type. */
    public void writeFloats(float[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromFloats(values, dataType(), order()));
    }

    /** Writes {@code values} into this region, narrowing to the array's data type. */
    public void writeLongs(long[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromLongs(values, dataType(), order()));
    }

    /** Writes {@code values} into this region, narrowing to the array's data type. */
    public void writeInts(int[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromInts(values, dataType(), order()));
    }

    private void checkLength(int given) {
        long expected = elementCount();
        if (given != expected) {
            throw new IllegalArgumentException(
                    "selection holds " + expected + " elements but got " + given);
        }
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
