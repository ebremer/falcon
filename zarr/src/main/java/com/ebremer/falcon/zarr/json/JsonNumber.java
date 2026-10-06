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
 * values &mdash; a fill value uses a {@link JsonString} for those. Python's {@code json} module,
 * however, writes them as the bare tokens {@code NaN}, {@code Infinity}, and {@code -Infinity}, and
 * zarr-python does so in user attributes. The reader accepts those tokens as numbers whose literal is
 * the token ({@link #isFinite()} is false, {@link #doubleValue()} gives the non-finite double), and the
 * writer writes them back as they came.
 *
 * @param literal the number exactly as written in JSON
 */
public record JsonNumber(String literal) implements JsonValue {

    /**
     * The most integer digits {@link #bigIntegerValue()} converts. Far beyond any Zarr integer type
     * (a {@code uint64} has 20 digits), it stops a literal such as {@code 1e20000000}, a few bytes of
     * JSON, from being expanded into a 20-million-digit integer. Python bounds {@code int} parsing at the
     * same 4300 digits.
     */
    public static final int MAX_INTEGER_DIGITS = 4300;

    /** The longest stretch of a literal that an error message quotes. */
    private static final int MAX_QUOTED = 40;

    /**
     * Creates a JSON number from its literal text, which is kept as given.
     *
     * @param literal the number exactly as written in JSON
     * @throws JsonException if {@code literal} is not a JSON number (RFC&nbsp;8259 &sect;6) or one of
     *                       the non-finite tokens {@code NaN}, {@code Infinity}, {@code -Infinity}
     */
    public JsonNumber {
        Objects.requireNonNull(literal, "literal");
        if (!isNonFiniteToken(literal) && !isNumberLiteral(literal)) {
            throw new JsonException("not a JSON number: \"" + quote(literal) + "\"");
        }
    }

    /**
     * A JSON number for the given integer.
     *
     * @param value the integer
     * @return the number, its literal the integer's decimal digits
     */
    public static JsonNumber of(long value) {
        return new JsonNumber(Long.toString(value));
    }

    /**
     * A JSON number for the given integer, of any size.
     *
     * @param value the integer
     * @return the number, its literal the integer's decimal digits
     */
    public static JsonNumber of(BigInteger value) {
        return new JsonNumber(value.toString());
    }

    /**
     * A JSON number for the given finite double.
     *
     * @param value the double
     * @return the number, its literal {@link Double#toString(double)}'s (so {@code 100.0}, not {@code 100})
     * @throws JsonException if {@code value} is NaN or infinite
     */
    public static JsonNumber of(double value) {
        if (!Double.isFinite(value)) {
            throw new JsonException(
                    "JSON numbers must be finite; NaN and infinity are encoded as JSON strings");
        }
        return new JsonNumber(Double.toString(value));
    }

    /**
     * Whether this is an ordinary JSON number, rather than one of the tokens {@code NaN},
     * {@code Infinity}, or {@code -Infinity} that Python writes and the reader accepts.
     *
     * @return true for an ordinary number, false for a non-finite token
     */
    public boolean isFinite() {
        return !isNonFiniteToken(literal);
    }

    /**
     * This number as a {@code long}.
     *
     * @return the value, exactly
     * @throws JsonException if the value is not an integer, does not fit a {@code long}, or is not finite
     */
    public long longValue() {
        BigDecimal d = integral(19);
        try {
            return d.longValueExact();
        } catch (ArithmeticException e) {
            throw new JsonException("JSON number " + quote(literal) + " is not a long: " + e.getMessage());
        }
    }

    /**
     * This number as an {@code int}.
     *
     * @return the value, exactly
     * @throws JsonException if the value is not an integer, does not fit an {@code int}, or is not finite
     */
    public int intValue() {
        long v = longValue();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new JsonException("JSON number " + quote(literal) + " does not fit in an int");
        }
        return (int) v;
    }

    /**
     * This number as a {@code double} (may lose precision, as IEEE&nbsp;754 requires). The tokens
     * {@code NaN}, {@code Infinity}, and {@code -Infinity} give the matching non-finite double.
     *
     * @return the nearest double; a literal beyond double's range gives an infinity
     */
    public double doubleValue() {
        return Double.parseDouble(literal);
    }

    /**
     * This number as an exact integer of arbitrary size (for example a {@code uint64} value).
     *
     * @return the value, exactly
     * @throws JsonException if it is not an integer, or has more than {@link #MAX_INTEGER_DIGITS} digits
     */
    public BigInteger bigIntegerValue() {
        BigDecimal d = integral(MAX_INTEGER_DIGITS);
        try {
            return d.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new JsonException("JSON number " + quote(literal) + " is not an integer: " + e.getMessage());
        }
    }

    /**
     * This number as a decimal whose integer part has at most {@code maxDigits} digits, and that is zero
     * or at least 1 in magnitude: checked from the literal's precision and scale, before an exact integer
     * conversion would expand an exponent such as {@code 1e20000000} or {@code 1e-20000000} digit by
     * digit.
     */
    private BigDecimal integral(int maxDigits) {
        if (!isFinite()) {
            throw new JsonException("JSON number " + literal + " is not finite");
        }
        BigDecimal d;
        try {
            d = new BigDecimal(literal);
        } catch (NumberFormatException e) { // an exponent beyond an int
            throw new JsonException("JSON number " + quote(literal) + " is out of range");
        }
        if (d.signum() != 0) {
            long integerDigits = (long) d.precision() - d.scale(); // digits before the decimal point
            if (integerDigits > maxDigits) {
                throw new JsonException("JSON number " + quote(literal) + " has more than " + maxDigits
                        + " integer digits");
            }
            if (integerDigits <= 0) {
                throw new JsonException("JSON number " + quote(literal) + " is not an integer");
            }
        }
        return d;
    }

    @Override
    public JsonNumber asNumber() {
        return this;
    }

    @Override
    public String typeName() {
        return "number";
    }

    private static boolean isNonFiniteToken(String s) {
        return s.equals("NaN") || s.equals("Infinity") || s.equals("-Infinity");
    }

    /** Whether {@code s} is exactly a JSON number: {@code -? int frac? exp?} (RFC&nbsp;8259 &sect;6). */
    static boolean isNumberLiteral(String s) {
        int i = 0;
        int n = s.length();
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        if (i >= n) {
            return false;
        }
        if (s.charAt(i) == '0') {
            i++;
        } else if (isDigit(s.charAt(i))) {
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
        } else {
            return false;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            if (i >= n || !isDigit(s.charAt(i))) {
                return false;
            }
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            if (i >= n || !isDigit(s.charAt(i))) {
                return false;
            }
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
        }
        return i == n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** {@code s} for an error message: shortened, with its length, when it is long. */
    static String quote(String s) {
        return s.length() <= MAX_QUOTED ? s
                : s.substring(0, MAX_QUOTED) + "... (" + s.length() + " characters)";
    }
}
