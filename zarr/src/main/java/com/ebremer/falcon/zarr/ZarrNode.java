package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.metadata.ConsolidatedMetadata;
import com.ebremer.falcon.zarr.metadata.GroupMetadata;
import com.ebremer.falcon.zarr.metadata.Metadata;
import com.ebremer.falcon.zarr.metadata.NodeMetadata;
import com.ebremer.falcon.zarr.metadata.NodeType;
import com.ebremer.falcon.zarr.metadata.V2Metadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

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

    /** {@return this node's store path: {@code ""} for the root, otherwise a {@code '/'}-separated path} */
    public String path() {
        return path;
    }

    /** {@return this node's name: its last path segment, or {@code ""} for the root} */
    public String name() {
        return name;
    }

    /**
     * {@return the store this node is in, from which {@link Zarr#open(Store, String)} opens the hierarchy's
     * other nodes, such as one a relative path such as {@code ../../} names}
     */
    public Store store() {
        return store;
    }

    /** {@return whether this node is a group} */
    public abstract boolean isGroup();

    /**
     * {@return the Zarr format this node's metadata is stored in: 3, a {@code zarr.json}; or 2, a
     * {@code .zarray} or {@code .zgroup} with its attributes in {@code .zattrs}} The groups and arrays
     * created in a group take its format.
     */
    public abstract int zarrFormat();

    /** {@return whether this node is an array} */
    public boolean isArray() {
        return !isGroup();
    }

    /**
     * {@return this node's user attributes, as this handle was opened with them; an empty object when none
     * are stored}
     */
    public abstract JsonObject attributes();

    /**
     * Replaces this node's attributes with {@code attributes} and returns a handle that has them; this
     * handle keeps the attributes it was opened with. The node's stored metadata is read again and
     * rewritten with only its attributes changed, so every other member survives (a v2 node's
     * {@code .zattrs} is rewritten). Consolidated metadata that lists the node is not updated: it is a
     * snapshot, stale until {@link ZarrGroup#consolidate()} runs again.
     *
     * @param attributes the node's new attributes
     * @return a handle on this node with the new attributes
     * @throws UnsupportedOperationException if the store is read-only
     * @throws ZarrFormatException           if the node's metadata is no longer stored, or is malformed
     * @throws IllegalStateException         if the node stored there is no longer of this node's kind
     */
    public abstract ZarrNode setAttributes(JsonObject attributes);

    /**
     * Merges {@code changes} into this node's attributes and returns a handle that has the result: each
     * top-level member of {@code changes} is added or replaces the stored one, and the rest are kept, as
     * zarr-python's {@code attrs.update} does. Otherwise as {@link #setAttributes}.
     *
     * @param changes the members to add or replace
     * @return a handle on this node with the merged attributes
     * @throws UnsupportedOperationException if the store is read-only
     * @throws ZarrFormatException           if the node's metadata is no longer stored, or is malformed
     * @throws IllegalStateException         if the node stored there is no longer of this node's kind
     */
    public abstract ZarrNode updateAttributes(JsonObject changes);

    /** {@code current}'s members with {@code changes}' added or replacing them. */
    static JsonObject merge(JsonObject current, JsonObject changes) {
        JsonObject.Builder b = JsonObject.builder();
        current.members().forEach(b::put);
        changes.members().forEach(b::put);
        return b.build();
    }

    /**
     * Rewrites this node's stored attributes to {@code change.apply(stored attributes)} and returns the
     * node's metadata as now stored. The new metadata is parsed before anything is written, so a failure
     * leaves the store as it was.
     */
    NodeMetadata rewriteAttributes(UnaryOperator<JsonObject> change) {
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }
        String v3Key = metadataKey(path);
        Optional<byte[]> v3 = store.get(v3Key);
        String writeKey;
        byte[] written;
        NodeMetadata meta;
        if (v3.isPresent()) {
            JsonObject doc = parseObject(v3.get(), v3Key);
            JsonValue stored = doc.find("attributes").orElse(JsonObject.builder().build());
            if (!(stored instanceof JsonObject current)) {
                throw new ZarrFormatException(v3Key + ".attributes: expected object, was " + stored.typeName());
            }
            written = Json.writeBytes(withMember(doc, "attributes", Objects.requireNonNull(change.apply(current))));
            meta = Metadata.parse(written, v3Key);
            writeKey = v3Key;
        } else {
            String zarrayKey = key(path, V2Metadata.ZARRAY);
            String zgroupKey = key(path, V2Metadata.ZGROUP);
            Optional<byte[]> zarray = store.get(zarrayKey);
            Optional<byte[]> zgroup = zarray.isPresent() ? Optional.empty() : store.get(zgroupKey);
            if (zarray.isEmpty() && zgroup.isEmpty()) {
                throw new ZarrFormatException("no zarr.json, .zarray, or .zgroup at '" + display() + "'");
            }
            writeKey = key(path, V2Metadata.ZATTRS);
            JsonObject current = store.get(writeKey).map(b -> parseObject(b, writeKey))
                    .orElse(JsonObject.builder().build());
            written = Json.writeBytes(Objects.requireNonNull(change.apply(current)));
            meta = zarray.isPresent() ? V2Metadata.parseArray(zarray.get(), written, zarrayKey)
                    : V2Metadata.parseGroup(zgroup.get(), written, zgroupKey);
        }
        NodeType expected = isGroup() ? NodeType.GROUP : NodeType.ARRAY;
        if (meta.nodeType() != expected) {
            throw new IllegalStateException("the node at '" + display() + "' is now a "
                    + meta.nodeType().name().toLowerCase(java.util.Locale.ROOT) + ", not the "
                    + expected.name().toLowerCase(java.util.Locale.ROOT) + " this handle was opened on");
        }
        store.set(writeKey, written);
        return meta;
    }

    /** {@code o} with {@code key} set to {@code value}: in its place if present, else last. */
    static JsonObject withMember(JsonObject o, String key, JsonValue value) {
        JsonObject.Builder b = JsonObject.builder();
        o.members().forEach(b::put);
        return b.put(key, value).build();
    }

    /** Parses stored JSON that must be an object, reporting either failure as {@link ZarrFormatException}. */
    static JsonObject parseObject(byte[] bytes, String key) {
        JsonValue v;
        try {
            v = Json.parse(bytes);
        } catch (JsonException e) {
            throw new ZarrFormatException("malformed JSON in '" + key + "': " + e.getMessage(), e);
        }
        if (v instanceof JsonObject o) {
            return o;
        }
        throw new ZarrFormatException(key + ": expected object, was " + v.typeName());
    }

    /**
     * This node as a {@link ZarrGroup}.
     *
     * @return this node
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
     * @return this node
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

    /**
     * {@code relativePath} checked as a path below a node: one or more names joined by {@code '/'}, none
     * empty, {@code "."}, or {@code ".."} (F9).
     *
     * @throws IllegalArgumentException if it is not such a path
     */
    static String relativePath(String relativePath) {
        if (relativePath.isEmpty()) {
            throw new IllegalArgumentException("invalid path: it is empty");
        }
        for (String name : relativePath.split("/", -1)) {
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("invalid path '" + relativePath + "': "
                        + (name.isEmpty() ? "an empty name" : "'" + name + "' cannot name a node"));
            }
        }
        return relativePath;
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
        deleteTree(store, path);
    }

    /**
     * Deletes every key under the node at {@code path} (for the root, every key in the store), the node's
     * own metadata keys first, so a delete cut short leaves no node that reads what is left.
     */
    static void deleteTree(Store store, String path) {
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

    /**
     * Writes the metadata of a new group at {@code path}: its {@code zarr.json}, or for Zarr v2 its
     * {@code .zattrs} and then its {@code .zgroup}, as zarr-python writes them, so the group appears only once
     * its attributes are stored.
     */
    static void writeGroup(Store store, String path, JsonObject attributes, int zarrFormat) {
        if (zarrFormat == 2) {
            store.set(key(path, V2Metadata.ZATTRS), Json.writeBytes(attributes));
            store.set(key(path, V2Metadata.ZGROUP),
                    Json.writeBytes(JsonObject.builder().put("zarr_format", 2).build()));
        } else {
            store.set(metadataKey(path), Json.writeBytes(ZarrGroup.groupJson(attributes)));
        }
    }

    /**
     * Writes the metadata of a new array at {@code path}, {@code metadata} being the spec's document in
     * {@code zarrFormat}: its {@code zarr.json}, or for Zarr v2 its {@code .zattrs} and then its
     * {@code .zarray}, as zarr-python writes them, so the array appears only once its attributes are stored.
     */
    static void writeArray(Store store, String path, ArraySpec spec, JsonObject metadata, int zarrFormat) {
        if (zarrFormat == 2) {
            store.set(key(path, V2Metadata.ZATTRS), Json.writeBytes(spec.attributes()));
            store.set(key(path, V2Metadata.ZARRAY), Json.writeBytes(metadata));
        } else {
            store.set(metadataKey(path), Json.writeBytes(metadata));
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
        return open(store, path, true);
    }

    /** As {@link #open(Store, String)}, with a group using its consolidated metadata if {@code useConsolidated}. */
    static ZarrNode open(Store store, String path, boolean useConsolidated) {
        return tryOpen(store, path, useConsolidated, false).orElseThrow(() -> new ZarrFormatException(
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
        return tryOpen(store, path, true, false);
    }

    /**
     * As {@link #tryOpen(Store, String)}. With {@code useConsolidated}, a v3 group whose {@code zarr.json}
     * carries consolidated metadata answers from it, and so do the groups opened through it; with
     * {@code v2Consolidated} as well, a v2 group fetches and answers from its {@code .zmetadata}, one more
     * request, which zarr-python makes only for the group a hierarchy is opened at.
     *
     * @throws ZarrFormatException      if the node's metadata, or the consolidated metadata it would use, is
     *                                  malformed
     * @throws ZarrUnsupportedException if the node uses an unimplemented feature
     */
    static Optional<ZarrNode> tryOpen(Store store, String path, boolean useConsolidated, boolean v2Consolidated) {
        String v3Key = metadataKey(path);
        Optional<byte[]> v3 = store.get(v3Key);
        if (v3.isPresent()) {
            return Optional.of(switch (Metadata.parse(v3.get(), v3Key)) {
                case GroupMetadata g -> new ZarrGroup(store, path, g, useConsolidated
                        ? g.consolidatedMetadata().flatMap(m -> ConsolidatedMetadata.parseV3(m, v3Key))
                                .map(m -> new Snapshot(m, v3Key)).orElse(null)
                        : null, "", useConsolidated);
                case ArrayMetadata a -> new ZarrArray(store, path, a);
            });
        }
        Optional<byte[]> zarray = store.get(key(path, V2Metadata.ZARRAY));
        if (zarray.isPresent()) {
            return Optional.of(new ZarrArray(store, path,
                    V2Metadata.parseArray(zarray.get(), attrs(store, path), key(path, V2Metadata.ZARRAY))));
        }
        Optional<byte[]> zgroup = store.get(key(path, V2Metadata.ZGROUP));
        if (zgroup.isEmpty()) {
            return Optional.empty();
        }
        GroupMetadata g = V2Metadata.parseGroup(zgroup.get(), attrs(store, path), key(path, V2Metadata.ZGROUP));
        Snapshot snapshot = null;
        if (useConsolidated && v2Consolidated) {
            String zmetadataKey = key(path, V2Metadata.ZMETADATA);
            snapshot = store.get(zmetadataKey)
                    .flatMap(b -> ConsolidatedMetadata.parseV2(b, zmetadataKey))
                    .map(m -> new Snapshot(m, zmetadataKey)).orElse(null);
        }
        return Optional.of(new ZarrGroup(store, path, g, snapshot, "", useConsolidated));
    }

    private static byte[] attrs(Store store, String path) {
        return store.get(key(path, V2Metadata.ZATTRS)).orElse(null);
    }
}
