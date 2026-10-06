package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.codec.ChunkBytes;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads a hyperslab from a chunked array by touching only the chunks that overlap the selection.
 *
 * <p>For each overlapping chunk it fetches and decodes the stored bytes (an absent chunk becomes the fill
 * value) and copies the chunk's intersection with the selection into the output buffer. The output is a
 * flat buffer of the selected elements in C (row-major) order, each primitive in the pipeline's
 * {@linkplain ChunkPipeline#elementOrder() element order}.
 */
public final class ChunkAssembler {

    private ChunkAssembler() {
    }

    /**
     * Validates a selection against {@code meta}'s shape.
     *
     * @throws IllegalArgumentException  if the selection rank is wrong
     * @throws IndexOutOfBoundsException if the selection is negative or extends past the array
     */
    public static void checkSelection(ArrayMetadata meta, long[] offset, long[] shape) {
        long[] arrayShape = meta.shape();
        int rank = arrayShape.length;
        if (offset.length != rank || shape.length != rank) {
            throw new IllegalArgumentException(
                    "selection rank must be " + rank + ", got offset " + offset.length + " / shape " + shape.length);
        }
        for (int i = 0; i < rank; i++) {
            // Compared without forming offset + shape, which can overflow.
            if (offset[i] < 0 || shape[i] < 0 || offset[i] > arrayShape[i] || shape[i] > arrayShape[i] - offset[i]) {
                throw new IndexOutOfBoundsException("selection of " + shape[i] + " at offset " + offset[i]
                        + " is out of bounds [0," + arrayShape[i] + ") in dimension " + i);
            }
        }
    }

    /**
     * The size in bytes of {@code count} elements of {@code elementSize} bytes, which must fit one Java array.
     *
     * @throws ZarrException if it does not
     */
    static int bufferSize(long count, int elementSize, String what) {
        if (count > Integer.MAX_VALUE / elementSize) {
            throw new ZarrException(what + " of " + count + " elements is too large for a single array");
        }
        return (int) count * elementSize;
    }

    /** The end of a chunk's part of a selection: {@code min(selEnd, origin + extent)}, without overflow. */
    static long overlapEnd(long chunkOrigin, long chunkExtent, long selEnd) {
        return chunkOrigin + Math.min(chunkExtent, selEnd - chunkOrigin);
    }

    /** Reads the selection {@code [offset, offset+selShape)} as a flat element buffer. */
    public static byte[] assemble(Store store, String arrayPath, ArrayMetadata meta, ChunkCache cache,
                                  long[] offset, long[] selShape) {
        checkSelection(meta, offset, selShape);

        RegularChunkGrid grid = meta.grid();
        int rank = grid.rank();
        long[] chunkShapeL = grid.chunkShape();
        DataType dataType = meta.dataType();
        int elementSize = dataType.byteCount();

        long total = RegularChunkGrid.elementCount(selShape); // within the array, so it fits a long
        byte[] out = new byte[bufferSize(total, elementSize, "selection")];
        if (total == 0) {
            return out;
        }

        ChunkPipeline pipeline = meta.pipeline();
        ByteOrder order = pipeline.elementOrder();
        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        byte[] fillElement = meta.fillValueBytes(order);
        int[] chunkShape = toInt(chunkShapeL);
        long[] selEnd = new long[rank];
        for (int i = 0; i < rank; i++) {
            selEnd[i] = offset[i] + selShape[i];
        }

        // The inclusive range of chunk coordinates the selection overlaps in each dimension.
        long[] firstChunk = new long[rank];
        long[] lastChunk = new long[rank];
        for (int i = 0; i < rank; i++) {
            firstChunk[i] = offset[i] / chunkShapeL[i];
            lastChunk[i] = (selEnd[i] - 1) / chunkShapeL[i];
        }

        long[] coord = firstChunk.clone();
        int[] regionOrigin = new int[rank];
        int[] regionShape = new int[rank];
        while (true) {
            // The part of this chunk the selection needs, in chunk-local coordinates.
            for (int i = 0; i < rank; i++) {
                long chunkOrigin = coord[i] * chunkShapeL[i];
                long lo = Math.max(offset[i], chunkOrigin);
                long hi = overlapEnd(chunkOrigin, chunkShapeL[i], selEnd[i]);
                regionOrigin[i] = (int) (lo - chunkOrigin);
                regionShape[i] = (int) (hi - lo);
            }
            byte[] chunk = readChunk(store, arrayPath, pipeline, encoding, cache, coord,
                    fillElement, chunkShape, elementSize, regionOrigin, regionShape);
            copyIntersection(out, selShape, offset, selEnd, coord, chunkShapeL, chunk, elementSize);

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

    /**
     * Decodes chunk {@code coord}, passing the needed region through so a sharding codec can fetch only
     * the sub-chunks that overlap it. An absent chunk yields a fill-valued block.
     */
    private static byte[] readChunk(Store store, String arrayPath, ChunkPipeline pipeline,
                                    ChunkKeyEncoding encoding, ChunkCache cache, long[] coord,
                                    byte[] fillElement, int[] chunkShape, int elementSize,
                                    int[] regionOrigin, int[] regionShape) {
        String relative = encoding.encode(coord);
        String key = arrayPath.isEmpty() ? relative : arrayPath + "/" + relative;

        // A whole-chunk decode is cacheable; a partial shard region is not (only its region is valid).
        // Non-sharded pipelines always decode the whole chunk, so force the full region to cache it.
        boolean wholeChunk = !pipeline.isSharded() || isFullChunk(regionOrigin, regionShape, chunkShape);
        int[] origin = regionOrigin;
        int[] shape = regionShape;
        long stamp = 0;
        if (wholeChunk && cache != null) {
            byte[] hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
            stamp = cache.stamp(); // before the store read, so a write racing it keeps this read uncached
            origin = new int[chunkShape.length];
            shape = chunkShape;
        }

        byte[] decoded = pipeline.decodeChunk(new StoreChunkBytes(store, key), fillElement, origin, shape);
        if (decoded != null) {
            if (wholeChunk && cache != null) {
                cache.put(key, decoded, stamp);
            }
            return decoded;
        }
        int count = 1;
        for (int c : chunkShape) {
            count *= c;
        }
        byte[] fill = new byte[count * elementSize];
        tile(fill, fillElement);
        return fill;
    }

    /**
     * Decodes the whole chunk stored under {@code key}, or {@code null} if it is absent. Used by the write
     * path to read a chunk back before updating part of it.
     */
    static byte[] readChunkOrNull(Store store, String key, ChunkPipeline pipeline,
                                  byte[] fillElement, long[] chunkShape) {
        int rank = chunkShape.length;
        int[] origin = new int[rank];
        int[] extent = new int[rank];
        for (int i = 0; i < rank; i++) {
            extent[i] = (int) chunkShape[i];
        }
        return pipeline.decodeChunk(new StoreChunkBytes(store, key), fillElement, origin, extent);
    }

    /** Byte-range access to one chunk in a store. */
    private record StoreChunkBytes(Store store, String key) implements ChunkBytes {

        @Override
        public OptionalLong size() {
            return store.size(key);
        }

        @Override
        public Optional<byte[]> readAll() {
            return store.get(key);
        }

        @Override
        public Optional<byte[]> readRange(long offset, long length) {
            return store.getRange(key, offset, length);
        }
    }

    /** Copies chunk {@code coord}'s overlap with the selection into {@code out}. */
    private static void copyIntersection(byte[] out, long[] selShape, long[] selOffset, long[] selEnd,
                                         long[] coord, long[] chunkShape, byte[] chunk, int elementSize) {
        int rank = selShape.length;
        long[] chunkOrigin = new long[rank];
        long[] srcOrigin = new long[rank];
        long[] dstOrigin = new long[rank];
        long[] block = new long[rank];
        for (int i = 0; i < rank; i++) {
            chunkOrigin[i] = coord[i] * chunkShape[i];
            long lo = Math.max(selOffset[i], chunkOrigin[i]);
            long hi = overlapEnd(chunkOrigin[i], chunkShape[i], selEnd[i]);
            srcOrigin[i] = lo - chunkOrigin[i];
            dstOrigin[i] = lo - selOffset[i];
            block[i] = hi - lo;
        }
        Blocks.copy(chunk, chunkShape, srcOrigin, out, selShape, dstOrigin, block, elementSize);
    }

    static void tile(byte[] buffer, byte[] element) {
        boolean allZero = true;
        for (byte b : element) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            return; // a fresh byte[] is already zero
        }
        for (int off = 0; off < buffer.length; off += element.length) {
            System.arraycopy(element, 0, buffer, off, element.length);
        }
    }

    private static int[] toInt(long[] shape) {
        int[] out = new int[shape.length];
        for (int i = 0; i < shape.length; i++) {
            out[i] = Math.toIntExact(shape[i]);
        }
        return out;
    }

    private static boolean isFullChunk(int[] regionOrigin, int[] regionShape, int[] chunkShape) {
        for (int i = 0; i < chunkShape.length; i++) {
            if (regionOrigin[i] != 0 || regionShape[i] != chunkShape[i]) {
                return false;
            }
        }
        return true;
    }
}
