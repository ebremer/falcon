package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;

/**
 * numcodecs' {@code packbits} filter: bools packed eight to a byte, most significant bit first
 * ({@code np.packbits}), after a byte counting the padding bits in the last one. Any non-zero byte packs
 * as 1; unpacking gives 0s and 1s.
 */
final class PackBitsCodec extends NumcodecsCodec {

    PackBitsCodec() {
        super("packbits");
    }

    @Override
    NumpyType encodedType() {
        return NumpyType.U1;
    }

    @Override
    public long encodedSize(long decodedSize) {
        return (decodedSize + 7) / 8 + 1;
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return decodedSize > Long.MAX_VALUE - 8 ? Long.MAX_VALUE : (decodedSize + 7) / 8 + 1;
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        if (input.length == 0) {
            throw new ZarrFormatException(name() + ": chunk has no padding byte");
        }
        int padded = input[0] & 0xff;
        long bits = 8L * (input.length - 1);
        // numcodecs drops the last `padded` bits (dec[:-padded]), which leaves nothing if there are fewer
        byte[] out = allocate(Math.max(0, bits - padded), maxSize);
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((input[1 + (i >>> 3)] >>> (7 - (i & 7))) & 1);
        }
        return out;
    }

    @Override
    public byte[] encode(byte[] input) {
        int n = input.length;
        byte[] out = new byte[(n + 7) / 8 + 1];
        out[0] = (byte) ((8 - n % 8) % 8);
        for (int i = 0; i < n; i++) {
            if (input[i] != 0) {
                out[1 + (i >>> 3)] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return out;
    }
}
