package com.ebremer.falcon.zarr;

/**
 * Thrown when data in a store violates the Zarr v3 specification: malformed {@code zarr.json},
 * an unexpected metadata field type, an impossible shape or chunk grid, a failed {@code crc32c}
 * check, a truncated chunk, and so on.
 *
 * <p>Messages should name the offending key or field where practical, to make malformed stores easy
 * to diagnose.
 */
public class ZarrFormatException extends ZarrException {

    public ZarrFormatException(String message) {
        super(message);
    }

    public ZarrFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
