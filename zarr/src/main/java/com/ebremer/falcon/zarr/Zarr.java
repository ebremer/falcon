package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.store.FileSystemStore;
import com.ebremer.falcon.zarr.store.Store;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Entry point for reading a Zarr v3 hierarchy.
 *
 * <p>{@link #open(Store)} reads the root {@code zarr.json} and returns the root {@link ZarrNode} &mdash;
 * a {@link ZarrGroup} or a {@link ZarrArray}. From a group, navigate to children with
 * {@link ZarrGroup#child}, {@link ZarrGroup#group}, {@link ZarrGroup#array}, and the listing accessors, or
 * open a node deeper down by its path with {@link #open(Store, String)}.
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
     * @param store the store holding the hierarchy
     * @return the root group or array
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
     * @param store           the store holding the hierarchy
     * @param useConsolidated whether a root group answers child queries from its consolidated metadata
     * @return the root group or array
     * @throws ZarrFormatException      if there is no root node metadata or it is malformed, or, with
     *                                  {@code useConsolidated}, if the consolidated metadata is malformed
     * @throws ZarrUnsupportedException if the store uses an unimplemented feature
     */
    public static ZarrNode open(Store store, boolean useConsolidated) {
        return ZarrNode.tryOpen(store, "", useConsolidated, true).orElseThrow(() -> new ZarrFormatException(
                "no root zarr.json, .zarray, or .zgroup found: not a Zarr store"));
    }

    /**
     * Opens the node at {@code path} in {@code store} (F9): {@code ""} or {@code "/"} for the root, else
     * names joined by {@code '/'}, with or without a leading {@code '/'} ({@code "model/weights"}). The node's
     * metadata is read directly, one request (for a v3 node) whatever the depth, as zarr-python's
     * {@code open(store, path=...)} does; the groups above it are not opened, so their consolidated metadata
     * is not consulted. A group opened here uses its own consolidated metadata, if it has any:
     * {@code open(store, path, true)}.
     *
     * @param store the store holding the hierarchy
     * @param path  the node's path from the root
     * @return the group or array at {@code path}
     * @throws NoSuchElementException   if there is no node at a non-root {@code path}
     * @throws IllegalArgumentException if the path has an empty name, or a {@code "."} or {@code ".."}
     * @throws ZarrFormatException      if there is no root node (for the root path), or the node's metadata is
     *                                  malformed
     * @throws ZarrUnsupportedException if the node uses an unimplemented feature
     */
    public static ZarrNode open(Store store, String path) {
        return open(store, path, true);
    }

    /**
     * Opens the node at {@code path} in {@code store}, as {@link #open(Store, String)} does, with a group
     * using its consolidated metadata only if {@code useConsolidated} (see {@link #open(Store, boolean)}).
     *
     * @param store           the store holding the hierarchy
     * @param path            the node's path from the root
     * @param useConsolidated whether a group opened here answers child queries from its consolidated
     *                        metadata
     * @return the group or array at {@code path}
     * @throws NoSuchElementException   if there is no node at a non-root {@code path}
     * @throws IllegalArgumentException if the path has an empty name, or a {@code "."} or {@code ".."}
     * @throws ZarrFormatException      if there is no root node (for the root path), or the node's metadata or
     *                                  its consolidated metadata is malformed
     * @throws ZarrUnsupportedException if the node uses an unimplemented feature
     */
    public static ZarrNode open(Store store, String path, boolean useConsolidated) {
        String nodePath = path.startsWith("/") ? path.substring(1) : path;
        if (nodePath.isEmpty()) {
            return open(store, useConsolidated);
        }
        String relative = ZarrNode.relativePath(nodePath);
        return ZarrNode.tryOpen(store, relative, useConsolidated, true).orElseThrow(() ->
                new NoSuchElementException("no node at '" + relative + "': no zarr.json, .zarray, or .zgroup"));
    }

    /**
     * Opens the group at {@code path} in {@code store}; see {@link #open(Store, String)}.
     *
     * @param store the store holding the hierarchy
     * @param path  the group's path from the root
     * @return the group
     * @throws NoSuchElementException if there is no node at a non-root {@code path}
     * @throws IllegalStateException  if the node there is an array
     */
    public static ZarrGroup openGroup(Store store, String path) {
        return open(store, path).asGroup();
    }

    /**
     * Opens the array at {@code path} in {@code store}; see {@link #open(Store, String)}.
     *
     * @param store the store holding the hierarchy
     * @param path  the array's path from the root
     * @return the array
     * @throws NoSuchElementException if there is no node at a non-root {@code path}
     * @throws IllegalStateException  if the node there is a group
     */
    public static ZarrArray openArray(Store store, String path) {
        return open(store, path).asArray();
    }

    /**
     * Opens the root node of the hierarchy in the store directory at {@code directory} (read-only), as
     * {@link #open(Store)} does over a {@link FileSystemStore}.
     *
     * @param directory the store's root directory
     * @return the root group or array
     * @throws ZarrFormatException      if there is no root node metadata, or it or the consolidated metadata
     *                                  is malformed
     * @throws ZarrUnsupportedException if the store uses an unimplemented feature
     */
    public static ZarrNode open(Path directory) {
        return open(FileSystemStore.openReadOnly(directory));
    }

    /**
     * Opens the hierarchy root as a group, using its consolidated metadata if it has any.
     *
     * @param store the store holding the hierarchy
     * @return the root group
     * @throws IllegalStateException if the root is an array
     */
    public static ZarrGroup openGroup(Store store) {
        return open(store).asGroup();
    }

    /**
     * Opens the hierarchy root as a group; see {@link #open(Store, boolean)}.
     *
     * @param store           the store holding the hierarchy
     * @param useConsolidated whether the group answers child queries from its consolidated metadata
     * @return the root group
     * @throws IllegalStateException if the root is an array
     */
    public static ZarrGroup openGroup(Store store, boolean useConsolidated) {
        return open(store, useConsolidated).asGroup();
    }

    /**
     * Opens the hierarchy root as an array.
     *
     * @param store the store holding the hierarchy
     * @return the root array
     * @throws IllegalStateException if the root is a group
     */
    public static ZarrArray openArray(Store store) {
        return open(store).asArray();
    }

    /**
     * Creates the root group of {@code store}, with no attributes, and returns it.
     *
     * @param store the store to create the group in
     * @return the new group
     * @throws IllegalArgumentException      if the store already has a root node
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrGroup createGroup(Store store) {
        return createGroup(store, new JsonObject(Map.of()), false);
    }

    /**
     * Creates the root group of {@code store} with the given attributes.
     *
     * @param store      the store to create the group in
     * @param attributes the group's attributes
     * @return the new group
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
     * @param store      the store to create the group in
     * @param attributes the group's attributes
     * @param overwrite  whether to delete every key in the store first
     * @return the new group
     * @throws IllegalArgumentException      if the store has a root node and {@code overwrite} is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrGroup createGroup(Store store, JsonObject attributes, boolean overwrite) {
        return createGroup(store, attributes, overwrite, ZARR_FORMAT);
    }

    /**
     * Creates the root group of {@code store} in the given Zarr format, as zarr-python's {@code zarr_format}:
     * 3, a {@code zarr.json}; or 2, a {@code .zgroup} with the attributes in {@code .zattrs}, as zarr-python
     * 3.4 writes them. The groups and arrays then created in it take its format. Otherwise as
     * {@link #createGroup(Store, JsonObject, boolean)}.
     *
     * @param store      the store to create the group in
     * @param attributes the group's attributes
     * @param overwrite  whether to delete every key in the store first
     * @param zarrFormat 2 or 3
     * @return the new group
     * @throws IllegalArgumentException      if {@code zarrFormat} is neither, or the store has a root node and
     *                                       {@code overwrite} is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrGroup createGroup(Store store, JsonObject attributes, boolean overwrite, int zarrFormat) {
        if (zarrFormat != 2 && zarrFormat != 3) {
            throw new IllegalArgumentException("the Zarr format must be 2 or 3, not " + zarrFormat);
        }
        Objects.requireNonNull(attributes, "attributes");
        ZarrNode.prepareCreate(store, "", false, overwrite);
        ZarrNode.writeGroup(store, "", attributes, zarrFormat);
        return ZarrNode.open(store, "").asGroup();
    }

    /**
     * Creates an array at the root of {@code store} and returns it: a Zarr v3 array, or a v2 one if the spec
     * says so ({@link ArraySpec.Builder#zarrFormat(int)}).
     *
     * @param store the store to create the array in
     * @param spec  the array's description
     * @return the new array
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
     * @param store     the store to create the array in
     * @param spec      the array's description
     * @param overwrite whether to delete every key in the store first
     * @return the new array
     * @throws IllegalArgumentException      if the store holds any key and {@code overwrite} is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public static ZarrArray createArray(Store store, ArraySpec spec, boolean overwrite) {
        int format = spec.rootFormat();
        JsonObject metadata = spec.metadata(format);
        ZarrNode.prepareCreate(store, "", true, overwrite);
        ZarrNode.writeArray(store, "", spec, metadata, format);
        return ZarrNode.open(store, "").asArray();
    }
}
