package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A pure-Java decompressor for the Blosc container: c-blosc's format version 2, what numcodecs and
 * zarr-python write, and c-blosc2's chunk format, versions 3 to 6 (see {@link Blosc2Decoder}, to which a
 * buffer whose version byte is 3 or more is handed).
 *
 * <p>Blosc is a meta-compressor: a 16-byte header, an offset table, and then per-block payloads
 * compressed by an <em>internal</em> codec, optionally preceded by a shuffle filter that regroups bytes
 * to help that codec. The layout is
 *
 * <pre>
 *   header (16 bytes)  version, versionlz, flags, typesize, nbytes, blocksize, cbytes
 *   int32 offsets[nblocks]                    each pointing at a block, from the buffer start
 *   per block: (int32 length, payload)*       one pair per stream; a length equal to the stream's
 *                                             uncompressed size means it is stored raw
 * </pre>
 *
 * <p>A block is split into {@code typesize} streams when the compressor asked for it; because that
 * decision follows encoder-side heuristics, it is recovered here from the payload sizes rather than
 * re-derived, which is both simpler and robust across c-blosc versions.
 *
 * <p>All of Blosc's internal codecs are supported: {@code blosclz}, {@code lz4}/{@code lz4hc} (identical
 * block format), {@code zlib} (via {@code java.util.zip}), {@code zstd} (via Falcon's own decoder), and
 * {@code snappy}; and both the byte- and bit-shuffle filters. A buffer using an undefined internal codec
 * or an unsupported format version is reported as a format/unsupported error rather than decoded wrongly.
 *
 * <p>A version-2 header is checked as c-blosc 1.x checks it before decoding: format version 2, the reserved flag
 * bit clear, a type size of at least 1, a block size from 1 to the buffer's size (and c-blosc's
 * {@code BLOSC_MAX_BLOCKSIZE}), a memcpy'ed buffer exactly 16 bytes longer than its data, and an offset
 * table that fits. With both shuffle flags set, a block is byte-unshuffled when its type size exceeds 1
 * and bit-unshuffled otherwise, as c-blosc does.
 */
public final class BloscDecoder {

    /** Minimum header length, and where a memcpy'ed payload starts. */
    private static final int HEADER_LENGTH = 16;

    private static final int FLAG_SHUFFLE = 0x01;
    private static final int FLAG_MEMCPYED = 0x02;
    private static final int FLAG_BITSHUFFLE = 0x04;
    private static final int FLAG_RESERVED = 0x08;

    /** The format version c-blosc 1.x writes and reads ({@code BLOSC_VERSION_FORMAT}). */
    private static final int VERSION_FORMAT = 2;
    /** c-blosc's {@code BLOSC_MAX_BLOCKSIZE}: {@code (INT_MAX - BLOSC_MAX_TYPESIZE * 4) / 3}. */
    static final int MAX_BLOCKSIZE = (Integer.MAX_VALUE - 255 * 4) / 3;

    private static final int COMPRESSOR_BLOSCLZ = 0;
    private static final int COMPRESSOR_LZ4 = 1;
    private static final int COMPRESSOR_SNAPPY = 2;
    private static final int COMPRESSOR_ZLIB = 3;
    private static final int COMPRESSOR_ZSTD = 4;

    private BloscDecoder() {
    }

    /** The decompressed size recorded in a Blosc buffer's header (Blosc 1 or 2). */
    public static int decompressedSize(byte[] src) {
        requireHeader(src);
        return le32(src, 4);
    }

    /**
     * Decompresses a Blosc buffer.
     *
     * @throws CompressionFormatException     if the buffer is malformed
     * @throws UnsupportedCompressionException if it uses an internal codec or filter that is not implemented
     */
    public static byte[] decompress(byte[] src) {
        return decompress(src, Integer.MAX_VALUE - 8);
    }

    /**
     * Decompresses a Blosc buffer that should hold at most {@code maxSize} bytes. A header declaring more
     * fails before anything is allocated, so a corrupt or hostile size cannot exhaust memory.
     *
     * @throws CompressionFormatException     if the buffer is malformed, or declares more than {@code maxSize}
     *                                        bytes
     * @throws UnsupportedCompressionException if it uses an internal codec or filter that is not implemented
     */
    public static byte[] decompress(byte[] src, int maxSize) {
        requireHeader(src);
        int version = src[0] & 0xff;
        if (version >= Blosc2Decoder.VERSION_ALPHA) {
            return Blosc2Decoder.decompress(src, maxSize);
        }
        int flags = src[2] & 0xff;
        int typeSize = src[3] & 0xff;
        int nbytes = le32(src, 4);
        int blocksize = le32(src, 8);
        int cbytes = le32(src, 12);

        if (nbytes < 0 || blocksize < 0 || cbytes < 0) {
            throw new CompressionFormatException("Blosc header has a negative size");
        }
        if (cbytes > src.length) {
            throw new CompressionFormatException(
                    "Blosc buffer declares " + cbytes + " bytes but only " + src.length + " are present");
        }
        if (nbytes == 0) {
            return new byte[0]; // c-blosc returns before checking anything else
        }
        if (nbytes > maxSize) {
            throw new CompressionFormatException(
                    "Blosc buffer declares " + nbytes + " bytes, more than the " + maxSize + " expected");
        }
        // c-blosc 1.x's checks (blosc_run_decompression_with_context), memcpy'ed buffers included.
        if (blocksize == 0 || blocksize > nbytes || blocksize > MAX_BLOCKSIZE) {
            throw new CompressionFormatException(
                    "Blosc block size " + blocksize + " is not between 1 and the " + nbytes + "-byte buffer");
        }
        if (typeSize == 0) {
            throw new CompressionFormatException("Blosc type size of zero");
        }
        if (version != VERSION_FORMAT) {
            throw new CompressionFormatException("Blosc format version " + version + " (c-blosc writes 2)");
        }
        if ((flags & FLAG_RESERVED) != 0) {
            throw new CompressionFormatException("Blosc header sets the reserved flag bit 0x08");
        }

        // A memcpy'ed buffer holds the original bytes verbatim: no blocks, no filter.
        if ((flags & FLAG_MEMCPYED) != 0) {
            if (cbytes != HEADER_LENGTH + (long) nbytes) {
                throw new CompressionFormatException("Blosc memcpy buffer of " + cbytes + " bytes does not hold "
                        + nbytes + " bytes after its header");
            }
            byte[] out = new byte[nbytes];
            System.arraycopy(src, HEADER_LENGTH, out, 0, nbytes);
            return out;
        }

        int compressor = (flags & 0xe0) >>> 5;
        if (compressor > COMPRESSOR_ZSTD) {
            throw new CompressionFormatException("unknown Blosc internal codec " + compressor);
        }
        // As c-blosc's blosc_d decides: the byte shuffle wins when both flags are set and the type is wider
        // than a byte; otherwise the bit-shuffle flag applies (to a block holding at least one element).
        boolean shuffle = (flags & FLAG_SHUFFLE) != 0 && typeSize > 1;
        boolean bitShuffle = !shuffle && (flags & FLAG_BITSHUFFLE) != 0;

        int wholeBlocks = nbytes / blocksize;
        int leftover = nbytes % blocksize;
        int blockCount = wholeBlocks + (leftover > 0 ? 1 : 0);
        if (blockCount > (cbytes - HEADER_LENGTH) / 4) {
            throw new CompressionFormatException("Blosc block offset table is truncated");
        }
        byte[] out = new byte[nbytes];

        // c-blosc may compress blocks in parallel and write them out of order, so a block's payload ends
        // at the nearest offset above its own, not at the next block's.
        int[] offsets = new int[blockCount];
        for (int b = 0; b < blockCount; b++) {
            offsets[b] = le32(src, HEADER_LENGTH + 4 * b);
        }
        int[] ascending = offsets.clone();
        java.util.Arrays.sort(ascending);

        byte[] block = new byte[blocksize];
        Scratch scratch = new Scratch();
        try {
            for (int b = 0; b < blockCount; b++) {
                int start = offsets[b];
                int end = nextOffsetAbove(ascending, start, cbytes);
                if (start < HEADER_LENGTH + 4 * blockCount || end > cbytes || end < start) {
                    throw new CompressionFormatException("Blosc block " + b + " has an invalid extent");
                }
                int blockBytes = (b == wholeBlocks && leftover > 0) ? leftover : blocksize;
                decodeBlock(src, start, end - start, block, blockBytes, typeSize, compressor, scratch);

                int destination = b * blocksize;
                if (bitShuffle && blockBytes >= typeSize) {
                    bitUnshuffle(block, out, destination, blockBytes, typeSize, scratch);
                } else if (shuffle) {
                    Shuffle.unshuffle(block, 0, out, destination, blockBytes, typeSize);
                } else {
                    System.arraycopy(block, 0, out, destination, blockBytes);
                }
            }
        } finally {
            scratch.close();
        }
        return out;
    }

    /** What one decode reuses from block to block: zlib's Inflater and the bit-unshuffle's scratch buffer. */
    static final class Scratch {
        private Inflater inflater;
        private byte[] bitTmp;

        Inflater inflater() {
            if (inflater == null) {
                inflater = new Inflater();
            } else {
                inflater.reset();
            }
            return inflater;
        }

        byte[] bitTmp(int size) {
            if (bitTmp == null || bitTmp.length < size) {
                bitTmp = new byte[size];
            }
            return bitTmp;
        }

        void close() {
            if (inflater != null) {
                inflater.end();
            }
        }
    }

    /**
     * Blosc's bit-unshuffle (c-blosc format version 2, {@code blosc_internal_bitunshuffle}): a block whose
     * element count is a multiple of 8 is un-transposed, any trailing partial element copied through; any
     * other block was stored as it was.
     */
    private static void bitUnshuffle(byte[] block, byte[] out, int destination, int length, int typeSize,
                                     Scratch scratch) {
        int elements = length / typeSize;
        if (elements % 8 != 0) {
            System.arraycopy(block, 0, out, destination, length);
            return;
        }
        Bitshuffle.untranspose(block, 0, out, destination, elements, typeSize, scratch.bitTmp(elements * typeSize));
        int shuffled = elements * typeSize;
        System.arraycopy(block, shuffled, out, destination + shuffled, length - shuffled);
    }

    /** Decodes one block, which may be stored as a single stream or split one stream per byte position. */
    private static void decodeBlock(byte[] src, int offset, int length, byte[] block, int blockBytes,
                                    int typeSize, int compressor, Scratch scratch) {
        if (length < 4) {
            throw new CompressionFormatException("Blosc block is too short to hold a stream length");
        }
        int firstLength = le32(src, offset);
        // One stream covers the whole block; otherwise it was split into `typesize` byte planes.
        int streams = (4 + firstLength == length || typeSize <= 1) ? 1 : typeSize;
        if (blockBytes % streams != 0) {
            throw new CompressionFormatException(
                    "Blosc block of " + blockBytes + " bytes does not divide into " + streams + " streams");
        }
        int streamBytes = blockBytes / streams;

        int cursor = offset;
        int end = offset + length;
        for (int s = 0; s < streams; s++) {
            if (cursor + 4 > end) {
                throw new CompressionFormatException("Blosc stream length is truncated");
            }
            int compressed = le32(src, cursor);
            cursor += 4;
            if (compressed < 0 || compressed > end - cursor) {
                throw new CompressionFormatException("Blosc stream payload is truncated");
            }
            int target = s * streamBytes;
            if (compressed == streamBytes) {
                // Incompressible: stored as-is.
                System.arraycopy(src, cursor, block, target, streamBytes);
            } else {
                inflate(compressor, src, cursor, compressed, block, target, streamBytes, scratch);
            }
            cursor += compressed;
        }
    }

    /** Decompresses one stream with internal codec {@code compressor} (the code in flags bits 5&ndash;7). */
    static void inflate(int compressor, byte[] src, int srcOff, int srcLen,
                                byte[] dst, int dstOff, int dstLen, Scratch scratch) {
        switch (compressor) {
            case COMPRESSOR_LZ4 -> Lz4.decompress(src, srcOff, srcLen, dst, dstOff, dstLen);
            case COMPRESSOR_ZSTD -> {
                byte[] decoded = ZstdDecoder.decompress(src, srcOff, srcLen, dstLen);
                if (decoded.length != dstLen) {
                    throw new CompressionFormatException("Blosc zstd stream produced " + decoded.length
                            + " bytes, expected " + dstLen);
                }
                System.arraycopy(decoded, 0, dst, dstOff, dstLen);
            }
            case COMPRESSOR_ZLIB -> zlib(src, srcOff, srcLen, dst, dstOff, dstLen, scratch.inflater());
            case COMPRESSOR_BLOSCLZ -> BloscLz.decompress(src, srcOff, srcLen, dst, dstOff, dstLen);
            case COMPRESSOR_SNAPPY -> Snappy.decompress(src, srcOff, srcLen, dst, dstOff, dstLen);
            default -> throw new CompressionFormatException("unknown Blosc internal codec " + compressor);
        }
    }

    private static void zlib(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstLen,
                             Inflater inflater) {
        try {
            inflater.setInput(src, srcOff, srcLen);
            int produced = 0;
            while (produced < dstLen) {
                int n = inflater.inflate(dst, dstOff + produced, dstLen - produced);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) {
                        break;
                    }
                    throw new CompressionFormatException("Blosc zlib stream stalled");
                }
                produced += n;
            }
            if (produced != dstLen) {
                throw new CompressionFormatException(
                        "Blosc zlib stream produced " + produced + " bytes, expected " + dstLen);
            }
        } catch (DataFormatException e) {
            throw new CompressionFormatException("Blosc zlib stream is corrupt: " + e.getMessage());
        }
    }

    /**
     * The smallest block offset strictly greater than {@code offset}, or {@code cbytes} if there is none: a
     * binary search, since a linear one made decoding quadratic in the number of blocks.
     */
    private static int nextOffsetAbove(int[] ascending, int offset, int cbytes) {
        int lo = 0;
        int hi = ascending.length; // the answer's index lies in [lo, hi]
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ascending[mid] > offset) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo < ascending.length ? ascending[lo] : cbytes;
    }

    private static void requireHeader(byte[] src) {
        if (src.length < HEADER_LENGTH) {
            throw new CompressionFormatException(
                    "Blosc buffer is " + src.length + " bytes, shorter than its 16-byte header");
        }
    }

    static int le32(byte[] src, int off) {
        return (src[off] & 0xff) | ((src[off + 1] & 0xff) << 8)
                | ((src[off + 2] & 0xff) << 16) | ((src[off + 3] & 0xff) << 24);
    }
}
