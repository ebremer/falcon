package com.ebremer.falcon.zarr.codec.zstd;

/** Thrown when a Zstandard frame is malformed or uses a feature this decoder does not implement. */
public final class ZstdFormatException extends RuntimeException {

    public ZstdFormatException(String message) {
        super(message);
    }
}
