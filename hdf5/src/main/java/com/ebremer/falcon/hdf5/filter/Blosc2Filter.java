package com.ebremer.falcon.hdf5.filter;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.blosc.B2ndArray;
import com.ebremer.falcon.core.compress.blosc.Blosc2Frame;
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
 */
final class Blosc2Filter {

    private static final int TYPE_SIZE = 2;
    private static final int CHUNK_BYTES = 3;
    private static final int RANK = 7;

    private Blosc2Filter() {
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
