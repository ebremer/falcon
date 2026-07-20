package com.ebremer.falcon.zarr.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChunkKeyEncodingTest {

    private static String key(String name, String separator, long... coords) {
        return ChunkKeyEncoding.of(name, separator).encode(coords);
    }

    @Test
    void defaultEncodingPrefixesWithC() {
        assertEquals("c", key("default", "/"));                    // rank 0
        assertEquals("c/5", key("default", "/", 5));               // rank 1
        assertEquals("c/1/2", key("default", "/", 1, 2));          // rank 2
        assertEquals("c/0/0/0/0", key("default", "/", 0, 0, 0, 0)); // rank 4
    }

    @Test
    void defaultEncodingWithDotSeparator() {
        assertEquals("c", key("default", "."));
        assertEquals("c.1.2", key("default", ".", 1, 2));
        assertEquals("c.10.20.30", key("default", ".", 10, 20, 30));
    }

    @Test
    void v2EncodingHasNoPrefix() {
        assertEquals("0", key("v2", "."));                 // rank 0 -> "0"
        assertEquals("5", key("v2", ".", 5));              // rank 1
        assertEquals("1.2", key("v2", ".", 1, 2));         // rank 2 default separator
        assertEquals("1.2.3", key("v2", ".", 1, 2, 3));    // rank 3
    }

    @Test
    void v2EncodingWithSlashSeparator() {
        assertEquals("0", key("v2", "/"));
        assertEquals("1/2", key("v2", "/", 1, 2));
        assertEquals("3/1/4/1/5", key("v2", "/", 3, 1, 4, 1, 5));
    }

    @Test
    void factoryRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> ChunkKeyEncoding.of("nested", "/"));
        assertThrows(IllegalArgumentException.class, () -> ChunkKeyEncoding.of("default", "-"));
        assertThrows(IllegalArgumentException.class, () -> ChunkKeyEncoding.of("default", ""));
    }

    @Test
    void exposesNameAndSeparator() {
        ChunkKeyEncoding e = ChunkKeyEncoding.of("v2", "/");
        assertEquals("v2", e.name());
        assertEquals("/", e.separator());
        assertEquals(ChunkKeyEncoding.Kind.V2, e.kind());
    }
}
