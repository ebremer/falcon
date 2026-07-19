package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Shared / committed (named) datatypes: datasets and attributes that reference a type stored once. */
class CommittedDatatypeTest {

    private static Hdf5File h5;

    @BeforeAll
    static void open() throws IOException {
        h5 = Hdf5File.open(Fixtures.path("committed_types.h5"));
    }

    @AfterAll
    static void close() {
        if (h5 != null) {
            h5.close();
        }
    }

    @Test
    void datasetsShareACommittedType() {
        // Both datasets' datatype messages are shared, pointing at the one committed /itype.
        assertArrayEquals(new int[] {0, 1, 2, 3}, h5.root().dataset("a").readInts());
        assertArrayEquals(new int[] {7, 8, 9}, h5.root().dataset("b").readInts());
        assertInstanceOf(Datatype.FixedPoint.class, h5.root().dataset("a").datatype());
    }

    @Test
    void committedTypeObjectExposesItsDefinition() {
        CommittedDatatype itype = h5.root().committedType("itype");
        Datatype type = itype.datatype();
        assertInstanceOf(Datatype.FixedPoint.class, type);
        assertEquals(4, type.size());
    }

    @Test
    void committedTypeIsNotClassifiedAsADataset() {
        assertInstanceOf(CommittedDatatype.class, h5.root().child("itype").orElseThrow());
    }

    @Test
    void attributeSharesACommittedType() {
        Attribute tag = h5.root().dataset("a").attribute("tag").orElseThrow();
        assertArrayEquals(new int[] {42}, tag.readInts());
    }

    @Test
    void committedTypeInOldStyleFile() throws IOException {
        // Same tree in a v0-superblock / symbol-table / v1-object-header file: the committed type is
        // classified from its header on the old-style path too.
        try (Hdf5File old = Hdf5File.open(Fixtures.path("committed_types_old.h5"))) {
            assertArrayEquals(new int[] {0, 1, 2, 3}, old.root().dataset("a").readInts());
            assertInstanceOf(CommittedDatatype.class, old.root().child("itype").orElseThrow());
            assertArrayEquals(new int[] {42},
                    old.root().dataset("a").attribute("tag").orElseThrow().readInts());
        }
    }

    @Test
    void committedEnumTypeResolves() {
        // A committed enum type, referenced by the "colors" dataset and navigable at /etype.
        Datatype colorsType = h5.root().dataset("colors").datatype();
        assertInstanceOf(Datatype.Enumeration.class, colorsType);
        assertEquals(3, ((Datatype.Enumeration) colorsType).members().size());

        Datatype etype = h5.root().committedType("etype").datatype();
        assertInstanceOf(Datatype.Enumeration.class, etype);
    }
}
