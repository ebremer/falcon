package com.ebremer.falcon.zarr.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable JSON object that preserves member insertion order, so serialization is stable and
 * reproduces the order metadata was written in.
 */
public final class JsonObject implements JsonValue {

    private final Map<String, JsonValue> members;

    /**
     * Wraps a defensive, order-preserving, immutable copy of {@code members} (no null keys or values).
     *
     * @param members the members, in the order {@code members} iterates them
     * @throws NullPointerException if a key or value is null
     */
    public JsonObject(Map<String, JsonValue> members) {
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> e : members.entrySet()) {
            copy.put(Objects.requireNonNull(e.getKey(), "key"),
                     Objects.requireNonNull(e.getValue(), "value"));
        }
        this.members = Collections.unmodifiableMap(copy);
    }

    /** {@return a new empty-object builder} */
    public static Builder builder() {
        return new Builder();
    }

    /** {@return the members, in insertion order} Immutable. */
    public Map<String, JsonValue> members() {
        return members;
    }

    /**
     * True if {@code key} is present.
     *
     * @param key the member name
     * @return whether the object has a member of that name
     */
    public boolean has(String key) {
        return members.containsKey(key);
    }

    /**
     * The value for {@code key}, or empty if absent.
     *
     * @param key the member name
     * @return the member's value, or empty
     */
    public Optional<JsonValue> find(String key) {
        return Optional.ofNullable(members.get(key));
    }

    /**
     * The value for a required {@code key}.
     *
     * @param key the member name
     * @return the member's value
     * @throws JsonException if the key is absent
     */
    public JsonValue get(String key) {
        JsonValue v = members.get(key);
        if (v == null) {
            throw new JsonException("missing required key: " + key);
        }
        return v;
    }

    @Override
    public JsonObject asObject() {
        return this;
    }

    @Override
    public String typeName() {
        return "object";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonObject other && members.equals(other.members);
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }

    @Override
    public String toString() {
        return toJson();
    }

    /** Builds a {@link JsonObject}, preserving the order members are added. */
    public static final class Builder {

        private final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();

        /** Creates an empty builder; {@link JsonObject#builder()} does the same. */
        public Builder() {
        }

        /**
         * Adds or replaces a member. A replaced member keeps its place in the order.
         *
         * @param key   the member name
         * @param value the member's value; {@link #build()} refuses a null one
         * @return this builder
         */
        public Builder put(String key, JsonValue value) {
            members.put(key, value);
            return this;
        }

        /**
         * Adds or replaces a string member.
         *
         * @param key   the member name
         * @param value the string
         * @return this builder
         * @throws NullPointerException if {@code value} is null
         */
        public Builder put(String key, String value) {
            return put(key, new JsonString(value));
        }

        /**
         * Adds or replaces an integer member.
         *
         * @param key   the member name
         * @param value the integer
         * @return this builder
         */
        public Builder put(String key, long value) {
            return put(key, JsonNumber.of(value));
        }

        /**
         * Adds or replaces a boolean member.
         *
         * @param key   the member name
         * @param value the boolean
         * @return this builder
         */
        public Builder put(String key, boolean value) {
            return put(key, JsonBool.of(value));
        }

        /**
         * The finished, immutable object. The builder may be reused: later puts do not change it.
         *
         * @return the object
         * @throws NullPointerException if a member was put with a null key or value
         */
        public JsonObject build() {
            return new JsonObject(members);
        }
    }
}
