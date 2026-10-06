package com.ebremer.falcon.zarr;

/**
 * Thrown when a store is well-formed but uses a Zarr feature Falcon does not yet implement &mdash; a
 * codec that has not landed, a registered extension marked {@code "must_understand": true}, or a
 * storage transformer, for example. Distinct from {@link ZarrFormatException}, which signals corrupt
 * or invalid data.
 */
public class ZarrUnsupportedException extends ZarrException {

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message
     */
    public ZarrUnsupportedException(String message) {
        super(message);
    }
}
