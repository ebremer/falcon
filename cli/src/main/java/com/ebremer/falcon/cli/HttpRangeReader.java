package com.ebremer.falcon.cli;

import com.ebremer.falcon.hdf5.RangeReader;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file at an {@code http(s)://} URL, read a byte range at a time ({@code Range} requests), for HDF5 files
 * on a web server. Redirects are followed, but not from https to http. Each read is pinned to the ETag the
 * server gave when the file was opened, if it gave a strong one ({@code If-Match}), so a file replaced
 * while it is read fails the read.
 */
final class HttpRangeReader implements RangeReader, AutoCloseable {

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client;
    private final URI uri;
    private final Map<String, String> headers;
    private final long size;
    private final String eTag; // a strong ETag, or null

    private HttpRangeReader(HttpClient client, URI uri, Map<String, String> headers, long size, String eTag) {
        this.client = client;
        this.uri = uri;
        this.headers = headers;
        this.size = size;
        this.eTag = eTag;
    }

    /**
     * Opens the file at {@code url}, learning its size from a request for its first byte.
     *
     * @param url     the file's URL
     * @param headers headers to send with every request, such as {@code Authorization}
     * @return a reader of the file
     * @throws FileNotFoundException if the server answers 404 or 410
     * @throws IOException           if the server does not serve byte ranges, or cannot be reached
     */
    static HttpRangeReader open(String url, Map<String, String> headers) throws IOException {
        URI uri = URI.create(url);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        try {
            HttpResponse<byte[]> response = send(client, request(uri, headers, null).header("Range", "bytes=0-0").build());
            int status = response.statusCode();
            long size;
            if (status == 206) {
                size = total(response).orElseThrow(() -> new IOException(url + " gave no size in its Content-Range"));
            } else if (status == 416) {
                size = 0; // an empty file has no first byte
            } else if (status == 404 || status == 410) {
                throw new FileNotFoundException("no file at " + url + " (HTTP " + status + ")");
            } else if (status == 200) {
                throw new IOException(url + " is served whole: the server does not serve byte ranges");
            } else {
                throw new IOException("HTTP " + status + " from " + url);
            }
            String eTag = response.headers().firstValue("ETag").filter(t -> t.startsWith("\"")).orElse(null);
            return new HttpRangeReader(client, uri, Map.copyOf(headers), size, eTag);
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void read(long position, ByteBuffer destination) throws IOException {
        int length = destination.remaining();
        if (length == 0) {
            return;
        }
        if (position < 0 || position > size - length) {
            throw new EOFException("bytes " + position + " to " + (position + length) + " are past the end of "
                    + uri + " (" + size + " bytes)");
        }
        HttpRequest.Builder request = request(uri, headers, eTag)
                .header("Range", "bytes=" + position + "-" + (position + length - 1));
        HttpResponse<byte[]> response = send(client, request.build());
        int status = response.statusCode();
        if (status == 412) {
            throw new IOException(uri + " changed since it was opened");
        }
        if (status != 206) {
            throw new IOException("HTTP " + status + " for bytes " + position + "-" + (position + length - 1)
                    + " of " + uri);
        }
        byte[] body = response.body();
        if (body.length != length) {
            throw new IOException(uri + " answered " + body.length + " bytes for a range of " + length);
        }
        destination.put(body);
    }

    private static HttpRequest.Builder request(URI uri, Map<String, String> headers, String eTag) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        headers.forEach(request::header);
        if (eTag != null) {
            request.header("If-Match", eTag);
        }
        return request;
    }

    private static HttpResponse<byte[]> send(HttpClient client, HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted reading " + request.uri());
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e); // a header the JDK refuses
        }
    }

    private static OptionalLong total(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Range").map(CONTENT_RANGE::matcher).filter(Matcher::matches)
                .filter(m -> !m.group(3).equals("*")).map(m -> OptionalLong.of(Long.parseLong(m.group(3))))
                .orElse(OptionalLong.empty());
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public String toString() {
        return uri.toString();
    }
}
