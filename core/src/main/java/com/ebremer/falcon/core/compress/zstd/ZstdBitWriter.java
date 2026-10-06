package com.ebremer.falcon.core.compress.zstd;

import java.util.Arrays;

/**
 * Writes an entropy bitstream the way Zstandard's {@code BIT_CStream} does: bits are appended LSB-first to
 * a little-endian byte stream, and {@link #close()} adds the end marker, a single 1 bit. A decoder reads
 * the stream backwards from that marker ({@link ZstdBitReader}), so the last value written is the first
 * read, and each value's most significant bit is read first.
 *
 * <p>The register holds 64 bits; callers {@link #flush()} after adding at most 56 bits.
 */
final class ZstdBitWriter {

    private byte[] out;
    private int pos;
    private long container;
    private int bitPos;

    ZstdBitWriter(int capacity) {
        this.out = new byte[Math.max(capacity, 16)];
    }

    /** Appends the low {@code nbBits} bits of {@code value} ({@code nbBits} at most 56 less what is pending). */
    void addBits(long value, int nbBits) {
        if (nbBits == 0) {
            return;
        }
        container |= (value & ((1L << nbBits) - 1)) << bitPos;
        bitPos += nbBits;
    }

    /** Moves every whole byte from the register to the output. */
    void flush() {
        int bytes = bitPos >>> 3;
        if (bytes == 0) {
            return;
        }
        ensure(bytes);
        for (int i = 0; i < bytes; i++) {
            out[pos++] = (byte) container;
            container >>>= 8;
        }
        bitPos &= 7;
    }

    /** Adds the end marker and returns the stream. */
    byte[] close() {
        addBits(1, 1);
        flush();
        if (bitPos > 0) {
            ensure(1);
            out[pos++] = (byte) container;
            container = 0;
            bitPos = 0;
        }
        return Arrays.copyOf(out, pos);
    }

    private void ensure(int extra) {
        if (pos + extra > out.length) {
            out = Arrays.copyOf(out, Math.max(out.length * 2, pos + extra));
        }
    }
}
