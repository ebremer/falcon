package com.ebremer.falcon.zarr.json;

import java.util.Objects;

/**
 * A JSON string. The stored {@link #value()} is already unescaped.
 *
 * @param value the string's text, unescaped
 */
public record JsonString(String value) implements JsonValue {

    /**
     * Creates a JSON string holding {@code value}.
     *
     * @param value the string's text, unescaped
     * @throws NullPointerException if {@code value} is null
     */
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
