package com.ebremer.falcon.cli;

import java.net.URI;
import java.net.URISyntaxException;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsProfileRegionProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProviderChain;
import software.amazon.awssdk.regions.providers.SystemSettingsRegionProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/** Builds the S3 client of the {@code --s3-*} options. */
final class S3Clients {

    /** The region when none is configured: a bucket elsewhere is still found (cross-region access). */
    static final Region FALLBACK_REGION = Region.US_EAST_1;

    private S3Clients() {
    }

    /**
     * Builds the client the options describe, over the JDK's {@code HttpURLConnection}.
     * <ul>
     *   <li><b>Region:</b> {@code --s3-region}; else the SDK's system settings ({@code aws.region},
     *       {@code AWS_REGION}) or the profile's; else us-east-1. Unless an endpoint is given, the client
     *       may reach buckets in every region, so the region only decides where a request first goes.</li>
     *   <li><b>Credentials:</b> none with {@code --s3-anonymous}; the profile's with {@code --s3-profile};
     *       else the SDK's default chain (system settings, environment, profiles, SSO, container and
     *       instance roles).</li>
     *   <li><b>Endpoint:</b> {@code --s3-endpoint} for S3-compatible storage: buckets are addressed by path,
     *       and checksums are sent and checked only where S3's API requires them, as such stores expect.</li>
     * </ul>
     *
     * @param options the command's options
     * @return a client; the caller closes it
     * @throws UsageException if the endpoint is not an http or https URL
     */
    static S3Client build(CommonOptions options) {
        S3ClientBuilder builder = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        builder.region(region(options));
        if (options.s3Endpoint != null) {
            builder.endpointOverride(endpoint(options.s3Endpoint))
                    .forcePathStyle(true)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        } else {
            builder.crossRegionAccessEnabled(true);
        }
        if (options.s3Anonymous) {
            builder.credentialsProvider(AnonymousCredentialsProvider.create());
        } else if (options.s3Profile != null) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(options.s3Profile));
        }
        return builder.build();
    }

    private static Region region(CommonOptions options) {
        if (options.s3Region != null) {
            return Region.of(options.s3Region);
        }
        // The SDK's default region chain would also ask the EC2 instance metadata service, which takes
        // seconds to give up off EC2; system settings and the profile are enough, given cross-region access.
        AwsProfileRegionProvider profile = options.s3Profile == null ? new AwsProfileRegionProvider()
                : new AwsProfileRegionProvider(ProfileFile::defaultProfileFile, options.s3Profile);
        try {
            return new AwsRegionProviderChain(new SystemSettingsRegionProvider(), profile).getRegion();
        } catch (SdkException e) {
            return FALLBACK_REGION;
        }
    }

    private static URI endpoint(String endpoint) {
        try {
            URI uri = new URI(endpoint);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new UsageException("--s3-endpoint takes an http:// or https:// URL, not '" + endpoint + "'");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new UsageException("--s3-endpoint is not a URL: " + e.getMessage());
        }
    }
}
