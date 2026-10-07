package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.blosc.B2ndArray;
import com.ebremer.falcon.core.compress.blosc.Blosc2Encoder;
import com.ebremer.falcon.core.compress.blosc.Blosc2Frame;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import java.util.Arrays;

/**
 * The Blosc2 filter (32026, {@code hdf5-blosc2}'s {@code blosc2_filter.c}, as hdf5plugin 7.1 builds it with
 * c-blosc2 3.3.2). Every chunk is a Blosc2 contiguous frame ({@link Blosc2Frame}):
 *
 * <ul>
 *   <li>with a {@code "b2nd"} (or {@code "caterva"}) metalayer, a b2nd array ({@link B2ndArray}) whose shape is
 *       the chunk's, cut into blocks of a shape the plugin derives from the block size, which are read back
 *       into C order (what the plugin writes for chunks of rank 2 and up);</li>
 *   <li>otherwise, a one-chunk super-chunk, whose first chunk is the data.</li>
 * </ul>
 *
 * Client data: {@code [filter version, block size, type size, chunk bytes, clevel, filters (shuffle),
 * compcode, ndim, chunk shape...]}; the rank and the chunk shape appear only for b2nd chunks, and are then
 * checked against the frame's array, as the plugin checks them.
 *
 * <p>{@link #encode} writes a chunk as the plugin does, through Falcon Core's {@link Blosc2Encoder}: the
 * plugin's frame byte for byte, but for zstd's (Falcon's own zstd frames, which c-blosc2 reads).
 */
final class Blosc2Filter {

    private static final int BLOCK_SIZE = 1;
    private static final int TYPE_SIZE = 2;
    private static final int CHUNK_BYTES = 3;
    private static final int RANK = 7;

    /** hdf5-blosc2's {@code FILTER_BLOSC2_VERSION}, which {@code set_local} stores in client data value 0. */
    static final int VERSION = 1;
    /** The name the plugin registers. */
    static final String NAME = "blosc2";
    /** c-blosc2's {@code BLOSC_TRUNC_PREC}, which the plugin cannot apply: it sets no {@code filters_meta}. */
    private static final int TRUNC_PREC = 4;
    /** Filters and codecs numbered from 32 are c-blosc2's registered plugins, which Falcon does not write. */
    private static final int REGISTERED_START = 32;

    private Blosc2Filter() {
    }

    /**
     * The client data {@code blosc2_set_local} stores for a new dataset from hdf5plugin's {@code (0, 0, 0, 0,
     * clevel, filters, compcode)}: the plugin's version, block size 0 (automatic), the type size (an array's
     * base type), the chunk's bytes (an unsigned 32-bit product, as the plugin computes it), clevel, filter, and
     * compressor; then, for a chunk of rank 2 to 16, the rank and the chunk's dimensions.
     *
     * @param typeSize    the datatype's size, or an array's base type's
     * @param elementSize the datatype's size
     * @param chunkShape  the chunk's shape
     * @param options     clevel, filter, and compressor code
     * @return the client data
     */
    static int[] clientData(int typeSize, int elementSize, long[] chunkShape, int[] options) {
        int rank = chunkShape.length;
        boolean b2nd = rank > 1 && rank <= B2ndArray.MAX_DIM;
        int[] cd = new int[b2nd ? 8 + rank : 7];
        cd[0] = VERSION;
        cd[TYPE_SIZE] = typeSize;
        int bytes = elementSize;
        for (long n : chunkShape) {
            bytes *= (int) n;
        }
        cd[CHUNK_BYTES] = bytes;
        System.arraycopy(options, 0, cd, 4, 3);
        if (b2nd) {
            cd[RANK] = rank;
            for (int i = 0; i < rank; i++) {
                cd[8 + i] = (int) chunkShape[i];
            }
        }
        return cd;
    }

    /**
     * Compresses one chunk as {@code blosc2_filter_function} does: a b2nd frame when the client data gives a
     * rank (2 to 16) and the chunk is exactly that shape at the client data's type size, its blocks shaped as
     * {@code compute_b2nd_block_shape} shapes them from the block size (0: what c-blosc2's tuner gives the
     * chunk with the byte shuffle, as {@code compute_blosc2_blocksize} asks it); otherwise a one-chunk
     * super-chunk frame, with the client data's block size (0: the tuner's).
     *
     * @return the frame, or null where the plugin fails and libhdf5 skips the optional filter: fewer than 6
     *         client-data values, a clevel above 9, truncated precision (the plugin sets no
     *         {@code filters_meta}), an unknown filter or compressor, a chunk shape or type c-blosc2 refuses
     * @throws HdfUnsupportedException for c-blosc2's registered filter and codec plugins, a b2nd chunk of
     *                                 elements over 255 bytes (the plugin cannot read back what it writes), and
     *                                 a block size that is not a multiple of the type size
     */
    static byte[] encode(int[] clientData, byte[] data) {
        if (clientData.length < 6) {
            return null; // too few filter parameters for Blosc2 compression
        }
        long blockSize = clientData[BLOCK_SIZE] & 0xffffffffL;
        long typeSize = clientData[TYPE_SIZE] & 0xffffffffL;
        long outBufSize = clientData[CHUNK_BYTES] & 0xffffffffL;
        int clevel = clientData[4];
        int filter = clientData[5];
        int compressor = clientData.length >= 7 ? clientData[6] : Blosc2Encoder.BLOSCLZ;
        int rank = -1;
        int[] chunkShape = null;
        long chunkSize = typeSize;
        if (clientData.length >= 8) {
            rank = clientData[RANK];
            if (rank < 2 || rank > B2ndArray.MAX_DIM || clientData.length < 8 + rank) {
                return null;
            }
            chunkShape = new int[rank];
            for (int i = 0; i < rank; i++) {
                chunkShape[i] = clientData[8 + i];
                chunkSize *= clientData[8 + i] & 0xffffffffL;
            }
        }
        if (compressor >= REGISTERED_START || filter >= REGISTERED_START) {
            throw new HdfUnsupportedException("Blosc2 filter: compressor " + compressor + " or filter " + filter
                    + " is a c-blosc2 plugin, which Falcon does not write");
        }
        if (compressor != Blosc2Encoder.BLOSCLZ && compressor != Blosc2Encoder.LZ4 && compressor != Blosc2Encoder.LZ4HC
                && compressor != Blosc2Encoder.ZLIB && compressor != Blosc2Encoder.ZSTD) {
            return null; // the library has no such compressor
        }
        if (clevel < 0 || clevel > 9 || filter < 0 || filter >= TRUNC_PREC || typeSize == 0 || typeSize > Integer.MAX_VALUE) {
            return null; // c-blosc2 refuses the parameters, or (truncated precision) the filter fails
        }
        int ts = (int) typeSize;
        if (rank > 1 && data.length != chunkSize) {
            rank = -1; // the chunk is not the filter's (an array type, or a filter before changed it): plain
        }
        if (rank > 1) {
            for (int n : chunkShape) {
                if (n <= 0) {
                    return null; // b2nd_create_ctx refuses the shape
                }
            }
            if (ts > 255) {
                throw new HdfUnsupportedException("Blosc2 filter: hdf5-blosc2 cannot read back the b2nd chunks it"
                        + " writes of " + ts + "-byte elements (c-blosc2 records them as single bytes)");
            }
            if (blockSize == 0) {
                if (outBufSize % typeSize != 0 || outBufSize > Integer.MAX_VALUE - 32) {
                    return null; // compute_blosc2_blocksize: blosc2_chunk_zeros refuses the size
                }
                blockSize = Blosc2Encoder.automaticBlockSize((int) outBufSize, ts, clevel, compressor,
                        Blosc2Encoder.SHUFFLE);
            }
            int[] blockShape = blockShape(blockSize, typeSize, chunkShape);
            long[] shape = new long[rank];
            for (int i = 0; i < rank; i++) {
                shape[i] = chunkShape[i];
            }
            return Blosc2Encoder.b2ndFrame(data, ts, shape, chunkShape, blockShape, Blosc2Encoder.opaqueDtype(ts),
                    clevel, compressor, filter);
        }
        if (blockSize > Integer.MAX_VALUE || ts <= 255 && blockSize > ts && blockSize % ts != 0) {
            throw new HdfUnsupportedException("Blosc2 filter: a block size of " + blockSize + " bytes is not a"
                    + " multiple of the " + ts + "-byte type, which c-blosc2 does not record consistently");
        }
        return Blosc2Encoder.frame(data, ts, clevel, compressor, filter, (int) blockSize);
    }

    /**
     * hdf5-blosc2's {@code compute_b2nd_block_shape}: from 2 in each dimension (1 where the chunk has 1), the
     * dimensions doubled from the innermost out, each at most the chunk's (or extended to it), while the block
     * holds at most {@code blockSize / typeSize} items.
     */
    static int[] blockShape(long blockSize, long typeSize, int[] chunkShape) {
        int rank = chunkShape.length;
        long nitems = blockSize / typeSize;
        long nitemsNew = 1;
        int[] block = new int[rank];
        for (int i = 0; i < rank; i++) {
            block[i] = chunkShape[i] == 1 ? 1 : 2;
            nitemsNew *= block[i];
        }
        if (nitemsNew >= nitems) {
            return block;
        }
        while (nitemsNew < nitems) {
            long previous = nitemsNew;
            for (int i = rank - 1; i >= 0; i--) {
                if (block[i] * 2 <= chunkShape[i]) {
                    if (nitemsNew * 2 <= nitems) {
                        nitemsNew *= 2;
                        block[i] *= 2;
                    }
                } else if (block[i] < chunkShape[i]) {
                    long extended = nitemsNew / block[i] * chunkShape[i];
                    if (extended <= nitems) {
                        nitemsNew = extended;
                        block[i] = chunkShape[i];
                    }
                }
            }
            if (nitemsNew == previous) {
                break;
            }
        }
        return block;
    }

    /**
     * Decodes one chunk, as {@code blosc2_filter_function} does in reverse.
     *
     * @param max the most bytes the chunk may decode to
     */
    static byte[] decode(int[] clientData, byte[] data, int max) {
        if (clientData.length < 4) {
            throw new CompressionFormatException("too few filter parameters (" + clientData.length + ")");
        }
        int typeSize = clientData[TYPE_SIZE];
        long chunkBytes = clientData[CHUNK_BYTES] & 0xffffffffL;
        int rank = -1;
        long[] chunkShape = null;
        if (clientData.length >= 8) {
            rank = clientData[RANK];
            if (rank < 2 || rank > B2ndArray.MAX_DIM) {
                throw new CompressionFormatException("chunk rank " + Integer.toUnsignedString(rank)
                        + " in the filter parameters is not 2 to " + B2ndArray.MAX_DIM);
            }
            if (clientData.length < 8 + rank) {
                throw new CompressionFormatException("too few chunk dimensions in the filter parameters ("
                        + (clientData.length - 8) + " of " + rank + ")");
            }
            chunkShape = new long[rank];
            for (int i = 0; i < rank; i++) {
                chunkShape[i] = clientData[8 + i]; // an int32_t, as the plugin reads it
            }
        }
        Blosc2Frame frame = Blosc2Frame.read(data, max);
        B2ndArray array = B2ndArray.of(frame);
        if (array == null) {
            return frame.chunk(0);
        }
        if (rank >= 0 && array.ndim() != rank) {
            throw new CompressionFormatException("b2nd array of rank " + array.ndim() + ", not the filter's " + rank);
        }
        long[] shape = array.shape();
        long items = 1;
        for (int i = 0; i < shape.length; i++) {
            if (rank >= 0 && shape[i] != chunkShape[i]) {
                throw new CompressionFormatException("b2nd array shape " + Arrays.toString(shape)
                        + " is not the filter's chunk shape " + Arrays.toString(chunkShape));
            }
            items *= shape[i];
        }
        // b2nd_get_slice_cbuffer fills a buffer of the client data's chunk size; the plugin reports the array's
        // items at the client data's type size.
        byte[] decoded = array.read();
        if (decoded.length > chunkBytes) {
            throw new CompressionFormatException("b2nd array of " + decoded.length + " bytes is larger than the "
                    + chunkBytes + "-byte chunk in the filter parameters");
        }
        long size = items * typeSize;
        if (typeSize < 0 || size > max) {
            throw new CompressionFormatException("b2nd array of " + items + " items of " + typeSize
                    + " bytes is more than the " + max + " expected");
        }
        return decoded.length == size ? decoded : Arrays.copyOf(decoded, (int) size);
    }
}
