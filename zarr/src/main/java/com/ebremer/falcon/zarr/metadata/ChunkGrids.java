package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.chunk.ChunkGrid;
import com.ebremer.falcon.zarr.chunk.RectilinearChunkGrid;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.Arrays;

/**
 * Parses an array's {@code chunk_grid}: the core {@code regular} grid, or the {@code rectilinear} extension
 * (zarr-extensions {@code chunk-grids/rectilinear}). A chunk grid may not be ignored, so any other is
 * {@link ZarrUnsupportedException}.
 */
final class ChunkGrids {

    private ChunkGrids() {
    }

    static ChunkGrid parse(JsonValue v, long[] shape, String ctx) {
        NamedConfig grid = NamedConfig.parse(v, ctx);
        return switch (grid.name()) {
            case "regular" -> regular(grid, shape, ctx);
            case "rectilinear" -> rectilinear(grid, shape, ctx);
            default -> throw new ZarrUnsupportedException(ctx + ": only the 'regular' and 'rectilinear' grids are"
                    + " supported, was '" + grid.name() + "'");
        };
    }

    private static ChunkGrid regular(NamedConfig grid, long[] shape, String ctx) {
        long[] chunkShape = Fields.intArray(
                Fields.require(grid.configuration(), "chunk_shape", ctx + ".configuration"),
                ctx + ".configuration.chunk_shape", true);
        if (chunkShape.length != shape.length) {
            throw new ZarrFormatException(ctx + ": chunk_shape rank " + chunkShape.length
                    + " does not match array rank " + shape.length);
        }
        try {
            return new RegularChunkGrid(shape, chunkShape);
        } catch (IllegalArgumentException e) {
            // Ranks, signs, and positivity are checked above, so the array has more elements than a long counts.
            throw new ZarrUnsupportedException(ctx + ": " + e.getMessage());
        }
    }

    /**
     * {@code {"kind": "inline", "chunk_shapes": [...]}}, one entry per dimension: a bare integer (a length
     * that repeats), or a list of lengths, each a bare integer or a {@code [length, count]} pair.
     */
    private static ChunkGrid rectilinear(NamedConfig grid, long[] shape, String ctx) {
        String cctx = ctx + ".configuration";
        String kind = Fields.string(Fields.require(grid.configuration(), "kind", cctx), cctx + ".kind");
        if (!kind.equals("inline")) {
            throw new ZarrUnsupportedException(cctx + ".kind: only 'inline' is supported, was '" + kind + "'");
        }
        JsonArray shapes = Fields.array(Fields.require(grid.configuration(), "chunk_shapes", cctx),
                cctx + ".chunk_shapes");
        if (shapes.size() != shape.length) {
            throw new ZarrFormatException(cctx + ".chunk_shapes: " + shapes.size()
                    + " dimensions do not match array rank " + shape.length);
        }
        RectilinearChunkGrid.Axis[] axes = new RectilinearChunkGrid.Axis[shape.length];
        for (int i = 0; i < axes.length; i++) {
            axes[i] = axis(shapes.get(i), cctx + ".chunk_shapes[" + i + "]");
        }
        try {
            ChunkGrid.elementCount(shape);
        } catch (ArithmeticException e) {
            throw new ZarrUnsupportedException(ctx + ": array shape " + Arrays.toString(shape) + " has more than "
                    + Long.MAX_VALUE + " elements");
        }
        try {
            return new RectilinearChunkGrid(shape, axes);
        } catch (IllegalArgumentException e) {
            throw new ZarrFormatException(cctx + ".chunk_shapes: " + e.getMessage(), e);
        }
    }

    private static RectilinearChunkGrid.Axis axis(JsonValue v, String ctx) {
        if (v instanceof JsonNumber) {
            return RectilinearChunkGrid.Axis.repeating(positive(v, ctx));
        }
        JsonArray entries = Fields.array(v, ctx);
        if (entries.size() == 0) {
            throw new ZarrFormatException(ctx + ": lists no chunk lengths");
        }
        long[] lengths = new long[entries.size()];
        long[] counts = new long[entries.size()];
        for (int r = 0; r < lengths.length; r++) {
            String ectx = ctx + "[" + r + "]";
            JsonValue entry = entries.get(r);
            if (entry instanceof JsonArray pair) {
                if (pair.size() != 2) {
                    throw new ZarrFormatException(ectx + ": a run must be [length, count], was " + entry.toJson());
                }
                lengths[r] = positive(pair.get(0), ectx + "[0]");
                counts[r] = positive(pair.get(1), ectx + "[1]");
            } else if (entry instanceof JsonString) {
                throw new ZarrFormatException(ectx + ": expected a length or a [length, count] run, was a string");
            } else {
                lengths[r] = positive(entry, ectx);
                counts[r] = 1;
            }
        }
        try {
            return RectilinearChunkGrid.Axis.runs(lengths, counts);
        } catch (IllegalArgumentException e) {
            throw new ZarrFormatException(ctx + ": " + e.getMessage(), e);
        }
    }

    private static long positive(JsonValue v, String ctx) {
        long value = Fields.integer(v, ctx);
        if (value <= 0) {
            throw new ZarrFormatException(ctx + ": must be positive, was " + value);
        }
        return value;
    }
}
