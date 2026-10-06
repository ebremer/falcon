package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A chunk codec pipeline: the ordered codec chain that turns a chunk's stored bytes into its decoded
 * elements. A valid Zarr v3 pipeline is {@code (array→array)* (array→bytes) (bytes→bytes)*} with exactly
 * one array&rarr;bytes codec; {@link #of} validates that structure.
 *
 * <p>{@link #decode} inverts the chain: the bytes&rarr;bytes codecs are undone in reverse order, the
 * array&rarr;bytes codec reshapes the flat buffer, and the array&rarr;array codecs are undone in reverse
 * order. The result is a flat buffer of the chunk's elements in C order, each primitive in
 * {@link #elementOrder()}.
 *
 * <p>Variable-length strings take the same chain with a {@code String[]} for the array: the
 * array&rarr;bytes codec is {@code vlen-utf8}, or {@code sharding_indexed} whose sub-chunks use it, and
 * {@code transpose} may come before either ({@link #decodeStringChunk}, {@link #encodeStrings}).
 *
 * <p>Every bytes&rarr;bytes codec decodes against a limit derived from the chunk's size (H1), so a few
 * bytes of corrupt input cannot claim gigabytes of output. Only a {@code vlen-utf8} chunk, whose decoded
 * size is not known in advance, is limited by the size of a Java array alone.
 */
public final class ChunkPipeline {

    /** The most bytes one Java array holds. */
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    private final DataType dataType;
    private final int[] chunkShape;
    private final List<ArrayArrayCodec> arrayCodecs;
    private final ArrayBytesCodec bytesCodec; // null when the array->bytes codec is vlen-utf8
    private final List<BytesBytesCodec> byteCodecs;
    private final int[] boundaryShape; // chunk shape at the array->bytes boundary (after array->array encode)
    private final long[] decodeLimits; // per bytes->bytes codec: the most bytes its decode may produce
    private final long maxEncodedLength;

    private ChunkPipeline(DataType dataType, int[] chunkShape, List<ArrayArrayCodec> arrayCodecs,
                          ArrayBytesCodec bytesCodec, List<BytesBytesCodec> byteCodecs, int[] boundaryShape) {
        this.dataType = dataType;
        this.chunkShape = chunkShape;
        this.arrayCodecs = arrayCodecs;
        this.bytesCodec = bytesCodec;
        this.byteCodecs = byteCodecs;
        this.boundaryShape = boundaryShape;
        // The encode chain grows the array->bytes output stage by stage; decoding stage i may produce at
        // most what stage i was given when encoding.
        long limit = bytesCodec == null ? Long.MAX_VALUE : bytesCodec.maxEncodedSize(boundaryShape, dataType.byteCount());
        this.decodeLimits = new long[byteCodecs.size()];
        for (int i = 0; i < byteCodecs.size(); i++) {
            decodeLimits[i] = limit;
            limit = byteCodecs.get(i).maxEncodedSize(limit);
        }
        this.maxEncodedLength = limit;
    }

    /**
     * Builds the pipeline for {@code chunkShape} elements of {@code dataType} from the array metadata's
     * codec specs (each a {@code {"name", "configuration"?}} object, in pipeline order).
     *
     * @throws ZarrFormatException      if the codec order is invalid or a configuration is malformed
     * @throws ZarrUnsupportedException if a codec is not yet implemented
     */
    public static ChunkPipeline of(DataType dataType, long[] chunkShape, List<JsonObject> codecSpecs) {
        int[] shape = Pipelines.toIntShape(chunkShape);
        if (!dataType.isVariableLength()
                && (long) Pipelines.elementCount(shape) * dataType.byteCount() > Integer.MAX_VALUE) {
            // A decoded chunk is one Java array; every size computed from it below then fits in an int.
            throw new ZarrFormatException("a chunk of " + Arrays.toString(chunkShape) + " " + dataType.name()
                    + " elements is larger than the 2 GB a single buffer holds");
        }
        List<ArrayArrayCodec> arrayCodecs = new ArrayList<>();
        ArrayBytesCodec bytesCodec = null;
        boolean vlen = false;
        List<BytesBytesCodec> byteCodecs = new ArrayList<>();
        int[] boundaryShape = shape;

        try {
            for (JsonObject spec : codecSpecs) {
                String name = spec.get("name").asString();
                JsonObject config = spec.find("configuration")
                        .map(JsonValue::asObject).orElse(EMPTY_CONFIG);
                boolean arrayBytesSet = bytesCodec != null || vlen;
                switch (name) {
                    case "transpose" -> {
                        if (arrayBytesSet) {
                            throw new ZarrFormatException(
                                    "array->array codec 'transpose' appears after the array->bytes codec");
                        }
                        TransposeCodec codec = TransposeCodec.parse(config, boundaryShape.length);
                        arrayCodecs.add(codec);
                        boundaryShape = codec.encodedShape(boundaryShape);
                    }
                    case "bytes" -> {
                        if (arrayBytesSet) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        if (dataType.isVariableLength()) {
                            throw new ZarrFormatException(
                                    "the '" + dataType.name() + "' data type requires the 'vlen-utf8' codec, not 'bytes'");
                        }
                        bytesCodec = BytesCodec.parse(config, dataType);
                    }
                    case "vlen-utf8" -> {
                        if (arrayBytesSet) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        if (!dataType.isVariableLength()) {
                            throw new ZarrFormatException(
                                    "the 'vlen-utf8' codec requires a variable-length data type, not '"
                                            + dataType.name() + "'");
                        }
                        vlen = true;
                    }
                    case "gzip" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(GzipCodec.parse(config));
                    }
                    case "crc32c" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(new Crc32cCodec());
                    }
                    case "sharding_indexed" -> {
                        if (arrayBytesSet) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        bytesCodec = ShardingCodec.parse(config, dataType, boundaryShape);
                    }
                    case "zstd" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(ZstdCodec.parse(config));
                    }
                    case "blosc" -> {
                        requireBytesCodec(arrayBytesSet, name);
                        byteCodecs.add(BloscCodec.parse(config, dataType.byteCount()));
                    }
                    default -> throw new ZarrUnsupportedException("unknown codec: '" + name + "'");
                }
            }
        } catch (JsonException e) {
            throw new ZarrFormatException("invalid codec configuration: " + e.getMessage(), e);
        }

        if (bytesCodec == null && !vlen) {
            throw new ZarrFormatException("codec pipeline has no array->bytes codec");
        }
        return new ChunkPipeline(dataType, shape, List.copyOf(arrayCodecs), bytesCodec,
                List.copyOf(byteCodecs), boundaryShape);
    }

    private static void requireBytesCodec(boolean arrayBytesSet, String name) {
        if (!arrayBytesSet) {
            throw new ZarrFormatException(
                    "bytes->bytes codec '" + name + "' appears before the array->bytes codec");
        }
    }

    /** The byte order of each decoded primitive (the {@code bytes} codec's endian, or LE for vlen strings). */
    public ByteOrder elementOrder() {
        return bytesCodec == null ? ByteOrder.LITTLE_ENDIAN : bytesCodec.elementByteOrder();
    }

    /** Whether the array&rarr;bytes codec is {@code vlen-utf8} (a {@code String[]} chunk). */
    public boolean isVlen() {
        return bytesCodec == null;
    }

    /** Whether the array&rarr;bytes codec is {@code sharding_indexed}. */
    public boolean isSharded() {
        return bytesCodec instanceof ShardingCodec;
    }

    /** The element data type. */
    public DataType dataType() {
        return dataType;
    }

    /** The encoded size of {@code rawLength} bytes after this pipeline's bytes&rarr;bytes codecs. */
    long encodedLength(long rawLength) {
        long length = rawLength;
        for (BytesBytesCodec codec : byteCodecs) {
            length = codec.encodedSize(length);
        }
        return length;
    }

    /** An upper bound on a stored chunk's size: the limit for decoding a codec this pipeline sits under. */
    long maxEncodedLength() {
        return maxEncodedLength;
    }

    /** Undoes the bytes&rarr;bytes codecs, each against its limit. */
    private byte[] undoBytesCodecs(byte[] stored) {
        byte[] bytes = stored;
        for (int i = byteCodecs.size() - 1; i >= 0; i--) {
            bytes = byteCodecs.get(i).decode(bytes, (int) Math.min(decodeLimits[i], MAX_ARRAY));
        }
        return bytes;
    }

    // ---- fixed-size elements --------------------------------------------------------------------------

    /**
     * Encodes a chunk's elements (a flat C-order buffer, each primitive in {@link #elementOrder()}) into
     * the bytes to store: array&rarr;array codecs in order, then the array&rarr;bytes codec, then the
     * bytes&rarr;bytes codecs in order. {@code fillElement} lets a shard omit all-fill sub-chunks.
     */
    public byte[] encode(byte[] elements, byte[] fillElement) {
        requireFixedSize();
        int elementSize = dataType.byteCount();
        long expected = (long) Pipelines.elementCount(chunkShape) * elementSize;
        if (elements.length != expected) {
            throw new ZarrFormatException(
                    "chunk is " + elements.length + " bytes, expected " + expected);
        }
        ArrayValue array = new ArrayValue(elements, chunkShape);
        for (ArrayArrayCodec codec : arrayCodecs) {
            array = codec.encode(array, elementSize);
        }
        byte[] bytes = bytesCodec.encode(array, elementSize, fillElement);
        for (BytesBytesCodec codec : byteCodecs) {
            bytes = codec.encode(bytes);
        }
        return bytes;
    }

    /**
     * Decodes a chunk's stored bytes into its elements: a flat buffer of the whole chunk's elements in C
     * order, each primitive in {@link #elementOrder()}. Empty sub-chunks of a shard decode as zeros; use
     * {@link #decodeChunk} to supply the array's fill value.
     *
     * @throws ZarrFormatException if the bytes are truncated, fail a checksum, or otherwise malformed
     */
    public byte[] decode(byte[] stored) {
        byte[] decoded = decodeChunk(ChunkBytes.of(stored), new byte[dataType.byteCount()],
                new int[chunkShape.length], chunkShape);
        if (decoded == null) {
            throw new ZarrFormatException("chunk bytes were unexpectedly absent");
        }
        return decoded;
    }

    /**
     * Decodes the chunk read through {@code source}, returning its elements in C order.
     *
     * <p>{@code regionOrigin}/{@code regionShape} name the part of the chunk the caller needs: with a
     * sharding codec and no other array-stage codecs, only the sub-chunks overlapping that region are
     * fetched and decoded, and the rest of the returned buffer holds {@code fillElement}. Callers must
     * not read outside the region they asked for.
     *
     * @return the chunk's elements, or {@code null} if the chunk is absent from the store
     */
    public byte[] decodeChunk(ChunkBytes source, byte[] fillElement, int[] regionOrigin, int[] regionShape) {
        requireFixedSize();
        ChunkBytes effective = source;
        if (!byteCodecs.isEmpty()) {
            byte[] bytes = source.readAll().orElse(null);
            if (bytes == null) {
                return null;
            }
            effective = ChunkBytes.of(undoBytesCodecs(bytes));
        }
        // A transpose between the chunk and the bytes permutes axes, so a region expressed in logical
        // coordinates does not map onto the encoded layout: decode the whole chunk in that case.
        boolean wholeChunk = !arrayCodecs.isEmpty();
        int[] origin = wholeChunk ? new int[boundaryShape.length] : regionOrigin;
        int[] extent = wholeChunk ? boundaryShape : regionShape;

        int elementSize = dataType.byteCount();
        ArrayValue array = bytesCodec.decode(effective, boundaryShape, elementSize, fillElement, origin, extent);
        if (array == null) {
            return null;
        }
        for (int i = arrayCodecs.size() - 1; i >= 0; i--) {
            array = arrayCodecs.get(i).decode(array, elementSize);
        }
        if (!Arrays.equals(array.shape, chunkShape)) {
            throw new ZarrFormatException("decoded chunk shape " + Arrays.toString(array.shape)
                    + " does not match " + Arrays.toString(chunkShape));
        }
        return array.data;
    }

    /**
     * Decodes only {@code [regionOrigin, regionOrigin + regionShape)} of the chunk, as a buffer of the
     * region's shape (PF1). A shard with nothing around it fetches and allocates no more than the region
     * needs; any other chunk is decoded whole and the region cut out of it.
     *
     * @return the region's elements in C order, or {@code null} if the chunk is absent from the store
     */
    public byte[] decodeRegion(ChunkBytes source, byte[] fillElement, int[] regionOrigin, int[] regionShape) {
        requireFixedSize();
        int elementSize = dataType.byteCount();
        if (bytesCodec instanceof ShardingCodec sharding && arrayCodecs.isEmpty() && byteCodecs.isEmpty()) {
            return sharding.decodeRegion(source, elementSize, fillElement, regionOrigin, regionShape);
        }
        byte[] chunk = decodeChunk(source, fillElement, regionOrigin, regionShape);
        if (chunk == null || Arrays.equals(regionShape, chunkShape)) {
            return chunk;
        }
        byte[] out = new byte[Pipelines.elementCount(regionShape) * elementSize];
        Pipelines.copyBox(chunk, chunkShape, regionOrigin, out, regionShape, new int[regionShape.length],
                regionShape, elementSize);
        return out;
    }

    /**
     * Whether {@link #updateShard} applies: the chunk is a shard with no codec before or after the
     * sharding codec, so its sub-chunks can be rewritten one by one.
     */
    public boolean canUpdateShard() {
        return bytesCodec instanceof ShardingCodec && arrayCodecs.isEmpty() && byteCodecs.isEmpty()
                && !dataType.isVariableLength();
    }

    /**
     * Rewrites a stored shard after a write to {@code [regionOrigin, regionOrigin + regionShape)} of it,
     * re-encoding only the sub-chunks the region touches (PF2). {@code chunk} is a whole-chunk buffer that
     * holds the new elements in the region; it is not read outside it.
     *
     * @param oldShard the shard's stored bytes, or {@code null} if it is absent
     * @return the shard to store, or {@code null} if it now holds only the fill value
     * @throws IllegalStateException if {@link #canUpdateShard()} is false
     */
    public byte[] updateShard(byte[] oldShard, byte[] chunk, byte[] fillElement, int[] regionOrigin,
                              int[] regionShape) {
        if (!canUpdateShard()) {
            throw new IllegalStateException("updateShard needs a shard with no other codecs");
        }
        return ((ShardingCodec) bytesCodec).update(oldShard, chunk, chunkShape, dataType.byteCount(),
                fillElement, regionOrigin, regionShape);
    }

    private void requireFixedSize() {
        if (dataType.isVariableLength()) {
            throw new IllegalStateException("the '" + dataType.name() + "' pipeline holds strings, not bytes");
        }
    }

    // ---- variable-length strings ---------------------------------------------------------------------

    /**
     * Decodes a variable-length string chunk read through {@code source}: the bytes&rarr;bytes codecs are
     * undone, then {@code vlen-utf8} (or the shard of {@code vlen-utf8} sub-chunks), then any
     * {@code transpose}. In a shard without a transpose, only the sub-chunks overlapping the region are
     * fetched; every other element is {@code fill}.
     *
     * @return the chunk's elements in C order, or {@code null} if the chunk is absent from the store
     * @throws ZarrFormatException if the bytes are malformed
     */
    public String[] decodeStringChunk(ChunkBytes source, String fill, int[] regionOrigin, int[] regionShape) {
        requireStrings();
        ChunkBytes effective = source;
        if (!byteCodecs.isEmpty()) {
            byte[] bytes = source.readAll().orElse(null);
            if (bytes == null) {
                return null;
            }
            effective = ChunkBytes.of(undoBytesCodecs(bytes));
        }
        String[] array;
        if (bytesCodec == null) {
            byte[] bytes = effective.readAll().orElse(null);
            if (bytes == null) {
                return null;
            }
            array = VlenUtf8.decode(bytes);
            int expected = Pipelines.elementCount(boundaryShape);
            if (array.length != expected) {
                throw new ZarrFormatException("decoded string chunk has " + array.length
                        + " elements, expected " + expected);
            }
        } else {
            boolean wholeChunk = !arrayCodecs.isEmpty(); // as in decodeChunk: a transpose moves the region
            array = ((ShardingCodec) bytesCodec).decodeStrings(effective, boundaryShape, fill,
                    wholeChunk ? new int[boundaryShape.length] : regionOrigin,
                    wholeChunk ? boundaryShape : regionShape);
            if (array == null) {
                return null;
            }
        }
        int[] shape = boundaryShape;
        for (int i = arrayCodecs.size() - 1; i >= 0; i--) {
            ArrayArrayCodec codec = arrayCodecs.get(i);
            array = codec.decodeStrings(array, shape);
            shape = codec.decodedShape(shape);
        }
        return array;
    }

    /**
     * Decodes a {@code vlen-utf8} string chunk's stored bytes into its {@code elementCount} elements in C
     * order.
     *
     * @throws ZarrFormatException if the pipeline is not a string pipeline, or the bytes are malformed
     */
    public String[] decodeStrings(byte[] stored, int elementCount) {
        String[] elements = decodeStringChunk(ChunkBytes.of(stored), "", new int[chunkShape.length], chunkShape);
        if (elements.length != elementCount) {
            throw new ZarrFormatException("decoded string chunk has " + elements.length
                    + " elements, expected " + elementCount);
        }
        return elements;
    }

    /**
     * Encodes a variable-length string chunk (a flat C-order {@code String[]}) into the bytes to store:
     * any {@code transpose}, then {@code vlen-utf8} (or a shard of it, omitting sub-chunks that hold only
     * {@code fill}), then the bytes&rarr;bytes codecs in order. A {@code null} element is written as "".
     */
    public byte[] encodeStrings(String[] elements, String fill) {
        requireStrings();
        int expected = Pipelines.elementCount(chunkShape);
        if (elements.length != expected) {
            throw new ZarrFormatException(
                    "string chunk has " + elements.length + " elements, expected " + expected);
        }
        String[] array = elements;
        int[] shape = chunkShape;
        for (ArrayArrayCodec codec : arrayCodecs) {
            array = codec.encodeStrings(array, shape);
            shape = codec.encodedShape(shape);
        }
        byte[] bytes = bytesCodec == null ? VlenUtf8.encode(array)
                : ((ShardingCodec) bytesCodec).encodeStrings(array, shape, fill);
        for (BytesBytesCodec codec : byteCodecs) {
            bytes = codec.encode(bytes);
        }
        return bytes;
    }

    /** {@link #encodeStrings(String[], String)} with the empty string as the fill value. */
    public byte[] encodeStrings(String[] elements) {
        return encodeStrings(elements, "");
    }

    private void requireStrings() {
        if (!dataType.isVariableLength()) {
            throw new IllegalStateException("the string methods need a variable-length data type, not '"
                    + dataType.name() + "'");
        }
    }

    private static final JsonObject EMPTY_CONFIG = new JsonObject(java.util.Map.of());
}
