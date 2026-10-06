package com.ebremer.falcon.zarr.json;

import java.util.List;

/** An immutable JSON array. */
public final class JsonArray implements JsonValue {

    private final List<JsonValue> values;

    /**
     * Wraps a defensive, immutable copy of {@code values} (which must contain no nulls).
     *
     * @param values the elements, in order
     * @throws NullPointerException if {@code values} or an element is null
     */
    public JsonArray(List<JsonValue> values) {
        this.values = List.copyOf(values);
    }

    /**
     * A JSON array of the given values.
     *
     * @param values the elements, in order (none null)
     * @return the array
     * @throws NullPointerException if an element is null
     */
    public static JsonArray of(JsonValue... values) {
        return new JsonArray(List.of(values));
    }

    /** {@return the elements, in order} Immutable. */
    public List<JsonValue> values() {
        return values;
    }

    /** {@return the number of elements} */
    public int size() {
        return values.size();
    }

    /**
     * The element at {@code index}.
     *
     * @param index the element's position, from 0
     * @return the element
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #size()}
     */
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
