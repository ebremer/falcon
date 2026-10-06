package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link HttpStore} against a tiny in-process HTTP server (the JDK's
 * {@code com.sun.net.httpserver}, a test-only module) that serves a {@link MemoryStore}, honoring
 * {@code HEAD} and the {@code Range} header just as a static file server would. The server can be told to
 * misbehave as real servers do: no {@code Content-Length} on {@code HEAD}, {@code 403} for absent keys,
 * {@code Range} ignored, a chunked body, a range longer than asked for, redirects.
 */
class HttpStoreTest {

    private HttpServer server;
    private MemoryStore backing;
    private String base;

    // How the server behaves; each test sets what it needs.
    private volatile String mount = "";            // keys are served under "/" + mount
    private volatile String requiredQuery;          // if set, a request without exactly this raw query gets 400
    private volatile int absentStatus = 404;
    private volatile boolean headWithoutLength;
    private volatile boolean headNotAllowed;
    private volatile boolean ignoreRange;
    private volatile boolean chunked;               // send a 200's body without Content-Length
    private volatile int extraRangeBytes;           // send this many bytes more than a range asked for
    private volatile String redirectFrom;           // requests under "/" + redirectFrom ...
    private volatile String redirectTo;             // ... are redirected (302) to "/" + redirectTo
    private final List<String> requests = new CopyOnWriteArrayList<>(); // "METHOD rawPath?rawQuery Range"

    @BeforeEach
    void start() throws IOException {
        backing = new MemoryStore();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String range = exchange.getRequestHeaders().getFirst("Range");
        String method = exchange.getRequestMethod();
        requests.add(method + " " + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                + (range == null ? "" : " " + range));
        String path = uri.getPath().substring(1); // decoded, without the leading '/'
        if (redirectFrom != null && path.startsWith(redirectFrom)) {
            exchange.getResponseHeaders().set("Location",
                    "/" + redirectTo + uri.getRawPath().substring(1 + redirectFrom.length()));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        if (requiredQuery != null && !requiredQuery.equals(uri.getRawQuery())) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        if (method.equals("HEAD") && headNotAllowed) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        Optional<byte[]> value = path.startsWith(mount) && path.length() > mount.length()
                ? backing.get(path.substring(mount.length())) : Optional.empty();
        if (value.isEmpty()) {
            exchange.sendResponseHeaders(absentStatus, -1);
            exchange.close();
            return;
        }
        byte[] body = value.get();
        boolean head = method.equals("HEAD");
        if (range != null && range.startsWith("bytes=") && !ignoreRange) {
            String spec = range.substring(6);
            int from;
            int to;
            if (spec.startsWith("-")) { // a suffix: the last N bytes
                from = Math.max(0, body.length - Integer.parseInt(spec.substring(1)));
                to = body.length - 1;
            } else {
                String[] parts = spec.split("-");
                from = Integer.parseInt(parts[0]);
                to = parts.length > 1 && !parts[1].isEmpty()
                        ? (int) Math.min(Long.parseLong(parts[1]), Integer.MAX_VALUE) : body.length - 1;
            }
            if (from >= body.length) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + body.length);
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
            to = Math.min(to, body.length - 1);
            int len = to - from + 1;
            exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + body.length);
            int sent = Math.min(len + extraRangeBytes, body.length - from);
            exchange.sendResponseHeaders(206, head ? -1 : sent);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body, from, sent);
                }
            }
        } else {
            if (head && !headWithoutLength) {
                // A static file server reports the size on HEAD; the JDK server preserves a
                // manually-set Content-Length when the body length argument is -1.
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            }
            exchange.sendResponseHeaders(200, head ? -1 : chunked ? 0 : body.length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
        exchange.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void getHeadAndRange() {
        backing.set("hello", "hello world".getBytes());
        HttpStore store = HttpStore.openReadOnly(base);

        assertArrayEquals("hello world".getBytes(), store.get("hello").orElseThrow());
        assertTrue(store.get("missing").isEmpty());
        assertTrue(store.exists("hello"));
        assertFalse(store.exists("missing"));
        assertEquals(OptionalLong.of(11), store.size("hello"));
        assertTrue(store.size("missing").isEmpty());

        assertArrayEquals("hello".getBytes(), store.getRange("hello", 0, 5).orElseThrow());
        assertArrayEquals("world".getBytes(), store.getRange("hello", 6, 5).orElseThrow());
        assertArrayEquals(new byte[0], store.getRange("hello", 100, 5).orElseThrow());
        assertArrayEquals(new byte[0], store.getRange("hello", 3, 0).orElseThrow());
        assertTrue(store.getRange("missing", 0, 4).isEmpty());
    }

    @Test
    void listingIsUnsupported() {
        HttpStore store = HttpStore.openReadOnly(base);
        assertThrows(UnsupportedOperationException.class, store::list);
        assertThrows(UnsupportedOperationException.class, () -> store.listDir(""));
        assertThrows(UnsupportedOperationException.class, () -> store.set("k", new byte[1]));
    }

    @Test
    void readsAZarrArrayOverHttp() {
        // Write a real Zarr array into the backing store, then read it back through HttpStore.
        Zarr.createArray(backing, ArraySpec.builder(new long[] {12}, DataType.INT32)
                .chunkShape(4).gzip(5).build())
                .writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});

        ZarrArray array = Zarr.open(HttpStore.openReadOnly(base)).asArray();
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, array.readInts());
        // a selection touches only its chunks -- each fetched over HTTP by key
        assertArrayEquals(new int[] {5, 6, 7, 8},
                array.select(new long[] {5}, new long[] {4}).readInts());
    }

    @Test
    void readsASHardedArrayViaByteRangesOverHttp() {
        Zarr.createArray(backing, ArraySpec.builder(new long[] {16}, DataType.INT32)
                .chunkShape(8).sharding(4).build())
                .writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});

        ZarrArray array = Zarr.open(HttpStore.openReadOnly(base)).asArray();
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, array.readInts());
        assertArrayEquals(new int[] {2, 3, 4, 5},
                array.select(new long[] {2}, new long[] {4}).readInts());
    }

    @Test
    void navigatesToNamedChildOverHttp() {
        // A group child can be reached by name (exists checks), even though listing children cannot.
        var root = Zarr.createGroup(backing);
        root.createArray("temp", ArraySpec.builder(new long[] {4}, DataType.INT32).build())
                .writeInts(new int[] {9, 8, 7, 6});

        var group = Zarr.open(HttpStore.openReadOnly(base)).asGroup();
        assertArrayEquals(new int[] {9, 8, 7, 6}, group.array("temp").readInts());
        assertThrows(UnsupportedOperationException.class, group::childNames); // listing not available
    }

    // ---- I7: keys are percent-encoded ---------------------------------------------------------------

    /**
     * Keys went into the URL unencoded (P1 I7): "a b" and "50%" were not valid URIs, "a#b" lost "#b" as a
     * fragment, "q?x" became a query, and "café" went out as raw non-ASCII.
     */
    @Test
    void keysArePercentEncoded() {
        String[] keys = {"a b", "50%", "a#b", "q?x", "café", "dir/x y/z+1"};
        String[] paths = {"/a%20b", "/50%25", "/a%23b", "/q%3Fx", "/caf%C3%A9", "/dir/x%20y/z%2B1"};
        for (String key : keys) {
            backing.set(key, bytes("value of " + key));
        }
        HttpStore store = HttpStore.openReadOnly(base);
        for (int i = 0; i < keys.length; i++) {
            requests.clear();
            assertArrayEquals(bytes("value of " + keys[i]), store.get(keys[i]).orElseThrow(), keys[i]);
            assertEquals("GET " + paths[i], requests.get(0));
            assertEquals(OptionalLong.of(9 + keys[i].getBytes(StandardCharsets.UTF_8).length), store.size(keys[i]));
        }
        assertEquals("caf%C3%A9/a%20b/~-._", HttpStore.encodePath("café/a b/~-._"));
    }

    // ---- I7: a size without Content-Length ----------------------------------------------------------

    /** A HEAD without Content-Length reported size 0, and sharded reads then failed (P1 I7). */
    @Test
    void aHeadWithoutContentLengthStillGivesTheSize() {
        backing.set("hello", bytes("hello world"));
        Zarr.createGroup(backing).createArray("s", ArraySpec.builder(new long[] {16}, DataType.INT32)
                .chunkShape(8).sharding(4).build())
                .writeInts(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});
        headWithoutLength = true;
        HttpStore store = HttpStore.openReadOnly(base);
        assertEquals(OptionalLong.of(11), store.size("hello"));
        assertTrue(store.size("missing").isEmpty());
        assertArrayEquals(new int[] {2, 3, 4, 5},
                Zarr.openGroup(store).array("s").select(new long[] {2}, new long[] {4}).readInts());
    }

    @Test
    void aServerThatRefusesHeadIsAskedWithARangedGet() {
        backing.set("hello", bytes("hello world"));
        backing.set("empty", new byte[0]);
        headNotAllowed = true;
        HttpStore store = HttpStore.openReadOnly(base);
        assertEquals(OptionalLong.of(11), store.size("hello"));
        assertEquals(OptionalLong.of(0), store.size("empty"));
        assertTrue(store.exists("hello"));
        assertFalse(store.exists("missing"));
        assertTrue(store.size("missing").isEmpty());
    }

    // ---- I7: which statuses mean "absent" ----------------------------------------------------------

    /**
     * Only 404 meant "absent", so a public S3 or GCS bucket, which answers 403 for a key it does not hold,
     * made every sparse array throw (P1 I7). The default stays 404, since a 403 may be a real denial.
     */
    @Test
    void a403CountsAsAbsentOnlyWhenAsked() {
        Zarr.createArray(backing, ArraySpec.builder(new long[] {8}, DataType.INT32).chunkShape(2).build())
                .select(new long[] {2}, new long[] {2}).writeInts(new int[] {5, 6}); // only chunk c/1 stored
        absentStatus = 403;

        HttpStore strict = HttpStore.openReadOnly(base);
        assertThrows(ZarrException.class, () -> strict.get("missing"));
        assertThrows(ZarrException.class, () -> Zarr.openArray(strict).readInts());

        HttpStore lenient = HttpStore.builder(base).missingStatuses(404, 403).build();
        assertTrue(lenient.get("missing").isEmpty());
        assertTrue(lenient.getRange("missing", 0, 4).isEmpty());
        assertTrue(lenient.getSuffix("missing", 4).isEmpty());
        assertTrue(lenient.size("missing").isEmpty());
        assertFalse(lenient.exists("missing"));
        assertArrayEquals(new int[] {0, 0, 5, 6, 0, 0, 0, 0}, Zarr.openArray(lenient).readInts());
    }

    @Test
    void theBuilderChecksItsSettings() {
        assertThrows(IllegalArgumentException.class, () -> HttpStore.builder(base).missingStatuses());
        assertThrows(IllegalArgumentException.class, () -> HttpStore.builder(base).missingStatuses(404, 200));
        assertThrows(IllegalArgumentException.class, () -> HttpStore.builder(base).timeoutMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> HttpStore.openReadOnly("ftp://host/store"));
        assertThrows(IllegalArgumentException.class, () -> HttpStore.openReadOnly("not a url"));
        assertThrows(IllegalArgumentException.class, () -> HttpStore.openReadOnly("/relative/store"));
    }

    // ---- I7: a query in the base URL ---------------------------------------------------------------

    /** A presigned or SAS base URL had each key appended inside its query (P1 I7). */
    @Test
    void aQueryInTheBaseUrlFollowsEveryKey() {
        mount = "store/";
        requiredQuery = "sig=a%2Fb&se=2030-01-01";
        Zarr.createArray(backing, ArraySpec.builder(new long[] {8}, DataType.INT32).chunkShape(4).build())
                .writeInts(new int[] {1, 2, 3, 4, 5, 6, 7, 8});

        HttpStore store = HttpStore.openReadOnly(base + "/store/?sig=a%2Fb&se=2030-01-01#ignored");
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7, 8}, Zarr.openArray(store).readInts());
        assertTrue(requests.contains("GET /store/zarr.json?sig=a%2Fb&se=2030-01-01"), requests.toString());
        assertTrue(requests.contains("GET /store/c/1?sig=a%2Fb&se=2030-01-01"), requests.toString());
    }

    // ---- I7: redirects -----------------------------------------------------------------------------

    @Test
    void redirectsAreFollowedWithTheirRange() {
        mount = "new/";
        redirectFrom = "old/";
        redirectTo = "new/";
        backing.set("hello", bytes("hello world"));
        HttpStore store = HttpStore.openReadOnly(base + "/old");
        assertArrayEquals(bytes("hello world"), store.get("hello").orElseThrow());
        assertArrayEquals(bytes("world"), store.getRange("hello", 6, 5).orElseThrow());
        assertEquals(OptionalLong.of(11), store.size("hello"));
        assertTrue(store.get("missing").isEmpty());
        assertTrue(requests.contains("GET /new/hello bytes=6-10"), requests.toString());

        redirectFrom = "loop/";
        redirectTo = "loop/";
        HttpStore loop = HttpStore.openReadOnly(base + "/loop");
        ZarrException e = assertThrows(ZarrException.class, () -> loop.get("hello"));
        assertTrue(e.getMessage().contains("redirects"), e.getMessage());
    }

    /**
     * {@code HttpURLConnection} follows a redirect only within one protocol, so the common http-to-https
     * redirect failed with HTTP 301 (P1 I7). Redirects are followed by hand now, except down to http.
     */
    @Test
    void redirectsMayGoUpToHttpsButNeverDown() {
        URI http = URI.create("http://host/a");
        URI https = URI.create("https://host/a");
        assertTrue(HttpStore.redirectAllowed(http, https));
        assertTrue(HttpStore.redirectAllowed(http, http));
        assertTrue(HttpStore.redirectAllowed(https, https));
        assertFalse(HttpStore.redirectAllowed(https, http));
        assertFalse(HttpStore.redirectAllowed(http, URI.create("file:///etc/passwd")));
        assertFalse(HttpStore.redirectAllowed(http, URI.create("ftp://host/a")));
    }

    // ---- I7: bounded bodies ------------------------------------------------------------------------

    /** A server that sent more than a range asked for had its extra bytes returned as data (P1 I7). */
    @Test
    void aRangeLongerThanAskedForFails() {
        backing.set("hello", bytes("hello world"));
        extraRangeBytes = 3;
        HttpStore store = HttpStore.openReadOnly(base);
        assertThrows(ZarrException.class, () -> store.getRange("hello", 0, 5));
        assertThrows(ZarrException.class, () -> store.getRange("hello", 2, 5));
    }

    @Test
    void aWholeValueIsReadOnlyUpToTheCap() {
        backing.set("big", new byte[1000]);
        chunked = true; // no Content-Length, so the cap is met while reading
        HttpStore capped = HttpStore.builder(base).maxBodyBytes(999).build();
        ZarrException e = assertThrows(ZarrException.class, () -> capped.get("big"));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        assertEquals(1000, HttpStore.builder(base).maxBodyBytes(1000).build().get("big").orElseThrow().length);

        chunked = false; // with Content-Length, the cap is met before reading
        assertTrue(assertThrows(ZarrException.class, () -> capped.get("big")).getMessage().contains("too large"));
    }

    @Test
    void aServerIgnoringRangeIsReadOnlyUpToTheRange() {
        byte[] value = new byte[100_000];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (i * 7);
        }
        backing.set("v", value);
        ignoreRange = true;
        HttpStore store = HttpStore.openReadOnly(base);
        assertArrayEquals(Arrays.copyOfRange(value, 500, 520), store.getRange("v", 500, 20).orElseThrow());
        assertArrayEquals(Arrays.copyOfRange(value, 99_990, 100_000), store.getRange("v", 99_990, 50).orElseThrow());
        assertArrayEquals(new byte[0], store.getRange("v", 200_000, 5).orElseThrow());
        assertArrayEquals(Arrays.copyOfRange(value, 99_900, 100_000), store.getSuffix("v", 100).orElseThrow());
        chunked = true; // no Content-Length: the tail is kept while reading
        assertArrayEquals(Arrays.copyOfRange(value, 99_900, 100_000), store.getSuffix("v", 100).orElseThrow());
        assertArrayEquals(value, store.getSuffix("v", 1 << 20).orElseThrow());
    }

    // ---- getSuffix -----------------------------------------------------------------------------------

    @Test
    void aSuffixIsOneRequest() {
        backing.set("shard", bytes("data....index"));
        backing.set("empty", new byte[0]);
        HttpStore store = HttpStore.openReadOnly(base);
        requests.clear();
        assertArrayEquals(bytes("index"), store.getSuffix("shard", 5).orElseThrow());
        assertEquals(List.of("GET /shard bytes=-5"), requests);
        assertArrayEquals(bytes("data....index"), store.getSuffix("shard", 100).orElseThrow());
        assertArrayEquals(new byte[0], store.getSuffix("empty", 5).orElseThrow());
        assertArrayEquals(new byte[0], store.getSuffix("shard", 0).orElseThrow());
        assertTrue(store.getSuffix("missing", 5).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.getSuffix("shard", -1));
    }

    @Test
    void contentRangeIsParsed() {
        assertArrayEquals(new long[] {0, 0, 1234}, HttpStore.contentRange("bytes 0-0/1234"));
        assertArrayEquals(new long[] {-1, -1, 0}, HttpStore.contentRange("bytes */0"));
        assertArrayEquals(new long[] {5, 9, -1}, HttpStore.contentRange("Bytes 5-9/*"));
        assertNull(HttpStore.contentRange(null));
        assertNull(HttpStore.contentRange("items 0-1/2"));
        assertNull(HttpStore.contentRange("bytes x-y/z"));
    }
}
