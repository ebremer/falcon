package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link HttpStore} against a tiny in-process HTTP server (the JDK's
 * {@code com.sun.net.httpserver}, a test-only module) that serves a {@link MemoryStore}, honoring
 * {@code HEAD} and the {@code Range} header just as a static file server would.
 */
class HttpStoreTest {

    private HttpServer server;
    private MemoryStore backing;
    private String base;

    @BeforeEach
    void start() throws IOException {
        backing = new MemoryStore();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getPath().substring(1); // strip leading '/'
            Optional<byte[]> value = key.isEmpty() ? Optional.empty() : backing.get(safe(key));
            if (value.isEmpty()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = value.get();
            String range = exchange.getRequestHeaders().getFirst("Range");
            boolean head = exchange.getRequestMethod().equals("HEAD");
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-");
                int from = Integer.parseInt(parts[0]);
                int to = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : body.length - 1;
                if (from >= body.length) {
                    exchange.sendResponseHeaders(416, -1);
                    exchange.close();
                    return;
                }
                to = Math.min(to, body.length - 1);
                int len = to - from + 1;
                if (head) {
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(len));
                }
                exchange.sendResponseHeaders(206, head ? -1 : len);
                if (!head) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body, from, len);
                    }
                }
            } else {
                if (head) {
                    // A static file server reports the size on HEAD; the JDK server preserves a
                    // manually-set Content-Length when the body length argument is -1.
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
                }
                exchange.sendResponseHeaders(200, head ? -1 : body.length);
                if (!head) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Keys the store hands us are already validated; the server just needs the raw name. */
    private static String safe(String key) {
        return key;
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
}
