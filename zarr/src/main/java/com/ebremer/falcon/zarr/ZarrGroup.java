package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.metadata.GroupMetadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * A group in a Zarr hierarchy: a named collection of child nodes.
 *
 * <p>Children are discovered from the store: a child is a subdirectory of this group's path that
 * contains its own {@code zarr.json}. Each accessor reads the store on demand, so a group reflects the
 * store's current contents rather than a cached snapshot.
 */
public final class ZarrGroup extends ZarrNode {

    /**
     * Names a new child may not take, because its key would collide with a metadata key: the v3
     * {@code zarr.json} and the v2 metadata files.
     */
    private static final Set<String> METADATA_NAMES = Set.of(
            "zarr.json", ".zarray", ".zgroup", ".zattrs", ".zmetadata");

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

    /**
     * The names of this group's direct children, sorted: every subdirectory that holds node metadata
     * (v3 or v2), including a child whose metadata is malformed or unsupported.
     */
    public List<String> childNames() {
        List<String> names = new ArrayList<>();
        for (String childPath : subdirectories()) {
            if (ZarrNode.hasNode(store, childPath)) {
                names.add(childPath.substring(childPath.lastIndexOf('/') + 1));
            }
        }
        return names; // listDir returns sorted entries, so names are already sorted
    }

    /** The paths of this group's subdirectories in the store, sorted. */
    private List<String> subdirectories() {
        String dir = path.isEmpty() ? "" : path + "/";
        List<String> paths = new ArrayList<>();
        for (String entry : store.listDir(dir)) {
            if (entry.endsWith("/")) { // a child directory ("<name>/"), as opposed to a key
                paths.add(entry.substring(0, entry.length() - 1));
            }
        }
        return paths;
    }

    /**
     * The direct child with the given name, if present.
     *
     * @throws IllegalArgumentException if the name cannot name a child (empty, containing {@code '/'},
     *                                  or {@code "."} / {@code ".."})
     * @throws ZarrFormatException      if the child's metadata is malformed
     * @throws ZarrUnsupportedException if the child uses an unimplemented feature
     */
    public Optional<ZarrNode> child(String childName) {
        return ZarrNode.tryOpen(store, childPath(childName));
    }

    /**
     * All direct children, groups and arrays, ordered by name. A child that cannot be opened, because its
     * metadata is malformed ({@link ZarrFormatException}) or uses something Falcon does not implement
     * ({@link ZarrUnsupportedException}), is left out, so one such child does not hide the rest:
     * {@link #childNames()} still lists it, and {@link #child(String)} reports why it fails. Any other
     * failure, such as the store failing to read, is thrown.
     */
    public List<ZarrNode> children() {
        List<ZarrNode> result = new ArrayList<>();
        for (String childPath : subdirectories()) {
            try {
                ZarrNode.tryOpen(store, childPath).ifPresent(result::add);
            } catch (ZarrFormatException | ZarrUnsupportedException e) {
                // left out, as documented: child(name) reports it
            }
        }
        return result;
    }

    /**
     * The direct child groups, ordered by name. Like {@link #children()}, it leaves out a child that cannot
     * be opened.
     */
    public List<ZarrGroup> groups() {
        List<ZarrGroup> result = new ArrayList<>();
        for (ZarrNode node : children()) {
            if (node instanceof ZarrGroup group) {
                result.add(group);
            }
        }
        return result;
    }

    /**
     * The direct child arrays, ordered by name. Like {@link #children()}, it leaves out a child that cannot
     * be opened.
     */
    public List<ZarrArray> arrays() {
        List<ZarrArray> result = new ArrayList<>();
        for (ZarrNode node : children()) {
            if (node instanceof ZarrArray array) {
                result.add(array);
            }
        }
        return result;
    }

    /**
     * The direct child group with the given name.
     *
     * @throws NoSuchElementException   if there is no child of that name
     * @throws IllegalArgumentException if the child is an array, or the name cannot name a child
     * @throws ZarrFormatException      if the child's metadata is malformed
     * @throws ZarrUnsupportedException if the child uses an unimplemented feature
     */
    public ZarrGroup group(String name) {
        return requireChild(name, ZarrGroup.class, "group");
    }

    /**
     * The direct child array with the given name.
     *
     * @throws NoSuchElementException   if there is no child of that name
     * @throws IllegalArgumentException if the child is a group, or the name cannot name a child
     * @throws ZarrFormatException      if the child's metadata is malformed
     * @throws ZarrUnsupportedException if the child uses an unimplemented feature
     */
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
        String childPath = newChildPath(name);
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
        String childPath = newChildPath(name);
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

    /**
     * The path of the child {@code childName}, which must be one path segment: not empty, no {@code '/'},
     * and not {@code "."} or {@code ".."}.
     */
    private String childPath(String childName) {
        if (childName.isEmpty() || childName.indexOf('/') >= 0
                || childName.equals(".") || childName.equals("..")) {
            throw new IllegalArgumentException("invalid child name: '" + childName + "'");
        }
        return path.isEmpty() ? childName : path + "/" + childName;
    }

    /**
     * The path of a new child {@code name}, checked against the v3 specification's rules for node names
     * (not made only of periods, not starting with the reserved {@code "__"}), against the metadata key
     * names it would collide with ({@code zarr.json}, {@code .zarray}, ...), and for portability: a name
     * ending in {@code '.'} or a space is refused, since Windows drops those characters and would store
     * {@code "data."} as {@code "data"}.
     */
    private String newChildPath(String name) {
        String childPath = childPath(name);
        String reason = name.chars().allMatch(c -> c == '.') ? "it is made only of periods"
                : name.startsWith("__") ? "the prefix \"__\" is reserved by the Zarr specification"
                : METADATA_NAMES.contains(name) ? "it is the name of a metadata key"
                : name.endsWith(".") || name.endsWith(" ") ? "it ends in '.' or a space, which Windows drops"
                : null;
        if (reason != null) {
            throw new IllegalArgumentException("invalid name for a new node: '" + name + "': " + reason);
        }
        return childPath;
    }

    /** The group's path; no store access (listing children here once walked the whole store). */
    @Override
    public String toString() {
        return "ZarrGroup[" + display() + "]";
    }
}
