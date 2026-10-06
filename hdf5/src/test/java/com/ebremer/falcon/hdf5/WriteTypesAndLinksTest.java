package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Datatype breadth and the generic writer (P2 WF2, WF3), links and region references (WF4): what Falcon
 * writes, it reads back. (libhdf5 reads the same in {@code WriterInteropExport}.)
 */
class WriteTypesAndLinksTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void everyDatatypeRoundTrips(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("types.h5");
        Map<String, Datatype> members = new LinkedHashMap<>();
        members.put("id", Datatype.uint64());
        members.put("xy", Datatype.arrayOf(Datatype.int16().withByteOrder(ByteOrder.BIG_ENDIAN), 2));
        members.put("label", Datatype.variableString());
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            w.createDataset("u8", Datatype.uint8(), 2, 2).write(new int[] {0, 1, 254, 255});
            w.createDataset("u32", Datatype.uint32(), 2).write(new long[] {0, 0xFFFF_FFFFL});
            w.createDataset("u64", Datatype.uint64(), 2).write(new BigInteger[] {BigInteger.ONE, BigInteger.TWO.pow(64).subtract(BigInteger.ONE)});
            w.createDataset("be", Datatype.int64().withByteOrder(ByteOrder.BIG_ENDIAN), 2).write(new long[] {Long.MIN_VALUE, -1});
            w.createDataset("f16", Datatype.float16(), 2).write(new double[] {1.5, -0.25});
            w.createDataset("bool", Datatype.bool(), 3).write(new boolean[] {true, false, true});
            w.createDataset("bits", Datatype.bitField(1), 2).write(new int[] {0x81, 1});
            w.createDataset("opaque", Datatype.opaque(2, "two"), 2).write(new byte[][] {{1, 2}, {3, 4}});
            w.createDataset("time", Datatype.unixTime(4).withByteOrder(ByteOrder.BIG_ENDIAN), 1)
                    .write(new Instant[] {Instant.parse("2009-02-13T23:31:30Z")});
            w.createDataset("string", Datatype.string(5), 2).write(new String[] {"abc", "de"});
            w.createDataset("records", Datatype.compound(members), 2).write(Map.of(
                    "id", new long[] {1, 2}, "xy", new int[] {-1, 2, 3, -4}, "label", new String[] {"first", "second"}));
            w.createDataset("chunked_records", Datatype.compound(members), 3).chunked(2).deflate(1).write(Map.of(
                    "id", new long[] {5, 6, 7}, "xy", new int[] {0, 0, 1, 1, 2, 2}, "label", new String[] {"a", "", "c"}));
            if (format == Hdf5Writer.Format.LATEST) {
                w.createDataset("complex", Datatype.complexOf(Datatype.float64()), 1).write(new double[] {1, -1});
            }
            w.root().stringAttribute("units", "m/s²")
                    .attribute("range", Datatype.float32(), new long[] {2}, new double[] {-1, 1})
                    .attribute("tags", Datatype.variableString(), new long[] {2}, new String[] {"a", "bc"});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertArrayEquals(new int[] {0, 1, 254, 255}, root.dataset("u8").readInts());
            assertArrayEquals(new long[] {0, 0xFFFF_FFFFL}, root.dataset("u32").readLongs());
            assertArrayEquals(new BigInteger[] {BigInteger.ONE, BigInteger.TWO.pow(64).subtract(BigInteger.ONE)},
                    (BigInteger[]) root.dataset("u64").read());
            assertArrayEquals(new long[] {Long.MIN_VALUE, -1}, root.dataset("be").readLongs());
            assertEquals(ByteOrder.BIG_ENDIAN, ((Datatype.FixedPoint) root.dataset("be").datatype()).byteOrder());
            assertArrayEquals(new double[] {1.5, -0.25}, root.dataset("f16").readDoubles());
            assertArrayEquals(new String[] {"TRUE", "FALSE", "TRUE"}, root.dataset("bool").readStrings());
            assertArrayEquals(new int[] {0x81, 1}, root.dataset("bits").readInts());
            assertEquals("two", ((Datatype.Opaque) root.dataset("opaque").datatype()).tag());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, root.dataset("opaque").readRawBytes());
            assertArrayEquals(new Instant[] {Instant.parse("2009-02-13T23:31:30Z")}, (Instant[]) root.dataset("time").read());
            assertArrayEquals(new String[] {"abc", "de"}, root.dataset("string").readStrings());
            for (String name : List.of("records", "chunked_records")) {
                Dataset records = root.dataset(name);
                assertInstanceOf(Datatype.Compound.class, records.datatype());
            }
            assertArrayEquals(new long[] {1, 2}, root.dataset("records").member("id").readLongs());
            assertArrayEquals(new int[] {-1, 2, 3, -4}, root.dataset("records").member("xy").readInts());
            assertArrayEquals(new String[] {"first", "second"}, root.dataset("records").member("label").readStrings());
            assertArrayEquals(new String[] {"a", "", "c"}, root.dataset("chunked_records").member("label").readStrings());
            if (format == Hdf5Writer.Format.LATEST) {
                assertArrayEquals(new double[] {1, -1}, root.dataset("complex").readComplexDoubles());
            }
            assertEquals("m/s²", root.attribute("units").orElseThrow().readString());
            assertArrayEquals(new float[] {-1, 1}, root.attribute("range").orElseThrow().readFloats());
            assertArrayEquals(new String[] {"a", "bc"}, root.attribute("tags").orElseThrow().readStrings());
        }
    }

    @Test
    void valuesMustFitTheirType() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("checks.h5"))) {
            assertThrows(IllegalArgumentException.class, () -> w.createDataset("a", Datatype.int8(), 1).write(new int[] {128}));
            assertThrows(IllegalArgumentException.class, () -> w.createDataset("b", Datatype.int32(), 1).write(new double[] {0.5}));
            assertThrows(IllegalArgumentException.class, () -> w.createDataset("c", Datatype.string(2), 1).write(new String[] {"abc"}));
            assertThrows(IllegalArgumentException.class, () -> w.createDataset("d", Datatype.bool(), 1).write(new String[] {"MAYBE"}));
            assertThrows(IllegalArgumentException.class, () -> w.createDataset("e", Datatype.uint64(), 1).write(new long[] {-1}));
            Map<String, Datatype> members = new LinkedHashMap<>();
            members.put("x", Datatype.int8());
            members.put("y", Datatype.int8());
            assertThrows(IllegalArgumentException.class,
                    () -> w.createDataset("f", Datatype.compound(members), 1).write(Map.of("x", new int[] {1})));
            w.abort();
        }
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("old.h5"), Hdf5Writer.Format.EARLIEST)) {
            assertThrows(HdfUnsupportedException.class, () -> w.createDataset("c", Datatype.complexOf(Datatype.float32()), 1));
            w.abort();
        }
    }

    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void linksAndRegionReferencesRoundTrip(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("links.h5");
        boolean latest = format == Hdf5Writer.Format.LATEST;
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            w.intDataset("grid", new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, new long[] {3, 4});
            Hdf5Writer.GroupWriter g = w.group("g");
            for (int i = 0; i < 10; i++) { // dense storage in the modern format
                g.intDataset("d" + i, new int[] {i}, new long[] {1});
            }
            g.softLink("up", "/grid").softLink("sibling", "d3").softLink("dangling", "/nowhere");
            if (latest) {
                g.externalLink("ext", "target.h5", "/x");
            }
            w.regionReferenceDataset("roi", new long[] {3}, new Hdf5Writer.Region[] {
                Hdf5Writer.Region.block("/grid", new long[] {1, 2}, new long[] {2, 2}),
                Hdf5Writer.Region.points("/grid", new long[][] {{2, 3}, {0, 1}}),
                null});
        }
        if (latest) {
            try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("target.h5"))) {
                w.intDataset("x", new int[] {42}, new long[] {1});
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group g = h5.root().group("g");
            assertEquals(new Link.Soft("up", "/grid"), g.link("up").orElseThrow());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, g.dataset("up").readInts());
            assertArrayEquals(new int[] {3}, g.dataset("sibling").readInts());
            assertTrue(g.child("dangling").isEmpty());
            if (latest) {
                assertEquals(new Link.External("ext", "target.h5", "/x"), g.link("ext").orElseThrow());
                assertArrayEquals(new int[] {42}, g.dataset("ext").readInts());
            }
            Selection[] regions = h5.root().dataset("roi").readRegionReferences();
            assertArrayEquals(new int[] {6, 7, 10, 11}, regions[0].readInts());
            assertArrayEquals(new int[] {11, 1}, regions[1].readInts());
            assertNull(regions[2]);
            assertEquals("/grid", regions[0].dataset().path());
        }
    }

    /**
     * References in chunked (filtered) and compact datasets and in attributes (P2 WF8): their chunks and
     * headers are written once close() knows where the targets are, which may be added after them.
     */
    @ParameterizedTest
    @EnumSource(Hdf5Writer.Format.class)
    void referencesInChunksCompactDataAndAttributes(Hdf5Writer.Format format) throws IOException {
        Path file = dir.resolve("refs.h5");
        Map<String, Datatype> members = new LinkedHashMap<>();
        members.put("id", Datatype.int32());
        members.put("target", Datatype.objectReference());
        try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
            Hdf5Writer.DatasetWriter refs = w.createDataset("refs", Datatype.objectReference(), 0).chunked(2)
                    .maxShape(Hdf5Writer.UNLIMITED).deflate(4);
            refs.append(new String[] {"/a", "/g", null});      // targets added afterwards
            refs.append(new String[] {"/g/b", "/"});
            w.createDataset("records", Datatype.compound(members), 3).chunked(2).shuffle().write(Map.of(
                    "id", new int[] {1, 2, 3}, "target", new String[] {"/g/b", null, "/a"}));
            w.createDataset("regions", Datatype.regionReference(), 2).chunked(1).deflate(1).write(new Hdf5Writer.Region[] {
                    Hdf5Writer.Region.block("/a", new long[] {1}, new long[] {2}), Hdf5Writer.Region.all("/g/b")});
            w.createDataset("compact", Datatype.objectReference(), 2).compact().write(new String[] {"/a", "/g/b"});
            w.referenceDataset("given", new long[] {1}, new String[] {"/g"}).compact();
            w.root().attribute("self", Datatype.objectReference(), new long[0], new String[] {"/"})
                    .attribute("targets", Datatype.objectReference(), new long[] {2}, new String[] {"/a", null})
                    .attribute("region", Datatype.regionReference(), new long[] {1},
                            new Hdf5Writer.Region[] {Hdf5Writer.Region.points("/a", new long[][] {{3}})});
            Hdf5Writer.GroupWriter g = w.group("g");
            for (int i = 0; i < 10; i++) { // dense attribute storage in the modern format
                g.attribute("r" + i, Datatype.objectReference(), new long[0], new String[] {i % 2 == 0 ? "/a" : "/g"});
            }
            g.intDataset("b", new int[] {7, 8}, new long[] {2})
                    .attribute("parent", Datatype.objectReference(), new long[0], new String[] {"/g"});
            w.intDataset("a", new int[] {10, 11, 12, 13}, new long[] {4});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Group root = h5.root();
            assertArrayEquals(new String[] {"/a", "/g", null, "/g/b", "/"}, paths(root.dataset("refs").readObjectReferences()));
            assertEquals(Dataset.Layout.CHUNKED, root.dataset("refs").layout());
            assertArrayEquals(new String[] {"/g/b", null, "/a"},
                    paths(root.dataset("records").member("target").readObjectReferences()));
            Selection[] regions = root.dataset("regions").readRegionReferences();
            assertArrayEquals(new int[] {11, 12}, regions[0].readInts());
            assertArrayEquals(new int[] {7, 8}, regions[1].readInts());
            assertArrayEquals(new String[] {"/a", "/g/b"}, paths(root.dataset("compact").readObjectReferences()));
            assertEquals(Dataset.Layout.COMPACT, root.dataset("given").layout());
            assertArrayEquals(new String[] {"/g"}, paths(root.dataset("given").readObjectReferences()));
            assertArrayEquals(new String[] {"/"}, paths(root.attribute("self").orElseThrow().readObjectReferences()));
            assertArrayEquals(new String[] {"/a", null}, paths(root.attribute("targets").orElseThrow().readObjectReferences()));
            assertArrayEquals(new int[] {13}, root.attribute("region").orElseThrow().readRegionReferences()[0].readInts());
            Group g = root.group("g");
            for (int i = 0; i < 10; i++) {
                assertArrayEquals(new String[] {i % 2 == 0 ? "/a" : "/g"},
                        paths(g.attribute("r" + i).orElseThrow().readObjectReferences()), "r" + i);
            }
            assertArrayEquals(new String[] {"/g"}, paths(g.dataset("b").attribute("parent").orElseThrow().readObjectReferences()));
        }
    }

    /** A reference to an object never added fails close(), which can be called again once it is added. */
    @Test
    void aMissingReferenceTargetCanBeAddedAndCloseRetried() throws IOException {
        Path file = dir.resolve("retry.h5");
        Hdf5Writer w = Hdf5Writer.create(file);
        w.createDataset("refs", Datatype.objectReference(), 1).chunked(1).deflate(1).write(new String[] {"/late"});
        w.root().attribute("ref", Datatype.objectReference(), new long[0], new String[] {"/late"});
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, w::close);
        assertTrue(missing.getMessage().contains("/late"), missing.getMessage());
        assertTrue(w.isOpen());
        w.intDataset("late", new int[] {5}, new long[] {1});
        w.close();
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(new String[] {"/late"}, paths(h5.root().dataset("refs").readObjectReferences()));
            assertArrayEquals(new String[] {"/late"}, paths(h5.root().attribute("ref").orElseThrow().readObjectReferences()));
        }
    }

    private static String[] paths(Hdf5Object[] objects) {
        String[] paths = new String[objects.length];
        for (int i = 0; i < objects.length; i++) {
            paths[i] = objects[i] == null ? null : objects[i].path();
        }
        return paths;
    }
}
