package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * numcodecs' {@code fixedscaleoffset} filter: {@code around((x - offset) * scale)} stored as
 * {@code astype}, and {@code enc / scale + offset} read back as {@code dtype}. The configuration is
 * {@code offset}, {@code scale}, {@code dtype}, and {@code astype} (the same as {@code dtype} when absent).
 *
 * <p>Each step is computed as NumPy 2 computes an array with a Python number (NEP&nbsp;50): a float array
 * stays in its own precision, the number rounded to it ({@code float32} arithmetic for {@code float32}
 * data); an integer array with a Python {@code int} stays in its type, wrapping, and the {@code int} must
 * fit it; an integer array with a Python {@code float} becomes {@code float64}. Division of an integer
 * array is always {@code float64}. {@code np.around} rounds half to even; the final casts are
 * {@code astype}'s. Whether {@code offset} and {@code scale} are {@code int}s or {@code float}s is read
 * from their JSON literals, as Python's {@code json} reads them.
 */
final class FixedScaleOffsetCodec extends NumcodecsCodec {

    private final Numcodecs.Scalar offset;
    private final Numcodecs.Scalar scale;
    private final NumpyType dtype;
    private final NumpyType astype;

    private FixedScaleOffsetCodec(Numcodecs.Scalar offset, Numcodecs.Scalar scale, NumpyType dtype,
                                  NumpyType astype) {
        super("fixedscaleoffset");
        this.offset = offset;
        this.scale = scale;
        this.dtype = dtype;
        this.astype = astype;
    }

    static FixedScaleOffsetCodec parse(JsonObject configuration) {
        Numcodecs.Scalar offset = Numcodecs.Scalar.of(configuration, "offset", "fixedscaleoffset");
        Numcodecs.Scalar scale = Numcodecs.Scalar.of(configuration, "scale", "fixedscaleoffset");
        NumpyType dtype = Numcodecs.requireDtype(configuration, "dtype", "fixedscaleoffset");
        NumpyType astype = Numcodecs.dtype(configuration, "astype", "fixedscaleoffset");
        FixedScaleOffsetCodec codec = new FixedScaleOffsetCodec(offset, scale, dtype, astype == null ? dtype : astype);
        if (dtype.kind() == 'b' || codec.astype.kind() == 'b') {
            throw codec.unsupported("bool elements are not supported");
        }
        return codec;
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
        // enc / scale: float64 for an integer array, else the array's float type; then + offset in it.
        NumpyType quotient = astype.isFloat() ? astype : NumpyType.float64();
        long divisor = quotient.fromDouble(scale.real());
        long addend = quotient.fromDouble(offset.real());
        for (int i = 0; i < n; i++) {
            long value = quotient.cast(astype.get(input, i), astype);
            value = quotient.add(quotient.divide(value, divisor), addend);
            dtype.put(out, i, dtype.cast(value, quotient));
        }
        return out;
    }

    @Override
    public byte[] encode(byte[] input) {
        int n = count(input, dtype);
        byte[] out = allocate((long) n * astype.size(), MAX_ARRAY);
        NumpyType difference = offset.resultWith(dtype);
        NumpyType product = scale.resultWith(difference);
        long subtrahend = offset.in(difference, name() + ": offset");
        long factor = scale.in(product, name() + ": scale");
        for (int i = 0; i < n; i++) {
            long value = difference.cast(dtype.get(input, i), dtype);
            value = product.cast(difference.subtract(value, subtrahend), difference);
            value = product.multiply(value, factor);
            if (product.isFloat()) {
                value = product.rint(value);
            }
            astype.put(out, i, astype.cast(value, product));
        }
        return out;
    }
}
