package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * A read-only {@link Store} over HTTP(S): each key is fetched from {@code base + "/" + key}. Built on
 * {@code java.net.HttpURLConnection} so the module stays {@code java.base}-only.
 *
 * <p>Partial reads use the HTTP {@code Range} header (byte-range requests), which is what makes a
 * remote sharded array efficient; {@link #getSuffix} reads the end of a value in one request
 * ({@code Range: bytes=-N}), and {@link #size} uses a {@code HEAD}. Plain HTTP offers no directory
 * listing, so {@link #list}, {@link #listPrefix}, and {@link #listDir} are unsupported &mdash; a remote
 * array reads fine (its chunk keys are computed, not listed) and a group navigates to a <em>named</em>
 * child fine, but enumerating a group's children ({@code childNames}) does not work over HTTP.
 *
 * <p>Requests:
 * <ul>
 *   <li>each segment of a key is percent-encoded (RFC 3986), so a key may hold spaces, {@code '%'},
 *       {@code '#'}, {@code '?'}, or non-ASCII characters;</li>
 *   <li>a query in the base URL (a presigned or SAS URL) is kept and sent with every key, after the key's
 *       path;</li>
 *   <li>redirects are followed, up to ten, from {@code http} to {@code https} too, but never from
 *       {@code https} to {@code http};</li>
 *   <li>a key is absent when the server answers {@code 404}. S3 and GCS answer {@code 403} for an absent
 *       key in a public bucket that may not be listed; {@link Builder#missingStatuses} can count that as
 *       absent too, at the cost of reading a denied key as missing (fill values) rather than failing;</li>
 *   <li>a whole value is read into one array of at most 2&nbsp;GB, and a range never beyond the length
 *       asked for: a larger body fails with {@link ZarrException}.</li>
 * </ul>
 *
 * <p>It may be used from several threads at once.
 */
public final class HttpStore implements Store {

    private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REDIRECTS = 10;
    private static final long MAX_BODY_BYTES = Integer.MAX_VALUE - 8; // the largest array the JDK allocates
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_PERMANENT_REDIRECT = 308;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    private final String base;  // scheme://authority/path, without a trailing '/'
    private final String query; // the base URL's query (no '?'), sent with every key, or null
    private final int timeoutMillis;
    private final Set<Integer> missingStatuses;
    private final long maxBodyBytes;

    private HttpStore(Builder b) {
        String url = b.baseUrl;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            url = url.substring(0, hash); // a fragment is never sent
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a valid URL: " + b.baseUrl, e);
        }
        if (!uri.isAbsolute() || !isHttp(uri.getScheme())) {
            throw new IllegalArgumentException("an HTTP store needs an http or https URL, not " + b.baseUrl);
        }
        int question = url.indexOf('?');
        String path = question < 0 ? url : url.substring(0, question);
        this.query = question < 0 || question == url.length() - 1 ? null : url.substring(question + 1);
        this.base = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        this.timeoutMillis = b.timeoutMillis;
        this.missingStatuses = Set.copyOf(b.missingStatuses);
        this.maxBodyBytes = b.maxBodyBytes;
    }

    /** Opens a read-only store rooted at {@code baseUrl} (for example {@code https://host/data/store}). */
    public static HttpStore openReadOnly(String baseUrl) {
        return builder(baseUrl).build();
    }

    /** Opens a read-only store rooted at {@code baseUrl} with the given connect/read timeout. */
    public static HttpStore openReadOnly(String baseUrl, int timeoutMillis) {
        return builder(baseUrl).timeoutMillis(timeoutMillis).build();
    }

    /**
     * A builder for a read-only store rooted at {@code baseUrl}, for settings beyond the URL.
     *
     * @param baseUrl the store's root, such as {@code https://host/data/store}, or a presigned URL with a
     *                query
     * @return a builder with the defaults: a 30-second timeout, and 404 as the only "absent" status
     */
    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /** Settings for an {@link HttpStore}. */
    public static final class Builder {

        private final String baseUrl;
        private int timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        private Set<Integer> missingStatuses = Set.of(HttpURLConnection.HTTP_NOT_FOUND);
        private long maxBodyBytes = MAX_BODY_BYTES;

        private Builder(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        }

        /**
         * Sets the connect and read timeout (default 30 s; 0 waits forever).
         *
         * @param timeoutMillis the timeout in milliseconds
         * @return this builder
         * @throws IllegalArgumentException if {@code timeoutMillis} is negative
         */
        public Builder timeoutMillis(int timeoutMillis) {
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("timeout must not be negative: " + timeoutMillis);
            }
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        /**
         * Sets the HTTP statuses that mean a key is absent, in place of the default {@code 404}. Pass
         * {@code 404, 403} for an S3 or GCS bucket that answers {@code 403} for a key it does not hold; a
         * key the server truly denies then reads as absent too.
         *
         * @param statuses the statuses, each a client or server error (400&ndash;599)
         * @return this builder
         * @throws IllegalArgumentException if none is given, or one is not 400&ndash;599
         */
        public Builder missingStatuses(int... statuses) {
            if (statuses.length == 0) {
                throw new IllegalArgumentException("give at least one status that means a key is absent");
            }
            Set<Integer> set = new TreeSet<>();
            for (int status : statuses) {
                if (status < 400 || status > 599) {
                    throw new IllegalArgumentException("an absent key's status must be 400-599, not " + status);
                }
                set.add(status);
            }
            this.missingStatuses = set;
            return this;
        }

        /** Caps a whole value read into one array (default and most: the JDK's array limit). For tests. */
        Builder maxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = Math.min(maxBodyBytes, MAX_BODY_BYTES);
            return this;
        }

        /**
         * The store.
         *
         * @return a read-only store with these settings
         * @throws IllegalArgumentException if the base URL is not an absolute {@code http} or {@code https}
         *                                  URL
         */
        public HttpStore build() {
            return new HttpStore(this);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        StoreKeys.validate(key);
        HttpURLConnection connection = request(key, "GET", null);
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return Optional.empty();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new ZarrException("HTTP " + code + " reading '" + key + "'");
            }
            return Optional.of(readBody(connection, key));
        } catch (IOException e) {
            throw new ZarrException("failed to read '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long offset, long length) {
        int len = MemoryStore.checkedLength(offset, length);
        StoreKeys.validate(key);
        if (len == 0) {
            return exists(key) ? Optional.of(new byte[0]) : Optional.empty();
        }
        long last = offset > Long.MAX_VALUE - len ? Long.MAX_VALUE : offset + len - 1;
        HttpURLConnection connection = request(key, "GET", "bytes=" + offset + "-" + last);
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return Optional.empty();
            }
            if (code == HTTP_RANGE_NOT_SATISFIABLE) { // past the end
                return Optional.of(new byte[0]);
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                long[] range = contentRange(connection.getHeaderField("Content-Range"));
                if (range != null && range[0] >= 0 && range[0] != offset) {
                    throw new ZarrException("asked for '" + key + "' from byte " + offset
                            + " but the server sent from byte " + range[0]);
                }
                return Optional.of(readAtMost(connection, len, key));
            }
            if (code == HttpURLConnection.HTTP_OK) {
                // The server ignored the Range header and sends the whole value: skip to the range, and stop
                // reading at its end.
                try (InputStream in = connection.getInputStream()) {
                    return Optional.of(skip(in, offset) ? in.readNBytes(len) : new byte[0]);
                }
            }
            throw new ZarrException("HTTP " + code + " reading range of '" + key + "'");
        } catch (IOException e) {
            throw new ZarrException("failed to read range of '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
    }

    /** Reads the end of a value in one request, {@code Range: bytes=-length}. */
    @Override
    public Optional<byte[]> getSuffix(String key, long length) {
        int len = MemoryStore.checkedLength(0, length);
        StoreKeys.validate(key);
        if (len == 0) {
            return exists(key) ? Optional.of(new byte[0]) : Optional.empty();
        }
        HttpURLConnection connection = request(key, "GET", "bytes=-" + len);
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return Optional.empty();
            }
            if (code == HTTP_RANGE_NOT_SATISFIABLE) { // an empty value has no end to send
                return Optional.of(new byte[0]);
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                return Optional.of(readAtMost(connection, len, key));
            }
            if (code == HttpURLConnection.HTTP_OK) {
                // The server ignored the Range header and sends the whole value: keep only its end.
                long total = connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream()) {
                    if (total >= 0) {
                        return Optional.of(skip(in, Math.max(0, total - len)) ? in.readNBytes(len) : new byte[0]);
                    }
                    return Optional.of(tail(in, len));
                }
            }
            throw new ZarrException("HTTP " + code + " reading the end of '" + key + "'");
        } catch (IOException e) {
            throw new ZarrException("failed to read the end of '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public boolean exists(String key) {
        StoreKeys.validate(key);
        HttpURLConnection connection = request(key, "HEAD", null);
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return false;
            }
            if (code == HttpURLConnection.HTTP_OK) {
                return true;
            }
            if (!headUnsupported(code)) {
                throw new ZarrException("HTTP " + code + " for HEAD '" + key + "'");
            }
        } catch (IOException e) {
            throw new ZarrException("failed to HEAD '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
        return sizeByRange(key).isPresent();
    }

    /**
     * The value's size, from a {@code HEAD}'s {@code Content-Length}. When the server sends none, or does
     * not answer {@code HEAD}, a {@code GET} of byte 0 asks for it instead ({@code Content-Range} carries
     * the total).
     */
    @Override
    public OptionalLong size(String key) {
        StoreKeys.validate(key);
        HttpURLConnection connection = request(key, "HEAD", null);
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return OptionalLong.empty();
            }
            if (code == HttpURLConnection.HTTP_OK) {
                long length = connection.getContentLengthLong();
                if (length >= 0) {
                    return OptionalLong.of(length);
                }
            } else if (!headUnsupported(code)) {
                throw new ZarrException("HTTP " + code + " for HEAD '" + key + "'");
            }
        } catch (IOException e) {
            throw new ZarrException("failed to HEAD '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
        return sizeByRange(key);
    }

    /** The size from a {@code GET} of byte 0, whose {@code Content-Range} carries the total. */
    private OptionalLong sizeByRange(String key) {
        HttpURLConnection connection = request(key, "GET", "bytes=0-0");
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return OptionalLong.empty();
            }
            long[] range = contentRange(connection.getHeaderField("Content-Range"));
            long total = range == null ? -1 : range[2];
            if (code == HttpURLConnection.HTTP_PARTIAL && total >= 0) {
                return OptionalLong.of(total);
            }
            if (code == HTTP_RANGE_NOT_SATISFIABLE) { // only an empty value has no byte 0
                return OptionalLong.of(Math.max(total, 0));
            }
            if (code == HttpURLConnection.HTTP_OK) {
                // The server ignored the Range header: the whole value comes, so its length is the size.
                long length = connection.getContentLengthLong();
                if (length >= 0) {
                    return OptionalLong.of(length);
                }
                try (InputStream in = connection.getInputStream()) {
                    return OptionalLong.of(in.transferTo(OutputStream.nullOutputStream()));
                }
            }
            throw new ZarrException("HTTP " + code + " finding the size of '" + key + "'");
        } catch (IOException e) {
            throw new ZarrException("failed to find the size of '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
    }

    private static boolean headUnsupported(int code) {
        return code == HttpURLConnection.HTTP_BAD_METHOD || code == HttpURLConnection.HTTP_NOT_IMPLEMENTED;
    }

    @Override
    public List<String> list() {
        throw new UnsupportedOperationException("HTTP stores cannot list keys");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        throw new UnsupportedOperationException("HTTP stores cannot list keys");
    }

    @Override
    public List<String> listDir(String prefix) {
        throw new UnsupportedOperationException("HTTP stores cannot list keys");
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public void set(String key, byte[] value) {
        throw new UnsupportedOperationException("HTTP store is read-only");
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException("HTTP store is read-only");
    }

    /** The URL of {@code key}: the base, the key's percent-encoded segments, then the base's query. */
    String url(String key) {
        return base + "/" + encodePath(key) + (query == null ? "" : "?" + query);
    }

    /**
     * Percent-encodes a key as a URL path (RFC 3986): each UTF-8 byte that is not unreserved
     * ({@code A-Z a-z 0-9 - . _ ~}) becomes {@code %XX}, and {@code '/'} stays the segment separator.
     */
    static String encodePath(String key) {
        StringBuilder out = new StringBuilder(key.length() + 16);
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean keep = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/';
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
     * Sends a request for {@code key} and returns its connection, its response status read, after
     * following redirects here: {@code HttpURLConnection} follows none between {@code http} and
     * {@code https}, and one from {@code http} to {@code https} is common. None to {@code http} from
     * {@code https} is followed.
     */
    private HttpURLConnection request(String key, String method, String range) {
        URI uri;
        try {
            uri = new URI(url(key));
        } catch (URISyntaxException e) {
            throw new ZarrException("bad HTTP request for key '" + key + "'", e);
        }
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection;
            int code;
            try {
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);
                connection.setInstanceFollowRedirects(false);
                if (range != null) {
                    connection.setRequestProperty("Range", range);
                }
                code = connection.getResponseCode();
            } catch (IOException | IllegalArgumentException e) {
                throw new ZarrException("failed to request '" + key + "' over HTTP", e);
            }
            if (!isRedirect(code)) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new ZarrException("HTTP " + code + " for '" + key + "' names no Location to go to");
            }
            if (redirects == MAX_REDIRECTS) {
                throw new ZarrException("more than " + MAX_REDIRECTS + " redirects for '" + key + "'");
            }
            URI next;
            try {
                next = uri.resolve(new URI(location));
            } catch (URISyntaxException e) {
                throw new ZarrException("HTTP " + code + " for '" + key + "' redirects to a bad URL: " + location, e);
            }
            if (!redirectAllowed(uri, next)) {
                throw new ZarrException("refusing to follow a redirect for '" + key + "' from " + uri.getScheme()
                        + " to " + next);
            }
            uri = next;
        }
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER || code == HTTP_TEMPORARY_REDIRECT
                || code == HTTP_PERMANENT_REDIRECT;
    }

    private static boolean isHttp(String scheme) {
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    /** Whether a redirect may be followed: to {@code http} or {@code https}, but not from {@code https} to {@code http}. */
    static boolean redirectAllowed(URI from, URI to) {
        return isHttp(to.getScheme()) && !(from.getScheme().equalsIgnoreCase("https") && to.getScheme().equalsIgnoreCase("http"));
    }

    /** A whole value, which must fit one array. */
    private byte[] readBody(HttpURLConnection connection, String key) throws IOException {
        long declared = connection.getContentLengthLong();
        if (declared > maxBodyBytes) {
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
            byte[] body = in.readNBytes((int) maxBodyBytes); // no length given: read up to the cap
            if (in.read() >= 0) {
                throw new ZarrException("'" + key + "' is more than " + maxBodyBytes
                        + " bytes, too large to read into one array");
            }
            return body;
        }
    }

    /** A partial body, which must be no longer than the {@code len} bytes asked for. */
    private static byte[] readAtMost(HttpURLConnection connection, int len, String key) throws IOException {
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

    /** Skips {@code n} bytes; false if the stream ends first. */
    private static boolean skip(InputStream in, long n) throws IOException {
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
    private static byte[] tail(InputStream in, int len) throws IOException {
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
