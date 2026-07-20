package com.ebremer.falcon.zarr.codec.blosc;

import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.codec.zstd.ZstdDecoder;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A pure-Java decompressor for the Blosc container (c-blosc format version 2, what numcodecs and
 * zarr-python write).
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
 * <p>Internal codecs supported: {@code lz4} and {@code lz4hc} (identical block format), {@code zlib}
 * (via {@code java.util.zip}), and {@code zstd} (via Falcon's own decoder). {@code blosclz} and
 * {@code snappy} are not implemented, nor is the bit-shuffle filter; each is reported as
 * {@link ZarrUnsupportedException} rather than decoded wrongly.
 */
public final class BloscDecoder {

    /** Minimum header length, and where a memcpy'ed payload starts. */
    private static final int HEADER_LENGTH = 16;

    private static final int FLAG_SHUFFLE = 0x01;
    private static final int FLAG_MEMCPYED = 0x02;
    private static final int FLAG_BITSHUFFLE = 0x04;

    private static final int COMPRESSOR_BLOSCLZ = 0;
    private static final int COMPRESSOR_LZ4 = 1;
    private static final int COMPRESSOR_SNAPPY = 2;
    private static final int COMPRESSOR_ZLIB = 3;
    private static final int COMPRESSOR_ZSTD = 4;

    private BloscDecoder() {
    }

    /** The decompressed size recorded in a Blosc buffer's header. */
    public static int decompressedSize(byte[] src) {
        requireHeader(src);
        return le32(src, 4);
    }

    /**
     * Decompresses a Blosc buffer.
     *
     * @throws BloscFormatException     if the buffer is malformed
     * @throws ZarrUnsupportedException if it uses an internal codec or filter that is not implemented
     */
    public static byte[] decompress(byte[] src) {
        requireHeader(src);
        int version = src[0] & 0xff;
        int flags = src[2] & 0xff;
        int typeSize = src[3] & 0xff;
        int nbytes = le32(src, 4);
        int blocksize = le32(src, 8);
        int cbytes = le32(src, 12);

        if (version > 2) {
            throw new ZarrUnsupportedException("Blosc format version " + version + " is not supported");
        }
        if (nbytes < 0 || blocksize < 0 || cbytes < 0) {
            throw new BloscFormatException("Blosc header has a negative size");
        }
        if (cbytes > src.length) {
            throw new BloscFormatException(
                    "Blosc buffer declares " + cbytes + " bytes but only " + src.length + " are present");
        }

        byte[] out = new byte[nbytes];
        if (nbytes == 0) {
            return out;
        }

        // A memcpy'ed buffer holds the original bytes verbatim: no blocks, no filter.
        if ((flags & FLAG_MEMCPYED) != 0) {
            if (HEADER_LENGTH + nbytes > src.length) {
                throw new BloscFormatException("Blosc memcpy payload is truncated");
            }
            System.arraycopy(src, HEADER_LENGTH, out, 0, nbytes);
            return out;
        }

        if (blocksize == 0) {
            throw new BloscFormatException("Blosc block size of zero");
        }
        int compressor = (flags & 0xe0) >>> 5;
        boolean shuffle = (flags & FLAG_SHUFFLE) != 0;
        boolean bitShuffle = (flags & FLAG_BITSHUFFLE) != 0;

        int wholeBlocks = nbytes / blocksize;
        int leftover = nbytes % blocksize;
        int blockCount = wholeBlocks + (leftover > 0 ? 1 : 0);
        int tableEnd = HEADER_LENGTH + 4 * blockCount;
        if (tableEnd > src.length) {
            throw new BloscFormatException("Blosc block offset table is truncated");
        }

        // c-blosc may compress blocks in parallel and write them out of order, so a block's payload ends
        // at the nearest offset above its own, not at the next block's.
        int[] offsets = new int[blockCount];
        for (int b = 0; b < blockCount; b++) {
            offsets[b] = le32(src, HEADER_LENGTH + 4 * b);
        }
        int[] ascending = offsets.clone();
        java.util.Arrays.sort(ascending);

        byte[] block = new byte[blocksize];
        for (int b = 0; b < blockCount; b++) {
            int start = offsets[b];
            int end = nextOffsetAbove(ascending, start, cbytes);
            if (start < tableEnd || end > cbytes || end < start) {
                throw new BloscFormatException("Blosc block " + b + " has an invalid extent");
            }
            int blockBytes = (b == wholeBlocks && leftover > 0) ? leftover : blocksize;
            decodeBlock(src, start, end - start, block, blockBytes, typeSize, compressor);

            int destination = b * blocksize;
            if (bitShuffle) {
                BitShuffle.unshuffle(block, 0, out, destination, blockBytes, typeSize);
            } else if (shuffle) {
                Shuffle.unshuffle(block, 0, out, destination, blockBytes, typeSize);
            } else {
                System.arraycopy(block, 0, out, destination, blockBytes);
            }
        }
        return out;
    }

    /** Decodes one block, which may be stored as a single stream or split one stream per byte position. */
    private static void decodeBlock(byte[] src, int offset, int length, byte[] block, int blockBytes,
                                    int typeSize, int compressor) {
        if (length < 4) {
            throw new BloscFormatException("Blosc block is too short to hold a stream length");
        }
        int firstLength = le32(src, offset);
        // One stream covers the whole block; otherwise it was split into `typesize` byte planes.
        int streams = (4 + firstLength == length || typeSize <= 1) ? 1 : typeSize;
        if (blockBytes % streams != 0) {
            throw new BloscFormatException(
                    "Blosc block of " + blockBytes + " bytes does not divide into " + streams + " streams");
        }
        int streamBytes = blockBytes / streams;

        int cursor = offset;
        int end = offset + length;
        for (int s = 0; s < streams; s++) {
            if (cursor + 4 > end) {
                throw new BloscFormatException("Blosc stream length is truncated");
            }
            int compressed = le32(src, cursor);
            cursor += 4;
            if (compressed < 0 || cursor + compressed > end) {
                throw new BloscFormatException("Blosc stream payload is truncated");
            }
            int target = s * streamBytes;
            if (compressed == streamBytes) {
                // Incompressible: stored as-is.
                System.arraycopy(src, cursor, block, target, streamBytes);
            } else {
                inflate(compressor, src, cursor, compressed, block, target, streamBytes);
            }
            cursor += compressed;
        }
    }

    private static void inflate(int compressor, byte[] src, int srcOff, int srcLen,
                                byte[] dst, int dstOff, int dstLen) {
        switch (compressor) {
            case COMPRESSOR_LZ4 -> Lz4.decompress(src, srcOff, srcLen, dst, dstOff, dstLen);
            case COMPRESSOR_ZSTD -> {
                byte[] decoded = ZstdDecoder.decompress(src, srcOff, srcLen);
                if (decoded.length != dstLen) {
                    throw new BloscFormatException("Blosc zstd stream produced " + decoded.length
                            + " bytes, expected " + dstLen);
                }
                System.arraycopy(decoded, 0, dst, dstOff, dstLen);
            }
            case COMPRESSOR_ZLIB -> zlib(src, srcOff, srcLen, dst, dstOff, dstLen);
            case COMPRESSOR_BLOSCLZ -> BloscLz.decompress(src, srcOff, srcLen, dst, dstOff, dstLen);
            case COMPRESSOR_SNAPPY -> throw new ZarrUnsupportedException(
                    "the Blosc internal codec 'snappy' is not supported (see zarr/TODO.md)");
            default -> throw new BloscFormatException("unknown Blosc internal codec " + compressor);
        }
    }

    private static void zlib(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstLen) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(src, srcOff, srcLen);
            int produced = 0;
            while (produced < dstLen) {
                int n = inflater.inflate(dst, dstOff + produced, dstLen - produced);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) {
                        break;
                    }
                    throw new BloscFormatException("Blosc zlib stream stalled");
                }
                produced += n;
            }
            if (produced != dstLen) {
                throw new BloscFormatException(
                        "Blosc zlib stream produced " + produced + " bytes, expected " + dstLen);
            }
        } catch (DataFormatException e) {
            throw new BloscFormatException("Blosc zlib stream is corrupt: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    /** The smallest block offset strictly greater than {@code offset}, or {@code cbytes} if there is none. */
    private static int nextOffsetAbove(int[] ascending, int offset, int cbytes) {
        for (int candidate : ascending) {
            if (candidate > offset) {
                return candidate;
            }
        }
        return cbytes;
    }

    private static void requireHeader(byte[] src) {
        if (src.length < HEADER_LENGTH) {
            throw new BloscFormatException(
                    "Blosc buffer is " + src.length + " bytes, shorter than its 16-byte header");
        }
    }

    private static int le32(byte[] src, int off) {
        return (src[off] & 0xff) | ((src[off + 1] & 0xff) << 8)
                | ((src[off + 2] & 0xff) << 16) | ((src[off + 3] & 0xff) << 24);
    }
}
