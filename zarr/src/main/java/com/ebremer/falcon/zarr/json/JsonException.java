package com.ebremer.falcon.zarr.json;

/**
 * Thrown when JSON text is malformed, or when a {@link JsonValue} is accessed as the wrong type
 * (for example calling {@link JsonValue#asObject()} on an array).
 *
 * <p>This package is Zarr-internal; metadata parsing wraps these into
 * {@code com.ebremer.falcon.zarr.ZarrFormatException} with the offending store key for context.
 */
public final class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }
}
