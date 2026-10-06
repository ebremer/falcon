package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.hdf5.HdfFormatException;

/**
 * The n-bit filter (filter id 5), as libhdf5's {@code H5Znbit.c} applies it: each element keeps only the
 * significant bits of its numbers, packed most-significant bit first from the chunk's start.
 *
 * <p><b>Client data</b>: the number of values, a "no compression needed" flag (the chunk is stored as it
 * is), the elements per chunk, then a description of the datatype, read recursively:
 * <ul>
 *   <li>atomic (1): size, byte order (0 little-, 1 big-endian), precision, bit offset &mdash; an integer or
 *       floating-point number, whose {@code precision} bits from bit {@code offset} are kept;</li>
 *   <li>array (2): total size, then the base type's class and description, repeated across the array;</li>
 *   <li>compound (3): size, member count, then per member its byte offset, class and description;</li>
 *   <li>no-op (4): size &mdash; a type n-bit cannot shrink (strings, references, ...), kept whole.</li>
 * </ul>
 * Packed, a chunk takes its significant bits rounded down to whole bytes, plus one byte (libhdf5 counts
 * the byte it would write next). Unpacked, an element's padding bits are zero.
 */
public final class Nbit {

    private static final int ATOMIC = 1;
    private static final int ARRAY = 2;
    private static final int COMPOUND = 3;
    private static final int NOOPTYPE = 4;
    private static final int MAX_DEPTH = 64;

    private Nbit() {
    }

    /** Unpacks a chunk of {@code uncompressedSize} bytes. */
    public static byte[] decode(byte[] data, int[] cd, int uncompressedSize) {
        if (cd.length > 1 && cd[1] == 1) {
            return data; // "no compression needed": libhdf5 stored the chunk untouched
        }
        int size = elementSize(cd);
        byte[] out = new byte[uncompressedSize];
        Walk walk = new Walk(cd, out, data, false);
        for (int at = 0; at + size <= uncompressedSize; at += size) {
            walk.element(at);
        }
        return out;
    }

    /** Packs a chunk, exactly as libhdf5 does. */
    public static byte[] encode(byte[] chunk, int[] cd) {
        if (cd.length > 1 && cd[1] == 1) {
            return chunk; // "no compression needed"
        }
        int size = elementSize(cd);
        int elements = chunk.length / size;
        byte[] packed = new byte[chunk.length + 1];
        Walk walk = new Walk(cd, chunk, packed, true);
        for (int i = 0; i < elements; i++) {
            walk.element(i * size);
        }
        return java.util.Arrays.copyOf(packed, (int) (walk.bit / 8) + 1);
    }

    private static int elementSize(int[] cd) {
        if (cd.length < 5 || cd[4] <= 0) {
            throw new HdfFormatException("n-bit filter client data is too short (" + cd.length + " values)");
        }
        return cd[4];
    }

    /** One pass over the datatype description, moving bits between elements and the packed stream. */
    private static final class Walk {
        private final int[] cd;
        private final byte[] elements;
        private final byte[] packed;
        private final boolean pack;
        long bit;

        Walk(int[] cd, byte[] elements, byte[] packed, boolean pack) {
            this.cd = cd;
            this.elements = elements;
            this.packed = packed;
            this.pack = pack;
        }

        void element(int at) {
            switch (at(3)) {
                case ATOMIC -> atomic(at, 4);
                case ARRAY -> array(at, 4, 0);
                case COMPOUND -> compound(at, 4, 0);
                case NOOPTYPE -> noop(at, at(4));
                default -> throw new HdfFormatException("unknown n-bit datatype class " + at(3));
            }
        }

        /** An atomic type described from {@code i}: its precision bits from its bit offset. */
        private int atomic(int at, int i) {
            int size = at(i);
            boolean bigEndian = at(i + 1) == 1;
            int precision = at(i + 2);
            int offset = at(i + 3);
            if (size <= 0 || precision < 0 || offset < 0 || (long) precision + offset > 8L * size) {
                throw new HdfFormatException("invalid n-bit atomic type: size " + size + ", precision " + precision
                        + ", offset " + offset);
            }
            requireWithin(at, size);
            for (int b = precision - 1; b >= 0; b--) {
                int position = offset + b;
                int index = at + (bigEndian ? size - 1 - position / 8 : position / 8);
                int mask = 1 << (position % 8);
                transfer(index, mask);
            }
            return i + 4;
        }

        /** An array described from {@code i}: its total size, its base type, repeated. */
        private int array(int at, int i, int depth) {
            depth(depth);
            int total = at(i);
            int baseClass = at(i + 1);
            i += 2;
            switch (baseClass) {
                case ATOMIC -> {
                    int size = positive(at(i));
                    for (int k = 0; k < total / size; k++) {
                        atomic(at + k * size, i);
                    }
                    return i + 4;
                }
                case ARRAY, COMPOUND -> {
                    int size = positive(at(i));
                    for (int k = 0; k < total / size; k++) {
                        if (baseClass == ARRAY) {
                            array(at + k * size, i, depth + 1);
                        } else {
                            compound(at + k * size, i, depth + 1);
                        }
                    }
                    // libhdf5 (H5Z__nbit_compress_one_array, and its decompress twin) leaves its index at the
                    // base type's description here, not past it: so does Falcon, to read what libhdf5 wrote.
                    return i;
                }
                case NOOPTYPE -> {
                    noop(at, total);
                    return i + 1;
                }
                default -> throw new HdfFormatException("unknown n-bit array base class " + baseClass);
            }
        }

        /** A compound described from {@code i}: its size, member count, and members. */
        private int compound(int at, int i, int depth) {
            depth(depth);
            int members = at(i + 1);
            i += 2;
            for (int m = 0; m < members; m++) {
                int offset = at(i);
                int memberClass = at(i + 1);
                i += 2;
                switch (memberClass) {
                    case ATOMIC -> i = atomic(at + offset, i);
                    case ARRAY -> i = array(at + offset, i, depth + 1);
                    case COMPOUND -> i = compound(at + offset, i, depth + 1);
                    case NOOPTYPE -> {
                        noop(at + offset, at(i));
                        i++;
                    }
                    default -> throw new HdfFormatException("unknown n-bit compound member class " + memberClass);
                }
            }
            return i;
        }

        /** A type kept whole: every bit of its {@code size} bytes, in the order they are stored. */
        private void noop(int at, int size) {
            requireWithin(at, size);
            for (int k = 0; k < size; k++) {
                for (int b = 7; b >= 0; b--) {
                    transfer(at + k, 1 << b);
                }
            }
        }

        /** Moves one bit: from the element into the stream when packing, else from the stream into it. */
        private void transfer(int index, int mask) {
            int byteAt = (int) (bit >>> 3);
            int streamMask = 0x80 >>> (int) (bit & 7);
            if (pack) {
                if ((elements[index] & mask) != 0) {
                    packed[byteAt] |= (byte) streamMask;
                }
            } else {
                if (byteAt >= packed.length) {
                    throw new HdfFormatException("n-bit chunk is truncated");
                }
                if ((packed[byteAt] & streamMask) != 0) {
                    elements[index] |= (byte) mask;
                }
            }
            bit++;
        }

        private void requireWithin(int at, int size) {
            if (at < 0 || size < 0 || (long) at + size > elements.length) {
                throw new HdfFormatException("n-bit datatype description runs past its element");
            }
        }

        private int at(int i) {
            if (i < 0 || i >= cd.length) {
                throw new HdfFormatException("n-bit filter client data ends at value " + cd.length + ", before its datatype");
            }
            return cd[i];
        }

        private static int positive(int size) {
            if (size <= 0) {
                throw new HdfFormatException("n-bit datatype of size " + size);
            }
            return size;
        }

        private static void depth(int depth) {
            if (depth > MAX_DEPTH) {
                throw new HdfFormatException("n-bit datatype description nests too deep");
            }
        }
    }
}
