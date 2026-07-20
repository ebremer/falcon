package com.ebremer.falcon.zarr.json;

import java.util.List;

/** An immutable JSON array. */
public final class JsonArray implements JsonValue {

    private final List<JsonValue> values;

    /** Wraps a defensive, immutable copy of {@code values} (which must contain no nulls). */
    public JsonArray(List<JsonValue> values) {
        this.values = List.copyOf(values);
    }

    /** A JSON array of the given values. */
    public static JsonArray of(JsonValue... values) {
        return new JsonArray(List.of(values));
    }

    /** The elements, in order. Immutable. */
    public List<JsonValue> values() {
        return values;
    }

    /** The number of elements. */
    public int size() {
        return values.size();
    }

    /** The element at {@code index}. */
    public JsonValue get(int index) {
        return values.get(index);
    }

    @Override
    public JsonArray asArray() {
        return this;
    }

    @Override
    public String typeName() {
        return "array";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonArray other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return toJson();
    }
}
