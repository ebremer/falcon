package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.data.ChunkAssembler;
import com.ebremer.falcon.zarr.data.ChunkCache;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * An array in a Zarr hierarchy: an N-dimensional grid of elements of one data type, stored in chunks.
 *
 * <p>A handle holds the array's metadata (shape, data type, chunk shape, fill value, codecs, dimension
 * names) as read when it was opened. Its reads and writes go to the store's chunks each time, so a read
 * sees every write made before it, through any handle. A handle from {@link #withChunkCache(long)} instead
 * keeps decoded chunks in memory: it sees its own writes, but not writes made through other handles or
 * processes until {@link #clearChunkCache()}.
 */
public final class ZarrArray extends ZarrNode {

    private final ArrayMetadata metadata;
    private final ChunkCache chunkCache; // decoded-chunk LRU, or null: see withChunkCache

    ZarrArray(Store store, String path, ArrayMetadata metadata) {
        this(store, path, metadata, null);
    }

    private ZarrArray(Store store, String path, ArrayMetadata metadata, ChunkCache chunkCache) {
        super(store, path);
        this.metadata = metadata;
        this.chunkCache = chunkCache;
    }

    @Override
    public boolean isGroup() {
        return false;
    }

    @Override
    public JsonObject attributes() {
        return metadata.attributes();
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
        return new ZarrArray(store, path, metadata, new ChunkCache(maxBytes));
    }

    /** Empties this handle's decoded-chunk cache; a handle without one has nothing to clear. */
    public void clearChunkCache() {
        if (chunkCache != null) {
            chunkCache.clear();
        }
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
