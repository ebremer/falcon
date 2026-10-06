package com.ebremer.falcon.core.compress.zstd;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

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
 *
 * <p>Like libzstd's {@code BIT_DStream}, it holds 64 bits of the stream in a register and serves several
 * reads from one little-endian load before refilling it.
 */
final class ZstdBitReader {

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long[] MASKS = new long[32];

    static {
        for (int i = 0; i < MASKS.length; i++) {
            MASKS[i] = (1L << i) - 1;
        }
    }

    private final byte[] data;
    private final int start;
    private final int length;
    private int bitPos;     // absolute index of the next bit to read; bit 0 is the LSB of data[start]
    private long container; // stream bits [base, base + 64): bytes before the stream read as zeros
    private int base;       // the bit index of the container's least significant bit (a multiple of 8)

    ZstdBitReader(byte[] data, int start, int length) {
        if (length <= 0) {
            throw new CompressionFormatException("empty entropy bitstream");
        }
        this.data = data;
        this.start = start;
        this.length = length;
        int lastByte = data[start + length - 1] & 0xff;
        if (lastByte == 0) {
            throw new CompressionFormatException("entropy bitstream has no end marker");
        }
        int highestSet = 31 - Integer.numberOfLeadingZeros(lastByte);
        this.bitPos = (length - 1) * 8 + highestSet - 1; // skip the marker bit itself
        refill();
    }

    /** Reads {@code count} bits (most significant first); {@code count} is at most 31. */
    int readBits(int count) {
        int value = peekBits(count);
        bitPos -= count;
        return value;
    }

    /** Reads {@code count} bits (at most 31) without consuming them. */
    int peekBits(int count) {
        // The bits read are positions bitPos down to bitPos - count + 1: as an integer, those bits of the
        // stream with the lowest position least significant. A count of 0 masks everything away.
        int low = bitPos - count + 1 - base;
        if (low < 0) {
            refill(); // now bitPos - base is 56..63, which holds any read of up to 31 bits
            low = bitPos - count + 1 - base;
        }
        return (int) ((container >>> low) & MASKS[count]);
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

    /** Loads the 8 bytes whose top byte holds the next bit to read. */
    private void refill() {
        int firstByte = (bitPos >> 3) - 7; // floor division, so this is right before the stream too
        base = firstByte * 8;
        if (firstByte >= 0 && firstByte + 8 <= length) {
            container = (long) LONG_LE.get(data, start + firstByte);
            return;
        }
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            int index = firstByte + i;
            value = (value << 8) | (index >= 0 && index < length ? data[start + index] & 0xffL : 0);
        }
        container = value;
    }
}
