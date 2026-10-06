package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.blosc.BloscDecoder;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.core.compress.lzf.Lzf;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;

/**
 * Decoders for the registered third-party HDF5 filters most common in the wild, built on Falcon Core's
 * pure-Java codecs. Each filter's chunk framing and client data come from its reference HDF5 plugin:
 *
 * <ul>
 *   <li><b>LZF</b> (32000, h5py's {@code lzf_filter.c}): a bare LZF stream; client data 2, when set, is the
 *       chunk size.</li>
 *   <li><b>Blosc</b> (32001, {@code hdf5-blosc}): a Blosc buffer, in c-blosc's format (version 2) or
 *       c-blosc2's (versions 3 to 6), which records everything needed to decode it.</li>
 *   <li><b>LZ4</b> (32004, the HDF Group's {@code H5Zlz4.c}): {@code decoded size (8, big-endian) · block
 *       size (4, big-endian)}, then per block {@code compressed size (4, big-endian) · LZ4 block}; a block
 *       whose compressed size equals its size is stored raw.</li>
 *   <li><b>bitshuffle</b> (32008, {@code bshuf_h5filter.c}): client data {@code [major, minor, element
 *       size, block size, compression, level]}. Without compression the chunk is the bit-shuffled data;
 *       with LZ4 (2) or zstd (3) it is {@code decoded size (8, big-endian) · block bytes (4, big-endian)}
 *       then the compressed blocks.</li>
 *   <li><b>Zstandard</b> (32015, the HDF Group's {@code H5Zzstd.c}): one zstd frame.</li>
 * </ul>
 *
 * Every decoded size a chunk declares is checked against the chunk's size before anything is allocated.
 */
final class ThirdPartyFilters {

    static final int LZF = 32000;
    static final int BLOSC = 32001;
    static final int LZ4 = 32004;
    static final int BITSHUFFLE = 32008;
    static final int ZSTD = 32015;

    private static final int BSHUF_COMPRESS_LZ4 = 2;
    private static final int BSHUF_COMPRESS_ZSTD = 3;

    private ThirdPartyFilters() {
    }

    /**
     * Reverses filter {@code id} on {@code data}, producing at most {@code maxBytes}.
     *
     * @param chunkSize the chunk's decoded size in bytes
     */
    static byte[] decode(int id, int[] clientData, byte[] data, int elementSize, int chunkSize, long maxBytes) {
        int max = (int) Math.min(Integer.MAX_VALUE - 8, maxBytes);
        try {
            return switch (id) {
                case LZF -> Lzf.decompress(data, 0, data.length,
                        clientData.length > 2 && clientData[2] > 0 ? Math.min(clientData[2], max) : chunkSize, max);
                case BLOSC -> blosc(data, max);
                case LZ4 -> lz4(data, max);
                case BITSHUFFLE -> bitshuffle(data, clientData, elementSize, max);
                case ZSTD -> ZstdDecoder.decompress(data, 0, data.length, max);
                default -> throw new IllegalArgumentException("not a third-party filter: " + id);
            };
        } catch (CompressionFormatException e) {
            throw new HdfFormatException(name(id) + " filter: " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new HdfUnsupportedException(name(id) + " filter: " + e.getMessage());
        }
    }

    private static byte[] blosc(byte[] data, int max) {
        int size = BloscDecoder.decompressedSize(data);
        if (size < 0 || size > max) {
            throw new CompressionFormatException("chunk declares " + size + " decoded bytes, more than its " + max);
        }
        return BloscDecoder.decompress(data);
    }

    private static byte[] lz4(byte[] data, int max) {
        if (data.length < 12) {
            throw new CompressionFormatException("chunk of " + data.length + " bytes is shorter than its header");
        }
        long size = be64(data, 0);
        long blockSize = be32(data, 8);
        if (size < 0 || size > max) {
            throw new CompressionFormatException("chunk declares " + size + " decoded bytes, more than its " + max);
        }
        if (blockSize == 0 && size > 0) {
            throw new CompressionFormatException("block size of zero");
        }
        byte[] out = new byte[(int) size];
        int in = 12;
        int done = 0;
        while (done < size) {
            int block = (int) Math.min(blockSize, size - done);
            if (data.length - in < 4) {
                throw new CompressionFormatException("block size is truncated");
            }
            long compressed = be32(data, in);
            in += 4;
            if (compressed > data.length - in) {
                throw new CompressionFormatException("block of " + compressed + " bytes overruns the chunk");
            }
            if (compressed == block) {
                System.arraycopy(data, in, out, done, block); // stored uncompressed
            } else {
                Lz4.decompress(data, in, (int) compressed, out, done, block);
            }
            in += (int) compressed;
            done += block;
        }
        return out;
    }

    private static byte[] bitshuffle(byte[] data, int[] clientData, int elementSize, int max) {
        int size = clientData.length > 2 ? clientData[2] : elementSize;
        if (size < 1) {
            throw new CompressionFormatException("element size " + size);
        }
        int compression = clientData.length > 4 ? clientData[4] : 0;
        if (compression == BSHUF_COMPRESS_LZ4 || compression == BSHUF_COMPRESS_ZSTD) {
            if (data.length < 12) {
                throw new CompressionFormatException("chunk of " + data.length + " bytes is shorter than its header");
            }
            long decoded = be64(data, 0);
            long blockBytes = be32(data, 8);
            if (decoded < 0 || decoded > max) {
                throw new CompressionFormatException("chunk declares " + decoded + " decoded bytes, more than its " + max);
            }
            if (decoded % size != 0 || blockBytes % size != 0) {
                throw new CompressionFormatException("sizes are not whole elements of " + size + " bytes");
            }
            return Bitshuffle.decompress(data, 12, data.length - 12, (int) (decoded / size), size,
                    (int) (blockBytes / size), compression == BSHUF_COMPRESS_LZ4
                            ? Bitshuffle.BlockCodec.LZ4 : Bitshuffle.BlockCodec.ZSTD);
        }
        if (compression != 0) {
            throw new UnsupportedCompressionException("bitshuffle compression " + compression + " is not supported");
        }
        if (data.length % size != 0) {
            throw new CompressionFormatException("chunk of " + data.length + " bytes is not whole elements of " + size);
        }
        int blockSize = clientData.length > 3 ? clientData[3] : 0;
        return Bitshuffle.unshuffle(data, 0, data.length / size, size, blockSize);
    }

    private static String name(int id) {
        return switch (id) {
            case LZF -> "lzf";
            case BLOSC -> "blosc";
            case LZ4 -> "lz4";
            case BITSHUFFLE -> "bitshuffle";
            default -> "zstd";
        };
    }

    private static long be64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xff);
        }
        return v;
    }

    private static long be32(byte[] b, int off) {
        return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16) | ((b[off + 2] & 0xffL) << 8) | (b[off + 3] & 0xffL);
    }
}
