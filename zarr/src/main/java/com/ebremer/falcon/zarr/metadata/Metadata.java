package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.json.Json;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;

/** Parses a {@code zarr.json} document into typed {@link NodeMetadata}. */
public final class Metadata {

    private Metadata() {
    }

    /**
     * Parses the UTF-8 {@code zarr.json} bytes of one node. {@code key} is the store key the bytes came
     * from, used only in diagnostics.
     *
     * @throws ZarrFormatException if the JSON is malformed or violates the array/group schema
     * @throws com.ebremer.falcon.zarr.ZarrUnsupportedException if the node uses an unimplemented feature
     */
    public static NodeMetadata parse(byte[] json, String key) {
        JsonValue root;
        try {
            root = Json.parse(json);
        } catch (JsonException e) {
            throw new ZarrFormatException("malformed JSON in '" + key + "': " + e.getMessage(), e);
        }
        return parse(root, key);
    }

    /**
     * Parses one node's already-parsed {@code zarr.json} document, such as an entry of consolidated metadata.
     * {@code key} names where the document came from, used only in diagnostics.
     *
     * @throws ZarrFormatException if the document violates the array/group schema
     * @throws com.ebremer.falcon.zarr.ZarrUnsupportedException if the node uses an unimplemented feature
     */
    public static NodeMetadata parse(JsonValue root, String key) {
        JsonObject o = Fields.object(root, key);
        String nodeType = Fields.string(Fields.require(o, "node_type", key), key + ".node_type");
        return wrapJson(key, () -> switch (nodeType) {
            case "group" -> GroupMetadata.parse(o, key);
            case "array" -> ArrayMetadata.parse(o, key);
            default -> throw new ZarrFormatException(
                    key + ": node_type must be 'array' or 'group', was '" + nodeType + "'");
        });
    }

    /**
     * Runs {@code parse}, reporting a {@link JsonException} (a member of the wrong JSON type that a parser
     * read without a typed helper) as the {@link ZarrFormatException} it is, so none escapes raw.
     */
    static <T> T wrapJson(String key, java.util.function.Supplier<T> parse) {
        try {
            return parse.get();
        } catch (JsonException e) {
            throw new ZarrFormatException(key + ": " + e.getMessage(), e);
        }
    }
}
