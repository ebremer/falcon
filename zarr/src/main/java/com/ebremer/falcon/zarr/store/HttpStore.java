package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * ({@code Range: bytes=-N}), and {@link #size} uses a {@code HEAD}.
 *
 * <p>HTTP has no way to list keys. A remote array reads without listing (its chunk keys are computed), and a
 * group opens a <em>named</em> child, or answers from consolidated metadata; but listing a group's children
 * ({@code childNames}) needs {@link #list}, {@link #listPrefix}, or {@link #listDir}, which by default throw
 * {@link UnsupportedOperationException}. {@link Builder#directoryListing} turns listing on: keys are then read
 * from the directory listings a static file server makes (Python's {@code http.server}, nginx's
 * {@code autoindex}, Apache's {@code mod_autoindex}), as fsspec's HTTP filesystem reads them. See there for
 * what counts as a key.
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
 *   <li>headers of the caller's, fixed ({@link Builder#header}) or made for each request
 *       ({@link Builder#requestHeaders}, for a bearer token that expires, or a signature), are sent only
 *       to the base URL's origin (its scheme, host, and port): a redirect elsewhere gets none of them, as
 *       curl drops {@code Authorization}. So an {@code http} base URL that redirects to {@code https}
 *       loses them; give the {@code https} URL.</li>
 * </ul>
 *
 * <p>For Amazon S3 or S3-compatible object storage with credentials, listing, and writing, see the
 * {@code S3Store} of Falcon's {@code s3} module (over the AWS SDK); a public bucket, or a presigned or SAS
 * URL, needs only this store.
 *
 * <p>It may be used from several threads at once.
 */
public final class HttpStore implements Store {

    private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REDIRECTS = 10;
    private static final long MAX_BODY_BYTES = HttpIo.MAX_BODY_BYTES;
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_PERMANENT_REDIRECT = 308;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = HttpIo.HTTP_RANGE_NOT_SATISFIABLE;
    static final int MAX_LISTING_DEPTH = 128;
    static final int MAX_LISTING_PAGES = 1_000_000;

    private final String base;  // scheme://authority/path, without a trailing '/'
    private final String query; // the base URL's query (no '?'), sent with every key, or null
    private final String origin; // the base URL's origin: the only one the caller's headers go to
    private final int timeoutMillis;
    private final Set<Integer> missingStatuses;
    private final long maxBodyBytes;
    private final Map<String, String> headers;  // fixed headers, checked
    private final RequestHeaders requestHeaders; // per-request headers, or null
    private final boolean directoryListing;       // whether to list keys from directory listing pages

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
        this.origin = HttpIo.origin(uri);
        this.timeoutMillis = b.timeoutMillis;
        this.missingStatuses = Set.copyOf(b.missingStatuses);
        this.maxBodyBytes = b.maxBodyBytes;
        this.headers = Map.copyOf(b.givenHeaders());
        this.requestHeaders = b.requestHeaders;
        this.directoryListing = b.directoryListing;
    }

    /**
     * Makes headers for each request an {@link HttpStore} sends, such as a bearer token that expires and
     * must be fetched again, or a signature over the request. It is called once per request, redirects to
     * the same origin included, and never for a request to another origin, which gets none of the
     * caller's headers. It may be called from several threads at once.
     */
    @FunctionalInterface
    public interface RequestHeaders {

        /**
         * The headers to add to one request.
         *
         * @param method the request's method: {@code GET} or {@code HEAD}
         * @param uri    the request's full URL, its key percent-encoded and its query included
         * @return the headers to add, by name; empty or {@code null} for none. A name the store or the JDK's
         *         HTTP client sets ({@code Range}, {@code Host}, {@code Content-Length}, ...), or a value
         *         with a line break or another control character, is refused with
         *         {@link IllegalArgumentException} when the request is made. A header that
         *         {@link Builder#header} also sets is sent with this value.
         */
        Map<String, String> headers(String method, URI uri);
    }

    /**
     * Opens a read-only store rooted at {@code baseUrl} (for example {@code https://host/data/store}), with
     * the {@link #builder} defaults.
     *
     * @param baseUrl the store's root URL
     * @return the store; nothing is requested until it is read
     * @throws IllegalArgumentException if {@code baseUrl} is not an absolute {@code http} or {@code https}
     *                                  URL
     */
    public static HttpStore openReadOnly(String baseUrl) {
        return builder(baseUrl).build();
    }

    /**
     * Opens a read-only store rooted at {@code baseUrl} with the given connect/read timeout.
     *
     * @param baseUrl       the store's root URL
     * @param timeoutMillis the connect and read timeout in milliseconds; 0 waits forever
     * @return the store; nothing is requested until it is read
     * @throws IllegalArgumentException if {@code baseUrl} is not an absolute {@code http} or {@code https}
     *                                  URL, or {@code timeoutMillis} is negative
     */
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
        private final Map<String, String> headers = new LinkedHashMap<>(); // lower-case name -> value
        private final Map<String, String> headerNames = new LinkedHashMap<>(); // lower-case name -> as given
        private RequestHeaders requestHeaders;
        private boolean directoryListing;

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

        /**
         * Adds a header to every request to the base URL's origin, such as {@code Authorization} or an API
         * key. Call it once per header; a second call with the same name (in any case) replaces the value.
         *
         * @param name  the header's name
         * @param value its value
         * @return this builder
         * @throws IllegalArgumentException if the name is not a valid header name or is one the store or the
         *                                  JDK's HTTP client sets ({@code Range}, {@code Host},
         *                                  {@code Content-Length}, ...), or the value holds a line break or
         *                                  another control character
         */
        public Builder header(String name, String value) {
            HttpIo.checkHeader(name, value);
            String lower = name.toLowerCase(Locale.ROOT);
            headers.put(lower, value);
            headerNames.put(lower, name);
            return this;
        }

        /**
         * Sets a hook that makes headers for each request, called as each is sent: for a token that expires,
         * or to sign a request. Its headers go only to the base URL's origin, like {@link #header}'s.
         *
         * @param hook the hook, or {@code null} for none
         * @return this builder
         */
        public Builder requestHeaders(RequestHeaders hook) {
            this.requestHeaders = hook;
            return this;
        }

        /**
         * Lets the store list keys from the server's directory listings: the HTML page a static file server
         * makes for a directory's URL (Python's {@code http.server}, nginx's {@code autoindex}, Apache's
         * {@code mod_autoindex}), as fsspec's HTTP filesystem reads them. Off by default, since a server's
         * other pages (an error page answered with {@code 200}, an application's start page) would list
         * links that are not keys.
         *
         * <p>With it on, {@link HttpStore#listDir listDir(prefix)} reads the page at the prefix's URL, ending
         * in {@code '/'}, and takes each link to a name directly below it: one ending in {@code '/'} is a
         * child prefix, any other a key. Links are resolved against the page, after any redirect; a link with
         * a query or a fragment (a column's sort order), to another origin, to the parent, or further down is
         * not a name, nor is one that no store key can be. A prefix the server does not have (a status of
         * {@link #missingStatuses}) lists nothing; a page that is not HTML fails with {@link ZarrException}.
         * {@link HttpStore#list} and {@link HttpStore#listPrefix} read the pages below too, a request per
         * directory, and fail more than 128 levels down (a server can link a directory into itself) or past
         * a million pages. A child prefix is listed whether or not it holds keys: an empty directory lists as
         * one.
         *
         * @param enabled whether to list keys from directory listings
         * @return this builder
         */
        public Builder directoryListing(boolean enabled) {
            this.directoryListing = enabled;
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

        /** The fixed headers by the names given, for the store. */
        private Map<String, String> givenHeaders() {
            Map<String, String> given = new LinkedHashMap<>();
            headers.forEach((lower, value) -> given.put(headerNames.get(lower), value));
            return given;
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

    /**
     * Every key, read from the server's directory listings (see {@link Builder#directoryListing}).
     *
     * @throws UnsupportedOperationException if directory listing is off, the default
     * @throws ZarrException                 if a listing cannot be read, or the walk goes too deep or too far
     */
    @Override
    public List<String> list() {
        return listPrefix("");
    }

    /**
     * Every key beginning with {@code prefix}, read from the server's directory listings (see
     * {@link Builder#directoryListing}): that of the directory the prefix names up to its last {@code '/'},
     * and those of the directories below it that can hold such keys.
     *
     * @throws UnsupportedOperationException if directory listing is off, the default
     * @throws IllegalArgumentException      if the prefix's part up to its last {@code '/'} is not a valid key
     * @throws ZarrException                 if a listing cannot be read, or the walk goes too deep or too far
     */
    @Override
    public List<String> listPrefix(String prefix) {
        requireListing();
        int slash = prefix.lastIndexOf('/');
        String start = directory(slash < 0 ? "" : prefix.substring(0, slash + 1));
        TreeSet<String> keys = new TreeSet<>();
        walk(start, prefix, keys, 0, new int[] {0});
        return List.copyOf(keys);
    }

    /**
     * The names directly below {@code prefix}, read from the server's directory listing of it (see
     * {@link Builder#directoryListing}): keys, and child prefixes ending in {@code '/'}.
     *
     * @throws UnsupportedOperationException if directory listing is off, the default
     * @throws IllegalArgumentException      if the prefix is neither empty nor a valid key, with or without a
     *                                       trailing {@code '/'}
     * @throws ZarrException                 if the listing cannot be read
     */
    @Override
    public List<String> listDir(String prefix) {
        requireListing();
        return List.copyOf(readListing(directory(prefix)));
    }

    private void requireListing() {
        if (!directoryListing) {
            throw new UnsupportedOperationException("HTTP stores cannot list keys unless directory listing is on "
                    + "(HttpStore.Builder.directoryListing) and the server makes listings");
        }
    }

    /** {@code prefix} as a directory: empty, or a valid key followed by {@code '/'}. */
    private static String directory(String prefix) {
        String dir = StoreKeys.asDirPrefix(prefix);
        if (!dir.isEmpty()) {
            StoreKeys.validate(dir.substring(0, dir.length() - 1));
        }
        return dir;
    }

    /** Adds the keys under {@code dir} that begin with {@code prefix}, going down where there can be some. */
    private void walk(String dir, String prefix, Set<String> keys, int depth, int[] pages) {
        if (depth > MAX_LISTING_DEPTH) {
            throw new ZarrException("listing '" + prefix + "' goes more than " + MAX_LISTING_DEPTH
                    + " directories down, to '" + dir + "': does the server link a directory into itself?");
        }
        if (++pages[0] > MAX_LISTING_PAGES) {
            throw new ZarrException("listing '" + prefix + "' takes more than " + MAX_LISTING_PAGES + " pages");
        }
        for (String name : readListing(dir)) {
            if (name.endsWith("/")) {
                if (name.startsWith(prefix) || prefix.startsWith(name)) {
                    walk(name, prefix, keys, depth + 1, pages);
                }
            } else if (name.startsWith(prefix)) {
                keys.add(name);
            }
        }
    }

    /** The names {@code dir}'s listing page holds, or none if the server has no such directory. */
    private Set<String> readListing(String dir) {
        String label = dir.isEmpty() ? "/" : dir;
        URI uri;
        try {
            uri = new URI(base + "/" + (dir.isEmpty() ? "" : encodePath(dir.substring(0, dir.length() - 1)) + "/")
                    + (query == null ? "" : "?" + query));
        } catch (URISyntaxException e) {
            throw new ZarrException("bad HTTP request for the listing of '" + label + "'", e);
        }
        HttpURLConnection connection = request(uri, label, "GET", null);
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return Set.of();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new ZarrException("HTTP " + code + " reading the listing of '" + label + "'");
            }
            String type = connection.getContentType();
            if (type != null && !DirectoryListing.isHtml(type)) {
                throw new ZarrException("the listing of '" + label + "' is " + type + ", not an HTML directory listing");
            }
            byte[] page = readBody(connection, label);
            return DirectoryListing.names(new String(page, DirectoryListing.charset(type)),
                    connection.getURL().toURI(), dir, query);
        } catch (IOException | URISyntaxException e) {
            throw new ZarrException("failed to read the listing of '" + label + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
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
        return HttpIo.encodePath(key);
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
        return request(uri, key, method, range);
    }

    /** As {@link #request(String, String, String)}, for any URL; {@code key} names it in messages. */
    private HttpURLConnection request(URI start, String key, String method, String range) {
        URI uri = start;
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
            } catch (IOException | IllegalArgumentException e) {
                throw new ZarrException("failed to request '" + key + "' over HTTP", e);
            }
            if (HttpIo.origin(uri).equals(origin)) { // the caller's headers never go to another origin
                try {
                    addHeaders(connection, method, uri);
                } catch (RuntimeException e) {
                    connection.disconnect();
                    throw e;
                }
            }
            try {
                code = connection.getResponseCode();
            } catch (IOException e) {
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

    /** Adds the fixed headers, then the hook's (which win over a fixed header of the same name). */
    private void addHeaders(HttpURLConnection connection, String method, URI uri) {
        headers.forEach(connection::setRequestProperty);
        if (requestHeaders != null) {
            Map<String, String> made = requestHeaders.headers(method, uri);
            if (made != null) {
                HttpIo.checkHeaders(made);
                made.forEach(connection::setRequestProperty);
            }
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
        return HttpIo.readBody(connection, key, maxBodyBytes);
    }

    private static byte[] readAtMost(HttpURLConnection connection, int len, String key) throws IOException {
        return HttpIo.readAtMost(connection, len, key);
    }

    private static boolean skip(InputStream in, long n) throws IOException {
        return HttpIo.skip(in, n);
    }

    private static byte[] tail(InputStream in, int len) throws IOException {
        return HttpIo.tail(in, len);
    }

    /**
     * Parses {@code Content-Range: bytes first-last/total} into {@code {first, last, total}}; see
     * {@link HttpIo#contentRange}.
     */
    static long[] contentRange(String header) {
        return HttpIo.contentRange(header);
    }
}
