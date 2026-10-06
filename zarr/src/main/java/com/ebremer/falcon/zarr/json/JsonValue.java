package com.ebremer.falcon.zarr.json;

/**
 * An immutable JSON value: one of {@link JsonObject}, {@link JsonArray}, {@link JsonString},
 * {@link JsonNumber}, {@link JsonBool}, or {@link JsonNull}.
 *
 * <p>The typed accessors ({@link #asObject()}, {@link #asArray()}, {@link #asString()},
 * {@link #asNumber()}, {@link #asBoolean()}) return the underlying value when the type matches and
 * throw {@link JsonException} otherwise, so metadata parsing reads as a chain of total operations.
 */
public sealed interface JsonValue
        permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBool, JsonNull {

    /**
     * This value as an object, or {@link JsonException} if it is not one.
     *
     * @return this value
     * @throws JsonException if this value is not an object
     */
    default JsonObject asObject() {
        throw typeError("object");
    }

    /**
     * This value as an array, or {@link JsonException} if it is not one.
     *
     * @return this value
     * @throws JsonException if this value is not an array
     */
    default JsonArray asArray() {
        throw typeError("array");
    }

    /**
     * This value's text if it is a string, or {@link JsonException} otherwise.
     *
     * @return the string's text, unescaped
     * @throws JsonException if this value is not a string
     */
    default String asString() {
        throw typeError("string");
    }

    /**
     * This value as a number, or {@link JsonException} if it is not one.
     *
     * @return this value
     * @throws JsonException if this value is not a number
     */
    default JsonNumber asNumber() {
        throw typeError("number");
    }

    /**
     * This value's boolean if it is one, or {@link JsonException} otherwise.
     *
     * @return the boolean
     * @throws JsonException if this value is not a boolean
     */
    default boolean asBoolean() {
        throw typeError("boolean");
    }

    /** {@return true only for {@link JsonNull}} */
    default boolean isNull() {
        return false;
    }

    /**
     * {@return a short name for this value's JSON type, used in diagnostics: {@code "object"},
     * {@code "array"}, {@code "string"}, {@code "number"}, {@code "boolean"}, or {@code "null"}}
     */
    String typeName();

    /** {@return the compact JSON serialization of this value, as {@link Json#write} gives it} */
    default String toJson() {
        return Json.write(this);
    }

    private JsonException typeError(String expected) {
        return new JsonException("expected JSON " + expected + " but was " + typeName());
    }
}
