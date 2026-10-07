package com.ebremer.falcon.cli;

import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonNull;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * The elements of an array, or of an attribute, as {@code dump} prints them and as JSON: each element in
 * C order. Numbers print as their shortest exact decimal ({@code 0.1}, not {@code 0.10000000149011612}, for
 * a float32), non-finite floats as {@code NaN}, {@code Infinity}, and {@code -Infinity}, strings quoted as
 * in JSON, and records as JSON objects.
 */
abstract class Values {

    private static final HexFormat HEX = HexFormat.of();

    /** {@return the number of elements} */
    abstract int size();

    /**
     * {@return element {@code i} as JSON}
     *
     * @param i the element's index
     */
    abstract JsonValue json(int i);

    /**
     * {@return element {@code i} as {@code dump} prints it in text}
     *
     * @param i the element's index
     */
    String text(int i) {
        return json(i).toJson();
    }

    /**
     * {@return element {@code i} as a CSV field, before quoting: a string's own text}
     *
     * @param i the element's index
     */
    String csv(int i) {
        JsonValue value = json(i);
        return value instanceof JsonString s ? s.value() : value.toJson();
    }

    // ---- numbers ------------------------------------------------------------------------------------------

    /**
     * {@return a JSON number of a double: its shortest decimal, or a non-finite token}
     *
     * @param value the number
     */
    static JsonNumber number(double value) {
        if (Double.isNaN(value)) {
            return new JsonNumber("NaN");
        }
        if (Double.isInfinite(value)) {
            return new JsonNumber(value > 0 ? "Infinity" : "-Infinity");
        }
        return new JsonNumber(Double.toString(value));
    }

    /**
     * {@return a JSON number of a float: its shortest decimal as a float, or a non-finite token}
     *
     * @param value the number
     */
    static JsonNumber number(float value) {
        return Float.isFinite(value) ? new JsonNumber(Float.toString(value)) : number((double) value);
    }

    static Values longs(long[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return JsonNumber.of(values[i]);
            }

            @Override String text(int i) {
                return Long.toString(values[i]);
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    /** Unsigned 64-bit integers, each held in a {@code long}'s bits. */
    static Values unsignedLongs(long[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return new JsonNumber(text(i));
            }

            @Override String text(int i) {
                return Long.toUnsignedString(values[i]);
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    static Values bigIntegers(BigInteger[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return values[i] == null ? JsonNull.INSTANCE : JsonNumber.of(values[i]);
            }
        };
    }

    static Values doubles(double[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return number(values[i]);
            }

            @Override String text(int i) {
                return number(values[i]).literal();
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    static Values floats(float[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return number(values[i]);
            }

            @Override String text(int i) {
                return number(values[i]).literal();
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    /** Booleans: each value nonzero is true. */
    static Values booleans(long[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return JsonBool.of(values[i] != 0);
            }
        };
    }

    /**
     * Complex numbers, as interleaved (real, imaginary) pairs: JSON {@code [re, im]}, text {@code 1.0+2.0j}.
     *
     * @param pairs  the parts, real then imaginary, of each element
     * @param single whether each part is a float32
     */
    static Values complex(double[] pairs, boolean single) {
        return new Values() {
            @Override int size() {
                return pairs.length / 2;
            }

            private JsonNumber part(double value) {
                return single ? number((float) value) : number(value);
            }

            @Override JsonValue json(int i) {
                return JsonArray.of(part(pairs[2 * i]), part(pairs[2 * i + 1]));
            }

            @Override String text(int i) {
                String im = part(pairs[2 * i + 1]).literal();
                return part(pairs[2 * i]).literal() + (im.startsWith("-") ? "" : "+") + im + "j";
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    static Values strings(String[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return values[i] == null ? JsonNull.INSTANCE : new JsonString(values[i]);
            }
        };
    }

    /**
     * Byte strings: in text, {@code b"..."} with each byte outside printable ASCII escaped, or {@code 0x...} for
     * opaque bytes; in JSON, text-like ones as the text they are when they are UTF-8, and otherwise base64.
     *
     * @param values the byte strings
     * @param text   whether they are text-like (numpy's {@code S}, variable-length bytes) rather than opaque
     */
    static Values bytes(byte[][] values, boolean text) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                if (values[i] == null) {
                    return JsonNull.INSTANCE;
                }
                return text ? textOrBase64(values[i]) : new JsonString(Base64.getEncoder().encodeToString(values[i]));
            }

            @Override String text(int i) {
                if (values[i] == null) {
                    return "null";
                }
                return text ? byteString(values[i]) : "0x" + HEX.formatHex(values[i]);
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    /** {@return a byte string as JSON: its text if it is valid UTF-8, else its base64} */
    static JsonString textOrBase64(byte[] bytes) {
        try {
            return new JsonString(java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString());
        } catch (java.nio.charset.CharacterCodingException e) {
            return new JsonString(Base64.getEncoder().encodeToString(bytes));
        }
    }

    static String byteString(byte[] bytes) {
        StringBuilder s = new StringBuilder("b\"");
        for (byte b : bytes) {
            int c = b & 0xff;
            if (c == '"' || c == '\\') {
                s.append('\\').append((char) c);
            } else if (c >= 0x20 && c < 0x7f) {
                s.append((char) c);
            } else {
                s.append("\\x").append(HEX.toHexDigits((byte) c));
            }
        }
        return s.append('"').toString();
    }

    static Values instants(Instant[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return values[i] == null ? JsonNull.INSTANCE : new JsonString(values[i].toString());
            }

            @Override String text(int i) {
                return values[i] == null ? "null" : values[i].toString();
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    /**
     * numpy's times: a {@code datetime64} prints as its date and time, to its unit's precision, and a
     * {@code timedelta64} as its count and unit; NaT ({@link Long#MIN_VALUE}) as {@code NaT}.
     *
     * @param values the counts of the unit
     * @param unit   numpy's unit, such as {@code s} or {@code generic}
     * @param scale  how many units each count stands for
     * @param delta  whether the type is a {@code timedelta64}
     */
    static Values times(long[] values, String unit, int scale, boolean delta) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                if (values[i] == Long.MIN_VALUE) {
                    return new JsonString("NaT");
                }
                return delta ? JsonNumber.of(values[i]) : new JsonString(text(i));
            }

            @Override String text(int i) {
                return time(values[i], unit, scale, delta);
            }

            @Override String csv(int i) {
                return text(i);
            }
        };
    }

    static String time(long count, String unit, int scale, boolean delta) {
        if (count == Long.MIN_VALUE) {
            return "NaT";
        }
        String units = (scale == 1 ? "" : Integer.toString(scale)) + unit;
        if (unit.equals("generic")) {
            return Long.toString(count);
        }
        if (delta) {
            try {
                return Math.multiplyExact(count, (long) scale) + " " + unit;
            } catch (ArithmeticException e) {
                return count + " " + units;
            }
        }
        try {
            long n = Math.multiplyExact(count, (long) scale);
            LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0);
            return switch (unit) {
                case "Y" -> Long.toString(1970 + n);
                case "M" -> epoch.plusMonths(n).toLocalDate().toString().substring(0, 7);
                case "W" -> epoch.plusWeeks(n).toLocalDate().toString();
                case "D" -> epoch.plusDays(n).toLocalDate().toString();
                case "h" -> epoch.plusHours(n).toString().substring(0, 13);
                case "m" -> epoch.plusMinutes(n).toString();
                case "s" -> seconds(Instant.ofEpochSecond(n), 0);
                case "ms" -> seconds(fraction(n, 1_000L), 3);
                case "us", "μs" -> seconds(fraction(n, 1_000_000L), 6);
                case "ns" -> seconds(fraction(n, 1_000_000_000L), 9);
                default -> count + " " + units; // ps, fs, as: finer than java.time
            };
        } catch (ArithmeticException | java.time.DateTimeException e) {
            return count + " " + units;
        }
    }

    /** {@return the instant {@code n} units of {@code 1/perSecond} of a second after 1970} */
    private static Instant fraction(long n, long perSecond) {
        return Instant.ofEpochSecond(Math.floorDiv(n, perSecond),
                Math.floorMod(n, perSecond) * (1_000_000_000L / perSecond));
    }

    private static String seconds(Instant instant, int fraction) {
        LocalDateTime t = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        String s = String.format("%04d-%02d-%02dT%02d:%02d:%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond());
        if (fraction > 0) {
            String nanos = String.format("%09d", t.getNano());
            s += "." + nanos.substring(0, fraction);
        }
        return s;
    }

    /** Elements already in JSON (records, sequences, references). */
    static Values of(JsonValue[] values) {
        return new Values() {
            @Override int size() {
                return values.length;
            }

            @Override JsonValue json(int i) {
                return values[i];
            }
        };
    }

    // ---- shapes -------------------------------------------------------------------------------------------

    /**
     * {@return the elements as nested JSON arrays of {@code shape}: one element for a scalar ({@code shape}
     * empty), an array for one dimension, and so on}
     *
     * @param values the elements, in C order
     * @param shape  their shape
     */
    static JsonValue nested(Values values, long[] shape) {
        if (shape.length == 0) {
            return values.size() == 0 ? JsonNull.INSTANCE : values.json(0);
        }
        return nested(values, shape, 0, 0);
    }

    private static JsonValue nested(Values values, long[] shape, int dim, long offset) {
        long stride = 1;
        for (int d = dim + 1; d < shape.length; d++) {
            stride *= shape[d];
        }
        List<JsonValue> items = new ArrayList<>((int) Math.min(shape[dim], 1 << 16));
        for (long i = 0; i < shape[dim]; i++) {
            long at = offset + i * stride;
            items.add(dim == shape.length - 1 ? values.json((int) at) : nested(values, shape, dim + 1, at));
        }
        return new JsonArray(items);
    }
}
