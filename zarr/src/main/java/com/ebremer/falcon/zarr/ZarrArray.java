package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.chunk.ChunkGrid;
import com.ebremer.falcon.zarr.chunk.RegularChunkGrid;
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
import java.util.stream.LongStream;
import java.util.stream.Stream;

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

    /** {@return the array shape (a defensive copy)} */
    public long[] shape() {
        return metadata.shape();
    }

    /** {@return the number of dimensions (0 for a scalar array)} */
    public int rank() {
        return metadata.rank();
    }

    /**
     * The chunk shape (a defensive copy); same rank as {@link #shape()}. A rectilinear chunk grid's chunks
     * differ in shape, so it has none: {@link #chunkSizes()} gives every array's chunks.
     *
     * @return the shape every chunk is stored at
     * @throws UnsupportedOperationException if the array's chunk grid is rectilinear (see
     *                                       {@link #isRectilinear()}), as zarr-python's {@code chunks} and
     *                                       {@code shards} are undefined there
     */
    public long[] chunkShape() {
        return metadata.chunkShape();
    }

    /**
     * Whether the array's chunk grid is {@code rectilinear} (zarr-python's {@code array.rectilinear_chunks}),
     * whose chunks differ in shape along a dimension, rather than {@code regular}.
     *
     * @return true for a rectilinear grid, false for a regular one
     */
    public boolean isRectilinear() {
        return !metadata.grid().isRegular();
    }

    /**
     * The length of each chunk along each dimension, cut at the array bound: {@code chunkSizes()[i][c]} is
     * chunk {@code c}'s extent along dimension {@code i}, inside the array. It describes a regular grid and a
     * rectilinear one alike (dask's {@code chunks}; zarr-python's {@code write_chunk_sizes}). A sharded
     * array's chunks are its shards; {@link #innerChunkShape()} gives their sub-chunks.
     *
     * @return one array per dimension, with one entry per chunk along it (none for an empty dimension)
     * @throws IllegalStateException if a dimension has more chunks than a Java array holds
     */
    public long[][] chunkSizes() {
        return metadata.grid().chunkSizes();
    }

    /**
     * The shape of the pieces a read fetches and decodes on its own (a defensive copy). For a sharded array
     * these are the sub-chunks its shards are made of (zarr-python's {@code chunks}, where
     * {@link #chunkShape()} is its {@code shards}): a read decodes only the sub-chunks it overlaps, so
     * {@code blocks(innerChunkShape())} reads a sharded array one sub-chunk at a time (F10). For any other
     * array, and for one whose shards are transposed before they are sharded (which are decoded whole), it
     * is the chunk shape. In a nested shard, the sub-chunks are the outer shard's. A sharded array's
     * sub-chunks share one shape even when its shards, on a rectilinear grid, do not.
     *
     * @return the shape of the smallest piece a read decodes on its own
     * @throws ZarrFormatException           if the array's codecs are malformed
     * @throws ZarrUnsupportedException      if they use an unimplemented codec
     * @throws UnsupportedOperationException if the array's chunk grid is rectilinear and its chunks are not
     *                                       sharded, so their shapes differ (zarr-python's {@code chunks} is
     *                                       undefined there too)
     */
    public long[] innerChunkShape() {
        int[] sub = metadata.pipeline().subChunkShape();
        if (sub == null) {
            return metadata.chunkShape();
        }
        long[] out = new long[sub.length];
        for (int i = 0; i < sub.length; i++) {
            out[i] = sub[i];
        }
        return out;
    }

    /**
     * The number of chunks along each dimension (a defensive copy): {@code ceil(shape / chunkShape)} for a
     * regular grid, and for a rectilinear one the chunks that overlap the array.
     *
     * @return the chunk count along each dimension
     */
    public long[] gridShape() {
        return metadata.grid().gridShape();
    }

    /** {@return the total number of chunks in the grid (the product of {@link #gridShape()})} */
    public long chunkCount() {
        return metadata.grid().chunkCount();
    }

    /**
     * The store key of the chunk at the given grid coordinates, including this array's path prefix
     * (for example {@code "temperature/c/1/2"}).
     *
     * @param coords the chunk's coordinates in the chunk grid, one per dimension
     * @return the chunk's store key; the chunk need not be stored
     * @throws IllegalArgumentException  if the wrong number of coordinates is given
     * @throws IndexOutOfBoundsException if a coordinate lies outside the chunk grid
     */
    public String chunkKey(long... coords) {
        metadata.grid().checkCoords(coords);
        String relative = metadata.chunkKeyEncoding().encode(coords);
        return path.isEmpty() ? relative : path + "/" + relative;
    }

    /** {@return the element data type} */
    public DataType dataType() {
        return metadata.dataType();
    }

    /**
     * The fill value, as raw JSON. See {@link #fillValueBytes(ByteOrder)} for the decoded element bytes.
     *
     * @return the {@code fill_value} as stored in the metadata (a v2 array's translated)
     */
    public JsonValue fillValue() {
        return metadata.fillValue();
    }

    /**
     * The fill value decoded to one element's bytes in the given order.
     *
     * @param order the byte order of each primitive in the element
     * @return one element's bytes
     * @throws UnsupportedOperationException if the data type is variable-length (its fill value is the
     *                                       string {@link #fillValue()} holds)
     */
    public byte[] fillValueBytes(ByteOrder order) {
        return metadata.fillValueBytes(order);
    }

    /**
     * {@return the codec names, in pipeline order} Only the top-level codecs: a sharded array's include
     * {@code sharding_indexed} but not the codecs inside it.
     */
    public List<String> codecNames() {
        return metadata.codecNames();
    }

    /** {@return the chunk key encoding name ({@code "default"} or {@code "v2"})} */
    public String chunkKeyEncoding() {
        return metadata.chunkKeyEncoding().name();
    }

    /** {@return the chunk key separator ({@code "/"} or {@code "."})} */
    public String separator() {
        return metadata.separator();
    }

    /**
     * The dimension names if present; one entry per dimension, {@code null} for an unnamed dimension.
     *
     * @return an unmodifiable list of the names, or empty if the metadata has none
     */
    public Optional<List<String>> dimensionNames() {
        return metadata.dimensionNames();
    }

    /**
     * The total number of elements: the product of the shape (1 for a scalar array). Opening an array
     * whose element count overflows {@code long} fails, so this is exact.
     *
     * @return the element count
     */
    public long size() {
        return metadata.grid().size();
    }

    /**
     * The hyperslab {@code [offset, offset+shape)} of this array. Nothing is read until one of the
     * selection's read methods is called.
     *
     * @param offset the region's start in each dimension
     * @param shape  the region's extent in each dimension (an extent may be zero)
     * @return the selection
     * @throws IllegalArgumentException  if the rank is wrong
     * @throws IndexOutOfBoundsException if the region extends past the array
     */
    public Selection select(long[] offset, long[] shape) {
        ChunkAssembler.checkSelection(metadata, offset, shape);
        return new Selection(this, offset.clone(), shape.clone());
    }

    /** {@return the whole array as a selection} */
    public Selection selectAll() {
        long[] shape = metadata.shape();
        return new Selection(this, new long[shape.length], shape);
    }

    /**
     * Reads the whole array as {@code double}s (bool, integer, and float types, widened); see
     * {@link Selection#readDoubles()}.
     *
     * @return the elements, in C order
     * @throws ZarrException as {@link Selection#readDoubles()} does, or if the array holds more elements than
     *                       one Java array can
     */
    public double[] readDoubles() {
        return selectAll().readDoubles();
    }

    /**
     * Reads the whole array as {@code float}s (float data types only).
     *
     * @return the elements, in C order
     * @throws ZarrException as {@link Selection#readFloats()} does, or if the array holds more elements than
     *                       one Java array can
     */
    public float[] readFloats() {
        return selectAll().readFloats();
    }

    /**
     * Reads the whole array as {@code long}s (integer data types that fit).
     *
     * @return the elements, in C order
     * @throws ZarrException as {@link Selection#readLongs()} does, or if the array holds more elements than
     *                       one Java array can
     */
    public long[] readLongs() {
        return selectAll().readLongs();
    }

    /**
     * Reads the whole array as {@code int}s (integer data types that fit).
     *
     * @return the elements, in C order
     * @throws ZarrException as {@link Selection#readInts()} does, or if the array holds more elements than
     *                       one Java array can
     */
    public int[] readInts() {
        return selectAll().readInts();
    }

    /**
     * Reads the whole array of an unsigned integer type exactly, uint64 as its 64 bits; see
     * {@link Selection#readUnsignedLongs()}.
     *
     * @return the elements, in C order
     * @throws ZarrException as {@link Selection#readUnsignedLongs()} does, or if the array holds more
     *                       elements than one Java array can
     */
    public long[] readUnsignedLongs() {
        return selectAll().readUnsignedLongs();
    }

    /**
     * Reads the whole complex array as {@code double}s, two per element (real, imaginary); see
     * {@link Selection#readComplex()}.
     *
     * @return two doubles per element, in C order
     * @throws ZarrException as {@link Selection#readComplex()} does, or if the array holds more elements
     *                       than one Java array can
     */
    public double[] readComplex() {
        return selectAll().readComplex();
    }

    /**
     * Reads the whole array's raw decoded element bytes, in C order, in the array's byte order.
     *
     * @return the element bytes, in C order
     * @throws ZarrException as {@link Selection#readRawBytes()} does, or if the array holds more elements than
     *                       one Java array can
     */
    public byte[] readRawBytes() {
        return selectAll().readRawBytes();
    }

    /**
     * Reads the whole array as {@code String}s, in C order (the {@code string} and
     * {@code fixed_length_utf32} data types); see {@link Selection#readStrings()}.
     *
     * @return the strings, in C order
     * @throws ZarrException as {@link Selection#readStrings()} does, or if the array holds more elements than
     *                       one Java array can
     */
    public String[] readStrings() {
        return selectAll().readStrings();
    }

    /**
     * Writes the whole array from {@code values} (the {@code string} and {@code fixed_length_utf32} data
     * types); see {@link Selection#writeStrings(String[])}.
     *
     * @param values the strings, one per element, in C order
     * @throws IllegalArgumentException as {@link Selection#writeStrings(String[])} does: {@code values} has
     *                                  the wrong length, or a string does not fit its element
     * @throws ZarrException            if the data type does not hold strings
     */
    public void writeStrings(String[] values) {
        selectAll().writeStrings(values);
    }

    /**
     * Reads the whole array as byte strings, in C order (the {@code variable_length_bytes},
     * {@code null_terminated_bytes}, {@code raw_bytes}, {@code struct}, and {@code r*} data types); see
     * {@link Selection#readByteArrays()}.
     *
     * @return the byte strings, in C order
     * @throws ZarrException as {@link Selection#readByteArrays()} does, or if the array holds more elements
     *                       than one Java array can
     */
    public byte[][] readByteArrays() {
        return selectAll().readByteArrays();
    }

    /**
     * Writes the whole array from {@code values}, one byte string per element; see
     * {@link Selection#writeByteArrays(byte[][])}.
     *
     * @param values the byte strings, one per element, in C order
     * @throws IllegalArgumentException as {@link Selection#writeByteArrays(byte[][])} does: {@code values}
     *                                  has the wrong length, or an element the wrong size for a fixed-size type
     * @throws ZarrException            if the data type does not hold byte strings
     */
    public void writeByteArrays(byte[][] values) {
        selectAll().writeByteArrays(values);
    }

    /**
     * Writes the whole array from {@code values}, narrowing to this array's data type; see
     * {@link Selection#writeDoubles(double[])}.
     *
     * @param values one value per element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeDoubles(double[] values) {
        selectAll().writeDoubles(values);
    }

    /**
     * Writes the whole array from {@code values}, narrowing to this array's data type; see
     * {@link Selection#writeFloats(float[])}.
     *
     * @param values one value per element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeFloats(float[] values) {
        selectAll().writeFloats(values);
    }

    /**
     * Writes the whole array from {@code values}, narrowing to this array's data type; see
     * {@link Selection#writeLongs(long[])}.
     *
     * @param values one value per element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeLongs(long[] values) {
        selectAll().writeLongs(values);
    }

    /**
     * Writes the whole array from {@code values}, narrowing to this array's data type; see
     * {@link Selection#writeInts(int[])}.
     *
     * @param values one value per element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or the type cannot hold a
     *                                  value (nothing is written)
     * @throws ZarrException            if the data type is not bool, an integer, or a float type
     */
    public void writeInts(int[] values) {
        selectAll().writeInts(values);
    }

    /**
     * Writes the whole unsigned integer array from {@code values}, each taken as an unsigned 64-bit value;
     * see {@link Selection#writeUnsignedLongs(long[])}.
     *
     * @param values one value per element, in C order
     * @throws IllegalArgumentException if {@code values} has the wrong length, or a value is too large for
     *                                  the type (nothing is written)
     * @throws ZarrException            if the data type is not an unsigned integer type
     */
    public void writeUnsignedLongs(long[] values) {
        selectAll().writeUnsignedLongs(values);
    }

    /**
     * Writes the whole complex array from {@code values}, two per element (real, imaginary); see
     * {@link Selection#writeComplex(double[])}.
     *
     * @param values two doubles per element, in C order
     * @throws IllegalArgumentException if {@code values} does not hold two doubles per element, or a part is
     *                                  out of range (nothing is written)
     * @throws ZarrException            if the data type is not complex64 or complex128
     */
    public void writeComplex(double[] values) {
        selectAll().writeComplex(values);
    }

    /**
     * Writes the whole array from raw element bytes (C order, this array's byte order).
     *
     * @param elements {@link #size()} elements of the data type's size each
     * @throws IllegalArgumentException if {@code elements} is not that long
     * @throws ZarrException            if the data type is variable-length
     */
    public void writeRawBytes(byte[] elements) {
        selectAll().writeRawBytes(elements);
    }

    /**
     * A selection per chunk, each covering that chunk's in-bounds region (edge chunks are clamped to the
     * array bound), in C order over the chunks; on a rectilinear grid the selections differ in shape as the
     * chunks do. Reading one block at a time streams an array whose whole contents would not fit in a
     * single Java array (a whole-array {@code readDoubles()} is capped near 2&nbsp;GB); the stream is lazy,
     * so blocks are produced without materializing them all. A chunk of a sharded array is a whole shard,
     * which may be large: {@code blocks(innerChunkShape())} goes sub-chunk by sub-chunk.
     *
     * @return one selection per chunk of the grid, in C order over the chunks
     */
    public Stream<Selection> blocks() {
        ChunkGrid grid = metadata.grid();
        return LongStream.range(0, grid.chunkCount()).mapToObj(i -> {
            long[] coords = grid.chunkCoordsAt(i);
            return new Selection(this, grid.chunkOrigin(coords), grid.validExtent(coords));
        });
    }

    /**
     * Selections tiling the array in blocks of {@code blockShape}, laid from the origin, in C order over the
     * blocks; a block at the array's far edge is cut to the array. The stream is lazy, as {@link #blocks()}'s
     * is.
     *
     * <p>A block shape that divides the chunk shape lets each block read part of one chunk; in a sharded
     * array, a block of {@link #innerChunkShape()} fetches and decodes exactly one sub-chunk (F10). Each such
     * read also fetches the shard's index unless the handle caches it: read through
     * {@link #withChunkCache(long)} to fetch each shard's index once.
     *
     * @param blockShape the block extent in each dimension, of this array's rank, each at least 1
     * @return one selection per block
     * @throws IllegalArgumentException if the rank differs or an extent is not positive
     */
    public Stream<Selection> blocks(long... blockShape) {
        RegularChunkGrid grid = new RegularChunkGrid(metadata.shape(), blockShape);
        return LongStream.range(0, grid.chunkCount()).mapToObj(i -> {
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
     * @param writeEmptyChunks whether the new handle stores chunks that hold only the fill value
     * @return a new handle on the same array
     */
    public ZarrArray withWriteEmptyChunks(boolean writeEmptyChunks) {
        return new ZarrArray(store, path, metadata, chunkCache, writeEmptyChunks);
    }

    /**
     * Whether this handle stores chunks that hold only the fill value; see {@link #withWriteEmptyChunks}.
     *
     * @return true if it stores them, false (the default) if it deletes them
     */
    public boolean writeEmptyChunks() {
        return writeEmptyChunks;
    }

    /**
     * Changes the array's shape, keeping its chunk shape, data, and everything else, and returns a handle on
     * the resized array (with this handle's options). The current shape is read from the store, not taken
     * from this handle, and the new one written back into the array's own metadata ({@code zarr.json}, or
     * a v2 array's {@code .zarray}); every other field of that document is kept as stored. A rectilinear
     * grid keeps its chunk lengths too, as zarr-python does: a dimension growing past the lengths it lists
     * gains one chunk covering the rest (and only then is its {@code chunk_grid} rewritten), and one
     * shrinking keeps them all.
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

        ArrayMetadata current = parseArray(stored, zattrs, key);
        JsonObject document = withMember(parseObject(stored, key), "shape", new JsonArray(extents));
        ChunkGrid grid = current.grid();
        if (!grid.isRegular()) {
            JsonObject resized = grid.resized(newShape).toJson();
            if (!resized.toJson().equals(grid.toJson().toJson())) {
                document = withMember(document, "chunk_grid", resized); // a dimension grew a chunk
            }
        }
        byte[] updated = Json.writeBytes(document);

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
                + " chunks=" + (isRectilinear() ? "rectilinear" : Arrays.toString(chunkShape())) + "]";
    }
}
