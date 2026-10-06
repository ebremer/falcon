package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import com.ebremer.falcon.core.compress.bitshuffle.Bitshuffle;
import com.ebremer.falcon.core.compress.blosc.BloscDecoder;
import com.ebremer.falcon.core.compress.blosc.BloscEncoder;
import com.ebremer.falcon.core.compress.bzip2.Bzip2Decoder;
import com.ebremer.falcon.core.compress.bzip2.Bzip2Encoder;
import com.ebremer.falcon.core.compress.lz4.Lz4;
import com.ebremer.falcon.core.compress.lzf.Lzf;
import com.ebremer.falcon.core.compress.zstd.ZstdDecoder;
import com.ebremer.falcon.core.compress.zstd.ZstdEncoder;
import com.ebremer.falcon.hdf5.HdfFormatException;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import java.util.Arrays;

/**
 * Decoders (and, for all but Blosc2, encoders) for the registered third-party HDF5 filters most common in the
 * wild, built on Falcon Core's pure-Java codecs. Each filter's chunk framing and client data come from its
 * reference HDF5 plugin:
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
 *   <li><b>bzip2</b> (307, PyTables' {@code H5Zbzip2.c}): one bzip2 stream; client data 0, when set, is the
 *       block size it was written with (1 to 9, in units of 100000 bytes), which the stream records too.
 *       Bytes after the stream are ignored, as {@code BZ2_bzDecompress} ignores them.</li>
 *   <li><b>Blosc2</b> (32026, {@code hdf5-blosc2}): a Blosc2 frame, a b2nd array for chunks of rank 2 and up
 *       (see {@link Blosc2Filter}).</li>
 * </ul>
 *
 * Every decoded size a chunk declares is checked against the chunk's size before anything is allocated.
 *
 * <p>{@link #encode} applies each filter as its plugin does on write (byte for byte its output, but for
 * zstd: Falcon's own encoder, which libzstd reads), and {@link #pluginName} gives the name each plugin
 * registers, which libhdf5 stores in the pipeline message.
 */
public final class ThirdPartyFilters {

    static final int LZF = 32000;
    static final int BLOSC = 32001;
    static final int LZ4 = 32004;
    static final int BITSHUFFLE = 32008;
    static final int ZSTD = 32015;
    static final int BZIP2 = 307;
    static final int BLOSC2 = 32026;

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
                case BZIP2 -> Bzip2Decoder.decompress(data, 0, data.length, max);
                case BLOSC2 -> Blosc2Filter.decode(clientData, data, max);
                default -> throw new IllegalArgumentException("not a third-party filter: " + id);
            };
        } catch (CompressionFormatException e) {
            throw new HdfFormatException(name(id) + " filter: " + e.getMessage(), e);
        } catch (UnsupportedCompressionException e) {
            throw new HdfUnsupportedException(name(id) + " filter: " + e.getMessage());
        }
    }

    /**
     * Applies filter {@code id} to a chunk on write, as its reference plugin does with the client data its
     * {@code set_local} stored (taken as the file has it):
     *
     * <ul>
     *   <li><b>LZF</b> ({@code lzf_filter.c}): liblzf's stream, in at most the chunk's size;</li>
     *   <li><b>Blosc</b> ({@code blosc_filter.c}): {@code blosc_compress} with type size {@code cd[2]}, clevel
     *       {@code cd[4]} (default 5), shuffle {@code cd[5]} (default 1) and compressor {@code cd[6]} (default
     *       BloscLZ), in at most the chunk's size;</li>
     *   <li><b>LZ4</b> ({@code H5Zlz4.c}): blocks of {@code cd[0]} bytes (0 or absent: 1 GiB), at most the
     *       chunk, each compressed by {@code LZ4_compress_default}, or stored raw if that does not shrink it;</li>
     *   <li><b>bitshuffle</b> ({@code bshuf_h5filter.c}): elements of {@code cd[2]} bytes in blocks of
     *       {@code cd[3]} (0: the default), bit-shuffled, then for {@code cd[4]} 2 or 3 compressed with LZ4 or
     *       zstd at level {@code cd[5]}, behind a 12-byte header;</li>
     *   <li><b>Zstandard</b> ({@code H5Zzstd.c}): one frame at level {@code cd[0]} (default 3), clamped to
     *       -131072 to 22;</li>
     *   <li><b>bzip2</b> ({@code H5Zbzip2.c}): one stream, {@code BZ2_bzBuffToBuffCompress} in blocks of
     *       {@code cd[0]} (default 9) times 100,000 bytes, kept even where it is larger than the chunk.</li>
     * </ul>
     *
     * @param id         the filter
     * @param clientData its client data
     * @param data       the chunk, as the filters before this one left it
     * @return the filtered chunk, or null where the plugin returns 0 (LZF or Blosc output that would not be
     *         smaller than the chunk, a bitshuffle chunk that is not whole elements, parameters its library
     *         refuses, such as a bzip2 block size outside 1 to 9): libhdf5 then skips the optional filter for the chunk, in its filter mask
     */
    public static byte[] encode(int id, int[] clientData, byte[] data) {
        return switch (id) {
            case LZF -> Lzf.compress(data, 0, data.length, data.length);
            case BLOSC -> encodeBlosc(clientData, data);
            case LZ4 -> encodeLz4(clientData, data);
            case BITSHUFFLE -> encodeBitshuffle(clientData, data);
            case ZSTD -> ZstdEncoder.compress(data, clientData.length > 0
                    ? Math.max(ZSTD_MIN_LEVEL, Math.min(ZstdEncoder.MAX_LEVEL, clientData[0]))
                    : ZstdEncoder.DEFAULT_LEVEL, false);
            case BZIP2 -> encodeBzip2(clientData, data);
            default -> throw new IllegalArgumentException("not a third-party filter Falcon writes: " + id);
        };
    }

    /**
     * The name filter {@code id}'s plugin registers ({@code H5Z_class2_t.name}): what libhdf5 stores for it in
     * the filter pipeline message when h5py and hdf5plugin write a dataset.
     *
     * @param id one of the filters {@link #encode} applies
     * @return the plugin's name
     */
    public static String pluginName(int id) {
        return switch (id) {
            case LZF -> "lzf";
            case BLOSC -> "blosc";
            case LZ4 -> "HDF5 lz4 filter; see " + HDF_GROUP_PLUGINS;
            case BITSHUFFLE -> "bitshuffle; see https://github.com/kiyo-masui/bitshuffle";
            case ZSTD -> "HDF5 zstd filter; see " + HDF_GROUP_PLUGINS;
            case BZIP2 -> "bzip2";
            default -> throw new IllegalArgumentException("not a third-party filter Falcon writes: " + id);
        };
    }

    private static final String HDF_GROUP_PLUGINS =
            "https://github.com/HDFGroup/hdf5_plugins/blob/master/docs/RegisteredFilterPlugins.md";
    /** zstd's lowest level ({@code ZSTD_minCLevel()}, -2^17), where H5Zzstd.c clamps a lower one. */
    private static final int ZSTD_MIN_LEVEL = -(1 << 17);
    /** H5Zlz4.c's {@code DEFAULT_BLOCK_SIZE}: 1 GiB. */
    private static final long LZ4_DEFAULT_BLOCK = 1L << 30;
    /** liblz4's {@code LZ4_MAX_INPUT_SIZE}: a larger block fails {@code LZ4_compress_default}. */
    private static final long LZ4_MAX_INPUT = 0x7E000000L;

    /**
     * hdf5-blosc's write: {@code blosc_compress} into a destination of the chunk's size, so a buffer that would
     * not shrink fails the filter, as do parameters c-blosc refuses (clevel above 9, shuffle above 2, an
     * unknown compressor).
     */
    private static byte[] encodeBzip2(int[] cd, byte[] data) {
        int blockSize = cd.length > 0 ? cd[0] : 9;
        if (blockSize < 1 || blockSize > 9) {
            return null; // H5Zbzip2.c: "invalid compression block size"
        }
        return Bzip2Encoder.compress(data, blockSize);
    }

    private static byte[] encodeBlosc(int[] cd, byte[] data) {
        if (cd.length < 4) {
            return null; // set_local always stores 4
        }
        int clevel = cd.length >= 5 ? cd[4] : 5;
        int shuffle = cd.length >= 6 ? cd[5] : BloscEncoder.SHUFFLE;
        int compressor = cd.length >= 7 ? cd[6] : BloscEncoder.BLOSCLZ;
        if (clevel < 0 || clevel > 9 || shuffle < BloscEncoder.NOSHUFFLE || shuffle > BloscEncoder.BITSHUFFLE
                || compressor < BloscEncoder.BLOSCLZ || compressor > BloscEncoder.ZSTD
                || data.length > Integer.MAX_VALUE - 32) {
            return null;
        }
        // cd[2] is unsigned: c-blosc takes a type size beyond 255 as a stream of bytes
        int typeSize = Integer.compareUnsigned(cd[2], 255) > 0 ? 1 : cd[2];
        return BloscEncoder.compress(data, typeSize, shuffle, 0, clevel, compressor, data.length);
    }

    /** H5Zlz4.c's write: {@code decoded size (8, BE) · block size (4, BE)}, then each block's size and bytes. */
    private static byte[] encodeLz4(int[] cd, byte[] data) {
        long nbytes = data.length;
        long blockSize = cd.length > 0 && cd[0] != 0 ? cd[0] & 0xffffffffL : LZ4_DEFAULT_BLOCK;
        blockSize = Math.min(blockSize, nbytes);
        if (nbytes == 0 || blockSize > LZ4_MAX_INPUT) {
            return null;
        }
        long blocks = (nbytes - 1) / blockSize + 1;
        long bound = 12 + blocks * 4 + (nbytes / blockSize) * Lz4.maxCompressedLength((int) blockSize)
                + (nbytes % blockSize == 0 ? 0 : Lz4.maxCompressedLength((int) (nbytes % blockSize)));
        byte[] out = new byte[(int) Math.min(Integer.MAX_VALUE - 8, bound)];
        putBe64(out, 0, nbytes);
        putBe32(out, 8, blockSize);
        int pos = 12;
        int done = 0;
        while (done < nbytes) {
            int block = (int) Math.min(blockSize, nbytes - done);
            int compressed = Lz4.compress(data, done, block, out, pos + 4, Lz4.maxCompressedLength(block), 1);
            if (compressed >= block) { // it did not shrink: stored raw
                compressed = block;
                System.arraycopy(data, done, out, pos + 4, block);
            }
            putBe32(out, pos, compressed);
            pos += 4 + compressed;
            done += block;
        }
        return Arrays.copyOf(out, pos);
    }

    /**
     * bshuf_h5filter.c's write: bit-shuffled blocks, alone or, for compression 2 (LZ4) or 3 (zstd), compressed
     * behind {@code decoded size (8, BE) · block bytes (4, BE)}.
     */
    private static byte[] encodeBitshuffle(int[] cd, byte[] data) {
        if (cd.length < 3) {
            return null; // "Not enough parameters."
        }
        long elementSize = cd[2] & 0xffffffffL;
        long blockSize = cd.length > 3 ? cd[3] & 0xffffffffL : 0;
        if (elementSize == 0 || elementSize > Integer.MAX_VALUE || data.length % elementSize != 0
                || blockSize % 8 != 0 || blockSize * elementSize > Integer.MAX_VALUE - 8) {
            return null; // "Non integer number of elements.", or bitshuffle's error -81
        }
        int size = (int) elementSize;
        int block = blockSize == 0 ? Bitshuffle.defaultBlockSize(size) : (int) blockSize;
        int elements = data.length / size;
        int compression = cd.length > 4 ? cd[4] : 0;
        if (compression != BSHUF_COMPRESS_LZ4 && compression != BSHUF_COMPRESS_ZSTD) {
            return Bitshuffle.shuffle(data, 0, elements, size, block);
        }
        // cd[5] is zstd's level; the filter reads it even when only five values are stored
        byte[] blocks = Bitshuffle.compress(data, 0, elements, size, block, compression == BSHUF_COMPRESS_LZ4
                ? Bitshuffle.BlockCodec.LZ4 : Bitshuffle.BlockCodec.ZSTD, cd.length > 5 ? cd[5] : 0);
        byte[] out = new byte[12 + blocks.length];
        putBe64(out, 0, data.length);
        putBe32(out, 8, (long) block * size);
        System.arraycopy(blocks, 0, out, 12, blocks.length);
        return out;
    }

    private static void putBe64(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            b[off + i] = (byte) v;
            v >>>= 8;
        }
    }

    private static void putBe32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
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
            case BZIP2 -> "bzip2";
            case BLOSC2 -> "blosc2";
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
