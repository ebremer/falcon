package com.ebremer.falcon.zarr.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A small S3 on the JDK's test-only {@code com.sun.net.httpserver}: one bucket, addressed in the path
 * ({@code /bucket/key}), holding objects in memory. It does what {@link S3Store} needs and checks each
 * request the way S3 does:
 * <ul>
 *   <li>the signature is recomputed from the request as received (its raw path and query, its headers,
 *       its body's hash) with an implementation of its own, and a request whose path or query is not
 *       percent-encoded exactly as SigV4 requires is refused;</li>
 *   <li>{@code GET}, {@code HEAD}, {@code PUT}, {@code DELETE}, and {@code Range} (a span or a suffix);</li>
 *   <li>{@code ListObjectsV2}: a prefix, the delimiter, continuation tokens, pages of {@link #pageSize},
 *       keys in S3's order (UTF-8 bytes), and {@code encoding-type=url};</li>
 *   <li>S3's error documents, and a few ways to misbehave: {@code 403} for an absent key, a bucket in
 *       another region ({@code 301}), {@code 503 SlowDown}.</li>
 * </ul>
 */
final class FakeS3 implements AutoCloseable {

    static final String ACCESS_KEY = "AKIDEXAMPLE";
    static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    private static final Comparator<String> S3_ORDER = (a, b) -> Arrays.compareUnsigned(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    final String bucket;
    final ConcurrentSkipListMap<String, byte[]> objects = new ConcurrentSkipListMap<>(S3_ORDER);
    final List<String> requests = new CopyOnWriteArrayList<>();     // "METHOD rawPath?rawQuery [range]"
    final List<String> authorizations = new CopyOnWriteArrayList<>(); // the Authorization header, or "-"
    final List<String> refusals = new CopyOnWriteArrayList<>();     // why a signature was refused

    volatile String region = "us-east-1";
    volatile String sessionToken;          // if set, a signed request must carry and sign it
    volatile boolean allowAnonymous;       // reads without a signature are served
    volatile int absentStatus = 404;
    volatile int pageSize = 1000;
    volatile boolean encodeKeys = true;    // honour encoding-type=url
    volatile String movedTo;               // a region: every request answers 301 PermanentRedirect
    final AtomicInteger failNext = new AtomicInteger(); // answer this many requests 503 SlowDown

    private final HttpServer server;

    FakeS3(String bucket) throws IOException {
        this.bucket = bucket;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
    }

    /** The endpoint to give {@link S3Store.Builder#endpoint}. */
    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            URI uri = exchange.getRequestURI();
            String rawPath = uri.getRawPath();
            String rawQuery = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            String method = exchange.getRequestMethod();
            String range = exchange.getRequestHeaders().getFirst("Range");
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(method + " " + rawPath + (rawQuery.isEmpty() ? "" : "?" + rawQuery)
                    + (range == null ? "" : " " + range));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization == null ? "-" : authorization);

            if (movedTo != null) {
                exchange.getResponseHeaders().set("x-amz-bucket-region", movedTo);
                error(exchange, method, 301, "PermanentRedirect",
                        "The bucket you are attempting to access must be addressed using the specified endpoint.");
                return;
            }
            if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                error(exchange, method, 503, "SlowDown", "Please reduce your request rate.");
                return;
            }
            if (authorization == null) {
                if (!allowAnonymous || !(method.equals("GET") || method.equals("HEAD"))) {
                    error(exchange, method, 403, "AccessDenied", "Access Denied");
                    return;
                }
            } else {
                String refusal = verify(exchange, method, rawPath, rawQuery, authorization, body);
                if (refusal != null) {
                    refusals.add(refusal);
                    error(exchange, method, 403, "SignatureDoesNotMatch", refusal);
                    return;
                }
            }

            String bucketPath = "/" + bucket;
            if (rawPath.equals(bucketPath) || rawPath.equals(bucketPath + "/")) {
                if (method.equals("GET") && query(rawQuery).containsKey("list-type")) {
                    list(exchange, query(rawQuery));
                } else {
                    error(exchange, method, 400, "InvalidRequest", "only ListObjectsV2 is implemented");
                }
                return;
            }
            if (!rawPath.startsWith(bucketPath + "/")) {
                error(exchange, method, 404, "NoSuchBucket", "The specified bucket does not exist");
                return;
            }
            String key = decode(rawPath.substring(bucketPath.length() + 1), false);
            switch (method) {
                case "PUT" -> {
                    objects.put(key, body);
                    exchange.getResponseHeaders().set("ETag", "\"x\"");
                    exchange.sendResponseHeaders(200, -1);
                }
                case "DELETE" -> {
                    objects.remove(key);
                    exchange.sendResponseHeaders(204, -1);
                }
                case "GET", "HEAD" -> read(exchange, method, key, range);
                default -> error(exchange, method, 405, "MethodNotAllowed", "not allowed");
            }
        }
    }

    private void read(HttpExchange exchange, String method, String key, String range) throws IOException {
        byte[] value = objects.get(key);
        if (value == null) {
            error(exchange, method, absentStatus, absentStatus == 404 ? "NoSuchKey" : "AccessDenied",
                    absentStatus == 404 ? "The specified key does not exist." : "Access Denied");
            return;
        }
        boolean head = method.equals("HEAD");
        if (range != null) {
            String spec = range.substring("bytes=".length());
            long from;
            long to;
            if (spec.startsWith("-")) {
                long n = Long.parseLong(spec.substring(1));
                from = Math.max(0, value.length - n);
                to = value.length - 1;
            } else {
                String[] parts = spec.split("-", -1);
                from = Long.parseLong(parts[0]);
                to = parts[1].isEmpty() ? value.length - 1 : Math.min(Long.parseLong(parts[1]), value.length - 1);
            }
            if (from >= value.length) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + value.length);
                error(exchange, method, 416, "InvalidRange", "The requested range is not satisfiable");
                return;
            }
            int len = (int) (to - from + 1);
            exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + value.length);
            exchange.sendResponseHeaders(206, head ? -1 : len);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(value, (int) from, len);
                }
            }
            return;
        }
        if (head) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(value.length));
        }
        exchange.sendResponseHeaders(200, head ? -1 : value.length == 0 ? -1 : value.length);
        if (!head && value.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(value);
            }
        }
    }

    /** ListObjectsV2: keys and common prefixes merged in S3's order, a page at a time. */
    private void list(HttpExchange exchange, Map<String, String> q) throws IOException {
        String prefix = q.getOrDefault("prefix", "");
        String delimiter = q.get("delimiter");
        boolean url = encodeKeys && "url".equals(q.get("encoding-type"));
        TreeSet<String> entries = new TreeSet<>(S3_ORDER); // a key, or a common prefix (ending in the delimiter)
        TreeSet<String> commons = new TreeSet<>(S3_ORDER);
        for (String key : objects.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            int at = delimiter == null ? -1 : key.indexOf(delimiter, prefix.length());
            if (at >= 0) {
                String common = key.substring(0, at + delimiter.length());
                entries.add(common);
                commons.add(common);
            } else {
                entries.add(key);
            }
        }
        String after = q.containsKey("continuation-token")
                ? new String(Base64.getDecoder().decode(q.get("continuation-token")), StandardCharsets.UTF_8) : null;
        NavigableMap<String, Boolean> page = new TreeMap<>(S3_ORDER);
        String last = null;
        boolean truncated = false;
        for (String entry : after == null ? entries : entries.tailSet(after, false)) {
            if (page.size() == pageSize) {
                truncated = true;
                break;
            }
            page.put(entry, commons.contains(entry));
            last = entry;
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        xml.append("<Name>").append(bucket).append("</Name><Prefix>").append(out(prefix, url)).append("</Prefix>");
        xml.append("<KeyCount>").append(page.size()).append("</KeyCount><MaxKeys>").append(pageSize).append("</MaxKeys>");
        if (delimiter != null) {
            xml.append("<Delimiter>").append(out(delimiter, url)).append("</Delimiter>");
        }
        if (url) {
            xml.append("<EncodingType>url</EncodingType>");
        }
        xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        for (Map.Entry<String, Boolean> e : page.entrySet()) {
            if (e.getValue()) {
                xml.append("<CommonPrefixes><Prefix>").append(out(e.getKey(), url)).append("</Prefix></CommonPrefixes>");
            } else {
                xml.append("<Contents><Key>").append(out(e.getKey(), url)).append("</Key><Size>")
                        .append(objects.getOrDefault(e.getKey(), new byte[0]).length)
                        .append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
            }
        }
        if (truncated) {
            xml.append("<NextContinuationToken>")
                    .append(Base64.getEncoder().encodeToString(last.getBytes(StandardCharsets.UTF_8)))
                    .append("</NextContinuationToken>");
        }
        xml.append("</ListBucketResult>");
        byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream o = exchange.getResponseBody()) {
            o.write(bytes);
        }
    }

    /** A key in a listing: form-encoded for encoding-type=url (as S3 does, a space as '+'), XML-escaped. */
    private static String out(String s, boolean url) {
        String v = url ? URLEncoder.encode(s, StandardCharsets.UTF_8) : s;
        StringBuilder b = new StringBuilder();
        v.codePoints().forEach(c -> {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> {
                    if (c > 0xffff) {
                        b.append("&#x").append(Integer.toHexString(c)).append(';'); // a character reference
                    } else {
                        b.appendCodePoint(c);
                    }
                }
            }
        });
        return b.toString();
    }

    private void error(HttpExchange exchange, String method, int status, String code, String message)
            throws IOException {
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>" + code + "</Code><Message>"
                + out(message, false) + "</Message><RequestId>4442587FB7D0A2F9</RequestId></Error>")
                .getBytes(StandardCharsets.UTF_8);
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(body);
            }
        }
    }

    // ---- SigV4, as S3 checks it ---------------------------------------------------------------------

    /** Why the request's signature is wrong, or null if it is right. */
    private String verify(HttpExchange exchange, String method, String rawPath, String rawQuery,
                          String authorization, byte[] body) {
        String prefix = "AWS4-HMAC-SHA256 ";
        if (!authorization.startsWith(prefix)) {
            return "not SigV4: " + authorization;
        }
        Map<String, String> parts = new TreeMap<>();
        for (String part : authorization.substring(prefix.length()).split(",")) {
            String p = part.strip();
            int eq = p.indexOf('=');
            parts.put(p.substring(0, eq), p.substring(eq + 1));
        }
        String[] credential = parts.get("Credential").split("/");
        String amzDate = exchange.getRequestHeaders().getFirst("x-amz-date");
        if (credential.length != 5 || !credential[0].equals(ACCESS_KEY) || amzDate == null
                || !credential[1].equals(amzDate.substring(0, 8)) || !credential[2].equals(region)
                || !credential[3].equals("s3") || !credential[4].equals("aws4_request")) {
            return "bad credential scope " + parts.get("Credential") + " (region " + region + ", date " + amzDate + ")";
        }
        List<String> signed = List.of(parts.get("SignedHeaders").split(";"));
        for (String required : List.of("host", "x-amz-date", "x-amz-content-sha256")) {
            if (!signed.contains(required)) {
                return "header " + required + " is not signed";
            }
        }
        if (exchange.getRequestHeaders().containsKey("Range") && !signed.contains("range")) {
            return "the range is not signed";
        }
        String token = exchange.getRequestHeaders().getFirst("x-amz-security-token");
        if (sessionToken != null && (!sessionToken.equals(token) || !signed.contains("x-amz-security-token"))) {
            return "the session token is missing or unsigned";
        }
        String payload = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (!hex(sha256(body)).equals(payload)) {
            return "x-amz-content-sha256 is not the body's hash";
        }
        // S3 takes the path as sent; it must already be encoded exactly as SigV4 encodes it.
        String canonicalPath = encodeEach(decode(rawPath, false), true);
        if (!canonicalPath.equals(rawPath)) {
            return "path " + rawPath + " is not canonically encoded (" + canonicalPath + ")";
        }
        List<String[]> pairs = new ArrayList<>();
        if (!rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                String[] canonicalPair = {encodeEach(decode(name, false), false), encodeEach(decode(value, false), false)};
                if (!(canonicalPair[0] + "=" + canonicalPair[1]).equals(pair)) {
                    return "query parameter " + pair + " is not canonically encoded";
                }
                pairs.add(canonicalPair);
            }
        }
        pairs.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        List<String> joined = new ArrayList<>();
        for (String[] p : pairs) {
            joined.add(p[0] + "=" + p[1]);
        }
        StringBuilder canonical = new StringBuilder(method).append('\n').append(rawPath).append('\n')
                .append(String.join("&", joined)).append('\n');
        for (String name : signed) {
            List<String> values = exchange.getRequestHeaders().get(name);
            if (values == null) {
                return "signed header " + name + " was not sent";
            }
            List<String> trimmed = new ArrayList<>();
            for (String v : values) {
                trimmed.add(v.strip().replaceAll(" +", " "));
            }
            canonical.append(name).append(':').append(String.join(",", trimmed)).append('\n');
        }
        canonical.append('\n').append(String.join(";", signed)).append('\n').append(payload);
        String scope = amzDate.substring(0, 8) + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + hex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
        for (String s : new String[] {region, "s3", "aws4_request"}) {
            key = hmac(key, s);
        }
        String expected = hex(hmac(key, toSign));
        return expected.equals(parts.get("Signature")) ? null : "signature mismatch for\n" + canonical;
    }

    /** Percent-decodes {@code s} as UTF-8; {@code '+'} stays a plus unless {@code plusIsSpace}. */
    static String decode(String s, boolean plusIsSpace) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else if (c == '+' && plusIsSpace) {
                out.write(' ');
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (Character.isHighSurrogate(c)) {
                    b = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
                    i++;
                }
                out.writeBytes(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** RFC 3986 encoding: unreserved characters kept, {@code '/'} too when {@code path}. */
    private static String encodeEach(String s, boolean path) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if (Character.isLetterOrDigit(c) && c < 0x80 || c == '-' || c == '_' || c == '.' || c == '~'
                    || (path && c == '/')) {
                b.append((char) c);
            } else {
                b.append(String.format("%%%02X", c));
            }
        }
        return b.toString();
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> q = new TreeMap<>();
        if (!rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                q.put(decode(eq < 0 ? pair : pair.substring(0, eq), false), eq < 0 ? "" : decode(pair.substring(eq + 1), false));
            }
        }
        return q;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
