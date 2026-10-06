package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import java.util.Arrays;

/**
 * A pure-Java Blosc encoder producing buffers that c-blosc (numcodecs, zarr-python) and
 * {@link BloscDecoder} both read. It writes the c-blosc format-version-2 container: a 16-byte header, a
 * block offset table, and per-block payloads.
 *
 * <p>Each block is filtered (no shuffle, the byte shuffle, or the bit shuffle) and compressed with Falcon's
 * own {@link ZstdEncoder} as the internal codec &mdash; both validated against their references. Block
 * sizes follow c-blosc's {@code compute_blocksize} for {@code zstd} (256&nbsp;KiB at clevel 5), so large
 * buffers stay within the block size c-blosc accepts. A block that would not shrink is stored raw, and a
 * buffer that compression does not help is stored whole (the {@code memcpy} path). The other internal
 * codecs are not offered on the write side; reading supports them all.
 *
 * <p>The header holds the type size in one byte, so, as c-blosc does, a type size above 255 is written
 * as 1 and its data is not shuffled.
 */
public final class BloscEncoder {

    /** The {@code shuffle} argument for no filter (c-blosc's {@code BLOSC_NOSHUFFLE}). */
    public static final int NOSHUFFLE = 0;
    /** The {@code shuffle} argument for the byte shuffle ({@code BLOSC_SHUFFLE}). */
    public static final int SHUFFLE = 1;
    /** The {@code shuffle} argument for the bit shuffle ({@code BLOSC_BITSHUFFLE}). */
    public static final int BITSHUFFLE = 2;

    private static final int HEADER = 16;
    private static final int VERSION = 2;
    private static final int VERSION_LZ = 1;
    private static final int MAX_TYPE_SIZE = 255; // the header's type size is one byte (BLOSC_MAX_TYPESIZE)
    private static final int COMPRESSOR_ZSTD = 4;
    private static final int FLAG_SHUFFLE = 0x01;
    private static final int FLAG_MEMCPYED = 0x02;
    private static final int FLAG_BITSHUFFLE = 0x04;
    // Bit 4 tells c-blosc a block is not split into type-size streams. zstd never splits, so it must be
    // set for c-blosc to parse the single stream (Falcon's own decoder infers this, but c-blosc reads it).
    private static final int FLAG_DONT_SPLIT = 0x10;

    private static final int DEFAULT_CLEVEL = 5;   // what Falcon's Zarr specs record
    private static final int L1 = 32 * 1024;       // c-blosc's L1, the base of its block size heuristic
    private static final int MIN_BUFFERSIZE = 128; // c-blosc's smallest forced block size

    private BloscEncoder() {
    }

    /**
     * Compresses {@code data} whose elements are {@code typeSize} bytes into a Blosc buffer: byte-shuffled
     * when the type is wider than a byte, in automatically sized blocks.
     */
    public static byte[] compress(byte[] data, int typeSize) {
        return compress(data, typeSize, typeSize > 1 ? SHUFFLE : NOSHUFFLE, 0, DEFAULT_CLEVEL);
    }

    /**
     * Compresses {@code data} into a Blosc buffer with the given filter and block size, sizing automatic
     * blocks as c-blosc does at clevel 5.
     *
     * @see #compress(byte[], int, int, int, int)
     */
    public static byte[] compress(byte[] data, int typeSize, int shuffle, int blockSize) {
        return compress(data, typeSize, shuffle, blockSize, DEFAULT_CLEVEL);
    }

    /**
     * Compresses {@code data} into a Blosc buffer.
     *
     * <p>{@code clevel} picks the automatic block size and the zstd level, as c-blosc does: clevel
     * {@code c} below 9 compresses at zstd level {@code 2c - 1}, and 9 at zstd's highest, 22
     * ({@link #zstdLevel}); clevel 0 stores the data uncompressed. A type size above 255
     * is written as 1 and not shuffled. Bit-shuffled data whose length is not a whole number of elements
     * is byte-shuffled instead: c-blosc cannot restore the partial element at the end of a bit-shuffled
     * block.
     *
     * @param typeSize  the element size in bytes (a value below 1 is taken as 1)
     * @param shuffle   {@link #NOSHUFFLE}, {@link #SHUFFLE}, or {@link #BITSHUFFLE}
     * @param blockSize the block size in bytes, or 0 for c-blosc's automatic size; a forced size is
     *                  adjusted as c-blosc adjusts it (at least 128 bytes, at most the buffer, a multiple of
     *                  the type size)
     * @param clevel    the compression level, 0 to 9
     * @return the Blosc buffer
     * @throws IllegalArgumentException if {@code shuffle}, {@code blockSize}, or {@code clevel} is out of range
     */
    public static byte[] compress(byte[] data, int typeSize, int shuffle, int blockSize, int clevel) {
        if (shuffle < NOSHUFFLE || shuffle > BITSHUFFLE) {
            throw new IllegalArgumentException("Blosc shuffle must be 0, 1, or 2, not " + shuffle);
        }
        if (blockSize < 0) {
            throw new IllegalArgumentException("Blosc block size must not be negative: " + blockSize);
        }
        if (clevel < 0 || clevel > 9) {
            throw new IllegalArgumentException("Blosc clevel must be 0 to 9, not " + clevel);
        }
        int nbytes = data.length;
        // c-blosc treats a type size too large for the header as a stream of single bytes.
        int ts = typeSize > MAX_TYPE_SIZE ? 1 : Math.max(typeSize, 1);
        int filter = typeSize > MAX_TYPE_SIZE ? NOSHUFFLE : shuffle;
        if (filter == BITSHUFFLE && nbytes % ts != 0) {
            filter = SHUFFLE;
        }
        if (nbytes == 0 || clevel == 0) {
            return memcpy(data, ts);
        }

        int blocksize = blockSize(clevel, ts, nbytes, blockSize);
        int blockCount = (nbytes + blocksize - 1) / blocksize;
        byte[][] streams = new byte[blockCount][];
        byte[] filtered = new byte[blocksize];
        byte[] tmp = filter == BITSHUFFLE ? new byte[blocksize] : null;
        long cbytes = HEADER + 4L * blockCount;
        for (int b = 0; b < blockCount; b++) {
            int start = b * blocksize;
            int length = Math.min(blocksize, nbytes - start);
            byte[] block = filterBlock(data, start, length, ts, filter, filtered, tmp);
            byte[] payload = ZstdEncoder.compress(block, zstdLevel(clevel), false);
            // A stream whose length equals the block's size is stored raw (c-blosc's rule, both ways).
            streams[b] = payload.length < length ? payload : block;
            cbytes += 4 + streams[b].length;
            if (cbytes >= HEADER + (long) nbytes) {
                return memcpy(data, ts); // compression does not pay: c-blosc stores the buffer as is
            }
        }

        byte[] out = new byte[(int) cbytes];
        int flags = filterFlag(filter) | FLAG_DONT_SPLIT | (COMPRESSOR_ZSTD << 5);
        writeHeader(out, flags, ts, nbytes, blocksize, (int) cbytes);
        int position = HEADER + 4 * blockCount;
        for (int b = 0; b < blockCount; b++) {
            putLe32(out, HEADER + 4 * b, position);
            putLe32(out, position, streams[b].length);
            System.arraycopy(streams[b], 0, out, position + 4, streams[b].length);
            position += 4 + streams[b].length;
        }
        return out;
    }

    /**
     * The zstd level c-blosc 1.x compresses at for {@code clevel} 1 to 9: {@code 2 * clevel - 1}, and zstd's
     * highest (22) for 9. Measured against numcodecs 0.17's c-blosc: for clevels 1 and 3 to 9, the zstd
     * frame in its buffer is byte for byte libzstd's at that level.
     */
    static int zstdLevel(int clevel) {
        return clevel < 9 ? 2 * clevel - 1 : ZstdEncoder.MAX_LEVEL;
    }

    /**
     * The block size c-blosc 1.x's {@code compute_blocksize} picks for its {@code zstd} codec, which is
     * never split into per-byte streams; {@code forced} is a requested size, or 0.
     */
    static int blockSize(int clevel, int typeSize, int nbytes, int forced) {
        if (nbytes < typeSize) {
            return 1;
        }
        int blocksize = nbytes;
        if (forced != 0) {
            blocksize = Math.min(Math.max(forced, MIN_BUFFERSIZE), BloscDecoder.MAX_BLOCKSIZE);
        } else if (nbytes >= L1) {
            blocksize = L1 * 2; // zstd, like zlib, is a high-ratio codec: c-blosc doubles its blocks
            blocksize = switch (clevel) {
                case 0 -> blocksize / 4;
                case 1 -> blocksize / 2;
                case 2 -> blocksize;
                case 3 -> blocksize * 2;
                case 4, 5 -> blocksize * 4;
                case 6, 7, 8 -> blocksize * 8;
                default -> blocksize * 16; // 9: eight times, and twice again for a high-ratio codec
            };
        }
        if (blocksize > nbytes) {
            blocksize = nbytes;
        }
        if (blocksize > typeSize) {
            blocksize = blocksize / typeSize * typeSize; // a whole number of elements
        }
        return blocksize;
    }

    /** One block after the filter: {@code data} itself (a copy of the block) when there is none. */
    private static byte[] filterBlock(byte[] data, int start, int length, int ts, int filter, byte[] filtered,
                                      byte[] tmp) {
        switch (filter) {
            case SHUFFLE -> Shuffle.shuffle(data, start, filtered, 0, length, ts);
            case BITSHUFFLE -> {
                // As c-blosc's blosc_internal_bitshuffle: a block whose element count is not a multiple of 8
                // is left as it is (the length here is always whole elements).
                int elements = length / ts;
                if (elements % 8 == 0) {
                    Bitshuffle.transpose(data, start, filtered, 0, elements, ts, tmp);
                } else {
                    System.arraycopy(data, start, filtered, 0, length);
                }
            }
            default -> System.arraycopy(data, start, filtered, 0, length);
        }
        return Arrays.copyOf(filtered, length);
    }

    private static int filterFlag(int filter) {
        return switch (filter) {
            case SHUFFLE -> FLAG_SHUFFLE;
            case BITSHUFFLE -> FLAG_BITSHUFFLE;
            default -> 0;
        };
    }

    /** Stores {@code data} verbatim (the c-blosc memcpy path): a header with the memcpy flag, then bytes. */
    private static byte[] memcpy(byte[] data, int typeSize) {
        int nbytes = data.length;
        byte[] out = new byte[HEADER + nbytes];
        writeHeader(out, FLAG_MEMCPYED, typeSize, nbytes, nbytes, HEADER + nbytes);
        System.arraycopy(data, 0, out, HEADER, nbytes);
        return out;
    }

    private static void writeHeader(byte[] out, int flags, int typeSize, int nbytes, int blocksize,
                                    int cbytes) {
        out[0] = (byte) VERSION;
        out[1] = (byte) VERSION_LZ;
        out[2] = (byte) flags;
        out[3] = (byte) typeSize;
        putLe32(out, 4, nbytes);
        putLe32(out, 8, blocksize);
        putLe32(out, 12, cbytes);
    }

    private static void putLe32(byte[] out, int off, int value) {
        out[off] = (byte) value;
        out[off + 1] = (byte) (value >>> 8);
        out[off + 2] = (byte) (value >>> 16);
        out[off + 3] = (byte) (value >>> 24);
    }
}
