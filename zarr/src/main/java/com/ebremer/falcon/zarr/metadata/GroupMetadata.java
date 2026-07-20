package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.json.JsonObject;
import java.util.Set;

/**
 * Parsed group metadata: {@code {"zarr_format": 3, "node_type": "group", "attributes": {...}}}.
 */
public final class GroupMetadata implements NodeMetadata {

    private static final Set<String> KNOWN = Set.of("zarr_format", "node_type", "attributes");

    private final JsonObject attributes;

    GroupMetadata(JsonObject attributes) {
        this.attributes = attributes;
    }

    /** Parses a validated group document. {@code ctx} names the source key for diagnostics. */
    static GroupMetadata parse(JsonObject o, String ctx) {
        Fields.requireZarrFormat3(o, ctx);
        JsonObject attributes = o.find("attributes")
                .map(v -> Fields.object(v, ctx + ".attributes"))
                .orElse(Fields.EMPTY_OBJECT);
        Fields.checkUnknownFields(o, KNOWN, ctx);
        return new GroupMetadata(attributes);
    }

    @Override
    public NodeType nodeType() {
        return NodeType.GROUP;
    }

    @Override
    public JsonObject attributes() {
        return attributes;
    }
}
