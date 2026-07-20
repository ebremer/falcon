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
            // A child is a subdirectory ("<name>/") that has its own zarr.json.
            if (entry.endsWith("/") && store.exists(entry + "zarr.json")) {
                names.add(entry.substring(dir.length(), entry.length() - 1));
            }
        }
        return names; // listDir returns sorted entries, so names are already sorted
    }

    /** The direct child with the given name, if present. */
    public Optional<ZarrNode> child(String childName) {
        String childPath = childPath(childName);
        if (!store.exists(ZarrNode.metadataKey(childPath))) {
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

    /** Creates (or replaces) a child group. */
    public ZarrGroup createGroup(String name) {
        return createGroup(name, new JsonObject(java.util.Map.of()));
    }

    /** Creates (or replaces) a child group with the given attributes. */
    public ZarrGroup createGroup(String name, JsonObject attributes) {
        String childPath = childPath(name);
        store.set(ZarrNode.metadataKey(childPath), Json.writeBytes(groupJson(attributes)));
        return ZarrNode.open(store, childPath).asGroup();
    }

    /** Creates (or replaces) a child array described by {@code spec}. */
    public ZarrArray createArray(String name, ArraySpec spec) {
        String childPath = childPath(name);
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
