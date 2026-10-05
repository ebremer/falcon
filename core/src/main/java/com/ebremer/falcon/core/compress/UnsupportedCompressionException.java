package com.ebremer.falcon.core.compress;

/**
 * Thrown when compressed data is well formed but uses a variant these codecs do not implement, such as a
 * newer container version. Each format module turns it into its own unsupported-feature exception.
 */
public final class UnsupportedCompressionException extends RuntimeException {

    public UnsupportedCompressionException(String message) {
        super(message);
    }
}
