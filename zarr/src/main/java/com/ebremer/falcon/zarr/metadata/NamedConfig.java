package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonValue;

/**
 * A named, configurable extension point: an object with a {@code "name"} and an optional
 * {@code "configuration"} object. This is the shape Zarr v3 uses for a chunk grid, a chunk key
 * encoding, and each codec, so one parser serves all three.
 *
 * @param name          the extension name (for example {@code "regular"}, {@code "default"},
 *                      {@code "bytes"})
 * @param configuration the configuration object, or an empty object when {@code "configuration"} is
 *                      absent
 */
public record NamedConfig(String name, JsonObject configuration) {

    static NamedConfig parse(JsonValue v, String ctx) {
        JsonObject o = Fields.object(v, ctx);
        String name = Fields.string(Fields.require(o, "name", ctx), ctx + ".name");
        JsonObject configuration = o.find("configuration")
                .map(c -> Fields.object(c, ctx + ".configuration"))
                .orElse(Fields.EMPTY_OBJECT);
        return new NamedConfig(name, configuration);
    }
}
