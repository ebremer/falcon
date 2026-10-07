package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.zarr.datatype.DataType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * numpy's {@code datetime64} or {@code timedelta64} as h5py stores one in HDF5: an 8-byte opaque type whose
 * tag is {@code NUMPY:} and numpy's dtype string, such as {@code NUMPY:<M8[ns]} (h5py's
 * {@code opaque_dtype}). h5py reads such a dataset back as that dtype. Zarr has the types themselves.
 *
 * @param delta whether it is a {@code timedelta64} (else a {@code datetime64})
 * @param unit  numpy's unit, such as {@code s}, or {@code generic}
 * @param scale how many units one count is
 * @param order the byte order of the counts
 */
record NumpyTime(boolean delta, String unit, int scale, ByteOrder order) {

    private static final Pattern TAG = Pattern.compile("NUMPY:([<>|=])([Mm])8(?:\\[(\\d*)([A-Za-zμ]+)])?");
    private static final Set<String> UNITS = Set.of("Y", "M", "W", "D", "h", "m", "s", "ms", "us", "μs", "ns",
            "ps", "fs", "as", "generic");

    /** {@return the time type an HDF5 type stands for, as h5py tags it, or null if it is not one} */
    static NumpyTime of(Datatype type) {
        if (!(type instanceof Datatype.Opaque opaque) || opaque.size() != 8) {
            return null;
        }
        Matcher m = TAG.matcher(opaque.tag());
        if (!m.matches()) {
            return null;
        }
        String unit = m.group(4) == null ? "generic" : m.group(4);
        if (!UNITS.contains(unit)) {
            return null;
        }
        int scale = 1;
        if (m.group(3) != null && !m.group(3).isEmpty()) {
            try {
                scale = Integer.parseInt(m.group(3));
            } catch (NumberFormatException e) {
                return null;
            }
            if (scale < 1) {
                return null;
            }
        }
        return new NumpyTime(m.group(2).equals("m"), unit.equals("μs") ? "us" : unit, scale,
                m.group(1).equals(">") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
    }

    /** {@return the time type of a Zarr data type, in a byte order} */
    static NumpyTime of(DataType type, ByteOrder order) {
        return new NumpyTime(type.kind() == com.ebremer.falcon.zarr.datatype.DataTypeKind.TIMEDELTA,
                type.unit(), type.scaleFactor(), order);
    }

    /** {@return the HDF5 type h5py stores it as: an 8-byte opaque, tagged} */
    Datatype.Opaque hdf5() {
        String units = unit.equals("generic") ? "" : "[" + (scale == 1 ? "" : Integer.toString(scale)) + unit + "]";
        return Datatype.opaque(8, "NUMPY:" + (order == ByteOrder.BIG_ENDIAN ? ">" : "<") + (delta ? "m" : "M") + "8"
                + units);
    }

    /** {@return the Zarr data type} */
    DataType zarr() {
        return delta ? DataType.timedelta64(unit, scale) : DataType.datetime64(unit, scale);
    }

    /** {@return each 8-byte element of {@code bytes} as its count} */
    long[] counts(byte[][] elements) {
        long[] counts = new long[elements.length];
        for (int i = 0; i < elements.length; i++) {
            counts[i] = ByteBuffer.wrap(elements[i]).order(order).getLong();
        }
        return counts;
    }

    /** {@return the elements as numpy prints them} */
    Values values(byte[][] elements) {
        return Values.times(counts(elements), unit, scale, delta);
    }
}
