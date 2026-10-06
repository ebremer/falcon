package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.metadata.GroupMetadata;
import com.ebremer.falcon.zarr.metadata.Metadata;
import com.ebremer.falcon.zarr.metadata.NodeMetadata;
import com.ebremer.falcon.zarr.metadata.V2Metadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** The metadata key ({@code zarr.json}) for a v3 node at {@code path}. */
    static String metadataKey(String path) {
        return key(path, "zarr.json");
    }

    /** A store key {@code name} beneath {@code path} ({@code name} itself for the root). */
    static String key(String path, String name) {
        return path.isEmpty() ? name : path + "/" + name;
    }

    /**
     * Whether a node (v3 or v2) exists at {@code path}. Up to three {@link Store#exists} probes; to open a
     * node, call {@link #tryOpen}, which needs none.
     */
    static boolean hasNode(Store store, String path) {
        return store.exists(key(path, "zarr.json"))
                || store.exists(key(path, V2Metadata.ZARRAY))
                || store.exists(key(path, V2Metadata.ZGROUP));
    }

    /**
     * Readies {@code path} for a new node, before its metadata is written. Without {@code overwrite} it
     * refuses a path that holds a node, and, for an array, a path with any key under it: the array would
     * read those keys as its chunks. With {@code overwrite} it deletes every key under the path (for the
     * root, every key in the store), the node's own metadata first, so a delete cut short leaves no node
     * that reads what is left.
     *
     * @throws UnsupportedOperationException if the store is read-only
     * @throws IllegalArgumentException      if the path is taken and {@code overwrite} is false
     */
    static void prepareCreate(Store store, String path, boolean array, boolean overwrite) {
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }
        String display = path.isEmpty() ? "/" : path;
        if (!overwrite) {
            if (hasNode(store, path)) {
                throw new IllegalArgumentException(
                        "a node already exists at '" + display + "'; pass overwrite = true to replace it");
            }
            if (array) {
                List<String> keys = keysUnder(store, path);
                if (!keys.isEmpty()) {
                    throw new IllegalArgumentException("'" + display + "' already holds " + keys.size()
                            + " key(s), such as '" + keys.get(0) + "', which a new array would read as its"
                            + " chunks; pass overwrite = true to delete them");
                }
            }
            return;
        }
        List<String> keys = keysUnder(store, path);
        Set<String> metadata = Set.of(metadataKey(path), key(path, V2Metadata.ZARRAY), key(path, V2Metadata.ZGROUP));
        List<String> ordered = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (metadata.contains(key)) {
                ordered.add(0, key);
            } else {
                ordered.add(key);
            }
        }
        for (String key : ordered) {
            store.delete(key);
        }
    }

    /** Every key under the node at {@code path}: for the root, every key in the store. */
    private static List<String> keysUnder(Store store, String path) {
        return path.isEmpty() ? store.list() : store.listPrefix(path + "/");
    }

    /**
     * Reads and classifies the node at {@code path}: v3 {@code zarr.json} first, then v2 metadata.
     *
     * @throws ZarrFormatException if there is no node there, or its metadata is malformed
     */
    static ZarrNode open(Store store, String path) {
        return tryOpen(store, path).orElseThrow(() -> new ZarrFormatException(
                "no zarr.json, .zarray, or .zgroup at '" + (path.isEmpty() ? "/" : path) + "'"));
    }

    /**
     * Reads and classifies the node at {@code path}, or empty if no node metadata is stored there. It
     * fetches {@code zarr.json}, then {@code .zarray}, then {@code .zgroup}, stopping at the first that is
     * present, with no {@link Store#exists} probes first: over HTTP each probe is a round trip, so a v3
     * node costs one request and a v2 array three (with its {@code .zattrs}), not five.
     *
     * @throws ZarrFormatException      if the node's metadata is malformed
     * @throws ZarrUnsupportedException if the node uses an unimplemented feature
     */
    static Optional<ZarrNode> tryOpen(Store store, String path) {
        Optional<byte[]> v3 = store.get(key(path, "zarr.json"));
        NodeMetadata meta;
        if (v3.isPresent()) {
            meta = Metadata.parse(v3.get(), metadataKey(path));
        } else {
            Optional<byte[]> zarray = store.get(key(path, V2Metadata.ZARRAY));
            if (zarray.isPresent()) {
                meta = V2Metadata.parseArray(zarray.get(), attrs(store, path), key(path, V2Metadata.ZARRAY));
            } else {
                Optional<byte[]> zgroup = store.get(key(path, V2Metadata.ZGROUP));
                if (zgroup.isEmpty()) {
                    return Optional.empty();
                }
                meta = V2Metadata.parseGroup(zgroup.get(), attrs(store, path), key(path, V2Metadata.ZGROUP));
            }
        }
        return Optional.of(switch (meta) {
            case GroupMetadata g -> new ZarrGroup(store, path, g);
            case ArrayMetadata a -> new ZarrArray(store, path, a);
        });
    }

    private static byte[] attrs(Store store, String path) {
        return store.get(key(path, V2Metadata.ZATTRS)).orElse(null);
    }
}
