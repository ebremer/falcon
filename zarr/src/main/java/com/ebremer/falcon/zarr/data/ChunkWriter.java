package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Writes a hyperslab into a chunked array, touching only the chunks that overlap it.
 *
 * <p>Chunks are stored whole, so a chunk the selection covers only partially is read back (decoded, or
 * fill if absent), updated in place, and re-encoded &mdash; a read-modify-write. A chunk the selection
 * covers completely is built directly from the input. After the update, a chunk holding nothing but the
 * fill value is <em>deleted</em> rather than stored, which is how Zarr represents empty chunks.
 */
public final class ChunkWriter {

    private ChunkWriter() {
    }

    /** Writes {@code elements} (the selection's elements, C order) into {@code [offset, offset+selShape)}. */
    public static void write(Store store, String arrayPath, ArrayMetadata meta,
                             long[] offset, long[] selShape, byte[] elements) {
        ChunkAssembler.checkSelection(meta, offset, selShape);
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }

        RegularChunkGrid grid = meta.grid();
        int rank = grid.rank();
        long[] chunkShape = grid.chunkShape();
        DataType dataType = meta.dataType();
        int elementSize = dataType.byteCount();

        long total = 1;
        for (long s : selShape) {
            total *= s;
        }
        long expected = total * elementSize;
        if (elements.length != expected) {
            throw new IllegalArgumentException(
                    "selection holds " + total + " elements (" + expected + " bytes) but got " + elements.length);
        }
        if (total == 0) {
            return;
        }

        ChunkPipeline pipeline = meta.pipeline();
        ByteOrder order = pipeline.elementOrder();
        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        byte[] fillElement = meta.fillValueBytes(order);

        int chunkElements = 1;
        for (long c : chunkShape) {
            chunkElements *= (int) c;
        }
        int chunkBytes = chunkElements * elementSize;
        byte[] emptyChunk = new byte[chunkBytes];
        ChunkAssembler.tile(emptyChunk, fillElement);

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
            writeChunk(store, arrayPath, meta, pipeline, encoding, coord, offset, selShape, selEnd,
                    chunkShape, elements, elementSize, fillElement, emptyChunk, chunkBytes);
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

    private static void writeChunk(Store store, String arrayPath, ArrayMetadata meta, ChunkPipeline pipeline,
                                   ChunkKeyEncoding encoding, long[] coord, long[] selOffset, long[] selShape,
                                   long[] selEnd, long[] chunkShape, byte[] elements, int elementSize,
                                   byte[] fillElement, byte[] emptyChunk, int chunkBytes) {
        int rank = chunkShape.length;
        long[] chunkOrigin = new long[rank];
        long[] srcOrigin = new long[rank];
        long[] dstOrigin = new long[rank];
        long[] block = new long[rank];
        boolean coversWholeChunk = true;
        for (int i = 0; i < rank; i++) {
            chunkOrigin[i] = coord[i] * chunkShape[i];
            long lo = Math.max(selOffset[i], chunkOrigin[i]);
            long hi = Math.min(selEnd[i], chunkOrigin[i] + chunkShape[i]);
            srcOrigin[i] = lo - selOffset[i];
            dstOrigin[i] = lo - chunkOrigin[i];
            block[i] = hi - lo;
            if (block[i] != chunkShape[i]) {
                coversWholeChunk = false;
            }
        }

        String relative = encoding.encode(coord);
        String key = arrayPath.isEmpty() ? relative : arrayPath + "/" + relative;

        byte[] chunk;
        if (coversWholeChunk) {
            chunk = new byte[chunkBytes]; // fully overwritten below
        } else {
            // Partial update: start from what is already stored, or from fill.
            byte[] existing = ChunkAssembler.readChunkOrNull(store, key, pipeline, fillElement, chunkShape);
            chunk = existing != null ? existing.clone() : emptyChunk.clone();
        }
        Blocks.copy(elements, selShape, srcOrigin, chunk, chunkShape, dstOrigin, block, elementSize);

        if (Arrays.equals(chunk, emptyChunk)) {
            store.delete(key); // an all-fill chunk is represented by its absence
            return;
        }
        store.set(key, pipeline.encode(chunk, fillElement));
    }
}
