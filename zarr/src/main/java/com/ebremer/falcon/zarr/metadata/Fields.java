package com.ebremer.falcon.zarr.metadata;

import com.ebremer.falcon.zarr.ZarrFormatException;
import com.ebremer.falcon.zarr.ZarrUnsupportedException;
import com.ebremer.falcon.zarr.json.JsonArray;
import com.ebremer.falcon.zarr.json.JsonBool;
import com.ebremer.falcon.zarr.json.JsonException;
import com.ebremer.falcon.zarr.json.JsonNumber;
import com.ebremer.falcon.zarr.json.JsonObject;
import com.ebremer.falcon.zarr.json.JsonString;
import com.ebremer.falcon.zarr.json.JsonValue;
import java.util.Map;
import java.util.Set;

/**
 * Typed field extraction for metadata parsing. Each helper reports a {@link ZarrFormatException} that
 * names the offending JSON path (for example {@code "array/zarr.json.chunk_grid.configuration"}),
 * turning the {@code json} layer's structural {@link JsonException}s into precise metadata diagnostics.
 */
final class Fields {

    /** A shared empty object, used as the default for absent {@code attributes} / {@code configuration}. */
    static final JsonObject EMPTY_OBJECT = new JsonObject(Map.of());

    private Fields() {
    }

    /** The value of a required member, or {@link ZarrFormatException} if absent. */
    static JsonValue require(JsonObject o, String key, String ctx) {
        return o.find(key).orElseThrow(
                () -> new ZarrFormatException(ctx + ": missing required field '" + key + "'"));
    }

    static JsonObject object(JsonValue v, String ctx) {
        if (v instanceof JsonObject o) {
            return o;
        }
        throw typeError(ctx, "object", v);
    }

    static JsonArray array(JsonValue v, String ctx) {
        if (v instanceof JsonArray a) {
            return a;
        }
        throw typeError(ctx, "array", v);
    }

    static String string(JsonValue v, String ctx) {
        if (v instanceof JsonString s) {
            return s.value();
        }
        throw typeError(ctx, "string", v);
    }

    static JsonNumber number(JsonValue v, String ctx) {
        if (v instanceof JsonNumber n) {
            return n;
        }
        throw typeError(ctx, "number", v);
    }

    /** A JSON integer that fits a {@code long}, or {@link ZarrFormatException}. */
    static long integer(JsonValue v, String ctx) {
        try {
            return number(v, ctx).longValue();
        } catch (JsonException e) {
            throw new ZarrFormatException(ctx + ": expected an integer: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a JSON array of integers into a {@code long[]}. When {@code positive} the values must be
     * &gt; 0 (a chunk shape); otherwise they must be &ge; 0 (an array shape).
     */
    static long[] intArray(JsonValue v, String ctx, boolean positive) {
        JsonArray a = array(v, ctx);
        long[] out = new long[a.size()];
        for (int i = 0; i < a.size(); i++) {
            String elemCtx = ctx + "[" + i + "]";
            long d;
            try {
                d = number(a.get(i), elemCtx).longValue();
            } catch (JsonException e) {
                throw new ZarrFormatException(elemCtx + ": expected an integer", e);
            }
            if (positive ? d <= 0 : d < 0) {
                throw new ZarrFormatException(
                        elemCtx + ": must be " + (positive ? "positive" : "non-negative") + ", was " + d);
            }
            out[i] = d;
        }
        return out;
    }

    /**
     * Validates {@code zarr_format == 3} in a {@code zarr.json}. A value of {@code 2} is refused as
     * unsupported there (Zarr v2 metadata is read from {@code .zarray}/{@code .zgroup}); any other value
     * is malformed.
     */
    static void requireZarrFormat3(JsonObject o, String ctx) {
        long format = integer(require(o, "zarr_format", ctx), ctx + ".zarr_format");
        if (format == 2) {
            throw new ZarrUnsupportedException(
                    ctx + ": zarr_format 2 in zarr.json (Zarr v2 metadata is read from .zarray and .zgroup)");
        }
        if (format != 3) {
            throw new ZarrFormatException(ctx + ": unsupported zarr_format " + format);
        }
    }

    /**
     * Enforces the v3 specification's rule for members outside {@code known}: an unknown member is an
     * extension the reader must understand, so it fails to open, unless it is an object that says
     * {@code "must_understand": false}, which may be ignored.
     *
     * @throws ZarrUnsupportedException for an unknown member that may not be ignored
     */
    static void checkUnknownFields(JsonObject o, Set<String> known, String ctx) {
        for (Map.Entry<String, JsonValue> e : o.members().entrySet()) {
            if (known.contains(e.getKey())) {
                continue;
            }
            if (e.getValue() instanceof JsonObject ext
                    && ext.members().get("must_understand") instanceof JsonBool flag
                    && !flag.value()) {
                continue;
            }
            throw new ZarrUnsupportedException(ctx + ": unknown field '" + e.getKey() + "' cannot be ignored"
                    + " (only an object with \"must_understand\": false may be)");
        }
    }

    private static ZarrFormatException typeError(String ctx, String expected, JsonValue actual) {
        return new ZarrFormatException(ctx + ": expected " + expected + ", was " + actual.typeName());
    }
}
