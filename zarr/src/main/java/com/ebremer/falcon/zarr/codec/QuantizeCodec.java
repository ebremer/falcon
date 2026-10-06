package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * numcodecs' {@code quantize} filter, a lossy one: each float is rounded to a multiple of a power of two
 * fine enough for {@code digits} decimal digits ({@code around(scale * x) / scale}, in the data's own
 * float precision, as NumPy 2 computes with a Python {@code float}), then cast to {@code astype}.
 * Decoding only casts back to {@code dtype}. Both types must be floats, as numcodecs requires.
 */
final class QuantizeCodec extends NumcodecsCodec {

    private final NumpyType dtype;
    private final NumpyType astype;
    private final double scale;

    private QuantizeCodec(NumpyType dtype, NumpyType astype, double scale) {
        super("quantize");
        this.dtype = dtype;
        this.astype = astype;
        this.scale = scale;
    }

    static QuantizeCodec parse(JsonObject configuration) {
        long digits = configuration.find("digits").orElseThrow(() ->
                new ZarrFormatException("numcodecs.quantize configuration needs 'digits'")).asNumber().longValue();
        NumpyType dtype = Numcodecs.requireDtype(configuration, "dtype", "quantize");
        NumpyType astype = Numcodecs.dtype(configuration, "astype", "quantize");
        if (astype == null) {
            astype = dtype;
        }
        if (!dtype.isFloat() || !astype.isFloat()) {
            throw new ZarrFormatException("numcodecs.quantize: only floating point data types are supported, not '"
                    + dtype + "' and '" + astype + "'");
        }
        if (Math.abs(digits) > 300) {
            throw new ZarrFormatException("numcodecs.quantize: digits " + digits + " is out of range");
        }
        return new QuantizeCodec(dtype, astype, scale((int) digits));
    }

    /**
     * quantize.py's scale for {@code digits}: {@code 2^ceil(log2(10^-e))}, where {@code e} is
     * {@code log10(10^-digits)} rounded away from zero. The power of two is found from the double's
     * exponent, which is what {@code ceil(log2(...))} gives for any value not itself a power of two.
     */
    static double scale(int digits) {
        double precision = Math.pow(10.0, -digits);
        double exp = Math.log10(precision);
        exp = exp < 0 ? Math.floor(exp) : Math.ceil(exp);
        double target = Math.pow(10.0, -exp);
        int bits = Math.getExponent(target);
        if (target != Math.scalb(1.0, bits)) {
            bits++;
        }
        return Math.scalb(1.0, bits);
    }

    @Override
    NumpyType encodedType() {
        return astype;
    }

    @Override
    public long encodedSize(long decodedSize) {
        return resized(decodedSize, dtype, astype);
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return resized(decodedSize, dtype, astype);
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        int n = count(input, astype);
        byte[] out = allocate((long) n * dtype.size(), maxSize);
        for (int i = 0; i < n; i++) {
            dtype.put(out, i, dtype.cast(astype.get(input, i), astype));
        }
        return out;
    }

    @Override
    public byte[] encode(byte[] input) {
        int n = count(input, dtype);
        byte[] out = allocate((long) n * astype.size(), MAX_ARRAY);
        long factor = dtype.fromDouble(scale);
        for (int i = 0; i < n; i++) {
            long value = dtype.rint(dtype.multiply(factor, dtype.get(input, i)));
            astype.put(out, i, astype.cast(dtype.divide(value, factor), dtype));
        }
        return out;
    }
}
