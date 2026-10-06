package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The {@code sharding_indexed} array&rarr;bytes codec: one stored chunk (a <em>shard</em>) packs a grid
 * of smaller sub-chunks plus an index giving each sub-chunk's {@code (offset, length)} within the shard.
 *
 * <p>The index is an array of {@code uint64} pairs of shape {@code subGrid + [2]}, encoded by
 * {@code index_codecs} (typically {@code bytes}+{@code crc32c}) and stored at {@code index_location}
 * ({@code "start"} or {@code "end"}, default {@code "end"}). A sub-chunk whose offset and length are both
 * all-ones is empty and reads as the fill value. Offsets are measured from the start of the shard.
 *
 * <p>Decoding fetches the index (from the end with a suffix read, so no size query is needed) and then
 * only the sub-chunks overlapping the requested region, using {@link ChunkBytes} byte ranges, so a small
 * read does not pull the whole shard. {@link #decodeRegion} returns just that region. Sub-chunks may hold
 * fixed-size elements or variable-length ones (an inner {@code vlen-utf8} or {@code vlen-bytes} pipeline,
 * as zarr-python writes for sharded string arrays). {@link #update} re-encodes only the sub-chunks a write
 * touches.
 *
 * <p>A sub-chunk may itself be a shard (nested sharding, which zarr-python writes), to any depth. A read
 * that needs only part of such a sub-chunk reads it through a {@link ChunkBytes#slice} of the outer source,
 * so it fetches the inner index and the inner sub-chunks it needs, not the whole sub-chunk; and a write to
 * part of one re-encodes only the inner sub-chunks it touches.
 */
final class ShardingCodec implements ArrayBytesCodec {

    /** Both index fields all-ones marks an empty (fill) sub-chunk. */
    private static final long EMPTY = -1L;

    /**
     * Sub-chunk byte ranges separated by no more than this are fetched together (reading the small gap in
     * between is cheaper than a second round trip). Sub-chunks are usually packed contiguously, so this
     * mainly bridges the holes left by empty sub-chunks in a partial read.
     */
    private static final long MAX_COALESCE_GAP = 8 * 1024;

    private static final List<JsonObject> DEFAULT_INDEX_CODECS = List.of(
            Json.parse("{\"name\":\"bytes\",\"configuration\":{\"endian\":\"little\"}}").asObject(),
            Json.parse("{\"name\":\"crc32c\"}").asObject());

    private final int[] subChunkShape;
    private final int[] subGridShape;
    private final int subChunkCount;
    private final ChunkPipeline inner;
    private final ChunkPipeline index;
    private final long encodedIndexSize;
    private final boolean indexAtStart;

    private ShardingCodec(int[] subChunkShape, int[] subGridShape, ChunkPipeline inner,
                          ChunkPipeline index, long encodedIndexSize, boolean indexAtStart) {
        this.subChunkShape = subChunkShape;
        this.subGridShape = subGridShape;
        this.subChunkCount = Pipelines.elementCount(subGridShape);
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

        ChunkPipeline inner = ChunkPipeline.of(dataType, toLong(sub), innerCodecs); // may itself be sharded

        long[] indexShape = new long[grid.length + 1];
        for (int i = 0; i < grid.length; i++) {
            indexShape[i] = grid[i];
        }
        indexShape[grid.length] = 2;
        ChunkPipeline index = ChunkPipeline.of(DataType.UINT64, indexShape, indexCodecs);
        if (index.isSharded()) {
            throw new ZarrUnsupportedException("a shard index cannot itself be sharded");
        }

        long entries = Pipelines.elementCount(grid);
        long encodedIndexSize = index.encodedLength(entries * 2 * 8);
        return new ShardingCodec(sub, grid, inner, index, encodedIndexSize, atStart);
    }

    @Override
    public String name() {
        return "sharding_indexed";
    }

    /** The shape of each sub-chunk (a copy). */
    int[] subChunkShape() {
        return subChunkShape.clone();
    }

    @Override
    public ByteOrder elementByteOrder() {
        return inner.elementOrder();
    }

    @Override
    public long maxEncodedSize(int[] shape, int elementSize) {
        long sub = inner.maxEncodedLength();
        if (sub > (Long.MAX_VALUE - encodedIndexSize) / subChunkCount) {
            return Long.MAX_VALUE;
        }
        return subChunkCount * sub + encodedIndexSize;
    }

    // ---- reading -------------------------------------------------------------------------------------

    @Override
    public ArrayValue decode(ChunkBytes source, int[] shape, int elementSize, byte[] fillElement,
                             int[] regionOrigin, int[] regionShape) {
        long[] entries = readIndex(source);
        if (entries == null) {
            return null;
        }
        byte[] out = new byte[Pipelines.elementCount(shape) * elementSize];
        Pipelines.tile(out, fillElement);
        int[] zero = new int[shape.length];
        forEachSubChunk(source, entries, regionOrigin, regionShape, (origin, sub, local, extent) -> {
            // Outside the region a nested shard may leave fill; the caller reads only the region.
            byte[] decoded = present(inner.decodeChunk(sub, fillElement, local, extent));
            Pipelines.copyBox(decoded, subChunkShape, zero, out, shape, origin, subChunkShape, elementSize);
        });
        return new ArrayValue(out, shape);
    }

    /**
     * Decodes only {@code [regionOrigin, regionOrigin + regionShape)} of the shard, returned as a buffer of
     * the region's shape: a small read of a large shard allocates no more than it asked for.
     *
     * @return the region's elements in C order, or {@code null} if the shard is absent
     */
    byte[] decodeRegion(ChunkBytes source, int elementSize, byte[] fillElement, int[] regionOrigin,
                        int[] regionShape) {
        long[] entries = readIndex(source);
        if (entries == null) {
            return null;
        }
        byte[] out = new byte[Pipelines.elementCount(regionShape) * elementSize];
        Pipelines.tile(out, fillElement);
        forEachSubChunk(source, entries, regionOrigin, regionShape, (origin, sub, local, extent) -> {
            byte[] part = present(inner.decodeRegion(sub, fillElement, local, extent));
            Pipelines.copyBox(part, extent, new int[extent.length], out, regionShape,
                    Pipelines.minus(Pipelines.plus(origin, local), regionOrigin), extent, elementSize);
        });
        return out;
    }

    /**
     * Decodes a shard of variable-length elements: the sub-chunks overlapping the region are decoded by the
     * inner ({@code vlen-utf8} or {@code vlen-bytes}) pipeline; every other element is {@code fill}.
     *
     * @return the shard's elements in C order, or {@code null} if the shard is absent
     */
    Object[] decodeVlen(ChunkBytes source, int[] shape, Object fill, int[] regionOrigin, int[] regionShape) {
        long[] entries = readIndex(source);
        if (entries == null) {
            return null;
        }
        Object[] out = inner.vlenCodec().newArray(Pipelines.elementCount(shape));
        Arrays.fill(out, fill);
        int[] zero = new int[shape.length];
        forEachSubChunk(source, entries, regionOrigin, regionShape, (origin, sub, local, extent) -> {
            Object[] decoded = present(inner.decodeVlenChunk(sub, fill, local, extent));
            Pipelines.copyBox(decoded, subChunkShape, zero, out, shape, origin, subChunkShape);
        });
        return out;
    }

    /** A sub-chunk the index lists, decoded: present, or the shard changed or broke between reads. */
    private static <T> T present(T decoded) {
        if (decoded == null) {
            throw new ZarrFormatException("shard sub-chunk bytes are missing");
        }
        return decoded;
    }

    /**
     * Reads and checks the shard's index (H2): a sub-chunk is empty only when both fields are all-ones,
     * and otherwise must name a byte range that a {@code long} can hold and one array can read.
     *
     * @return the entries as {@code offset, length} pairs, or {@code null} if the shard is absent
     */
    private long[] readIndex(ChunkBytes source) {
        byte[] raw = source.readShardIndex(indexAtStart, encodedIndexSize).orElse(null);
        if (raw == null) {
            return null;
        }
        if (raw.length != encodedIndexSize) {
            throw new ZarrFormatException("shard is " + raw.length + " bytes, smaller than its "
                    + encodedIndexSize + "-byte index");
        }
        ByteBuffer decoded = ByteBuffer.wrap(index.decode(raw)).order(index.elementOrder());
        long[] entries = new long[2 * subChunkCount];
        for (int i = 0; i < subChunkCount; i++) {
            long offset = decoded.getLong(16 * i);
            long length = decoded.getLong(16 * i + 8);
            if (offset != EMPTY || length != EMPTY) {
                // Unsigned values of 2^63 and up read as negative; a range must not overflow a long.
                if (offset < 0 || length < 0 || length > Long.MAX_VALUE - offset) {
                    throw new ZarrFormatException("shard index entry " + i + " (offset "
                            + Long.toUnsignedString(offset) + ", length " + Long.toUnsignedString(length)
                            + ") is not a valid byte range");
                }
                if (length > Integer.MAX_VALUE) {
                    throw new ZarrFormatException("shard sub-chunk " + i + " of " + length
                            + " bytes is too large to read");
                }
            }
            entries[2 * i] = offset;
            entries[2 * i + 1] = length;
        }
        return entries;
    }

    /**
     * Receives one sub-chunk the region overlaps: its origin in the shard, its stored bytes (fetched, or a
     * slice of the shard to read from), and the part of it the region covers, in the sub-chunk's own
     * coordinates.
     */
    private interface SubChunkSink {
        void accept(int[] origin, ChunkBytes stored, int[] localOrigin, int[] localShape);
    }

    /**
     * A non-empty sub-chunk to fetch: its byte range in the shard, where its elements land, and the part of
     * it the region covers.
     */
    private record SubChunk(long offset, long length, int[] origin, int[] localOrigin, int[] localShape) {
    }

    /**
     * Fetches the non-empty sub-chunks overlapping the region and hands each to {@code sink}, in as few
     * range requests as possible: sorted by offset, adjacent ranges (and ranges separated by only a small
     * gap) are merged into one {@link ChunkBytes#readRange} and then sliced apart. A shard packs its
     * sub-chunks contiguously, so a run of them usually needs a single fetch, which matters most over
     * HTTP, where each fetch is a round trip.
     *
     * <p>A sub-chunk that is itself a shard read in part ({@link ChunkPipeline#readsPartially}), and that the
     * region covers only in part, is not fetched: the sink gets a {@link ChunkBytes#slice} of the source,
     * through which the inner shard reads its own index and the inner sub-chunks it needs.
     */
    private void forEachSubChunk(ChunkBytes source, long[] entries, int[] regionOrigin, int[] regionShape,
                                 SubChunkSink sink) {
        int rank = subChunkShape.length;
        for (int i = 0; i < rank; i++) {
            if (regionShape[i] <= 0) {
                return; // nothing requested
            }
        }
        int[] first = new int[rank];
        int[] last = new int[rank];
        for (int i = 0; i < rank; i++) {
            first[i] = regionOrigin[i] / subChunkShape[i];
            last[i] = (regionOrigin[i] + regionShape[i] - 1) / subChunkShape[i];
        }
        boolean partialInner = inner.readsPartially();
        List<SubChunk> needed = new ArrayList<>();
        int[] coord = first.clone();
        while (true) {
            int linear = 0;
            for (int i = 0; i < rank; i++) {
                linear = linear * subGridShape[i] + coord[i];
            }
            long offset = entries[2 * linear];
            long length = entries[2 * linear + 1];
            if (offset != EMPTY || length != EMPTY) {
                int[] origin = new int[rank];
                for (int i = 0; i < rank; i++) {
                    origin[i] = coord[i] * subChunkShape[i];
                }
                int[][] overlap = Pipelines.intersect(origin, subChunkShape, regionOrigin, regionShape);
                int[] local = Pipelines.minus(overlap[0], origin);
                if (partialInner && !Arrays.equals(overlap[1], subChunkShape)) {
                    sink.accept(origin, source.slice(offset, length), local, overlap[1]);
                } else {
                    needed.add(new SubChunk(offset, length, origin, local, overlap[1]));
                }
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
        if (needed.isEmpty()) {
            return;
        }

        needed.sort(Comparator.comparingLong(SubChunk::offset));
        int i = 0;
        while (i < needed.size()) {
            long groupStart = needed.get(i).offset;
            long groupEnd = groupStart + needed.get(i).length;
            int j = i + 1;
            while (j < needed.size() && needed.get(j).offset - groupEnd <= MAX_COALESCE_GAP) {
                groupEnd = Math.max(groupEnd, needed.get(j).offset + needed.get(j).length);
                j++;
            }
            long span = groupEnd - groupStart;
            if (span > Integer.MAX_VALUE) {
                throw new ZarrFormatException("shard range of " + span + " bytes is too large to read");
            }
            byte[] group = source.readRange(groupStart, span).orElseThrow(
                    () -> new ZarrFormatException("shard sub-chunk bytes are missing"));
            if (group.length != span) {
                throw new ZarrFormatException("shard is truncated: got " + group.length + " of " + span
                        + " bytes at offset " + groupStart);
            }
            for (int k = i; k < j; k++) {
                SubChunk s = needed.get(k);
                int localOffset = (int) (s.offset - groupStart);
                byte[] stored = j == i + 1 ? group // a lone sub-chunk is the whole fetch
                        : Arrays.copyOfRange(group, localOffset, localOffset + (int) s.length);
                sink.accept(s.origin, ChunkBytes.of(stored), s.localOrigin, s.localShape);
            }
            i = j;
        }
    }

    // ---- writing -------------------------------------------------------------------------------------

    /** Produces one sub-chunk's stored bytes, or {@code null} for an empty (all-fill) sub-chunk. */
    private interface Payloads {
        byte[] payload(int linear, int[] origin);
    }

    @Override
    public byte[] encode(ArrayValue array, int elementSize, byte[] fillElement, boolean writeEmptyChunks) {
        byte[] emptySub = new byte[Pipelines.elementCount(subChunkShape) * elementSize];
        Pipelines.tile(emptySub, fillElement);
        int[] zero = new int[subChunkShape.length];
        byte[] shard = assemble((linear, origin) -> {
            byte[] sub = new byte[emptySub.length];
            Pipelines.copyBox(array.data, array.shape, origin, sub, subChunkShape, zero, subChunkShape, elementSize);
            return !writeEmptyChunks && Arrays.equals(sub, emptySub) ? null // all fill: omitted
                    : inner.encode(sub, fillElement, writeEmptyChunks);
        });
        return shard != null ? shard : assembleEmpty();
    }

    /**
     * Encodes a shard of variable-length elements; a sub-chunk holding only {@code fill} is omitted unless
     * {@code writeEmptyChunks}.
     */
    byte[] encodeVlen(Object[] chunk, int[] shape, Object fill, boolean writeEmptyChunks) {
        VlenCodec vlen = inner.vlenCodec();
        int[] zero = new int[subChunkShape.length];
        byte[] shard = assemble((linear, origin) -> {
            Object[] sub = vlen.newArray(Pipelines.elementCount(subChunkShape));
            Pipelines.copyBox(chunk, shape, origin, sub, subChunkShape, zero, subChunkShape);
            return !writeEmptyChunks && vlen.isAllFill(sub, fill) ? null
                    : inner.encodeVlen(sub, fill, writeEmptyChunks);
        });
        return shard != null ? shard : assembleEmpty();
    }

    /**
     * Rewrites a shard after a write to {@code [regionOrigin, regionOrigin + regionShape)} of it (PF2).
     * {@code chunk} holds the new elements in that region; outside it, it is ignored. A sub-chunk the
     * region does not touch keeps its stored bytes; one it covers is encoded from {@code chunk}; one it
     * covers partly is decoded, updated, and encoded, or, if it is itself a shard, updated the same way,
     * one level down. Nothing else is decoded or encoded.
     *
     * @param oldShard         the shard's stored bytes, or {@code null} if it is absent
     * @param writeEmptyChunks whether a touched sub-chunk that holds only the fill value is stored rather
     *                         than omitted
     * @return the new shard, or {@code null} if every sub-chunk is now empty (all fill)
     */
    byte[] update(byte[] oldShard, byte[] chunk, int[] shape, int elementSize, byte[] fillElement,
                  int[] regionOrigin, int[] regionShape, boolean writeEmptyChunks) {
        ChunkBytes old = oldShard == null ? null : ChunkBytes.of(oldShard);
        long[] entries = old == null ? null : readIndex(old);
        byte[] emptySub = new byte[Pipelines.elementCount(subChunkShape) * elementSize];
        Pipelines.tile(emptySub, fillElement);
        int[] zero = new int[subChunkShape.length];
        return assemble((linear, origin) -> {
            byte[] stored = null;
            if (entries != null && (entries[2 * linear] != EMPTY || entries[2 * linear + 1] != EMPTY)) {
                long offset = entries[2 * linear];
                long length = entries[2 * linear + 1];
                if (offset + length > oldShard.length) {
                    throw new ZarrFormatException("shard is truncated: sub-chunk " + linear + " ends at "
                            + (offset + length) + ", past its " + oldShard.length + " bytes");
                }
                stored = Arrays.copyOfRange(oldShard, (int) offset, (int) (offset + length));
            }
            int[][] overlap = Pipelines.intersect(origin, subChunkShape, regionOrigin, regionShape);
            if (overlap == null) {
                return stored; // untouched: keep its bytes (or its absence)
            }
            int[] local = Pipelines.minus(overlap[0], origin);
            boolean whole = Arrays.equals(overlap[1], subChunkShape);
            if (!whole && inner.canUpdateShard()) {
                // A nested shard: rewrite only the inner sub-chunks the write touches.
                byte[] part = new byte[emptySub.length]; // read only inside the written region
                Pipelines.copyBox(chunk, shape, overlap[0], part, subChunkShape, local, overlap[1], elementSize);
                return inner.updateShard(stored, part, fillElement, local, overlap[1], writeEmptyChunks);
            }
            byte[] sub;
            if (whole) {
                sub = new byte[emptySub.length]; // wholly rewritten
            } else if (stored == null) {
                sub = emptySub.clone();
            } else {
                sub = present(inner.decodeChunk(ChunkBytes.of(stored), fillElement, zero, subChunkShape));
            }
            Pipelines.copyBox(chunk, shape, overlap[0], sub, subChunkShape, local, overlap[1], elementSize);
            return !writeEmptyChunks && Arrays.equals(sub, emptySub) ? null
                    : inner.encode(sub, fillElement, writeEmptyChunks);
        });
    }

    /**
     * Lays out a shard from each sub-chunk's payload: the payloads in order, and the index before or after
     * them.
     *
     * @return the shard, or {@code null} if every sub-chunk is empty
     */
    private byte[] assemble(Payloads payloads) {
        int rank = subChunkShape.length;
        long[] offsets = new long[subChunkCount];
        long[] lengths = new long[subChunkCount];
        List<byte[]> stored = new ArrayList<>(subChunkCount);
        long cursor = indexAtStart ? encodedIndexSize : 0;
        int[] coord = new int[rank];
        for (int linear = 0; linear < subChunkCount; linear++) {
            int[] origin = new int[rank];
            for (int i = 0; i < rank; i++) {
                origin[i] = coord[i] * subChunkShape[i];
            }
            byte[] payload = payloads.payload(linear, origin);
            if (payload == null) {
                offsets[linear] = EMPTY;
                lengths[linear] = EMPTY;
            } else {
                offsets[linear] = cursor;
                lengths[linear] = payload.length;
                cursor += payload.length;
                stored.add(payload);
            }
            for (int i = rank - 1; i >= 0; i--) {
                if (++coord[i] < subGridShape[i]) {
                    break;
                }
                coord[i] = 0;
            }
        }
        if (stored.isEmpty()) {
            return null;
        }
        return layout(offsets, lengths, stored, cursor);
    }

    /** A shard whose every sub-chunk is empty: just the index. */
    private byte[] assembleEmpty() {
        long[] empty = new long[subChunkCount];
        Arrays.fill(empty, EMPTY);
        return layout(empty, empty, List.of(), indexAtStart ? encodedIndexSize : 0);
    }

    private byte[] layout(long[] offsets, long[] lengths, List<byte[]> payloads, long cursor) {
        ByteBuffer entries = ByteBuffer.allocate(subChunkCount * 16).order(index.elementOrder());
        for (int i = 0; i < subChunkCount; i++) {
            entries.putLong(offsets[i]);
            entries.putLong(lengths[i]);
        }
        byte[] indexBytes = index.encode(entries.array(), new byte[8]);
        if (indexBytes.length != encodedIndexSize) {
            throw new ZarrFormatException("shard index encoded to " + indexBytes.length
                    + " bytes, expected " + encodedIndexSize);
        }
        long dataBytes = cursor - (indexAtStart ? encodedIndexSize : 0);
        if (dataBytes + encodedIndexSize > Integer.MAX_VALUE) {
            throw new ZarrUnsupportedException("a shard of " + (dataBytes + encodedIndexSize)
                    + " bytes is larger than one array holds");
        }
        byte[] shard = new byte[(int) (dataBytes + encodedIndexSize)];
        int position = indexAtStart ? (int) encodedIndexSize : 0;
        for (byte[] payload : payloads) {
            System.arraycopy(payload, 0, shard, position, payload.length);
            position += payload.length;
        }
        System.arraycopy(indexBytes, 0, shard, indexAtStart ? 0 : (int) dataBytes, indexBytes.length);
        return shard;
    }

    // ---- parsing helpers ------------------------------------------------------------------------------

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

    /** The codec specs of a list; a bare name (the spec's shorthand) stands for a codec with no configuration. */
    private static List<JsonObject> objects(JsonArray array) {
        List<JsonObject> out = new ArrayList<>(array.size());
        for (JsonValue v : array.values()) {
            out.add(v instanceof JsonString name ? JsonObject.builder().put("name", name.value()).build() : v.asObject());
        }
        return out;
    }
}
