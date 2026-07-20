package com.ebremer.falcon.zarr.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * A JSON number, stored as its exact source {@link #literal()} so that parse-then-write is
 * byte-stable (an integer stays {@code 100}, never {@code 100.0}).
 *
 * <p>The accessors interpret the literal numerically: {@link #longValue()} and {@link #intValue()}
 * accept any literal with an integral value (so {@code 1e2} yields {@code 100}) and throw
 * {@link JsonException} on a fractional or out-of-range value; {@link #doubleValue()} parses as an
 * IEEE&nbsp;754 double; {@link #bigIntegerValue()} handles integers beyond {@code long} range, such as
 * {@code uint64} fill values.
 *
 * <p>JSON has no representation for NaN or infinity; Zarr encodes those as the strings {@code "NaN"},
 * {@code "Infinity"}, and {@code "-Infinity"}. Accordingly {@link #of(double)} rejects non-finite
 * values &mdash; a fill value uses a {@link JsonString} for those.
 */
public record JsonNumber(String literal) implements JsonValue {

    public JsonNumber {
        Objects.requireNonNull(literal, "literal");
    }

    /** A JSON number for the given integer. */
    public static JsonNumber of(long value) {
        return new JsonNumber(Long.toString(value));
    }

    /** A JSON number for the given integer. */
    public static JsonNumber of(BigInteger value) {
        return new JsonNumber(value.toString());
    }

    /**
     * A JSON number for the given finite double.
     *
     * @throws JsonException if {@code value} is NaN or infinite
     */
    public static JsonNumber of(double value) {
        if (!Double.isFinite(value)) {
            throw new JsonException(
                    "JSON numbers must be finite; NaN and infinity are encoded as JSON strings");
        }
        return new JsonNumber(Double.toString(value));
    }

    /** This number as a {@code long}. */
    public long longValue() {
        try {
            return new BigDecimal(literal).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new JsonException("JSON number " + literal + " is not a long: " + e.getMessage());
        }
    }

    /** This number as an {@code int}. */
    public int intValue() {
        long v = longValue();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new JsonException("JSON number " + literal + " does not fit in an int");
        }
        return (int) v;
    }

    /** This number as a {@code double} (may lose precision, as IEEE&nbsp;754 requires). */
    public double doubleValue() {
        return Double.parseDouble(literal);
    }

    /** This number as an exact integer of arbitrary size (for example a {@code uint64} value). */
    public BigInteger bigIntegerValue() {
        try {
            return new BigDecimal(literal).toBigIntegerExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new JsonException(
                    "JSON number " + literal + " is not an integer: " + e.getMessage());
        }
    }

    @Override
    public JsonNumber asNumber() {
        return this;
    }

    @Override
    public String typeName() {
        return "number";
    }
}
