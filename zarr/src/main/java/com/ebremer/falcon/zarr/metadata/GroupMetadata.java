package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.Optional;
import java.util.Set;

/**
 * Parsed group metadata: {@code {"zarr_format": 3, "node_type": "group", "attributes": {...}}}.
 *
 * <p>A group may also carry {@code consolidated_metadata}, which zarr-python writes into a consolidated
 * group's {@code zarr.json}: a snapshot of the metadata of every node below the group (or {@code null} in
 * older releases). It is kept here unparsed, so a damaged snapshot does not stop the group opening when
 * the caller asked not to use it; {@link ConsolidatedMetadata#parseV3} reads it.
 */
public final class GroupMetadata implements NodeMetadata {

    /** The members Falcon reads. */
    private static final Set<String> KNOWN =
            Set.of("zarr_format", "node_type", "attributes", "consolidated_metadata");

    private final JsonObject attributes;
    private final JsonValue consolidated; // the raw consolidated_metadata member, or null if absent
    private final int zarrFormat;         // 3, or 2 for a v2 group translated by V2Metadata

    GroupMetadata(JsonObject attributes, JsonValue consolidated, int zarrFormat) {
        this.attributes = attributes;
        this.consolidated = consolidated;
        this.zarrFormat = zarrFormat;
    }

    /** Parses a validated group document. {@code ctx} names the source key for diagnostics. */
    static GroupMetadata parse(JsonObject o, String ctx) {
        return parse(o, ctx, 3);
    }

    /**
     * Parses a validated group document, recording {@code zarrFormat} as the format it was stored in: 2 for
     * the v3 document {@link V2Metadata} translates a {@code .zgroup} into.
     */
    static GroupMetadata parse(JsonObject o, String ctx, int zarrFormat) {
        Fields.requireZarrFormat3(o, ctx);
        JsonObject attributes = o.find("attributes")
                .map(v -> Fields.object(v, ctx + ".attributes"))
                .orElse(Fields.EMPTY_OBJECT);
        Fields.checkUnknownFields(o, KNOWN, ctx);
        return new GroupMetadata(attributes, o.find("consolidated_metadata").orElse(null), zarrFormat);
    }

    @Override
    public NodeType nodeType() {
        return NodeType.GROUP;
    }

    @Override
    public int zarrFormat() {
        return zarrFormat;
    }

    @Override
    public JsonObject attributes() {
        return attributes;
    }

    /** The raw {@code consolidated_metadata} member, unparsed; empty if the document has none. */
    public Optional<JsonValue> consolidatedMetadata() {
        return Optional.ofNullable(consolidated);
    }
}
