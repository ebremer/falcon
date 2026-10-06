package com.ebremer.falcon.zarr.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A strict recursive-descent JSON parser (RFC&nbsp;8259). Zarr metadata documents are small, so the
 * whole text is parsed from an in-memory {@code String}. Nesting depth is bounded to keep pathological
 * input from overflowing the stack.
 *
 * <p>Two departures from RFC&nbsp;8259, both about what real metadata holds:
 * <ul>
 *   <li>the bare tokens {@code NaN}, {@code Infinity}, and {@code -Infinity}, which Python's
 *       {@code json.dumps} writes (zarr-python writes them in attributes), are read as
 *       {@link JsonNumber}s whose literal is the token;</li>
 *   <li>a key repeated within one object is an error (RFC&nbsp;8259 leaves its meaning open; taking the
 *       last silently would read a document two ways).</li>
 * </ul>
 */
final class JsonReader {

    /** Maximum object/array nesting. Zarr metadata nests only a few levels (codecs, sharding). */
    private static final int MAX_DEPTH = 256;

    private final String s;
    private int pos;

    private JsonReader(String s) {
        this.s = s;
    }

    static JsonValue parse(String text) {
        JsonReader r = new JsonReader(text);
        r.skipWhitespace();
        JsonValue v = r.readValue(0);
        r.skipWhitespace();
        if (r.pos != r.s.length()) {
            throw r.error("trailing content after JSON value");
        }
        return v;
    }

    private JsonValue readValue(int depth) {
        if (pos >= s.length()) {
            throw error("unexpected end of input");
        }
        char c = s.charAt(pos);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> new JsonString(readString());
            case 't', 'f' -> readBool();
            case 'n' -> readNull();
            case 'N' -> readToken("NaN");
            case 'I' -> readToken("Infinity");
            default -> {
                if (c == '-' && s.startsWith("-Infinity", pos)) {
                    yield readToken("-Infinity");
                }
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private JsonObject readObject(int depth) {
        checkDepth(depth);
        expect('{');
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return new JsonObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected string key in object");
            }
            int keyAt = pos;
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            JsonValue value = readValue(depth + 1);
            if (members.putIfAbsent(key, value) != null) {
                pos = keyAt;
                throw error("duplicate key \"" + JsonNumber.quote(key) + "\" in object");
            }
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return new JsonObject(members);
            }
            if (c != ',') {
                throw error("expected ',' or '}' in object");
            }
        }
    }

    private JsonArray readArray(int depth) {
        checkDepth(depth);
        expect('[');
        List<JsonValue> values = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return new JsonArray(values);
        }
        while (true) {
            skipWhitespace();
            values.add(readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return new JsonArray(values);
            }
            if (c != ',') {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                sb.append(readEscape());
            } else if (c < 0x20) {
                throw error("unescaped control character U+" + hex4(c) + " in string");
            } else {
                sb.append(c);
            }
        }
    }

    private char readEscape() {
        if (pos >= s.length()) {
            throw error("unterminated escape");
        }
        char c = s.charAt(pos++);
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw error("invalid escape '\\" + c + "'");
        };
    }

    private char readUnicodeEscape() {
        if (pos + 4 > s.length()) {
            throw error("truncated \\u escape");
        }
        int cp = 0;
        for (int i = 0; i < 4; i++) {
            char h = s.charAt(pos++);
            int d = Character.digit(h, 16);
            if (d < 0) {
                throw error("invalid hex digit '" + h + "' in \\u escape");
            }
            cp = (cp << 4) | d;
        }
        return (char) cp;
    }

    private JsonNumber readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        // integer part
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw error("invalid number");
        }
        // fraction
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) {
                throw error("invalid number: expected digit after '.'");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        // exponent
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("invalid number: expected digit in exponent");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        return new JsonNumber(s.substring(start, pos));
    }

    /** One of the non-finite number tokens Python writes ({@code NaN}, {@code Infinity}, {@code -Infinity}). */
    private JsonNumber readToken(String token) {
        if (!s.startsWith(token, pos)) {
            throw error("invalid literal");
        }
        pos += token.length();
        return new JsonNumber(token);
    }

    private JsonBool readBool() {
        if (s.startsWith("true", pos)) {
            pos += 4;
            return JsonBool.TRUE;
        }
        if (s.startsWith("false", pos)) {
            pos += 5;
            return JsonBool.FALSE;
        }
        throw error("invalid literal");
    }

    private JsonNull readNull() {
        if (s.startsWith("null", pos)) {
            pos += 4;
            return JsonNull.INSTANCE;
        }
        throw error("invalid literal");
    }

    private void checkDepth(int depth) {
        if (depth >= MAX_DEPTH) {
            throw error("JSON nesting deeper than " + MAX_DEPTH);
        }
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    /** The current character without consuming, or {@code '\0'} at end of input. */
    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    /** Consumes and returns the current character. */
    private char next() {
        if (pos >= s.length()) {
            throw error("unexpected end of input");
        }
        return s.charAt(pos++);
    }

    private void expect(char c) {
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String hex4(int c) {
        return String.format("%04X", c);
    }

    private JsonException error(String message) {
        return new JsonException("JSON at position " + pos + ": " + message);
    }
}
