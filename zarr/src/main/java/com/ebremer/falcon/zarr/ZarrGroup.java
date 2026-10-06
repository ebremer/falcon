package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.metadata.GroupMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * A group in a Zarr hierarchy: a named collection of child nodes.
 *
 * <p>Children are discovered from the store: a child is a subdirectory of this group's path that
 * contains its own {@code zarr.json}. Each accessor reads the store on demand, so a group reflects the
 * store's current contents rather than a cached snapshot.
 */
public final class ZarrGroup extends ZarrNode {

    private final GroupMetadata metadata;

    ZarrGroup(Store store, String path, GroupMetadata metadata) {
        super(store, path);
        this.metadata = metadata;
    }

    @Override
    public boolean isGroup() {
        return true;
    }

    @Override
    public JsonObject attributes() {
        return metadata.attributes();
    }

    /** The names of this group's direct children, sorted. */
    public List<String> childNames() {
        String dir = path.isEmpty() ? "" : path + "/";
        List<String> names = new ArrayList<>();
        for (String entry : store.listDir(dir)) {
            // A child is a subdirectory ("<name>/") that holds its own node metadata (v3 or v2).
            if (entry.endsWith("/")) {
                String childPath = entry.substring(0, entry.length() - 1);
                if (ZarrNode.hasNode(store, childPath)) {
                    names.add(entry.substring(dir.length(), entry.length() - 1));
                }
            }
        }
        return names; // listDir returns sorted entries, so names are already sorted
    }

    /** The direct child with the given name, if present. */
    public Optional<ZarrNode> child(String childName) {
        String childPath = childPath(childName);
        if (!ZarrNode.hasNode(store, childPath)) {
            return Optional.empty();
        }
        return Optional.of(ZarrNode.open(store, childPath));
    }

    /** All direct children, groups and arrays, ordered by name. */
    public List<ZarrNode> children() {
        List<ZarrNode> result = new ArrayList<>();
        for (String childName : childNames()) {
            child(childName).ifPresent(result::add);
        }
        return result;
    }

    /** The direct child groups, ordered by name. */
    public List<ZarrGroup> groups() {
        List<ZarrGroup> result = new ArrayList<>();
        for (ZarrNode node : children()) {
            if (node instanceof ZarrGroup group) {
                result.add(group);
            }
        }
        return result;
    }

    /** The direct child arrays, ordered by name. */
    public List<ZarrArray> arrays() {
        List<ZarrArray> result = new ArrayList<>();
        for (ZarrNode node : children()) {
            if (node instanceof ZarrArray array) {
                result.add(array);
            }
        }
        return result;
    }

    /** The direct child group with the given name. */
    public ZarrGroup group(String name) {
        return requireChild(name, ZarrGroup.class, "group");
    }

    /** The direct child array with the given name. */
    public ZarrArray array(String name) {
        return requireChild(name, ZarrArray.class, "array");
    }

    private <T extends ZarrNode> T requireChild(String childName, Class<T> kind, String label) {
        ZarrNode node = child(childName).orElseThrow(
                () -> new NoSuchElementException("no child '" + childName + "' in " + display()));
        if (!kind.isInstance(node)) {
            throw new IllegalArgumentException(
                    "'" + childName + "' in " + display() + " is not a " + label);
        }
        return kind.cast(node);
    }

    /**
     * Creates a child group.
     *
     * @throws IllegalArgumentException      if the name is invalid or a node already has it
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrGroup createGroup(String name) {
        return createGroup(name, new JsonObject(java.util.Map.of()), false);
    }

    /**
     * Creates a child group with the given attributes.
     *
     * @throws IllegalArgumentException      if the name is invalid or a node already has it
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrGroup createGroup(String name, JsonObject attributes) {
        return createGroup(name, attributes, false);
    }

    /**
     * Creates a child group with the given attributes. With {@code overwrite}, everything stored under the
     * name is deleted first: an old array's chunks, or an old group and all its descendants. Without it, a
     * name that a node already has is refused.
     *
     * @throws IllegalArgumentException      if the name is invalid, or a node has it and {@code overwrite}
     *                                       is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrGroup createGroup(String name, JsonObject attributes, boolean overwrite) {
        String childPath = childPath(name);
        ZarrNode.prepareCreate(store, childPath, false, overwrite);
        store.set(ZarrNode.metadataKey(childPath), Json.writeBytes(groupJson(attributes)));
        return ZarrNode.open(store, childPath).asGroup();
    }

    /**
     * Creates a child array described by {@code spec}.
     *
     * @throws IllegalArgumentException      if the name is invalid, or anything is stored under it (a node,
     *                                       or keys a new array would read as its chunks)
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrArray createArray(String name, ArraySpec spec) {
        return createArray(name, spec, false);
    }

    /**
     * Creates a child array described by {@code spec}. With {@code overwrite}, everything stored under the
     * name is deleted first: an old array's chunks, or an old group and all its descendants. Without it,
     * the name is refused if anything is stored under it, a node or keys a new array would read as its
     * chunks.
     *
     * @throws IllegalArgumentException      if the name is invalid, or anything is stored under it and
     *                                       {@code overwrite} is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrArray createArray(String name, ArraySpec spec, boolean overwrite) {
        String childPath = childPath(name);
        ZarrNode.prepareCreate(store, childPath, true, overwrite);
        store.set(ZarrNode.metadataKey(childPath), Json.writeBytes(spec.toJson()));
        return ZarrNode.open(store, childPath).asArray();
    }

    /** The {@code zarr.json} document for a group with the given attributes. */
    static JsonObject groupJson(JsonObject attributes) {
        return JsonObject.builder()
                .put("zarr_format", Zarr.ZARR_FORMAT)
                .put("node_type", "group")
                .put("attributes", attributes)
                .build();
    }

    private String childPath(String childName) {
        if (childName.isEmpty() || childName.indexOf('/') >= 0) {
            throw new IllegalArgumentException("invalid child name: '" + childName + "'");
        }
        return path.isEmpty() ? childName : path + "/" + childName;
    }

    @Override
    public String toString() {
        return "ZarrGroup[" + display() + " children=" + childNames() + "]";
    }
}
