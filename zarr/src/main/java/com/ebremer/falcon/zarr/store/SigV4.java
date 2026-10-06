package com.ebremer.falcon.zarr.store;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for S3, header form: the {@code Authorization} header of one request. See
 * "Authenticating Requests: Using the Authorization Header (AWS Signature Version 4)" in the Amazon S3 API
 * reference. A pure function of its inputs: {@code javax.crypto} HMAC-SHA256 and {@code MessageDigest}
 * SHA-256, both in {@code java.base}.
 */
final class SigV4 {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    /** The SHA-256 of no bytes: the payload hash of a request without a body. */
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private SigV4() {
    }

    /** The {@code x-amz-date} form of an instant: {@code yyyyMMdd'T'HHmmss'Z'}, in UTC. */
    static String amzDate(Instant instant) {
        return AMZ_DATE.format(instant);
    }

    /**
     * The canonical query string: each name and value percent-encoded (RFC 3986, {@code '/'} encoded
     * too), the pairs sorted by encoded name and then value, joined by {@code '&'}. A parameter without a
     * value is {@code name=}. The same string is sent as the request's query, so what is signed is what is
     * sent.
     */
    static String canonicalQuery(List<Map.Entry<String, String>> params) {
        List<String[]> encoded = new ArrayList<>(params.size());
        for (Map.Entry<String, String> p : params) {
            encoded.add(new String[] {HttpIo.encode(p.getKey(), false), HttpIo.encode(p.getValue(), false)});
        }
        encoded.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        StringBuilder out = new StringBuilder();
        for (String[] p : encoded) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(p[0]).append('=').append(p[1]);
        }
        return out.toString();
    }

    /**
     * The canonical request.
     *
     * @param method         the HTTP method
     * @param canonicalUri   the request's path exactly as sent: already percent-encoded, segment by
     *                       segment, and for S3 not encoded twice
     * @param canonicalQuery the query exactly as sent ({@link #canonicalQuery}), or {@code ""}
     * @param headers        the signed headers, by lower-case name, sorted; values as sent
     * @param payloadHash    the hex SHA-256 of the body (the {@code x-amz-content-sha256} value)
     */
    static String canonicalRequest(String method, String canonicalUri, String canonicalQuery,
                                   SortedMap<String, String> headers, String payloadHash) {
        StringBuilder out = new StringBuilder(256);
        out.append(method).append('\n').append(canonicalUri).append('\n').append(canonicalQuery).append('\n');
        for (Map.Entry<String, String> h : headers.entrySet()) {
            out.append(h.getKey()).append(':').append(trimAll(h.getValue())).append('\n');
        }
        out.append('\n').append(signedHeaders(headers)).append('\n').append(payloadHash);
        return out.toString();
    }

    /** The signed header names, {@code ';'}-separated, in order. */
    static String signedHeaders(SortedMap<String, String> headers) {
        return String.join(";", headers.keySet());
    }

    /** The credential scope: {@code date/region/service/aws4_request}. */
    static String scope(String amzDate, String region, String service) {
        return amzDate.substring(0, 8) + "/" + region + "/" + service + "/aws4_request";
    }

    /** The string to sign. */
    static String stringToSign(String amzDate, String scope, String canonicalRequest) {
        return ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    }

    /** The signature: HMAC-SHA256 of the string to sign under the key derived from the secret, as hex. */
    static String signature(String secretKey, String amzDate, String region, String service, String stringToSign) {
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        return HexFormat.of().formatHex(hmac(key, stringToSign));
    }

    /**
     * The {@code Authorization} header's value for a request.
     *
     * @param headers the headers to sign, by lower-case name; they must include {@code host} and
     *                {@code x-amz-date}, the latter equal to {@code amzDate}
     */
    static String authorization(String method, String canonicalUri, String canonicalQuery,
                                SortedMap<String, String> headers, String payloadHash, String accessKeyId,
                                String secretKey, String region, String service, String amzDate) {
        String canonical = canonicalRequest(method, canonicalUri, canonicalQuery, headers, payloadHash);
        String scope = scope(amzDate, region, service);
        String signature = signature(secretKey, amzDate, region, service, stringToSign(amzDate, scope, canonical));
        return ALGORITHM + " Credential=" + accessKeyId + "/" + scope + ",SignedHeaders=" + signedHeaders(headers)
                + ",Signature=" + signature;
    }

    /** A header value as signed: leading and trailing spaces removed, and each run of spaces made one. */
    static String trimAll(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is missing from the JDK", e); // every JDK has it
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing from the JDK", e); // every JDK has it
        }
    }
}
