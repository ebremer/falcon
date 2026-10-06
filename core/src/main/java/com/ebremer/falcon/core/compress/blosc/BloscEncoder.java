package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import java.util.Arrays;
import java.util.zip.Deflater;

/**
 * A pure-Java Blosc encoder producing buffers that c-blosc (numcodecs, zarr-python) and
 * {@link BloscDecoder} both read. It writes the c-blosc format-version-2 container: a 16-byte header, a
 * block offset table, and per-block streams.
 *
 * <p>Every internal compressor c-blosc offers is available ({@link #BLOSCLZ}, {@link #LZ4},
 * {@link #LZ4HC}, {@link #SNAPPY}, {@link #ZLIB}, {@link #ZSTD}), each Falcon's own: BloscLZ ported from
 * c-blosc 1.21.6 and LZ4/LZ4HC from lz4 1.10.0 (numcodecs' builds), zlib from {@code java.util.zip} (zlib
 * 1.3.1, as c-blosc bundles), zstd Falcon's {@link ZstdEncoder}, and snappy after Google's. Except with zstd,
 * a buffer is c-blosc's own, byte for byte. The container follows c-blosc 1.21's write side
 * ({@code blosc.c}), so a buffer's header (flags, type size, block size) is the one c-blosc writes for the
 * same data and settings:
 *
 * <ul>
 *   <li>block sizes from {@code compute_blocksize}: larger for the high-ratio compressors (lz4hc, zlib,
 *       zstd) and with the clevel, then, for a buffer split into streams, enlarged by the type size;</li>
 *   <li>the split from {@code split_block} (the default, "forward compatible" mode): every compressor but
 *       zstd compresses a block as one stream per byte of the type when the type size is at most 16 and a
 *       block holds at least 128 elements; the last, partial block is never split;</li>
 *   <li>the level as each compressor takes it: BloscLZ and LZ4HC the clevel itself, LZ4 an acceleration of
 *       {@code 10 - clevel}, zlib the clevel, zstd {@code 2 * clevel - 1} (22 at 9);</li>
 *   <li>a stream that does not shrink is stored raw; a buffer whose streams would not fit in its own size
 *       plus 16 bytes, one under 128 bytes, and one at clevel 0 are stored whole (the {@code memcpy} flag,
 *       added to the rest of the header).</li>
 * </ul>
 *
 * <p>The header holds the type size in one byte, so, as c-blosc does, a type size above 255 is written
 * as 1 (and a byte shuffle of one-byte elements does nothing). One rule differs on purpose: c-blosc before
 * 1.18 does not restore the partial element at the end of a bit-shuffled block (1.18 and later copy it
 * through), so data whose length is not a whole number of elements is byte-shuffled instead, which every
 * version restores.
 */
public final class BloscEncoder {

    /** The {@code shuffle} argument for no filter (c-blosc's {@code BLOSC_NOSHUFFLE}). */
    public static final int NOSHUFFLE = 0;
    /** The {@code shuffle} argument for the byte shuffle ({@code BLOSC_SHUFFLE}). */
    public static final int SHUFFLE = 1;
    /** The {@code shuffle} argument for the bit shuffle ({@code BLOSC_BITSHUFFLE}). */
    public static final int BITSHUFFLE = 2;

    /** The BloscLZ internal compressor (c-blosc's compressor code {@code BLOSC_BLOSCLZ}, 0). */
    public static final int BLOSCLZ = 0;
    /** The LZ4 internal compressor ({@code BLOSC_LZ4}, 1). */
    public static final int LZ4 = 1;
    /** The LZ4HC internal compressor ({@code BLOSC_LZ4HC}, 2), which writes LZ4's format. */
    public static final int LZ4HC = 2;
    /** The Snappy internal compressor ({@code BLOSC_SNAPPY}, 3), which c-blosc builds may lack. */
    public static final int SNAPPY = 3;
    /** The zlib internal compressor ({@code BLOSC_ZLIB}, 4). */
    public static final int ZLIB = 4;
    /** The zstd internal compressor ({@code BLOSC_ZSTD}, 5). */
    public static final int ZSTD = 5;

    /** The compressor names, by code: c-blosc's {@code cname}s. */
    private static final String[] NAMES = {"blosclz", "lz4", "lz4hc", "snappy", "zlib", "zstd"};
    /** The header's compressor format, by compressor code: LZ4HC writes LZ4's format. */
    private static final int[] FORMATS = {0, 1, 1, 2, 3, 4};

    private static final int HEADER = 16;
    private static final int VERSION = 2;
    private static final int VERSION_LZ = 1; // every compressor's format version is 1
    private static final int MAX_TYPE_SIZE = 255; // the header's type size is one byte (BLOSC_MAX_TYPESIZE)
    private static final int FLAG_SHUFFLE = 0x01;
    private static final int FLAG_MEMCPYED = 0x02;
    private static final int FLAG_BITSHUFFLE = 0x04;
    private static final int FLAG_DONT_SPLIT = 0x10;

    private static final int DEFAULT_CLEVEL = 5;   // what Falcon's Zarr specs record
    private static final int L1 = 32 * 1024;       // c-blosc's L1, the base of its block size heuristic
    private static final int MIN_BUFFERSIZE = 128; // c-blosc's smallest forced block, and memcpy below it
    private static final int MAX_SPLITS = 16;      // the widest type split into streams
    /** c-blosc's {@code BLOSC_MAX_BUFFERSIZE}, less what a Java array cannot hold. */
    private static final int MAX_BUFFERSIZE = Integer.MAX_VALUE - HEADER - 8;

    private BloscEncoder() {
    }

    /**
     * The compressor code for a c-blosc {@code cname}: {@code blosclz}, {@code lz4}, {@code lz4hc},
     * {@code snappy}, {@code zlib}, or {@code zstd}.
     *
     * @param cname the name
     * @return the code, {@link #BLOSCLZ} to {@link #ZSTD}
     * @throws IllegalArgumentException if the name is none of those
     */
    public static int compressor(String cname) {
        int code = Arrays.asList(NAMES).indexOf(cname);
        if (code < 0) {
            throw new IllegalArgumentException("unknown Blosc compressor '" + cname + "'");
        }
        return code;
    }

    /**
     * Compresses {@code data} whose elements are {@code typeSize} bytes into a Blosc buffer with zstd:
     * byte-shuffled when the type is wider than a byte, in automatically sized blocks, at clevel 5.
     *
     * @param data     the bytes to compress
     * @param typeSize the element size in bytes
     * @return the Blosc buffer
     */
    public static byte[] compress(byte[] data, int typeSize) {
        return compress(data, typeSize, typeSize > 1 ? SHUFFLE : NOSHUFFLE, 0, DEFAULT_CLEVEL, ZSTD);
    }

    /**
     * Compresses {@code data} into a Blosc buffer with zstd at clevel 5, with the given filter and block size.
     *
     * @param data      the bytes to compress
     * @param typeSize  the element size in bytes
     * @param shuffle   {@link #NOSHUFFLE}, {@link #SHUFFLE}, or {@link #BITSHUFFLE}
     * @param blockSize the block size in bytes, or 0 for c-blosc's automatic size
     * @return the Blosc buffer
     * @see #compress(byte[], int, int, int, int, int)
     */
    public static byte[] compress(byte[] data, int typeSize, int shuffle, int blockSize) {
        return compress(data, typeSize, shuffle, blockSize, DEFAULT_CLEVEL, ZSTD);
    }

    /**
     * Compresses {@code data} into a Blosc buffer with zstd.
     *
     * @param data      the bytes to compress
     * @param typeSize  the element size in bytes
     * @param shuffle   {@link #NOSHUFFLE}, {@link #SHUFFLE}, or {@link #BITSHUFFLE}
     * @param blockSize the block size in bytes, or 0 for c-blosc's automatic size
     * @param clevel    the compression level, 0 to 9
     * @return the Blosc buffer
     * @see #compress(byte[], int, int, int, int, int)
     */
    public static byte[] compress(byte[] data, int typeSize, int shuffle, int blockSize, int clevel) {
        return compress(data, typeSize, shuffle, blockSize, clevel, ZSTD);
    }

    /**
     * Compresses {@code data} into a Blosc buffer, as c-blosc 1.21's {@code blosc_compress_ctx} does with a
     * destination of the data's size plus 16 bytes (numcodecs' choice).
     *
     * @param data       the bytes to compress
     * @param typeSize   the element size in bytes (a value below 1 is taken as 1; above 255, as 1)
     * @param shuffle    {@link #NOSHUFFLE}, {@link #SHUFFLE}, or {@link #BITSHUFFLE}
     * @param blockSize  the block size in bytes, or 0 for c-blosc's automatic size; a forced size is
     *                   adjusted as c-blosc adjusts it (at least 128 bytes, enlarged for a split buffer, at
     *                   most the data, a multiple of the type size)
     * @param clevel     the compression level, 0 (store) to 9
     * @param compressor the internal compressor, {@link #BLOSCLZ} to {@link #ZSTD} (see {@link #compressor})
     * @return the Blosc buffer
     * @throws IllegalArgumentException if an argument is out of range, or the data is too large for one buffer
     */
    public static byte[] compress(byte[] data, int typeSize, int shuffle, int blockSize, int clevel,
                                  int compressor) {
        return compress(data, typeSize, shuffle, blockSize, clevel, compressor, data.length + HEADER);
    }

    /**
     * Compresses {@code data} into a Blosc buffer as c-blosc 1.21's {@code blosc_compress} does with a
     * destination of {@code destSize} bytes, or answers null where c-blosc returns 0 for want of room: each
     * stream is cut off at the destination's end (a stream that then does not fit, or whose raw copy would
     * not, fails the buffer), and the whole buffer is stored as it is (the {@code memcpy} flag: at clevel 0,
     * under 128 bytes, or when a stream failed) only if its size plus 16 fits. hdf5-blosc
     * ({@code blosc_filter.c}) passes the data's own size, so a buffer that would not shrink is refused, and
     * libhdf5 stores the chunk unfiltered. With {@code destSize} the data's size plus 16 this is
     * {@link #compress(byte[], int, int, int, int, int)}.
     *
     * @param data       the bytes to compress
     * @param typeSize   the element size in bytes (a value below 1 is taken as 1; above 255, as 1)
     * @param shuffle    {@link #NOSHUFFLE}, {@link #SHUFFLE}, or {@link #BITSHUFFLE}
     * @param blockSize  the block size in bytes, or 0 for c-blosc's automatic size
     * @param clevel     the compression level, 0 (store) to 9
     * @param compressor the internal compressor, {@link #BLOSCLZ} to {@link #ZSTD} (see {@link #compressor})
     * @param destSize   the bytes the buffer may take
     * @return the Blosc buffer, or null if it does not fit in {@code destSize} bytes
     * @throws IllegalArgumentException if an argument is out of range, or the data is too large for one buffer
     */
    public static byte[] compress(byte[] data, int typeSize, int shuffle, int blockSize, int clevel,
                                  int compressor, int destSize) {
        if (shuffle < NOSHUFFLE || shuffle > BITSHUFFLE) {
            throw new IllegalArgumentException("Blosc shuffle must be 0, 1, or 2, not " + shuffle);
        }
        if (blockSize < 0) {
            throw new IllegalArgumentException("Blosc block size must not be negative: " + blockSize);
        }
        if (clevel < 0 || clevel > 9) {
            throw new IllegalArgumentException("Blosc clevel must be 0 to 9, not " + clevel);
        }
        if (compressor < BLOSCLZ || compressor > ZSTD) {
            throw new IllegalArgumentException("Blosc compressor must be 0 to 5, not " + compressor);
        }
        int nbytes = data.length;
        if (nbytes > MAX_BUFFERSIZE) {
            throw new IllegalArgumentException("Blosc cannot hold " + nbytes + " bytes in one buffer");
        }
        // c-blosc treats a type size too large for the header as a stream of single bytes.
        int ts = typeSize > MAX_TYPE_SIZE ? 1 : Math.max(typeSize, 1);
        int filter = shuffle;
        if (filter == BITSHUFFLE && nbytes % ts != 0) {
            filter = SHUFFLE;
        }
        int blocksize = blockSize(compressor, clevel, ts, nbytes, blockSize);
        boolean split = splitBlock(compressor, ts, blocksize);
        int flags = filterFlag(filter) | (split ? 0 : FLAG_DONT_SPLIT) | (FORMATS[compressor] << 5);
        if (destSize < HEADER) {
            return null; // initialize_context_compression: no room for the header
        }
        if (clevel == 0 || nbytes < MIN_BUFFERSIZE) {
            return memcpy(data, flags, ts, blocksize, destSize);
        }

        int maxbytes = destSize;
        int leftover = nbytes % blocksize;
        int nblocks = nbytes / blocksize + (leftover > 0 ? 1 : 0);
        long tableEnd = HEADER + 4L * nblocks;
        if (tableEnd > maxbytes) {
            return memcpy(data, flags, ts, blocksize, destSize);
        }
        byte[] out = new byte[maxbytes];
        int ntbytes = (int) tableEnd;
        byte[] filtered = new byte[blocksize];
        byte[] tmp = filter == BITSHUFFLE ? new byte[blocksize] : null;
        Deflater deflater = compressor == ZLIB ? new Deflater(clevel) : null;
        try {
            for (int b = 0; b < nblocks; b++) {
                boolean last = b == nblocks - 1 && leftover > 0;
                int bsize = last ? leftover : blocksize;
                putLe32(out, HEADER + 4 * b, ntbytes);
                filterBlock(data, b * blocksize, bsize, ts, filter, filtered, tmp);
                int nsplits = split && !last ? ts : 1;
                int neblock = bsize / nsplits;
                for (int j = 0; j < nsplits; j++) {
                    ntbytes += 4; // the stream's length comes first
                    int maxout = compressor == SNAPPY ? Snappy.maxCompressedLength(neblock) : neblock;
                    if ((long) ntbytes + maxout > maxbytes) {
                        maxout = maxbytes - ntbytes; // never past the destination
                        if (maxout <= 0) {
                            return memcpy(data, flags, ts, blocksize, destSize);
                        }
                    }
                    int cbytes = compressStream(compressor, clevel, filtered, j * neblock, neblock, out, ntbytes,
                            maxout, split, deflater);
                    if (cbytes == 0 || cbytes == neblock) { // it did not shrink: stored raw
                        if ((long) ntbytes + neblock > maxbytes) {
                            return memcpy(data, flags, ts, blocksize, destSize);
                        }
                        System.arraycopy(filtered, j * neblock, out, ntbytes, neblock);
                        cbytes = neblock;
                    }
                    putLe32(out, ntbytes - 4, cbytes);
                    ntbytes += cbytes;
                }
            }
        } finally {
            if (deflater != null) {
                deflater.end();
            }
        }
        writeHeader(out, flags, ts, nbytes, blocksize, ntbytes);
        return Arrays.copyOf(out, ntbytes);
    }

    /**
     * Compresses one stream into {@code out} at {@code pos}, as {@code blosc_c} calls each compressor.
     *
     * @return the stream's length, or 0 if it does not fit in {@code maxout} bytes
     */
    private static int compressStream(int compressor, int clevel, byte[] src, int off, int len, byte[] out, int pos,
                                      int maxout, boolean split, Deflater deflater) {
        return switch (compressor) {
            case BLOSCLZ -> BloscLz.compress(clevel, src, off, len, out, pos, maxout, split);
            case LZ4 -> Lz4.compress(src, off, len, out, pos, maxout, 10 - clevel);
            case LZ4HC -> Lz4.compressHc(src, off, len, out, pos, maxout, clevel);
            case SNAPPY -> Snappy.compress(src, off, len, out, pos, maxout);
            case ZLIB -> { // compress2: a zlib stream at level clevel, or nothing if it does not fit
                deflater.reset();
                deflater.setInput(src, off, len);
                deflater.finish();
                int n = 0;
                while (!deflater.finished() && n < maxout) {
                    n += deflater.deflate(out, pos + n, maxout - n);
                }
                yield deflater.finished() ? n : 0;
            }
            default -> {
                byte[] frame = ZstdEncoder.compress(Arrays.copyOfRange(src, off, off + len), zstdLevel(clevel), false);
                if (frame.length > maxout) {
                    yield 0;
                }
                System.arraycopy(frame, 0, out, pos, frame.length);
                yield frame.length;
            }
        };
    }

    /**
     * The zstd level c-blosc 1.x compresses at for {@code clevel} 1 to 9: {@code 2 * clevel - 1}, and zstd's
     * highest (22) for 9. Measured against numcodecs 0.17's c-blosc: for clevels 1 and 3 to 9, the zstd
     * frame in its buffer is byte for byte libzstd's at that level.
     */
    static int zstdLevel(int clevel) {
        return clevel < 9 ? 2 * clevel - 1 : ZstdEncoder.MAX_LEVEL;
    }

    /** The block size of a zstd buffer ({@link #blockSize(int, int, int, int, int)} for {@link #ZSTD}). */
    static int blockSize(int clevel, int typeSize, int nbytes, int forced) {
        return blockSize(ZSTD, clevel, typeSize, nbytes, forced);
    }

    /**
     * The block size c-blosc 1.21's {@code compute_blocksize} picks; {@code forced} is a requested size, or 0.
     */
    static int blockSize(int compressor, int clevel, int typeSize, int nbytes, int forced) {
        if (nbytes < typeSize) {
            return 1;
        }
        boolean hcr = compressor == LZ4HC || compressor == ZLIB || compressor == ZSTD; // high-ratio codecs
        int blocksize = nbytes;
        if (forced != 0) {
            blocksize = Math.min(Math.max(forced, MIN_BUFFERSIZE), BloscDecoder.MAX_BLOCKSIZE);
        } else if (nbytes >= L1) {
            blocksize = hcr ? L1 * 2 : L1; // high-ratio codecs get blocks twice as large
            blocksize = switch (clevel) {
                case 0 -> blocksize / 4;
                case 1 -> blocksize / 2;
                case 2 -> blocksize;
                case 3 -> blocksize * 2;
                case 4, 5 -> blocksize * 4;
                case 6, 7, 8 -> blocksize * 8;
                default -> blocksize * (hcr ? 16 : 8); // 9: eight times, and twice again for a high-ratio codec
            };
        }
        if (clevel > 0 && splitBlock(compressor, typeSize, blocksize)) {
            // A split block becomes typesize streams, so it is enlarged by the type size: from at most
            // 256 KiB, to at least 64 KiB and at most 1 MiB.
            blocksize = Math.min(blocksize, 1 << 18) * typeSize;
            blocksize = Math.min(Math.max(blocksize, 1 << 16), 1 << 20);
        }
        if (blocksize > nbytes) {
            blocksize = nbytes;
        }
        if (blocksize > typeSize) {
            blocksize = blocksize / typeSize * typeSize; // a whole number of elements
        }
        return blocksize;
    }

    /**
     * c-blosc 1.21's {@code split_block} in its default mode ({@code BLOSC_FORWARD_COMPAT_SPLIT}): whether
     * blocks are compressed as one stream per byte of the type.
     */
    static boolean splitBlock(int compressor, int typeSize, int blocksize) {
        return compressor != ZSTD && typeSize <= MAX_SPLITS && blocksize / typeSize >= MIN_BUFFERSIZE;
    }

    /**
     * Applies the block's filter into {@code filtered}, as {@code blosc_c} does: the byte shuffle only for a
     * type wider than a byte, the bit shuffle only for a block of at least one element and, as
     * {@code blosc_internal_bitshuffle}, a whole number of groups of eight elements.
     */
    private static void filterBlock(byte[] data, int start, int length, int ts, int filter, byte[] filtered,
                                    byte[] tmp) {
        if (filter == SHUFFLE && ts > 1) {
            Shuffle.shuffle(data, start, filtered, 0, length, ts);
        } else if (filter == BITSHUFFLE && length >= ts && (length / ts) % 8 == 0) {
            Bitshuffle.transpose(data, start, filtered, 0, length / ts, ts, tmp);
        } else {
            System.arraycopy(data, start, filtered, 0, length);
        }
    }

    private static int filterFlag(int filter) {
        return switch (filter) {
            case SHUFFLE -> FLAG_SHUFFLE;
            case BITSHUFFLE -> FLAG_BITSHUFFLE;
            default -> 0;
        };
    }

    /**
     * Stores {@code data} verbatim (the c-blosc memcpy path): the header, with the memcpy flag added to
     * everything else it says, then the bytes; or null if they do not fit in {@code destSize} bytes.
     */
    private static byte[] memcpy(byte[] data, int flags, int typeSize, int blocksize, int destSize) {
        int nbytes = data.length;
        if ((long) nbytes + HEADER > destSize) {
            return null;
        }
        byte[] out = new byte[HEADER + nbytes];
        writeHeader(out, flags | FLAG_MEMCPYED, typeSize, nbytes, blocksize, HEADER + nbytes);
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
