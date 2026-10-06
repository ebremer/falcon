package com.ebremer.falcon.zarr.store;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the names in a directory listing: the HTML page a static file server makes for a directory's URL,
 * as Python's {@code http.server}, nginx's {@code autoindex}, and Apache's {@code mod_autoindex} do. Like
 * fsspec's HTTP filesystem, it takes the page's links ({@code <a href>}) as the directory's entries; unlike
 * it, only links to a name directly below the directory count, so the page's other links (a column's sort
 * order, the parent directory, another site) do not.
 */
final class DirectoryListing {

    /** An anchor's {@code href}, quoted with either quote or not at all; case and attribute order vary. */
    private static final Pattern HREF = Pattern.compile(
            "<a\\s(?:[^>]*?\\s)?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern CHARACTER_REFERENCE = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]+);");

    private DirectoryListing() {
    }

    /** Whether a {@code Content-Type} is HTML, as a listing page is. */
    static boolean isHtml(String contentType) {
        String type = mediaType(contentType);
        return type.equals("text/html") || type.equals("application/xhtml+xml");
    }

    /** The page's character set, from its {@code Content-Type}; UTF-8 if none is given or it is unknown. */
    static Charset charset(String contentType) {
        if (contentType != null) {
            for (String parameter : contentType.split(";")) {
                String p = parameter.trim();
                if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                    String name = p.substring(8).trim().replace("\"", "");
                    try {
                        return Charset.forName(name);
                    } catch (IllegalArgumentException unknown) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The names below {@code dir} that the listing page links to: keys ({@code dir + name}) and child
     * prefixes ({@code dir + name + "/"}), sorted.
     *
     * @param html  the page
     * @param page  the page's URL, after any redirect: relative links resolve against it
     * @param dir   the store prefix the page lists: empty, or ending in {@code '/'}
     * @param query the store's query, which a link may carry too; or null
     */
    static Set<String> names(String html, URI page, String dir, String query) {
        List<String> directory = segments(page.getRawPath());
        if (directory == null) {
            return Set.of();
        }
        if (!directory.isEmpty()) {
            directory.removeLast(); // the page's own name, or the empty one after its trailing '/'
        }
        Set<String> names = new TreeSet<>();
        Matcher m = HREF.matcher(COMMENT.matcher(html).replaceAll(""));
        while (m.find()) {
            String href = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String name = name(unescapeHtml(href.trim()), page, directory, query);
            if (name != null) {
                names.add(dir + name);
            }
        }
        return names;
    }

    /**
     * The name a link gives directly below {@code directory} (the page's path, as decoded segments), with a
     * trailing {@code '/'} for a directory; or null if it gives none.
     */
    private static String name(String href, URI page, List<String> directory, String query) {
        URI link;
        try {
            link = page.resolve(new URI(encodeIllegal(href)));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
        if (link.getRawFragment() != null || (link.getRawQuery() != null && !link.getRawQuery().equals(query))) {
            return null; // a sort order ("?C=N;O=D"), an anchor on the page
        }
        if (!HttpIo.origin(link).equals(HttpIo.origin(page))) {
            return null;
        }
        List<String> path = segments(link.getRawPath());
        if (path == null || path.size() < directory.size() + 1 || !path.subList(0, directory.size()).equals(directory)) {
            return null; // the parent, or elsewhere
        }
        List<String> rest = path.subList(directory.size(), path.size());
        boolean isDirectory = rest.size() == 2 && rest.get(1).isEmpty();
        if (rest.size() != 1 && !isDirectory) {
            return null; // further down
        }
        String name = rest.get(0);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0) {
            return null;
        }
        try {
            StoreKeys.validate(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return isDirectory ? name + "/" : name;
    }

    /**
     * A raw URL path's segments, each percent-decoded as UTF-8 ({@code "/a/b%20c/"} is {@code [a, b c, ""]}):
     * compared so, a server's choice of what to escape does not matter. Null if one is not valid UTF-8.
     */
    private static List<String> segments(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        String[] parts = (rawPath.startsWith("/") ? rawPath.substring(1) : rawPath).split("/", -1);
        for (String part : parts) {
            String decoded = percentDecode(part);
            if (decoded == null) {
                return null;
            }
            out.add(decoded);
        }
        return out;
    }

    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && isHex(s, i + 1) && isHex(s, i + 2)) {
                bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
                i++;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean isHex(String s, int i) {
        return i < s.length() && Character.digit(s.charAt(i), 16) >= 0;
    }

    /**
     * {@code href} with what a URI may not hold as it is percent-encoded: spaces, non-ASCII characters (a
     * server may leave a UTF-8 name unescaped), and a {@code '%'} that does not begin an escape.
     */
    private static String encodeIllegal(String href) {
        StringBuilder out = new StringBuilder(href.length());
        for (int i = 0; i < href.length(); i++) {
            char c = href.charAt(i);
            boolean escape = c == '%' && !(isHex(href, i + 1) && isHex(href, i + 2));
            if (escape || c <= ' ' || c >= 0x7F || "\"<>\\^`{|}".indexOf(c) >= 0) {
                int cp = href.codePointAt(i);
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
                i += Character.charCount(cp) - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Resolves HTML character references: {@code &amp;}, {@code &#39;}, {@code &#x27;}, and the like. */
    static String unescapeHtml(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = CHARACTER_REFERENCE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            String replacement;
            if (ref.startsWith("#x") || ref.startsWith("#X")) {
                replacement = codePoint(Integer.parseInt(ref.substring(2), 16), m.group());
            } else if (ref.startsWith("#")) {
                replacement = codePoint(Integer.parseInt(ref.substring(1)), m.group());
            } else {
                replacement = switch (ref) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    case "nbsp" -> "\u00A0";
                    default -> m.group();
                };
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String codePoint(int cp, String reference) {
        return Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : reference;
    }
}
