package com.ebremer.falcon.zarr;

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
 * <p>Z1 exposes the array's description &mdash; shape, data type, chunk shape, fill value, codecs, and
 * dimension names. Reading element data lands in Z5, once the data-type, chunk-grid, and codec stages
 * are in place.
 */
public final class ZarrArray extends ZarrNode {

    private final ArrayMetadata metadata;

    ZarrArray(Store store, String path, ArrayMetadata metadata) {
        super(store, path);
        this.metadata = metadata;
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

    /** The total number of elements: the product of the shape (1 for a scalar array). */
    public long size() {
        long count = 1;
        for (long dimension : metadata.shape()) {
            count *= dimension;
        }
        return count;
    }

    /** Internal access to the parsed metadata for later stages (Z3+). */
    ArrayMetadata metadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ZarrArray[" + display() + " " + dataType().name() + " shape=" + Arrays.toString(shape())
                + " chunks=" + Arrays.toString(chunkShape()) + "]";
    }
}
