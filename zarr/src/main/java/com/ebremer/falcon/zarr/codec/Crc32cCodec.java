package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * The {@code crc32c} bytes&rarr;bytes codec: it appends a 4-byte little-endian CRC-32C (Castagnoli)
 * checksum of the preceding bytes. Decoding verifies the checksum and strips it, so a corrupt chunk is
 * reported as a {@link ZarrFormatException} rather than silently decoded to wrong data.
 */
final class Crc32cCodec implements BytesBytesCodec {

    @Override
    public String name() {
        return "crc32c";
    }

    @Override
    public long encodedSize(long decodedSize) {
        return decodedSize + 4;
    }

    @Override
    public long maxEncodedSize(long decodedSize) {
        return decodedSize > Long.MAX_VALUE - 4 ? Long.MAX_VALUE : decodedSize + 4;
    }

    @Override
    public byte[] decode(byte[] input, int maxSize) {
        if (input.length < 4) {
            throw new ZarrFormatException("crc32c: chunk is shorter than the 4-byte checksum");
        }
        int dataLength = input.length - 4;
        if (dataLength > maxSize) {
            throw new ZarrFormatException("crc32c: chunk holds " + dataLength + " bytes, more than " + maxSize);
        }
        CRC32C crc = new CRC32C();
        crc.update(input, 0, dataLength);
        long computed = crc.getValue() & 0xffffffffL;
        long stored = (input[dataLength] & 0xffL)
                | ((input[dataLength + 1] & 0xffL) << 8)
                | ((input[dataLength + 2] & 0xffL) << 16)
                | ((input[dataLength + 3] & 0xffL) << 24);
        if (computed != stored) {
            throw new ZarrFormatException(
                    String.format("crc32c mismatch: computed 0x%08x, stored 0x%08x", computed, stored));
        }
        return Arrays.copyOf(input, dataLength);
    }

    @Override
    public byte[] encode(byte[] input) {
        CRC32C crc = new CRC32C();
        crc.update(input);
        long value = crc.getValue();
        byte[] out = Arrays.copyOf(input, input.length + 4);
        out[input.length] = (byte) value;
        out[input.length + 1] = (byte) (value >>> 8);
        out[input.length + 2] = (byte) (value >>> 16);
        out[input.length + 3] = (byte) (value >>> 24);
        return out;
    }
}
