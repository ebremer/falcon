package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The {@code sharding_indexed} array&rarr;bytes codec: one stored chunk (a <em>shard</em>) packs a grid
 * of smaller sub-chunks plus an index giving each sub-chunk's {@code (offset, length)} within the shard.
 *
 * <p>The index is an array of {@code uint64} pairs of shape {@code subGrid + [2]}, encoded by
 * {@code index_codecs} (typically {@code bytes}+{@code crc32c}) and stored at {@code index_location}
 * ({@code "start"} or {@code "end"}, default {@code "end"}). A sub-chunk whose offset and length are both
 * all-ones is empty and reads as the fill value. Offsets are measured from the start of the shard.
 *
 * <p>Decoding fetches the index and then only the sub-chunks overlapping the requested region, using
 * {@link ChunkBytes} byte ranges, so a small read does not pull the whole shard.
 */
final class ShardingCodec implements ArrayBytesCodec {

    /** Both index fields all-ones marks an empty (fill) sub-chunk. */
    private static final long EMPTY = -1L;

    private static final List<JsonObject> DEFAULT_INDEX_CODECS = List.of(
            Json.parse("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}").asObject(),
            Json.parse("{\"name\":\"crc32c\"}").asObject());

    private final int[] subChunkShape;
    private final int[] subGridShape;
    private final ChunkPipeline inner;
    private final ChunkPipeline index;
    private final long encodedIndexSize;
    private final boolean indexAtStart;

    private ShardingCodec(int[] subChunkShape, int[] subGridShape, ChunkPipeline inner,
                          ChunkPipeline index, long encodedIndexSize, boolean indexAtStart) {
        this.subChunkShape = subChunkShape;
        this.subGridShape = subGridShape;
        this.inner = inner;
        this.index = index;
        this.encodedIndexSize = encodedIndexSize;
        this.indexAtStart = indexAtStart;
    }

    static ShardingCodec parse(JsonObject configuration, DataType dataType, int[] outerShape) {
        int[] sub = intArray(configuration.get("chunk_shape").asArray());
        if (sub.length != outerShape.length) {
            throw new ZarrFormatException("sharding chunk_shape has rank " + sub.length
                    + " but the outer chunk has rank " + outerShape.length);
        }
        int[] grid = new int[sub.length];
        for (int i = 0; i < sub.length; i++) {
            if (sub[i] <= 0) {
                throw new ZarrFormatException("sharding chunk_shape must be positive");
            }
            if (outerShape[i] % sub[i] != 0) {
                throw new ZarrFormatException("sharding chunk_shape " + sub[i]
                        + " does not divide the outer chunk shape " + outerShape[i] + " in dimension " + i);
            }
            grid[i] = outerShape[i] / sub[i];
        }

        List<JsonObject> innerCodecs = objects(configuration.get("codecs").asArray());
        List<JsonObject> indexCodecs = configuration.find("index_codecs")
                .map(v -> objects(v.asArray()))
                .orElse(DEFAULT_INDEX_CODECS);
        boolean atStart = configuration.find("index_location")
                .map(v -> switch (v.asString()) {
                    case "start" -> true;
                    case "end" -> false;
                    default -> throw new ZarrFormatException(
                            "sharding index_location must be 'start' or 'end', was '" + v.asString() + "'");
                })
                .orElse(false);

        ChunkPipeline inner = ChunkPipeline.of(dataType, toLong(sub), innerCodecs);
        if (inner.isSharded()) {
            throw new ZarrUnsupportedException("nested sharding is not supported");
        }

        long[] indexShape = new long[grid.length + 1];
        for (int i = 0; i < grid.length; i++) {
            indexShape[i] = grid[i];
        }
        indexShape[grid.length] = 2;
        ChunkPipeline index = ChunkPipeline.of(DataType.UINT64, indexShape, indexCodecs);
        if (index.isSharded()) {
            throw new ZarrUnsupportedException("a shard index cannot itself be sharded");
        }

        long entries = 1;
        for (int g : grid) {
            entries *= g;
        }
        long encodedIndexSize = index.encodedLength(entries * 2 * 8);
        return new ShardingCodec(sub, grid, inner, index, encodedIndexSize, atStart);
    }

    @Override
    public String name() {
        return "sharding_indexed";
    }

    @Override
    public ByteOrder elementByteOrder() {
        return inner.elementOrder();
    }

    @Override
    public ArrayValue decode(ChunkBytes source, int[] shape, int elementSize, byte[] fillElement,
                             int[] regionOrigin, int[] regionShape) {
        OptionalLong storedSize = source.size();
        if (storedSize.isEmpty()) {
            return null;
        }
        long shardSize = storedSize.getAsLong();
        if (shardSize < encodedIndexSize) {
            throw new ZarrFormatException("shard is " + shardSize
                    + " bytes, smaller than its " + encodedIndexSize + "-byte index");
        }
        long indexOffset = indexAtStart ? 0 : shardSize - encodedIndexSize;
        byte[] indexBytes = source.readRange(indexOffset, encodedIndexSize).orElse(null);
        if (indexBytes == null) {
            return null;
        }
        ByteBuffer entries = ByteBuffer.wrap(index.decode(indexBytes)).order(index.elementOrder());

        byte[] out = new byte[Pipelines.elementCount(shape) * elementSize];
        tile(out, fillElement);

        int rank = shape.length;
        for (int i = 0; i < rank; i++) {
            if (regionShape[i] <= 0) {
                return new ArrayValue(out, shape); // nothing requested
            }
        }

        // Only the sub-chunks overlapping the requested region.
        int[] first = new int[rank];
        int[] last = new int[rank];
        for (int i = 0; i < rank; i++) {
            first[i] = regionOrigin[i] / subChunkShape[i];
            last[i] = (regionOrigin[i] + regionShape[i] - 1) / subChunkShape[i];
        }

        int[] coord = first.clone();
        while (true) {
            int linear = 0;
            for (int i = 0; i < rank; i++) {
                linear = linear * subGridShape[i] + coord[i];
            }
            long offset = entries.getLong(linear * 16);
            long length = entries.getLong(linear * 16 + 8);
            if (offset != EMPTY || length != EMPTY) {
                byte[] sub = source.readRange(offset, length).orElseThrow(
                        () -> new ZarrFormatException("shard sub-chunk bytes are missing"));
                if (sub.length != length) {
                    throw new ZarrFormatException("shard sub-chunk is truncated: got " + sub.length
                            + " of " + length + " bytes");
                }
                byte[] subElements = inner.decode(sub);
                int[] origin = new int[rank];
                for (int i = 0; i < rank; i++) {
                    origin[i] = coord[i] * subChunkShape[i];
                }
                copyBlock(subElements, subChunkShape, out, shape, origin, elementSize);
            }
            int d = rank - 1;
            for (; d >= 0; d--) {
                if (++coord[d] <= last[d]) {
                    break;
                }
                coord[d] = first[d];
            }
            if (d < 0) {
                break;
            }
        }
        return new ArrayValue(out, shape);
    }

    @Override
    public byte[] encode(ArrayValue array, int elementSize, byte[] fillElement) {
        int rank = array.shape.length;
        int subElements = Pipelines.elementCount(subChunkShape);
        int subBytes = subElements * elementSize;
        byte[] emptySub = new byte[subBytes];
        tile(emptySub, fillElement);

        int count = 1;
        for (int g : subGridShape) {
            count *= g;
        }
        long[] offsets = new long[count];
        long[] lengths = new long[count];
        List<byte[]> payloads = new ArrayList<>(count);

        long encodedIndex = encodedIndexSize;
        long cursor = indexAtStart ? encodedIndex : 0;
        int[] coord = new int[rank];
        for (int linear = 0; linear < count; linear++) {
            byte[] sub = new byte[subBytes];
            int[] origin = new int[rank];
            for (int i = 0; i < rank; i++) {
                origin[i] = coord[i] * subChunkShape[i];
            }
            extractBlock(array.data, array.shape, origin, sub, subChunkShape, elementSize);
            if (java.util.Arrays.equals(sub, emptySub)) {
                offsets[linear] = EMPTY; // all fill: omit the sub-chunk entirely
                lengths[linear] = EMPTY;
            } else {
                byte[] payload = inner.encode(sub, fillElement);
                offsets[linear] = cursor;
                lengths[linear] = payload.length;
                cursor += payload.length;
                payloads.add(payload);
            }
            for (int i = rank - 1; i >= 0; i--) {
                if (++coord[i] < subGridShape[i]) {
                    break;
                }
                coord[i] = 0;
            }
        }

        ByteBuffer entries = ByteBuffer.allocate(count * 16).order(index.elementOrder());
        for (int i = 0; i < count; i++) {
            entries.putLong(offsets[i]);
            entries.putLong(lengths[i]);
        }
        byte[] indexBytes = index.encode(entries.array(), new byte[8]);
        if (indexBytes.length != encodedIndex) {
            throw new ZarrFormatException("shard index encoded to " + indexBytes.length
                    + " bytes, expected " + encodedIndex);
        }

        long dataBytes = cursor - (indexAtStart ? encodedIndex : 0);
        byte[] shard = new byte[(int) (dataBytes + encodedIndex)];
        int dataStart = indexAtStart ? (int) encodedIndex : 0;
        int position = dataStart;
        for (byte[] payload : payloads) {
            System.arraycopy(payload, 0, shard, position, payload.length);
            position += payload.length;
        }
        System.arraycopy(indexBytes, 0, shard, indexAtStart ? 0 : (int) dataBytes, indexBytes.length);
        return shard;
    }

    /** Copies a sub-chunk out of the shard's element buffer. */
    private static void extractBlock(byte[] src, int[] srcShape, int[] srcOrigin,
                                     byte[] dst, int[] dstShape, int elementSize) {
        int rank = srcShape.length;
        if (rank == 0) {
            System.arraycopy(src, 0, dst, 0, elementSize);
            return;
        }
        int[] srcStride = strides(srcShape);
        int[] dstStride = strides(dstShape);
        int last = rank - 1;
        int run = dstShape[last];
        int outer = 1;
        for (int i = 0; i < last; i++) {
            outer *= dstShape[i];
        }
        int[] index = new int[rank];
        for (int n = 0; n < outer; n++) {
            int srcOffset = srcOrigin[last] * srcStride[last];
            int dstOffset = 0;
            for (int i = 0; i < last; i++) {
                srcOffset += (srcOrigin[i] + index[i]) * srcStride[i];
                dstOffset += index[i] * dstStride[i];
            }
            System.arraycopy(src, srcOffset * elementSize, dst, dstOffset * elementSize, run * elementSize);
            for (int i = last - 1; i >= 0; i--) {
                if (++index[i] < dstShape[i]) {
                    break;
                }
                index[i] = 0;
            }
        }
    }

    /** Copies a full sub-chunk into the shard's element buffer at {@code dstOrigin}. */
    private static void copyBlock(byte[] src, int[] srcShape, byte[] dst, int[] dstShape,
                                  int[] dstOrigin, int elementSize) {
        int rank = srcShape.length;
        if (rank == 0) {
            System.arraycopy(src, 0, dst, 0, elementSize);
            return;
        }
        int[] srcStride = strides(srcShape);
        int[] dstStride = strides(dstShape);
        int last = rank - 1;
        int run = srcShape[last];
        int outer = 1;
        for (int i = 0; i < last; i++) {
            outer *= srcShape[i];
        }
        int[] index = new int[rank];
        for (int n = 0; n < outer; n++) {
            int srcOffset = 0;
            int dstOffset = dstOrigin[last] * dstStride[last];
            for (int i = 0; i < last; i++) {
                srcOffset += index[i] * srcStride[i];
                dstOffset += (dstOrigin[i] + index[i]) * dstStride[i];
            }
            System.arraycopy(src, srcOffset * elementSize, dst, dstOffset * elementSize, run * elementSize);
            for (int i = last - 1; i >= 0; i--) {
                if (++index[i] < srcShape[i]) {
                    break;
                }
                index[i] = 0;
            }
        }
    }

    private static int[] strides(int[] shape) {
        int[] stride = new int[shape.length];
        int acc = 1;
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
        if (allZero || element.length == 0) {
            return;
        }
        for (int off = 0; off < buffer.length; off += element.length) {
            System.arraycopy(element, 0, buffer, off, element.length);
        }
    }

    private static int[] intArray(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).asNumber().intValue();
        }
        return out;
    }

    private static long[] toLong(int[] values) {
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }

    private static List<JsonObject> objects(JsonArray array) {
        List<JsonObject> out = new ArrayList<>(array.size());
        for (JsonValue v : array.values()) {
            out.add(v.asObject());
        }
        return out;
    }
}
