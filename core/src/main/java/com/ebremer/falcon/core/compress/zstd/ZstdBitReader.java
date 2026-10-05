package com.ebremer.falcon.core.compress.zstd;

import com.ebremer.falcon.core.compress.CompressionFormatException;

/**
 * Reads a Zstandard entropy bitstream <em>backwards</em>, as the format requires.
 *
 * <p>Such a stream is written so that decoding starts at its last byte: the highest set bit of that byte
 * is an end marker, everything above it is padding, and bits are then consumed downwards. The first bit
 * read becomes the most significant bit of the returned value.
 *
 * <p>Reading past the front of the stream yields zero bits rather than failing, as libzstd's reader
 * does; a stream must then be checked with {@link #finished()} (consumed exactly) or
 * {@link #overflowed()}, so a corrupt one cannot decode to bits it does not hold.
 */
final class ZstdBitReader {

    private final byte[] data;
    private final int start;
    private int bitPos; // absolute index of the next bit to read; bit 0 is the LSB of data[start]

    ZstdBitReader(byte[] data, int start, int length) {
        if (length <= 0) {
            throw new CompressionFormatException("empty entropy bitstream");
        }
        this.data = data;
        this.start = start;
        int lastByte = data[start + length - 1] & 0xff;
        if (lastByte == 0) {
            throw new CompressionFormatException("entropy bitstream has no end marker");
        }
        int highestSet = 31 - Integer.numberOfLeadingZeros(lastByte);
        this.bitPos = (length - 1) * 8 + highestSet - 1; // skip the marker bit itself
    }

    /** Reads {@code count} bits (most significant first). */
    int readBits(int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 1) | bitAt(bitPos--);
        }
        return value;
    }

    /** Reads {@code count} bits without consuming them. */
    int peekBits(int count) {
        int value = 0;
        int pos = bitPos;
        for (int i = 0; i < count; i++) {
            value = (value << 1) | bitAt(pos--);
        }
        return value;
    }

    /** Consumes {@code count} bits. */
    void skipBits(int count) {
        bitPos -= count;
    }

    /**
     * Whether reading has consumed <em>more</em> bits than the stream holds. Consuming exactly the last
     * bit is not an overflow, which matters for the interleaved-state loops whose length is defined by
     * this condition.
     */
    boolean overflowed() {
        return bitPos < -1;
    }

    /** Whether exactly every bit of the stream has been consumed (libzstd's {@code BIT_endOfDStream}). */
    boolean finished() {
        return bitPos == -1;
    }

    private int bitAt(int absolute) {
        if (absolute < 0) {
            return 0; // over-reading the front is defined to produce zeros
        }
        return (data[start + (absolute >>> 3)] >>> (absolute & 7)) & 1;
    }
}
