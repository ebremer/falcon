package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.store.Store;
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

    /** The data-type name as written in {@code zarr.json} (for example {@code "float64"}). */
    public String dataType() {
        return metadata.dataType();
    }

    /** The raw fill value; the typed decoding lands in Z2. */
    public JsonValue fillValue() {
        return metadata.fillValue();
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
        return "ZarrArray[" + display() + " " + dataType() + " shape=" + Arrays.toString(shape())
                + " chunks=" + Arrays.toString(chunkShape()) + "]";
    }
}
