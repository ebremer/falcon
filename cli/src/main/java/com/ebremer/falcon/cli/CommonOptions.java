package com.ebremer.falcon.cli;

import com.beust.jcommander.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The options every command takes: help, error detail, and how to reach S3 and HTTP sources. */
final class CommonOptions {

    @Parameter(names = {"-h", "--help"}, help = true, order = 100, description = "Show this command's options")
    boolean help;

    @Parameter(names = {"-v", "--verbose"}, order = 101, description = "Show an error's stack trace")
    boolean verbose;

    @Parameter(names = "--s3-region", order = 110, description = "The AWS region of s3:// URLs (default: "
            + "AWS_REGION, then the profile's, then us-east-1; a bucket elsewhere is found anyway)")
    String s3Region;

    @Parameter(names = "--s3-profile", order = 111, description = "The AWS profile whose credentials and region to "
            + "use (default: AWS_PROFILE, else 'default')")
    String s3Profile;

    @Parameter(names = "--s3-endpoint", order = 112, description = "The endpoint of S3-compatible storage, such as "
            + "http://localhost:9000 for MinIO: buckets are then addressed by path")
    String s3Endpoint;

    @Parameter(names = {"--s3-anonymous", "--no-sign-request"}, order = 113, description = "Send S3 requests "
            + "unsigned, to read a public bucket without credentials")
    boolean s3Anonymous;

    @Parameter(names = "--header", order = 120, description = "An HTTP header for http(s):// sources, as 'Name: value' "
            + "(repeatable), such as 'Authorization: Bearer ...'")
    List<String> headers = new ArrayList<>();

    @Parameter(names = "--http-listing", order = 121, description = "List an http(s):// Zarr store's groups from "
            + "the directory pages a static file server makes")
    boolean httpListing;

    /**
     * {@return the {@code --header} options, by name}
     *
     * @throws UsageException if one is not {@code Name: value}
     */
    Map<String, String> httpHeaders() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String header : headers) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                throw new UsageException("--header takes 'Name: value', not '" + header + "'");
            }
            map.put(header.substring(0, colon).strip(), header.substring(colon + 1).strip());
        }
        return map;
    }
}
