package com.ebremer.falcon.zarr.json;

import java.nio.charset.StandardCharsets;

/**
 * Entry point for reading and writing JSON. Zarr metadata documents are UTF-8 JSON; this parses them
 * into the {@link JsonValue} model and serializes that model back.
 *
 * <p>{@code java.base} ships no JSON parser, so this small, spec-scoped implementation keeps the
 * module dependency-free.
 */
public final class Json {

    private Json() {
    }

    /** Parses one JSON value from {@code text}; rejects trailing content. */
    public static JsonValue parse(String text) {
        return JsonReader.parse(text);
    }

    /** Parses one JSON value from UTF-8 {@code bytes}. */
    public static JsonValue parse(byte[] bytes) {
        return JsonReader.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Serializes {@code value} to canonical compact JSON (no insignificant whitespace). */
    public static String write(JsonValue value) {
        return JsonWriter.write(value);
    }

    /** Serializes {@code value} to indented, human-readable JSON. */
    public static String writePretty(JsonValue value) {
        return JsonWriter.writePretty(value);
    }

    /** Serializes {@code value} to canonical compact JSON encoded as UTF-8 bytes. */
    public static byte[] writeBytes(JsonValue value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }
}
