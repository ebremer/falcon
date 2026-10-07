package com.ebremer.falcon.s3;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * The SDK clients a test sends to a {@link FakeS3} with: its endpoint, addressed by path, retrying without
 * waiting; each is closed with this.
 */
final class Clients implements AutoCloseable {

    static final AwsCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(FakeS3.ACCESS_KEY, FakeS3.SECRET));

    private final FakeS3 s3;
    private final List<S3Client> clients = new ArrayList<>();

    Clients(FakeS3 s3) {
        this.s3 = s3;
    }

    /** A client with the fake's credentials and region, which tries each request 3 times. */
    S3Client signed() {
        return client(CREDENTIALS, "us-east-1", 3);
    }

    S3Client client(AwsCredentialsProvider credentials, String region, int maxAttempts) {
        return client(credentials, region, maxAttempts, s3.endpoint());
    }

    S3Client client(AwsCredentialsProvider credentials, String region, int maxAttempts, String endpoint) {
        return client(credentials, region, maxAttempts, endpoint, b -> { });
    }

    /** A client to the fake that {@code more} configures further. */
    S3Client client(AwsCredentialsProvider credentials, String region, int maxAttempts,
                    Consumer<S3ClientBuilder> more) {
        return client(credentials, region, maxAttempts, s3.endpoint(), more);
    }

    private S3Client client(AwsCredentialsProvider credentials, String region, int maxAttempts, String endpoint,
                            Consumer<S3ClientBuilder> more) {
        S3ClientBuilder builder = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.standardRetryStrategy().toBuilder()
                        .maxAttempts(maxAttempts)
                        .backoffStrategy(BackoffStrategy.retryImmediately())
                        .throttlingBackoffStrategy(BackoffStrategy.retryImmediately())
                        .build()));
        more.accept(builder);
        S3Client client = builder.build();
        clients.add(client);
        return client;
    }

    /**
     * The {@code Range} of each {@code GET} the fake received for {@code path} (its raw path, such as
     * {@code /bucket/a/b}), in order: {@code "bytes=..."}, or {@code ""} for a whole object.
     */
    List<String> gets(FakeS3 fake, String path) {
        List<String> ranges = new ArrayList<>();
        for (String request : fake.requests) {
            if (!request.startsWith("GET " + path)) {
                continue;
            }
            String rest = request.substring(4 + path.length());
            if (!(rest.isEmpty() || rest.startsWith("?") || rest.startsWith(" "))) {
                continue; // another object, whose key goes on
            }
            int space = rest.lastIndexOf(" bytes=");
            ranges.add(space < 0 ? "" : rest.substring(space + 1));
        }
        return ranges;
    }

    @Override
    public void close() {
        clients.forEach(S3Client::close);
    }
}
