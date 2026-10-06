package com.ebremer.falcon.core.compress.zfp;

import java.util.Arrays;

/**
 * zfp's bit stream, written: bits are put least significant first into each byte in turn
 * ({@code bitstream.inl}), so a stream of any word size holds them in the same order; only the padding that
 * {@code stream_flush} adds at the end, to a whole word, depends on the word size.
 */
final class ZfpBitWriter {

    private byte[] data = new byte[64];
    private long position;

    /** The number of bits written (or padded) so far ({@code stream_wtell}). */
    long position() {
        return position;
    }

    /** Writes one bit, 0 or 1, and returns it ({@code stream_write_bit}). */
    int writeBit(int bit) {
        ensure(1);
        if (bit != 0) {
            data[(int) (position >>> 3)] |= (byte) (1 << (int) (position & 7));
        }
        position++;
        return bit;
    }

    /**
     * Writes the low {@code n} bits of {@code value}, 0 to 64, least significant first, and returns the bits
     * not written, {@code value >>> n} ({@code stream_write_bits}; 0 when {@code n} is 64).
     */
    long writeBits(long value, int n) {
        ensure(n);
        int done = 0;
        while (done < n) {
            int at = (int) (position >>> 3);
            int shift = (int) (position & 7);
            int take = Math.min(8 - shift, n - done);
            data[at] |= (byte) (((value >>> done) & ((1 << take) - 1)) << shift);
            done += take;
            position += take;
        }
        return n == 64 ? 0 : value >>> n;
    }

    /** Writes {@code n} zero bits ({@code stream_pad}). */
    void pad(long n) {
        ensure(n);
        position += n;
    }

    /**
     * The stream padded with zeros to a whole number of {@code wordBits}-bit words ({@code stream_flush}), as
     * {@code zfp_compress} leaves it.
     */
    byte[] toByteArray(int wordBits) {
        long words = (position + wordBits - 1) / wordBits;
        long bytes = words * (wordBits / 8);
        ensure(bytes * 8 - position);
        return Arrays.copyOf(data, (int) bytes);
    }

    private void ensure(long bits) {
        long need = (position + bits + 7) >>> 3;
        if (need > data.length) {
            if (need > Integer.MAX_VALUE - 8) {
                throw new IllegalStateException("zfp stream larger than a Java array");
            }
            data = Arrays.copyOf(data, (int) Math.max(need, Math.min(Integer.MAX_VALUE - 8L, 2L * data.length)));
        }
    }
}
