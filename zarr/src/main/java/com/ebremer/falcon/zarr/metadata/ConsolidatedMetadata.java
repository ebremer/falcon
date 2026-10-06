package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import com.ebremer.falcon.zarr.store.StoreKeys;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Consolidated metadata: a snapshot of the metadata of every node below a group, kept with the group so a
 * reader learns the whole hierarchy from one fetch instead of one per node. zarr-python writes two forms:
 *
 * <ul>
 *   <li><b>v3</b>: a {@code consolidated_metadata} member of the group's {@code zarr.json},
 *       {@code {"kind": "inline", "must_understand": false, "metadata": {...}}}. Its {@code metadata} maps
 *       each node's path relative to the group ({@code "g/inner"}) to that node's {@code zarr.json}
 *       document. The map is flat: a nested group's own entry carries an empty
 *       {@code consolidated_metadata}, and its children are listed under their full relative paths.</li>
 *   <li><b>v2</b>: a {@code .zmetadata} document beside the root group's {@code .zgroup},
 *       {@code {"zarr_consolidated_format": 1, "metadata": {...}}}, mapping keys relative to the group
 *       ({@code "g/.zgroup"}, {@code "g/a/.zarray"}, {@code "g/a/.zattrs"}) to those documents.</li>
 * </ul>
 *
 * <p>Parsing checks the snapshot's own structure: it must be well formed, each path a valid relative key,
 * and each path's parent a group in the snapshot. An entry's content is parsed only when it is read
 * ({@link Entry#parse}), so one malformed or unsupported node affects only itself, as when nodes are read
 * one at a time. Instances are immutable.
 */
public final class ConsolidatedMetadata {

    /** zarr-python's empty marker for a group in the flat map: its children are listed beside it. */
    private static final JsonObject EMPTY_INLINE = JsonObject.builder()
            .put("kind", "inline").put("must_understand", false).put("metadata", Fields.EMPTY_OBJECT).build();

    /**
     * The order zarr-python 3.4 writes paths in: by depth, then by the NFKC-normalized, case-folded path.
     * Java has no full Unicode case folding; upper- then lower-casing matches Python's {@code casefold()}
     * for every case that matters here (it folds {@code ß} to {@code ss}, as casefold does). Paths that
     * still tie are ordered by code point, so the output is deterministic.
     */
    private static final Comparator<String> WRITE_ORDER = Comparator
            .comparingInt(ConsolidatedMetadata::depth)
            .thenComparing(ConsolidatedMetadata::fold, ConsolidatedMetadata::compareCodePoints)
            .thenComparing(ConsolidatedMetadata::compareCodePoints);

    /** One node's metadata in the snapshot, as stored. */
    public static final class Entry {

        private final JsonObject document;    // v3: the zarr.json; v2: the .zarray or .zgroup document
        private final JsonValue v2Attributes; // v2 only: the .zattrs document, or null if none
        private final boolean v2;
        private final boolean group;

        private Entry(JsonObject document, JsonValue v2Attributes, boolean v2, boolean group) {
            this.document = document;
            this.v2Attributes = v2Attributes;
            this.v2 = v2;
            this.group = group;
        }

        /** Whether the entry describes a group (whose children the snapshot may list). */
        public boolean isGroup() {
            return group;
        }

        /**
         * Parses the entry as opening the node from its own metadata would. {@code ctx} names the entry in
         * diagnostics.
         *
         * @throws ZarrFormatException      if the node's metadata is malformed
         * @throws ZarrUnsupportedException if the node uses an unimplemented feature
         */
        public NodeMetadata parse(String ctx) {
            if (!v2) {
                return Metadata.parse(document, ctx);
            }
            return group ? V2Metadata.parseGroup(document, v2Attributes, ctx)
                    : V2Metadata.parseArray(document, v2Attributes, ctx);
        }
    }

    private final Map<String, Entry> entries;         // relative path -> entry
    private final Map<String, List<String>> children; // a group's relative path ("" for the snapshot's own) -> sorted names

    private ConsolidatedMetadata(Map<String, Entry> entries, String ctx) {
        Map<String, List<String>> index = new HashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            String path = e.getKey();
            int slash = path.lastIndexOf('/');
            String parent = slash < 0 ? "" : path.substring(0, slash);
            if (!parent.isEmpty()) {
                Entry p = entries.get(parent);
                if (p == null || !p.group) {
                    throw new ZarrFormatException(ctx + ": '" + path + "' is listed, but its parent '" + parent
                            + "' is not a group in it");
                }
            }
            index.computeIfAbsent(parent, k -> new ArrayList<>()).add(path.substring(slash + 1));
        }
        for (List<String> names : index.values()) {
            names.sort(null); // as a store lists them
        }
        index.replaceAll((k, v) -> List.copyOf(v));
        this.entries = Collections.unmodifiableMap(entries);
        this.children = Collections.unmodifiableMap(index);
    }

    /** The entry at {@code relativePath} (for example {@code "g/inner"}), if the snapshot lists one. */
    public Optional<Entry> entry(String relativePath) {
        return Optional.ofNullable(entries.get(relativePath));
    }

    /**
     * The names of the direct children of the group at {@code groupPath} ({@code ""} for the snapshot's own
     * group), sorted as a store lists them; empty if it lists none.
     */
    public List<String> childNames(String groupPath) {
        return children.getOrDefault(groupPath, List.of());
    }

    /** The number of nodes the snapshot lists. */
    public int size() {
        return entries.size();
    }

    /** This snapshot without the node at {@code relativePath} and everything below it. */
    public ConsolidatedMetadata without(String relativePath) {
        Map<String, Entry> kept = new HashMap<>();
        String below = relativePath + "/";
        entries.forEach((path, entry) -> {
            if (!path.equals(relativePath) && !path.startsWith(below)) {
                kept.put(path, entry);
            }
        });
        return new ConsolidatedMetadata(kept, "");
    }

    /**
     * Reads a v3 group's {@code consolidated_metadata} member. A JSON {@code null} (older zarr-python) means
     * none. A {@code kind} other than {@code "inline"} is ignored, so the group's nodes are read one at a
     * time, unless the member says {@code "must_understand": true}.
     *
     * @param ctx the group's metadata key, for diagnostics
     * @return the snapshot, or empty if there is none Falcon can use
     * @throws ZarrFormatException      if an inline snapshot is malformed
     * @throws ZarrUnsupportedException if an unknown kind must be understood
     */
    public static Optional<ConsolidatedMetadata> parseV3(JsonValue member, String ctx) {
        String c = ctx + ".consolidated_metadata";
        if (member.isNull()) {
            return Optional.empty();
        }
        JsonObject o = Fields.object(member, c);
        if (!(o.members().get("kind") instanceof JsonString kind && kind.value().equals("inline"))) {
            if (o.members().get("must_understand") instanceof JsonBool flag && flag.value()) {
                throw new ZarrUnsupportedException(c + ": kind " + o.find("kind").map(JsonValue::toJson).orElse("(none)")
                        + " must be understood, and only \"inline\" is supported");
            }
            return Optional.empty();
        }
        JsonObject metadata = Fields.object(Fields.require(o, "metadata", c), c + ".metadata");
        Map<String, Entry> entries = new HashMap<>();
        for (Map.Entry<String, JsonValue> e : metadata.members().entrySet()) {
            String ec = c + ".metadata['" + e.getKey() + "']";
            checkPath(e.getKey(), ec);
            entries.put(e.getKey(), v3Entry(Fields.object(e.getValue(), ec)));
        }
        return Optional.of(new ConsolidatedMetadata(entries, c + ".metadata"));
    }

    /** An entry of v3 consolidated metadata: a v3 node's document, or a v2 node's as zarr-python embeds it. */
    private static Entry v3Entry(JsonObject doc) {
        if (doc.members().get("zarr_format") instanceof JsonNumber n && n.isFinite() && n.doubleValue() == 2) {
            // a v2 node: its .zarray or .zgroup document, with its .zattrs under "attributes"
            JsonObject.Builder b = JsonObject.builder();
            doc.members().forEach((k, v) -> {
                if (!k.equals("attributes")) {
                    b.put(k, v);
                }
            });
            return new Entry(b.build(), doc.find("attributes").orElse(null), true, !doc.has("shape"));
        }
        boolean group = doc.members().get("node_type") instanceof JsonString t && t.value().equals("group");
        return new Entry(doc, null, false, group);
    }

    /**
     * Reads a v2 hierarchy's {@code .zmetadata}. A {@code zarr_consolidated_format} other than 1 is a form
     * Falcon does not know, and is ignored. The root group's own documents in it ({@code .zgroup},
     * {@code .zattrs}) are skipped, as zarr-python skips them: the group's own files are read instead.
     *
     * @param key the {@code .zmetadata} key, for diagnostics
     * @return the snapshot, or empty if its format is unknown
     * @throws ZarrFormatException if it is malformed
     */
    public static Optional<ConsolidatedMetadata> parseV2(byte[] zmetadata, String key) {
        JsonObject doc;
        try {
            doc = Fields.object(Json.parse(zmetadata), key);
        } catch (JsonException e) {
            throw new ZarrFormatException("malformed JSON in '" + key + "': " + e.getMessage(), e);
        }
        Optional<JsonValue> format = doc.find("zarr_consolidated_format");
        if (format.isPresent()
                && !(format.get() instanceof JsonNumber n && n.isFinite() && n.doubleValue() == 1)) {
            return Optional.empty();
        }
        JsonObject metadata = Fields.object(Fields.require(doc, "metadata", key), key + ".metadata");
        Map<String, JsonObject> arrays = new HashMap<>();
        Map<String, JsonObject> groups = new HashMap<>();
        Map<String, JsonValue> attributes = new HashMap<>();
        for (Map.Entry<String, JsonValue> e : metadata.members().entrySet()) {
            String k = e.getKey();
            String ec = key + ".metadata['" + k + "']";
            int dot = k.lastIndexOf("/.");
            if (dot < 0) {
                if (k.equals(V2Metadata.ZGROUP) || k.equals(V2Metadata.ZATTRS)) {
                    continue;
                }
                throw new ZarrFormatException(ec + ": not a key of a node below the group");
            }
            String path = k.substring(0, dot);
            checkPath(path, ec);
            switch (k.substring(dot + 1)) {
                case V2Metadata.ZARRAY -> arrays.put(path, Fields.object(e.getValue(), ec));
                case V2Metadata.ZGROUP -> groups.put(path, Fields.object(e.getValue(), ec));
                case V2Metadata.ZATTRS -> attributes.put(path, e.getValue());
                default -> throw new ZarrFormatException(ec + ": not a .zarray, .zgroup, or .zattrs key");
            }
        }
        Map<String, Entry> entries = new HashMap<>();
        // as when nodes are read one at a time, .zarray wins over .zgroup; .zattrs alone is no node
        groups.forEach((path, zgroup) -> entries.put(path, new Entry(zgroup, attributes.get(path), true, true)));
        arrays.forEach((path, zarray) -> entries.put(path, new Entry(zarray, attributes.get(path), true, false)));
        return Optional.of(new ConsolidatedMetadata(entries, key + ".metadata"));
    }

    /**
     * The {@code consolidated_metadata} member for a group whose descendants have the given
     * {@code zarr.json} documents (keyed by path relative to the group), laid out as zarr-python 3.4 writes
     * it: {@code {"kind": "inline", "must_understand": false, "metadata": {...}}}, with paths in its order
     * and each group's own {@code consolidated_metadata} replaced by the empty marker, since its children
     * are listed beside it.
     */
    public static JsonObject inline(Map<String, JsonObject> documents) {
        List<String> paths = new ArrayList<>(documents.keySet());
        paths.sort(WRITE_ORDER);
        JsonObject.Builder metadata = JsonObject.builder();
        for (String path : paths) {
            JsonObject doc = documents.get(path);
            if (doc.members().get("node_type") instanceof JsonString t && t.value().equals("group")) {
                doc = withMember(doc, "consolidated_metadata", EMPTY_INLINE);
            }
            metadata.put(path, doc);
        }
        return JsonObject.builder().put("kind", "inline").put("must_understand", false)
                .put("metadata", metadata.build()).build();
    }

    /**
     * A v3 {@code consolidated_metadata} member without the node at {@code relativePath} and everything below
     * it; the member itself if it lists none of them, or is not an inline snapshot.
     */
    public static JsonValue withoutV3(JsonValue member, String relativePath) {
        if (!(member instanceof JsonObject o && o.members().get("metadata") instanceof JsonObject metadata)) {
            return member;
        }
        JsonObject kept = withoutPaths(metadata, relativePath, false);
        return kept == metadata ? member : withMember(o, "metadata", kept);
    }

    /**
     * A {@code .zmetadata} document without the keys of the node at {@code relativePath} and everything below
     * it; the document itself if it has none of them.
     */
    public static JsonObject withoutV2(JsonObject zmetadata, String relativePath) {
        if (!(zmetadata.members().get("metadata") instanceof JsonObject metadata)) {
            return zmetadata;
        }
        JsonObject kept = withoutPaths(metadata, relativePath, true);
        return kept == metadata ? zmetadata : withMember(zmetadata, "metadata", kept);
    }

    /** {@code metadata} without the paths at or below {@code path} (v2: its keys {@code path/.z*} and below). */
    private static JsonObject withoutPaths(JsonObject metadata, String path, boolean v2) {
        String below = path + "/";
        JsonObject.Builder b = JsonObject.builder();
        boolean removed = false;
        for (Map.Entry<String, JsonValue> e : metadata.members().entrySet()) {
            String k = e.getKey();
            if (k.startsWith(below) || (!v2 && k.equals(path))) {
                removed = true;
            } else {
                b.put(k, e.getValue());
            }
        }
        return removed ? b.build() : metadata;
    }

    /** {@code o} with {@code key} set to {@code value}: in its place if present, else last. */
    private static JsonObject withMember(JsonObject o, String key, JsonValue value) {
        JsonObject.Builder b = JsonObject.builder();
        o.members().forEach(b::put);
        return b.put(key, value).build();
    }

    private static void checkPath(String path, String ctx) {
        try {
            StoreKeys.validate(path);
        } catch (IllegalArgumentException e) {
            throw new ZarrFormatException(ctx + ": not a valid node path: " + e.getMessage(), e);
        }
    }

    private static int depth(String path) {
        int n = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                n++;
            }
        }
        return n;
    }

    private static String fold(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /** Compares by Unicode code point, as Python compares strings (Java's compareTo compares UTF-16 units). */
    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
