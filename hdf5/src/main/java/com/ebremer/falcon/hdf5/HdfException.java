package com.ebremer.falcon.hdf5;

/**
 * Base type for all Falcon HDF5 errors.
 *
 * <p>Unchecked so that hot, deeply-nested read paths need not thread checked exceptions through every
 * call. Catch this to handle any failure Falcon raises while reading or writing an HDF5 file.
 */
public class HdfException extends RuntimeException {

    /**
     * An exception with the given message.
     *
     * @param message what failed
     */
    public HdfException(String message) {
        super(message);
    }

    /**
     * An exception with the given message and cause.
     *
     * @param message what failed
     * @param cause   the underlying failure
     */
    public HdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
