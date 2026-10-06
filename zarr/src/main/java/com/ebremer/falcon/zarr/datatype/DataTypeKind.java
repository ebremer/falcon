package com.ebremer.falcon.zarr.datatype;

/** The broad family a {@link DataType} belongs to, which determines how a fill value is encoded. */
public enum DataTypeKind {
    /** {@code bool}: a single byte, {@code 0} or {@code 1}. */
    BOOL,
    /** {@code int8}/{@code int16}/{@code int32}/{@code int64}: two's-complement signed integers. */
    INT,
    /** {@code uint8}/{@code uint16}/{@code uint32}/{@code uint64}: unsigned integers. */
    UINT,
    /** {@code float16}/{@code float32}/{@code float64}: IEEE&nbsp;754 floating point. */
    FLOAT,
    /** {@code complex64}/{@code complex128}: two consecutive floats (real, then imaginary). */
    COMPLEX,
    /** {@code r<N>}: raw {@code N}-bit values, {@code N} a positive multiple of 8. */
    RAW,
    /** {@code string}: variable-length UTF-8, decoded to {@link String}s (no fixed element size). */
    STRING,
    /** {@code variable_length_bytes}: variable-length byte strings, decoded to {@code byte[]}s. */
    BYTES,
    /**
     * {@code numpy.datetime64}: a signed 64-bit count of time units since 1970-01-01T00:00:00, read and
     * written as {@code long}s; {@link Long#MIN_VALUE} is NaT ("not a time").
     */
    DATETIME,
    /** {@code numpy.timedelta64}: a signed 64-bit count of time units; {@link Long#MIN_VALUE} is NaT. */
    TIMEDELTA,
    /**
     * {@code fixed_length_utf32}: text of up to a fixed number of code points, each stored as a 4-byte
     * UTF-32 code unit and padded with NULs (numpy's {@code U} type), read and written as {@link String}s.
     */
    FIXED_STRING,
    /**
     * {@code null_terminated_bytes}: byte strings of up to a fixed length, padded with NULs (numpy's
     * {@code S} type), read and written as {@code byte[]}s without the padding.
     */
    FIXED_BYTES,
    /** {@code raw_bytes}: opaque elements of a fixed number of bytes (numpy's {@code V} type). */
    RAW_BYTES,
    /** {@code struct}: a record of named fields, each of a fixed-size data type, packed without padding. */
    STRUCT
}
