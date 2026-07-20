package com.ebremer.falcon.zarr.json;

/** The JSON {@code null} literal. A singleton; use {@link #INSTANCE}. */
public final class JsonNull implements JsonValue {

    /** The single {@code null} instance. */
    public static final JsonNull INSTANCE = new JsonNull();

    private JsonNull() {
    }

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public String typeName() {
        return "null";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonNull;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "null";
    }
}
