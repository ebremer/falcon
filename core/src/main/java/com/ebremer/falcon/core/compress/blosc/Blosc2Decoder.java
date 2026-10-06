package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.shuffle.ByteShuffle;
import java.util.Arrays;

/**
 * Reads a c-blosc2 chunk: Blosc format versions 3 to 6, which c-blosc 1.x refuses. The layout is c-blosc2's
 * {@code README_CHUNK_FORMAT.rst}; where that document leaves a rule open, c-blosc2 3.3's {@code blosc2.c}
 * ({@code read_chunk_header}, {@code initialize_context_decompression}, {@code blosc_d},
 * {@code pipeline_backward}) is followed.
 *
 * <pre>
 *   header (16 bytes)    version, versionlz, flags, typesize, nbytes, blocksize, cbytes   (as Blosc 1)
 *   extended header      filters[6], udcodec, compcode_meta, filters_meta[6], blosc2_flags2, blosc2_flags
 *     (16 more bytes, when flags sets both shuffle bits 0x01 and 0x04)
 *   int32 bstarts[nblocks]                   each block's start, from the chunk's start
 *   per block, per stream: int32 csize, then  csize &gt; 0: csize bytes (raw when csize is the stream's size)
 *                                            csize == 0: none; the stream is zeros
 *                                            csize &lt; 0: a token byte; bit 0 set means every byte is -csize
 * </pre>
 *
 * <p>A block is one stream when flags bit 4 ({@code 0x10}) is set or it is the last, short block; otherwise
 * it is {@code typesize} streams, one per byte position. Decoded streams pass back through the filter
 * pipeline, slot 5 first: byte shuffle (grouping {@code filters_meta} bytes when that is set, else
 * {@code typesize}), bit shuffle (whole groups of 8 elements; the rest copied through, as c-blosc2 does from
 * format version 3), XOR delta against the chunk's first block, and truncated precision (lossy, so nothing to
 * undo). Without an extended header the flags give the filters, as c-blosc2's {@code flags_to_filters} does.
 *
 * <p>{@code blosc2_flags} bits 4&ndash;6 mark a chunk holding only a header: all zeros, all NaN (float32 or
 * float64 by type size), one value repeated (its bytes follow the 32-byte header, and their count is the
 * type size), or uninitialised values, which read as zeros here (c-blosc2 leaves the output untouched). A
 * memcpy'ed chunk holds the data right after its header, unfiltered.
 *
 * <p>Refused as unsupported: variable-length blocks ({@code blosc2_flags2} bit 0, format version 6),
 * dictionaries, lazy chunks (their blocks live in a super-chunk's frame), instrumented chunks (they hold
 * codec timings, not data), user-defined or registered plugin codecs and filters, and format versions above
 * 6. Refused as malformed: what c-blosc2 refuses, and a split block whose size is not a whole number of
 * streams, which c-blosc2 itself decodes wrongly.
 */
final class Blosc2Decoder {

    /** The first c-blosc2 format version ({@code BLOSC2_VERSION_FORMAT_ALPHA}); its filters[5] is unset. */
    static final int VERSION_ALPHA = 3;
    /** The newest format version c-blosc2 3.3 writes ({@code BLOSC2_VERSION_FORMAT_VL_BLOCKS}). */
    static final int VERSION_MAX = 6;

    private static final int MIN_HEADER_LENGTH = 16;
    /** The extended header's length; a memcpy'ed chunk's data, or a repeated value, starts here. */
    static final int EXTENDED_HEADER_LENGTH = 32;
    /** c-blosc2's {@code BLOSC2_MAXBLOCKSIZE}, which also bounds a repeated value's size. */
    static final int MAX_BLOCKSIZE = 536866816;

    // flags (byte 2)
    private static final int FLAG_SHUFFLE = 0x01;
    private static final int FLAG_MEMCPYED = 0x02;
    private static final int FLAG_BITSHUFFLE = 0x04;
    private static final int FLAG_DELTA = 0x08;
    private static final int FLAG_DONT_SPLIT = 0x10;

    // blosc2_flags (byte 31) and blosc2_flags2 (byte 30)
    private static final int USE_DICT = 0x01;
    private static final int LAZY = 0x08;
    private static final int INSTRUMENTED = 0x80;
    private static final int VL_BLOCKS = 0x01;

    // Special values: blosc2_flags bits 4-6.
    private static final int SPECIAL_ZERO = 1;
    private static final int SPECIAL_NAN = 2;
    private static final int SPECIAL_VALUE = 3;
    private static final int SPECIAL_UNINIT = 4;

    // Filter IDs; 32 and up are registered plugins or user-defined.
    private static final int NOFILTER = 0;
    private static final int SHUFFLE = 1;
    private static final int BITSHUFFLE = 2;
    private static final int DELTA = 3;
    private static final int TRUNC_PREC = 4;
    private static final int FILTER_SLOTS = 6;

    // The codec in flags bits 5-7; 2 (once snappy) and 5 are reserved.
    private static final int CODEC_BLOSCLZ = 0;
    private static final int CODEC_LZ4 = 1;
    private static final int CODEC_ZLIB = 3;
    private static final int CODEC_ZSTD = 4;
    private static final int CODEC_USER_DEFINED = 6;

    private Blosc2Decoder() {
    }

    /** Decodes a chunk whose version byte is at least {@link #VERSION_ALPHA}; see the class comment. */
    static byte[] decompress(byte[] src, int maxSize) {
        int version = src[0] & 0xff;
        if (version > VERSION_MAX) {
            throw new UnsupportedCompressionException("Blosc format version " + version
                    + " is not supported (c-blosc2 3.3 writes up to " + VERSION_MAX + ")");
        }
        int flags = src[2] & 0xff;
        int typeSize = src[3] & 0xff;
        int nbytes = BloscDecoder.le32(src, 4);
        int blocksize = BloscDecoder.le32(src, 8);
        int cbytes = BloscDecoder.le32(src, 12);

        // read_chunk_header's checks.
        if (cbytes < MIN_HEADER_LENGTH) {
            throw new CompressionFormatException("Blosc2 chunk declares " + cbytes + " bytes, less than its header");
        }
        if (blocksize <= 0 || blocksize > MAX_BLOCKSIZE) {
            throw new CompressionFormatException(
                    "Blosc2 block size " + blocksize + " is not between 1 and " + MAX_BLOCKSIZE);
        }
        if (typeSize == 0) {
            throw new CompressionFormatException("Blosc2 type size of zero");
        }
        if (nbytes < 0) {
            throw new CompressionFormatException("Blosc2 chunk declares a negative size, " + nbytes);
        }
        boolean extended = (flags & (FLAG_SHUFFLE | FLAG_BITSHUFFLE)) == (FLAG_SHUFFLE | FLAG_BITSHUFFLE);
        int overhead = extended ? EXTENDED_HEADER_LENGTH : MIN_HEADER_LENGTH;
        byte[] filters = new byte[FILTER_SLOTS];
        byte[] filtersMeta = new byte[FILTER_SLOTS];
        int udcodec = 0;
        int flags2 = 0;
        int blosc2Flags = 0;
        if (extended) {
            if (cbytes < EXTENDED_HEADER_LENGTH || src.length < EXTENDED_HEADER_LENGTH) {
                throw new CompressionFormatException("Blosc2 chunk of " + Math.min(cbytes, src.length)
                        + " bytes is shorter than its 32-byte extended header");
            }
            System.arraycopy(src, 16, filters, 0, FILTER_SLOTS);
            udcodec = src[22] & 0xff;
            System.arraycopy(src, 24, filtersMeta, 0, FILTER_SLOTS);
            flags2 = src[30] & 0xff;
            blosc2Flags = src[31] & 0xff;
            if (version == VERSION_ALPHA) { // the alpha series left the last slot uninitialised
                filters[5] = 0;
                filtersMeta[5] = 0;
            }
        } else {
            // flags_to_filters: a chunk without the extended header names its filters in the flags.
            if ((flags & FLAG_SHUFFLE) != 0) {
                filters[5] = SHUFFLE;
            }
            if ((flags & FLAG_BITSHUFFLE) != 0) {
                filters[5] = BITSHUFFLE;
            }
            if ((flags & FLAG_DELTA) != 0) {
                filters[4] = DELTA;
            }
        }
        int special = (blosc2Flags >>> 4) & 0x7;
        if (cbytes > src.length) {
            throw new CompressionFormatException(
                    "Blosc2 chunk declares " + cbytes + " bytes but only " + src.length + " are present");
        }
        if (nbytes > maxSize) {
            throw new CompressionFormatException(
                    "Blosc2 chunk declares " + nbytes + " bytes, more than the " + maxSize + " expected");
        }

        if ((flags2 & VL_BLOCKS) != 0) {
            throw new UnsupportedCompressionException("Blosc2 chunks with variable-length blocks are not supported");
        }
        if ((blosc2Flags & INSTRUMENTED) != 0) {
            throw new UnsupportedCompressionException(
                    "an instrumented Blosc2 chunk holds codec measurements, not data");
        }
        if ((blosc2Flags & LAZY) != 0 && special == 0) {
            throw new UnsupportedCompressionException(
                    "a lazy Blosc2 chunk holds no data: its blocks are in its super-chunk's frame");
        }
        if (special > SPECIAL_UNINIT) {
            throw new CompressionFormatException("unknown Blosc2 special value " + special);
        }
        int valueSize = typeSize;
        if (special == SPECIAL_VALUE) {
            // The repeated value follows the header, and its length is the real type size.
            valueSize = cbytes - EXTENDED_HEADER_LENGTH;
            if (valueSize <= 0 || valueSize > MAX_BLOCKSIZE || valueSize > nbytes || nbytes % valueSize != 0) {
                throw new CompressionFormatException("Blosc2 repeated value of " + valueSize
                        + " bytes does not fill " + nbytes + " bytes");
            }
        } else if (special != 0 && special != SPECIAL_ZERO && nbytes % typeSize != 0) {
            throw new CompressionFormatException(
                    "Blosc2 chunk of " + nbytes + " bytes is not a whole number of " + typeSize + "-byte values");
        }
        if (nbytes > 0 && blocksize > nbytes) {
            blocksize = nbytes; // as c-blosc2 does
        }
        boolean memcpyed = (flags & FLAG_MEMCPYED) != 0;
        if (memcpyed && cbytes != (long) nbytes + overhead) {
            throw new CompressionFormatException("Blosc2 memcpy chunk of " + cbytes + " bytes does not hold "
                    + nbytes + " bytes after its " + overhead + "-byte header");
        }
        if (nbytes == 0) {
            return new byte[0];
        }
        int blockCount = nbytes / blocksize + (nbytes % blocksize > 0 ? 1 : 0);

        if (special != 0) {
            return special(src, special, nbytes, blocksize, blockCount, special == SPECIAL_VALUE ? valueSize : typeSize);
        }
        if (memcpyed) {
            return Arrays.copyOfRange(src, overhead, overhead + nbytes);
        }

        if ((blosc2Flags & USE_DICT) != 0) {
            throw new UnsupportedCompressionException("Blosc2 chunks compressed with a dictionary are not supported");
        }
        int codec = flags >>> 5;
        switch (codec) {
            case CODEC_BLOSCLZ, CODEC_LZ4, CODEC_ZLIB, CODEC_ZSTD -> { }
            case CODEC_USER_DEFINED -> throw new UnsupportedCompressionException(
                    "Blosc2 user-defined or plugin codec " + udcodec + " is not supported");
            case 7 -> throw new UnsupportedCompressionException(
                    "Blosc2 chunk whose codec is defined by its super-chunk is not supported");
            default -> throw new CompressionFormatException("reserved Blosc2 internal codec " + codec);
        }
        for (byte filter : filters) {
            int id = filter & 0xff;
            if (id >= 32) {
                throw new UnsupportedCompressionException(
                        "Blosc2 filter " + id + " (" + filterName(id) + ") is not supported");
            }
            if (id > TRUNC_PREC) {
                throw new CompressionFormatException("undefined Blosc2 filter " + id);
            }
        }
        long bstartsEnd = overhead + 4L * blockCount;
        if (bstartsEnd > cbytes) {
            throw new CompressionFormatException("Blosc2 block offset table is truncated");
        }
        return blocks(src, version, flags, typeSize, nbytes, blocksize, cbytes, overhead, blockCount, (int) bstartsEnd,
                codec, filters, filtersMeta);
    }

    /** The blocks section: each block's streams decoded, then its filters undone. */
    private static byte[] blocks(byte[] src, int version, int flags, int typeSize, int nbytes, int blocksize,
                                 int cbytes, int overhead, int blockCount, int bstartsEnd, int codec,
                                 byte[] filters, byte[] filtersMeta) {
        byte[] out = new byte[nbytes];
        int leftover = nbytes % blocksize;
        boolean dontSplit = (flags & FLAG_DONT_SPLIT) != 0;
        byte[] first = new byte[blocksize];
        byte[] second = null;
        BloscDecoder.Scratch scratch = new BloscDecoder.Scratch();
        try {
            for (int b = 0; b < blockCount; b++) {
                boolean shortBlock = b == blockCount - 1 && leftover > 0;
                int blockBytes = shortBlock ? leftover : blocksize;
                int start = BloscDecoder.le32(src, overhead + 4 * b);
                if (start < bstartsEnd || start >= cbytes) {
                    throw new CompressionFormatException("Blosc2 block " + b + " starts at " + start
                            + ", outside the chunk's streams");
                }
                int streams = dontSplit || shortBlock ? 1 : typeSize;
                if (blockBytes % streams != 0) {
                    throw new CompressionFormatException("Blosc2 block of " + blockBytes
                            + " bytes does not divide into " + streams + " streams");
                }
                streams(src, start, cbytes, first, blockBytes / streams, streams, codec, scratch);
                if (second == null && hasFilters(filters)) {
                    second = new byte[blocksize];
                }
                unfilter(filters, filtersMeta, typeSize, first, second, blockBytes, out, b * blocksize, scratch);
            }
        } finally {
            scratch.close();
        }
        return out;
    }

    /** Decodes {@code streams} consecutive streams of {@code streamBytes} each, starting at {@code cursor}. */
    private static void streams(byte[] src, int cursor, int cbytes, byte[] block, int streamBytes, int streams,
                                int codec, BloscDecoder.Scratch scratch) {
        for (int s = 0; s < streams; s++) {
            if (cursor > cbytes - 4) {
                throw new CompressionFormatException("Blosc2 stream length is truncated");
            }
            int csize = BloscDecoder.le32(src, cursor);
            cursor += 4;
            int target = s * streamBytes;
            if (csize == 0) {
                Arrays.fill(block, target, target + streamBytes, (byte) 0); // a stream of zeros
            } else if (csize < 0) {
                if (cursor >= cbytes) {
                    throw new CompressionFormatException("Blosc2 stream token is truncated");
                }
                int token = src[cursor++] & 0xff;
                if ((token & 0x01) == 0) {
                    throw new CompressionFormatException("Blosc2 stream token " + token + " is not a run");
                }
                if (csize < -255) {
                    throw new CompressionFormatException("Blosc2 run of " + csize + " does not name a byte");
                }
                Arrays.fill(block, target, target + streamBytes, (byte) -csize); // a run of one byte
            } else {
                if (csize > cbytes - cursor) {
                    throw new CompressionFormatException("Blosc2 stream payload is truncated");
                }
                if (csize == streamBytes) {
                    System.arraycopy(src, cursor, block, target, streamBytes); // stored raw
                } else {
                    BloscDecoder.inflate(codec, src, cursor, csize, block, target, streamBytes, scratch);
                }
                cursor += csize;
            }
        }
    }

    private static boolean hasFilters(byte[] filters) {
        for (byte filter : filters) {
            if (filter != NOFILTER && filter != TRUNC_PREC) {
                return true;
            }
        }
        return false;
    }

    /**
     * Undoes the filter pipeline on one decoded block, last slot first ({@code pipeline_backward}), leaving the
     * block in {@code out} at {@code offset}. The delta filter works in place in {@code out}, against the
     * chunk's first block, which is fully decoded by then.
     */
    private static void unfilter(byte[] filters, byte[] filtersMeta, int typeSize, byte[] first, byte[] second,
                                 int length, byte[] out, int offset, BloscDecoder.Scratch scratch) {
        byte[] current = first;
        int currentOffset = 0;
        for (int i = FILTER_SLOTS - 1; i >= 0; i--) {
            int filter = filters[i] & 0xff;
            byte[] spare = current == first ? second : first; // never the buffer being read
            switch (filter) {
                case SHUFFLE -> {
                    int group = filtersMeta[i] == 0 ? typeSize : filtersMeta[i] & 0xff;
                    ByteShuffle.unshuffle(current, currentOffset, spare, 0, length, group);
                    current = spare;
                    currentOffset = 0;
                }
                case BITSHUFFLE -> {
                    bitUnshuffle(current, currentOffset, spare, length, typeSize, scratch);
                    current = spare;
                    currentOffset = 0;
                }
                case DELTA -> {
                    if (current != out) {
                        System.arraycopy(current, currentOffset, out, offset, length);
                        current = out;
                        currentOffset = offset;
                    }
                    undoDelta(out, offset, length, typeSize);
                }
                default -> { } // no filter, or truncated precision, which has nothing to undo
            }
        }
        if (current != out) {
            System.arraycopy(current, currentOffset, out, offset, length);
        }
    }

    /**
     * c-blosc2's bit-unshuffle from format version 3: whole groups of 8 elements are un-transposed and the
     * rest copied through (version 2 left a block whose element count was not a multiple of 8 as it was).
     */
    private static void bitUnshuffle(byte[] src, int srcOff, byte[] dst, int length, int typeSize,
                                     BloscDecoder.Scratch scratch) {
        int elements = length / typeSize;
        int transposed = elements - elements % 8;
        int bytes = transposed * typeSize;
        if (transposed > 0) {
            Bitshuffle.untranspose(src, srcOff, dst, 0, transposed, typeSize, scratch.bitTmp(bytes));
        }
        System.arraycopy(src, srcOff + bytes, dst, bytes, length - bytes);
    }

    /**
     * c-blosc2's {@code delta_decoder}: the first block XORs each element with the decoded one before it,
     * every other block each element with the first block's at the same position. Elements are 1, 2, 4, or 8
     * bytes (other type sizes use 8 if they are a multiple of 8, else 1); trailing bytes are left as they are.
     */
    private static void undoDelta(byte[] out, int offset, int length, int typeSize) {
        int width = switch (typeSize) {
            case 1, 2, 4, 8 -> typeSize;
            default -> typeSize % 8 == 0 ? 8 : 1;
        };
        int end = length / width * width;
        if (offset == 0) {
            for (int i = width; i < end; i++) {
                out[i] ^= out[i - width];
            }
        } else {
            for (int i = 0; i < end; i++) {
                out[offset + i] ^= out[i];
            }
        }
    }

    /**
     * A chunk of one special value. c-blosc2 fills it block by block, so a NaN or repeated value must fit each
     * block a whole number of times.
     */
    private static byte[] special(byte[] src, int special, int nbytes, int blocksize, int blockCount, int valueSize) {
        byte[] out = new byte[nbytes];
        if (special == SPECIAL_ZERO || special == SPECIAL_UNINIT) {
            return out; // uninitialised values read as zeros
        }
        if (blockCount > 1 && blocksize % valueSize != 0) {
            throw new CompressionFormatException("Blosc2 block of " + blocksize
                    + " bytes does not hold whole " + valueSize + "-byte values");
        }
        byte[] value;
        if (special == SPECIAL_NAN) {
            value = switch (valueSize) {
                case 4 -> new byte[] {0, 0, (byte) 0xc0, 0x7f}; // nanf(""), little-endian
                case 8 -> new byte[] {0, 0, 0, 0, 0, 0, (byte) 0xf8, 0x7f};
                default -> throw new CompressionFormatException(
                        "Blosc2 NaN chunk has a " + valueSize + "-byte type; NaN needs 4 or 8 bytes");
            };
        } else {
            value = Arrays.copyOfRange(src, EXTENDED_HEADER_LENGTH, EXTENDED_HEADER_LENGTH + valueSize);
        }
        System.arraycopy(value, 0, out, 0, valueSize);
        for (int filled = valueSize; filled < nbytes; filled *= 2) { // doubling copies
            System.arraycopy(out, 0, out, filled, Math.min(filled, nbytes - filled));
        }
        return out;
    }

    /** c-blosc2's registered filter names ({@code filters-registry.h}). */
    private static String filterName(int id) {
        return switch (id) {
            case 32 -> "ndcell";
            case 33 -> "ndmean";
            case 34, 35 -> "bytedelta";
            case 36 -> "int_trunc";
            default -> id < 160 ? "a registered plugin" : "user-defined";
        };
    }
}
