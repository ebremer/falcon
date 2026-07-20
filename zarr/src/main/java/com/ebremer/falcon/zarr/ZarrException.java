package com.ebremer.falcon.zarr;

/**
 * Base type for all Falcon Zarr errors.
 *
 * <p>Unchecked so that read and write paths need not thread checked exceptions through every call, and
 * so that lower-level {@link java.io.IOException}s and JSON parse failures can be surfaced uniformly.
 * Catch this to handle any failure Falcon raises while reading or writing a Zarr store.
 */
public class ZarrException extends RuntimeException {

    public ZarrException(String message) {
        super(message);
    }

    public ZarrException(String message, Throwable cause) {
        super(message, cause);
    }
}
