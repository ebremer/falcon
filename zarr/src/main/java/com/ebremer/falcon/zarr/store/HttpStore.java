package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A read-only {@link Store} over HTTP(S): each key is fetched from {@code base + "/" + key}. Built on
 * {@code java.net.HttpURLConnection} so the module stays {@code java.base}-only.
 *
 * <p>Partial reads use the HTTP {@code Range} header (byte-range requests), which is what makes a
 * remote sharded array efficient; {@link #size} uses a {@code HEAD}. Plain HTTP offers no directory
 * listing, so {@link #list}, {@link #listPrefix}, and {@link #listDir} are unsupported &mdash; a remote
 * array reads fine (its chunk keys are computed, not listed) and a group navigates to a <em>named</em>
 * child fine, but enumerating a group's children ({@code childNames}) does not work over HTTP.
 */
public final class HttpStore implements Store {

    private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;

    private final String base; // normalized to have no trailing slash
    private final int timeoutMillis;

    private HttpStore(String base, int timeoutMillis) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.timeoutMillis = timeoutMillis;
    }

    /** Opens a read-only store rooted at {@code baseUrl} (for example {@code https://host/data/store}). */
    public static HttpStore openReadOnly(String baseUrl) {
        return new HttpStore(baseUrl, DEFAULT_TIMEOUT_MILLIS);
    }

    /** Opens a read-only store rooted at {@code baseUrl} with the given connect/read timeout. */
    public static HttpStore openReadOnly(String baseUrl, int timeoutMillis) {
        return new HttpStore(baseUrl, timeoutMillis);
    }

    @Override
    public Optional<byte[]> get(String key) {
        StoreKeys.validate(key);
        HttpURLConnection connection = open(key, "GET", null);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return Optional.empty();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new ZarrException("HTTP " + code + " reading '" + key + "'");
            }
            return Optional.of(readBody(connection));
        } catch (IOException e) {
            throw new ZarrException("failed to read '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long offset, long length) {
        int len = MemoryStore.checkedLength(offset, length);
        StoreKeys.validate(key);
        String range = "bytes=" + offset + "-" + (offset + len - 1);
        HttpURLConnection connection = open(key, "GET", range);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return Optional.empty();
            }
            if (code == 416) { // requested range not satisfiable: past the end
                return Optional.of(new byte[0]);
            }
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                return Optional.of(readBody(connection));
            }
            if (code == HttpURLConnection.HTTP_OK) {
                // The server ignored the Range header and sent the whole entity; slice it ourselves.
                byte[] whole = readBody(connection);
                if (offset >= whole.length) {
                    return Optional.of(new byte[0]);
                }
                int from = (int) offset;
                int to = (int) Math.min((long) from + len, whole.length);
                byte[] slice = new byte[to - from];
                System.arraycopy(whole, from, slice, 0, slice.length);
                return Optional.of(slice);
            }
            throw new ZarrException("HTTP " + code + " reading range of '" + key + "'");
        } catch (IOException e) {
            throw new ZarrException("failed to read range of '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public boolean exists(String key) {
        return head(key).isPresent();
    }

    @Override
    public OptionalLong size(String key) {
        return head(key);
    }

    /** Returns the content length if the key exists (via {@code HEAD}), or empty on 404. */
    private OptionalLong head(String key) {
        StoreKeys.validate(key);
        HttpURLConnection connection = open(key, "HEAD", null);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return OptionalLong.empty();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new ZarrException("HTTP " + code + " for HEAD '" + key + "'");
            }
            long length = connection.getContentLengthLong();
            return length >= 0 ? OptionalLong.of(length) : OptionalLong.of(0);
        } catch (IOException e) {
            throw new ZarrException("failed to HEAD '" + key + "' over HTTP", e);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public List<String> list() {
        throw new UnsupportedOperationException("HTTP stores cannot list keys");
    }

    @Override
    public List<String> listPrefix(String prefix) {
        throw new UnsupportedOperationException("HTTP stores cannot list keys");
    }

    @Override
    public List<String> listDir(String prefix) {
        throw new UnsupportedOperationException("HTTP stores cannot list keys");
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    @Override
    public void set(String key, byte[] value) {
        throw new UnsupportedOperationException("HTTP store is read-only");
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException("HTTP store is read-only");
    }

    private HttpURLConnection open(String key, String method, String range) {
        try {
            URL url = new URI(base + "/" + key).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(true);
            if (range != null) {
                connection.setRequestProperty("Range", range);
            }
            return connection;
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            throw new ZarrException("bad HTTP request for key '" + key + "'", e);
        }
    }

    private static byte[] readBody(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
