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
    public byte[] decode(byte[] input) {
        if (input.length < 4) {
            throw new ZarrFormatException("crc32c: chunk is shorter than the 4-byte checksum");
        }
        int dataLength = input.length - 4;
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
}
