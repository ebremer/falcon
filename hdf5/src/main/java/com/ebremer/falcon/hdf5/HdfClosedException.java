package com.ebremer.falcon.hdf5;

/**
 * Thrown when an object, attribute, or selection is read after its {@link Hdf5File} was closed, or
 * when an {@link Hdf5Writer} is used after it was closed or aborted.
 */
public class HdfClosedException extends HdfException {

    public HdfClosedException(String message) {
        super(message);
    }
}
