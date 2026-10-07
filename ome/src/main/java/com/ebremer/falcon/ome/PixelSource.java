package com.ebremer.falcon.ome;

import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * The pixels an image is written from: an array of any size, read a box at a time, so that the image never
 * needs to fit in memory. {@link #of(ZarrArray)} reads a Zarr array; any other source (an HDF5 dataset, a
 * decoder) implements the two methods.
 */
public interface PixelSource {

    /** {@return the array's extent in each dimension} */
    long[] shape();

    /**
     * {@return the elements' data type: a boolean, integer, or floating-point type}
     */
    DataType dataType();

    /**
     * Reads a box of the array.
     *
     * @param offset the box's first element, in each dimension
     * @param shape  the box's extent in each dimension
     * @return the box's elements in C order, each in little-endian byte order and
     *         {@code dataType().byteCount()} bytes long
     */
    byte[] read(long[] offset, long[] shape);

    /**
     * A source that reads a Zarr array, whatever its byte order.
     *
     * @param array the array
     * @return the source
     * @throws IllegalArgumentException if the array's data type is not a boolean, integer, or floating-point
     *                                  type
     */
    static PixelSource of(ZarrArray array) {
        Objects.requireNonNull(array, "array");
        DataType type = array.dataType();
        if (!isNumeric(type)) {
            throw new IllegalArgumentException("an image's pixels are booleans, integers, or floating-point numbers, "
                    + "not " + type);
        }
        return new PixelSource() {
            @Override
            public long[] shape() {
                return array.shape();
            }

            @Override
            public DataType dataType() {
                return type;
            }

            @Override
            public byte[] read(long[] offset, long[] shape) {
                var selection = array.select(offset, shape);
                int n = Math.toIntExact(selection.elementCount());
                ByteBuffer out = ByteBuffer.allocate(Math.multiplyExact(n, type.byteCount()))
                        .order(ByteOrder.LITTLE_ENDIAN);
                switch (type.kind()) {
                    case BOOL -> {
                        for (long v : selection.readLongs()) {
                            out.put((byte) v);
                        }
                    }
                    case INT, UINT -> {
                        long[] values = type.kind() == DataTypeKind.UINT && type.byteCount() == 8
                                ? selection.readUnsignedLongs() : selection.readLongs();
                        for (long v : values) {
                            switch (type.byteCount()) {
                                case 1 -> out.put((byte) v);
                                case 2 -> out.putShort((short) v);
                                case 4 -> out.putInt((int) v);
                                default -> out.putLong(v);
                            }
                        }
                    }
                    case FLOAT -> {
                        switch (type.byteCount()) {
                            case 2 -> {
                                for (float v : selection.readFloats()) {
                                    out.putShort(Float.floatToFloat16(v));
                                }
                            }
                            case 4 -> {
                                for (float v : selection.readFloats()) {
                                    out.putFloat(v);
                                }
                            }
                            default -> {
                                for (double v : selection.readDoubles()) {
                                    out.putDouble(v);
                                }
                            }
                        }
                    }
                    default -> throw new IllegalStateException();
                }
                return out.array();
            }
        };
    }

    /**
     * {@return whether an image can hold elements of a data type: a boolean, integer, or floating-point type}
     *
     * @param type the data type
     */
    static boolean isNumeric(DataType type) {
        return switch (type.kind()) {
            case BOOL, INT, UINT -> true;
            case FLOAT -> type.byteCount() == 2 || type.byteCount() == 4 || type.byteCount() == 8;
            default -> false;
        };
    }
}
