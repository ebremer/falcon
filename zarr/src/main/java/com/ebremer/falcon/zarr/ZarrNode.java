package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.metadata.GroupMetadata;
import com.ebremer.falcon.zarr.metadata.Metadata;
import com.ebremer.falcon.zarr.metadata.NodeMetadata;
import com.ebremer.falcon.zarr.store.Store;

/**
 * A node in a Zarr hierarchy: either a {@link ZarrGroup} or a {@link ZarrArray}. Every node is located
 * by a store {@link #path()} (the empty string for the root) and carries user {@link #attributes()}.
 */
public abstract sealed class ZarrNode permits ZarrGroup, ZarrArray {

    final Store store;
    final String path; // "" for the root, else a '/'-separated path such as "a/b"
    final String name; // "" for the root, else the last path segment

    ZarrNode(Store store, String path) {
        this.store = store;
        this.path = path;
        int slash = path.lastIndexOf('/');
        this.name = slash < 0 ? path : path.substring(slash + 1);
    }

    /** This node's store path: {@code ""} for the root, otherwise a {@code '/'}-separated path. */
    public String path() {
        return path;
    }

    /** This node's name: its last path segment, or {@code ""} for the root. */
    public String name() {
        return name;
    }

    /** Whether this node is a group. */
    public abstract boolean isGroup();

    /** Whether this node is an array. */
    public boolean isArray() {
        return !isGroup();
    }

    /** This node's user attributes; an empty object when none are stored. */
    public abstract JsonObject attributes();

    /**
     * This node as a {@link ZarrGroup}.
     *
     * @throws IllegalStateException if it is an array
     */
    public ZarrGroup asGroup() {
        if (this instanceof ZarrGroup g) {
            return g;
        }
        throw new IllegalStateException("node '" + display() + "' is an array, not a group");
    }

    /**
     * This node as a {@link ZarrArray}.
     *
     * @throws IllegalStateException if it is a group
     */
    public ZarrArray asArray() {
        if (this instanceof ZarrArray a) {
            return a;
        }
        throw new IllegalStateException("node '" + display() + "' is a group, not an array");
    }

    /** A human-readable form of the path ({@code "/"} for the root). */
    String display() {
        return path.isEmpty() ? "/" : path;
    }

    /** The metadata key ({@code zarr.json}) for a node at {@code path}. */
    static String metadataKey(String path) {
        return path.isEmpty() ? "zarr.json" : path + "/zarr.json";
    }

    /** Reads and classifies the node at {@code path}, dispatching on its {@code node_type}. */
    static ZarrNode open(Store store, String path) {
        byte[] json = store.get(metadataKey(path)).orElseThrow(
                () -> new ZarrFormatException("no zarr.json at '" + (path.isEmpty() ? "/" : path) + "'"));
        NodeMetadata meta = Metadata.parse(json, metadataKey(path));
        return switch (meta) {
            case GroupMetadata g -> new ZarrGroup(store, path, g);
            case ArrayMetadata a -> new ZarrArray(store, path, a);
        };
    }
}
