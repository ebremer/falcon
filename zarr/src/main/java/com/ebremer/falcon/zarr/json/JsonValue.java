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

    /** This value as an object, or {@link JsonException} if it is not one. */
    default JsonObject asObject() {
        throw typeError("object");
    }

    /** This value as an array, or {@link JsonException} if it is not one. */
    default JsonArray asArray() {
        throw typeError("array");
    }

    /** This value's text if it is a string, or {@link JsonException} otherwise. */
    default String asString() {
        throw typeError("string");
    }

    /** This value as a number, or {@link JsonException} if it is not one. */
    default JsonNumber asNumber() {
        throw typeError("number");
    }

    /** This value's boolean if it is one, or {@link JsonException} otherwise. */
    default boolean asBoolean() {
        throw typeError("boolean");
    }

    /** True only for {@link JsonNull}. */
    default boolean isNull() {
        return false;
    }

    /** A short name for this value's JSON type, used in diagnostics. */
    String typeName();

    /** Compact JSON serialization of this value. */
    default String toJson() {
        return Json.write(this);
    }

    private JsonException typeError(String expected) {
        return new JsonException("expected JSON " + expected + " but was " + typeName());
    }
}
