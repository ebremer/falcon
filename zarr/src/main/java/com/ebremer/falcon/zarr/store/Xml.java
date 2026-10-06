package com.ebremer.falcon.zarr.store;

import com.ebremer.falcon.zarr.ZarrException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A small XML reader for S3's responses (a bucket listing, an error), which {@code java.base} does not
 * parse: {@code java.xml} is a module of its own. It reads elements, attributes (and ignores them), text,
 * the five predefined entities, character references ({@code &#...;}, {@code &#x...;}), CDATA sections,
 * comments, and processing instructions such as the XML declaration. A document type declaration is
 * refused, so no entity it could define is ever expanded. It is iterative, so deep nesting cannot overflow
 * the stack, and nesting deeper than {@value #MAX_DEPTH} is refused. Anything malformed is a
 * {@link ZarrException}.
 */
final class Xml {

    /** Deeper than any S3 response, which nests three or four levels. */
    static final int MAX_DEPTH = 64;

    /** An element: its name as written (a prefix included), its children, and its text. */
    static final class Element {
        private final String name;
        private final List<Element> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        Element(String name) {
            this.name = name;
        }

        /** The element's name as written, a namespace prefix included. */
        String name() {
            return name;
        }

        /** The name without its namespace prefix. */
        String localName() {
            int colon = name.indexOf(':');
            return colon < 0 ? name : name.substring(colon + 1);
        }

        /** The element's own text, its children's left out. */
        String text() {
            return text.toString();
        }

        /** The child elements, in order. */
        List<Element> children() {
            return children;
        }

        /** The children of local name {@code localName}, in order. */
        List<Element> children(String localName) {
            List<Element> out = new ArrayList<>();
            for (Element child : children) {
                if (child.localName().equals(localName)) {
                    out.add(child);
                }
            }
            return out;
        }

        /** The first child of local name {@code localName}, or {@code null}. */
        Element child(String localName) {
            for (Element child : children) {
                if (child.localName().equals(localName)) {
                    return child;
                }
            }
            return null;
        }

        /** The text of the first child of local name {@code localName}, or {@code null} if there is none. */
        String childText(String localName) {
            Element child = child(localName);
            return child == null ? null : child.text();
        }
    }

    private final String s;
    private final String what;
    private int pos;

    private Xml(String s, String what) {
        this.s = s;
        this.what = what;
    }

    /**
     * Parses a UTF-8 document.
     *
     * @param utf8 the document's bytes
     * @param what what the document is, for messages (for example {@code "the listing of bucket b"})
     * @return the root element
     * @throws ZarrException if the bytes are not well-formed UTF-8 XML of the forms this reader knows
     */
    static Element parse(byte[] utf8, String what) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException e) {
            throw new ZarrException("malformed XML in " + what + ": not UTF-8", e);
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return new Xml(text, what).document();
    }

    private Element document() {
        Deque<Element> open = new ArrayDeque<>();
        Element root = null;
        while (pos < s.length()) {
            if (s.startsWith("<?", pos)) {
                pos = after("?>", "a processing instruction");
            } else if (s.startsWith("<!--", pos)) {
                pos = after("-->", "a comment");
            } else if (s.startsWith("<![CDATA[", pos)) {
                if (open.isEmpty()) {
                    throw error("text outside the root element");
                }
                int start = pos + 9;
                pos = after("]]>", "a CDATA section");
                open.peek().text.append(s, start, pos - 3);
            } else if (s.startsWith("<!", pos)) {
                throw error("a document type declaration, which this reader does not accept");
            } else if (s.startsWith("</", pos)) {
                pos += 2;
                String name = name();
                skipSpace();
                expect('>');
                if (open.isEmpty() || !open.peek().name.equals(name)) {
                    throw error("end tag </" + name + "> does not close "
                            + (open.isEmpty() ? "any element" : "<" + open.peek().name + ">"));
                }
                open.pop();
            } else if (s.charAt(pos) == '<') {
                pos++;
                if (root != null && open.isEmpty()) {
                    throw error("a second root element");
                }
                Element element = new Element(name());
                boolean empty = attributes();
                if (open.isEmpty()) {
                    root = element;
                } else {
                    open.peek().children.add(element);
                }
                if (!empty) {
                    if (open.size() == MAX_DEPTH) {
                        throw error("elements nested deeper than " + MAX_DEPTH);
                    }
                    open.push(element);
                }
            } else {
                int end = s.indexOf('<', pos);
                if (end < 0) {
                    end = s.length();
                }
                String chunk = s.substring(pos, end);
                if (open.isEmpty()) {
                    if (!chunk.isBlank()) {
                        throw error("text outside the root element");
                    }
                } else {
                    decode(chunk, pos, open.peek().text);
                }
                pos = end;
            }
        }
        if (!open.isEmpty()) {
            throw error("element <" + open.peek().name + "> is never closed");
        }
        if (root == null) {
            throw error("no root element");
        }
        return root;
    }

    /** Reads a start tag's attributes and its end; true if it ends with {@code "/>"}. */
    private boolean attributes() {
        while (true) {
            boolean spaced = skipSpace();
            if (pos >= s.length()) {
                throw error("a start tag is never closed");
            }
            char c = s.charAt(pos);
            if (c == '>') {
                pos++;
                return false;
            }
            if (c == '/') {
                pos++;
                expect('>');
                return true;
            }
            if (!spaced) {
                throw error("expected a space, '>', or '/>' in a start tag");
            }
            name();
            skipSpace();
            expect('=');
            skipSpace();
            if (pos >= s.length() || (s.charAt(pos) != '"' && s.charAt(pos) != '\'')) {
                throw error("an attribute's value must be quoted");
            }
            char quote = s.charAt(pos++);
            int end = s.indexOf(quote, pos);
            if (end < 0) {
                throw error("an attribute's value is never closed");
            }
            if (s.indexOf('<', pos) >= 0 && s.indexOf('<', pos) < end) {
                throw error("'<' in an attribute's value");
            }
            decode(s.substring(pos, end), pos, new StringBuilder()); // checked; attributes are not used
            pos = end + 1;
        }
    }

    private String name() {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            boolean nameChar = Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '-' || c == '.'
                    || c > 0x7f;
            if (!nameChar) {
                break;
            }
            pos++;
        }
        if (pos == start) {
            throw error("expected a name");
        }
        char first = s.charAt(start);
        if (first == '-' || first == '.' || Character.isDigit(first)) {
            throw error("a name may not start with '" + first + "'");
        }
        return s.substring(start, pos);
    }

    /** Skips white space; true if there was some. */
    private boolean skipSpace() {
        int start = pos;
        while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
            pos++;
        }
        return pos > start;
    }

    private void expect(char c) {
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    /** The position just after the next {@code end}. */
    private int after(String end, String construct) {
        int at = s.indexOf(end, pos);
        if (at < 0) {
            throw error(construct + " is never closed");
        }
        return at + end.length();
    }

    /** Appends {@code chunk} to {@code out}, its entity and character references replaced. */
    private void decode(String chunk, int offset, StringBuilder out) {
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c != '&') {
                out.append(c);
                continue;
            }
            int semi = chunk.indexOf(';', i);
            if (semi < 0 || semi - i > 12) {
                pos = offset + i;
                throw error("'&' that starts no reference");
            }
            String ref = chunk.substring(i + 1, semi);
            switch (ref) {
                case "lt" -> out.append('<');
                case "gt" -> out.append('>');
                case "amp" -> out.append('&');
                case "quot" -> out.append('"');
                case "apos" -> out.append('\'');
                default -> {
                    int cp = -1;
                    try {
                        if (ref.startsWith("#x")) {
                            cp = Integer.parseInt(ref.substring(2), 16);
                        } else if (ref.startsWith("#")) {
                            cp = Integer.parseInt(ref.substring(1), 10);
                        }
                    } catch (NumberFormatException e) {
                        cp = -1;
                    }
                    if (cp <= 0 || cp > Character.MAX_CODE_POINT || (cp >= 0xD800 && cp <= 0xDFFF)) {
                        pos = offset + i;
                        throw error("unknown or invalid reference '&" + ref + ";'");
                    }
                    out.appendCodePoint(cp);
                }
            }
            i = semi;
        }
    }

    private ZarrException error(String reason) {
        return new ZarrException("malformed XML in " + what + ": " + reason + " (at character " + pos + ")");
    }
}
