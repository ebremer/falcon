package com.ebremer.falcon.hdf5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A root group whose Symbol Table message is corrupt (P1 C2): libhdf5 mends it, when it opens the file for
 * writing, from the copy of the message's two addresses that a version 0&ndash;1 superblock keeps in its
 * root symbol table entry ({@code H5G__stab_valid}). Falcon reads through that copy, and mends the message
 * when it changes the file. {@code corrupt_stab.h5} is {@code old_style_groups.h5}'s tree with both of the
 * message's addresses 0, as in the HDF5 library's {@code corrupt_stab_msg.h5}.
 */
class SymbolTableRepairTest {

    // A version-0 superblock's root symbol table entry is at 56: the link name's offset, the object header's
    // address, the cache type (72), four reserved bytes, and the scratch pad (80): B-tree, then local heap.
    private static final int HEADER = 64;
    private static final int CACHE_TYPE = 72;
    private static final int CACHED = 80;

    private static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Fixtures.path(name));
    }

    private static ByteBuffer le(byte[] file) {
        return ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Where the body of the root group's Symbol Table message is: its B-tree's address, then its heap's. */
    private static int message(byte[] file) {
        ByteBuffer b = le(file);
        int header = (int) b.getLong(HEADER);
        assertEquals(1, file[header], "a version-1 object header");
        java.util.ArrayDeque<int[]> chunks = new java.util.ArrayDeque<>(); // each: where its messages start, and end
        chunks.add(new int[] {header + 16, header + 16 + b.getInt(header + 8)});
        while (!chunks.isEmpty()) {
            int[] chunk = chunks.poll();
            for (int p = chunk[0]; p + 8 <= chunk[1]; p += 8 + b.getShort(p + 2)) {
                if (b.getShort(p) == 0x11) {
                    return p + 8;
                }
                if (b.getShort(p) == 0x10) { // a continuation: the address and the length of another chunk
                    chunks.add(new int[] {(int) b.getLong(p + 8), (int) (b.getLong(p + 8) + b.getLong(p + 16))});
                }
            }
        }
        throw new AssertionError("the root group has no Symbol Table message");
    }

    /** Every object's path, kind, shape, and bytes. */
    private static String describe(Hdf5File h5) {
        StringBuilder out = new StringBuilder();
        describe(h5.root(), out);
        return out.toString();
    }

    private static void describe(Hdf5Object object, StringBuilder out) {
        out.append(object.path()).append(object.isGroup() ? " group" : "").append('\n');
        if (object instanceof Group group) {
            for (String name : group.childNames().stream().sorted().toList()) {
                describe(group.child(name).orElseThrow(), out);
            }
        } else if (object instanceof Dataset dataset) {
            out.append("  ").append(Arrays.toString(dataset.dataspace().dimensions())).append(' ')
                    .append(Arrays.toString(dataset.readRawBytes())).append('\n');
        }
    }

    @Test
    void theFixtureIsCorruptAsLibhdf5sIs() throws IOException {
        byte[] file = fixture("corrupt_stab.h5");
        ByteBuffer b = le(file);
        assertEquals(0, file[8], "a version-0 superblock");
        assertEquals(1, b.getInt(CACHE_TYPE), "the root entry caches a symbol table");
        int message = message(file);
        assertEquals(0, b.getLong(message));
        assertEquals(0, b.getLong(message + 8));
        assertEquals("TREE", new String(file, (int) b.getLong(CACHED), 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("HEAP", new String(file, (int) b.getLong(CACHED + 8), 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void readsTheRootGroupThroughTheSuperblocksCopy() throws IOException {
        try (Hdf5File corrupt = Hdf5File.open(Fixtures.path("corrupt_stab.h5"));
             Hdf5File whole = Hdf5File.open(Fixtures.path("old_style_groups.h5"))) {
            assertEquals(List.of("alpha", "empty", "root_ds"), corrupt.root().childNames());
            assertArrayEquals(new int[] {0, 1, 2, 3}, corrupt.root().dataset("root_ds").readInts());
            assertEquals(describe(whole), describe(corrupt));
        }
    }

    /** A lookup by name walks the B-tree before the links are listed: that is mended too. */
    @Test
    void findsANameThroughTheSuperblocksCopy() throws IOException {
        try (Hdf5File h5 = Hdf5File.open(fixture("corrupt_stab.h5"))) {
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, h5.root().dataset("alpha/beta/gamma").readInts());
            assertTrue(h5.root().child("absent").isEmpty());
            assertTrue(h5.root().link("empty").isPresent());
        }
    }

    /** Each address is checked and mended on its own, whatever is wrong with it. */
    @Test
    void mendsEitherAddressAlone() throws IOException {
        String whole;
        try (Hdf5File h5 = Hdf5File.open(Fixtures.path("old_style_groups.h5"))) {
            whole = describe(h5);
        }
        byte[] original = fixture("corrupt_stab.h5");
        long btree = le(original).getLong(CACHED);
        long heap = le(original).getLong(CACHED + 8);
        long[][] messages = {
            {btree, 0},               // only the heap's address is wrong
            {0, heap},                // only the B-tree's
            {heap, btree},            // each points at the other: something is there, but not what is wanted
            {-1, -1},                 // undefined addresses
            {1L << 40, original.length}, // outside the file, and at its very end
            {btree + 1, heap - 1},
        };
        for (long[] addresses : messages) {
            byte[] file = original.clone();
            int message = message(file);
            le(file).putLong(message, addresses[0]).putLong(message + 8, addresses[1]);
            try (Hdf5File h5 = Hdf5File.open(file)) {
                assertEquals(whole, describe(h5), Arrays.toString(addresses));
            }
        }
    }

    /** A message that is good is what is read, whatever the superblock's copy says. */
    @Test
    void aGoodMessageIsNotReplaced() throws IOException {
        byte[] file = fixture("old_style_groups.h5");
        String whole;
        try (Hdf5File h5 = Hdf5File.open(file.clone())) {
            whole = describe(h5);
        }
        ByteBuffer b = le(file);
        assertEquals(1, b.getInt(CACHE_TYPE));
        long btree = b.getLong(CACHED);
        b.putLong(CACHED, b.getLong(CACHED + 8)).putLong(CACHED + 8, btree); // a copy that points at the wrong things
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(whole, describe(h5));
        }
    }

    /** Without a copy that is good, the group fails as it did: a typed exception that names what is missing. */
    @Test
    void aMessageTheSuperblockCannotMendStillFails() throws IOException {
        byte[] uncached = fixture("corrupt_stab.h5");
        le(uncached).putInt(CACHE_TYPE, 0);
        try (Hdf5File h5 = Hdf5File.open(uncached)) {
            HdfFormatException e = assertThrows(HdfFormatException.class, () -> h5.root().links());
            assertTrue(e.getMessage().contains("'HEAP' at 0"), e.getMessage());
            assertThrows(HdfFormatException.class, () -> h5.root().child("alpha"));
        }
        byte[] badCopy = fixture("corrupt_stab.h5");
        le(badCopy).putLong(CACHED, 8).putLong(CACHED + 8, 16);
        try (Hdf5File h5 = Hdf5File.open(badCopy)) {
            assertThrows(HdfFormatException.class, () -> h5.root().links());
        }
        byte[] halfCopy = fixture("corrupt_stab.h5");
        le(halfCopy).putLong(CACHED, 8); // the heap is found, the B-tree is not
        try (Hdf5File h5 = Hdf5File.open(halfCopy)) {
            HdfFormatException e = assertThrows(HdfFormatException.class, () -> h5.root().links());
            assertTrue(e.getMessage().contains("'TREE' at 0"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ changing such a file

    /** Changing the file mends the message in it, as libhdf5 does, and keeps the superblock's copy. */
    @Test
    void theWriterMendsTheMessageWhenItChangesTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("mended.h5");
        Files.copy(Fixtures.path("corrupt_stab.h5"), file);
        byte[] before = Files.readAllBytes(file);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.dataset("root_ds").stringAttribute("note", "changed"); // the root group's links stay as they are
        }
        byte[] after = Files.readAllBytes(file);
        int message = message(after);
        assertArrayEquals(Arrays.copyOfRange(before, CACHED, CACHED + 16), Arrays.copyOfRange(after, CACHED, CACHED + 16),
                "the superblock's copy is as it was");
        assertArrayEquals(Arrays.copyOfRange(after, CACHED, CACHED + 16), Arrays.copyOfRange(after, message, message + 16),
                "the message says what the superblock's copy says");
        le(after).putInt(CACHE_TYPE, 0); // the message alone now finds the group
        try (Hdf5File h5 = Hdf5File.open(after)) {
            assertEquals(List.of("alpha", "empty", "root_ds"), h5.root().childNames());
            assertEquals("changed", h5.root().dataset("root_ds").attribute("note").orElseThrow().readString());
            assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, h5.root().dataset("alpha/beta/gamma").readInts());
        }
    }

    /**
     * A change that does not touch the root group's links at all: the writer used to write the message's bad
     * addresses over the superblock's copy of them, the only good one, and the root group was lost.
     */
    @Test
    void theWriterKeepsTheSuperblocksCopyWhenTheRootGroupsLinksStay(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kept.h5");
        Files.copy(Fixtures.path("corrupt_stab.h5"), file);
        byte[] before = Files.readAllBytes(file);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.root().stringAttribute("title", "changed");
        }
        byte[] after = Files.readAllBytes(file);
        int message = message(after);
        assertArrayEquals(Arrays.copyOfRange(before, CACHED, CACHED + 16), Arrays.copyOfRange(after, CACHED, CACHED + 16));
        assertArrayEquals(Arrays.copyOfRange(before, CACHED, CACHED + 16), Arrays.copyOfRange(after, message, message + 16));
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(List.of("alpha", "empty", "root_ds"), h5.root().childNames());
            assertEquals("changed", h5.root().attribute("title").orElseThrow().readString());
        }
    }

    /** Adding to the root group writes it a new symbol table, which the message and the copy both name. */
    @Test
    void theWriterAddsToARootGroupWhoseMessageWasCorrupt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("added.h5");
        Files.copy(Fixtures.path("corrupt_stab.h5"), file);
        byte[] before = Files.readAllBytes(file);
        try (Hdf5Writer w = Hdf5Writer.open(file)) {
            w.intDataset("added", new int[] {7, 8, 9}, new long[] {3});
            w.delete("empty");
        }
        byte[] after = Files.readAllBytes(file);
        int message = message(after);
        assertArrayEquals(Arrays.copyOfRange(after, CACHED, CACHED + 16), Arrays.copyOfRange(after, message, message + 16));
        assertFalse(Arrays.equals(Arrays.copyOfRange(before, CACHED, CACHED + 16), Arrays.copyOfRange(after, CACHED, CACHED + 16)),
                "a new symbol table");
        try (Hdf5File h5 = Hdf5File.open(file)) {
            assertEquals(List.of("added", "alpha", "root_ds"), h5.root().childNames());
            assertArrayEquals(new int[] {7, 8, 9}, h5.root().dataset("added").readInts());
            assertArrayEquals(new int[] {0, 1, 2, 3}, h5.root().dataset("root_ds").readInts());
            assertEquals(List.of("beta", "delta"), h5.root().group("alpha").childNames());
        }
    }
}
