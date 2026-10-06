package com.ebremer.falcon.zarr.data;

import com.ebremer.falcon.zarr.chunk.ChunkGrid;
import com.ebremer.falcon.zarr.chunk.ChunkKeyEncoding;
import com.ebremer.falcon.zarr.codec.ChunkPipeline;
import com.ebremer.falcon.zarr.codec.VlenCodec;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The chunk work of resizing an array (F6): the array's chunk grid keeps its chunks (a rectilinear grid
 * only gains one past its last where the array grows beyond it), so a resize only changes which chunks, and
 * which parts of edge chunks, lie inside the array.
 *
 * <ul>
 *   <li><b>Growing</b> brings part of an old edge chunk inside the array: the part past the old shape.
 *       That part may hold old values, since shrinking (by Falcon or zarr-python) leaves an edge chunk's
 *       bytes as they were, so {@link #clearExposed} sets it to the fill value first.</li>
 *   <li><b>Shrinking</b> leaves some chunks wholly outside the array: {@link #deleteOutside} deletes them,
 *       one delete per chunk in the removed part of the grid.</li>
 * </ul>
 *
 * Chunks wholly outside the old shape are taken to be absent, as both Falcon and zarr-python delete them
 * when shrinking; finding strays there would mean listing the store.
 */
public final class Resize {

    private Resize() {
    }

    /**
     * Sets to the fill value every element inside {@code next}'s shape but outside {@code current}'s that
     * lies in a chunk {@code current}'s grid covers, chunk by chunk, skipping chunks that are not stored.
     * Run it before the new metadata is written: the elements it touches are outside the old shape, so a
     * reader of the old metadata never sees them change.
     */
    public static void clearExposed(Store store, String arrayPath, ArrayMetadata current, ArrayMetadata next,
                                    ChunkCache cache, boolean writeEmptyChunks) {
        long[] oldShape = current.shape();
        long[] newShape = next.shape();
        ChunkGrid grid = current.grid();
        int rank = oldShape.length;
        long[] kept = new long[rank];    // inside both shapes
        long[] covered = new long[rank]; // inside the new shape and the old grid's chunks
        for (int i = 0; i < rank; i++) {
            kept[i] = Math.min(oldShape[i], newShape[i]);
            long chunks = grid.chunksAlong(i);
            long gridEnd = chunks == 0 ? 0 : ChunkAssembler.overlapEnd(grid.chunkStart(i, chunks - 1),
                    grid.chunkLength(i, chunks - 1), Long.MAX_VALUE);
            covered[i] = Math.min(newShape[i], gridEnd);
        }
        ChunkKeyEncoding encoding = next.chunkKeyEncoding();
        for (long[][] slab : boxMinus(covered, kept)) {
            long[] first = new long[rank];
            long[] last = new long[rank];
            for (int i = 0; i < rank; i++) {
                first[i] = grid.chunkAt(i, slab[0][i]);
                last[i] = grid.chunkAt(i, slab[1][i] - 1);
            }
            forEach(first, last, coord -> {
                if (!store.exists(ChunkAssembler.chunkKey(arrayPath, encoding, coord))) {
                    return; // nothing stored, nothing old to clear
                }
                long[] origin = new long[rank];
                long[] extent = new long[rank];
                for (int i = 0; i < rank; i++) {
                    long start = grid.chunkStart(i, coord[i]);
                    long lo = Math.max(slab[0][i], start);
                    long hi = ChunkAssembler.overlapEnd(start, grid.chunkLength(i, coord[i]), slab[1][i]);
                    origin[i] = lo;
                    extent[i] = hi - lo;
                }
                writeFill(store, arrayPath, next, cache, origin, extent, writeEmptyChunks);
            });
        }
    }

    /** Writes the fill value over {@code [origin, origin + extent)}, a region within one chunk. */
    private static void writeFill(Store store, String arrayPath, ArrayMetadata meta, ChunkCache cache,
                                  long[] origin, long[] extent, boolean writeEmptyChunks) {
        int count = Math.toIntExact(ChunkGrid.elementCount(extent));
        ChunkPipeline pipeline = meta.pipeline();
        VlenCodec vlen = pipeline.vlenCodec();
        if (vlen != null) {
            Object[] fill = vlen.newArray(count);
            Arrays.fill(fill, vlen.fill(meta.fillValue()));
            VlenChunks.write(store, arrayPath, meta, origin, extent, fill, writeEmptyChunks);
        } else {
            byte[] element = meta.fillValueBytes(pipeline.elementOrder());
            byte[] fill = new byte[count * element.length];
            ChunkAssembler.tile(fill, element);
            ChunkWriter.write(store, arrayPath, meta, cache, origin, extent, fill, writeEmptyChunks);
        }
    }

    /**
     * Deletes every chunk of {@code current}'s grid that lies wholly outside {@code next}'s shape. Run it
     * after the new metadata is written, so a reader of the new metadata never sees those chunks go.
     */
    public static void deleteOutside(Store store, String arrayPath, ArrayMetadata current, ArrayMetadata next,
                                     ChunkCache cache) {
        long[] oldGrid = current.grid().gridShape();
        long[] newGrid = next.grid().gridShape();
        long[] kept = new long[oldGrid.length];
        for (int i = 0; i < kept.length; i++) {
            kept[i] = Math.min(oldGrid[i], newGrid[i]);
        }
        ChunkKeyEncoding encoding = current.chunkKeyEncoding();
        for (long[][] box : boxMinus(oldGrid, kept)) {
            long[] last = box[1].clone();
            for (int i = 0; i < last.length; i++) {
                last[i]--;
            }
            forEach(box[0], last, coord -> {
                String key = ChunkAssembler.chunkKey(arrayPath, encoding, coord);
                synchronized (ChunkLocks.of(store, key)) {
                    store.delete(key);
                    if (cache != null) {
                        cache.invalidate(key);
                    }
                }
            });
        }
    }

    /**
     * {@code [0, outer)} minus {@code [0, inner)} (with {@code inner <= outer} in every dimension) as
     * disjoint boxes {@code {lo, hi}}: box {@code d} takes {@code [inner, outer)} in dimension {@code d},
     * {@code [0, inner)} before it, and {@code [0, outer)} after it. Empty boxes are left out.
     */
    static List<long[][]> boxMinus(long[] outer, long[] inner) {
        int rank = outer.length;
        List<long[][]> boxes = new ArrayList<>();
        for (int d = 0; d < rank; d++) {
            long[] lo = new long[rank];
            long[] hi = new long[rank];
            boolean empty = false;
            for (int i = 0; i < rank; i++) {
                lo[i] = i == d ? inner[i] : 0;
                hi[i] = i < d ? inner[i] : outer[i];
                empty |= lo[i] >= hi[i];
            }
            if (!empty) {
                boxes.add(new long[][] {lo, hi});
            }
        }
        return boxes;
    }

    /** Calls {@code action} with each coordinate from {@code first} to {@code last} inclusive, in C order. */
    private static void forEach(long[] first, long[] last, Consumer<long[]> action) {
        int rank = first.length;
        long[] coord = first.clone();
        while (true) {
            action.accept(coord.clone());
            int d = rank - 1;
            for (; d >= 0; d--) {
                if (++coord[d] <= last[d]) {
                    break;
                }
                coord[d] = first[d];
            }
            if (d < 0) {
                return;
            }
        }
    }
}
