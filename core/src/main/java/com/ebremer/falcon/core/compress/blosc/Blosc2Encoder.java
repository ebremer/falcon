package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.core.compress.shuffle.ByteShuffle;
import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

/**
 * A pure-Java c-blosc2 encoder: Blosc2 chunks (format version 5, the extended 32-byte header), contiguous
 * frames (cframes) of them, and b2nd arrays in a frame, as c-blosc2 3.3.2 writes them
 * ({@code blosc2.c}, {@code stune.c}, {@code frame.c}, {@code schunk.c}, {@code b2nd.c}), and so as
 * hdf5-blosc2's filter (HDF5's 32026) stores each chunk. {@link Blosc2Frame} and {@link B2ndArray} read them.
 *
 * <p>A chunk is c-blosc2's own, byte for byte, with every internal compressor but zstd: BloscLZ in its
 * c-blosc2 form, LZ4 and LZ4HC as lz4 1.10.0, zlib as zlib 1.3 (both as hdf5plugin builds c-blosc2), and zstd
 * Falcon's {@link ZstdEncoder} (valid, but not libzstd's bytes). It follows c-blosc2's write side:
 *
 * <ul>
 *   <li>the block size from {@code blosc_stune_next_blocksize} unless one is forced: from 32 KiB, larger for
 *       the high-ratio compressors (LZ4HC, zlib, zstd) and with the clevel; for a buffer split into streams,
 *       32 KiB to 512 KiB by clevel times the type size (32 KiB to 4 MiB); at most the data, a multiple of the
 *       type size;</li>
 *   <li>the split from {@code split_block} ({@code BLOSC_FORWARD_COMPAT_SPLIT}): BloscLZ, LZ4, and zstd up to
 *       clevel 5 compress a block as one stream per byte of the type when the byte shuffle is on, the type is
 *       at most 16 bytes, and a block holds at least 32 elements; the last, partial block is never split;</li>
 *   <li>the filter on each block: the byte shuffle, the bit shuffle (whole groups of 8 elements, the rest
 *       copied), or the XOR delta against the chunk's first block (its first block against itself, shifted
 *       by one element);</li>
 *   <li>a stream of one repeated byte as a run (its length the byte, negated, then a token byte of 1 unless
 *       the byte is 0); a chunk of nothing but zero runs as the special zero chunk, its header alone;</li>
 *   <li>a stream that does not shrink stored raw; a chunk at clevel 0, one under 32 bytes, and one whose
 *       streams would not fit in its own size plus 32 bytes stored whole (the {@code memcpy} flag);</li>
 *   <li>the compressors' levels: BloscLZ and LZ4HC the clevel, LZ4 an acceleration of {@code 10 - clevel},
 *       zlib the clevel, zstd {@code 2 * clevel - 1} (22 at 9).</li>
 * </ul>
 *
 * <p>The header holds the type size in one byte, so, as c-blosc2 does, a type above 255 bytes is
 * compressed as single bytes. The bytes past the last whole element of a delta-filtered block are left
 * undefined by c-blosc2 (whatever its scratch buffer held); Falcon writes the previous block's there, and
 * zeros in the first.
 */
public final class Blosc2Encoder {

    /** The BloscLZ compressor (c-blosc2's compressor code {@code BLOSC_BLOSCLZ}, 0). */
    public static final int BLOSCLZ = 0;
    /** The LZ4 compressor ({@code BLOSC_LZ4}, 1). */
    public static final int LZ4 = 1;
    /** The LZ4HC compressor ({@code BLOSC_LZ4HC}, 2), which writes LZ4's format. */
    public static final int LZ4HC = 2;
    /** The zlib compressor ({@code BLOSC_ZLIB}, 4). */
    public static final int ZLIB = 4;
    /** The zstd compressor ({@code BLOSC_ZSTD}, 5). */
    public static final int ZSTD = 5;

    /** No filter ({@code BLOSC_NOFILTER}). */
    public static final int NOFILTER = 0;
    /** The byte shuffle ({@code BLOSC_SHUFFLE}). */
    public static final int SHUFFLE = 1;
    /** The bit shuffle ({@code BLOSC_BITSHUFFLE}). */
    public static final int BITSHUFFLE = 2;
    /** The XOR delta against the chunk's first block ({@code BLOSC_DELTA}). */
    public static final int DELTA = 3;

    /** c-blosc2's split modes ({@code BLOSC_ALWAYS_SPLIT} ... {@code BLOSC_FORWARD_COMPAT_SPLIT}). */
    static final int ALWAYS_SPLIT = 1;
    static final int NEVER_SPLIT = 2;
    static final int FORWARD_COMPAT_SPLIT = 4;

    /** The extended header's length, also c-blosc2's {@code BLOSC2_MAX_OVERHEAD}. */
    static final int HEADER = 32;
    private static final int VERSION_FORMAT_STABLE = 5;
    private static final int VERSION_LZ = 1; // every compressor's format version is 1
    private static final int MAX_TYPE_SIZE = 255;
    private static final int MIN_BUFFERSIZE = 32; // memcpy below it; a split block holds at least as many
    private static final int MAX_STREAMS = 16;
    private static final int L1 = 32 * 1024;
    private static final int FILTER_SLOTS = 6;
    /** c-blosc2's {@code BLOSC2_MAX_BUFFERSIZE}, less what a Java array cannot hold. */
    private static final int MAX_BUFFERSIZE = Integer.MAX_VALUE - HEADER - 8;

    // header flags (byte 2)
    private static final int DOSHUFFLE = 0x01;
    private static final int MEMCPYED = 0x02;
    private static final int DOBITSHUFFLE = 0x04;
    private static final int DODELTA = 0x08;
    private static final int SPECIAL_ZERO = 1;

    private Blosc2Encoder() {
    }

    /**
     * The compressor code for a c-blosc2 compressor name: {@code blosclz}, {@code lz4}, {@code lz4hc},
     * {@code zlib}, or {@code zstd}.
     *
     * @param cname the name
     * @return the code
     * @throws IllegalArgumentException if the name is none of those
     */
    public static int compressor(String cname) {
        return switch (cname) {
            case "blosclz" -> BLOSCLZ;
            case "lz4" -> LZ4;
            case "lz4hc" -> LZ4HC;
            case "zlib" -> ZLIB;
            case "zstd" -> ZSTD;
            default -> throw new IllegalArgumentException("unknown Blosc2 compressor '" + cname + "'");
        };
    }

    /**
     * Compresses {@code data} into one Blosc2 chunk, as c-blosc2's {@code blosc2_compress_ctx} does with a
     * destination of the data's size plus 32 bytes (what a super-chunk and a b2nd array give it), so it always
     * succeeds.
     *
     * @param data       the bytes to compress
     * @param typeSize   the element size in bytes, at least 1 (above 255, the data is compressed as bytes)
     * @param clevel     the compression level, 0 (store) to 9
     * @param compressor {@link #BLOSCLZ}, {@link #LZ4}, {@link #LZ4HC}, {@link #ZLIB}, or {@link #ZSTD}
     * @param filter     {@link #NOFILTER}, {@link #SHUFFLE}, {@link #BITSHUFFLE}, or {@link #DELTA}
     * @param blockSize  the block size in bytes, or 0 for c-blosc2's automatic size; a forced size is at most
     *                   the data's and is rounded down to a multiple of the type size by c-blosc2, so it must
     *                   be one already
     * @return the chunk
     * @throws IllegalArgumentException if an argument is out of range, or the data is too large for one chunk
     */
    public static byte[] compress(byte[] data, int typeSize, int clevel, int compressor, int filter, int blockSize) {
        checkArguments(typeSize, clevel, compressor, filter, blockSize);
        return compress(data, 0, data.length, new Params(typeSize, clevel, compressor, filters(filter), blockSize,
                FORWARD_COMPAT_SPLIT)).chunk();
    }

    /**
     * The block size c-blosc2's {@code blosc_stune_next_blocksize} gives a chunk of {@code nbytes} bytes, as
     * {@code blosc2_chunk_zeros} records it in a chunk's header (hdf5-blosc2's {@code compute_blosc2_blocksize}
     * asks it so, with the byte shuffle).
     *
     * @param nbytes     the chunk's size in bytes
     * @param typeSize   the element size in bytes, at least 1
     * @param clevel     the compression level, 0 to 9
     * @param compressor the compressor code
     * @param filter     the filter, which decides (with the byte shuffle) whether blocks are split
     * @return the block size in bytes
     */
    public static int automaticBlockSize(int nbytes, int typeSize, int clevel, int compressor, int filter) {
        checkArguments(typeSize, clevel, compressor, filter, 0);
        return blockSize(nbytes, typeSize, clevel, compressor, filter == SHUFFLE, FORWARD_COMPAT_SPLIT, 0);
    }

    /**
     * A contiguous frame of a super-chunk holding {@code data} as its one chunk, as hdf5-blosc2 writes a chunk
     * of rank 1: {@code blosc2_schunk_append_buffer} into a new in-memory super-chunk, then
     * {@code blosc2_schunk_to_buffer}.
     *
     * @param data       the bytes
     * @param typeSize   the element size in bytes, at least 1
     * @param clevel     the compression level, 0 to 9
     * @param compressor the compressor code
     * @param filter     the filter
     * @param blockSize  the block size in bytes, or 0 for c-blosc2's automatic size
     * @return the frame
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static byte[] frame(byte[] data, int typeSize, int clevel, int compressor, int filter, int blockSize) {
        checkArguments(typeSize, clevel, compressor, filter, blockSize);
        Params params = new Params(typeSize, clevel, compressor, filters(filter), blockSize, FORWARD_COMPAT_SPLIT);
        Compressed chunk = compress(data, 0, data.length, params);
        return Blosc2Frame.write(List.of(chunk.chunk()), chunk.frameParams(params), List.of());
    }

    /**
     * A contiguous frame of a b2nd array holding {@code data} (its items in C order), as
     * {@code b2nd_from_cbuffer} and {@code b2nd_to_cframe} write it: the array cut into chunks of
     * {@code chunkShape}, each padded with zeros to whole blocks of {@code blockShape} and its blocks laid out
     * in C order (the items of each in C order), each compressed as {@link #compress} does with the block's
     * size; the {@code "b2nd"} metalayer records the shapes and {@code dtype}. hdf5-blosc2 writes a chunk of
     * rank 2 and up so, the array and its one chunk the HDF5 chunk, and {@code dtype} {@code "|V"} and the
     * type size.
     *
     * @param data       the array's bytes, in C order
     * @param typeSize   the element size in bytes, at least 1
     * @param shape      the array's shape, rank 1 to {@value B2ndArray#MAX_DIM}
     * @param chunkShape the chunks' shape, each dimension at least 1
     * @param blockShape the blocks' shape, each dimension at least 1 and at most the chunk's
     * @param dtype      the data type the metalayer records, in NumPy's form
     * @param clevel     the compression level, 0 to 9
     * @param compressor the compressor code
     * @param filter     the filter
     * @return the frame
     * @throws IllegalArgumentException if an argument is out of range, or the data is not the array's size
     */
    public static byte[] b2ndFrame(byte[] data, int typeSize, long[] shape, int[] chunkShape, int[] blockShape,
                                   String dtype, int clevel, int compressor, int filter) {
        checkArguments(typeSize, clevel, compressor, filter, 0);
        B2ndArray.Layout layout = B2ndArray.layout(shape, chunkShape, blockShape);
        if (data.length != layout.items() * typeSize) {
            throw new IllegalArgumentException("a b2nd array of " + layout.items() + " " + typeSize
                    + "-byte items is not " + data.length + " bytes");
        }
        long blockBytes = layout.blockItems() * typeSize;
        long chunkBytes = layout.extChunkItems() * typeSize;
        if (blockBytes > MAX_BUFFERSIZE || chunkBytes > MAX_BUFFERSIZE) {
            throw new IllegalArgumentException("b2nd chunks of " + chunkBytes + " bytes are too large");
        }
        Params params = new Params(typeSize, clevel, compressor, filters(filter), (int) blockBytes,
                FORWARD_COMPAT_SPLIT);
        List<byte[]> chunks = new ArrayList<>();
        Compressed last = null;
        for (byte[] chunk : B2ndArray.cut(data, typeSize, layout)) {
            last = compress(chunk, 0, chunk.length, params);
            chunks.add(last.chunk());
        }
        byte[] meta = B2ndArray.metalayer(shape, chunkShape, blockShape, dtype);
        return Blosc2Frame.write(chunks, last.frameParams(params), List.of(new Blosc2Frame.Metalayer("b2nd", meta)));
    }

    private static void checkArguments(int typeSize, int clevel, int compressor, int filter, int blockSize) {
        if (typeSize < 1) {
            throw new IllegalArgumentException("Blosc2 type size must be at least 1, not " + typeSize);
        }
        if (clevel < 0 || clevel > 9) {
            throw new IllegalArgumentException("Blosc2 clevel must be 0 to 9, not " + clevel);
        }
        if (compressor != BLOSCLZ && compressor != LZ4 && compressor != LZ4HC && compressor != ZLIB
                && compressor != ZSTD) {
            throw new IllegalArgumentException("Blosc2 compressor must be 0, 1, 2, 4, or 5, not " + compressor);
        }
        if (filter < NOFILTER || filter > DELTA) {
            throw new IllegalArgumentException("Blosc2 filter must be 0 to 3, not " + filter);
        }
        if (blockSize < 0) {
            throw new IllegalArgumentException("Blosc2 block size must not be negative: " + blockSize);
        }
        if (blockSize > 0 && typeSize <= MAX_TYPE_SIZE && blockSize > typeSize && blockSize % typeSize != 0) {
            throw new IllegalArgumentException("Blosc2 block size " + blockSize + " is not a multiple of the "
                    + typeSize + "-byte type");
        }
    }

    /** A filter pipeline of one filter, in the last slot, as {@code BLOSC2_CPARAMS_DEFAULTS} places it. */
    static byte[] filters(int filter) {
        byte[] filters = new byte[FILTER_SLOTS];
        filters[FILTER_SLOTS - 1] = (byte) filter;
        return filters;
    }

    /** A compression context's parameters ({@code blosc2_cparams}, the ones Falcon sets). */
    record Params(int typeSize, int clevel, int compressor, byte[] filters, int blockSize, int splitMode) {
    }

    /**
     * A chunk, and what its context holds after compressing it: the (capped) type size and the block size,
     * which a frame's header records.
     */
    record Compressed(byte[] chunk, int typeSize, int blockSize) {

        Blosc2Frame.Params frameParams(Params params) {
            return new Blosc2Frame.Params(typeSize, blockSize, BloscDecoder.le32(chunk, 4), params.clevel(),
                    params.compressor(), params.splitMode(), params.filters());
        }
    }

    /**
     * {@code blosc2_compress_ctx} into a destination of {@code nbytes + 32} bytes, in a fresh context:
     * {@code initialize_context_compression}, {@code write_compression_header}, then
     * {@code blosc_compress_context}.
     */
    static Compressed compress(byte[] src, int off, int nbytes, Params p) {
        if (nbytes > MAX_BUFFERSIZE) {
            throw new IllegalArgumentException("Blosc2 cannot hold " + nbytes + " bytes in one chunk");
        }
        int destsize = nbytes + HEADER;
        byte[] filters = p.filters();
        int filterFlags = 0;
        boolean anyFilter = false;
        for (byte f : filters) {
            filterFlags |= f == SHUFFLE ? DOSHUFFLE : f == BITSHUFFLE ? DOBITSHUFFLE : f == DELTA ? DODELTA : 0;
            anyFilter |= f != NOFILTER;
        }
        // The tuner sizes the blocks with the type size as given; only then is it capped.
        int blocksize = blockSize(nbytes, p.typeSize(), p.clevel(), p.compressor(), (filterFlags & DOSHUFFLE) != 0,
                p.splitMode(), p.blockSize());
        int typesize = p.typeSize() > MAX_TYPE_SIZE ? 1 : p.typeSize();
        // The header records the block size asked for (clamped to the data), or else the tuner's.
        int headerBlocksize = p.blockSize() > 0 ? Math.min(p.blockSize(), nbytes > 0 ? nbytes : p.blockSize())
                : blocksize;
        int leftover = nbytes % blocksize;
        int nblocks = nbytes / blocksize + (leftover > 0 ? 1 : 0);

        byte[] dest = new byte[destsize];
        int headerFlags = DOSHUFFLE | DOBITSHUFFLE; // both set: the extended header follows
        boolean memcpyed = p.clevel() == 0 || nbytes < MIN_BUFFERSIZE;
        if (memcpyed) {
            headerFlags |= MEMCPYED;
        }
        int outputBytes = memcpyed ? HEADER : HEADER + 4 * nblocks;
        if (!memcpyed && outputBytes > destsize) {
            headerFlags |= MEMCPYED;
            memcpyed = true;
            outputBytes = HEADER;
        }
        boolean split = false;
        if (!memcpyed) {
            headerFlags |= filterFlags;
            split = splitBlock(p.splitMode(), p.compressor(), p.clevel(), (filterFlags & DOSHUFFLE) != 0, typesize,
                    blocksize);
            headerFlags |= (split ? 0 : 1) << 4;
            headerFlags |= format(p.compressor()) << 5;
        }
        dest[0] = VERSION_FORMAT_STABLE;
        dest[1] = VERSION_LZ;
        dest[2] = (byte) headerFlags;
        dest[3] = (byte) typesize;
        putLe32(dest, 4, nbytes);
        putLe32(dest, 8, headerBlocksize);
        System.arraycopy(filters, 0, dest, 16, FILTER_SLOTS);
        dest[22] = (byte) p.compressor();
        // bytes 23 (codec meta), 24-29 (filters' meta), 30 (flags2), 31 (flags): all zero

        int ntbytes = 0;
        if (!memcpyed) {
            ntbytes = compressBlocks(src, off, nbytes, typesize, blocksize, nblocks, leftover, split, anyFilter,
                    filters, p, dest, outputBytes, destsize);
            if (ntbytes == 0) { // incompressible: try storing it whole
                headerFlags |= MEMCPYED;
                memcpyed = true;
            }
        }
        if (memcpyed) {
            System.arraycopy(src, off, dest, HEADER, nbytes); // fits: the destination is nbytes + 32
            ntbytes = HEADER + nbytes;
            dest[2] = (byte) headerFlags;
        } else {
            int nstreams = nblocks;
            if (split) {
                nstreams = leftover > 0 ? (nblocks - 1) * typesize + 1 : nblocks * typesize;
            }
            if (ntbytes == HEADER + 4 * nblocks + nstreams * 4) {
                // every stream a run of zeros: the special zero chunk, its header alone
                dest[31] |= (byte) (SPECIAL_ZERO << 4);
                ntbytes = HEADER;
            }
        }
        putLe32(dest, 12, ntbytes);
        return new Compressed(Arrays.copyOf(dest, ntbytes), typesize, blocksize);
    }

    /** {@code serial_blosc} over {@code blosc_c}: the blocks' streams after the block starts; 0 if they do not fit. */
    private static int compressBlocks(byte[] src, int off, int nbytes, int typesize, int blocksize, int nblocks,
                                      int leftover, boolean split, boolean anyFilter, byte[] filters, Params p,
                                      byte[] dest, int start, int destsize) {
        int ntbytes = start;
        byte[] tmp = new byte[blocksize];
        byte[] tmp2 = new byte[blocksize];
        Deflater deflater = p.compressor() == ZLIB ? new Deflater(p.clevel()) : null;
        try {
            for (int j = 0; j < nblocks; j++) {
                putLe32(dest, HEADER + 4 * j, ntbytes);
                boolean leftoverBlock = j == nblocks - 1 && leftover > 0;
                int bsize = leftoverBlock ? leftover : blocksize;
                byte[] block = src;
                int blockOff = off + j * blocksize;
                if (anyFilter) {
                    block = pipelineForward(src, off, j * blocksize, bsize, typesize, filters, tmp, tmp2);
                    blockOff = 0;
                }
                int nstreams = split && !leftoverBlock ? typesize : 1;
                int neblock = bsize / nstreams;
                for (int s = 0; s < nstreams; s++) {
                    ntbytes += 4; // the stream's length comes first
                    int ip = blockOff + s * neblock;
                    if (isRun(block, ip, neblock)) {
                        int value = block[ip] & 0xff;
                        if (ntbytes > destsize) {
                            return 0;
                        }
                        putLe32(dest, ntbytes - 4, -value);
                        if (value > 0) { // a token byte marks the run
                            ntbytes += 1;
                            if (ntbytes > destsize) {
                                return 0;
                            }
                            dest[ntbytes - 1] = 1;
                        }
                        continue;
                    }
                    int maxout = neblock;
                    if (ntbytes + maxout > destsize) {
                        maxout = destsize - ntbytes;
                        if (maxout <= 0) {
                            return 0;
                        }
                    }
                    int cbytes = compressStream(p.compressor(), p.clevel(), block, ip, neblock, dest, ntbytes, maxout,
                            deflater);
                    if (cbytes == 0 || cbytes == neblock) { // it did not shrink: stored raw
                        if (ntbytes + neblock > destsize) {
                            return 0;
                        }
                        System.arraycopy(block, ip, dest, ntbytes, neblock);
                        cbytes = neblock;
                    }
                    putLe32(dest, ntbytes - 4, cbytes);
                    ntbytes += cbytes;
                }
            }
        } finally {
            if (deflater != null) {
                deflater.end();
            }
        }
        return ntbytes;
    }

    /**
     * {@code pipeline_forward}: the block at {@code offset} of the chunk through the filters, slot 0 first,
     * the first into {@code tmp}, the next into {@code tmp2}, and so on (c-blosc2 cycles its buffers so for
     * two filters); answers the buffer holding the result, from its start. {@code tmp} keeps what it held
     * between blocks, as c-blosc2's scratch buffer does.
     */
    private static byte[] pipelineForward(byte[] src, int off, int offset, int bsize, int typesize, byte[] filters,
                                          byte[] tmp, byte[] tmp2) {
        byte[] in = src;
        int inOff = off + offset;
        for (byte f : filters) {
            if (f == NOFILTER) {
                continue;
            }
            byte[] out = in == tmp ? tmp2 : tmp;
            switch (f) {
                case SHUFFLE -> ByteShuffle.shuffle(in, inOff, out, 0, bsize, typesize);
                case BITSHUFFLE -> {
                    int elements = bsize / typesize;
                    elements -= elements % 8; // whole groups of 8; the rest is copied
                    if (elements > 0) {
                        Bitshuffle.transpose(in, inOff, out, 0, elements, typesize, new byte[elements * typesize]);
                    }
                    int done = elements * typesize;
                    System.arraycopy(in, inOff + done, out, done, bsize - done);
                }
                case DELTA -> delta(src, off, offset, in, inOff, bsize, typesize, out);
                default -> throw new IllegalStateException("filter " + f);
            }
            in = out;
            inOff = 0;
        }
        return in;
    }

    /**
     * {@code delta_encoder}: the chunk's first block XORed with itself one element back (its first element
     * kept), every other block with the first block's original bytes. Elements are the type's (1, 2, 4, or 8
     * bytes; another size by bytes, or by 8 bytes when a multiple of 8); bytes past the last whole element are
     * not written.
     */
    private static void delta(byte[] chunk, int chunkOff, int offset, byte[] in, int inOff, int nbytes, int typesize,
                              byte[] out) {
        int unit = switch (typesize) {
            case 1, 2, 4, 8 -> typesize;
            default -> typesize % 8 == 0 ? 8 : 1;
        };
        int n = nbytes / unit * unit;
        if (offset == 0) {
            // the reference block: dref is the block itself
            System.arraycopy(in, inOff, out, 0, Math.min(unit, n));
            for (int i = unit; i < n; i++) {
                out[i] = (byte) (in[inOff + i] ^ in[inOff + i - unit]);
            }
        } else {
            for (int i = 0; i < n; i++) {
                out[i] = (byte) (in[inOff + i] ^ chunk[chunkOff + i]);
            }
        }
    }

    /** {@code get_run}: whether every byte of the stream is its first. */
    private static boolean isRun(byte[] b, int off, int len) {
        byte x = b[off];
        for (int i = 1; i < len; i++) {
            if (b[off + i] != x) {
                return false;
            }
        }
        return true;
    }

    private static int compressStream(int compressor, int clevel, byte[] src, int off, int len, byte[] out, int pos,
                                      int maxout, Deflater deflater) {
        return switch (compressor) {
            case BLOSCLZ -> BloscLz.compress2(clevel, src, off, len, out, pos, maxout);
            case LZ4 -> Lz4.compress(src, off, len, out, pos, maxout, 10 - clevel);
            case LZ4HC -> Lz4.compressHc(src, off, len, out, pos, maxout, clevel);
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
            default -> { // ZSTD_compressCCtx, or nothing if it does not fit
                byte[] frame = ZstdEncoder.compress(Arrays.copyOfRange(src, off, off + len),
                        BloscEncoder.zstdLevel(clevel), false);
                if (frame.length > maxout) {
                    yield 0;
                }
                System.arraycopy(frame, 0, out, pos, frame.length);
                yield frame.length;
            }
        };
    }

    /** The compressor's format in the header's flags ({@code compcode_to_compformat}). */
    private static int format(int compressor) {
        return switch (compressor) {
            case BLOSCLZ -> 0;
            case LZ4, LZ4HC -> 1;
            case ZLIB -> 3;
            default -> 4;
        };
    }

    /** {@code blosc_stune_next_blocksize}; {@code forced} is the block size asked for, or 0. */
    static int blockSize(int nbytes, int typesize, int clevel, int compressor, boolean shuffle, int splitMode,
                         int forced) {
        if (nbytes < typesize) {
            return 1;
        }
        int blocksize = nbytes;
        boolean splitmode = splitBlock(splitMode, compressor, clevel, shuffle, typesize, blocksize);
        if (forced != 0) {
            blocksize = forced;
        } else {
            if (nbytes >= L1) {
                boolean hcr = compressor == LZ4HC || compressor == ZLIB || compressor == ZSTD;
                blocksize = hcr ? 2 * L1 : L1;
                blocksize = switch (clevel) {
                    case 0 -> blocksize / 4;
                    case 1 -> blocksize / 2;
                    case 2 -> blocksize;
                    case 3 -> blocksize * 2;
                    case 4, 5 -> blocksize * 4;
                    case 6, 7, 8 -> blocksize * 8;
                    default -> blocksize * (hcr ? 16 : 8);
                };
            }
            if (clevel > 0 && splitmode) {
                blocksize = switch (clevel) {
                    case 1, 2, 3 -> 32 * 1024;
                    case 4, 5, 6 -> 64 * 1024;
                    case 7 -> 128 * 1024;
                    case 8 -> 256 * 1024;
                    default -> 512 * 1024;
                };
                blocksize *= typesize;
                blocksize = Math.max(Math.min(blocksize, 4 * 1024 * 1024), 32 * 1024);
            }
        }
        if (blocksize > nbytes) {
            blocksize = nbytes;
        }
        if (blocksize > typesize) {
            blocksize = blocksize / typesize * typesize;
        }
        return blocksize;
    }

    /** {@code split_block}: whether blocks are compressed as one stream per byte of the type. */
    static boolean splitBlock(int splitMode, int compressor, int clevel, boolean shuffle, int typesize,
                              int blocksize) {
        if (splitMode == ALWAYS_SPLIT) {
            return true;
        }
        if (splitMode == NEVER_SPLIT) {
            return false;
        }
        return (compressor == BLOSCLZ || compressor == LZ4 || compressor == ZSTD && clevel <= 5) && shuffle
                && typesize <= MAX_STREAMS && blocksize / typesize >= MIN_BUFFERSIZE;
    }

    static void putLe32(byte[] out, int off, int value) {
        out[off] = (byte) value;
        out[off + 1] = (byte) (value >>> 8);
        out[off + 2] = (byte) (value >>> 16);
        out[off + 3] = (byte) (value >>> 24);
    }

    /** {@code "|V"} and the size: hdf5-blosc2's opaque NumPy dtype for a b2nd array. */
    public static String opaqueDtype(int typeSize) {
        return "|V" + typeSize;
    }
}
