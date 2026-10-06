package com.ebremer.falcon.core.compress.blosc;

import com.ebremer.falcon.core.compress.CompressionFormatException;
import com.ebremer.falcon.core.compress.UnsupportedCompressionException;
import java.util.Arrays;

/**
 * A b2nd (formerly Caterva) array: an N-dimensional array stored in a {@link Blosc2Frame}, its shape in the
 * frame's {@code "b2nd"} metalayer (or the older {@code "caterva"} one, of the same format). The layout is
 * c-blosc2's {@code README_B2ND_METALAYER.rst} and {@code b2nd.c} (c-blosc2 3.3.2):
 *
 * <pre>
 *   metalayer   msgpack [version, ndim, shape (int64 each), chunkshape (int32), blockshape (int32),
 *               dtype format, dtype (str32)]
 *   chunks      the array cut into chunkshape pieces, in C order over the grid of chunks; each chunk is the
 *               chunk shape rounded up to whole blocks (extchunkshape), cut into blockshape blocks, in C order
 *               over the grid of blocks, each block's items in C order; padding past the array or the chunk
 *               shape holds nothing
 * </pre>
 *
 * <p>The metalayer is checked as {@code b2nd_deserialize_meta} and {@code b2nd_from_schunk} check it, and the
 * sizes as {@code update_shape_struct} and {@code get_set_slice} do. A chunk is read block by block into the
 * array, the parts of each block inside both the chunk shape and the array kept ({@code get_set_slice}).
 *
 * <p>The array is bounded by the frame's {@code maxBytes}, and its chunks, which decode to their padded size,
 * by 4<sup>ndim</sup> times the array: padding at most doubles a dimension, and so does a chunk grid that
 * overhangs the array, when chunks and blocks are no larger than the array (as c-blosc2's writers make them).
 * Arrays padded beyond that, a block or a chunk far larger than the array, are refused as unsupported.
 */
public final class B2ndArray {

    /** c-blosc2's {@code B2ND_MAX_DIM}. */
    public static final int MAX_DIM = 16;

    private final Blosc2Frame frame;
    private final int ndim;
    private final long[] shape;
    private final int[] chunkShape;
    private final int[] blockShape;

    private B2ndArray(Blosc2Frame frame, int ndim, long[] shape, int[] chunkShape, int[] blockShape) {
        this.frame = frame;
        this.ndim = ndim;
        this.shape = shape;
        this.chunkShape = chunkShape;
        this.blockShape = blockShape;
    }

    /**
     * The b2nd array a frame holds.
     *
     * @param frame the frame
     * @return its array, or null if the frame has neither a {@code "b2nd"} nor a {@code "caterva"} metalayer
     * @throws CompressionFormatException if the metalayer is malformed or its shapes are inconsistent
     */
    public static B2ndArray of(Blosc2Frame frame) {
        byte[] meta = frame.metalayer("b2nd");
        if (meta == null) {
            meta = frame.metalayer("caterva"); // b2nd reads its predecessor's metalayer too
        }
        if (meta == null) {
            return null;
        }
        // b2nd_deserialize_meta_inline: the msgpack markers are skipped, not checked, as c-blosc2 does.
        int pos = 0;
        require(meta, pos, 1 + 1 + 1);
        pos += 2; // array marker and version
        int ndim = meta[pos++]; // a signed byte
        if (ndim < 0 || ndim > MAX_DIM) {
            throw new CompressionFormatException("b2nd array of " + ndim + " dimensions (at most " + MAX_DIM + ")");
        }
        long[] shape = new long[ndim];
        int[] chunkShape = new int[ndim];
        int[] blockShape = new int[ndim];
        require(meta, pos, 1);
        pos++;
        for (int i = 0; i < ndim; i++) {
            require(meta, pos, 9);
            shape[i] = Blosc2Frame.be64(meta, pos + 1);
            pos += 9;
        }
        require(meta, pos, 1);
        pos++;
        for (int i = 0; i < ndim; i++) {
            require(meta, pos, 5);
            chunkShape[i] = Blosc2Frame.be32(meta, pos + 1);
            pos += 5;
        }
        require(meta, pos, 1);
        pos++;
        for (int i = 0; i < ndim; i++) {
            require(meta, pos, 5);
            blockShape[i] = Blosc2Frame.be32(meta, pos + 1);
            pos += 5;
        }
        if (pos < meta.length) { // the dtype, which only needs to be well formed
            require(meta, pos, 1 + 1 + 4);
            if ((meta[pos + 1] & 0xff) != 0xdb) {
                throw new CompressionFormatException("b2nd dtype is not a msgpack str32");
            }
            int dtypeLength = Blosc2Frame.be32(meta, pos + 2);
            pos += 6;
            if (dtypeLength < 0) {
                throw new CompressionFormatException("b2nd dtype of negative length");
            }
            require(meta, pos, dtypeLength);
        }
        // validate_shape_chunkshape_blockshape, and b2nd_from_schunk's pairing of shapes.
        for (int i = 0; i < ndim; i++) {
            if (shape[i] < 0 || chunkShape[i] < 0 || blockShape[i] < 0) {
                throw new CompressionFormatException("b2nd shape, chunkshape, or blockshape is negative in dimension " + i);
            }
            if (blockShape[i] == 0 && chunkShape[i] != 0) {
                throw new CompressionFormatException("b2nd blockshape is zero where chunkshape is not, in dimension " + i);
            }
            if (shape[i] != 0 && chunkShape[i] == 0) {
                throw new CompressionFormatException("b2nd chunkshape is zero where shape is not, in dimension " + i);
            }
        }
        // update_shape_struct's overflow checks.
        long items = 1;
        long extItems = 1;
        long extChunkItems = 1;
        int chunkItems = 1;
        int blockItems = 1;
        try {
            for (int i = 0; i < ndim; i++) {
                long ext = shape[i];
                long extChunk = chunkShape[i];
                if (chunkShape[i] != 0) {
                    if (shape[i] % chunkShape[i] != 0) {
                        ext = Math.addExact(shape[i], chunkShape[i] - shape[i] % chunkShape[i]);
                    }
                    if (chunkShape[i] % blockShape[i] != 0) {
                        extChunk = (long) chunkShape[i] + blockShape[i] - chunkShape[i] % blockShape[i];
                    }
                } else {
                    ext = 0;
                }
                items = Math.multiplyExact(items, shape[i]);
                extItems = Math.multiplyExact(extItems, ext);
                extChunkItems = Math.multiplyExact(extChunkItems, extChunk);
                chunkItems = Math.multiplyExact(chunkItems, chunkShape[i]);
                blockItems = Math.multiplyExact(blockItems, blockShape[i]);
            }
        } catch (ArithmeticException e) {
            throw new CompressionFormatException("b2nd shape, chunkshape, or blockshape overflows");
        }
        return new B2ndArray(frame, ndim, shape, chunkShape, blockShape);
    }

    /** The number of dimensions. */
    public int ndim() {
        return ndim;
    }

    /** The array's shape. */
    public long[] shape() {
        return shape.clone();
    }

    /** The shape of each chunk. */
    public int[] chunkShape() {
        return chunkShape.clone();
    }

    /** The shape of each block within a chunk. */
    public int[] blockShape() {
        return blockShape.clone();
    }

    /**
     * The whole array, in C order, each item the frame's type size; as {@code b2nd_get_slice_cbuffer} reads it
     * from start to shape.
     *
     * @return the array's bytes
     * @throws CompressionFormatException     if the array exceeds the frame's {@code maxBytes}, a chunk is
     *                                        missing or malformed, or a chunk is not its padded chunk shape
     * @throws UnsupportedCompressionException if a chunk uses a codec or filter Falcon does not decode, or the
     *                                        chunks, padded, are more than 4<sup>ndim</sup> times the array
     */
    public byte[] read() {
        int typeSize = frame.typeSize();
        if (ndim == 0) { // one item, in the first chunk
            if (typeSize > frame.maxBytes()) {
                throw new CompressionFormatException("b2nd item of " + typeSize + " bytes is more than the "
                        + frame.maxBytes() + " bytes expected");
            }
            byte[] item = frame.chunk(0, typeSize);
            return Arrays.copyOf(item, typeSize);
        }
        long items = 1;
        for (long n : shape) {
            items *= n; // checked in of()
        }
        if (items == 0) {
            return new byte[0];
        }
        long outBytes = items * typeSize;
        if (items > Integer.MAX_VALUE || outBytes > frame.maxBytes()) {
            throw new CompressionFormatException("b2nd array of " + Arrays.toString(shape) + " " + typeSize
                    + "-byte items is more than the " + frame.maxBytes() + " bytes expected");
        }
        long[] extChunk = new long[ndim];
        long[] chunksInArray = new long[ndim];
        long[] blocksInChunk = new long[ndim];
        long extChunkItems = 1;
        long chunks = 1;
        long blocks = 1;
        for (int i = 0; i < ndim; i++) {
            extChunk[i] = chunkShape[i] % blockShape[i] == 0 ? chunkShape[i]
                    : (long) chunkShape[i] + blockShape[i] - chunkShape[i] % blockShape[i];
            long ext = shape[i] % chunkShape[i] == 0 ? shape[i] : shape[i] + chunkShape[i] - shape[i] % chunkShape[i];
            chunksInArray[i] = ext / chunkShape[i];
            blocksInChunk[i] = extChunk[i] / blockShape[i];
            extChunkItems *= extChunk[i];
            chunks *= chunksInArray[i];
            blocks *= blocksInChunk[i];
        }
        long extChunkBytes = extChunkItems * typeSize; // no overflow: each factor is below 2^32
        if (extChunkItems > Integer.MAX_VALUE || extChunkBytes > Integer.MAX_VALUE) {
            throw new CompressionFormatException("b2nd chunk of " + extChunkItems + " items overflows a Blosc2 chunk");
        }
        if (extChunkBytes > Integer.MAX_VALUE - 8L) {
            throw new UnsupportedCompressionException("b2nd chunk of " + extChunkBytes + " bytes is too large");
        }
        if (chunks > frame.chunkCount()) {
            throw new CompressionFormatException("b2nd array of " + chunks + " chunks is in a frame of "
                    + frame.chunkCount());
        }
        // The chunks decode to their padded size; padding a dimension at most quadruples it (chunks and blocks
        // no larger than the array), so more than 4^ndim times the array is not read.
        long budget = ndim >= 31 || outBytes > (Long.MAX_VALUE >> (2 * ndim)) ? Long.MAX_VALUE : outBytes << (2 * ndim);
        if (chunks > budget / extChunkBytes) {
            throw new UnsupportedCompressionException("b2nd array of " + outBytes + " bytes in " + chunks
                    + " chunks of " + extChunkBytes + " padded bytes, more than 4^" + ndim + " times the array, is not read");
        }
        int blockBytes = (int) (extChunkBytes / blocks);
        byte[] out = new byte[(int) outBytes];
        long[] arrayStrides = strides(shape);
        long[] chunkIndex = new long[ndim];
        long[] blockIndex = new long[ndim];
        long[] start = new long[ndim];
        long[] extent = new long[ndim];
        for (int c = 0; c < chunks; c++) {
            unravel(c, chunksInArray, chunkIndex);
            byte[] data = frame.chunk(c, (int) extChunkBytes);
            if (data.length != extChunkBytes) {
                throw new CompressionFormatException("b2nd chunk " + c + " holds " + data.length + " bytes, not its "
                        + extChunkBytes);
            }
            for (int b = 0; b < blocks; b++) {
                unravel(b, blocksInChunk, blockIndex);
                boolean empty = false;
                for (int i = 0; i < ndim; i++) {
                    long chunkStart = chunkIndex[i] * chunkShape[i];
                    long chunkStop = Math.min(chunkStart + chunkShape[i], shape[i]);
                    long blockStart = Math.min(chunkStart + blockIndex[i] * blockShape[i], chunkStop);
                    long blockStop = Math.min(blockStart + blockShape[i], chunkStop);
                    start[i] = blockStart;
                    extent[i] = blockStop - blockStart;
                    empty |= extent[i] == 0;
                }
                if (!empty) {
                    copyBlock(data, (long) b * blockBytes, typeSize, out, arrayStrides, start, extent);
                }
            }
        }
        return out;
    }

    /**
     * Copies the {@code extent} corner of one block (its items in C order over the block shape) into the array
     * at {@code start}, a run along the last dimension at a time.
     */
    private void copyBlock(byte[] block, long blockOffset, int typeSize, byte[] out, long[] arrayStrides,
                           long[] start, long[] extent) {
        long[] position = new long[ndim];
        int run = (int) (extent[ndim - 1] * typeSize);
        while (true) {
            long src = 0;
            long dst = 0;
            for (int i = 0; i < ndim; i++) {
                src = src * blockShape[i] + position[i];
                dst += (start[i] + position[i]) * arrayStrides[i];
            }
            System.arraycopy(block, (int) (blockOffset + src * typeSize), out, (int) (dst * typeSize), run);
            int i = ndim - 2;
            while (i >= 0 && ++position[i] == extent[i]) {
                position[i] = 0;
                i--;
            }
            if (i < 0) {
                return;
            }
        }
    }

    /** Item strides of a C-order array of {@code dims}. */
    private static long[] strides(long[] dims) {
        long[] strides = new long[dims.length];
        long stride = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            strides[i] = stride;
            stride *= dims[i];
        }
        return strides;
    }

    /** The C-order coordinates of index {@code n} in a grid of {@code dims}. */
    private static void unravel(long n, long[] dims, long[] coordinates) {
        for (int i = dims.length - 1; i >= 0; i--) {
            coordinates[i] = n % dims[i];
            n /= dims[i];
        }
    }

    private static void require(byte[] meta, int pos, int bytes) {
        if (pos > meta.length || meta.length - pos < bytes) {
            throw new CompressionFormatException("b2nd metalayer is truncated");
        }
    }
}
