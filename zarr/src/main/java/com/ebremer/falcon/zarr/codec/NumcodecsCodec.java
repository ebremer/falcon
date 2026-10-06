package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;

/**
 * A numcodecs filter or checksum run as a bytes&rarr;bytes codec named {@code numcodecs.<id>}, as
 * zarr-python 3 names numcodecs' codecs. Each one decodes exactly what numcodecs' {@code decode} is given
 * in zarr-python's Zarr v2 pipeline (a buffer of the previous stage's elements) and returns what it
 * returns, as bytes; encoding likewise. See {@link Numcodecs}.
 */
abstract class NumcodecsCodec implements BytesBytesCodec {

    /** The most bytes one Java array holds, the bound on what an encoder may produce. */
    static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    private final String id;

    NumcodecsCodec(String id) {
        this.id = id;
    }

    @Override
    public final String name() {
        return Numcodecs.PREFIX + id;
    }

    /**
     * The type of the elements {@link #encode} hands the next codec, as numcodecs' {@code encode} returns
     * them ({@link NumpyType#U1} for a plain buffer), or {@code null} if they are not NumPy numbers.
     */
    abstract NumpyType encodedType();

    /**
     * The number of {@code type} elements in {@code buffer}. NumPy views a buffer only as a whole number
     * of elements, so a remainder is malformed input.
     */
    final int count(byte[] buffer, NumpyType type) {
        if (buffer.length % type.size() != 0) {
            throw new ZarrFormatException(name() + ": " + buffer.length + " bytes are not a whole number of '"
                    + type + "' elements");
        }
        return buffer.length / type.size();
    }

    /** A new buffer of {@code length} bytes, refused before allocating if it is more than {@code maxSize}. */
    final byte[] allocate(long length, int maxSize) {
        if (length > maxSize) {
            throw new ZarrFormatException(name() + ": the result would be " + length + " bytes, more than the "
                    + maxSize + " it may hold");
        }
        return new byte[(int) length];
    }

    /**
     * {@code size} bytes of {@code from} elements as {@code to} elements: the size of the same count (a
     * partial element counted whole), saturating at {@code Long.MAX_VALUE}, the bound of a variable-length
     * chunk.
     */
    static long resized(long size, NumpyType from, NumpyType to) {
        if (size > Long.MAX_VALUE / 8) {
            return Long.MAX_VALUE;
        }
        return (size + from.size() - 1) / from.size() * to.size();
    }

    /** Thrown where a configuration names an encoder setting Falcon cannot reproduce. */
    final ZarrUnsupportedException unsupported(String message) {
        return new ZarrUnsupportedException(name() + ": " + message);
    }
}
