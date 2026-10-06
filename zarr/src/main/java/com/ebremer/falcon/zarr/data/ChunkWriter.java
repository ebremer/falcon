package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.chunk.ChunkGrid;
import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Writes a hyperslab into a chunked array, touching only the chunks that overlap it.
 *
 * <p>A chunk the selection covers completely, counting only the part of an edge chunk inside the array,
 * is built directly from the input. A chunk it covers in part is read back (decoded, or fill if absent),
 * updated, and re-encoded: a read-modify-write. A shard covered in part re-encodes only the sub-chunks
 * the selection touches and keeps the stored bytes of the rest. After the update, a chunk holding nothing
 * but the fill value is <em>deleted</em> rather than stored, which is how Zarr represents empty chunks;
 * with {@code writeEmptyChunks} (zarr-python's {@code write_empty_chunks}, F7) it is stored like any other,
 * and so is each sub-chunk of a shard that the write touches.
 *
 * <p>Writes to the same chunk through the same store object take turns ({@link ChunkLocks}), so two
 * threads updating different parts of one chunk or shard do not lose either update.
 */
public final class ChunkWriter {

    private ChunkWriter() {
    }

    /**
     * Writes {@code elements} (the selection's elements, C order) into {@code [offset, offset+selShape)}.
     * A chunk left holding only the fill value is deleted, unless {@code writeEmptyChunks}.
     */
    public static void write(Store store, String arrayPath, ArrayMetadata meta, ChunkCache cache,
                             long[] offset, long[] selShape, byte[] elements, boolean writeEmptyChunks) {
        ChunkAssembler.checkSelection(meta, offset, selShape);
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }

        ChunkGrid grid = meta.grid();
        int rank = grid.rank();
        long[] arrayShape = grid.arrayShape();
        DataType dataType = meta.dataType();
        int elementSize = dataType.byteCount();

        long total = ChunkGrid.elementCount(selShape); // within the array, so it fits a long
        if (total > Integer.MAX_VALUE / elementSize || elements.length != total * elementSize) {
            throw new IllegalArgumentException(
                    "selection holds " + total + " elements of " + elementSize + " bytes but got " + elements.length
                            + " bytes");
        }
        if (total == 0) {
            return;
        }

        ByteOrder order = meta.pipeline().elementOrder(); // checks that the first chunk fits one buffer
        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        byte[] fillElement = meta.fillValueBytes(order);

        long[] selEnd = new long[rank];
        for (int i = 0; i < rank; i++) {
            selEnd[i] = offset[i] + selShape[i];
        }

        long[][] range = ChunkAssembler.chunkRange(grid, offset, selEnd);
        long[] coord = range[0].clone();
        long[] emptyShape = null; // the shape emptyChunk was made for; a regular grid makes it once
        byte[] emptyChunk = null;
        do {
            ChunkPipeline pipeline = meta.chunkPipeline(coord); // checks that the chunk fits one buffer
            long[] chunkShape = ChunkAssembler.chunkShape(grid, coord);
            if (!Arrays.equals(chunkShape, emptyShape)) {
                emptyShape = chunkShape;
                emptyChunk = new byte[(int) ChunkGrid.elementCount(chunkShape) * elementSize];
                ChunkAssembler.tile(emptyChunk, fillElement);
            }
            long[] chunkOrigin = new long[rank];
            for (int i = 0; i < rank; i++) {
                chunkOrigin[i] = grid.chunkStart(i, coord[i]);
            }
            writeChunk(store, arrayPath, pipeline, encoding, cache, coord, chunkOrigin, offset, selShape, selEnd,
                    chunkShape, arrayShape, elements, elementSize, fillElement, emptyChunk, writeEmptyChunks);
        } while (ChunkAssembler.next(coord, range[0], range[1]));
    }

    private static void writeChunk(Store store, String arrayPath, ChunkPipeline pipeline,
                                   ChunkKeyEncoding encoding, ChunkCache cache, long[] coord, long[] chunkOrigins,
                                   long[] selOffset, long[] selShape, long[] selEnd, long[] chunkShape,
                                   long[] arrayShape, byte[] elements, int elementSize, byte[] fillElement,
                                   byte[] emptyChunk, boolean writeEmptyChunks) {
        int rank = chunkShape.length;
        long[] srcOrigin = new long[rank];
        long[] dstOrigin = new long[rank];
        long[] block = new long[rank];
        boolean coversChunk = true; // every element of the chunk inside the array is written
        boolean edge = false;       // the chunk reaches past the array
        for (int i = 0; i < rank; i++) {
            long chunkOrigin = chunkOrigins[i];
            long lo = Math.max(selOffset[i], chunkOrigin);
            long hi = ChunkAssembler.overlapEnd(chunkOrigin, chunkShape[i], selEnd[i]);
            srcOrigin[i] = lo - selOffset[i];
            dstOrigin[i] = lo - chunkOrigin;
            block[i] = hi - lo;
            long inBounds = Math.min(chunkShape[i], arrayShape[i] - chunkOrigin);
            edge |= inBounds != chunkShape[i];
            coversChunk &= block[i] == inBounds;
        }
        String key = ChunkAssembler.chunkKey(arrayPath, encoding, coord);

        synchronized (ChunkLocks.of(store, key)) {
            byte[] stored; // what to store, or null to delete the chunk (all fill)
            if (coversChunk) {
                // Nothing in the array is left to keep: the part of an edge chunk past the array is fill.
                byte[] chunk = edge ? emptyChunk.clone() : new byte[emptyChunk.length];
                Blocks.copy(elements, selShape, srcOrigin, chunk, chunkShape, dstOrigin, block, elementSize);
                stored = !writeEmptyChunks && Arrays.equals(chunk, emptyChunk) ? null
                        : pipeline.encode(chunk, fillElement, writeEmptyChunks);
            } else if (pipeline.canUpdateShard()) {
                byte[] chunk = new byte[emptyChunk.length]; // read only inside the written region
                Blocks.copy(elements, selShape, srcOrigin, chunk, chunkShape, dstOrigin, block, elementSize);
                stored = pipeline.updateShard(store.get(key).orElse(null), chunk, fillElement,
                        ChunkAssembler.toInt(dstOrigin), ChunkAssembler.toInt(block), writeEmptyChunks);
            } else {
                // Partial update: start from what is already stored, or from fill.
                byte[] existing = ChunkAssembler.readChunkOrNull(store, key, pipeline, fillElement, chunkShape);
                byte[] chunk = existing != null ? existing : emptyChunk.clone();
                Blocks.copy(elements, selShape, srcOrigin, chunk, chunkShape, dstOrigin, block, elementSize);
                stored = !writeEmptyChunks && Arrays.equals(chunk, emptyChunk) ? null
                        : pipeline.encode(chunk, fillElement, writeEmptyChunks);
            }
            if (stored == null) {
                store.delete(key); // an all-fill chunk is represented by its absence
            } else {
                store.set(key, stored);
            }
            if (cache != null) {
                // After the store changes, so a read racing this write cannot cache the old bytes afterwards.
                cache.invalidate(key);
            }
        }
    }
}
