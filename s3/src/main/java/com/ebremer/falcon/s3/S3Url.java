package com.ebremer.falcon.s3;

import java.util.Objects;

/**
 * An {@code s3://bucket/path} URL: the bucket, and the path after it (an object's key or a prefix), which
 * may be empty.
 */
record S3Url(String bucket, String path) {

    /**
     * The bucket and path of {@code url}.
     *
     * @throws IllegalArgumentException if it is not an {@code s3://} URL with a valid bucket name, or has a
     *                                  query or fragment
     */
    static S3Url parse(String url) {
        Objects.requireNonNull(url, "url");
        if (!url.regionMatches(true, 0, "s3://", 0, 5)) {
            throw new IllegalArgumentException("not an s3:// URL: " + url);
        }
        if (url.indexOf('?') >= 0 || url.indexOf('#') >= 0) {
            throw new IllegalArgumentException("an s3:// URL names a bucket and a path, no query or fragment: " + url);
        }
        String rest = url.substring(5);
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        return new S3Url(checkBucket(bucket), slash < 0 ? "" : rest.substring(slash + 1));
    }

    /**
     * {@code bucket}, if it may be a bucket's name: 1 to 255 letters, digits, {@code '.'}, {@code '-'}, or
     * {@code '_'} (Amazon's own rules are stricter today, but older buckets and other stores allow these).
     *
     * @throws IllegalArgumentException if it may not
     */
    static String checkBucket(String bucket) {
        Objects.requireNonNull(bucket, "bucket");
        if (bucket.isEmpty() || bucket.length() > 255 || !bucket.chars().allMatch(
                c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '.' || c == '-' || c == '_')) {
            throw new IllegalArgumentException("not a bucket name: '" + bucket + "'");
        }
        return bucket;
    }
}
