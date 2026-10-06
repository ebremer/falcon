package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.JsonObject;

/**
 * numcodecs' {@code shuffle} codec: the bytes of {@code elementsize}-byte elements regrouped by position
 * (every element's first byte, then every second byte, ...), as Blosc's byte shuffle does. An
 * {@code elementsize} of 1 or less copies; a buffer that is not a whole number of elements is refused, as
 * numcodecs refuses it. The configuration's {@code elementsize} defaults to 4, numcodecs' default.
 */
final class ShuffleCodec extends NumcodecsCodec {

    private static final int DEFAULT_ELEMENT_SIZE = 4;

    private final int elementSize;

    private ShuffleCodec(int elementSize) {
        super("shuffle");
        this.elementSize = elementSize;
    }

    static ShuffleCodec parse(JsonObject configuration) {
        long size = Numcodecs.integer(configuration, "elementsize", DEFAULT_ELEMENT_SIZE);
        if (size > Integer.MAX_VALUE) {
            throw new ZarrFormatException("numcodecs.shuffle: elementsize " + size + " is too large");
        }
        return new ShuffleCodec((int) Math.max(size, 1));
    }

    @Override
    NumpyType encodedType() {
        return NumpyType.U1;
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
        return regroup(input, allocate(input.length, maxSize), false);
    }

    @Override
    public byte[] encode(byte[] input) {
        return regroup(input, new byte[input.length], true);
    }

    /** Shuffles {@code input} into {@code out}, or unshuffles it; a 1-byte element copies. */
    private byte[] regroup(byte[] input, byte[] out, boolean shuffle) {
        if (elementSize == 1) {
            System.arraycopy(input, 0, out, 0, input.length);
            return out;
        }
        if (input.length % elementSize != 0) {
            throw new ZarrFormatException(name() + ": " + input.length
                    + " bytes are not a whole number of " + elementSize + "-byte elements");
        }
        int count = input.length / elementSize;
        for (int b = 0; b < elementSize; b++) {
            for (int e = 0; e < count; e++) {
                if (shuffle) {
                    out[b * count + e] = input[e * elementSize + b];
                } else {
                    out[e * elementSize + b] = input[b * count + e];
                }
            }
        }
        return out;
    }
}
