package com.ebremer.falcon.s3;

import com.ebremer.falcon.hdf5.ExternalFileAccess;
import com.ebremer.falcon.hdf5.HdfUnsupportedException;
import com.ebremer.falcon.hdf5.RangeReader;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * An HDF5 file in Amazon S3, or S3-compatible object storage, read a byte range at a time through the AWS
 * SDK: {@link com.ebremer.falcon.hdf5.Hdf5File#open(RangeReader)} then fetches only the metadata and the
 * chunks a read needs, never the whole object. It is read-only; S3 cannot change part of an object, so an
 * HDF5 file is written locally and uploaded.
 *
 * <pre>{@code
 * try (S3Client s3 = S3Client.create();   // the SDK's default region and credentials
 *      Hdf5File h5 = Hdf5File.open(S3RangeReader.open(s3, "s3://my-bucket/data/scan.h5"))) {
 *     ...
 * }
 * }</pre>
 *
 * <p><b>The client.</b> The caller builds the {@link S3Client} and closes it after the file: its region,
 * credentials, endpoint, retries, and timeouts are the SDK's settings (see {@link S3Store}). A client with
 * {@code AnonymousCredentialsProvider} reads a public bucket.
 *
 * <p><b>Requests.</b> {@link #open} sends a {@code HeadObject}, for the object's size and ETag; each
 * {@link #read} is a {@code GetObject} with a {@code Range}, and with {@code If-Match} naming that ETag, so
 * an object replaced while the file is open fails the read instead of mixing two files' bytes. HDF5 reads
 * metadata in cached pages (see {@link com.ebremer.falcon.hdf5.OpenOptions}), so a file's first reads are a
 * few requests of 64&nbsp;KiB, and each chunk is one request.
 *
 * <p><b>Other files.</b> An HDF5 file read this way has no directory, so by default it opens no other file
 * (the files its external links, virtual datasets, external raw data, and references name).
 * {@link #siblings()} opens them from beside the object, as {@link ExternalFileAccess#sameDirectory()} does
 * for a local file:
 *
 * <pre>{@code
 * S3RangeReader reader = S3RangeReader.open(s3, "s3://my-bucket/data/vds.h5");
 * OpenOptions options = OpenOptions.defaults().externalFileAccess(ExternalFileAccess.resolvedBy(reader.siblings()));
 * try (Hdf5File h5 = Hdf5File.open(reader, options)) { ... }
 * }</pre>
 *
 * <p>It may be used from several threads at once, as the client may.
 */
public final class S3RangeReader implements RangeReader {

    private final S3Client client;
    private final String bucket;
    private final String key;
    private final long size;
    private final String eTag; // or null, if the store gave none: reads are then not pinned

    private S3RangeReader(S3Client client, String bucket, String key, long size, String eTag) {
        this.client = client;
        this.bucket = bucket;
        this.key = key;
        this.size = size;
        this.eTag = eTag;
    }

    /**
     * Opens the object {@code key} in {@code bucket}: one {@code HeadObject}, for its size and ETag.
     *
     * @param client the client to send requests with; the caller closes it, after the HDF5 file
     * @param bucket the bucket's name
     * @param key    the object's key, such as {@code data/scan.h5}
     * @return a reader of the object
     * @throws FileNotFoundException    if there is no such object (S3 answered {@code 404})
     * @throws IOException              if S3 refuses the request or cannot be reached
     * @throws IllegalArgumentException if the bucket's name is not a bucket name, or the key is empty
     */
    public static S3RangeReader open(S3Client client, String bucket, String key) throws IOException {
        Objects.requireNonNull(client, "client");
        S3Url.checkBucket(bucket);
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("an object's key must not be empty");
        }
        HeadObjectResponse head;
        try {
            head = client.headObject(r -> r.bucket(bucket).key(key));
        } catch (S3Exception e) {
            String where = url(bucket, key);
            if (e.statusCode() == 404) {
                FileNotFoundException missing = new FileNotFoundException("no object " + where);
                missing.initCause(e);
                throw missing;
            }
            throw new IOException("S3 HEAD " + where + ": " + S3Errors.describe(client, e), e);
        } catch (SdkException e) {
            throw new IOException("failed to HEAD " + url(bucket, key), e);
        }
        Long length = head.contentLength();
        if (length == null || length < 0) {
            throw new IOException("HEAD " + url(bucket, key) + " gave no Content-Length");
        }
        String eTag = head.eTag();
        return new S3RangeReader(client, bucket, key, length, eTag == null || eTag.isEmpty() ? null : eTag);
    }

    /**
     * Opens the object an {@code s3://bucket/key} URL names.
     *
     * @param client the client to send requests with; the caller closes it, after the HDF5 file
     * @param s3Url  the URL, such as {@code s3://my-bucket/data/scan.h5}
     * @return a reader of the object
     * @throws FileNotFoundException    if there is no such object
     * @throws IOException              if S3 refuses the request or cannot be reached
     * @throws IllegalArgumentException if the URL is not an {@code s3://} URL naming a bucket and a key, or
     *                                  has a query or fragment
     */
    public static S3RangeReader open(S3Client client, String s3Url) throws IOException {
        S3Url url = S3Url.parse(s3Url);
        if (url.path().isEmpty()) {
            throw new IllegalArgumentException("an s3:// URL of an HDF5 file names an object: " + s3Url);
        }
        return open(client, url.bucket(), url.path());
    }

    /** {@return the object's size in bytes, from when it was opened} */
    @Override
    public long size() {
        return size;
    }

    /**
     * Reads the bytes at {@code position} with one ranged {@code GetObject}, pinned to the ETag the object had
     * when it was opened.
     *
     * @param position    the offset of the first byte, from the start of the object
     * @param destination the buffer to fill, from its position to its limit
     * @throws EOFException if the object ends first
     * @throws IOException  if the object changed since it was opened, S3 refuses the request, or it cannot
     *                      be reached
     */
    @Override
    public void read(long position, ByteBuffer destination) throws IOException {
        int length = destination.remaining();
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative: " + position);
        }
        if (length == 0) {
            return;
        }
        if (position > size - length) {
            throw new EOFException(this + " ends at byte " + size + ", before " + length + " bytes at " + position);
        }
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(r -> {
            r.bucket(bucket).key(key).range("bytes=" + position + "-" + (position + length - 1));
            if (eTag != null) {
                r.ifMatch(eTag);
            }
        })) {
            if (in.response().sdkHttpResponse().statusCode() == 200 && !skip(in, position)) {
                throw new EOFException(this + " ended before byte " + position); // the Range was ignored
            }
            fill(in, destination);
            if (destination.hasRemaining()) {
                throw new EOFException(this + " sent " + (length - destination.remaining()) + " of the "
                        + length + " bytes at " + position);
            }
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                throw new IOException(this + " changed since it was opened (its ETag is no longer " + eTag + ")", e);
            }
            if (e.statusCode() == 416) {
                throw new EOFException(this + " ends before byte " + position);
            }
            throw new IOException("S3 GET a range of " + this + ": " + S3Errors.describe(client, e), e);
        } catch (SdkException e) {
            throw new IOException("failed to read a range of " + this, e);
        }
    }

    /**
     * Opens the other files this HDF5 file names from beside it in the bucket, for
     * {@link ExternalFileAccess#resolvedBy}: a relative name from the object's own "directory" (the key up
     * to its last {@code '/'}), and, as libhdf5 looks for a file moved with the files it names, an absolute
     * name by its file name alone there. A name that climbs out of that directory ({@code ../x.h5}) is
     * refused, as {@link ExternalFileAccess#sameDirectory()} refuses it; a name with no object is missing.
     *
     * @return a resolver of the file's other files, through the same client
     */
    public ExternalFileAccess.Resolver siblings() {
        String directory = key.substring(0, key.lastIndexOf('/') + 1);
        return (name, purpose) -> {
            String resolved = resolve(directory, name);
            if (resolved == null) {
                throw new HdfUnsupportedException("refusing to open '" + name + "' (a " + purpose
                        + "): it is outside s3://" + bucket + "/" + directory);
            }
            try {
                return open(client, bucket, resolved);
            } catch (FileNotFoundException e) {
                return null;
            }
        };
    }

    /**
     * The key {@code name} leads to from {@code directory}: a relative name's {@code '.'} and {@code ".."}
     * segments are resolved, an absolute one (a leading {@code '/'} or {@code '\'}, or a drive letter) is
     * taken by its last segment; null if it climbs out of the directory or names nothing.
     */
    static String resolve(String directory, String name) {
        String n = name.replace('\\', '/');
        boolean absolute = n.startsWith("/") || (n.length() >= 2 && n.charAt(1) == ':' && Character.isLetter(n.charAt(0)));
        if (absolute) {
            n = n.substring(n.lastIndexOf('/') + 1);
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : n.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        return segments.isEmpty() || n.endsWith("/") ? null : directory + String.join("/", segments);
    }

    /** Fills {@code destination} from {@code in}, until either ends. */
    private static void fill(InputStream in, ByteBuffer destination) throws IOException {
        if (destination.hasArray()) {
            int at = destination.arrayOffset() + destination.position();
            int n = in.readNBytes(destination.array(), at, destination.remaining());
            destination.position(destination.position() + n);
            return;
        }
        byte[] buffer = new byte[(int) Math.min(destination.remaining(), 64 << 10)];
        while (destination.hasRemaining()) {
            int n = in.read(buffer, 0, Math.min(buffer.length, destination.remaining()));
            if (n < 0) {
                return;
            }
            destination.put(buffer, 0, n);
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

    private static String url(String bucket, String key) {
        return "s3://" + bucket + "/" + key;
    }

    /** The object as an {@code s3://} URL, and the client's region and endpoint; never credentials. */
    @Override
    public String toString() {
        return url(bucket, key) + " (" + S3Errors.where(client) + ")";
    }
}
