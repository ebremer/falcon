package com.ebremer.falcon.s3;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;

/** What the store and the reader say about a client and its failures. */
final class S3Errors {

    private static final int MAX_MESSAGE_CHARS = 300;

    private S3Errors() {
    }

    /**
     * A failed request as S3 answered it: the status, S3's error code and message, and, for a bucket in another
     * region than the client's, which region that is.
     */
    static String describe(S3Client client, AwsServiceException e) {
        StringBuilder m = new StringBuilder("HTTP ").append(e.statusCode());
        AwsErrorDetails details = e.awsErrorDetails();
        String code = details == null ? null : details.errorCode();
        String message = details == null ? null : details.errorMessage();
        String bucketRegion = details == null || details.sdkHttpResponse() == null ? null
                : details.sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region").orElse(null);
        if (code != null && !code.isBlank()) {
            m.append(' ').append(shorten(code));
        }
        if (message != null && !message.isBlank()) {
            m.append(": ").append(shorten(message));
        }
        String region = region(client);
        if (bucketRegion != null && !bucketRegion.equals(region)) {
            m.append(" (the bucket is in region '").append(shorten(bucketRegion)).append("', not '").append(region)
                    .append("': build the S3Client with that region, or with crossRegionAccessEnabled(true))");
        } else if (e.statusCode() >= 300 && e.statusCode() < 400) {
            m.append(" (redirects are not followed; check the client's region and endpoint)");
        } else if (e.statusCode() == 403 && anonymous(client)) {
            m.append(" (the client is anonymous: give it credentials for a bucket that is not public)");
        }
        return m.toString();
    }

    /** Whether the client sends its requests unsigned. */
    static boolean anonymous(S3Client client) {
        return client.serviceClientConfiguration().credentialsProvider() instanceof AnonymousCredentialsProvider;
    }

    /** The client's region, and its endpoint if it was given one, for a description. */
    static String where(S3Client client) {
        return client.serviceClientConfiguration().endpointOverride()
                .map(endpoint -> endpoint + ", " + region(client))
                .orElseGet(() -> region(client));
    }

    private static String region(S3Client client) {
        var region = client.serviceClientConfiguration().region();
        return region == null ? "no region" : region.id();
    }

    private static String shorten(String s) {
        String flat = s.replaceAll("\\p{Cntrl}", " ");
        return flat.length() <= MAX_MESSAGE_CHARS ? flat : flat.substring(0, MAX_MESSAGE_CHARS) + "...";
    }
}
