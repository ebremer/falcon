package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Attribute reading (compact storage), including vlen strings via the global heap. */
class AttributeTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("attributes.h5"));
    }

    @AfterAll
    static void close() {
        if (h5 != null) {
            h5.close();
        }
    }

    @Test
    void rootStringAttribute() {
        assertEquals("hello", h5.root().attribute("title").orElseThrow().readString());
    }

    @Test
    void rootScalarIntAttribute() {
        assertArrayEquals(new int[] {3}, (int[]) h5.root().attribute("version").orElseThrow().read());
    }

    @Test
    void datasetStringAttribute() {
        assertEquals("meters", h5.root().dataset("data").attribute("units").orElseThrow().readString());
    }

    @Test
    void datasetFloatArrayAttribute() {
        assertArrayEquals(new double[] {1.5, 2.5, 3.5},
                h5.root().dataset("data").attribute("scale").orElseThrow().readDoubles());
    }

    @Test
    void groupLongAttribute() {
        assertArrayEquals(new long[] {42},
                h5.root().group("grp").attribute("count").orElseThrow().readLongs());
    }

    @Test
    void attributeNamesAndShape() {
        Set<String> names = h5.root().attributes().stream()
                .map(Attribute::name).collect(Collectors.toSet());
        assertEquals(Set.of("title", "version"), names);

        Attribute scale = h5.root().dataset("data").attribute("scale").orElseThrow();
        assertEquals(1, scale.dataspace().rank());
        assertArrayEquals(new long[] {3}, scale.dataspace().dimensions());
    }
}
