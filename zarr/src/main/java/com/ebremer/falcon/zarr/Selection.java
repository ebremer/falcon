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

    /** {@return the selection's start in each dimension (a defensive copy)} */
    public long[] offset() {
        return offset.clone();
    }

    /** {@return the selection's extent in each dimension (a defensive copy)} */
    public long[] shape() {
        return shape.clone();
    }

    /** {@return the number of selected elements (the product of the shape)} */
    public long elementCount() {
        return RegularChunkGrid.elementCount(shape); // no larger than the array's size, so it fits a long
    }

    /**
     * The raw decoded element bytes of the selection, in C order, each primitive in the array's byte order.
     *
     * @return {@link #elementCount()} elements of the data type's size each
     * @throws ZarrException if the data type is variable-length
     */
    public byte[] readRawBytes() {
        requireFixedSize();
        return ChunkAssembler.assemble(array.store, array.path, array.metadata(), array.chunkCache(),
                offset, shape);
    }

    /**
     * The selected elements as {@code double}s (bool, integer, and float types, widened; bool as 0 or 1).
     * An int64 or uint64 beyond 2<sup>53</sup> in magnitude may round.
     *
     * @return the elements, in C order
     * @throws ZarrException if the data type is complex, raw, or variable-length
     */
    public double[] readDoubles() {
        return Elements.toDoubles(readRawBytes(), dataType(), order(), intCount());
    }

    /**
     * The selected elements as {@code float}s (float data types only; a float64 rounds).
     *
     * @return the elements, in C order
     * @throws ZarrException if the data type is not a float type
     */
    public float[] readFloats() {
        return Elements.toFloats(readRawBytes(), dataType(), order(), intCount());
    }

    /**
     * The selected elements as {@code long}s (bool and integer data types that fit: all but uint64).
     *
     * @return the elements, in C order
     * @throws ZarrException if the data type is not bool or an integer type, or is uint64 (use
     *                       {@link #readUnsignedLongs()})
     */
    public long[] readLongs() {
        return Elements.toLongs(readRawBytes(), dataType(), order(), intCount());
    }

    /**
     * The selected elements as {@code int}s (bool and integer data types that fit: up to int32 and uint16).
     *
     * @return the elements, in C order
     * @throws ZarrException if the data type is not bool or an integer type, or is int64, uint32, or uint64
     */
    public int[] readInts() {
        return Elements.toInts(readRawBytes(), dataType(), order(), intCount());
    }

    /**
     * The selected elements of an unsigned integer type, exactly: uint8, uint16, and uint32 as their values,
     * and uint64 as its 64 bits, so a value of 2<sup>63</sup> or more is a negative {@code long} that
     * {@link Long#toUnsignedString(long)} and {@link Long}'s other unsigned methods read correctly.
     * ({@link #readLongs()} refuses uint64, whose values may not fit a {@code long}.)
     *
     * @return the elements, in C order
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
     * @return {@code 2 * elementCount()} doubles
     * @throws ZarrException if the data type is not complex64 or complex128
     */
    public double[] readComplex() {
        return Elements.toComplex(readRawBytes(), dataType(), order(), intCount());
    }

    /**
     * Writes raw element bytes (C order, the array's byte order) into this region.
     *
     * @param elements {@link #elementCount()} elements of the data type's size each
     * @throws IllegalArgumentException if {@code elements} is not that long
     * @throws ZarrException            if the data type is variable-length
     */
    public void writeRawBytes(byte[] elements) {
        requireFixedSize();
        ChunkWriter.write(array.store, array.path, array.metadata(), array.chunkCache(),
                offset, shape, elements, array.writeEmptyChunks());
    }

    /**
     * Writes {@code values} into this region, narrowing to the array's data type: an integer type takes
     * only a whole number in its range, a float type rounds to nearest, and bool takes nonzero (NaN
     * included) as true.
     *
     * @param values one value per selected element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeDoubles(double[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromDoubles(values, dataType(), order()));
    }

    /**
     * Writes {@code values} into this region, narrowing to the array's data type as
     * {@link #writeDoubles(double[])} does.
     *
     * @param values one value per selected element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeFloats(float[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromFloats(values, dataType(), order()));
    }

    /**
     * Writes {@code values} into this region, narrowing to the array's data type: an integer type must hold
     * each value exactly, a float type rounds, and bool takes nonzero as true.
     *
     * @param values one value per selected element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeLongs(long[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromLongs(values, dataType(), order()));
    }

    /**
     * Writes {@code values} into this region, narrowing to the array's data type as
     * {@link #writeLongs(long[])} does.
     *
     * @param values one value per selected element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeInts(int[] values) {
        checkLength(values.length);
        writeRawBytes(Elements.fromInts(values, dataType(), order()));
    }

    /**
     * Writes {@code values} into this region of an unsigned integer array, each {@code long} taken as an
     * unsigned 64-bit value, as {@link #readUnsignedLongs()} gives them: uint64 stores every value exactly,
     * and a narrower type refuses one above its maximum.
     *
     * @param values one value per selected element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or a value is too large for
     *                                  the type (nothing is written)
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
     * @param values {@code 2 * elementCount()} doubles, in C order
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
     * The selected elements as {@code String}s, in C order: the {@code string} data type, or
     * {@code fixed_length_utf32}, whose elements are read less their trailing NULs, as numpy reads them.
     *
     * @return the strings
     * @throws ZarrException if this array's data type is neither
     */
    public String[] readStrings() {
        if (dataType().kind() == DataTypeKind.FIXED_STRING) {
            return Elements.toFixedStrings(readRawBytes(), dataType(), order(), intCount());
        }
        requireKind(DataTypeKind.STRING, "readStrings/writeStrings");
        return (String[]) VlenChunks.read(array.store, array.path, array.metadata(), offset, shape);
    }

    /**
     * Writes {@code values} into this region: the {@code string} data type, or {@code fixed_length_utf32},
     * which refuses a string of more code points than it holds. A {@code null} element is written as the
     * empty string.
     *
     * @param values the strings, one per selected element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or a string does not fit a
     *                                  {@code fixed_length_utf32} element (nothing is written)
     * @throws ZarrException            if this array's data type is neither
     */
    public void writeStrings(String[] values) {
        if (dataType().kind() == DataTypeKind.FIXED_STRING) {
            checkLength(values.length);
            writeRawBytes(Elements.fromFixedStrings(values, dataType(), order()));
            return;
        }
        requireKind(DataTypeKind.STRING, "readStrings/writeStrings");
        checkLength(values.length);
        VlenChunks.write(array.store, array.path, array.metadata(), offset, shape, values,
                array.writeEmptyChunks());
    }

    /**
     * The selected elements as byte strings, in C order. Each {@code byte[]} is the caller's own. The data
     * types that hold byte strings:
     * <ul>
     *   <li>{@code variable_length_bytes};</li>
     *   <li>{@code null_terminated_bytes}, each element less its trailing NULs, as numpy reads them;</li>
     *   <li>{@code raw_bytes} and {@code r*}, each element whole;</li>
     *   <li>{@code struct}, each element whole, every number in it little-endian whatever the array's byte
     *       order: field {@code f} of element {@code e} starts at {@code dataType().fieldOffset(f)}.</li>
     * </ul>
     *
     * @return the byte strings
     * @throws ZarrException if this array's data type is none of these
     */
    public byte[][] readByteArrays() {
        if (isFixedBytes()) {
            return Elements.toFixedByteArrays(readRawBytes(), dataType(), order(), intCount());
        }
        requireKind(DataTypeKind.BYTES, "readByteArrays/writeByteArrays");
        return (byte[][]) VlenChunks.read(array.store, array.path, array.metadata(), offset, shape);
    }

    /**
     * Writes {@code values} into this region, one byte string per element, for the data types
     * {@link #readByteArrays()} reads: a {@code variable_length_bytes} element takes any length, a
     * {@code null_terminated_bytes} element up to its type's length (padded with NULs), and a
     * {@code raw_bytes}, {@code r*}, or {@code struct} element exactly its length (a struct's numbers
     * little-endian). A {@code null} element is written as an empty byte string, or all zero bytes. The
     * arrays are encoded during the call and not kept.
     *
     * @param values the byte strings, one per selected element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or an element the wrong length
     *                                  for a fixed-size type (nothing is written)
     * @throws ZarrException            if this array's data type does not hold byte strings
     */
    public void writeByteArrays(byte[][] values) {
        if (isFixedBytes()) {
            checkLength(values.length);
            writeRawBytes(Elements.fromFixedByteArrays(values, dataType(), order()));
            return;
        }
        requireKind(DataTypeKind.BYTES, "readByteArrays/writeByteArrays");
        checkLength(values.length);
        VlenChunks.write(array.store, array.path, array.metadata(), offset, shape, values,
                array.writeEmptyChunks());
    }

    private boolean isFixedBytes() {
        return switch (dataType().kind()) {
            case FIXED_BYTES, RAW_BYTES, RAW, STRUCT -> true;
            default -> false;
        };
    }

    private void requireKind(DataTypeKind kind, String methods) {
        if (dataType().kind() != kind) {
            String needed = kind == DataTypeKind.STRING ? "'string' or 'fixed_length_utf32'"
                    : "'variable_length_bytes', 'null_terminated_bytes', 'raw_bytes', 'struct', or 'r*'";
            throw new ZarrException(methods + " requires the " + needed + " data type, not '"
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
