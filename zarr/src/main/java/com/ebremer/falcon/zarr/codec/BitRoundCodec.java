package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonObject;
import java.nio.ByteOrder;

/**
 * numcodecs' {@code bitround} filter, a lossy one: each float keeps {@code keepbits} bits of its mantissa,
 * rounded to nearest with ties to even on its bits (bitround.py's integer arithmetic, which wraps).
 * Decoding is the identity, as numcodecs' is on the stored bytes.
 *
 * <p>The floats are the elements the stage before gives ({@link Numcodecs#elementType}): encoding refuses
 * any other elements, as numcodecs does, big-endian floats too (numcodecs finds the mantissa width by the
 * dtype's name, which a non-native {@code >f4} lacks), and a {@code keepbits} above the float's mantissa
 * width (10, 23, or 52). At exactly the width the data passes through unchanged.
 */
final class BitRoundCodec extends NumcodecsCodec {

    private final int keepbits;
    private final NumpyType input; // the elements encode is given; null if not NumPy numbers

    private BitRoundCodec(int keepbits, NumpyType input) {
        super("bitround");
        this.keepbits = keepbits;
        this.input = input;
    }

    static BitRoundCodec parse(JsonObject configuration, NumpyType input) {
        long keepbits = Numcodecs.integer(configuration, "keepbits", -1);
        if (!configuration.has("keepbits")) {
            throw new ZarrFormatException("numcodecs.bitround configuration needs 'keepbits'");
        }
        if (keepbits < 0 || keepbits > Integer.MAX_VALUE) {
            throw new ZarrFormatException("numcodecs.bitround: keepbits must be zero or positive, was " + keepbits);
        }
        return new BitRoundCodec((int) keepbits, input);
    }

    @Override
    NumpyType encodedType() {
        // numcodecs returns the floats as integers of the same width, unless it returned them untouched
        return input != null && input.isFloat() && keepbits != mantissaBits(input)
                ? new NumpyType('i', input.size(), input.order()) : input;
    }

    @Override
    public long encodedSize(long decodedSize) {
        return decodedSize;
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return decodedSize;
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        if (input.length > maxSize) {
            throw new ZarrFormatException(name() + ": chunk holds " + input.length + " bytes, more than " + maxSize);
        }
        return input.clone();
    }

    @Override
    public byte[] encode(byte[] data) {
        if (input == null || !input.isFloat()) {
            throw new ZarrUnsupportedException(name() + ": only float arrays can be bit-rounded, not "
                    + (input == null ? "these elements" : "'" + input + "'"));
        }
        if (input.order() == ByteOrder.BIG_ENDIAN && input.size() > 1) {
            // numcodecs looks the width up by the dtype's name, which a non-native '>f4' does not have
            throw new ZarrUnsupportedException(name() + ": numcodecs cannot bit-round big-endian floats ('"
                    + input + "')");
        }
        int bits = mantissaBits(input);
        if (keepbits > bits) {
            throw new ZarrFormatException(name() + ": keepbits " + keepbits + " is too large for '" + input + "'");
        }
        if (keepbits == bits) {
            return data.clone();
        }
        NumpyType integer = new NumpyType('i', input.size(), input.order());
        int n = count(data, integer);
        int maskbits = bits - keepbits;
        long mask = integer.wrap(-1L << maskbits);
        long halfQuantum = (1L << (maskbits - 1)) - 1;
        byte[] out = new byte[data.length];
        for (int i = 0; i < n; i++) {
            long b = integer.get(data, i);
            b = integer.wrap(b + ((b >> maskbits) & 1) + halfQuantum);
            integer.put(out, i, b & mask);
        }
        return out;
    }

    /** The explicit mantissa bits of a float type: 10, 23, or 52. */
    private static int mantissaBits(NumpyType type) {
        return switch (type.size()) {
            case 2 -> 10;
            case 4 -> 23;
            default -> 52;
        };
    }
}
