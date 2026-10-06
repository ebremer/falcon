package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A {@link Store} over S3-compatible object storage: Amazon S3, Google Cloud Storage through its XML API
 * and HMAC keys, MinIO, Cloudflare R2, and others that speak the S3 API. Each key is an object under the
 * store's prefix in one bucket. Built on {@code java.net.HttpURLConnection}, so the module stays
 * {@code java.base}-only.
 *
 * <pre>{@code
 * Store store = S3Store.fromUrl("s3://my-bucket/data/image.zarr")
 *         .region("eu-west-1")
 *         .fromEnvironment()      // AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN
 *         .build();
 * ZarrGroup root = Zarr.openGroup(store);
 * }</pre>
 *
 * <p><b>Requests.</b> {@link #get} is a {@code GET}, {@link #getRange} and {@link #getSuffix} a {@code GET}
 * with a {@code Range}, {@link #size} and {@link #exists} a {@code HEAD}, {@link #set} a {@code PUT}, and
 * {@link #delete} a {@code DELETE}. The listings use {@code ListObjectsV2}, a page of up to 1,000 keys per
 * request, {@link #listDir} with the delimiter {@code '/'}, so a group's children are listed without
 * listing its arrays' chunks. A listing leaves out an object whose name is not a valid store key, such as
 * the "folder" objects ending in {@code '/'} that consoles create.
 *
 * <p><b>Signing.</b> With credentials, each request is signed with AWS Signature Version 4 (HMAC-SHA256 from
 * {@code javax.crypto}), the body's SHA-256 included; a session token is sent and signed too. Without
 * credentials (the default) requests go unsigned, which reads a public bucket, and the store is read-only.
 * The S3 error code and message of a failed request are in the {@link ZarrException}'s message. A bucket
 * in another region than the one configured is reported with the region it is in; requests are never
 * redirected.
 *
 * <p><b>Retries.</b> A request answered {@code 500}, {@code 502}, {@code 503} (S3's {@code SlowDown}), or
 * {@code 504}, or that fails to connect or is cut off before its status, is sent again, up to
 * {@link Builder#maxRetries} times (3 by default), after waiting 100&nbsp;ms, then 200, then 400, ....
 * Every request the store makes may be repeated: each reads or replaces a whole object.
 *
 * <p><b>Absent keys.</b> A key is absent when S3 answers {@code 404}. A bucket that may be read but not
 * listed answers {@code 403} for an absent key; {@link Builder#missingStatuses} can count that as absent
 * too, at the cost of reading a denied key as missing (fill values) rather than failing.
 *
 * <p>It may be used from several threads at once.
 */
public final class S3Store implements Store {

    private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int MAX_RETRIES = 10;
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 100;
    private static final int MAX_LIST_PAGE_BYTES = 32 << 20; // a page of 1,000 keys is a few hundred KiB
    private static final int MAX_ERROR_BYTES = 64 << 10;
    private static final int MAX_MESSAGE_CHARS = 300;
    private static final String SERVICE = "s3";

    private final String bucket;
    private final String region;
    private final String scheme;     // "http" or "https"
    private final String authority;  // host[:port] requests go to (the bucket in it, for virtual hosting)
    private final String bucketPath; // the bucket's path ("" for virtual hosting, else "/bucket"), no '/' at the end
    private final String prefix;     // the store's root in the bucket: "" or "a/b/"
    private final String accessKeyId; // null when anonymous
    private final String secretKey;
    private final String sessionToken; // or null
    private final boolean writable;
    private final int timeoutMillis;
    private final Set<Integer> missingStatuses;
    private final int maxRetries;
    private final long retryDelayMillis;
    private final long maxBodyBytes;
    private final Clock clock;

    private S3Store(Builder b) {
        this.bucket = b.bucket;
        this.region = b.region;
        boolean pathStyle = b.pathStyle != null ? b.pathStyle : b.endpoint != null || !virtualHostable(b.bucket);
        if (!pathStyle && !virtualHostable(b.bucket)) {
            throw new IllegalArgumentException("bucket '" + b.bucket + "' cannot be addressed as a host name"
                    + " (it has a '.', an upper-case letter, or '_'); use path-style addressing");
        }
        URI endpoint = b.endpoint != null ? b.endpoint : URI.create("https://s3." + b.region + ".amazonaws.com");
        this.scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String endpointPath = endpoint.getRawPath() == null ? "" : endpoint.getRawPath();
        if (endpointPath.endsWith("/")) {
            endpointPath = endpointPath.substring(0, endpointPath.length() - 1);
        }
        if (pathStyle) {
            this.authority = endpoint.getRawAuthority();
            this.bucketPath = endpointPath + "/" + b.bucket;
        } else {
            this.authority = b.bucket + "." + endpoint.getRawAuthority();
            this.bucketPath = endpointPath;
        }
        this.prefix = b.prefix;
        this.accessKeyId = b.accessKeyId;
        this.secretKey = b.secretKey;
        this.sessionToken = b.sessionToken;
        this.writable = b.accessKeyId != null && !b.readOnly;
        this.timeoutMillis = b.timeoutMillis;
        this.missingStatuses = Set.copyOf(b.missingStatuses);
        this.maxRetries = b.maxRetries;
        this.retryDelayMillis = b.retryDelayMillis;
        this.maxBodyBytes = b.maxBodyBytes;
        this.clock = b.clock;
    }

    /**
     * A builder for a store over {@code bucket}, rooted at the bucket's top until {@link Builder#prefix} says
     * otherwise.
     *
     * @param bucket the bucket's name
     * @return a builder with the defaults: region {@code us-east-1}, Amazon's endpoint for it, no credentials
     *         (read-only), a 30-second timeout, 3 retries, and 404 as the only "absent" status
     * @throws IllegalArgumentException if the name is not a bucket name: 1&ndash;255 letters, digits,
     *                                  {@code '.'}, {@code '-'}, or {@code '_'}
     */
    public static Builder builder(String bucket) {
        return new Builder(bucket);
    }

    /**
     * A builder for the bucket and prefix of an {@code s3://bucket/prefix} URL (the prefix may be absent).
     *
     * @param s3Url the URL, such as {@code s3://my-bucket/data/image.zarr}
     * @return a builder for that bucket and prefix, with {@link #builder}'s defaults otherwise
     * @throws IllegalArgumentException if the URL is not an {@code s3://} URL naming a bucket, has a query
     *                                  or fragment, or its prefix is not a valid store key
     */
    public static Builder fromUrl(String s3Url) {
        Objects.requireNonNull(s3Url, "s3Url");
        if (!s3Url.regionMatches(true, 0, "s3://", 0, 5)) {
            throw new IllegalArgumentException("not an s3:// URL: " + s3Url);
        }
        if (s3Url.indexOf('?') >= 0 || s3Url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("an s3:// URL names a bucket and a prefix, no query or fragment: " + s3Url);
        }
        String rest = s3Url.substring(5);
        int slash = rest.indexOf('/');
        Builder b = new Builder(slash < 0 ? rest : rest.substring(0, slash));
        if (slash >= 0) {
            b.prefix(rest.substring(slash + 1));
        }
        return b;
    }

    /** Settings for an {@link S3Store}. */
    public static final class Builder {

        private final String bucket;
        private String region = "us-east-1";
        private boolean regionSet;
        private URI endpoint;      // null: Amazon's endpoint for the region
        private Boolean pathStyle; // null: path style with an endpoint, or for a bucket no host name can hold
        private String prefix = "";
        private String accessKeyId;
        private String secretKey;
        private String sessionToken;
        private boolean readOnly;
        private int timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
        private Set<Integer> missingStatuses = Set.of(HttpURLConnection.HTTP_NOT_FOUND);
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private long retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS;
        private long maxBodyBytes = HttpIo.MAX_BODY_BYTES;
        private Clock clock = Clock.systemUTC();

        private Builder(String bucket) {
            Objects.requireNonNull(bucket, "bucket");
            if (bucket.isEmpty() || bucket.length() > 255 || !bucket.chars().allMatch(
                    c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                            || c == '.' || c == '-' || c == '_')) {
                throw new IllegalArgumentException("not a bucket name: '" + bucket + "'");
            }
            this.bucket = bucket;
        }

        /**
         * Sets the bucket's region (default {@code us-east-1}), which every signature names and which picks
         * Amazon's endpoint. Google Cloud Storage and Cloudflare R2 take {@code auto}.
         *
         * @param region the region, such as {@code eu-west-1}
         * @return this builder
         * @throws IllegalArgumentException if the region is empty or holds a character other than a letter,
         *                                  a digit, {@code '-'}, or {@code '_'}
         */
        public Builder region(String region) {
            Objects.requireNonNull(region, "region");
            if (region.isEmpty() || !region.chars().allMatch(
                    c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                throw new IllegalArgumentException("not a region: '" + region + "'");
            }
            this.region = region;
            this.regionSet = true;
            return this;
        }

        /**
         * Sends requests to {@code url} instead of Amazon's endpoint: {@code http://localhost:9000} for MinIO,
         * {@code https://<account>.r2.cloudflarestorage.com} for R2, {@code https://storage.googleapis.com}
         * for Google Cloud Storage. Requests then address the bucket in the path ({@code /bucket/key}) unless
         * {@link #pathStyle} says otherwise.
         *
         * @param url the endpoint, an {@code http} or {@code https} URL, which may have a path
         * @return this builder
         * @throws IllegalArgumentException if the URL is not an absolute {@code http} or {@code https} URL with
         *                                  a host, or has a query, fragment, or user information
         */
        public Builder endpoint(String url) {
            Objects.requireNonNull(url, "url");
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("not a valid URL: " + url, e);
            }
            String s = uri.getScheme();
            if (s == null || !(s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https")) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException(
                        "an S3 endpoint is an http or https URL with a host and no query, fragment, or user: " + url);
            }
            this.endpoint = uri;
            return this;
        }

        /**
         * Chooses how requests address the bucket: in the path ({@code https://endpoint/bucket/key}) or in the
         * host name ({@code https://bucket.endpoint/key}). By default, Amazon's endpoint is addressed by host
         * name, unless the bucket's name cannot be a host name (a {@code '.'} breaks TLS certificates), and
         * an {@link #endpoint} by path, as MinIO needs.
         *
         * @param pathStyle whether to address the bucket in the path
         * @return this builder
         */
        public Builder pathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
            return this;
        }

        /**
         * Roots the store at {@code prefix} in the bucket: key {@code k} is the object {@code prefix/k}.
         *
         * @param prefix a {@code '/'}-separated path such as {@code data/image.zarr}; leading and trailing
         *               {@code '/'} are ignored, and an empty prefix is the bucket's top
         * @return this builder
         * @throws IllegalArgumentException if the prefix is not a valid store key
         */
        public Builder prefix(String prefix) {
            Objects.requireNonNull(prefix, "prefix");
            String p = prefix;
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (!p.isEmpty()) {
                StoreKeys.validate(p);
            }
            this.prefix = p.isEmpty() ? "" : p + "/";
            return this;
        }

        /**
         * Signs requests with an access key and its secret.
         *
         * @param accessKeyId     the access key's id
         * @param secretAccessKey its secret
         * @return this builder
         * @throws IllegalArgumentException if either is empty
         */
        public Builder credentials(String accessKeyId, String secretAccessKey) {
            return credentials(accessKeyId, secretAccessKey, null);
        }

        /**
         * Signs requests with temporary credentials: an access key, its secret, and a session token.
         *
         * @param accessKeyId     the access key's id
         * @param secretAccessKey its secret
         * @param sessionToken    the session token, or {@code null} for none
         * @return this builder
         * @throws IllegalArgumentException if the key or secret is empty, or a value holds a control
         *                                  character
         */
        public Builder credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
            Objects.requireNonNull(accessKeyId, "accessKeyId");
            Objects.requireNonNull(secretAccessKey, "secretAccessKey");
            if (accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
                throw new IllegalArgumentException("an access key id and its secret must not be empty");
            }
            if (!headerSafe(accessKeyId) || (sessionToken != null && !headerSafe(sessionToken))) {
                throw new IllegalArgumentException(
                        "an access key id or session token holds a character a header may not (a line break?)");
            }
            this.accessKeyId = accessKeyId;
            this.secretKey = secretAccessKey;
            this.sessionToken = sessionToken == null || sessionToken.isEmpty() ? null : sessionToken;
            return this;
        }

        /**
         * Takes the credentials from the environment, as the AWS command line does:
         * {@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}, and {@code AWS_SESSION_TOKEN} if set; and
         * the region from {@code AWS_REGION} or {@code AWS_DEFAULT_REGION}, unless {@link #region} set one.
         * Profiles ({@code ~/.aws/credentials}) and instance roles are not read.
         *
         * @return this builder
         * @throws IllegalStateException if {@code AWS_ACCESS_KEY_ID} or {@code AWS_SECRET_ACCESS_KEY} is not set
         */
        public Builder fromEnvironment() {
            return fromEnvironment(System::getenv);
        }

        Builder fromEnvironment(Function<String, String> env) {
            String id = env.apply("AWS_ACCESS_KEY_ID");
            String secret = env.apply("AWS_SECRET_ACCESS_KEY");
            if (id == null || id.isEmpty() || secret == null || secret.isEmpty()) {
                throw new IllegalStateException("AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY must both be set");
            }
            credentials(id, secret, env.apply("AWS_SESSION_TOKEN"));
            if (!regionSet) {
                String r = env.apply("AWS_REGION");
                if (r == null || r.isEmpty()) {
                    r = env.apply("AWS_DEFAULT_REGION");
                }
                if (r != null && !r.isEmpty()) {
                    region(r);
                }
            }
            return this;
        }

        /**
         * Sends requests unsigned, for a public bucket (the default). The store is then read-only.
         *
         * @return this builder
         */
        public Builder anonymous() {
            this.accessKeyId = null;
            this.secretKey = null;
            this.sessionToken = null;
            return this;
        }

        /**
         * Makes the store read-only, even with credentials.
         *
         * @return this builder
         */
        public Builder readOnly() {
            this.readOnly = true;
            return this;
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
         * {@code 404, 403} for a bucket that may be read but not listed, which answers {@code 403} for a key
         * it does not hold; a key the bucket truly denies then reads as absent too.
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
         * Sets how many times a request is sent again after a server error or a failed connection (default
         * 3; 0 sends each once).
         *
         * @param maxRetries the number of retries, 0&ndash;10
         * @return this builder
         * @throws IllegalArgumentException if it is outside 0&ndash;10
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
                throw new IllegalArgumentException("retries must be 0-" + MAX_RETRIES + ", not " + maxRetries);
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /** The first retry's wait, doubled for each further one. For tests. */
        Builder retryDelayMillis(long millis) {
            this.retryDelayMillis = millis;
            return this;
        }

        /** Caps a whole value read into one array (default and most: the JDK's array limit). For tests. */
        Builder maxBodyBytes(long maxBodyBytes) {
            this.maxBodyBytes = Math.min(maxBodyBytes, HttpIo.MAX_BODY_BYTES);
            return this;
        }

        /** The clock that dates signatures. For tests. */
        Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * The store.
         *
         * @return a store with these settings: writable with credentials unless {@link #readOnly}
         * @throws IllegalArgumentException if host-name addressing was asked for a bucket whose name cannot be
         *                                  a host name
         */
        public S3Store build() {
            return new S3Store(this);
        }
    }

    /** Whether {@code value} may go into a header: printable Latin-1, or a tab. */
    private static boolean headerSafe(String value) {
        return value.chars().allMatch(c -> (c >= 0x20 || c == '\t') && c != 0x7f && c <= 0xff);
    }

    /** Whether the bucket can be addressed in a host name: lower case, digits, and '-', 3 to 63 of them. */
    private static boolean virtualHostable(String bucket) {
        return bucket.length() >= 3 && bucket.length() <= 63 && bucket.chars().allMatch(
                c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')
                && bucket.charAt(0) != '-' && bucket.charAt(bucket.length() - 1) != '-';
    }

    // ---- reads --------------------------------------------------------------------------------------

    @Override
    public Optional<byte[]> get(String key) {
        StoreKeys.validate(key);
        HttpURLConnection connection = send("GET", objectPath(key), List.of(), null, null, key);
        boolean reusable = false;
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return Optional.empty();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw failure(connection, code, "GET " + object(key));
            }
            byte[] body = HttpIo.readBody(connection, key, maxBodyBytes);
            reusable = true;
            return Optional.of(body);
        } catch (IOException e) {
            throw new ZarrException("failed to read " + object(key) + " from " + this, e);
        } finally {
            finish(connection, reusable);
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
        HttpURLConnection connection = send("GET", objectPath(key), List.of(), "bytes=" + offset + "-" + last, null, key);
        boolean reusable = false;
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return Optional.empty();
            }
            if (code == HttpIo.HTTP_RANGE_NOT_SATISFIABLE) { // past the end
                return Optional.of(new byte[0]);
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                long[] range = HttpIo.contentRange(connection.getHeaderField("Content-Range"));
                if (range != null && range[0] >= 0 && range[0] != offset) {
                    throw new ZarrException("asked for '" + key + "' from byte " + offset
                            + " but the server sent from byte " + range[0]);
                }
                byte[] body = HttpIo.readAtMost(connection, len, key);
                reusable = true;
                return Optional.of(body);
            }
            if (code == HttpURLConnection.HTTP_OK) { // the Range was ignored: skip to it, stop at its end
                try (InputStream in = connection.getInputStream()) {
                    return Optional.of(HttpIo.skip(in, offset) ? in.readNBytes(len) : new byte[0]);
                }
            }
            throw failure(connection, code, "GET a range of " + object(key));
        } catch (IOException e) {
            throw new ZarrException("failed to read a range of " + object(key) + " from " + this, e);
        } finally {
            finish(connection, reusable);
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
        HttpURLConnection connection = send("GET", objectPath(key), List.of(), "bytes=-" + len, null, key);
        boolean reusable = false;
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return Optional.empty();
            }
            if (code == HttpIo.HTTP_RANGE_NOT_SATISFIABLE) { // an empty value has no end to send
                return Optional.of(new byte[0]);
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                byte[] body = HttpIo.readAtMost(connection, len, key);
                reusable = true;
                return Optional.of(body);
            }
            if (code == HttpURLConnection.HTTP_OK) { // the Range was ignored: keep only the end
                long total = connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream()) {
                    if (total >= 0) {
                        return Optional.of(HttpIo.skip(in, Math.max(0, total - len)) ? in.readNBytes(len) : new byte[0]);
                    }
                    return Optional.of(HttpIo.tail(in, len));
                }
            }
            throw failure(connection, code, "GET the end of " + object(key));
        } catch (IOException e) {
            throw new ZarrException("failed to read the end of " + object(key) + " from " + this, e);
        } finally {
            finish(connection, reusable);
        }
    }

    @Override
    public boolean exists(String key) {
        StoreKeys.validate(key);
        return head(key) >= 0;
    }

    @Override
    public OptionalLong size(String key) {
        StoreKeys.validate(key);
        long size = head(key);
        return size < 0 ? OptionalLong.empty() : OptionalLong.of(size);
    }

    /** The object's size from a {@code HEAD}, or -1 if it is absent. */
    private long head(String key) {
        HttpURLConnection connection = send("HEAD", objectPath(key), List.of(), null, null, key);
        boolean reusable = false;
        try {
            int code = connection.getResponseCode();
            if (missingStatuses.contains(code)) {
                return -1;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw failure(connection, code, "HEAD " + object(key));
            }
            long length = connection.getContentLengthLong();
            if (length < 0) {
                throw new ZarrException("HEAD " + object(key) + " in " + this + " gave no Content-Length");
            }
            drain(connection);
            reusable = true;
            return length;
        } catch (IOException e) {
            throw new ZarrException("failed to HEAD " + object(key) + " in " + this, e);
        } finally {
            finish(connection, reusable);
        }
    }

    // ---- listings -----------------------------------------------------------------------------------

    @Override
    public List<String> list() {
        return listPrefix("");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        TreeSet<String> out = new TreeSet<>();
        listObjects(this.prefix + prefix, false, key -> {
            if (key.startsWith(this.prefix)) {
                addIfKey(out, key.substring(this.prefix.length()));
            }
        }, common -> { });
        return List.copyOf(out);
    }

    @Override
    public List<String> listDir(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String dir = StoreKeys.asDirPrefix(prefix);
        TreeSet<String> out = new TreeSet<>();
        String full = this.prefix + dir;
        listObjects(full, true, key -> {
            if (key.startsWith(full) && key.length() > full.length() && key.indexOf('/', full.length()) < 0) {
                addIfKey(out, key.substring(this.prefix.length()));
            }
        }, common -> {
            if (common.startsWith(full) && common.length() > full.length() + 1
                    && common.indexOf('/', full.length()) == common.length() - 1) {
                String child = common.substring(this.prefix.length(), common.length() - 1);
                if (isKey(child)) {
                    out.add(child + "/");
                }
            }
        });
        return List.copyOf(out);
    }

    private static void addIfKey(TreeSet<String> out, String key) {
        if (isKey(key)) {
            out.add(key);
        }
    }

    private static boolean isKey(String key) {
        try {
            StoreKeys.validate(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false; // a "folder" object ("a/"), or a name with an empty segment: not a store key
        }
    }

    /**
     * Lists every object under {@code s3Prefix} with {@code ListObjectsV2}, page by page, passing each
     * key, and each common prefix when {@code delimited}, to the consumers.
     */
    private void listObjects(String s3Prefix, boolean delimited, java.util.function.Consumer<String> keys,
                             java.util.function.Consumer<String> commonPrefixes) {
        String token = null;
        Set<String> seenTokens = new HashSet<>();
        String what = "the listing of " + this;
        while (true) {
            List<Map.Entry<String, String>> params = new ArrayList<>();
            params.add(Map.entry("list-type", "2"));
            params.add(Map.entry("encoding-type", "url"));
            if (!s3Prefix.isEmpty()) {
                params.add(Map.entry("prefix", s3Prefix));
            }
            if (delimited) {
                params.add(Map.entry("delimiter", "/"));
            }
            if (token != null) {
                params.add(Map.entry("continuation-token", token));
            }
            HttpURLConnection connection = send("GET", bucketPath.isEmpty() ? "/" : bucketPath, params, null, null,
                    s3Prefix);
            Xml.Element result;
            boolean reusable = false;
            try {
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw failure(connection, code, "list the objects under '" + s3Prefix + "'");
                }
                byte[] page = HttpIo.readBody(connection, "a listing page", MAX_LIST_PAGE_BYTES);
                reusable = true;
                result = Xml.parse(page, what);
            } catch (IOException e) {
                throw new ZarrException("failed to list " + this, e);
            } finally {
                finish(connection, reusable);
            }
            if (!result.localName().equals("ListBucketResult")) {
                throw new ZarrException("a listing of " + this + " answered <" + result.name() + ">, not a ListBucketResult");
            }
            boolean urlEncoded = "url".equals(result.childText("EncodingType"));
            for (Xml.Element contents : result.children("Contents")) {
                String key = contents.childText("Key");
                if (key != null) {
                    keys.accept(urlEncoded ? decode(key) : key);
                }
            }
            for (Xml.Element common : result.children("CommonPrefixes")) {
                String p = common.childText("Prefix");
                if (p != null) {
                    commonPrefixes.accept(urlEncoded ? decode(p) : p);
                }
            }
            if (!"true".equals(result.childText("IsTruncated"))) {
                return;
            }
            token = result.childText("NextContinuationToken");
            if (token == null || token.isEmpty() || !seenTokens.add(token)) {
                throw new ZarrException("a listing of " + this + " says it continues, but "
                        + (token == null || token.isEmpty() ? "gives no continuation token" : "repeats a continuation token"));
            }
        }
    }

    /** A key from a listing asked for with {@code encoding-type=url}: percent-encoded, a space as {@code '+'}. */
    private String decode(String encoded) {
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ZarrException("a listing of " + this + " holds a badly encoded key: " + shorten(encoded), e);
        }
    }

    // ---- writes -------------------------------------------------------------------------------------

    @Override
    public boolean isWritable() {
        return writable;
    }

    @Override
    public void set(String key, byte[] value) {
        StoreKeys.validate(key);
        Objects.requireNonNull(value, "value");
        checkWritable();
        HttpURLConnection connection = send("PUT", objectPath(key), List.of(), null, value, key);
        boolean reusable = false;
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_NO_CONTENT
                    && code != HttpURLConnection.HTTP_CREATED) {
                throw failure(connection, code, "PUT " + object(key));
            }
            drain(connection);
            reusable = true;
        } catch (IOException e) {
            throw new ZarrException("failed to write " + object(key) + " to " + this, e);
        } finally {
            finish(connection, reusable);
        }
    }

    @Override
    public void delete(String key) {
        StoreKeys.validate(key);
        checkWritable();
        HttpURLConnection connection = send("DELETE", objectPath(key), List.of(), null, null, key);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_NO_CONTENT && code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_ACCEPTED && code != HttpURLConnection.HTTP_NOT_FOUND) {
                throw failure(connection, code, "DELETE " + object(key));
            }
        } catch (IOException e) {
            throw new ZarrException("failed to delete " + object(key) + " from " + this, e);
        } finally {
            connection.disconnect();
        }
    }

    private void checkWritable() {
        if (!writable) {
            throw new UnsupportedOperationException("S3 store " + this + " is read-only"
                    + (accessKeyId == null ? " (it has no credentials)" : ""));
        }
    }

    // ---- requests -----------------------------------------------------------------------------------

    /** The object for {@code key}, quoted, for messages: the prefixed key. */
    private String object(String key) {
        return "'" + prefix + key + "'";
    }

    /**
     * Ends a request: a connection whose body was read to its end is left open, for the JDK to send the
     * next request to that host over it; any other is closed.
     */
    private static void finish(HttpURLConnection connection, boolean reusable) {
        if (!reusable) {
            connection.disconnect();
        }
    }

    /** The path of the object for {@code key}: the bucket's path, then the prefixed key, percent-encoded. */
    String objectPath(String key) {
        return bucketPath + "/" + HttpIo.encodePath(prefix + key);
    }

    /** The URL a request to {@code path} with {@code query} goes to. */
    URI uri(String path, String query) {
        try {
            return new URI(scheme + "://" + authority + path + (query.isEmpty() ? "" : "?" + query));
        } catch (URISyntaxException e) {
            throw new ZarrException("bad S3 request URL for " + path, e);
        }
    }

    /**
     * Sends a request and returns its connection with the status read, retrying a server error or a failed
     * connection with growing waits.
     */
    private HttpURLConnection send(String method, String path, List<Map.Entry<String, String>> params, String range,
                                   byte[] body, String key) {
        String query = SigV4.canonicalQuery(params);
        URI uri = uri(path, query);
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = open(method, uri, path, query, range, body);
                int code = connection.getResponseCode();
                if (attempt < maxRetries && retryable(code)) {
                    connection.disconnect();
                    pause(attempt, key);
                    continue;
                }
                return connection;
            } catch (IOException e) {
                if (connection != null) {
                    connection.disconnect();
                }
                if (attempt >= maxRetries) {
                    throw new ZarrException("failed to " + method + " '" + prefix + key + "' in " + this, e);
                }
                pause(attempt, key);
            } catch (IllegalArgumentException e) {
                throw new ZarrException("bad S3 request for '" + prefix + key + "' in " + this, e);
            }
        }
    }

    private static boolean retryable(int code) {
        return code == HttpURLConnection.HTTP_INTERNAL_ERROR || code == HttpURLConnection.HTTP_BAD_GATEWAY
                || code == HttpURLConnection.HTTP_UNAVAILABLE || code == HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
    }

    private void pause(int attempt, String key) {
        try {
            Thread.sleep(retryDelayMillis << Math.min(attempt, 20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZarrException("interrupted while waiting to retry a request for '" + key + "'", e);
        }
    }

    /** Opens, signs, and sends one request; its body, if any, is written. */
    private HttpURLConnection open(String method, URI uri, String path, String query, String range, byte[] body)
            throws IOException {
        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        if (range != null) {
            connection.setRequestProperty("Range", range);
        }
        if (accessKeyId != null) {
            String date = SigV4.amzDate(clock.instant());
            String payload = body == null ? SigV4.EMPTY_SHA256 : SigV4.sha256Hex(body);
            TreeMap<String, String> signed = new TreeMap<>();
            signed.put("host", hostHeader(url));
            signed.put("x-amz-content-sha256", payload);
            signed.put("x-amz-date", date);
            if (range != null) {
                signed.put("range", range);
            }
            if (sessionToken != null) {
                signed.put("x-amz-security-token", sessionToken);
            }
            connection.setRequestProperty("Authorization", SigV4.authorization(method, path, query, signed, payload,
                    accessKeyId, secretKey, region, SERVICE, date));
            connection.setRequestProperty("x-amz-content-sha256", payload);
            connection.setRequestProperty("x-amz-date", date);
            if (sessionToken != null) {
                connection.setRequestProperty("x-amz-security-token", sessionToken);
            }
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
        }
        return connection;
    }

    /**
     * The {@code Host} header {@code HttpURLConnection} sends for {@code url}, which a signature must name
     * exactly: the host, and the port unless it is the scheme's default.
     */
    static String hostHeader(URL url) {
        int port = url.getPort();
        return port != -1 && port != url.getDefaultPort() ? url.getHost() + ":" + port : url.getHost();
    }

    private static void drain(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            in.transferTo(OutputStream.nullOutputStream());
        }
    }

    /**
     * The failure of a request: its status, S3's error code and message from the body, and, for a bucket in
     * another region, which region that is.
     */
    private ZarrException failure(HttpURLConnection connection, int code, String request) {
        String bucketRegion = connection.getHeaderField("x-amz-bucket-region");
        String s3Code = null;
        String message = null;
        String bodyRegion = null;
        byte[] body = HttpIo.errorBody(connection, MAX_ERROR_BYTES);
        if (body.length > 0) {
            try {
                Xml.Element error = Xml.parse(body, "an S3 error");
                if (error.localName().equals("Error")) {
                    s3Code = error.childText("Code");
                    message = error.childText("Message");
                    bodyRegion = error.childText("Region");
                }
            } catch (ZarrException e) {
                // not S3's error document: the status says what went wrong
            }
        }
        StringBuilder m = new StringBuilder("S3 ").append(request).append(" in ").append(this).append(": HTTP ")
                .append(code);
        if (s3Code != null) {
            m.append(' ').append(shorten(s3Code));
        }
        if (message != null && !message.isBlank()) {
            m.append(": ").append(shorten(message));
        }
        String elsewhere = bucketRegion != null ? bucketRegion : bodyRegion;
        if (elsewhere != null && !elsewhere.equals(region)) {
            m.append(" (the bucket is in region '").append(shorten(elsewhere)).append("', not '").append(region)
                    .append("': set region(\"").append(shorten(elsewhere)).append("\"))");
        } else if (code >= 300 && code < 400) {
            m.append(" (redirects are not followed; check the region and endpoint)");
        } else if (code == HttpURLConnection.HTTP_FORBIDDEN && accessKeyId == null) {
            m.append(" (the store is anonymous: give credentials for a bucket that is not public)");
        }
        return new ZarrException(m.toString());
    }

    private static String shorten(String s) {
        String flat = s.replaceAll("\\p{Cntrl}", " ");
        return flat.length() <= MAX_MESSAGE_CHARS ? flat : flat.substring(0, MAX_MESSAGE_CHARS) + "...";
    }

    /** The bucket and prefix as an {@code s3://} URL, and the endpoint; never the credentials. */
    @Override
    public String toString() {
        String root = prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);
        return "s3://" + bucket + "/" + root + " (" + scheme + "://" + authority + ", " + region + ")";
    }
}
