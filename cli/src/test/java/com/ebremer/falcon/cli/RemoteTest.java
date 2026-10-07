package com.ebremer.falcon.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.s3.FakeS3;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sources and targets over HTTP and S3: a static file server, and the s3 module's FakeS3. */
class RemoteTest {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

    @TempDir
    Path dir;

    private HttpServer http;
    private FakeS3 s3;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private String h5;
    private String zarr;

    @BeforeEach
    void start() throws IOException {
        h5 = Samples.hdf5(dir.resolve("sample.h5")).toString();
        zarr = dir.resolve("sample.zarr").toString();
        Cli.run("convert", "-q", "--consolidate", h5, zarr).ok();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", this::serve);
        http.start();
        s3 = new FakeS3("bucket");
    }

    @AfterEach
    void stop() {
        http.stop(0);
        s3.close();
    }

    /** Serves the test's directory, in byte ranges when asked: GET and HEAD, 404 for anything absent. */
    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(auth == null ? "-" : auth);
            Path file = dir.resolve(exchange.getRequestURI().getPath().substring(1));
            if (!Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            String range = exchange.getRequestHeaders().getFirst("Range");
            boolean head = exchange.getRequestMethod().equals("HEAD");
            Matcher m = range == null ? null : RANGE.matcher(range);
            if (m != null && m.matches()) {
                int from = Integer.parseInt(m.group(1));
                int to = Math.min(bytes.length - 1, Integer.parseInt(m.group(2)));
                if (from >= bytes.length) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes */" + bytes.length);
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + bytes.length);
                exchange.sendResponseHeaders(206, head ? -1 : to - from + 1);
                if (!head) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes, from, to - from + 1);
                    }
                }
                return;
            }
            exchange.sendResponseHeaders(200, head ? -1 : bytes.length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/" + path;
    }

    @Test
    void hdf5AndZarrOverHttp() {
        assertEquals(Cli.ok("ls", "-r", h5), Cli.ok("ls", "-r", url("sample.h5")));
        assertEquals(Cli.ok("dump", h5, "cube", "-s", "1"), Cli.ok("dump", url("sample.h5"), "cube", "-s", "1"));
        assertEquals(Cli.ok("ls", "-r", zarr), Cli.ok("ls", "-r", url("sample.zarr"))); // consolidated: listed
        assertEquals(Cli.ok("dump", zarr, "table"), Cli.ok("dump", url("sample.zarr"), "table"));
        String converted = dir.resolve("from-http.zarr").toString();
        Cli.run("convert", "-q", url("sample.h5"), converted).ok();
        assertEquals(Cli.ok("dump", h5, "run/temperature"), Cli.ok("dump", converted, "run/temperature"));

        authorizations.clear();
        Cli.ok("info", "--header", "Authorization: Bearer token", url("sample.h5"));
        assertFalse(authorizations.isEmpty());
        assertTrue(authorizations.stream().allMatch("Bearer token"::equals), authorizations.toString());
        assertEquals(2, Cli.run("ls", "--header", "no colon", url("sample.h5")).status());
        assertEquals(1, Cli.run("ls", url("nothing.zarr")).status());
    }

    /** Puts every file of a local directory into the fake bucket under {@code prefix}. */
    private void upload(Path local, String prefix) throws IOException {
        try (Stream<Path> files = Files.walk(local)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String key = prefix + "/" + local.relativize(file).toString().replace('\\', '/');
                s3.objects.put(key, Files.readAllBytes(file));
            }
        }
    }

    private String[] s3(String... args) {
        String[] options = {"--s3-endpoint", s3.endpoint(), "--s3-region", "us-east-1"};
        String[] all = new String[args.length + options.length];
        all[0] = args[0];
        System.arraycopy(options, 0, all, 1, options.length);
        System.arraycopy(args, 1, all, 1 + options.length, args.length - 1);
        return all;
    }

    @Test
    void hdf5AndZarrInS3() throws IOException {
        s3.allowAnonymous = true;
        s3.objects.put("data/sample.h5", Files.readAllBytes(Path.of(h5)));
        upload(Path.of(zarr), "data/sample.zarr");
        assertEquals(Cli.ok("ls", "-r", h5), Cli.ok(s3("ls", "--s3-anonymous", "-r", "s3://bucket/data/sample.h5")));
        assertEquals(Cli.ok("info", h5, "table"), Cli.ok(s3("info", "--s3-anonymous", "s3://bucket/data/sample.h5", "table")));
        assertEquals(Cli.ok("ls", "-r", zarr), Cli.ok(s3("ls", "--s3-anonymous", "-r", "s3://bucket/data/sample.zarr")));
        assertEquals(Cli.ok("dump", zarr, "cube"), Cli.ok(s3("dump", "--s3-anonymous", "s3://bucket/data/sample.zarr/", "cube")));
        String local = dir.resolve("from-s3.zarr").toString();
        Cli.run(s3("convert", "-q", "--s3-anonymous", "s3://bucket/data/sample.h5", local)).ok();
        assertEquals(Cli.ok("dump", h5, "words"), Cli.ok("dump", local, "words"));

        s3.objects.put("data/notes.txt", "hello".getBytes());
        Cli.Result notHdf5 = Cli.run(s3("ls", "--s3-anonymous", "s3://bucket/data/notes.txt"));
        assertEquals(1, notHdf5.status());
        assertTrue(notHdf5.err().contains("is an object, but not an HDF5 file"), notHdf5.err());
    }

    @Test
    void writingToS3UsesTheSdksCredentials() {
        String id = System.getProperty("aws.accessKeyId");
        String secret = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", FakeS3.ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", FakeS3.SECRET);
        try {
            Cli.run(s3("copy", zarr, "s3://bucket/copies/one.zarr")).ok();
            assertTrue(s3.objects.containsKey("copies/one.zarr/zarr.json"), s3.objects.keySet().toString());
            assertEquals(Cli.ok("dump", zarr, "run/counts"), Cli.ok(s3("dump", "s3://bucket/copies/one.zarr", "run/counts")));
            Cli.run(s3("convert", "-q", "--zarr-format", "2", h5, "s3://bucket/copies/two.zarr")).ok();
            assertTrue(s3.objects.containsKey("copies/two.zarr/.zgroup"), s3.objects.keySet().toString());
            Cli.Result again = Cli.run(s3("copy", zarr, "s3://bucket/copies/one.zarr"));
            assertEquals(1, again.status());
            assertTrue(again.err().contains("--overwrite"), again.err());
            Cli.run(s3("consolidate", "s3://bucket/copies/two.zarr")).ok();
            assertTrue(s3.objects.containsKey("copies/two.zarr/.zmetadata"));
        } finally {
            restore("aws.accessKeyId", id);
            restore("aws.secretAccessKey", secret);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
