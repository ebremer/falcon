package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.Arrays;
import java.util.Objects;

/**
 * The variable-length {@code string} analogue of {@link ChunkAssembler} / {@link ChunkWriter}: reads and
 * writes a hyperslab of a string array as a C-order {@code String[]}, touching only the chunks that overlap
 * the selection.
 *
 * <p>String chunks decode to {@code String[]} rather than a flat byte buffer, through the pipeline's string
 * path ({@link ChunkPipeline#decodeStringChunk}/{@link ChunkPipeline#encodeStrings}): {@code vlen-utf8},
 * or a shard of {@code vlen-utf8} sub-chunks, with an optional {@code transpose} before either. Reading part
 * of a shard fetches only the sub-chunks it needs; a write re-encodes the whole chunk.
 */
public final class StringChunks {

    private StringChunks() {
    }

    /** Reads the selection {@code [offset, offset+selShape)} as a flat C-order {@code String[]}. */
    public static String[] read(Store store, String arrayPath, ArrayMetadata meta,
                                long[] offset, long[] selShape) {
        ChunkAssembler.checkSelection(meta, offset, selShape);

        RegularChunkGrid grid = meta.grid();
        int rank = grid.rank();
        long[] chunkShape = grid.chunkShape();

        long total = RegularChunkGrid.elementCount(selShape); // within the array, so it fits a long
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "selection of " + total + " elements is too large to read into a single array");
        }
        String[] out = new String[(int) total];
        if (total == 0) {
            return out;
        }

        ChunkPipeline pipeline = meta.pipeline();
        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        String fill = fillString(meta);
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
            String[] chunk = pipeline.decodeStringChunk(new StoreChunkBytes(store, key, null), fill,
                    regionOrigin, regionShape);
            if (chunk == null) {
                chunk = fillChunk(chunkShapeInt, fill);
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
        return out;
    }

    /** Writes {@code elements} (the selection's strings, C order) into {@code [offset, offset+selShape)}. */
    public static void write(Store store, String arrayPath, ArrayMetadata meta,
                             long[] offset, long[] selShape, String[] elements) {
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
        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        String fill = fillString(meta);
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
            writeChunk(store, arrayPath, pipeline, encoding, coord, offset, selShape, selEnd, chunkShape,
                    arrayShape, chunkShapeInt, elements, fill);
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

    private static void writeChunk(Store store, String arrayPath, ChunkPipeline pipeline,
                                   ChunkKeyEncoding encoding, long[] coord, long[] selOffset, long[] selShape,
                                   long[] selEnd, long[] chunkShape, long[] arrayShape, int[] chunkShapeInt,
                                   String[] elements, String fill) {
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
            String[] chunk = null;
            if (!coversChunk) {
                chunk = pipeline.decodeStringChunk(new StoreChunkBytes(store, key, null), fill,
                        new int[rank], chunkShapeInt);
            }
            if (chunk == null) {
                chunk = fillChunk(chunkShapeInt, fill); // also the part of an edge chunk past the array
            }
            Blocks.copyObjects(elements, selShape, srcOrigin, chunk, chunkShape, dstOrigin, block);

            if (isAllFill(chunk, fill)) {
                store.delete(key); // an all-fill chunk is represented by its absence
            } else {
                store.set(key, pipeline.encodeStrings(chunk, fill));
            }
        }
    }

    private static void copyIntersection(String[] out, long[] selShape, long[] selOffset, long[] selEnd,
                                         long[] coord, long[] chunkShape, String[] chunk) {
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

    private static String[] fillChunk(int[] chunkShape, String fill) {
        int count = 1;
        for (int d : chunkShape) {
            count *= d;
        }
        String[] chunk = new String[count];
        Arrays.fill(chunk, fill);
        return chunk;
    }

    /** Whether every element equals {@code fill}; a {@code null} counts as the empty string it is stored as. */
    private static boolean isAllFill(String[] chunk, String fill) {
        for (String s : chunk) {
            if (!Objects.equals(s == null ? "" : s, fill)) {
                return false;
            }
        }
        return true;
    }

    private static String fillString(ArrayMetadata meta) {
        return meta.fillValue().asString();
    }
}
