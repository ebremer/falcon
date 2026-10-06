package com.ebremer.falcon.zarr.json;

/**
 * A JSON {@code true} or {@code false}.
 *
 * @param value the boolean
 */
public record JsonBool(boolean value) implements JsonValue {

    /** The JSON {@code true} literal. */
    public static final JsonBool TRUE = new JsonBool(true);

    /** The JSON {@code false} literal. */
    public static final JsonBool FALSE = new JsonBool(false);

    /**
     * Returns the shared instance for {@code value}.
     *
     * @param value the boolean
     * @return {@link #TRUE} or {@link #FALSE}
     */
    public static JsonBool of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public boolean asBoolean() {
        return value;
    }

    @Override
    public String typeName() {
        return "boolean";
    }
}
