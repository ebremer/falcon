package com.ebremer.falcon.ome;

import com.ebremer.falcon.zarr.ZarrFormatException;

/**
 * Thrown when a group's OME-Zarr metadata cannot be read: a part is missing that the reading needs, or a
 * value has the wrong JSON type. The message names where, as a JSON pointer into the attributes.
 *
 * <p>Reading is lenient: it needs only what it reads. {@link OmeValidator} reports everything the
 * specification requires.
 */
public class OmeFormatException extends ZarrFormatException {

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message
     */
    public OmeFormatException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a detail message and the failure that caused it.
     *
     * @param message the detail message
     * @param cause   the underlying failure
     */
    public OmeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
