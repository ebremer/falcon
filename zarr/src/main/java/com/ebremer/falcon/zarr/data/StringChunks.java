package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The variable-length {@code string} analogue of {@link ChunkAssembler} / {@link ChunkWriter}: reads and
 * writes a hyperslab of a string array as a C-order {@code String[]}, touching only the chunks that overlap
 * the selection.
 *
 * <p>String chunks decode to {@code String[]} rather than a flat byte buffer, so they use the
 * {@code vlen-utf8} array&rarr;bytes path ({@link ChunkPipeline#decodeStrings}/{@link
 * ChunkPipeline#encodeStrings}) and the object-array block copy in {@link Blocks#copyObjects}. Sharding and
 * transpose are rejected for string arrays at pipeline-build time, so a chunk is always stored whole and
 * there is no partial-region decode.
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

        long total = 1;
        for (long s : selShape) {
            total *= s;
        }
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
        int chunkElements = elementCount(chunkShape);

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
            String[] chunk = readChunk(store, arrayPath, pipeline, encoding, coord, fill, chunkElements);
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

        long total = 1;
        for (long s : selShape) {
            total *= s;
        }
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
        int chunkElements = elementCount(chunkShape);

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
                    elements, fill, chunkElements);
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

    private static String[] readChunk(Store store, String arrayPath, ChunkPipeline pipeline,
                                      ChunkKeyEncoding encoding, long[] coord, String fill, int chunkElements) {
        String[] chunk = decodeChunkOrNull(store, arrayPath, pipeline, encoding, coord, chunkElements);
        if (chunk != null) {
            return chunk;
        }
        String[] fillChunk = new String[chunkElements];
        Arrays.fill(fillChunk, fill);
        return fillChunk;
    }

    /** Decodes the whole chunk at {@code coord}, or {@code null} if it is absent from the store. */
    private static String[] decodeChunkOrNull(Store store, String arrayPath, ChunkPipeline pipeline,
                                              ChunkKeyEncoding encoding, long[] coord, int chunkElements) {
        String relative = encoding.encode(coord);
        String key = arrayPath.isEmpty() ? relative : arrayPath + "/" + relative;
        Optional<byte[]> stored = store.get(key);
        if (stored.isEmpty()) {
            return null;
        }
        return pipeline.decodeStrings(stored.get(), chunkElements);
    }

    private static void writeChunk(Store store, String arrayPath, ChunkPipeline pipeline,
                                   ChunkKeyEncoding encoding, long[] coord, long[] selOffset, long[] selShape,
                                   long[] selEnd, long[] chunkShape, String[] elements, String fill,
                                   int chunkElements) {
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

        String[] chunk;
        if (coversWholeChunk) {
            chunk = new String[chunkElements]; // fully overwritten below
        } else {
            String[] existing = decodeChunkOrNull(store, arrayPath, pipeline, encoding, coord, chunkElements);
            if (existing != null) {
                chunk = existing;
            } else {
                chunk = new String[chunkElements];
                Arrays.fill(chunk, fill);
            }
        }
        Blocks.copyObjects(elements, selShape, srcOrigin, chunk, chunkShape, dstOrigin, block);

        if (isAllFill(chunk, fill)) {
            store.delete(key); // an all-fill chunk is represented by its absence
            return;
        }
        store.set(key, pipeline.encodeStrings(chunk));
    }

    private static void copyIntersection(String[] out, long[] selShape, long[] selOffset, long[] selEnd,
                                         long[] coord, long[] chunkShape, String[] chunk) {
        int rank = selShape.length;
        long[] chunkOrigin = new long[rank];
        long[] srcOrigin = new long[rank];
        long[] dstOrigin = new long[rank];
        long[] block = new long[rank];
        for (int i = 0; i < rank; i++) {
            chunkOrigin[i] = coord[i] * chunkShape[i];
            long lo = Math.max(selOffset[i], chunkOrigin[i]);
            long hi = Math.min(selEnd[i], chunkOrigin[i] + chunkShape[i]);
            srcOrigin[i] = lo - chunkOrigin[i];
            dstOrigin[i] = lo - selOffset[i];
            block[i] = hi - lo;
        }
        Blocks.copyObjects(chunk, chunkShape, srcOrigin, out, selShape, dstOrigin, block);
    }

    private static boolean isAllFill(String[] chunk, String fill) {
        for (String s : chunk) {
            if (!Objects.equals(s == null ? fill : s, fill)) {
                return false;
            }
        }
        return true;
    }

    private static String fillString(ArrayMetadata meta) {
        return meta.fillValue().asString();
    }

    private static int elementCount(long[] shape) {
        long count = 1;
        for (long s : shape) {
            count *= s;
        }
        return Math.toIntExact(count);
    }
}
