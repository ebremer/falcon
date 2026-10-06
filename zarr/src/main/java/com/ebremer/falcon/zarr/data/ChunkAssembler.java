package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.chunk.ChunkGrid;
import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;

/**
 * Reads a hyperslab from a chunked array by touching only the chunks that overlap the selection.
 *
 * <p>For each overlapping chunk it fetches and decodes the stored bytes (an absent chunk becomes the fill
 * value) and copies the chunk's intersection with the selection into the output buffer. The output is a
 * flat buffer of the selected elements in C (row-major) order, each primitive in the pipeline's
 * {@linkplain ChunkPipeline#elementOrder() element order}. A chunk the selection covers only in part is
 * decoded whole, unless it is a shard: then only the selected region is fetched, decoded, and allocated.
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

    /**
     * The chunks a selection {@code [offset, end)} overlaps: {@code first[i]} to {@code last[i]} inclusive
     * along each dimension. The selection must be non-empty.
     */
    static long[][] chunkRange(ChunkGrid grid, long[] offset, long[] end) {
        int rank = offset.length;
        long[] first = new long[rank];
        long[] last = new long[rank];
        for (int i = 0; i < rank; i++) {
            first[i] = grid.chunkAt(i, offset[i]);
            last[i] = grid.chunkAt(i, end[i] - 1);
        }
        return new long[][] {first, last};
    }

    /** The declared shape of the chunk at {@code coord}. */
    static long[] chunkShape(ChunkGrid grid, long[] coord) {
        long[] shape = new long[coord.length];
        for (int i = 0; i < shape.length; i++) {
            shape[i] = grid.chunkLength(i, coord[i]);
        }
        return shape;
    }

    /**
     * Advances {@code coord} to the next chunk of {@code [first, last]} in C order; false when it was the
     * last.
     */
    static boolean next(long[] coord, long[] first, long[] last) {
        for (int d = coord.length - 1; d >= 0; d--) {
            if (++coord[d] <= last[d]) {
                return true;
            }
            coord[d] = first[d];
        }
        return false;
    }

    /** The store key of the chunk at {@code coord}. */
    static String chunkKey(String arrayPath, ChunkKeyEncoding encoding, long[] coord) {
        String relative = encoding.encode(coord);
        return arrayPath.isEmpty() ? relative : arrayPath + "/" + relative;
    }

    /** Reads the selection {@code [offset, offset+selShape)} as a flat element buffer. */
    public static byte[] assemble(Store store, String arrayPath, ArrayMetadata meta, ChunkCache cache,
                                  long[] offset, long[] selShape) {
        checkSelection(meta, offset, selShape);

        ChunkGrid grid = meta.grid();
        int rank = grid.rank();
        DataType dataType = meta.dataType();
        int elementSize = dataType.byteCount();

        long total = ChunkGrid.elementCount(selShape); // within the array, so it fits a long
        byte[] out = new byte[bufferSize(total, elementSize, "selection")];
        if (total == 0) {
            return out;
        }

        ByteOrder order = meta.pipeline().elementOrder();
        ChunkKeyEncoding encoding = meta.chunkKeyEncoding();
        byte[] fillElement = meta.fillValueBytes(order);
        long[] selEnd = new long[rank];
        for (int i = 0; i < rank; i++) {
            selEnd[i] = offset[i] + selShape[i];
        }

        // The inclusive range of chunk coordinates the selection overlaps in each dimension.
        long[][] range = chunkRange(grid, offset, selEnd);
        long[] coord = range[0].clone();
        int[] regionOrigin = new int[rank];
        int[] regionShape = new int[rank];
        do {
            // The part of this chunk the selection needs, in chunk-local coordinates.
            long[] chunkOrigin = new long[rank];
            long[] chunkShapeL = chunkShape(grid, coord);
            for (int i = 0; i < rank; i++) {
                chunkOrigin[i] = grid.chunkStart(i, coord[i]);
                long lo = Math.max(offset[i], chunkOrigin[i]);
                long hi = overlapEnd(chunkOrigin[i], chunkShapeL[i], selEnd[i]);
                regionOrigin[i] = (int) (lo - chunkOrigin[i]);
                regionShape[i] = (int) (hi - lo);
            }
            Block block = readChunk(store, chunkKey(arrayPath, encoding, coord), meta.chunkPipeline(coord), cache,
                    fillElement, toInt(chunkShapeL), elementSize, regionOrigin, regionShape);
            copyIntersection(out, selShape, offset, selEnd, chunkOrigin, chunkShapeL, block, elementSize);
        } while (next(coord, range[0], range[1]));
        return out;
    }

    /** Decoded elements of part of a chunk: {@code data} holds the box {@code [origin, origin + shape)}. */
    private record Block(byte[] data, int[] origin, int[] shape) {
    }

    /**
     * Decodes the part of the chunk under {@code key} that the selection needs. A chunk is decoded whole
     * (and, with a cache, cached), except a shard that the selection covers only in part: then just the
     * region is fetched and decoded. An absent chunk yields fill.
     */
    private static Block readChunk(Store store, String key, ChunkPipeline pipeline, ChunkCache cache,
                                   byte[] fillElement, int[] chunkShape, int elementSize,
                                   int[] regionOrigin, int[] regionShape) {
        StoreChunkBytes source = new StoreChunkBytes(store, key, cache);
        int[] zero = new int[chunkShape.length];
        if (pipeline.isSharded() && !isFullChunk(regionOrigin, regionShape, chunkShape)) {
            byte[] region = pipeline.decodeRegion(source, fillElement, regionOrigin, regionShape);
            if (region == null) {
                region = new byte[elementCount(regionShape) * elementSize];
                tile(region, fillElement);
            }
            return new Block(region, regionOrigin.clone(), regionShape.clone());
        }

        long stamp = 0;
        if (cache != null) {
            byte[] hit = cache.get(key);
            if (hit != null) {
                return new Block(hit, zero, chunkShape);
            }
            stamp = cache.stamp(); // before the store read, so a write racing it keeps this read uncached
        }
        byte[] decoded = pipeline.decodeChunk(source, fillElement, zero, chunkShape);
        if (decoded != null) {
            if (cache != null) {
                cache.put(key, decoded, stamp);
            }
            return new Block(decoded, zero, chunkShape);
        }
        byte[] fill = new byte[elementCount(chunkShape) * elementSize];
        tile(fill, fillElement);
        return new Block(fill, zero, chunkShape);
    }

    /**
     * Decodes the whole chunk stored under {@code key}, or {@code null} if it is absent. Used by the write
     * path to read a chunk back before updating part of it.
     */
    static byte[] readChunkOrNull(Store store, String key, ChunkPipeline pipeline,
                                  byte[] fillElement, long[] chunkShape) {
        int[] extent = toInt(chunkShape);
        return pipeline.decodeChunk(new StoreChunkBytes(store, key, null), fillElement, new int[extent.length],
                extent);
    }

    /**
     * Copies the overlap of the chunk at {@code chunkOrigin} with the selection from {@code block} into
     * {@code out}.
     */
    private static void copyIntersection(byte[] out, long[] selShape, long[] selOffset, long[] selEnd,
                                         long[] chunkOrigin, long[] chunkShape, Block block, int elementSize) {
        int rank = selShape.length;
        long[] blockShape = new long[rank];
        long[] srcOrigin = new long[rank];
        long[] dstOrigin = new long[rank];
        long[] extent = new long[rank];
        for (int i = 0; i < rank; i++) {
            long lo = Math.max(selOffset[i], chunkOrigin[i]);
            long hi = overlapEnd(chunkOrigin[i], chunkShape[i], selEnd[i]);
            blockShape[i] = block.shape[i];
            srcOrigin[i] = lo - chunkOrigin[i] - block.origin[i];
            dstOrigin[i] = lo - selOffset[i];
            extent[i] = hi - lo;
        }
        Blocks.copy(block.data, blockShape, srcOrigin, out, selShape, dstOrigin, extent, elementSize);
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

    static int[] toInt(long[] shape) {
        int[] out = new int[shape.length];
        for (int i = 0; i < shape.length; i++) {
            out[i] = Math.toIntExact(shape[i]);
        }
        return out;
    }

    private static int elementCount(int[] shape) {
        int count = 1;
        for (int d : shape) {
            count *= d;
        }
        return count;
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
