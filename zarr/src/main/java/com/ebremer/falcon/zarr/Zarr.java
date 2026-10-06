package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.file.Path;
import java.util.Map;

/**
 * Entry point for reading a Zarr v3 hierarchy.
 *
 * <p>{@link #open(Store)} reads the root {@code zarr.json} and returns the root {@link ZarrNode} &mdash;
 * a {@link ZarrGroup} or a {@link ZarrArray}. From a group, navigate to children with
 * {@link ZarrGroup#child}, {@link ZarrGroup#group}, {@link ZarrGroup#array}, and the listing accessors.
 */
public final class Zarr {

    /** The Zarr storage format version this module targets. */
    public static final int ZARR_FORMAT = 3;

    private Zarr() {
    }

    /**
     * Opens the root node of the hierarchy in {@code store}, using consolidated metadata when the root has
     * it, as zarr-python 3 does: {@code open(store, true)}.
     *
     * <p>Both Zarr v3 (a {@code zarr.json} at each node) and Zarr v2 ({@code .zarray}/{@code .zgroup}
     * with a sidecar {@code .zattrs}) are read; v2 metadata is translated into the v3 model on open.
     *
     * @throws ZarrFormatException      if there is no root node metadata, or it or the consolidated metadata
     *                                  is malformed
     * @throws ZarrUnsupportedException if the store uses an unimplemented feature
     */
    public static ZarrNode open(Store store) {
        return open(store, true);
    }

    /**
     * Opens the root node of the hierarchy in {@code store}.
     *
     * <p>With {@code useConsolidated}, a root group that stores consolidated metadata (a snapshot of the
     * metadata of every node below it, written by {@link ZarrGroup#consolidate()} or zarr-python's
     * {@code consolidate_metadata}) answers child queries from it, and so do the groups opened through it:
     * walking the hierarchy then costs no store requests beyond the root's. A v3 root's snapshot is in its
     * {@code zarr.json}; a v2 root's is the {@code .zmetadata} key, one more request to look for. A
     * snapshot shows the hierarchy as it was consolidated (see {@link ZarrGroup}). Without
     * {@code useConsolidated}, every node's own metadata is read, here and below.
     *
     * @throws ZarrFormatException      if there is no root node metadata or it is malformed, or, with
     *                                  {@code useConsolidated}, if the consolidated metadata is malformed
     * @throws ZarrUnsupportedException if the store uses an unimplemented feature
     */
    public static ZarrNode open(Store store, boolean useConsolidated) {
        return ZarrNode.tryOpen(store, "", useConsolidated, true).orElseThrow(() -> new ZarrFormatException(
                "no root zarr.json, .zarray, or .zgroup found: not a Zarr store"));
    }

    /** Opens the root node of the hierarchy in the store directory at {@code directory} (read-only). */
    public static ZarrNode open(Path directory) {
        return open(FileSystemStore.openReadOnly(directory));
    }

    /**
     * Opens the hierarchy root as a group, using its consolidated metadata if it has any.
     *
     * @throws IllegalStateException if the root is an array
     */
    public static ZarrGroup openGroup(Store store) {
        return open(store).asGroup();
    }

    /**
     * Opens the hierarchy root as a group; see {@link #open(Store, boolean)}.
     *
     * @throws IllegalStateException if the root is an array
     */
    public static ZarrGroup openGroup(Store store, boolean useConsolidated) {
        return open(store, useConsolidated).asGroup();
    }

    /**
     * Opens the hierarchy root as an array.
     *
     * @throws IllegalStateException if the root is a group
     */
    public static ZarrArray openArray(Store store) {
        return open(store).asArray();
    }

    /**
     * Creates the root group of {@code store} and returns it.
     *
     * @throws IllegalArgumentException      if the store already has a root node
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrGroup createGroup(Store store) {
        return createGroup(store, new JsonObject(Map.of()), false);
    }

    /**
     * Creates the root group of {@code store} with the given attributes.
     *
     * @throws IllegalArgumentException      if the store already has a root node
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrGroup createGroup(Store store, JsonObject attributes) {
        return createGroup(store, attributes, false);
    }

    /**
     * Creates the root group of {@code store} with the given attributes. With {@code overwrite}, every key
     * in the store is deleted first, the old hierarchy and anything else stored there alike; without it, a
     * store that already has a root node is refused.
     *
     * @throws IllegalArgumentException      if the store has a root node and {@code overwrite} is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrGroup createGroup(Store store, JsonObject attributes, boolean overwrite) {
        ZarrNode.prepareCreate(store, "", false, overwrite);
        store.set("zarr.json", Json.writeBytes(ZarrGroup.groupJson(attributes)));
        return ZarrNode.open(store, "").asGroup();
    }

    /**
     * Creates an array at the root of {@code store} and returns it.
     *
     * @throws IllegalArgumentException      if the store holds any key (a root array would read stray
     *                                       keys as its chunks)
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrArray createArray(Store store, ArraySpec spec) {
        return createArray(store, spec, false);
    }

    /**
     * Creates an array at the root of {@code store}. With {@code overwrite}, every key in the store is
     * deleted first; without it, a store that holds any key is refused, since a root array would read stray
     * keys as its chunks.
     *
     * @throws IllegalArgumentException      if the store holds any key and {@code overwrite} is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrArray createArray(Store store, ArraySpec spec, boolean overwrite) {
        ZarrNode.prepareCreate(store, "", true, overwrite);
        store.set("zarr.json", Json.writeBytes(spec.toJson()));
        return ZarrNode.open(store, "").asArray();
    }
}
