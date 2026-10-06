package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * numcodecs' {@code astype} filter: elements of {@code decode_dtype} stored as {@code encode_dtype}, each
 * converted as {@code ndarray.astype} converts it (an integer narrowed wraps, a float to an integer
 * truncates toward zero, a float narrowed rounds to nearest even, anything to bool is "not zero").
 */
final class AsTypeCodec extends NumcodecsCodec {

    private final NumpyType encodeType;
    private final NumpyType decodeType;

    private AsTypeCodec(NumpyType encodeType, NumpyType decodeType) {
        super("astype");
        this.encodeType = encodeType;
        this.decodeType = decodeType;
    }

    static AsTypeCodec parse(JsonObject configuration) {
        return new AsTypeCodec(Numcodecs.requireDtype(configuration, "encode_dtype", "astype"),
                Numcodecs.requireDtype(configuration, "decode_dtype", "astype"));
    }

    @Override
    NumpyType encodedType() {
        return encodeType;
    }

    @Override
    public long encodedSize(long decodedSize) {
        return resized(decodedSize, decodeType, encodeType);
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return resized(decodedSize, decodeType, encodeType);
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        return convert(input, encodeType, decodeType, maxSize);
    }

    @Override
    public byte[] encode(byte[] input) {
        return convert(input, decodeType, encodeType, MAX_ARRAY);
    }

    private byte[] convert(byte[] input, NumpyType from, NumpyType to, int maxSize) {
        int n = count(input, from);
        byte[] out = allocate((long) n * to.size(), maxSize);
        for (int i = 0; i < n; i++) {
            to.put(out, i, to.cast(from.get(input, i), from));
        }
        return out;
    }
}
