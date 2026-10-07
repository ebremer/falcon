/**
 * Falcon S3 &mdash; Amazon S3 (and S3-compatible object storage) for Falcon's formats, over the
 * <a href="https://github.com/aws/aws-sdk-java-v2">AWS SDK for Java 2.x</a>:
 * <ul>
 *   <li>{@link com.ebremer.falcon.s3.S3Store}, a Zarr store that reads, lists, and writes the objects under a
 *       prefix of a bucket;</li>
 *   <li>{@link com.ebremer.falcon.s3.S3RangeReader}, an HDF5 range reader that reads an object a byte range
 *       at a time, so opening a large file reads only the metadata and chunks a read needs.</li>
 * </ul>
 *
 * <p>This is Falcon's one module with runtime dependencies beyond {@code java.base}: the SDK's S3 client and
 * its {@code url-connection-client} (over the JDK's {@code HttpURLConnection}), which bring
 * {@code reactive-streams}, {@code slf4j-api}, and {@code eventstream}. The format modules stay
 * dependency-free; each is an optional dependency here, so an application brings the one it uses. The
 * caller builds the {@code S3Client}, with its region, credentials, endpoint, and retries, and closes it.
 */
// "s3" ends in a digit, which javac's module lint flags; the name is the service's. The SDK's jars are
// automatic modules (they name themselves in their manifests), and the S3Client in this module's API needs
// its module read by callers too.
@SuppressWarnings({"module", "requires-transitive-automatic"})
module com.ebremer.falcon.s3 {
    requires transitive software.amazon.awssdk.services.s3;
    requires software.amazon.awssdk.core;
    requires software.amazon.awssdk.awscore;
    requires software.amazon.awssdk.auth;
    requires software.amazon.awssdk.regions;
    requires software.amazon.awssdk.identity.spi;
    requires software.amazon.awssdk.http;
    requires static transitive com.ebremer.falcon.zarr;
    requires static transitive com.ebremer.falcon.hdf5;

    exports com.ebremer.falcon.s3;
}
