package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * AWS Signature Version 4 against signatures computed elsewhere.
 *
 * <p>Two sources:
 * <ul>
 *   <li>the four worked examples in Amazon's S3 API reference, "Examples: Signature Calculations for the
 *       Authorization Header: Transferring Payload in a Single Chunk" (GET an object with a Range, PUT an
 *       object, GET ?lifecycle, and a listing), with their published signatures;</li>
 *   <li>botocore 1.43.108's {@code S3SigV4Auth}, the signer the AWS CLI and boto3 use, run once over the
 *       requests below with credentials {@code AKIDEXAMPLE} /
 *       {@code wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY}, session token
 *       {@code FQoGZXIvYXdzEXAMPLETOKEN//+=} where marked, and time 2026-01-02T03:04:05Z. The paths are
 *       botocore's own encoding of each key ({@code botocore.utils.percent_encode(key, safe="/~")}) and the
 *       queries its own canonical query string, so both encodings are checked too. botocore is a
 *       development-time oracle only, not something Falcon or its build uses.</li>
 * </ul>
 */
class SigV4Test {

    private static final String DOC_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String DOC_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String DOC_DATE = "20130524T000000Z";
    private static final String DOC_HOST = "examplebucket.s3.amazonaws.com";

    private static String doc(String method, String path, String query, TreeMap<String, String> headers,
                              String payload) {
        String auth = SigV4.authorization(method, path, query, headers, payload, DOC_KEY, DOC_SECRET,
                "us-east-1", "s3", DOC_DATE);
        return auth.substring(auth.indexOf("Signature=") + "Signature=".length());
    }

    private static TreeMap<String, String> docHeaders(String payload) {
        TreeMap<String, String> h = new TreeMap<>();
        h.put("host", DOC_HOST);
        h.put("x-amz-content-sha256", payload);
        h.put("x-amz-date", DOC_DATE);
        return h;
    }

    @Test
    void theWorkedExamplesOfTheS3ApiReference() {
        TreeMap<String, String> get = docHeaders(SigV4.EMPTY_SHA256);
        get.put("range", "bytes=0-9");
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                doc("GET", "/test.txt", "", get, SigV4.EMPTY_SHA256));

        String payload = SigV4.sha256Hex("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8));
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payload);
        TreeMap<String, String> put = docHeaders(payload);
        put.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        put.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        assertEquals("/test%24file.text", "/" + HttpIo.encodePath("test$file.text"));
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
                doc("PUT", "/test%24file.text", "", put, payload));

        String lifecycle = SigV4.canonicalQuery(List.of(Map.entry("lifecycle", "")));
        assertEquals("lifecycle=", lifecycle);
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
                doc("GET", "/", lifecycle, docHeaders(SigV4.EMPTY_SHA256), SigV4.EMPTY_SHA256));

        String list = SigV4.canonicalQuery(List.of(Map.entry("prefix", "J"), Map.entry("max-keys", "2")));
        assertEquals("max-keys=2&prefix=J", list); // sorted
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
                doc("GET", "/", list, docHeaders(SigV4.EMPTY_SHA256), SigV4.EMPTY_SHA256));
    }

    /** One request botocore signed. {@code key} is null for the listing, whose path is the bucket's. */
    private record Vector(String method, String host, String base, String key, String path,
                          List<Map.Entry<String, String>> params, String query, Map<String, String> headers,
                          String body, boolean token, String region, String signedHeaders, String signature) {
    }

    private static final List<Vector> BOTOCORE = List.of(
            new Vector("GET", "bucket.s3.us-west-2.amazonaws.com", "", "a b/c d", "/a%20b/c%20d", List.of(), "",
                    Map.of(), "", false, "us-west-2", "host;x-amz-content-sha256;x-amz-date",
                    "54d5633a4a84579ded676e99eeb9a2a8d6f99bf170f8108f1ccdfce7f54b12cb"),
            new Vector("GET", "bucket.s3.us-east-1.amazonaws.com", "", "caf\u00E9/\u4E2D\u6587/emoji\uD83D\uDE00",
                    "/caf%C3%A9/%E4%B8%AD%E6%96%87/emoji%F0%9F%98%80", List.of(), "", Map.of("range", "bytes=10-20"),
                    "", false, "us-east-1", "host;range;x-amz-content-sha256;x-amz-date",
                    "3cc0ddd2306a84e1de5a12febb32f4f84ed1313bba8c6a171c246d66660dd464"),
            new Vector("GET", "bucket.s3.us-east-1.amazonaws.com", "", "50%/x+y=z~w", "/50%25/x%2By%3Dz~w", List.of(),
                    "", Map.of(), "", true, "us-east-1", "host;x-amz-content-sha256;x-amz-date;x-amz-security-token",
                    "d53715dfab0e8c140c8442bc91a52d2491644e8fa59719ef7d4f8cd20ff5c05d"),
            new Vector("HEAD", "127.0.0.1:9000", "/bucket", "data.zarr/zarr.json", "/bucket/data.zarr/zarr.json",
                    List.of(), "", Map.of(), "", false, "us-east-1", "host;x-amz-content-sha256;x-amz-date",
                    "367ba8daff6f2fd71a4b4f0b78ace6d80566be9aba4a97f07d31298ceeb84784"),
            new Vector("GET", "127.0.0.1:9000", "/bucket", null, "/bucket",
                    List.of(Map.entry("list-type", "2"), Map.entry("prefix", "data/a b+c/"), Map.entry("delimiter", "/"),
                            Map.entry("encoding-type", "url"), Map.entry("continuation-token", "1/AbC+dEf==")),
                    "continuation-token=1%2FAbC%2BdEf%3D%3D&delimiter=%2F&encoding-type=url&list-type=2"
                            + "&prefix=data%2Fa%20b%2Bc%2F",
                    Map.of(), "", false, "us-east-1", "host;x-amz-content-sha256;x-amz-date",
                    "0b00cba75e68e0927338b7582cb3b5509efc78b0bf5720c1a1f4af097d3d1e36"),
            new Vector("PUT", "bucket.s3.eu-central-1.amazonaws.com", "", "dir/x y/z+1", "/dir/x%20y/z%2B1", List.of(),
                    "", Map.of(), "hello world", true, "eu-central-1",
                    "host;x-amz-content-sha256;x-amz-date;x-amz-security-token",
                    "9d32c58ea06465d967220eab67fe47267d91a6e56a176c73e6ec6d5ff6438f5c"),
            new Vector("DELETE", "storage.googleapis.com", "/my-bucket", "a=b&c", "/my-bucket/a%3Db%26c", List.of(), "",
                    Map.of(), "", false, "auto", "host;x-amz-content-sha256;x-amz-date",
                    "ec7b509f7d307e3da5b9ab78534d6632e3135678ad98ef26c225504a163ec4b5"),
            new Vector("GET", "bucket.s3.us-east-1.amazonaws.com", "", "c/0/1", "/c/0/1", List.of(), "",
                    Map.of("range", "bytes=-100"), "", false, "us-east-1", "host;range;x-amz-content-sha256;x-amz-date",
                    "2231cd1475b6078931edcbb3cc37b24e23cba3d077876c55fd2d1a16449a739a"),
            new Vector("GET", "bucket.s3.us-east-1.amazonaws.com", "", "k", "/k", List.of(), "",
                    Map.of("x-amz-meta-note", "  a   b  c "), "", false, "us-east-1",
                    "host;x-amz-content-sha256;x-amz-date;x-amz-meta-note",
                    "53030f5457219a9ff1ececcd543322cf23ada9dd50a589d5302dc8ef8deee5b1"));

    @Test
    void signaturesBotocoreComputed() {
        String date = SigV4.amzDate(Instant.parse("2026-01-02T03:04:05Z"));
        assertEquals("20260102T030405Z", date);
        for (Vector v : BOTOCORE) {
            String path = v.key() == null ? v.base() : v.base() + "/" + HttpIo.encodePath(v.key());
            assertEquals(v.path(), path, "path of " + v.key());
            String query = SigV4.canonicalQuery(v.params());
            assertEquals(v.query(), query, "query of " + v.path());
            String payload = SigV4.sha256Hex(v.body().getBytes(StandardCharsets.UTF_8));
            TreeMap<String, String> headers = new TreeMap<>(v.headers());
            headers.put("host", v.host());
            headers.put("x-amz-content-sha256", payload);
            headers.put("x-amz-date", date);
            if (v.token()) {
                headers.put("x-amz-security-token", "FQoGZXIvYXdzEXAMPLETOKEN//+=");
            }
            String auth = SigV4.authorization(v.method(), path, query, headers, payload, FakeS3.ACCESS_KEY,
                    FakeS3.SECRET, v.region(), "s3", date);
            assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260102/" + v.region()
                    + "/s3/aws4_request,"), auth);
            assertTrue(auth.contains(",SignedHeaders=" + v.signedHeaders() + ","), auth);
            assertTrue(auth.endsWith(",Signature=" + v.signature()), v.method() + " " + v.path() + ": " + auth);
        }
    }

    @Test
    void headerValuesAreTrimmedAsSigned() {
        assertEquals("a b c", SigV4.trimAll("  a   b  c "));
        assertEquals("x", SigV4.trimAll("x"));
        assertEquals(SigV4.EMPTY_SHA256, SigV4.sha256Hex(new byte[0]));
    }

    @Test
    void theQueryIsEncodedAndSortedByNameThenValue() {
        // "a" sorts before "a-b" (a name, not "name=value", is compared), and equal names by value
        assertEquals("a=2&a=3&a-b=1",
                SigV4.canonicalQuery(List.of(Map.entry("a-b", "1"), Map.entry("a", "3"), Map.entry("a", "2"))));
        assertEquals("k=%E2%82%AC%20%2B%2F%3F%26~", SigV4.canonicalQuery(List.of(Map.entry("k", "\u20AC +/?&~"))));
    }
}
