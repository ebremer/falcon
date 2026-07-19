package com.ebremer.falcon.hdf5;

/**
 * Thrown when bytes on disk violate the HDF5 File Format Specification: a bad signature, an
 * out-of-range file address, an impossible field value, a failed checksum, and so on.
 *
 * <p>Messages should include the offending byte offset where practical, to make malformed files easy
 * to diagnose.
 */
public class HdfFormatException extends HdfException {

    public HdfFormatException(String message) {
        super(message);
    }

    public HdfFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
