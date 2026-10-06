package com.ebremer.falcon.zarr;

import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.metadata.ArrayMetadata;
import com.ebremer.falcon.zarr.metadata.ConsolidatedMetadata;
import com.ebremer.falcon.zarr.metadata.GroupMetadata;
import com.ebremer.falcon.zarr.metadata.Metadata;
import com.ebremer.falcon.zarr.metadata.NodeMetadata;
import com.ebremer.falcon.zarr.metadata.V2Metadata;
import com.ebremer.falcon.zarr.store.Store;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A group in a Zarr hierarchy: a named collection of child nodes.
 *
 * <p>Children are discovered from the store: a child is a subdirectory of this group's path that
 * contains its own {@code zarr.json} (or v2 {@code .zarray}/{@code .zgroup}). Each accessor reads the
 * store on demand, so a group reflects the store's current contents rather than a cached snapshot.
 *
 * <p><b>Consolidated metadata.</b> A group may store a snapshot of the metadata of every node below it
 * (written by {@link #consolidate()} or zarr-python's {@code consolidate_metadata}); {@link Zarr#open(Store)}
 * uses it, as zarr-python 3 does. Such a group, and every group opened through it, answers
 * {@link #childNames()}, {@link #child}, {@link #children()}, {@link #groups()}, {@link #arrays()},
 * {@link #group}, and {@link #array} from the snapshot without reading the store
 * ({@link #isConsolidated()} says which). Arrays still read and write their chunks in the store. The
 * snapshot is what the hierarchy was when it was consolidated: a node created, resized, or given new
 * attributes since is seen as it was until {@code consolidate()} runs again, and so is the metadata of
 * this group's children. Deleting a child through a group ({@link #delete}) is the exception: as in
 * zarr-python, it also removes the child from the consolidated metadata stored with that group, and from
 * the snapshot every handle opened with it answers from.
 */
public final class ZarrGroup extends ZarrNode {

    /**
     * Names a new child may not take, because its key would collide with a metadata key: the v3
     * {@code zarr.json} and the v2 metadata files.
     */
    private static final Set<String> METADATA_NAMES = Set.of(
            "zarr.json", ".zarray", ".zgroup", ".zattrs", ".zmetadata");

    private final GroupMetadata metadata;
    private final Snapshot snapshot;       // the consolidated metadata it answers from, or null: read the store
    private final String snapshotPath;     // this group's path within the snapshot ("" for the group holding it)
    private final boolean useConsolidated; // whether a group opened from the store uses its consolidated metadata

    ZarrGroup(Store store, String path, GroupMetadata metadata, Snapshot snapshot, String snapshotPath,
              boolean useConsolidated) {
        super(store, path);
        this.metadata = metadata;
        this.snapshot = snapshot;
        this.snapshotPath = snapshotPath;
        this.useConsolidated = useConsolidated;
    }

    @Override
    public boolean isGroup() {
        return true;
    }

    @Override
    public JsonObject attributes() {
        return metadata.attributes();
    }

    /** {@inheritDoc} A group answering from consolidated metadata keeps answering from it. */
    @Override
    public ZarrGroup setAttributes(JsonObject attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return withMetadata((GroupMetadata) rewriteAttributes(current -> attributes));
    }

    /** {@inheritDoc} A group answering from consolidated metadata keeps answering from it. */
    @Override
    public ZarrGroup updateAttributes(JsonObject changes) {
        Objects.requireNonNull(changes, "changes");
        return withMetadata((GroupMetadata) rewriteAttributes(current -> merge(current, changes)));
    }

    private ZarrGroup withMetadata(GroupMetadata newMetadata) {
        return new ZarrGroup(store, path, newMetadata, snapshot, snapshotPath, useConsolidated);
    }

    /**
     * Whether this group answers {@link #childNames()}, {@link #child}, and the other child accessors from
     * consolidated metadata, a snapshot of the hierarchy below it, rather than from the store. See the
     * class description.
     *
     * @return true if this group answers from a snapshot
     */
    public boolean isConsolidated() {
        return snapshot != null;
    }

    /**
     * The names of this group's direct children, sorted: every subdirectory that holds node metadata
     * (v3 or v2), including a child whose metadata is malformed or unsupported. A consolidated group lists
     * the children its snapshot does.
     *
     * @return the children's names (each a single path segment), sorted; empty if there are none
     * @throws UnsupportedOperationException if the group is not consolidated and the store cannot list its
     *                                       keys
     */
    public List<String> childNames() {
        if (snapshot != null) {
            return snapshot.get().childNames(snapshotPath);
        }
        List<String> names = new ArrayList<>();
        for (String childPath : subdirectories(path)) {
            if (ZarrNode.hasNode(store, childPath)) {
                names.add(childPath.substring(childPath.lastIndexOf('/') + 1));
            }
        }
        return names; // listDir returns sorted entries, so names are already sorted
    }

    /** The paths of the subdirectories of {@code dirPath} in the store, sorted. */
    private List<String> subdirectories(String dirPath) {
        String dir = dirPath.isEmpty() ? "" : dirPath + "/";
        List<String> paths = new ArrayList<>();
        for (String entry : store.listDir(dir)) {
            if (entry.endsWith("/")) { // a child directory ("<name>/"), as opposed to a key
                paths.add(entry.substring(0, entry.length() - 1));
            }
        }
        return paths;
    }

    /**
     * The node at {@code relativePath} below this group, if present: a direct child's name ({@code "temp"})
     * or a path through child groups ({@code "model/layers/weights"}, F9).
     *
     * <p>A consolidated group finds the node in its snapshot, as it finds a direct child. Any other group
     * opens the node's metadata directly, one request whatever the depth, as zarr-python does: the groups
     * along the path are not opened, so a path through something that is not a group is not noticed.
     *
     * @param relativePath a child's name, or names joined by {@code '/'}
     * @return the group or array there, or empty if there is no node there
     * @throws IllegalArgumentException if the path is not one or more names joined by {@code '/'} (no empty
     *                                  name, and no {@code "."} or {@code ".."})
     * @throws ZarrFormatException      if the node's metadata is malformed
     * @throws ZarrUnsupportedException if the node uses an unimplemented feature
     */
    public Optional<ZarrNode> child(String relativePath) {
        String relative = ZarrNode.relativePath(relativePath);
        String childPath = path.isEmpty() ? relative : path + "/" + relative;
        return snapshot != null ? fromSnapshot(relative, childPath)
                : ZarrNode.tryOpen(store, childPath, useConsolidated, false);
    }

    /** The child {@code name} as the snapshot describes it; no store access. */
    private Optional<ZarrNode> fromSnapshot(String name, String childPath) {
        String relative = snapshotPath.isEmpty() ? name : snapshotPath + "/" + name;
        return snapshot.get().entry(relative).<ZarrNode>map(entry -> switch (entry.parse(snapshot.describe(relative))) {
            case GroupMetadata g -> new ZarrGroup(store, childPath, g, snapshot, relative, useConsolidated);
            case ArrayMetadata a -> new ZarrArray(store, childPath, a);
        });
    }

    /**
     * All direct children, groups and arrays, ordered by name. A child that cannot be opened, because its
     * metadata is malformed ({@link ZarrFormatException}) or uses something Falcon does not implement
     * ({@link ZarrUnsupportedException}), is left out, so one such child does not hide the rest:
     * {@link #childNames()} still lists it, and {@link #child(String)} reports why it fails. Any other
     * failure, such as the store failing to read, is thrown.
     *
     * @return the children that open
     * @throws UnsupportedOperationException if the group is not consolidated and the store cannot list its
     *                                       keys
     */
    public List<ZarrNode> children() {
        List<ZarrNode> result = new ArrayList<>();
        if (snapshot != null) {
            for (String name : childNames()) {
                try {
                    fromSnapshot(name, key(path, name)).ifPresent(result::add);
                } catch (ZarrFormatException | ZarrUnsupportedException e) {
                    // left out, as documented: child(name) reports it
                }
            }
            return result;
        }
        for (String childPath : subdirectories(path)) {
            try {
                ZarrNode.tryOpen(store, childPath, useConsolidated, false).ifPresent(result::add);
            } catch (ZarrFormatException | ZarrUnsupportedException e) {
                // left out, as documented: child(name) reports it
            }
        }
        return result;
    }

    /**
     * The direct child groups, ordered by name. Like {@link #children()}, it leaves out a child that cannot
     * be opened.
     *
     * @return the child groups that open
     * @throws UnsupportedOperationException if the group is not consolidated and the store cannot list its
     *                                       keys
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
     *
     * @return the child arrays that open
     * @throws UnsupportedOperationException if the group is not consolidated and the store cannot list its
     *                                       keys
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
     * The group at {@code relativePath} below this one: a direct child's name, or a path through child
     * groups, as {@link #child(String)} takes it.
     *
     * @param relativePath a child's name, or names joined by {@code '/'}
     * @return the group there
     * @throws NoSuchElementException   if there is no node there
     * @throws IllegalArgumentException if the node is an array, or the path is invalid
     * @throws ZarrFormatException      if the child's metadata is malformed
     * @throws ZarrUnsupportedException if the child uses an unimplemented feature
     */
    public ZarrGroup group(String relativePath) {
        return requireChild(relativePath, ZarrGroup.class, "group");
    }

    /**
     * The array at {@code relativePath} below this group: a direct child's name, or a path through child
     * groups, as {@link #child(String)} takes it.
     *
     * @param relativePath a child's name, or names joined by {@code '/'}
     * @return the array there
     * @throws NoSuchElementException   if there is no node there
     * @throws IllegalArgumentException if the node is a group, or the path is invalid
     * @throws ZarrFormatException      if the child's metadata is malformed
     * @throws ZarrUnsupportedException if the child uses an unimplemented feature
     */
    public ZarrArray array(String relativePath) {
        return requireChild(relativePath, ZarrArray.class, "array");
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
     * Creates a child group, with no attributes.
     *
     * @param name the child's name, a single path segment
     * @return the new group
     * @throws IllegalArgumentException      if the name is invalid or a node already has it
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrGroup createGroup(String name) {
        return createGroup(name, new JsonObject(java.util.Map.of()), false);
    }

    /**
     * Creates a child group with the given attributes.
     *
     * @param name       the child's name, a single path segment
     * @param attributes the group's attributes
     * @return the new group
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
     * @param name       the child's name, a single path segment
     * @param attributes the group's attributes
     * @param overwrite  whether to delete everything stored under the name first
     * @return the new group
     * @throws IllegalArgumentException      if the name is invalid, or a node has it and {@code overwrite}
     *                                       is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrGroup createGroup(String name, JsonObject attributes, boolean overwrite) {
        String childPath = newChildPath(name);
        ZarrNode.prepareCreate(store, childPath, false, overwrite);
        store.set(ZarrNode.metadataKey(childPath), Json.writeBytes(groupJson(attributes)));
        return ZarrNode.open(store, childPath, useConsolidated).asGroup();
    }

    /**
     * Creates a child array described by {@code spec}.
     *
     * @param name the child's name, a single path segment
     * @param spec the array's description
     * @return the new array
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
     * @param name      the child's name, a single path segment
     * @param spec      the array's description
     * @param overwrite whether to delete everything stored under the name first
     * @return the new array
     * @throws IllegalArgumentException      if the name is invalid, or anything is stored under it and
     *                                       {@code overwrite} is false
     * @throws UnsupportedOperationException if the store is read-only
     */
    public ZarrArray createArray(String name, ArraySpec spec, boolean overwrite) {
        String childPath = newChildPath(name);
        ZarrNode.prepareCreate(store, childPath, true, overwrite);
        store.set(ZarrNode.metadataKey(childPath), Json.writeBytes(spec.toJson()));
        return ZarrNode.open(store, childPath, useConsolidated).asArray();
    }

    /**
     * Deletes the direct child {@code name}: every key stored under it, an array's chunks or a group and
     * all its descendants, the child's own metadata first, so a delete cut short leaves no node that reads
     * what is left. As in zarr-python, the child is also removed from the consolidated metadata stored with
     * this group, if it has any, and from the snapshot this handle (and every handle opened with it)
     * answers from; consolidated metadata stored with a group further up is not changed, and lists the
     * child until it is consolidated again. Handles already open on the child or below it are not
     * invalidated: their reads see fill values and missing metadata.
     *
     * @param name the child's name, a single path segment
     * @throws IllegalArgumentException      if the name cannot name a child
     * @throws UnsupportedOperationException if the store is read-only, or cannot list its keys
     * @throws NoSuchElementException        if there is no child of that name, in the store or the snapshot
     */
    public void delete(String name) {
        String childPath = childPath(name);
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }
        String relative = snapshotPath.isEmpty() ? name : snapshotPath + "/" + name;
        boolean listed = snapshot != null && snapshot.get().entry(relative).isPresent();
        if (!listed && !ZarrNode.hasNode(store, childPath)) {
            throw new NoSuchElementException("no child '" + name + "' in " + display());
        }
        ZarrNode.deleteTree(store, childPath);
        forgetInStoredSnapshot(name);
        if (snapshot != null) {
            snapshot.remove(relative);
        }
    }

    /** Removes the child {@code name} from the consolidated metadata stored with this group, if it has any. */
    private void forgetInStoredSnapshot(String name) {
        String key = metadataKey(path);
        Optional<byte[]> v3 = store.get(key);
        if (v3.isPresent()) {
            JsonObject doc = parseObject(v3.get(), key);
            Optional<JsonValue> member = doc.find("consolidated_metadata");
            if (member.isPresent()) {
                JsonValue kept = ConsolidatedMetadata.withoutV3(member.get(), name);
                if (kept != member.get()) {
                    store.set(key, Json.writeBytes(withMember(doc, "consolidated_metadata", kept)));
                }
            }
            return;
        }
        String zmetadataKey = key(path, V2Metadata.ZMETADATA);
        Optional<byte[]> zmetadata = store.get(zmetadataKey);
        if (zmetadata.isPresent()) {
            JsonObject doc = parseObject(zmetadata.get(), zmetadataKey);
            JsonObject kept = ConsolidatedMetadata.withoutV2(doc, name);
            if (kept != doc) {
                store.set(zmetadataKey, Json.writeBytes(kept));
            }
        }
    }

    /**
     * Consolidates the metadata of every node below this group into this group's {@code zarr.json}, as
     * zarr-python's {@code consolidate_metadata} does, and returns a handle that answers from it. A reader
     * then learns the whole hierarchy below the group from one fetch.
     *
     * <p>Each descendant's metadata is read from the store, never from an earlier snapshot, and embedded
     * as stored, in zarr-python 3.4's layout (an inline {@code consolidated_metadata} member listing every
     * node by its path relative to this group), so zarr-python reads it with {@code use_consolidated=True}.
     * This group's {@code zarr.json} is rewritten with only that member changed. A node Falcon cannot read
     * because it uses an unimplemented feature is embedded too, for readers that can; a node whose
     * metadata is malformed stops the consolidation, since a snapshot without it would hide it from every
     * reader of the snapshot.
     *
     * <p>The result is a snapshot: see the class description for what later changes do to it. The walk and
     * the write are separate store calls, not one atomic step, so consolidate when nothing else is changing
     * the hierarchy.
     *
     * @return a handle on this group that answers from the new consolidated metadata
     * @throws UnsupportedOperationException if the store is read-only, or cannot list its keys; or if this
     *                                       group or a node below it is a Zarr v2 node (Falcon writes v3
     *                                       metadata only)
     * @throws ZarrFormatException           if the metadata of this group or a node below it is malformed
     */
    public ZarrGroup consolidate() {
        if (!store.isWritable()) {
            throw new UnsupportedOperationException("store is read-only");
        }
        String key = metadataKey(path);
        Optional<byte[]> stored = store.get(key);
        if (stored.isEmpty()) {
            if (store.exists(key(path, V2Metadata.ZGROUP))) {
                throw new UnsupportedOperationException("'" + display()
                        + "' is a Zarr v2 group; consolidate() writes v3 consolidated metadata only");
            }
            throw new ZarrFormatException("no zarr.json at '" + display() + "'");
        }
        JsonObject doc = parseObject(stored.get(), key);
        Map<String, JsonObject> documents = new LinkedHashMap<>();
        collect(path, "", documents);
        JsonObject member = ConsolidatedMetadata.inline(documents);
        byte[] written = Json.writeBytes(withMember(doc, "consolidated_metadata", member));
        NodeMetadata meta = Metadata.parse(written, key);
        if (!(meta instanceof GroupMetadata group)) {
            throw new IllegalStateException("the node at '" + display() + "' is now an array, not a group");
        }
        ConsolidatedMetadata consolidated = ConsolidatedMetadata.parseV3(member, key).orElseThrow();
        store.set(key, written);
        return new ZarrGroup(store, path, group, new Snapshot(consolidated, key), "", true);
    }

    /**
     * Adds the {@code zarr.json} document of every node below the group at {@code groupPath} to {@code out},
     * keyed by its path relative to the consolidating group ({@code relative} is the group's own).
     */
    private void collect(String groupPath, String relative, Map<String, JsonObject> out) {
        for (String childPath : subdirectories(groupPath)) {
            String name = childPath.substring(childPath.lastIndexOf('/') + 1);
            String childRelative = relative.isEmpty() ? name : relative + "/" + name;
            String key = metadataKey(childPath);
            Optional<byte[]> bytes = store.get(key);
            if (bytes.isEmpty()) {
                if (store.exists(key(childPath, V2Metadata.ZARRAY)) || store.exists(key(childPath, V2Metadata.ZGROUP))) {
                    throw new UnsupportedOperationException("'" + childPath
                            + "' is a Zarr v2 node; consolidate() writes v3 consolidated metadata only");
                }
                continue; // no node here, as childNames() leaves such a directory out
            }
            JsonObject doc = parseObject(bytes.get(), key);
            try {
                Metadata.parse(doc, key);
            } catch (ZarrUnsupportedException e) {
                // valid Zarr that Falcon cannot read: embedded as stored, for readers that can
            }
            out.put(childRelative, doc);
            if (doc.members().get("node_type") instanceof JsonString type && type.value().equals("group")) {
                collect(childPath, childRelative, out);
            }
        }
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
