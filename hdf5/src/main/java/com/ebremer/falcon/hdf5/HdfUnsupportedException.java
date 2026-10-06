package com.ebremer.falcon.hdf5;

/**
 * Thrown when a file is well-formed but uses an HDF5 feature Falcon does not yet implement (for
 * example, new-style link storage, which arrives in a later roadmap stage). Distinct from
 * {@link HdfFormatException}, which signals corrupt or invalid data.
 */
public class HdfUnsupportedException extends HdfException {

    /**
     * An exception with the given message.
     *
     * @param message what is not supported, or refused
     */
    public HdfUnsupportedException(String message) {
        super(message);
    }
}
