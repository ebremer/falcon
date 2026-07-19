package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Object references: 8-byte object-header addresses resolved to navigable objects. */
class ReferenceTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("references.h5"));
    }

    @AfterAll
    static void close() {
        if (h5 != null) {
            h5.close();
        }
    }

    @Test
    void objectReferencesResolveToTheRightObjects() {
        Hdf5Object[] refs = h5.root().dataset("refs").readObjectReferences();
        assertEquals(3, refs.length);

        // refs[0] -> dataset target_a, refs[1] -> group target_g, refs[2] -> nested dataset inner.
        assertInstanceOf(Dataset.class, refs[0]);
        assertInstanceOf(Group.class, refs[1]);
        assertInstanceOf(Dataset.class, refs[2]);

        assertEquals(h5.root().dataset("target_a").objectHeaderAddress(), refs[0].objectHeaderAddress());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, ((Dataset) refs[0]).readInts());
        assertArrayEquals(new int[] {0, 1, 2}, ((Dataset) refs[2]).readInts());

        // A referenced group is fully navigable.
        assertEquals(List.of("inner"), ((Group) refs[1]).childNames());
    }

    @Test
    void objectReferenceAttributeResolves() {
        Object value = h5.root().dataset("refs").attribute("points_to").orElseThrow().read();
        Hdf5Object[] refs = assertInstanceOf(Hdf5Object[].class, value);
        assertEquals(1, refs.length);
        assertEquals(h5.root().dataset("target_a").objectHeaderAddress(), refs[0].objectHeaderAddress());
    }

    @Test
    void regionReferencesResolveToSelections() {
        Selection[] regions = h5.root().dataset("rrefs").readRegionReferences();
        assertEquals(2, regions.length);

        // 1-D: elements 1,2,3 of target_a = arange(5).
        assertEquals(h5.root().dataset("target_a").objectHeaderAddress(), regions[0].dataset().objectHeaderAddress());
        assertArrayEquals(new long[] {1}, regions[0].offset());
        assertArrayEquals(new long[] {3}, regions[0].shape());
        assertArrayEquals(new int[] {1, 2, 3}, regions[0].readInts());

        // 2-D: rows 1-2, cols 1-3 of the 4x5 grid arange(20).
        assertArrayEquals(new long[] {1, 1}, regions[1].offset());
        assertArrayEquals(new long[] {2, 3}, regions[1].shape());
        assertArrayEquals(new int[] {6, 7, 8, 11, 12, 13}, regions[1].readInts());
    }

    @Test
    void readDispatchesToReferenceResolution() {
        Object value = h5.root().dataset("refs").read();
        Hdf5Object[] refs = assertInstanceOf(Hdf5Object[].class, value);
        assertEquals(3, refs.length);
        assertTrue(refs[1].isGroup());
    }
}
