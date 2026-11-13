package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ebremer.falcon.core.checksum.Lookup3;
import com.ebremer.falcon.hdf5.datatype.Datatype;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Files closed with a metadata cache image ({@code H5Pset_mdc_image_config}; P1 C1): their metadata is in
 * the image block, not at its own addresses, and Falcon reads it from there. The fixtures are libhdf5's
 * ({@code gen_fixtures.py mdc_image}); {@code mdc_image.h5} is one that libhdf5 2.0.0's release builds
 * cannot read back, and HDF5 1.14 can.
 */
class CacheImageTest {

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Fixtures.path(name));
    }

    private static long u64(byte[] bytes, long at) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong((int) at);
    }

    /**
     * Where the body of the Metadata Cache Image message (type 0x18) is in a file's superblock extension, a
     * version-2 object header of one chunk, and where that chunk's checksum is.
     */
    private static int[] imageMessage(byte[] file, int base) {
        int extension = base + (int) u64(file, base + 20);
        assertEquals("OHDR", new String(file, extension, 4, StandardCharsets.US_ASCII));
        int flags = file[extension + 5];
        int p = extension + 6 + ((flags & 0x20) != 0 ? 16 : 0) + ((flags & 0x10) != 0 ? 4 : 0); // times, phase change
        assertEquals(0, flags & 0x03, "a one-byte chunk size");
        int end = p + 1 + (file[p] & 0xff);
        for (p++; p < end; ) {
            int size = (file[p + 1] & 0xff) | (file[p + 2] & 0xff) << 8;
            int body = p + 4 + ((flags & 0x04) != 0 ? 2 : 0);
            if (file[p] == 0x18) {
                return new int[] {body, end, extension};
            }
            p = body + size;
        }
        throw new AssertionError("no Metadata Cache Image message in the superblock extension");
    }

    /** Where a file's image block is, and its size: from the Metadata Cache Image message of its extension. */
    private static long[] imageBlock(byte[] file, int base) {
        int body = imageMessage(file, base)[0];
        return new long[] {base + u64(file, body + 1), u64(file, body + 9)};
    }

    private static int[] range(int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        return values;
    }

    /** The fixture is what it is meant to be: nothing but zeros where its root object header belongs. */
    @Test
    void theFixturesMetadataIsInTheImageOnly() throws IOException {
        byte[] file = fixture("mdc_image.h5");
        long root = u64(file, 36);
        for (int i = 0; i < 64; i++) {
            assertEquals(0, file[(int) root + i], "byte " + i + " of the root object header's place");
        }
        long[] block = imageBlock(file, 0);
        assertEquals("MDCI", new String(file, (int) block[0], 4, StandardCharsets.US_ASCII));
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertTrue(h5.hasMetadataCacheImage());
        }
        try (Hdf5File h5 = Hdf5File.open(fixture("new_style_groups.h5"))) {
            assertFalse(h5.hasMetadataCacheImage());
        }
    }

    @Test
    void readsEveryKindOfMetadataFromTheImage() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("mdc_image.h5"))) {
            Group root = h5.root();
            assertEquals(3, h5.superblockVersion());
            assertEquals(List.of("attrs", "chunked", "dense", "record", "records", "scalars", "soft", "strings"),
                    root.childNames().stream().sorted().toList());
            assertEquals("metadata cache image", root.attribute("title").orElseThrow().readString());

            // object headers, a compact dataset, and contiguous data, which is where it always was
            assertArrayEquals(range(10), root.dataset("scalars/i4").readInts());
            assertArrayEquals(new double[] {0, 0.25, 0.5, 0.75, 1}, root.dataset("scalars/f8").readDoubles());
            Dataset bigEndian = root.dataset("scalars/be_u2");
            assertArrayEquals(new long[] {2, 3}, bigEndian.dataspace().dimensions());
            assertArrayEquals(range(6), bigEndian.readInts());
            Dataset compact = root.dataset("scalars/compact");
            assertEquals(Dataset.Layout.COMPACT, compact.layout());
            assertArrayEquals(range(4), compact.readInts());

            // dense links: a fractal heap and a version-2 B-tree
            Group dense = root.group("dense");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                names.add((i % 3 == 0 ? "g" : "d") + String.format("%02d", i));
            }
            assertEquals(names.stream().sorted().toList(), dense.childNames().stream().sorted().toList());
            assertArrayEquals(new int[] {22, 22, 22}, dense.dataset("d22").readInts());
            assertTrue(dense.group("g21").childNames().isEmpty());

            // dense attributes, and a variable-length string in a global heap collection
            Dataset attrs = root.dataset("attrs");
            assertEquals(13, attrs.attributes().size());
            assertArrayEquals(new long[] {0, 1, 2, 3, 4, 5}, attrs.attribute("a05").orElseThrow().readLongs());
            assertEquals("variable-length text", attrs.attribute("text").orElseThrow().readString());
            assertArrayEquals(new float[] {0, 1, 2}, attrs.readFloats());

            // each chunk index: a fixed array, an extensible array, a version-2 B-tree, a single chunk
            Dataset fixed = root.dataset("chunked/fixed");
            assertArrayEquals(new long[] {5, 10}, fixed.chunkShape().orElseThrow());
            assertEquals(List.of(Filter.SHUFFLE, Filter.DEFLATE), fixed.filters().stream().map(Filter::id).toList());
            assertArrayEquals(range(600), fixed.readInts());
            double[] halves = new double[40];
            for (int i = 0; i < halves.length; i++) {
                halves[i] = i * 0.5;
            }
            assertArrayEquals(halves, root.dataset("chunked/extensible").readDoubles());
            assertArrayEquals(range(36), root.dataset("chunked/btree").readInts());
            assertArrayEquals(range(50), root.dataset("chunked/single").readInts());

            assertArrayEquals(new String[] {"alpha", "beta", "", "gamma delta"}, root.dataset("strings").readStrings());
            assertEquals(new Link.Soft("soft", "/scalars/i4"), root.link("soft").orElseThrow());
            assertArrayEquals(range(10), root.dataset("soft").readInts());
            assertInstanceOf(Datatype.Compound.class, root.committedType("record").datatype());
            Map<?, ?> records = (Map<?, ?>) root.dataset("records").read();
            assertArrayEquals(new int[] {1, 2, 3}, (int[]) records.get("a"));
            assertArrayEquals(new double[] {1.5, 2.5, 3.5}, (double[]) records.get("b"));
        }
    }

    /** A lookup by name reads a group's name index before its links are listed: that is in the image too. */
    @Test
    void findsObjectsByPathThroughTheImage() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("mdc_image.h5"))) {
            assertArrayEquals(new int[] {7, 7, 7}, h5.root().dataset("/dense/d07").readInts());
            assertTrue(h5.root().child("dense/absent").isEmpty());
            assertArrayEquals(new int[] {12, 13, 14}, h5.root().dataset("chunked/fixed")
                    .select(new long[] {0, 12}, new long[] {1, 3}).readInts());
        }
    }

    /** After a user block, the addresses of the image, and those in it, count from the superblock. */
    @Test
    void readsAnImageAfterAUserBlock() throws IOException {
        byte[] file = fixture("mdc_image_userblock.h5");
        assertEquals("falcon fixture", new String(file, 0, 14, StandardCharsets.US_ASCII));
        long[] block = imageBlock(file, 512);
        assertEquals("MDCI", new String(file, (int) block[0], 4, StandardCharsets.US_ASCII));
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("mdc_image_userblock.h5"))) {
            assertTrue(h5.hasMetadataCacheImage());
            assertEquals("user block", h5.root().attribute("note").orElseThrow().readString());
            assertArrayEquals(range(6), h5.root().dataset("grp/data").readInts());
            double[] values = new double[40];
            for (int i = 0; i < values.length; i++) {
                values[i] = i;
            }
            assertArrayEquals(values, h5.root().dataset("chunked").readDoubles());
        }
    }

    /** Through a reader, in the smallest pages, few of them kept: reads cross pages and entries both. */
    @Test
    void readsAnImageThroughARangeReaderInSmallPages() throws IOException {
        OpenOptions pages = OpenOptions.defaults().readerPageSize(512).readerCacheSize(2048);
        try (FileChannel channel = FileChannel.open(Fixtures.path("mdc_image.h5"), StandardOpenOption.READ);
             Hdf5File h5 = Hdf5File.open(RangeReader.of(channel), pages)) {
            assertEquals(24, h5.root().group("dense").children().size());
            assertArrayEquals(range(600), h5.root().dataset("chunked/fixed").readInts());
            assertEquals("variable-length text", h5.root().dataset("attrs").attribute("text").orElseThrow().readString());
            assertArrayEquals(new String[] {"alpha", "beta", "", "gamma delta"}, h5.root().dataset("strings").readStrings());
        }
    }

    // ------------------------------------------------------------------ images that are not what they say

    @Test
    void anImageThatFailsItsChecksumFailsTheOpen() throws IOException {
        byte[] file = fixture("mdc_image.h5");
        long[] block = imageBlock(file, 0);
        file[(int) (block[0] + block[1] / 2)] ^= 0x10;
        HdfFormatException e = assertThrows(HdfFormatException.class, () -> Hdf5File.open(file));
        assertTrue(e.getMessage().contains("metadata cache image checksum mismatch"), e.getMessage());
    }

    @Test
    void anImageThatIsNotWhereItsMessageSaysFailsTheOpen() throws IOException {
        byte[] file = fixture("mdc_image.h5");
        long[] block = imageBlock(file, 0);
        file[(int) block[0]] = 'X';
        HdfFormatException e = assertThrows(HdfFormatException.class, () -> Hdf5File.open(file));
        assertTrue(e.getMessage().contains("'MDCI'"), e.getMessage());

        // a message that gives the image another size, in an extension whose checksum is right
        byte[] resized = fixture("mdc_image.h5");
        int[] message = imageMessage(resized, 0); // its body, the extension's checksum, the extension
        ByteBuffer le = ByteBuffer.wrap(resized).order(ByteOrder.LITTLE_ENDIAN);
        le.putLong(message[0] + 9, block[1] - 8);
        le.putInt(message[1], Lookup3.hashLittle(resized, message[2], message[1] - message[2], 0));
        e = assertThrows(HdfFormatException.class, () -> Hdf5File.open(resized));
        assertTrue(e.getMessage().contains("its message says"), e.getMessage());
    }

    /** A truncated file loses the image block, which is at its end: a typed failure, not zeros read as metadata. */
    @Test
    void aFileCutShortOfItsImageFailsTheOpen() throws IOException {
        byte[] file = fixture("mdc_image.h5");
        byte[] cut = Arrays.copyOf(file, file.length - 100);
        HdfFormatException e = assertThrows(HdfFormatException.class, () -> Hdf5File.open(cut));
        assertTrue(e.getMessage().contains("lies outside"), e.getMessage());
    }

    // ------------------------------------------------------------------ changing such a file

    /** The writer leaves a file with an image alone: what it wrote at an entry's address would stay hidden. */
    @Test
    void theWriterRefusesAFileWithAnImage(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("mdc_image.h5");
        Files.copy(Fixtures.path("mdc_image.h5"), file);
        HdfUnsupportedException e = assertThrows(HdfUnsupportedException.class, () -> Hdf5Writer.open(file));
        assertTrue(e.getMessage().contains("metadata cache image"), e.getMessage());
        assertArrayEquals(fixture("mdc_image.h5"), Files.readAllBytes(file), "the file is as it was");
        Files.delete(file); // nothing holds it open
    }
}
