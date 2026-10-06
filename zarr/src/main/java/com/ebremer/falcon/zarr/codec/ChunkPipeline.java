package com.ebremer.falcon.zarr.codec;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
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
 * <p>Variable-length elements take the same chain with an {@code Object[]} for the array (a
 * {@code String[]} for strings, a {@code byte[][]} for byte strings): the array&rarr;bytes codec is
 * {@code vlen-utf8} or {@code vlen-bytes} ({@link VlenCodec}), or {@code sharding_indexed} whose
 * sub-chunks use it, and {@code transpose} may come before either ({@link #decodeVlenChunk},
 * {@link #encodeVlen}).
 *
 * <p>Every bytes&rarr;bytes codec decodes against a limit derived from the chunk's size (H1), so a few
 * bytes of corrupt input cannot claim gigabytes of output. Only a variable-length chunk, whose decoded
 * size is not known in advance, is limited by the size of a Java array alone.
 */
public final class ChunkPipeline {

    /** The most bytes one Java array holds. */
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    private final DataType dataType;
    private final int[] chunkShape;
    private final List<ArrayArrayCodec> arrayCodecs;
    private final ArrayBytesCodec bytesCodec; // null when the array->bytes codec is variable-length
    private final VlenCodec vlen;             // the array->bytes codec when it is variable-length, else null
    private final List<BytesBytesCodec> byteCodecs;
    private final int[] boundaryShape; // chunk shape at the array->bytes boundary (after array->array encode)
    private final long[] decodeLimits; // per bytes->bytes codec: the most bytes its decode may produce
    private final long maxEncodedLength;

    private ChunkPipeline(DataType dataType, int[] chunkShape, List<ArrayArrayCodec> arrayCodecs,
                          ArrayBytesCodec bytesCodec, VlenCodec vlen, List<BytesBytesCodec> byteCodecs,
                          int[] boundaryShape) {
        this.dataType = dataType;
        this.chunkShape = chunkShape;
        this.arrayCodecs = arrayCodecs;
        this.bytesCodec = bytesCodec;
        this.vlen = vlen;
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
        VlenCodec vlen = null;
        List<BytesBytesCodec> byteCodecs = new ArrayList<>();
        int[] boundaryShape = shape;

        try {
            for (JsonObject spec : codecSpecs) {
                String name = spec.get("name").asString();
                JsonObject config = spec.find("configuration")
                        .map(JsonValue::asObject).orElse(EMPTY_CONFIG);
                boolean arrayBytesSet = bytesCodec != null || vlen != null;
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
                            throw new ZarrFormatException("the '" + dataType.name() + "' data type requires the '"
                                    + VlenCodec.of(dataType).codecName() + "' codec, not 'bytes'");
                        }
                        bytesCodec = BytesCodec.parse(config, dataType);
                    }
                    case "vlen-utf8", "vlen-bytes" -> {
                        if (arrayBytesSet) {
                            throw new ZarrFormatException("more than one array->bytes codec");
                        }
                        VlenCodec codec = VlenCodec.of(dataType);
                        if (codec == null || !codec.codecName().equals(name)) {
                            throw new ZarrFormatException("the '" + name + "' codec requires the '"
                                    + (name.equals("vlen-utf8") ? DataType.STRING : DataType.BYTES).name()
                                    + "' data type, not '" + dataType.name() + "'");
                        }
                        vlen = codec;
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

        if (bytesCodec == null && vlen == null) {
            throw new ZarrFormatException("codec pipeline has no array->bytes codec");
        }
        return new ChunkPipeline(dataType, shape, List.copyOf(arrayCodecs), bytesCodec, vlen,
                List.copyOf(byteCodecs), boundaryShape);
    }

    private static void requireBytesCodec(boolean arrayBytesSet, String name) {
        if (!arrayBytesSet) {
            throw new ZarrFormatException(
                    "bytes->bytes codec '" + name + "' appears before the array->bytes codec");
        }
    }

    /** The byte order of each decoded primitive (the {@code bytes} codec's endian, or LE for vlen elements). */
    public ByteOrder elementOrder() {
        return bytesCodec == null ? ByteOrder.LITTLE_ENDIAN : bytesCodec.elementByteOrder();
    }

    /** Whether the array&rarr;bytes codec is {@code vlen-utf8} or {@code vlen-bytes} (an {@code Object[]} chunk). */
    public boolean isVlen() {
        return vlen != null;
    }

    /**
     * The variable-length codec of this pipeline's elements, whether it is the array&rarr;bytes codec or a
     * shard's sub-chunks use it; {@code null} for a fixed-size data type.
     */
    public VlenCodec vlenCodec() {
        return VlenCodec.of(dataType);
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
        return encode(elements, fillElement, false);
    }

    /**
     * {@link #encode(byte[], byte[])}, where {@code writeEmptyChunks} keeps a shard's all-fill sub-chunks
     * (F7): each is stored rather than omitted.
     */
    public byte[] encode(byte[] elements, byte[] fillElement, boolean writeEmptyChunks) {
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
        byte[] bytes = bytesCodec.encode(array, elementSize, fillElement, writeEmptyChunks);
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
     * @param oldShard         the shard's stored bytes, or {@code null} if it is absent
     * @param writeEmptyChunks whether a touched sub-chunk holding only the fill value is stored (F7) rather
     *                         than omitted
     * @return the shard to store, or {@code null} if it now holds only the fill value
     * @throws IllegalStateException if {@link #canUpdateShard()} is false
     */
    public byte[] updateShard(byte[] oldShard, byte[] chunk, byte[] fillElement, int[] regionOrigin,
                              int[] regionShape, boolean writeEmptyChunks) {
        if (!canUpdateShard()) {
            throw new IllegalStateException("updateShard needs a shard with no other codecs");
        }
        return ((ShardingCodec) bytesCodec).update(oldShard, chunk, chunkShape, dataType.byteCount(),
                fillElement, regionOrigin, regionShape, writeEmptyChunks);
    }

    private void requireFixedSize() {
        if (dataType.isVariableLength()) {
            throw new IllegalStateException("the '" + dataType.name()
                    + "' pipeline holds variable-length elements, not fixed-size ones");
        }
    }

    // ---- variable-length elements --------------------------------------------------------------------

    /**
     * Decodes a variable-length chunk read through {@code source}: the bytes&rarr;bytes codecs are undone,
     * then {@code vlen-utf8}/{@code vlen-bytes} (or the shard of such sub-chunks), then any
     * {@code transpose}. In a shard without a transpose, only the sub-chunks overlapping the region are
     * fetched; every other element is the {@code fill} object itself.
     *
     * @param fill the fill element ({@link VlenCodec#fill}): a {@code String} or a {@code byte[]}
     * @return the chunk's elements in C order, a {@code String[]} or a {@code byte[][]}, or {@code null} if
     *         the chunk is absent from the store
     * @throws ZarrFormatException if the bytes are malformed
     */
    public Object[] decodeVlenChunk(ChunkBytes source, Object fill, int[] regionOrigin, int[] regionShape) {
        requireVlen();
        ChunkBytes effective = source;
        if (!byteCodecs.isEmpty()) {
            byte[] bytes = source.readAll().orElse(null);
            if (bytes == null) {
                return null;
            }
            effective = ChunkBytes.of(undoBytesCodecs(bytes));
        }
        Object[] array;
        if (bytesCodec == null) {
            byte[] bytes = effective.readAll().orElse(null);
            if (bytes == null) {
                return null;
            }
            array = vlen.decode(bytes);
            int expected = Pipelines.elementCount(boundaryShape);
            if (array.length != expected) {
                throw new ZarrFormatException("decoded " + vlen.codecName() + " chunk has " + array.length
                        + " elements, expected " + expected);
            }
        } else {
            boolean wholeChunk = !arrayCodecs.isEmpty(); // as in decodeChunk: a transpose moves the region
            array = ((ShardingCodec) bytesCodec).decodeVlen(effective, boundaryShape, fill,
                    wholeChunk ? new int[boundaryShape.length] : regionOrigin,
                    wholeChunk ? boundaryShape : regionShape);
            if (array == null) {
                return null;
            }
        }
        int[] shape = boundaryShape;
        for (int i = arrayCodecs.size() - 1; i >= 0; i--) {
            ArrayArrayCodec codec = arrayCodecs.get(i);
            array = codec.decodeObjects(array, shape);
            shape = codec.decodedShape(shape);
        }
        return array;
    }

    /**
     * Decodes a variable-length chunk's stored bytes into its {@code elementCount} elements in C order, an
     * empty element standing in for the fill value.
     *
     * @throws ZarrFormatException if the pipeline is not variable-length, or the bytes are malformed
     */
    public Object[] decodeVlen(byte[] stored, int elementCount) {
        requireVlen();
        Object fill = vlenCodec().fill(new JsonString(""));
        Object[] elements = decodeVlenChunk(ChunkBytes.of(stored), fill, new int[chunkShape.length], chunkShape);
        if (elements.length != elementCount) {
            throw new ZarrFormatException("decoded " + vlenCodec().codecName() + " chunk has " + elements.length
                    + " elements, expected " + elementCount);
        }
        return elements;
    }

    /** {@link #decodeVlen} for a {@code vlen-utf8} pipeline. */
    public String[] decodeStrings(byte[] stored, int elementCount) {
        requireStrings();
        return (String[]) decodeVlen(stored, elementCount);
    }

    /**
     * Encodes a variable-length chunk (a flat C-order {@code String[]} or {@code byte[][]}) into the bytes to
     * store: any {@code transpose}, then {@code vlen-utf8}/{@code vlen-bytes} (or a shard of it, omitting
     * sub-chunks that hold only {@code fill} unless {@code writeEmptyChunks}), then the bytes&rarr;bytes
     * codecs in order. A {@code null} element is written as an empty one.
     */
    public byte[] encodeVlen(Object[] elements, Object fill, boolean writeEmptyChunks) {
        requireVlen();
        int expected = Pipelines.elementCount(chunkShape);
        if (elements.length != expected) {
            throw new ZarrFormatException(
                    "variable-length chunk has " + elements.length + " elements, expected " + expected);
        }
        Object[] array = elements;
        int[] shape = chunkShape;
        for (ArrayArrayCodec codec : arrayCodecs) {
            array = codec.encodeObjects(array, shape);
            shape = codec.encodedShape(shape);
        }
        byte[] bytes = bytesCodec == null ? vlen.encode(array)
                : ((ShardingCodec) bytesCodec).encodeVlen(array, shape, fill, writeEmptyChunks);
        for (BytesBytesCodec codec : byteCodecs) {
            bytes = codec.encode(bytes);
        }
        return bytes;
    }

    /** {@link #encodeVlen} for a {@code vlen-utf8} pipeline, with the empty string as the fill value. */
    public byte[] encodeStrings(String[] elements) {
        requireStrings();
        return encodeVlen(elements, "", false);
    }

    private void requireVlen() {
        if (!dataType.isVariableLength()) {
            throw new IllegalStateException("the variable-length methods need a variable-length data type, not '"
                    + dataType.name() + "'");
        }
    }

    private void requireStrings() {
        if (vlenCodec() != VlenCodec.UTF8) {
            throw new IllegalStateException("the string methods need the 'string' data type, not '"
                    + dataType.name() + "'");
        }
    }

    private static final JsonObject EMPTY_CONFIG = new JsonObject(java.util.Map.of());
}
