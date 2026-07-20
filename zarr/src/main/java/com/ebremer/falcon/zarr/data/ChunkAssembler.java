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
            if (offset[i] < 0 || shape[i] < 0 || offset[i] + shape[i] > arrayShape[i]) {
                throw new IndexOutOfBoundsException("selection [" + offset[i] + "," + (offset[i] + shape[i])
                        + ") is out of bounds [0," + arrayShape[i] + ") in dimension " + i);
            }
        }
    }

    /** Reads the selection {@code [offset, offset+selShape)} as a flat element buffer. */
    public static byte[] assemble(Store store, String arrayPath, ArrayMetadata meta,
                                  long[] offset, long[] selShape) {
        checkSelection(meta, offset, selShape);

        RegularChunkGrid grid = meta.grid();
        int rank = grid.rank();
        long[] chunkShapeL = grid.chunkShape();
        DataType dataType = meta.dataType();
        int elementSize = dataType.byteCount();

        long total = 1;
        for (long s : selShape) {
            total *= s;
        }
        long totalBytes = total * elementSize;
        if (totalBytes > Integer.MAX_VALUE) {
            throw new ZarrException(
                    "selection of " + total + " elements is too large to read into a single array");
        }
        byte[] out = new byte[(int) totalBytes];
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
                long hi = Math.min(selEnd[i], chunkOrigin + chunkShapeL[i]);
                regionOrigin[i] = (int) (lo - chunkOrigin);
                regionShape[i] = (int) (hi - lo);
            }
            byte[] chunk = readChunk(store, arrayPath, pipeline, encoding, coord,
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
                                    ChunkKeyEncoding encoding, long[] coord, byte[] fillElement,
                                    int[] chunkShape, int elementSize,
                                    int[] regionOrigin, int[] regionShape) {
        String relative = encoding.encode(coord);
        String key = arrayPath.isEmpty() ? relative : arrayPath + "/" + relative;
        byte[] decoded = pipeline.decodeChunk(new StoreChunkBytes(store, key), fillElement,
                regionOrigin, regionShape);
        if (decoded != null) {
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
        if (rank == 0) {
            System.arraycopy(chunk, 0, out, 0, elementSize);
            return;
        }
        long[] chunkOrigin = new long[rank];
        long[] lo = new long[rank];
        long[] hi = new long[rank];
        for (int i = 0; i < rank; i++) {
            chunkOrigin[i] = coord[i] * chunkShape[i];
            lo[i] = Math.max(selOffset[i], chunkOrigin[i]);
            hi[i] = Math.min(selEnd[i], chunkOrigin[i] + chunkShape[i]);
        }
        int last = rank - 1;
        long run = hi[last] - lo[last];
        if (run <= 0) {
            return;
        }
        long[] outStride = rowMajorStrides(selShape);
        long[] chunkStride = rowMajorStrides(chunkShape);
        long[] g = lo.clone(); // current global coordinate; the last axis stays at lo[last]
        while (true) {
            long chunkFlat = 0;
            long outFlat = 0;
            for (int i = 0; i < rank; i++) {
                chunkFlat += (g[i] - chunkOrigin[i]) * chunkStride[i];
                outFlat += (g[i] - selOffset[i]) * outStride[i];
            }
            System.arraycopy(chunk, (int) (chunkFlat * elementSize),
                    out, (int) (outFlat * elementSize), (int) (run * elementSize));
            int d = last - 1;
            for (; d >= 0; d--) {
                if (++g[d] < hi[d]) {
                    break;
                }
                g[d] = lo[d];
            }
            if (d < 0) {
                break;
            }
        }
    }

    private static long[] rowMajorStrides(long[] shape) {
        long[] stride = new long[shape.length];
        long acc = 1;
        for (int i = shape.length - 1; i >= 0; i--) {
            stride[i] = acc;
            acc *= shape[i];
        }
        return stride;
    }

    private static void tile(byte[] buffer, byte[] element) {
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
}
