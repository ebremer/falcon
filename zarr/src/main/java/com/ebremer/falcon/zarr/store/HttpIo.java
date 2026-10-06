package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What {@link HttpStore} and {@link S3Store} share: percent-encoding a key, reading bounded bodies, and
 * checking request headers.
 */
final class HttpIo {

    /** The largest array the JDK allocates, and so the largest whole value a store reads. */
    static final long MAX_BODY_BYTES = Integer.MAX_VALUE - 8;
    static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    /**
     * Headers a caller may not set: {@code Range}, which the store sends, and the headers
     * {@code HttpURLConnection} owns and would silently drop or override.
     */
    private static final Set<String> OWNED = Set.of(
            "range", "host", "content-length", "connection", "transfer-encoding", "keep-alive", "upgrade",
            "trailer", "via", "origin", "content-transfer-encoding", "access-control-request-headers",
            "access-control-request-method");

    private HttpIo() {
    }

    /**
     * Percent-encodes a key as a URL path (RFC 3986): each UTF-8 byte that is not unreserved
     * ({@code A-Z a-z 0-9 - . _ ~}) becomes {@code %XX}, and {@code '/'} stays the segment separator.
     */
    static String encodePath(String key) {
        return encode(key, true);
    }

    /**
     * Percent-encodes {@code s} (RFC 3986): every UTF-8 byte but the unreserved characters becomes
     * {@code %XX}, with upper-case hex digits; {@code '/'} is kept when {@code keepSlash}.
     */
    static String encode(String s, boolean keepSlash) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean keep = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || (keepSlash && c == '/');
            if (keep) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /**
     * Checks one header a caller adds to requests.
     *
     * @throws IllegalArgumentException if the name is not an HTTP token, the value holds a control
     *                                  character (CR and LF included) or a character beyond Latin-1, or
     *                                  the header is one the store or the JDK's HTTP client owns
     */
    static void checkHeader(String name, String value) {
        Objects.requireNonNull(name, "header name");
        Objects.requireNonNull(value, "value of header " + name);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a header name must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean token = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) {
                throw new IllegalArgumentException("not a valid header name: '" + name + "'");
            }
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) {
                throw new IllegalArgumentException("the value of header " + name
                        + " holds a character a header may not (U+" + String.format("%04X", (int) c) + ")");
            }
        }
        if (OWNED.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("the store sets header " + name + " itself");
        }
    }

    /** Checks every header in {@code headers} ({@link #checkHeader}). */
    static void checkHeaders(Map<String, String> headers) {
        for (Map.Entry<String, String> h : headers.entrySet()) {
            checkHeader(h.getKey(), h.getValue());
        }
    }

    /** A URL's origin: its scheme, host, and port, the port given even when it is the default. */
    static String origin(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port < 0) {
            port = scheme.equals("https") ? 443 : 80;
        }
        String host = uri.getHost() != null ? uri.getHost()
                : uri.getRawAuthority() != null ? uri.getRawAuthority() : "";
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** A whole value, at most {@code max} bytes. */
    static byte[] readBody(HttpURLConnection connection, String key, long max) throws IOException {
        long declared = connection.getContentLengthLong();
        if (declared > max) {
            throw new ZarrException("'" + key + "' is " + declared + " bytes, too large to read into one array");
        }
        try (InputStream in = connection.getInputStream()) {
            if (declared >= 0) {
                byte[] body = in.readNBytes((int) declared);
                if (body.length < declared) {
                    throw new ZarrException("'" + key + "' ended after " + body.length + " of " + declared + " bytes");
                }
                return body;
            }
            byte[] body = in.readNBytes((int) max); // no length given: read up to the cap
            if (in.read() >= 0) {
                throw new ZarrException("'" + key + "' is more than " + max + " bytes, too large to read into one array");
            }
            return body;
        }
    }

    /** A partial body, which must be no longer than the {@code len} bytes asked for. */
    static byte[] readAtMost(HttpURLConnection connection, int len, String key) throws IOException {
        long declared = connection.getContentLengthLong();
        if (declared > len) {
            throw new ZarrException("asked for " + len + " bytes of '" + key + "' but the server sends " + declared);
        }
        try (InputStream in = connection.getInputStream()) {
            byte[] body = in.readNBytes(len);
            if (in.read() >= 0) {
                throw new ZarrException("asked for " + len + " bytes of '" + key + "' but the server sends more");
            }
            return body;
        }
    }

    /**
     * The first {@code max} bytes of an error response's body, or none; the rest is not read. Failing to
     * read it is not an error: the status already says what went wrong.
     */
    static byte[] errorBody(HttpURLConnection connection, int max) {
        try (InputStream error = connection.getErrorStream()) {
            if (error != null) {
                return error.readNBytes(max);
            }
        } catch (IOException e) {
            return new byte[0];
        }
        try (InputStream in = connection.getInputStream()) { // a 3xx's body is not an "error stream"
            return in.readNBytes(max);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** Skips {@code n} bytes; false if the stream ends first. */
    static boolean skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return false;
                }
                skipped = 1;
            }
            left -= skipped;
        }
        return true;
    }

    /** The last {@code len} bytes of a stream of unknown length, keeping no more than {@code len}. */
    static byte[] tail(InputStream in, int len) throws IOException {
        byte[] ring = new byte[len];
        long total = 0;
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) >= 0; ) {
            for (int i = 0; i < n; i++) {
                ring[(int) ((total + i) % len)] = chunk[i];
            }
            total += n;
        }
        int size = (int) Math.min(total, len);
        byte[] out = new byte[size];
        long start = total - size;
        for (int i = 0; i < size; i++) {
            out[i] = ring[(int) ((start + i) % len)];
        }
        return out;
    }

    /**
     * Parses {@code Content-Range: bytes first-last/total} (or {@code bytes *}{@code /total}) into
     * {@code {first, last, total}}, with -1 for a part given as {@code *}; {@code null} if absent or
     * unreadable.
     */
    static long[] contentRange(String header) {
        if (header == null) {
            return null;
        }
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes ", 0, 6)) {
            return null;
        }
        h = h.substring(6).trim();
        int slash = h.indexOf('/');
        if (slash < 0) {
            return null;
        }
        try {
            String span = h.substring(0, slash).trim();
            String size = h.substring(slash + 1).trim();
            long total = size.equals("*") ? -1 : Long.parseLong(size);
            if (span.equals("*")) {
                return new long[] {-1, -1, total};
            }
            int dash = span.indexOf('-');
            if (dash < 0) {
                return null;
            }
            return new long[] {Long.parseLong(span.substring(0, dash).trim()),
                Long.parseLong(span.substring(dash + 1).trim()), total};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
