package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import java.nio.charset.StandardCharsets;

/**
 * The {@code vlen-utf8} array&rarr;bytes codec: it serializes an array of variable-length UTF-8 strings
 * (the Zarr {@code string} data type). The wire format is a little-endian {@code uint32} element count,
 * then, per element, a little-endian {@code uint32} byte length followed by that many UTF-8 bytes.
 *
 * <p>Unlike the fixed-size {@code bytes} codec, a decoded string chunk is a {@code String[]} rather than a
 * flat byte buffer, so this lives outside the {@link ArrayBytesCodec} interface and is driven directly by
 * {@link ChunkPipeline}'s string path.
 */
final class VlenUtf8 {

    private VlenUtf8() {
    }

    /** Decodes {@code count} strings from the vlen-utf8 buffer. */
    static String[] decode(byte[] in) {
        if (in.length < 4) {
            throw new ZarrFormatException("vlen-utf8 chunk is shorter than its 4-byte count");
        }
        int count = le32(in, 0);
        // Each element carries at least a 4-byte length, so the count cannot exceed this bound; the check
        // also stops a corrupt count from triggering a huge allocation.
        if (count < 0 || (long) count * 4 > in.length) {
            throw new ZarrFormatException("vlen-utf8 element count " + (count & 0xffffffffL) + " is invalid");
        }
        String[] out = new String[count];
        int off = 4;
        for (int i = 0; i < count; i++) {
            if (4 > in.length - off) {
                throw new ZarrFormatException("vlen-utf8 element " + i + " length is truncated");
            }
            int length = le32(in, off);
            off += 4;
            if (length < 0 || length > in.length - off) { // not off + length, which can overflow
                throw new ZarrFormatException("vlen-utf8 element " + i + " overruns the chunk");
            }
            out[i] = new String(in, off, length, StandardCharsets.UTF_8);
            off += length;
        }
        return out;
    }

    /** Encodes {@code elements} (a {@code null} is written as the empty string) into a vlen-utf8 buffer. */
    static byte[] encode(String[] elements) {
        byte[][] utf8 = new byte[elements.length][];
        long total = 4;
        for (int i = 0; i < elements.length; i++) {
            String s = elements[i] == null ? "" : elements[i];
            utf8[i] = s.getBytes(StandardCharsets.UTF_8);
            total += 4L + utf8[i].length;
        }
        if (total > Integer.MAX_VALUE) {
            throw new ZarrFormatException("vlen-utf8 chunk of " + total + " bytes is too large");
        }
        byte[] out = new byte[(int) total];
        putLe32(out, 0, elements.length);
        int off = 4;
        for (byte[] bytes : utf8) {
            putLe32(out, off, bytes.length);
            off += 4;
            System.arraycopy(bytes, 0, out, off, bytes.length);
            off += bytes.length;
        }
        return out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void putLe32(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
        b[off + 2] = (byte) (value >>> 16);
        b[off + 3] = (byte) (value >>> 24);
    }
}
