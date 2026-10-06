package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.codec.VlenCodec;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.Arrays;

/**
 * The variable-length analogue of {@link ChunkAssembler} / {@link ChunkWriter}: reads and writes a
 * hyperslab of a {@code string} or {@code variable_length_bytes} array as a C-order {@code String[]} or
 * {@code byte[][]}, touching only the chunks that overlap the selection.
 *
 * <p>Such chunks decode to an {@code Object[]} rather than a flat byte buffer, through the pipeline's
 * variable-length path ({@link ChunkPipeline#decodeVlenChunk}/{@link ChunkPipeline#encodeVlen}):
 * {@code vlen-utf8} or {@code vlen-bytes}, or a shard of such sub-chunks, with an optional {@code transpose}
 * before either. Reading part of a shard fetches only the sub-chunks it needs; a write re-encodes the whole
 * chunk.
 */
public final class VlenChunks {

    private VlenChunks() {
    }

    /**
     * Reads the selection {@code [offset, offset+selShape)} as a flat C-order array: a {@code String[]} for
     * the {@code string} data type, a {@code byte[][]} for {@code variable_length_bytes}. Every
     * {@code byte[]} is the caller's own.
     */
    public static Object[] read(Store store, String arrayPath, ArrayMetadata meta,
                                long[] offset, long[] selShape) {
        ChunkAssembler.checkSelection(meta, offset, selShape);

        RegularChunkGrid grid = meta.grid();
        int rank = grid.rank();
        long[] chunkShape = grid.chunkShape();
        ChunkPipeline pipeline = meta.pipeline();
        VlenCodec vlen = pipeline.vlenCodec();

        long total = RegularChunkGrid.elementCount(selShape); // within the array, so it fits a long
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "selection of " + total + " elements is too large to read into a single array");
        }
        Object[] out = vlen.newArray((int) total);
        if (total == 0) {
            return out;
        }

        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        Object fill = vlen.fill(meta.fillValue());
        int[] chunkShapeInt = ChunkAssembler.toInt(chunkShape);

        long[] selEnd = new long[rank];
        long[] firstChunk = new long[rank];
        long[] lastChunk = new long[rank];
        for (int i = 0; i < rank; i++) {
            selEnd[i] = offset[i] + selShape[i];
            firstChunk[i] = offset[i] / chunkShape[i];
            lastChunk[i] = (selEnd[i] - 1) / chunkShape[i];
        }

        long[] coord = firstChunk.clone();
        int[] regionOrigin = new int[rank];
        int[] regionShape = new int[rank];
        while (true) {
            for (int i = 0; i < rank; i++) {
                long chunkOrigin = coord[i] * chunkShape[i];
                long lo = Math.max(offset[i], chunkOrigin);
                long hi = ChunkAssembler.overlapEnd(chunkOrigin, chunkShape[i], selEnd[i]);
                regionOrigin[i] = (int) (lo - chunkOrigin);
                regionShape[i] = (int) (hi - lo);
            }
            String key = ChunkAssembler.chunkKey(arrayPath, encoding, coord);
            Object[] chunk = pipeline.decodeVlenChunk(new StoreChunkBytes(store, key, null), fill,
                    regionOrigin, regionShape);
            if (chunk == null) {
                chunk = fillChunk(vlen, chunkShapeInt, fill);
            }
            copyIntersection(out, selShape, offset, selEnd, coord, chunkShape, chunk);

            int d = rank - 1;
            for (; d >= 0; d--) {
                if (++coord[d] <= lastChunk[d]) {
                    break;
                }
                coord[d] = firstChunk[d];
            }
            if (d < 0) {
                break;
            }
        }
        return vlen.detach(out, fill);
    }

    /**
     * Writes {@code elements} (the selection's elements, C order, a {@code String[]} or {@code byte[][]} to
     * match the data type) into {@code [offset, offset+selShape)}. A chunk left holding only the fill value
     * is deleted, unless {@code writeEmptyChunks}, which stores it.
     */
    public static void write(Store store, String arrayPath, ArrayMetadata meta, long[] offset, long[] selShape,
                             Object[] elements, boolean writeEmptyChunks) {
        ChunkAssembler.checkSelection(meta, offset, selShape);
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }

        RegularChunkGrid grid = meta.grid();
        int rank = grid.rank();
        long[] chunkShape = grid.chunkShape();
        long[] arrayShape = grid.arrayShape();

        long total = RegularChunkGrid.elementCount(selShape);
        if (elements.length != total) {
            throw new IllegalArgumentException(
                    "selection holds " + total + " elements but got " + elements.length);
        }
        if (total == 0) {
            return;
        }

        ChunkPipeline pipeline = meta.pipeline();
        VlenCodec vlen = pipeline.vlenCodec();
        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        Object fill = vlen.fill(meta.fillValue());
        int[] chunkShapeInt = ChunkAssembler.toInt(chunkShape);

        long[] selEnd = new long[rank];
        long[] firstChunk = new long[rank];
        long[] lastChunk = new long[rank];
        for (int i = 0; i < rank; i++) {
            selEnd[i] = offset[i] + selShape[i];
            firstChunk[i] = offset[i] / chunkShape[i];
            lastChunk[i] = (selEnd[i] - 1) / chunkShape[i];
        }

        long[] coord = firstChunk.clone();
        while (true) {
            writeChunk(store, arrayPath, pipeline, vlen, encoding, coord, offset, selShape, selEnd, chunkShape,
                    arrayShape, chunkShapeInt, elements, fill, writeEmptyChunks);
            int d = rank - 1;
            for (; d >= 0; d--) {
                if (++coord[d] <= lastChunk[d]) {
                    break;
                }
                coord[d] = firstChunk[d];
            }
            if (d < 0) {
                return;
            }
        }
    }

    private static void writeChunk(Store store, String arrayPath, ChunkPipeline pipeline, VlenCodec vlen,
                                   ChunkKeyEncoding encoding, long[] coord, long[] selOffset, long[] selShape,
                                   long[] selEnd, long[] chunkShape, long[] arrayShape, int[] chunkShapeInt,
                                   Object[] elements, Object fill, boolean writeEmptyChunks) {
        int rank = chunkShape.length;
        long[] srcOrigin = new long[rank];
        long[] dstOrigin = new long[rank];
        long[] block = new long[rank];
        boolean coversChunk = true; // every element of the chunk inside the array is written
        for (int i = 0; i < rank; i++) {
            long chunkOrigin = coord[i] * chunkShape[i];
            long lo = Math.max(selOffset[i], chunkOrigin);
            long hi = ChunkAssembler.overlapEnd(chunkOrigin, chunkShape[i], selEnd[i]);
            srcOrigin[i] = lo - selOffset[i];
            dstOrigin[i] = lo - chunkOrigin;
            block[i] = hi - lo;
            coversChunk &= block[i] == Math.min(chunkShape[i], arrayShape[i] - chunkOrigin);
        }
        String key = ChunkAssembler.chunkKey(arrayPath, encoding, coord);

        synchronized (ChunkLocks.of(store, key)) {
            Object[] chunk = null;
            if (!coversChunk) {
                chunk = pipeline.decodeVlenChunk(new StoreChunkBytes(store, key, null), fill,
                        new int[rank], chunkShapeInt);
            }
            if (chunk == null) {
                chunk = fillChunk(vlen, chunkShapeInt, fill); // also the part of an edge chunk past the array
            }
            Blocks.copyObjects(elements, selShape, srcOrigin, chunk, chunkShape, dstOrigin, block);

            if (!writeEmptyChunks && vlen.isAllFill(chunk, fill)) {
                store.delete(key); // an all-fill chunk is represented by its absence
            } else {
                store.set(key, pipeline.encodeVlen(chunk, fill, writeEmptyChunks));
            }
        }
    }

    private static void copyIntersection(Object[] out, long[] selShape, long[] selOffset, long[] selEnd,
                                         long[] coord, long[] chunkShape, Object[] chunk) {
        int rank = selShape.length;
        long[] srcOrigin = new long[rank];
        long[] dstOrigin = new long[rank];
        long[] block = new long[rank];
        for (int i = 0; i < rank; i++) {
            long chunkOrigin = coord[i] * chunkShape[i];
            long lo = Math.max(selOffset[i], chunkOrigin);
            long hi = ChunkAssembler.overlapEnd(chunkOrigin, chunkShape[i], selEnd[i]);
            srcOrigin[i] = lo - chunkOrigin;
            dstOrigin[i] = lo - selOffset[i];
            block[i] = hi - lo;
        }
        Blocks.copyObjects(chunk, chunkShape, srcOrigin, out, selShape, dstOrigin, block);
    }

    private static Object[] fillChunk(VlenCodec vlen, int[] chunkShape, Object fill) {
        int count = 1;
        for (int d : chunkShape) {
            count *= d;
        }
        Object[] chunk = vlen.newArray(count);
        Arrays.fill(chunk, fill);
        return chunk;
    }
}
