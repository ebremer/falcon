package com.ebremer.falcon.zarr.json;

import java.util.Map;

/**
 * Serializes a {@link JsonValue} to text. Two forms are offered: a canonical compact form (no
 * insignificant whitespace) and a pretty form (two-space indent). Both:
 *
 * <ul>
 *   <li>emit object members in their stored insertion order (so metadata round-trips stably);</li>
 *   <li>emit each {@link JsonNumber} from its exact literal;</li>
 *   <li>escape only what JSON requires ({@code "}, {@code \\}, and control characters), leaving
 *       {@code /} and non-ASCII characters as raw UTF-8.</li>
 * </ul>
 */
final class JsonWriter {

    private final StringBuilder sb = new StringBuilder();
    private final boolean pretty;

    private JsonWriter(boolean pretty) {
        this.pretty = pretty;
    }

    static String write(JsonValue value) {
        JsonWriter w = new JsonWriter(false);
        w.writeValue(value, 0);
        return w.sb.toString();
    }

    static String writePretty(JsonValue value) {
        JsonWriter w = new JsonWriter(true);
        w.writeValue(value, 0);
        return w.sb.toString();
    }

    private void writeValue(JsonValue value, int depth) {
        switch (value) {
            case JsonObject o -> writeObject(o, depth);
            case JsonArray a -> writeArray(a, depth);
            case JsonString s -> writeString(s.value());
            case JsonNumber n -> sb.append(n.literal());
            case JsonBool b -> sb.append(b.value() ? "true" : "false");
            case JsonNull ignored -> sb.append("null");
        }
    }

    private void writeObject(JsonObject o, int depth) {
        Map<String, JsonValue> members = o.members();
        if (members.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> e : members.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newlineIndent(depth + 1);
            writeString(e.getKey());
            sb.append(':');
            if (pretty) {
                sb.append(' ');
            }
            writeValue(e.getValue(), depth + 1);
        }
        newlineIndent(depth);
        sb.append('}');
    }

    private void writeArray(JsonArray a, int depth) {
        if (a.size() == 0) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (JsonValue v : a.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newlineIndent(depth + 1);
            writeValue(v, depth + 1);
        }
        newlineIndent(depth);
        sb.append(']');
    }

    private void writeString(String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private void newlineIndent(int depth) {
        if (!pretty) {
            return;
        }
        sb.append('\n');
        sb.append("  ".repeat(depth));
    }
}
