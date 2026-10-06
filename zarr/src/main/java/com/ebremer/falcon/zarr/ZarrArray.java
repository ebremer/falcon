package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.data.ChunkAssembler;
import com.ebremer.falcon.zarr.data.ChunkCache;
import com.ebremer.falcon.zarr.data.Resize;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.metadata.Metadata;
import com.ebremer.falcon.zarr.metadata.NodeMetadata;
import com.ebremer.falcon.zarr.metadata.V2Metadata;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An array in a Zarr hierarchy: an N-dimensional grid of elements of one data type, stored in chunks.
 *
 * <p>A handle holds the array's metadata (shape, data type, chunk shape, fill value, codecs, dimension
 * names) as read when it was opened. Its reads and writes go to the store's chunks each time, so a read
 * sees every write made before it, through any handle. A handle from {@link #withChunkCache(long)} instead
 * keeps decoded chunks in memory: it sees its own writes, but not writes made through other handles or
 * processes until {@link #clearChunkCache()}.
 *
 * <p>A chunk that a write leaves holding only the fill value is deleted rather than stored, as Zarr
 * represents empty chunks; a handle from {@link #withWriteEmptyChunks(boolean)} stores it instead.
 */
public final class ZarrArray extends ZarrNode {

    private final ArrayMetadata metadata;
    private final ChunkCache chunkCache;     // decoded-chunk LRU, or null: see withChunkCache
    private final boolean writeEmptyChunks;  // see withWriteEmptyChunks

    ZarrArray(Store store, String path, ArrayMetadata metadata) {
        this(store, path, metadata, null, false);
    }

    private ZarrArray(Store store, String path, ArrayMetadata metadata, ChunkCache chunkCache,
                      boolean writeEmptyChunks) {
        super(store, path);
        this.metadata = metadata;
        this.chunkCache = chunkCache;
        this.writeEmptyChunks = writeEmptyChunks;
    }

    @Override
    public boolean isGroup() {
        return false;
    }

    @Override
    public JsonObject attributes() {
        return metadata.attributes();
    }

    /** {@inheritDoc} The new handle keeps this handle's options, such as its chunk cache. */
    @Override
    public ZarrArray setAttributes(JsonObject attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return withMetadata((ArrayMetadata) rewriteAttributes(current -> attributes));
    }

    /** {@inheritDoc} The new handle keeps this handle's options, such as its chunk cache. */
    @Override
    public ZarrArray updateAttributes(JsonObject changes) {
        Objects.requireNonNull(changes, "changes");
        return withMetadata((ArrayMetadata) rewriteAttributes(current -> merge(current, changes)));
    }

    /** The array shape (a defensive copy). */
    public long[] shape() {
        return metadata.shape();
    }

    /** The number of dimensions. */
    public int rank() {
        return metadata.rank();
    }

    /** The chunk shape (a defensive copy); same rank as {@link #shape()}. */
    public long[] chunkShape() {
        return metadata.chunkShape();
    }

    /** The number of chunks along each dimension (a defensive copy): {@code ceil(shape / chunkShape)}. */
    public long[] gridShape() {
        return metadata.grid().gridShape();
    }

    /** The total number of chunks in the grid. */
    public long chunkCount() {
        return metadata.grid().chunkCount();
    }

    /**
     * The store key of the chunk at the given grid coordinates, including this array's path prefix
     * (for example {@code "temperature/c/1/2"}).
     *
     * @throws IllegalArgumentException  if the wrong number of coordinates is given
     * @throws IndexOutOfBoundsException if a coordinate lies outside the chunk grid
     */
    public String chunkKey(long... coords) {
        metadata.grid().checkCoords(coords);
        String relative = metadata.chunkKeyEncoding().encode(coords);
        return path.isEmpty() ? relative : path + "/" + relative;
    }

    /** The element data type. */
    public DataType dataType() {
        return metadata.dataType();
    }

    /** The fill value, as raw JSON. See {@link #fillValueBytes(ByteOrder)} for the decoded element bytes. */
    public JsonValue fillValue() {
        return metadata.fillValue();
    }

    /** The fill value decoded to one element's bytes in the given order. */
    public byte[] fillValueBytes(ByteOrder order) {
        return metadata.fillValueBytes(order);
    }

    /** The codec names, in pipeline order. */
    public List<String> codecNames() {
        return metadata.codecNames();
    }

    /** The chunk key encoding name ({@code "default"} or {@code "v2"}). */
    public String chunkKeyEncoding() {
        return metadata.chunkKeyEncoding().name();
    }

    /** The chunk key separator ({@code "/"} or {@code "."}). */
    public String separator() {
        return metadata.separator();
    }

    /**
     * The dimension names if present; one entry per dimension, {@code null} for an unnamed dimension.
     */
    public Optional<List<String>> dimensionNames() {
        return metadata.dimensionNames();
    }

    /**
     * The total number of elements: the product of the shape (1 for a scalar array). Opening an array
     * whose element count overflows {@code long} fails, so this is exact.
     */
    public long size() {
        return metadata.grid().size();
    }

    /**
     * The hyperslab {@code [offset, offset+shape)} of this array.
     *
     * @throws IllegalArgumentException  if the rank is wrong
     * @throws IndexOutOfBoundsException if the region extends past the array
     */
    public Selection select(long[] offset, long[] shape) {
        ChunkAssembler.checkSelection(metadata, offset, shape);
        return new Selection(this, offset.clone(), shape.clone());
    }

    /** The whole array as a selection. */
    public Selection selectAll() {
        long[] shape = metadata.shape();
        return new Selection(this, new long[shape.length], shape);
    }

    /** Reads the whole array as {@code double}s (any numeric type, widened). */
    public double[] readDoubles() {
        return selectAll().readDoubles();
    }

    /** Reads the whole array as {@code float}s (float data types only). */
    public float[] readFloats() {
        return selectAll().readFloats();
    }

    /** Reads the whole array as {@code long}s (integer data types that fit). */
    public long[] readLongs() {
        return selectAll().readLongs();
    }

    /** Reads the whole array as {@code int}s (integer data types that fit). */
    public int[] readInts() {
        return selectAll().readInts();
    }

    /** Reads the whole array's raw decoded element bytes, in C order, in the array's byte order. */
    public byte[] readRawBytes() {
        return selectAll().readRawBytes();
    }

    /** Reads the whole array as {@code String}s, in C order (the {@code string} data type only). */
    public String[] readStrings() {
        return selectAll().readStrings();
    }

    /** Writes the whole array from {@code values} (the {@code string} data type only). */
    public void writeStrings(String[] values) {
        selectAll().writeStrings(values);
    }

    /**
     * Reads the whole array as byte strings, in C order (the {@code variable_length_bytes} data type only).
     */
    public byte[][] readByteArrays() {
        return selectAll().readByteArrays();
    }

    /** Writes the whole array from {@code values} (the {@code variable_length_bytes} data type only). */
    public void writeByteArrays(byte[][] values) {
        selectAll().writeByteArrays(values);
    }

    /** Writes the whole array from {@code values}, narrowing to this array's data type. */
    public void writeDoubles(double[] values) {
        selectAll().writeDoubles(values);
    }

    /** Writes the whole array from {@code values}, narrowing to this array's data type. */
    public void writeFloats(float[] values) {
        selectAll().writeFloats(values);
    }

    /** Writes the whole array from {@code values}, narrowing to this array's data type. */
    public void writeLongs(long[] values) {
        selectAll().writeLongs(values);
    }

    /** Writes the whole array from {@code values}, narrowing to this array's data type. */
    public void writeInts(int[] values) {
        selectAll().writeInts(values);
    }

    /** Writes the whole array from raw element bytes (C order, this array's byte order). */
    public void writeRawBytes(byte[] elements) {
        selectAll().writeRawBytes(elements);
    }

    /**
     * A selection per chunk, each covering that chunk's in-bounds region (edge chunks are clamped to the
     * array bound). Reading one block at a time streams an array whose whole contents would not fit in a
     * single Java array (a whole-array {@code readDoubles()} is capped near 2&nbsp;GB); the stream is lazy,
     * so blocks are produced without materializing them all.
     */
    public java.util.stream.Stream<Selection> blocks() {
        var grid = metadata.grid();
        return java.util.stream.LongStream.range(0, grid.chunkCount()).mapToObj(i -> {
            long[] coords = grid.chunkCoordsAt(i);
            return new Selection(this, grid.chunkOrigin(coords), grid.validExtent(coords));
        });
    }

    /**
     * A handle on this array that keeps up to {@code maxBytes} of decoded chunks in memory (least recently
     * used first out), so overlapping or repeated reads of a compressed array decode each chunk once. Each
     * call makes a new handle with its own empty cache.
     *
     * <p>Its writes update the cache, so its reads see them. Writes made through any other handle, or by
     * another process, are not seen while a chunk stays cached: call {@link #clearChunkCache()} to read
     * them. The handle may be shared between threads.
     *
     * @param maxBytes the cache's budget of decoded bytes; a chunk larger than it is never cached
     * @return a new handle on the same array, with its own cache
     * @throws IllegalArgumentException if {@code maxBytes} is not positive
     */
    public ZarrArray withChunkCache(long maxBytes) {
        return new ZarrArray(store, path, metadata, new ChunkCache(maxBytes), writeEmptyChunks);
    }

    /** Empties this handle's decoded-chunk cache; a handle without one has nothing to clear. */
    public void clearChunkCache() {
        if (chunkCache != null) {
            chunkCache.clear();
        }
    }

    /**
     * A handle on this array that, with {@code writeEmptyChunks}, stores a chunk a write leaves holding only
     * the fill value, where a handle normally deletes it (zarr-python's {@code write_empty_chunks}). In a
     * sharded array, each sub-chunk a write touches is then stored too. Reads are unaffected: an absent chunk
     * and a stored all-fill one read the same. Storing them costs space, but lets a store's listing show
     * every chunk written, and turns a later write of real data into a replacement rather than a creation.
     *
     * <p>The new handle keeps this one's other options, and shares its chunk cache if it has one.
     *
     * @return a new handle on the same array
     */
    public ZarrArray withWriteEmptyChunks(boolean writeEmptyChunks) {
        return new ZarrArray(store, path, metadata, chunkCache, writeEmptyChunks);
    }

    /** Whether this handle stores chunks that hold only the fill value; see {@link #withWriteEmptyChunks}. */
    public boolean writeEmptyChunks() {
        return writeEmptyChunks;
    }

    /**
     * Changes the array's shape, keeping its chunk shape, data, and everything else, and returns a handle on
     * the resized array (with this handle's options). The current shape is read from the store, not taken
     * from this handle, and the new one written back into the array's own metadata ({@code zarr.json}, or
     * a v2 array's {@code .zarray}); every other field of that document is kept as stored.
     *
     * <ul>
     *   <li><b>Shrinking</b> deletes every chunk wholly outside the new shape, one store delete per chunk in
     *       the removed part of the grid. Data in the part of an edge chunk past the new shape stays stored,
     *       out of sight.</li>
     *   <li><b>Growing</b> sets to the fill value the part of each old edge chunk that comes inside the
     *       array, so values cut off by an earlier shrink, by Falcon or by zarr-python (which leaves them
     *       too), never reappear. That rewrites the stored edge chunks when the old shape is not a multiple
     *       of the chunk shape; chunks beyond the old grid are new and read as fill.</li>
     * </ul>
     *
     * <p>Not atomic: other writers must leave the array alone while it is resized, and handles opened before
     * keep the old shape. A consolidated group's snapshot of this array goes stale until it is consolidated
     * again.
     *
     * @param newShape the new shape, of this array's rank; each extent zero or more
     * @return a handle on the resized array
     * @throws IllegalArgumentException      if the rank differs or an extent is negative
     * @throws UnsupportedOperationException if the store is read-only
     * @throws ZarrFormatException           if the array's stored metadata is gone or malformed
     */
    public ZarrArray resize(long... newShape) {
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }
        if (newShape.length != metadata.rank()) {
            throw new IllegalArgumentException("new shape has rank " + newShape.length + ", the array has rank "
                    + metadata.rank());
        }
        List<JsonValue> extents = new ArrayList<>(newShape.length);
        for (long extent : newShape) {
            if (extent < 0) {
                throw new IllegalArgumentException("negative extent in new shape " + Arrays.toString(newShape));
            }
            extents.add(JsonNumber.of(extent));
        }
        // The v3 document, or a v2 array's .zarray; v2 keeps its attributes in .zattrs.
        String v3Key = ZarrNode.metadataKey(path);
        Optional<byte[]> v3 = store.get(v3Key);
        String key = v3.isPresent() ? v3Key : ZarrNode.key(path, V2Metadata.ZARRAY);
        byte[] stored = v3.isPresent() ? v3.get() : store.get(key).orElseThrow(() -> new ZarrFormatException(
                "no array metadata at '" + display() + "' to resize"));
        byte[] zattrs = v3.isPresent() ? null : store.get(ZarrNode.key(path, V2Metadata.ZATTRS)).orElse(null);

        byte[] updated = Json.writeBytes(withMember(parseObject(stored, key), "shape", new JsonArray(extents)));

        ArrayMetadata current = parseArray(stored, zattrs, key);
        ArrayMetadata next;
        try {
            next = parseArray(updated, zattrs, key);
        } catch (ZarrUnsupportedException e) {
            throw new IllegalArgumentException("cannot resize to " + Arrays.toString(newShape) + ": "
                    + e.getMessage(), e);
        }
        Resize.clearExposed(store, path, current, next, chunkCache, writeEmptyChunks);
        store.set(key, updated);
        Resize.deleteOutside(store, path, current, next, chunkCache);
        return withMetadata(next);
    }

    /** Parses stored array metadata: a v3 {@code zarr.json}, or a v2 {@code .zarray} with its {@code .zattrs}. */
    private ArrayMetadata parseArray(byte[] stored, byte[] zattrs, String key) {
        NodeMetadata parsed = key.endsWith(V2Metadata.ZARRAY) ? V2Metadata.parseArray(stored, zattrs, key)
                : Metadata.parse(stored, key);
        if (!(parsed instanceof ArrayMetadata array)) {
            throw new ZarrFormatException("'" + key + "' no longer describes an array");
        }
        return array;
    }

    /**
     * A handle like this one, its options included, on {@code newMetadata}: what a change to the array's
     * metadata (its attributes, its shape) returns.
     */
    ZarrArray withMetadata(ArrayMetadata newMetadata) {
        return new ZarrArray(store, path, newMetadata, chunkCache, writeEmptyChunks);
    }

    /** Internal access to the parsed metadata for the read path. */
    ArrayMetadata metadata() {
        return metadata;
    }

    /** This handle's decoded-chunk cache, or {@code null} if it has none. */
    ChunkCache chunkCache() {
        return chunkCache;
    }

    @Override
    public String toString() {
        return "ZarrArray[" + display() + " " + dataType().name() + " shape=" + Arrays.toString(shape())
                + " chunks=" + Arrays.toString(chunkShape()) + "]";
    }
}
