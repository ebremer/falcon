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
    RAW
}
