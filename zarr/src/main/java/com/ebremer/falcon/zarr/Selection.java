package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.data.ChunkAssembler;
import com.ebremer.falcon.zarr.data.ChunkWriter;
import com.ebremer.falcon.zarr.data.Elements;
import com.ebremer.falcon.zarr.data.VlenChunks;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
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
        return RegularChunkGrid.elementCount(shape); // no larger than the array's size, so it fits a long
    }

    /** The raw decoded element bytes of the selection, in C order, each primitive in the array's byte order. */
    public byte[] readRawBytes() {
        requireFixedSize();
        return ChunkAssembler.assemble(array.store, array.path, array.metadata(), array.chunkCache(),
                offset, shape);
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

    /**
     * The selected elements of an unsigned integer type, exactly: uint8, uint16, and uint32 as their values,
     * and uint64 as its 64 bits, so a value of 2<sup>63</sup> or more is a negative {@code long} that
     * {@link Long#toUnsignedString(long)} and {@link Long}'s other unsigned methods read correctly.
     * ({@link #readLongs()} refuses uint64, whose values may not fit a {@code long}.)
     *
     * @throws ZarrException if the data type is not an unsigned integer type
     */
    public long[] readUnsignedLongs() {
        return Elements.toUnsignedLongs(readRawBytes(), dataType(), order(), intCount());
    }

    /**
     * The selected elements of a complex type as {@code double}s, two per element: the real part, then the
     * imaginary, as numpy lays them out. Element {@code i} is {@code (result[2i], result[2i + 1])}; a
     * complex64's float parts widen exactly.
     *
     * @throws ZarrException if the data type is not complex64 or complex128
     */
    public double[] readComplex() {
        return Elements.toComplex(readRawBytes(), dataType(), order(), intCount());
    }

    /** Writes raw element bytes (C order, the array's byte order) into this region. */
    public void writeRawBytes(byte[] elements) {
        requireFixedSize();
        ChunkWriter.write(array.store, array.path, array.metadata(), array.chunkCache(),
                offset, shape, elements, array.writeEmptyChunks());
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

    /**
     * Writes {@code values} into this region of an unsigned integer array, each {@code long} taken as an
     * unsigned 64-bit value, as {@link #readUnsignedLongs()} gives them: uint64 stores every value exactly,
     * and a narrower type refuses one above its maximum.
     *
     * @throws IllegalArgumentException if a value is too large for the type (nothing is written)
     * @throws ZarrException            if the data type is not an unsigned integer type
     */
    public void writeUnsignedLongs(long[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromUnsignedLongs(values, dataType(), order()));
    }

    /**
     * Writes complex values into this region, two {@code double}s per element (the real part, then the
     * imaginary). A complex64 rounds each part to the nearest float and refuses a finite part beyond float's
     * range.
     *
     * @throws IllegalArgumentException if {@code values} does not hold two doubles per selected element, or a
     *                                  part is out of range (nothing is written)
     * @throws ZarrException            if the data type is not complex64 or complex128
     */
    public void writeComplex(double[] values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("complex values come in pairs (real, imaginary), but got "
                    + values.length + " doubles");
        }
        checkLength(values.length / 2);
        writeRawBytes(Elements.fromComplex(values, dataType(), order()));
    }

    /**
     * The selected elements as {@code String}s, in C order (the {@code string} data type only).
     *
     * @throws ZarrException if this array is not a variable-length string array
     */
    public String[] readStrings() {
        requireKind(DataTypeKind.STRING, "readStrings/writeStrings");
        return (String[]) VlenChunks.read(array.store, array.path, array.metadata(), offset, shape);
    }

    /**
     * Writes {@code values} into this region (the {@code string} data type only). A {@code null} element is
     * written as the empty string.
     *
     * @throws ZarrException if this array is not a variable-length string array
     */
    public void writeStrings(String[] values) {
        requireKind(DataTypeKind.STRING, "readStrings/writeStrings");
        checkLength(values.length);
        VlenChunks.write(array.store, array.path, array.metadata(), offset, shape, values,
                array.writeEmptyChunks());
    }

    /**
     * The selected elements as byte strings, in C order (the {@code variable_length_bytes} data type only).
     * Each {@code byte[]} is the caller's own.
     *
     * @throws ZarrException if this array is not a variable-length bytes array
     */
    public byte[][] readByteArrays() {
        requireKind(DataTypeKind.BYTES, "readByteArrays/writeByteArrays");
        return (byte[][]) VlenChunks.read(array.store, array.path, array.metadata(), offset, shape);
    }

    /**
     * Writes {@code values} into this region (the {@code variable_length_bytes} data type only). A
     * {@code null} element is written as an empty byte string. The arrays are encoded during the call and
     * not kept.
     *
     * @throws ZarrException if this array is not a variable-length bytes array
     */
    public void writeByteArrays(byte[][] values) {
        requireKind(DataTypeKind.BYTES, "readByteArrays/writeByteArrays");
        checkLength(values.length);
        VlenChunks.write(array.store, array.path, array.metadata(), offset, shape, values,
                array.writeEmptyChunks());
    }

    private void requireKind(DataTypeKind kind, String methods) {
        if (dataType().kind() != kind) {
            String needed = kind == DataTypeKind.STRING ? DataType.STRING.name() : DataType.BYTES.name();
            throw new ZarrException(methods + " requires the '" + needed + "' data type, not '"
                    + dataType().name() + "'");
        }
    }

    private void requireFixedSize() {
        if (dataType().isVariableLength()) {
            throw new ZarrException("the '" + dataType().name() + "' data type is variable-length; use "
                    + (dataType().kind() == DataTypeKind.STRING ? "readStrings()/writeStrings()"
                    : "readByteArrays()/writeByteArrays()"));
        }
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
