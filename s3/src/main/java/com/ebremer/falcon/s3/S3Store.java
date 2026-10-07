package com.ebremer.falcon.s3;

import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.store.Store;
import com.ebremer.falcon.zarr.store.StoreKeys;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A Zarr {@link Store} over Amazon S3, or S3-compatible object storage (Google Cloud Storage through its XML
 * API, MinIO, Cloudflare R2, ...), through the AWS SDK: each key is an object under the store's prefix in
 * one bucket.
 *
 * <pre>{@code
 * try (S3Client s3 = S3Client.create()) {   // the SDK's default region and credentials
 *     ZarrGroup root = Zarr.openGroup(S3Store.fromUrl(s3, "s3://my-bucket/data/image.zarr").build());
 *     ...
 * }
 * }</pre>
 *
 * <p><b>The client.</b> The caller builds the {@link S3Client} and closes it once the store is no longer
 * used; the store never closes it. Everything about how requests are sent is the client's: its region, its
 * credentials (by default the SDK's chain: environment variables, system properties, profiles, SSO, web
 * identity, and container and instance roles), its endpoint and addressing, its retries, and its timeouts.
 * A client with {@code AnonymousCredentialsProvider} reads a public bucket, and its store is read-only.
 *
 * <p><b>Requests.</b> {@link #get} is a {@code GetObject}, {@link #getRange} and {@link #getSuffix} a
 * {@code GetObject} with a {@code Range}, {@link #size} and {@link #exists} a {@code HeadObject},
 * {@link #set} a {@code PutObject}, and {@link #delete} a {@code DeleteObject}. The listings use
 * {@code ListObjectsV2}, every page of it, {@link #listDir} with the delimiter {@code '/'}, so a group's
 * children are listed without listing its arrays' chunks. A listing leaves out an object whose name is not
 * a valid store key, such as the "folder" objects ending in {@code '/'} that consoles create.
 *
 * <p><b>Failures.</b> A key is absent when S3 answers {@code 404}. A bucket that may be read but not listed
 * answers {@code 403} for an absent key; {@link Builder#missingStatuses} can count that as absent too, at
 * the cost of reading a denied key as missing (fill values) rather than failing. Any other failure is a
 * {@link ZarrException} with S3's status, error code, and message, and for a bucket in another region than
 * the client's, the region it is in.
 *
 * <p>It may be used from several threads at once, as the client may.
 */
public final class S3Store implements Store {

    /** The most bytes one value may have: Java's largest array. */
    private static final long MAX_VALUE_BYTES = Integer.MAX_VALUE - 8;

    private final S3Client client;
    private final String bucket;
    private final String prefix; // the store's root in the bucket: "" or "a/b/"
    private final boolean writable;
    private final Set<Integer> missingStatuses;

    private S3Store(Builder b) {
        this.client = b.client;
        this.bucket = b.bucket;
        this.prefix = b.prefix;
        this.writable = !b.readOnly && !S3Errors.anonymous(b.client);
        this.missingStatuses = Set.copyOf(b.missingStatuses);
    }

    /**
     * A builder for a store over {@code bucket}, rooted at the bucket's top until {@link Builder#prefix} says
     * otherwise.
     *
     * @param client the client to send requests with; the caller closes it
     * @param bucket the bucket's name
     * @return a builder: writable (unless the client is anonymous), with 404 as the only "absent" status
     * @throws IllegalArgumentException if the name is not a bucket name: 1&ndash;255 letters, digits,
     *                                  {@code '.'}, {@code '-'}, or {@code '_'}
     */
    public static Builder builder(S3Client client, String bucket) {
        return new Builder(client, S3Url.checkBucket(bucket));
    }

    /**
     * A builder for the bucket and prefix of an {@code s3://bucket/prefix} URL (the prefix may be absent).
     *
     * @param client the client to send requests with; the caller closes it
     * @param s3Url  the URL, such as {@code s3://my-bucket/data/image.zarr}
     * @return a builder for that bucket and prefix, with {@link #builder}'s defaults otherwise
     * @throws IllegalArgumentException if the URL is not an {@code s3://} URL naming a bucket, has a query
     *                                  or fragment, or its prefix is not a valid store key
     */
    public static Builder fromUrl(S3Client client, String s3Url) {
        S3Url url = S3Url.parse(s3Url);
        return new Builder(client, url.bucket()).prefix(url.path());
    }

    /** Settings for an {@link S3Store}. */
    public static final class Builder {

        private final S3Client client;
        private final String bucket;
        private String prefix = "";
        private boolean readOnly;
        private Set<Integer> missingStatuses = Set.of(404);

        private Builder(S3Client client, String bucket) {
            this.client = Objects.requireNonNull(client, "client");
            this.bucket = bucket;
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
         * Makes the store read-only, whatever the client's credentials allow.
         *
         * @return this builder
         */
        public Builder readOnly() {
            this.readOnly = true;
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
         * The store.
         *
         * @return a store with these settings: writable unless {@link #readOnly} or the client is anonymous
         */
        public S3Store build() {
            return new S3Store(this);
        }
    }

    // ---- reads --------------------------------------------------------------------------------------

    @Override
    public Optional<byte[]> get(String key) {
        StoreKeys.validate(key);
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(request(key).build())) {
            Long length = in.response().contentLength();
            if (length != null && length > MAX_VALUE_BYTES) {
                throw new ZarrException(object(key) + " in " + this + " holds " + length
                        + " bytes, more than one array can");
            }
            return Optional.of(length == null ? readAll(in, key) : readExactly(in, length.intValue(), key));
        } catch (S3Exception e) {
            if (missingStatuses.contains(e.statusCode())) {
                return Optional.empty();
            }
            throw failure(e, "GET " + object(key));
        } catch (SdkException | IOException e) {
            throw new ZarrException("failed to read " + object(key) + " from " + this, e);
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long offset, long length) {
        int len = checkedLength(offset, length);
        StoreKeys.validate(key);
        if (len == 0) {
            return exists(key) ? Optional.of(new byte[0]) : Optional.empty();
        }
        long last = offset > Long.MAX_VALUE - len ? Long.MAX_VALUE : offset + len - 1;
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(
                request(key).range("bytes=" + offset + "-" + last).build())) {
            if (in.response().sdkHttpResponse().statusCode() == 200) { // the Range was ignored: skip to it
                return Optional.of(skip(in, offset) ? in.readNBytes(len) : new byte[0]);
            }
            long start = rangeStart(in.response().contentRange());
            if (start >= 0 && start != offset) {
                throw new ZarrException("asked for " + object(key) + " from byte " + offset
                        + " but S3 sent from byte " + start);
            }
            return Optional.of(in.readNBytes(len));
        } catch (S3Exception e) {
            if (missingStatuses.contains(e.statusCode())) {
                return Optional.empty();
            }
            if (e.statusCode() == 416) { // past the end
                return Optional.of(new byte[0]);
            }
            throw failure(e, "GET a range of " + object(key));
        } catch (SdkException | IOException e) {
            throw new ZarrException("failed to read a range of " + object(key) + " from " + this, e);
        }
    }

    /** Reads the end of a value in one request, {@code Range: bytes=-length}. */
    @Override
    public Optional<byte[]> getSuffix(String key, long length) {
        int len = checkedLength(0, length);
        StoreKeys.validate(key);
        if (len == 0) {
            return exists(key) ? Optional.of(new byte[0]) : Optional.empty();
        }
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(request(key).range("bytes=-" + len).build())) {
            if (in.response().sdkHttpResponse().statusCode() == 200) { // the Range was ignored: keep only the end
                Long total = in.response().contentLength();
                if (total != null) {
                    return Optional.of(skip(in, Math.max(0, total - len)) ? in.readNBytes(len) : new byte[0]);
                }
                return Optional.of(tail(in, len));
            }
            return Optional.of(in.readNBytes(len));
        } catch (S3Exception e) {
            if (missingStatuses.contains(e.statusCode())) {
                return Optional.empty();
            }
            if (e.statusCode() == 416) { // an empty value has no end to send
                return Optional.of(new byte[0]);
            }
            throw failure(e, "GET the end of " + object(key));
        } catch (SdkException | IOException e) {
            throw new ZarrException("failed to read the end of " + object(key) + " from " + this, e);
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

    /** The object's size from a {@code HeadObject}, or -1 if it is absent. */
    private long head(String key) {
        try {
            Long length = client.headObject(r -> r.bucket(bucket).key(prefix + key)).contentLength();
            if (length == null) {
                throw new ZarrException("HEAD " + object(key) + " in " + this + " gave no Content-Length");
            }
            return length;
        } catch (S3Exception e) {
            if (missingStatuses.contains(e.statusCode())) {
                return -1;
            }
            throw failure(e, "HEAD " + object(key));
        } catch (SdkException e) {
            throw new ZarrException("failed to HEAD " + object(key) + " in " + this, e);
        }
    }

    private GetObjectRequest.Builder request(String key) {
        return GetObjectRequest.builder().bucket(bucket).key(prefix + key);
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
        String dir = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
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
     * Lists every object under {@code s3Prefix} with {@code ListObjectsV2}, page by page, passing each key,
     * and each common prefix when {@code delimited}, to the consumers.
     */
    private void listObjects(String s3Prefix, boolean delimited, Consumer<String> keys, Consumer<String> commonPrefixes) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket);
        if (!s3Prefix.isEmpty()) {
            request.prefix(s3Prefix);
        }
        if (delimited) {
            request.delimiter("/");
        }
        Set<String> seenTokens = new HashSet<>();
        try {
            for (ListObjectsV2Response page : client.listObjectsV2Paginator(request.build())) {
                for (S3Object object : page.contents()) {
                    keys.accept(object.key());
                }
                for (CommonPrefix common : page.commonPrefixes()) {
                    commonPrefixes.accept(common.prefix());
                }
                String token = page.nextContinuationToken();
                if (Boolean.TRUE.equals(page.isTruncated()) && token != null && !seenTokens.add(token)) {
                    throw new ZarrException("a listing of " + this + " repeats a continuation token");
                }
            }
        } catch (S3Exception e) {
            throw failure(e, "list the objects under '" + s3Prefix + "'");
        } catch (SdkException e) {
            throw new ZarrException("failed to list " + this, e);
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
        try {
            client.putObject(r -> r.bucket(bucket).key(prefix + key).contentType("application/octet-stream"),
                    RequestBody.fromBytes(value));
        } catch (S3Exception e) {
            throw failure(e, "PUT " + object(key));
        } catch (SdkException e) {
            throw new ZarrException("failed to write " + object(key) + " to " + this, e);
        }
    }

    @Override
    public void delete(String key) {
        StoreKeys.validate(key);
        checkWritable();
        try {
            client.deleteObject(r -> r.bucket(bucket).key(prefix + key));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) { // S3 itself answers 204 for an absent key; others may say 404
                throw failure(e, "DELETE " + object(key));
            }
        } catch (SdkException e) {
            throw new ZarrException("failed to delete " + object(key) + " from " + this, e);
        }
    }

    private void checkWritable() {
        if (!writable) {
            throw new UnsupportedOperationException("S3 store " + this + " is read-only"
                    + (S3Errors.anonymous(client) ? " (its client is anonymous)" : ""));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** The object for {@code key}, quoted, for messages: the prefixed key. */
    private String object(String key) {
        return "'" + prefix + key + "'";
    }

    private ZarrException failure(S3Exception e, String request) {
        return new ZarrException("S3 " + request + " in " + this + ": " + S3Errors.describe(client, e), e);
    }

    /** {@code length} as an array's length, checking a range's offset and length as {@link Store} says. */
    private static int checkedLength(long offset, long length) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative: " + length);
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("length exceeds Integer.MAX_VALUE: " + length);
        }
        return (int) length;
    }

    /** The first byte a {@code Content-Range: bytes first-last/size} header names, or -1 if it names none. */
    private static long rangeStart(String contentRange) {
        if (contentRange == null || !contentRange.startsWith("bytes ")) {
            return -1;
        }
        int dash = contentRange.indexOf('-', 6);
        try {
            return dash < 0 ? -1 : Long.parseLong(contentRange.substring(6, dash).strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private byte[] readExactly(InputStream in, int length, String key) throws IOException {
        byte[] value = in.readNBytes(length);
        if (value.length != length || in.read() >= 0) {
            throw new ZarrException(object(key) + " in " + this + " should hold " + length + " bytes, but "
                    + (value.length != length ? "only " + value.length + " came" : "more came"));
        }
        return value;
    }

    private byte[] readAll(InputStream in, String key) throws IOException {
        byte[] value = in.readNBytes((int) MAX_VALUE_BYTES);
        if (in.read() >= 0) {
            throw new ZarrException(object(key) + " in " + this + " holds more bytes than one array can");
        }
        return value;
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

    /** The last {@code n} bytes of a stream of unknown length. */
    private static byte[] tail(InputStream in, int n) throws IOException {
        byte[] ring = new byte[n];
        long total = 0;
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) > 0; ) {
            for (int i = 0; i < read; i++) {
                ring[(int) (total++ % n)] = buffer[i];
            }
        }
        int size = (int) Math.min(total, n);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = ring[(int) ((total - size + i) % n)];
        }
        return out;
    }

    /** The bucket and prefix as an {@code s3://} URL, and the client's region and endpoint; never credentials. */
    @Override
    public String toString() {
        String root = prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);
        return "s3://" + bucket + "/" + root + " (" + S3Errors.where(client) + ")";
    }
}
