package com.ebremer.falcon.core.compress;

/**
 * Thrown when compressed data is malformed: truncated, inconsistent with its own headers, or decoding to
 * a size other than the one recorded. Each format module turns it into its own format exception.
 */
public final class CompressionFormatException extends RuntimeException {

    public CompressionFormatException(String message) {
        super(message);
    }
}
