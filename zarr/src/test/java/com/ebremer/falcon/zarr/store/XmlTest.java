package com.ebremer.falcon.zarr.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.zarr.ZarrException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The small XML reader {@link S3Store} reads listings and errors with. */
class XmlTest {

    private static Xml.Element parse(String xml) {
        return Xml.parse(xml.getBytes(StandardCharsets.UTF_8), "a test document");
    }

    @Test
    void readsAnS3Listing() {
        Xml.Element root = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>bucket</Name><Prefix/>
                  <IsTruncated>true</IsTruncated>
                  <Contents><Key>a b</Key><Size>3</Size></Contents>
                  <Contents><Key>c</Key><Size>0</Size></Contents>
                  <CommonPrefixes><Prefix>d/</Prefix></CommonPrefixes>
                  <NextContinuationToken>tok==</NextContinuationToken>
                </ListBucketResult>
                """);
        assertEquals("ListBucketResult", root.localName());
        assertEquals("bucket", root.childText("Name"));
        assertEquals("", root.childText("Prefix"));
        assertNull(root.childText("Delimiter"));
        assertEquals(List.of("a b", "c"), root.children("Contents").stream().map(e -> e.childText("Key")).toList());
        assertEquals("d/", root.child("CommonPrefixes").childText("Prefix"));
        assertEquals("tok==", root.childText("NextContinuationToken"));
    }

    @Test
    void readsReferencesCdataCommentsAndPrefixes() {
        Xml.Element root = parse("\uFEFF<?xml version='1.0'?><!-- a comment --><s3:Error a='1' b=\"&amp;2\">"
                + "<s3:Code>x&lt;y&gt;z&amp;&quot;&apos;</s3:Code><Message>caf&#233; &#xE9; &#x1F600;"
                + "<![CDATA[<raw & text>]]></Message><?pi data?><Empty/></s3:Error><!-- after -->\n");
        assertEquals("s3:Error", root.name());
        assertEquals("Error", root.localName());
        assertEquals("x<y>z&\"'", root.childText("Code"));
        assertEquals("caf\u00E9 \u00E9 \uD83D\uDE00<raw & text>", root.childText("Message"));
        assertEquals("", root.childText("Empty"));
        assertEquals(3, root.children().size());
    }

    @Test
    void malformedDocumentsAreZarrExceptions() {
        String[] bad = {
            "",
            "   ",
            "text only",
            "<a>",
            "<a></b>",
            "<a><b></a></b>",
            "</a>",
            "<a/><b/>",
            "<a/>trailing",
            "<a>&unknown;</a>",
            "<a>& </a>",
            "<a>&#0;</a>",
            "<a>&#xD800;</a>",
            "<a>&#x110000;</a>",
            "<a>&#xZZ;</a>",
            "<a b=c/>",
            "<a b='<'/>",
            "<a b='x/>",
            "<a b='1'c='2'/>",
            "<1a/>",
            "<a",
            "<a><!-- never closed</a>",
            "<a><![CDATA[never closed</a>",
            "<?xml never closed",
            "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;\">]><a>&lol2;</a>",
        };
        for (String xml : bad) {
            assertThrows(ZarrException.class, () -> parse(xml), xml);
        }
        ZarrException e = assertThrows(ZarrException.class,
                () -> Xml.parse(new byte[] {'<', 'a', '>', (byte) 0xC3, '<', '/', 'a', '>'}, "bytes"));
        assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
    }

    @Test
    void deepNestingIsRefusedWithoutOverflowingTheStack() {
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < Xml.MAX_DEPTH; i++) {
            ok.append("<e>");
        }
        for (int i = 0; i < Xml.MAX_DEPTH; i++) {
            ok.append("</e>");
        }
        assertEquals("e", parse(ok.toString()).name());

        String deep = "<e>".repeat(200_000) + "</e>".repeat(200_000);
        ZarrException e = assertThrows(ZarrException.class, () -> parse(deep));
        assertTrue(e.getMessage().contains("deeper"), e.getMessage());
    }
}
