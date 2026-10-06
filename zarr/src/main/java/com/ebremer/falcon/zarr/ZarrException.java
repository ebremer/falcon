package com.ebremer.falcon.zarr;

/**
 * Base type for all Falcon Zarr errors.
 *
 * <p>Unchecked so that read and write paths need not thread checked exceptions through every call, and
 * so that lower-level {@link java.io.IOException}s and JSON parse failures can be surfaced uniformly.
 * Catch this to handle any failure Falcon raises while reading or writing a Zarr store.
 */
public class ZarrException extends RuntimeException {

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message
     */
    public ZarrException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a detail message and the failure that caused it.
     *
     * @param message the detail message
     * @param cause   the underlying failure (an {@link java.io.IOException}, for example)
     */
    public ZarrException(String message, Throwable cause) {
        super(message, cause);
    }
}
