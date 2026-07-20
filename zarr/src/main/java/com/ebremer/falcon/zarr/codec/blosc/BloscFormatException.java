package com.ebremer.falcon.zarr.codec.blosc;

/** Thrown when a Blosc buffer is malformed. */
public final class BloscFormatException extends RuntimeException {

    public BloscFormatException(String message) {
        super(message);
    }
}
