package com.ebremer.falcon.zarr.json;

import java.util.Objects;

/** A JSON string. The stored {@link #value()} is already unescaped. */
public record JsonString(String value) implements JsonValue {

    public JsonString {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String asString() {
        return value;
    }

    @Override
    public String typeName() {
        return "string";
    }
}
