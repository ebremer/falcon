package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.hdf5.datatype.Datatype;
import com.ebremer.falcon.hdf5.header.MessageType;
import com.ebremer.falcon.hdf5.io.HdfBuffer;
import com.ebremer.falcon.hdf5.layout.DataLayout;
import com.ebremer.falcon.hdf5.layout.DataLayoutMessage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The writer's edge cases that once produced files libhdf5 rejects or misreads while still passing
 * Falcon's own round trip: empty datasets, many vlen elements, oversized messages, bad names, typed fill
 * values, non-ASCII strings, out-of-range n-bit values, and the earliest format's message versions.
 * {@link WriterInteropExport} checks the same cases against libhdf5.
 */
class WriterEdgeCaseTest {

    @TempDir
    Path dir;

    @Test
    void emptyDatasetsHaveNoStorage() throws IOException {
        Path file = dir.resolve("empty.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.intDataset("none", new int[0], new long[] {0});
            w.intDataset("rows", new int[0], new long[] {3, 0});
            w.stringDataset("strings", new String[0], new long[] {0});
            w.intChunkedDataset("chunked", new int[0], new long[] {0}, new long[] {4});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            for (String name : List.of("none", "rows", "chunked")) {
                assertEquals(0, h5.root().dataset(name).readInts().length, name);
            }
            assertArrayEquals(new long[] {3, 0}, h5.root().dataset("rows").dataspace().dimensions());
            assertEquals(0, h5.root().dataset("strings").readStrings().length);
            for (String name : List.of("none", "strings")) {
                Dataset ds = h5.root().dataset(name);
                var layout = (DataLayout.Contiguous) DataLayoutMessage.parse(ds.ctx,
                        ds.header().find(MessageType.DATA_LAYOUT).bodyOffset());
                assertEquals(HdfBuffer.UNDEFINED_ADDRESS, layout.address(), name);
            }
        }
    }

    @Test
    void vlenDataBeyondOneHeapCollection() throws IOException {
        Path file = dir.resolve("vlen.h5");
        String[] many = new String[70_000];
        for (int i = 0; i < many.length; i++) {
            many[i] = "s" + i;
        }
        String big = "x".repeat(3 << 20);
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.stringDataset("many", many, new long[] {many.length});
            w.stringDataset("big", new String[] {"a", big, "b", ""}, new long[] {4});
            w.intSequenceDataset("rows", new long[] {2}, new int[][] {{1, 2}, {}});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(many, h5.root().dataset("many").readStrings());
            assertArrayEquals(new String[] {"a", big, "b", ""}, h5.root().dataset("big").readStrings());
            assertArrayEquals(new int[][] {{1, 2}, {}}, h5.root().dataset("rows").readVlenInts());
        }
    }

    @Test
    void smallVlenDatasetsShareOneCollection() throws IOException {
        Path file = dir.resolve("shared.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            for (int i = 0; i < 4; i++) {
                w.stringDataset("s" + i, new String[] {"a", "b"}, new long[] {2});
            }
        }
        assertTrue(Files.size(file) < 2 * 4096, "four tiny vlen datasets cost " + Files.size(file) + " bytes");
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(new String[] {"a", "b"}, h5.root().dataset("s3").readStrings());
        }
    }

    @Test
    void messagesStayUnder64KiB() throws IOException {
        Path file = dir.resolve("limits.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.byteDataset("compact_max", new byte[65524], new long[] {65524}).compact();
            assertThrows(IllegalStateException.class,
                    () -> w.byteDataset("compact_over", new byte[65525], new long[] {65525}).compact());
            w.intDataset("big_attr", new int[] {1}, new long[] {1})
                    .doubleAttribute("a", new double[8180], new long[] {8180});
            assertThrows(IllegalArgumentException.class, () -> w.intDataset("huge_attr", new int[] {1}, new long[] {1})
                    .doubleAttribute("a", new double[8190], new long[] {8190}));
            // Nine attributes go dense; one larger than libhdf5's 4 KiB managed-object default.
            Hdf5Writer.DatasetWriter dense = w.intDataset("dense", new int[] {1}, new long[] {1});
            for (int i = 0; i < 8; i++) {
                dense.intAttribute("small" + i, new int[] {i}, new long[] {});
            }
            dense.doubleAttribute("wide", range(1000), new long[] {1000});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(65524, h5.root().dataset("compact_max").readRawBytes().length);
            assertEquals(8180, h5.root().dataset("big_attr").attribute("a").orElseThrow().readDoubles().length);
            assertArrayEquals(range(1000), h5.root().dataset("dense").attribute("wide").orElseThrow().readDoubles());
            assertEquals(9, h5.root().dataset("dense").attributes().size());
        }
    }

    @Test
    void namesAreValidatedWhenAdded() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("names.h5"))) {
            for (String bad : new String[] {"", ".", "a/b", "nul\0"}) {
                assertThrows(IllegalArgumentException.class, () -> w.group(bad), bad);
                assertThrows(IllegalArgumentException.class, () -> w.intDataset(bad, new int[] {1}, new long[] {1}), bad);
            }
            w.group("x");
            assertThrows(IllegalArgumentException.class, () -> w.intDataset("x", new int[] {1}, new long[] {1}));
            assertThrows(IllegalArgumentException.class, () -> w.group("x"));
            Hdf5Writer.DatasetWriter d = w.intDataset("d", new int[] {1}, new long[] {1}).intAttribute("a", new int[] {1}, new long[] {});
            assertThrows(IllegalArgumentException.class, () -> d.intAttribute("a", new int[] {2}, new long[] {}));
            assertThrows(IllegalArgumentException.class, () -> d.intAttribute("", new int[] {2}, new long[] {}));
            assertThrows(IllegalArgumentException.class, () -> Hdf5Writer.CompoundField.int32("", new int[] {1}));
            assertThrows(IllegalArgumentException.class, () -> w.compoundDataset("c", new long[] {1},
                    Hdf5Writer.CompoundField.int32("f", new int[] {1}), Hdf5Writer.CompoundField.int32("f", new int[] {2})));
            assertThrows(IllegalArgumentException.class, () -> Hdf5Writer.enumType().add("A", 0).add("A", 1));
            assertThrows(IllegalArgumentException.class, () -> Hdf5Writer.enumType().add("A", 0).add("B", 0));
        }
    }

    @Test
    void longAndNonAsciiNamesRoundTrip() throws IOException {
        String longName = "n".repeat(300);
        for (Hdf5Writer.Format format : Hdf5Writer.Format.values()) {
            Path file = dir.resolve("names-" + format + ".h5");
            try (Hdf5Writer w = Hdf5Writer.create(file, format)) {
                w.intDataset(longName, new int[] {1}, new long[] {1});
                w.group("café").intAttribute("été", new int[] {2}, new long[] {});
            }
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertArrayEquals(new int[] {1}, h5.root().dataset(longName).readInts(), format.name());
                assertEquals(2, h5.root().group("café").attribute("été").orElseThrow().readInt(), format.name());
            }
        }
    }

    @Test
    void fillValuesConvertToTheDatasetType() throws IOException {
        Path file = dir.resolve("fill.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.doubleDataset("f64", new double[] {1}, new long[] {1}).fillValue(5);
            w.floatDataset("f32", new float[] {1}, new long[] {1}).fillValue(2);
            w.intDataset("i32", new int[] {1}, new long[] {1}).fillValue(-7.0);
            w.longDataset("i64", new long[] {1}, new long[] {1}).fillValue(Long.MIN_VALUE);
            assertThrows(IllegalArgumentException.class, () -> w.intDataset("frac", new int[] {1}, new long[] {1}).fillValue(2.5));
            assertThrows(IllegalArgumentException.class, () -> w.intDataset("big", new int[] {1}, new long[] {1}).fillValue(1L << 40));
            assertThrows(IllegalArgumentException.class, () -> w.byteDataset("b", new byte[] {1}, new long[] {1}).fillValue(128));
            assertThrows(IllegalStateException.class, () -> w.stringDataset("s", new String[] {"a"}, new long[] {1}).fillValue(0));
            assertThrows(IllegalStateException.class, () -> w.referenceDataset("r", new long[] {1}, new String[] {null}).fillValue(0));
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(5.0, le(h5, "f64").getDouble());
            assertEquals(2.0f, le(h5, "f32").getFloat());
            assertEquals(-7, le(h5, "i32").getInt());
            assertEquals(Long.MIN_VALUE, le(h5, "i64").getLong());
        }
    }

    private static ByteBuffer le(Hdf5File h5, String name) {
        return ByteBuffer.wrap(h5.root().dataset(name).fillValueBytes().orElseThrow()).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void nonAsciiStringsAreUtf8() throws IOException {
        Path file = dir.resolve("utf8.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.fixedStringDataset("fixed", new String[] {"café", "日本", ""}, new long[] {3});
            w.fixedStringDataset("ascii", new String[] {"ab"}, new long[] {1});
            w.root().fixedStringDataset("cut", new String[] {"café", "abcdef"}, new long[] {2}, 4);
            w.compoundDataset("rec", new long[] {1}, Hdf5Writer.CompoundField.int32("é", new int[] {3}));
            w.enumDataset("e", new long[] {1}, Hdf5Writer.enumType().add("ÉTÉ", 1), new int[] {1});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertArrayEquals(new String[] {"café", "日本", ""}, h5.root().dataset("fixed").readStrings());
            assertEquals(Datatype.CharacterSet.UTF8, ((Datatype.StringType) h5.root().dataset("fixed").datatype()).characterSet());
            assertEquals(Datatype.CharacterSet.ASCII, ((Datatype.StringType) h5.root().dataset("ascii").datatype()).characterSet());
            assertArrayEquals(new String[] {"caf", "abcd"}, h5.root().dataset("cut").readStrings()); // never half a character
            assertEquals("é", ((Datatype.Compound) h5.root().dataset("rec").datatype()).members().getFirst().name());
            assertEquals("ÉTÉ", ((Datatype.Enumeration) h5.root().dataset("e").datatype()).members().getFirst().name());
        }
    }

    @Test
    void nbitRejectsValuesItCannotStore() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("nbit.h5"))) {
            long[] shape = {4};
            long[] chunk = {4};
            assertThrows(IllegalArgumentException.class,
                    () -> w.intChunkedDataset("neg", new int[] {1, -1, 2, 3}, shape, chunk).nbit(8));
            assertThrows(IllegalArgumentException.class,
                    () -> w.intChunkedDataset("wide", new int[] {1, 256, 2, 3}, shape, chunk).nbit(8));
            assertThrows(IllegalArgumentException.class,
                    () -> w.intChunkedDataset("negfull", new int[] {-1, 0, 0, 0}, shape, chunk).nbit(32));
            assertThrows(IllegalArgumentException.class,
                    () -> w.intChunkedDataset("fill_first", new int[] {1, 2, 3, 4}, shape, chunk).fillValue(-1).nbit(8));
            assertThrows(IllegalArgumentException.class,
                    () -> w.intChunkedDataset("fill_after", new int[] {1, 2, 3, 4}, shape, chunk).nbit(8).fillValue(300));
            w.intChunkedDataset("ok", new int[] {0, 255, 7, 8}, shape, chunk).nbit(8).fillValue(255);
        }
    }

    @Test
    void earliestFormatUsesTheOriginalMessageVersions() throws IOException {
        Path file = dir.resolve("earliest.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, Hdf5Writer.Format.EARLIEST)) {
            w.intDataset("i", new int[] {1, 2}, new long[] {2}).fillValue(9).intAttribute("a", new int[] {3}, new long[] {});
            w.doubleDataset("scalar", new double[] {2.5}, new long[] {});
            w.compoundDataset("rec", new long[] {2}, Hdf5Writer.CompoundField.int32("x", new int[] {1, 2}),
                    Hdf5Writer.CompoundField.float64("y", new double[] {0.5, 1.5}));
            w.enumDataset("e", new long[] {2}, Hdf5Writer.enumType().add("LONG_NAME_X", 0).add("B", 1), new int[] {1, 0});
            w.int32ArrayDataset("arr", new long[] {1}, new int[] {2, 2}, new int[] {1, 2, 3, 4});
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            Dataset i = h5.root().dataset("i");
            assertEquals(1, version(i, MessageType.DATASPACE));
            assertEquals(2, version(i, MessageType.FILL_VALUE));
            assertEquals(1, version(i, MessageType.ATTRIBUTE));
            assertEquals(1, version(h5.root().dataset("scalar"), MessageType.DATASPACE));
            assertEquals(1, version(h5.root().dataset("rec"), MessageType.DATATYPE) >> 4);
            assertEquals(1, version(h5.root().dataset("e"), MessageType.DATATYPE) >> 4);
            assertEquals(2, version(h5.root().dataset("arr"), MessageType.DATATYPE) >> 4);
            // ...and they read back.
            assertArrayEquals(new int[] {1, 2}, i.readInts());
            assertEquals(9, le(h5, "i").getInt());
            assertEquals(3, i.attribute("a").orElseThrow().readInt());
            assertEquals(2.5, h5.root().dataset("scalar").readDouble());
            var rec = (Datatype.Compound) h5.root().dataset("rec").datatype();
            assertEquals(List.of("x", "y"), rec.members().stream().map(Datatype.Compound.Member::name).toList());
            assertEquals(8, rec.members().get(1).offset() + 4);
            var e = (Datatype.Enumeration) h5.root().dataset("e").datatype();
            assertEquals("LONG_NAME_X", e.members().getFirst().name());
            assertArrayEquals(new int[] {2, 2}, ((Datatype.Array) h5.root().dataset("arr").datatype()).dimensions());
            assertArrayEquals(new int[] {1, 2, 3, 4}, toInts(h5.root().dataset("arr").readRawBytes()));
        }
    }

    private static int version(Dataset ds, int messageType) {
        return ds.ctx.buffer().getUnsignedByte(ds.header().find(messageType).bodyOffset());
    }

    private static int[] toInts(byte[] raw) {
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[raw.length / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = b.getInt();
        }
        return out;
    }

    private static double[] range(int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = i * 0.5;
        }
        return a;
    }

    @Test
    void failedCloseLeavesNothingAndCanBeRetried() throws IOException {
        Path file = dir.resolve("retry.h5");
        Hdf5Writer w = Hdf5Writer.create(file);
        w.referenceDataset("refs", new long[] {1}, new String[] {"/later"});
        assertThrows(IllegalArgumentException.class, w::close); // the target does not exist yet
        assertTrue(w.isOpen());
        try (var listing = Files.list(dir)) {
            // No file at the target; the data written so far stays in a hidden temporary file, for the retry.
            assertTrue(listing.allMatch(p -> p.getFileName().toString().startsWith(".retry.h5.")), "only the temporary file");
        }
        w.intDataset("later", new int[] {5}, new long[] {1});
        w.close();
        w.close();                                             // idempotent
        try (var listing = Files.list(dir)) {
            assertEquals(List.of(file), listing.toList());     // the temporary file became the target
        }
        assertTrue(!w.isOpen());
        assertThrows(HdfClosedException.class, () -> w.intDataset("more", new int[] {1}, new long[] {1}));
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(h5.root().dataset("later").objectHeaderAddress(),
                    h5.root().dataset("refs").readObjectReferences()[0].objectHeaderAddress());
        }
    }

    @Test
    void abortKeepsTheExistingFile() throws IOException {
        Path file = dir.resolve("keep.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file)) {
            w.intDataset("old", new int[] {1}, new long[] {1});
        }
        byte[] before = Files.readAllBytes(file);
        Hdf5Writer w = Hdf5Writer.create(file);
        Hdf5Writer.DatasetWriter d = w.intDataset("new", new int[] {2}, new long[] {1});
        w.abort();
        w.close();
        assertArrayEquals(before, Files.readAllBytes(file));
        assertThrows(HdfClosedException.class, () -> d.intAttribute("a", new int[] {1}, new long[] {}));
        assertThrows(HdfClosedException.class, () -> w.group("g"));
    }

    @Test
    void chunkShapesAreValidatedWhenAdded() throws IOException {
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("chunks.h5"))) {
            int[] data = new int[6];
            assertThrows(IllegalArgumentException.class, () -> w.intChunkedDataset("rank", data, new long[] {2, 3}, new long[] {2}));
            assertThrows(IllegalArgumentException.class, () -> w.intChunkedDataset("zero", data, new long[] {6}, new long[] {0}));
            assertThrows(IllegalArgumentException.class, () -> w.intChunkedDataset("scalar", new int[] {1}, new long[] {}, new long[] {}));
            assertThrows(IllegalArgumentException.class,
                    () -> w.intChunkedDataset("huge", data, new long[] {6}, new long[] {1L << 30}));
            w.intChunkedDataset("bigger_than_data", data, new long[] {6}, new long[] {8}); // libhdf5 reads this
        }
        // The earliest format writes chunked datasets too, indexed by a version-1 B-tree.
        try (Hdf5Writer w = Hdf5Writer.create(dir.resolve("chunks_old.h5"), Hdf5Writer.Format.EARLIEST)) {
            w.intChunkedDataset("c", new int[] {1, 2, 3}, new long[] {3}, new long[] {2}).deflate(3);
        }
        try (Hdf5File h5 = Hdf5File.open(dir.resolve("chunks_old.h5"))) {
            assertArrayEquals(new int[] {1, 2, 3}, h5.root().dataset("c").readInts());
        }
    }

    /** The earliest format's version-1 headers hold every attribute; its groups' B-trees grow levels (P2 WF5). */
    @Test
    void earliestFormatHoldsManyAttributesAndChildren() throws IOException {
        Path file = dir.resolve("old_limits.h5");
        try (Hdf5Writer w = Hdf5Writer.create(file, Hdf5Writer.Format.EARLIEST)) {
            Hdf5Writer.DatasetWriter d = w.intDataset("d", new int[] {1}, new long[] {1});
            for (int i = 0; i < 12; i++) {
                d.intAttribute("a" + i, new int[] {i}, new long[] {}); // v1 headers hold them all
            }
            Hdf5Writer.GroupWriter g = w.group("g");
            for (int i = 0; i < 300; i++) {
                g.intDataset("x" + i, new int[] {i}, new long[] {1});
            }
            for (int i = 300; i < 9000; i++) { // 1,125 symbol-table nodes: a group B-tree of two levels
                g.softLink("x" + i, "x" + (i % 300));
            }
        }
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(12, h5.root().dataset("d").attributes().size());
            Group g = h5.root().group("g");
            assertEquals(9000, g.childNames().size());
            for (int i : new int[] {0, 299, 300, 4567, 8999}) {
                assertArrayEquals(new int[] {i % 300}, g.dataset("x" + i).readInts(), "x" + i);
            }
        }
    }
}
