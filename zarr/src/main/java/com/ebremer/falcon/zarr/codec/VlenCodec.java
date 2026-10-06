package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.datatype.DataTypeKind;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * The variable-length array&rarr;bytes codecs: {@code vlen-utf8} serializes the {@code string} data type,
 * whose elements are {@link String}s, and {@code vlen-bytes} the {@code variable_length_bytes} data type,
 * whose elements are {@code byte[]}s. Both use numcodecs' wire format: a little-endian {@code uint32}
 * element count, then, per element, a little-endian {@code uint32} byte length followed by that many bytes
 * (UTF-8 for a string).
 *
 * <p>A decoded chunk is an {@code Object[]} whose runtime type is the element's ({@code String[]} or
 * {@code byte[][]}, see {@link #newArray}), rather than a flat byte buffer, so these codecs live outside the
 * {@link ArrayBytesCodec} interface and are driven by {@link ChunkPipeline}'s variable-length path. A
 * {@code null} element is written as an empty one.
 */
public enum VlenCodec {

    /** {@code vlen-utf8}: {@link String} elements. */
    UTF8("vlen-utf8") {
        @Override
        public Object[] newArray(int length) {
            return new String[length];
        }

        @Override
        Object element(byte[] in, int off, int length) {
            return new String(in, off, length, StandardCharsets.UTF_8);
        }

        @Override
        byte[] bytes(Object element) {
            return element == null ? new byte[0] : ((String) element).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isFill(Object element, Object fill) {
            return (element == null ? "" : element).equals(fill);
        }

        @Override
        public Object fill(JsonValue json) {
            if (!(json instanceof JsonString s)) {
                throw new ZarrFormatException("the 'string' data type requires a string fill value");
            }
            return s.value();
        }
    },

    /** {@code vlen-bytes}: {@code byte[]} elements. */
    BYTES("vlen-bytes") {
        @Override
        public Object[] newArray(int length) {
            return new byte[length][];
        }

        @Override
        Object element(byte[] in, int off, int length) {
            return Arrays.copyOfRange(in, off, off + length);
        }

        @Override
        byte[] bytes(Object element) {
            return element == null ? new byte[0] : (byte[]) element;
        }

        @Override
        public boolean isFill(Object element, Object fill) {
            return Arrays.equals(element == null ? new byte[0] : (byte[]) element, (byte[]) fill);
        }

        /** The fill value is base64 text, as zarr-python writes it ({@code ""} for no bytes). */
        @Override
        public Object fill(JsonValue json) {
            if (!(json instanceof JsonString s)) {
                throw new ZarrFormatException(
                        "the 'variable_length_bytes' data type requires a base64 string fill value");
            }
            try {
                return Base64.getDecoder().decode(s.value());
            } catch (IllegalArgumentException e) {
                throw new ZarrFormatException("variable_length_bytes fill value is not base64: " + e.getMessage(), e);
            }
        }

        @Override
        public Object[] detach(Object[] elements, Object fill) {
            for (int i = 0; i < elements.length; i++) {
                if (elements[i] == fill) {
                    elements[i] = ((byte[]) fill).clone();
                }
            }
            return elements;
        }
    };

    private final String codecName;

    VlenCodec(String codecName) {
        this.codecName = codecName;
    }

    /** The codec for a variable-length data type, or {@code null} for a fixed-size one. */
    public static VlenCodec of(DataType dataType) {
        return dataType.kind() == DataTypeKind.STRING ? UTF8
                : dataType.kind() == DataTypeKind.BYTES ? BYTES
                : null;
    }

    /** The codec's name in {@code zarr.json}. */
    public String codecName() {
        return codecName;
    }

    /** A new chunk of {@code length} elements: a {@code String[]} or a {@code byte[][]}. */
    public abstract Object[] newArray(int length);

    /** Element {@code length} bytes at {@code off} of {@code in}, decoded. */
    abstract Object element(byte[] in, int off, int length);

    /** An element's stored bytes ({@code null} is empty). */
    abstract byte[] bytes(Object element);

    /** Whether {@code element} equals the fill value; a {@code null} counts as the empty element it is stored as. */
    public abstract boolean isFill(Object element, Object fill);

    /**
     * The fill value of an array of this codec's data type, from its {@code fill_value} JSON: the string
     * itself, or the bytes its base64 names.
     *
     * @throws ZarrFormatException if the JSON is not a valid fill value
     */
    public abstract Object fill(JsonValue json);

    /**
     * Gives each element that is the {@code fill} object itself its own copy, so a caller changing one
     * {@code byte[]} changes no other; strings are immutable and need none.
     *
     * @return {@code elements}
     */
    public Object[] detach(Object[] elements, Object fill) {
        return elements;
    }

    /** Whether every element equals {@code fill}. */
    public boolean isAllFill(Object[] elements, Object fill) {
        for (Object element : elements) {
            if (!isFill(element, fill)) {
                return false;
            }
        }
        return true;
    }

    /** Decodes a chunk's elements from its stored bytes. */
    Object[] decode(byte[] in) {
        if (in.length < 4) {
            throw new ZarrFormatException(codecName + " chunk is shorter than its 4-byte count");
        }
        int count = le32(in, 0);
        // Each element carries at least a 4-byte length, so the count cannot exceed this bound; the check
        // also stops a corrupt count from triggering a huge allocation.
        if (count < 0 || (long) count * 4 > in.length) {
            throw new ZarrFormatException(codecName + " element count " + (count & 0xffffffffL) + " is invalid");
        }
        Object[] out = newArray(count);
        int off = 4;
        for (int i = 0; i < count; i++) {
            if (4 > in.length - off) {
                throw new ZarrFormatException(codecName + " element " + i + " length is truncated");
            }
            int length = le32(in, off);
            off += 4;
            if (length < 0 || length > in.length - off) { // not off + length, which can overflow
                throw new ZarrFormatException(codecName + " element " + i + " overruns the chunk");
            }
            out[i] = element(in, off, length);
            off += length;
        }
        return out;
    }

    /** Encodes {@code elements} into a chunk's stored bytes. */
    byte[] encode(Object[] elements) {
        byte[][] stored = new byte[elements.length][];
        long total = 4;
        for (int i = 0; i < elements.length; i++) {
            stored[i] = bytes(elements[i]);
            total += 4L + stored[i].length;
        }
        if (total > Integer.MAX_VALUE) {
            throw new ZarrFormatException(codecName + " chunk of " + total + " bytes is too large");
        }
        byte[] out = new byte[(int) total];
        putLe32(out, 0, elements.length);
        int off = 4;
        for (byte[] bytes : stored) {
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
