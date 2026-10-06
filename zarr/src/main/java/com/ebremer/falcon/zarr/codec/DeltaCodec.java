package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * numcodecs' {@code delta} filter: the first element, then each element's difference from the one before.
 * The configuration is {@code dtype}, the elements' type, and {@code astype}, the stored differences'
 * (the same when absent).
 *
 * <p>Encoding takes the differences in {@code dtype} ({@code np.diff}: an integer wraps, a float rounds)
 * and casts them to {@code astype}; the first element is cast too, and an integer that does not fit an
 * integer {@code astype} is refused, as NumPy refuses it (so is a buffer with no elements, as numcodecs
 * refuses it). Decoding is {@code np.cumsum} into
 * {@code dtype}: NumPy accumulates in the promotion of the two types ({@code np.promote_types}, so an
 * {@code int8} into {@code int16} sums in {@code int16}, a {@code float32} into {@code float64} in
 * {@code float64}, and a {@code uint64} with an {@code int64} in {@code float64}), one element after the
 * other, and casts each sum to {@code dtype}.
 */
final class DeltaCodec extends NumcodecsCodec {

    private final NumpyType dtype;
    private final NumpyType astype;
    private final NumpyType sum; // the type np.cumsum accumulates in

    private DeltaCodec(NumpyType dtype, NumpyType astype) {
        super("delta");
        this.dtype = dtype;
        this.astype = astype;
        this.sum = NumpyType.promote(dtype, astype);
    }

    static DeltaCodec parse(JsonObject configuration) {
        NumpyType dtype = Numcodecs.requireDtype(configuration, "dtype", "delta");
        NumpyType astype = Numcodecs.dtype(configuration, "astype", "delta");
        DeltaCodec codec = new DeltaCodec(dtype, astype == null ? dtype : astype);
        if (dtype.kind() == 'b' || codec.astype.kind() == 'b') {
            throw codec.unsupported("bool elements are not supported (NumPy cannot subtract them)");
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
        long total = 0;
        for (int i = 0; i < n; i++) {
            long value = sum.cast(astype.get(input, i), astype);
            total = i == 0 ? value : sum.add(total, value);
            dtype.put(out, i, dtype.cast(total, sum));
        }
        return out;
    }

    @Override
    public byte[] encode(byte[] input) {
        int n = count(input, dtype);
        if (n == 0) {
            throw new ZarrFormatException(name() + ": there is no first element to encode"); // as numcodecs
        }
        byte[] out = allocate((long) n * astype.size(), MAX_ARRAY);
        long previous = dtype.get(input, 0);
        if (dtype.isInteger() && astype.isInteger() && !astype.holds(previous, dtype)) {
            throw new ZarrFormatException(name() + ": the first element does not fit '" + astype + "'");
        }
        astype.put(out, 0, astype.cast(previous, dtype));
        for (int i = 1; i < n; i++) {
            long current = dtype.get(input, i);
            astype.put(out, i, astype.cast(dtype.subtract(current, previous), dtype));
            previous = current;
        }
        return out;
    }
}
