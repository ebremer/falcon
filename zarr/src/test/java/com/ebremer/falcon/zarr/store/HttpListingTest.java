package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ArraySpec;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrException;
import com.ebremer.falcon.zarr.ZarrGroup;
import com.ebremer.falcon.zarr.ZarrNode;
import com.ebremer.falcon.zarr.datatype.DataType;
import com.ebremer.falcon.zarr.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link HttpStore}'s listing from directory listing pages (P2 F13), as fsspec's HTTP filesystem lists:
 * first {@link DirectoryListing} on the pages real servers make, then a store over an in-process server
 * (the JDK's {@code com.sun.net.httpserver}, a test-only module) that serves a {@link MemoryStore}'s keys
 * and, for a URL ending in {@code '/'}, a listing page in the style of Python's {@code http.server}, nginx's
 * {@code autoindex}, or Apache's {@code mod_autoindex}.
 */
class HttpListingTest {

    // ---- pages as servers make them -----------------------------------------------------------------

    /** Python 3.12's {@code http.server} (SimpleHTTPRequestHandler.list_directory). */
    private static final String PYTHON = """
            <!DOCTYPE HTML>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Directory listing for /data/store/</title>
            </head>
            <body>
            <h1>Directory listing for /data/store/</h1>
            <hr>
            <ul>
            <li><a href=".zattrs">.zattrs</a></li>
            <li><a href="a%20b/">a b/</a></li>
            <li><a href="gr%C3%B6%C3%9Fe/">größe/</a></li>
            <li><a href="x%26y">x&amp;y</a></li>
            <li><a href="zarr.json">zarr.json</a></li>
            </ul>
            <hr>
            </body>
            </html>
            """;

    /** nginx's {@code autoindex}: a parent link, and names padded into columns. */
    private static final String NGINX = """
            <html>
            <head><title>Index of /data/store/</title></head>
            <body>
            <h1>Index of /data/store/</h1><hr><pre><a href="../">../</a>
            <a href="a%20b/">a b/</a>                                               06-Oct-2026 10:00                   -
            <a href="c/">c/</a>                                                 06-Oct-2026 10:00                   -
            <a href="zarr.json">zarr.json</a>                                          06-Oct-2026 10:00                 123
            </pre><hr></body>
            </html>
            """;

    /** Apache's {@code mod_autoindex}: sort links in the header, an absolute parent link, icons. */
    private static final String APACHE = """
            <!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 3.2 Final//EN">
            <html>
             <head>
              <title>Index of /data/store</title>
             </head>
             <body>
            <h1>Index of /data/store</h1>
              <table>
               <tr><th valign="top"><img src="/icons/blank.gif" alt="[ICO]"></th><th><a href="?C=N;O=D">Name</a></th><th><a href="?C=M;O=A">Last modified</a></th><th><a href="?C=S;O=A">Size</a></th></tr>
               <tr><th colspan="5"><hr></th></tr>
            <tr><td valign="top"><img src="/icons/back.gif" alt="[PARENTDIR]"></td><td><a href="/data/">Parent Directory</a></td><td>&nbsp;</td><td align="right">  - </td></tr>
            <tr><td valign="top"><img src="/icons/folder.gif" alt="[DIR]"></td><td><a href="c/">c/</a></td><td align="right">2026-10-06 10:00  </td><td align="right">  - </td></tr>
            <tr><td valign="top"><img src="/icons/text.gif" alt="[TXT]"></td><td><a href="zarr.json">zarr.json</a></td><td align="right">2026-10-06 10:00  </td><td align="right">123 </td></tr>
               <tr><th colspan="5"><hr></th></tr>
            </table>
            <address>Apache/2.4.58 (Ubuntu) Server at example.org Port 443</address>
            </body></html>
            """;

    private static final URI PAGE = URI.create("https://example.org/data/store/");

    @Test
    void readsPythonsListing() {
        assertEquals(Set.of(".zattrs", "a b/", "größe/", "x&y", "zarr.json"),
                DirectoryListing.names(PYTHON, PAGE, "", null));
    }

    @Test
    void readsNginxsListingWithoutItsParentLink() {
        assertEquals(Set.of("a b/", "c/", "zarr.json"), DirectoryListing.names(NGINX, PAGE, "", null));
    }

    @Test
    void readsApachesListingWithoutItsSortOrParentLinks() {
        assertEquals(Set.of("c/", "zarr.json"), DirectoryListing.names(APACHE, PAGE, "", null));
    }

    @Test
    void namesAreTheStorePrefixPlusTheLinkedName() {
        assertEquals(Set.of("g/a/c/", "g/a/zarr.json"),
                DirectoryListing.names(APACHE, URI.create("https://example.org/data/store/g/a/"), "g/a/", null));
    }

    /** Links that are not a name directly below the listed directory are not entries. */
    @Test
    void onlyLinksToANameDirectlyBelowCount() {
        String html = """
                <a href="plain">x</a> <A HREF='quoted-single'>x</A> <a class="f" href=unquoted>x</a>
                <a href="https://example.org/data/store/absolute">same origin</a>
                <a href="https://other.example.org/data/store/elsewhere">other host</a>
                <a href="http://example.org/data/store/scheme">other scheme</a>
                <a href="#top">fragment</a> <a href="name#frag">fragment</a> <a href="name?x=1">query</a>
                <a href="../">parent</a> <a href="./">self</a> <a href=".">self</a> <a href="/">root</a>
                <a href="/data/other/sibling">a sibling directory's file</a> <a href="../other/">a sibling</a>
                <a href="deep/er">further down</a> <a href="deep/er/">further down</a>
                <a href="a%2Fb">an escaped slash</a> <a href="%FF">not UTF-8</a>
                <a href="raw ü name">raw, unescaped</a> <a href="pct%zz">a stray percent</a>
                <!-- <a href="commented">x</a> -->
                <a name="anchor-only">no href</a> <link href="style.css">
                <a href="sub/../undone">dot segments</a> <a href="&#x7a;arr.json">a reference</a>
                """;
        assertEquals(Set.of("plain", "quoted-single", "unquoted", "absolute", "raw ü name", "pct%zz", "undone",
                "zarr.json"), DirectoryListing.names(html, PAGE, "", null));
    }

    @Test
    void aLinkMayCarryTheStoresQuery() {
        String html = "<a href=\"k?token=abc\">k</a> <a href=\"j?token=other\">j</a>";
        assertEquals(Set.of("k"), DirectoryListing.names(html, URI.create("https://example.org/s/?token=abc"),
                "", "token=abc"));
    }

    @Test
    void htmlCharacterReferences() {
        assertEquals("a&b'c\"<>é", DirectoryListing.unescapeHtml("a&amp;b&#39;c&quot;&lt;&gt;&#xe9;"));
        assertEquals("&bogus; &#xFFFFFFF;", DirectoryListing.unescapeHtml("&bogus; &#xFFFFFFF;"));
    }

    @Test
    void contentTypes() {
        assertTrue(DirectoryListing.isHtml("text/html; charset=utf-8"));
        assertTrue(DirectoryListing.isHtml("TEXT/HTML"));
        assertTrue(DirectoryListing.isHtml("application/xhtml+xml"));
        assertTrue(!DirectoryListing.isHtml("application/json"));
        assertEquals(StandardCharsets.ISO_8859_1, DirectoryListing.charset("text/html; charset=\"ISO-8859-1\""));
        assertEquals(StandardCharsets.UTF_8, DirectoryListing.charset("text/html; charset=nonsense"));
        assertEquals(StandardCharsets.UTF_8, DirectoryListing.charset(null));
    }

    // ---- a store over a server that lists ----------------------------------------------------------

    enum Style { PYTHON, NGINX, APACHE }

    private HttpServer server;
    private MemoryStore backing;
    private String base;
    private volatile Style style = Style.PYTHON;
    private volatile String mount = "data/store/";  // keys are served under "/" + mount
    private volatile String moved;                  // a listing under "/" + mount + moved redirects to "/moved/..."
    private volatile String contentType = "text/html; charset=utf-8";
    private volatile boolean loop;                  // every directory lists a child "loop/"
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        backing = new MemoryStore();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/data/store";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        requests.add(exchange.getRequestMethod() + " " + uri.getRawPath());
        String path = uri.getPath().substring(1); // decoded, without the leading '/'
        if (moved != null && path.startsWith(mount + moved) && path.endsWith("/")) {
            exchange.getResponseHeaders().set("Location", "/moved/" + uri.getRawPath().substring(1 + mount.length()));
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
            return;
        }
        String keyPath;
        if (path.startsWith("moved/")) {
            keyPath = path.substring("moved/".length());
        } else if (path.startsWith(mount) || (path + "/").equals(mount)) {
            keyPath = path.length() > mount.length() ? path.substring(mount.length()) : "";
        } else {
            send(exchange, 404, "text/plain", "not found");
            return;
        }
        if (keyPath.isEmpty() || keyPath.endsWith("/")) {
            List<String> names = backing.listDir(keyPath);
            if (names.isEmpty() && !keyPath.isEmpty() && !loop) {
                send(exchange, 404, "text/html", "<html><body><a href=\"phantom\">404</a></body></html>");
                return;
            }
            send(exchange, 200, contentType, page(uri.getRawPath(), keyPath, names));
            return;
        }
        Optional<byte[]> value = backing.get(keyPath);
        if (value.isEmpty()) {
            send(exchange, 404, "text/plain", "not found");
            return;
        }
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(value.get().length));
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, value.get().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(value.get());
            }
        }
        exchange.close();
    }

    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, b.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(b);
        }
        exchange.close();
    }

    /** The listing page for {@code dir}, in the server's style, of the names {@code names} (full keys). */
    private String page(String rawPath, String dir, List<String> names) {
        StringBuilder b = new StringBuilder();
        List<String> entries = new java.util.ArrayList<>();
        for (String name : names) {
            entries.add(name.substring(dir.length()));
        }
        if (loop) {
            entries.add("loop/");
        }
        switch (style) {
            case PYTHON -> {
                b.append("<!DOCTYPE HTML>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Directory listing for ")
                        .append(rawPath).append("</title></head><body><ul>\n");
                for (String e : entries) {
                    b.append("<li><a href=\"").append(quote(e)).append("\">").append(html(e)).append("</a></li>\n");
                }
                b.append("</ul><hr></body></html>\n");
            }
            case NGINX -> {
                b.append("<html><head><title>Index of ").append(rawPath).append("</title></head><body><pre>")
                        .append("<a href=\"../\">../</a>\n");
                for (String e : entries) { // nginx leaves non-ASCII bytes in a link as they are
                    b.append("<a href=\"").append(e.replace("%", "%25").replace(" ", "%20").replace("&", "&amp;"))
                            .append("\">").append(html(e)).append("</a>          06-Oct-2026 10:00     -\n");
                }
                b.append("</pre><hr></body></html>\n");
            }
            case APACHE -> {
                b.append("<html><body><table><tr><th><a href=\"?C=N;O=D\">Name</a></th></tr>\n")
                        .append("<tr><td><a href=\"").append(parent(rawPath)).append("\">Parent Directory</a></td></tr>\n");
                for (String e : entries) {
                    b.append("<tr><td><a href=\"").append(html(quote(e))).append("\">").append(html(e))
                            .append("</a></td></tr>\n");
                }
                b.append("</table></body></html>\n");
            }
            default -> throw new AssertionError();
        }
        return b.toString();
    }

    private static String quote(String name) {
        boolean dir = name.endsWith("/");
        String n = dir ? name.substring(0, name.length() - 1) : name;
        return URLEncoder.encode(n, StandardCharsets.UTF_8).replace("+", "%20") + (dir ? "/" : "");
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String parent(String rawPath) {
        String p = rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
        return p.substring(0, p.lastIndexOf('/') + 1);
    }

    private HttpStore listing() {
        return HttpStore.builder(base).directoryListing(true).build();
    }

    private void hierarchy() {
        ZarrGroup root = Zarr.createGroup(backing, Json.parse("{\"title\":\"listed\"}").asObject());
        root.createArray("a", ArraySpec.builder(new long[] {6}, DataType.INT32).chunkShape(2).build())
                .writeInts(new int[] {1, 2, 3, 4, 5, 6});
        ZarrGroup g = root.createGroup("g");
        g.createArray("größe & más", ArraySpec.builder(new long[] {2}, DataType.UINT8).build())
                .writeInts(new int[] {7, 8});
        g.createGroup("sub");
    }

    @ParameterizedTest
    @EnumSource(Style.class)
    void listsAHierarchyInEachServersStyle(Style s) {
        style = s;
        hierarchy();
        HttpStore store = listing();
        assertEquals(backing.list(), store.list());
        assertEquals(backing.listDir(""), store.listDir(""));
        assertEquals(backing.listDir("g/"), store.listDir("g"));
        assertEquals(backing.listPrefix("g/gr"), store.listPrefix("g/gr"));
        assertEquals(backing.listPrefix("a/c/"), store.listPrefix("a/c/"));
        assertEquals(List.of("a/c/0", "a/c/1", "a/c/2", "a/zarr.json"), store.listPrefix("a"));
        // the Zarr API lists children with it, no consolidated metadata needed
        ZarrGroup root = Zarr.openGroup(store);
        assertEquals(List.of("a", "g"), root.childNames());
        assertEquals(List.of("größe & más", "sub"), root.group("g").childNames());
        assertEquals(List.of("a", "g", "g/größe & más", "g/sub"), paths(root, ""));
        assertArrayEquals(new int[] {7, 8}, root.group("g").array("größe & más").readInts());
    }

    private static List<String> paths(ZarrGroup group, String prefix) {
        List<String> out = new java.util.ArrayList<>();
        for (ZarrNode node : group.children()) {
            out.add(prefix + node.name());
            if (node instanceof ZarrGroup child) {
                out.addAll(paths(child, prefix + node.name() + "/"));
            }
        }
        return out;
    }

    @Test
    void listingIsOffByDefault() {
        hierarchy();
        HttpStore store = HttpStore.openReadOnly(base);
        assertThrows(UnsupportedOperationException.class, store::list);
        assertThrows(UnsupportedOperationException.class, () -> store.listDir(""));
        assertThrows(UnsupportedOperationException.class, () -> store.listPrefix("a/"));
        assertThrows(UnsupportedOperationException.class, () -> Zarr.openGroup(store).childNames());
        assertTrue(requests.stream().noneMatch(r -> r.endsWith("/")), "no listing was requested");
    }

    @Test
    void aDirectoryTheServerDoesNotHaveListsNothing() {
        hierarchy();
        HttpStore store = listing();
        assertEquals(List.of(), store.listDir("absent/"));
        assertEquals(List.of(), store.listPrefix("absent/x"));
        assertEquals(List.of(), store.listDir("a/zarr.json")); // a key, not a directory
    }

    @Test
    void aPageThatIsNotHtmlFails() {
        hierarchy();
        contentType = "application/json";
        assertThrows(ZarrException.class, () -> listing().listDir(""));
    }

    @Test
    void prefixesMustBeKeys() {
        HttpStore store = listing();
        assertThrows(IllegalArgumentException.class, () -> store.listDir("../"));
        assertThrows(IllegalArgumentException.class, () -> store.listDir("a//b"));
        assertThrows(IllegalArgumentException.class, () -> store.listPrefix("/x"));
    }

    /** A redirected listing's links resolve against the page it lands on. */
    @Test
    void linksResolveAgainstTheRedirectedPage() {
        hierarchy();
        moved = "g/";
        HttpStore store = listing();
        assertEquals(backing.listDir("g/"), store.listDir("g/"));
        assertTrue(requests.contains("GET /moved/g/"), requests::toString);
    }

    @Test
    void aDirectoryLinkedIntoItselfFailsInsteadOfWalkingForever() {
        hierarchy();
        loop = true;
        HttpStore store = listing();
        assertEquals(List.of("g/größe & más/", "g/loop/", "g/sub/", "g/zarr.json"), store.listDir("g/"));
        ZarrException e = assertThrows(ZarrException.class, () -> store.listPrefix("g/sub/"));
        assertTrue(e.getMessage().contains("more than " + HttpStore.MAX_LISTING_DEPTH), e.getMessage());
        assertTrue(requests.size() < HttpStore.MAX_LISTING_DEPTH + 20, "requests: " + requests.size());
    }

    @Test
    void theRootServedAtAHostsTopLevel() {
        mount = "";
        base = base.substring(0, base.indexOf("/data/store"));
        backing.set("zarr.json", "{}".getBytes(StandardCharsets.UTF_8));
        backing.set("x/zarr.json", "{}".getBytes(StandardCharsets.UTF_8));
        style = Style.APACHE; // its parent link from the root is "/" itself
        assertEquals(List.of("x/", "zarr.json"), listing().listDir(""));
        assertEquals(List.of("x/zarr.json", "zarr.json"), listing().list());
    }
}
